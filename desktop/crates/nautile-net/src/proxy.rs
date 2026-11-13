//! TCP connections, possibly through a SOCKS5 or HTTP CONNECT proxy (the proxy resolves the name), and port
//! knocking. Same behaviour as Android's `SshProxy.kt` and `PortKnocker.kt`.

use std::io;
use std::time::Duration;

use base64::Engine;
use nautile_core::model::ProxyKind;
use nautile_core::{tr, trf};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};

pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

#[derive(Debug, Clone)]
pub struct Proxy {
    pub kind: ProxyKind,
    pub host: String,
    pub port: u16,
    pub username: Option<String>,
    pub password: Option<String>,
}

fn err(msg: String) -> io::Error {
    io::Error::other(msg)
}

/// Opens a TCP connection to `host:port`, through [proxy] when set.
pub async fn connect(host: &str, port: u16, proxy: Option<&Proxy>) -> io::Result<TcpStream> {
    let work = async {
        let Some(p) = proxy else {
            return TcpStream::connect((host, port)).await;
        };
        let mut s = TcpStream::connect((p.host.as_str(), p.port)).await?;
        match p.kind {
            ProxyKind::Socks5 => socks5(&mut s, p, host, port).await?,
            ProxyKind::Http => http_connect(&mut s, p, host, port).await?,
        }
        Ok(s)
    };
    let s = tokio::time::timeout(CONNECT_TIMEOUT, work)
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, tr("Délai de connexion dépassé")))??;
    let _ = s.set_nodelay(true);
    Ok(s)
}

async fn socks5(s: &mut TcpStream, p: &Proxy, host: &str, port: u16) -> io::Result<()> {
    let with_auth = p.username.as_deref().is_some_and(|u| !u.is_empty());
    s.write_all(if with_auth { &[5, 2, 0, 2] } else { &[5, 1, 0] }).await?;
    let mut two = [0u8; 2];
    s.read_exact(&mut two).await?;
    if two[0] != 5 {
        return Err(err(tr("Proxy SOCKS5 : réponse invalide")));
    }
    match two[1] {
        0 => {}
        2 => {
            let u = p.username.as_deref().unwrap_or_default().as_bytes();
            let pw = p.password.as_deref().unwrap_or_default().as_bytes();
            let mut msg = vec![1, u.len() as u8];
            msg.extend_from_slice(u);
            msg.push(pw.len() as u8);
            msg.extend_from_slice(pw);
            s.write_all(&msg).await?;
            s.read_exact(&mut two).await?;
            if two[1] != 0 {
                return Err(err(tr("Proxy SOCKS5 : identifiants refusés")));
            }
        }
        _ => return Err(err(tr("Proxy SOCKS5 : aucune méthode d'authentification acceptée"))),
    }
    let name = host.as_bytes();
    let mut req = vec![5, 1, 0, 3, name.len() as u8];
    req.extend_from_slice(name);
    req.extend_from_slice(&port.to_be_bytes());
    s.write_all(&req).await?;
    let mut head = [0u8; 4];
    s.read_exact(&mut head).await?;
    if head[0] != 5 {
        return Err(err(tr("Proxy SOCKS5 : réponse invalide")));
    }
    if head[1] != 0 {
        return Err(err(trf("Proxy SOCKS5 : connexion refusée (code %1$s)", &[&head[1]])));
    }
    let skip = match head[3] {
        1 => 4,
        4 => 16,
        3 => {
            let mut n = [0u8; 1];
            s.read_exact(&mut n).await?;
            n[0] as usize
        }
        _ => return Err(err(tr("Proxy SOCKS5 : adresse invalide"))),
    };
    let mut rest = vec![0u8; skip + 2];
    s.read_exact(&mut rest).await?;
    Ok(())
}

async fn http_connect(s: &mut TcpStream, p: &Proxy, host: &str, port: u16) -> io::Result<()> {
    let authority = if host.contains(':') {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    };
    let mut req = format!("CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\n");
    if let Some(u) = p.username.as_deref().filter(|u| !u.is_empty()) {
        let token = base64::engine::general_purpose::STANDARD.encode(format!("{u}:{}", p.password.as_deref().unwrap_or_default()));
        req.push_str(&format!("Proxy-Authorization: Basic {token}\r\n"));
    }
    req.push_str("\r\n");
    s.write_all(req.as_bytes()).await?;
    // Byte by byte, so that no SSH data past the blank line is consumed.
    let mut head = Vec::new();
    while !head.ends_with(b"\r\n\r\n") {
        let mut b = [0u8; 1];
        if s.read(&mut b).await? == 0 {
            return Err(err(tr("Proxy HTTP : connexion fermée")));
        }
        head.push(b[0]);
        if head.len() > 16_384 {
            return Err(err(tr("Proxy HTTP : réponse trop longue")));
        }
    }
    let head = String::from_utf8_lossy(&head);
    let first = head.lines().next().unwrap_or_default().trim().to_owned();
    if first.split(' ').nth(1) != Some("200") {
        return Err(err(trf("Proxy HTTP : %1$s", &[&first])));
    }
    Ok(())
}

/// One port of a knocking sequence.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Knock {
    pub port: u16,
    pub udp: bool,
}

/// `7000,8000,9000` (TCP); prefix a port with `u:` for UDP (`t:` is accepted for TCP).
pub fn parse_knocks(sequence: &str) -> Result<Vec<Knock>, String> {
    sequence
        .split([',', ' ', ';'])
        .map(str::trim)
        .filter(|t| !t.is_empty())
        .map(|token| {
            let lower = token.to_lowercase();
            let udp = lower.starts_with("u:");
            let number = lower.trim_start_matches("u:").trim_start_matches("t:");
            match number.parse::<u16>() {
                Ok(port) if port > 0 => Ok(Knock { port, udp }),
                _ => Err(trf("Port de knocking invalide : %1$s", &[&token])),
            }
        })
        .collect()
}

/// Touches each port so that a firewall (knockd…) opens the real one.
pub async fn knock(host: &str, sequence: &[Knock]) -> io::Result<()> {
    let address = tokio::net::lookup_host((host, 0))
        .await?
        .next()
        .ok_or_else(|| err(trf("Adresse introuvable : %1$s", &[&host])))?
        .ip();
    for k in sequence {
        if k.udp {
            let bind = if address.is_ipv6() { "[::]:0" } else { "0.0.0.0:0" };
            let sock = UdpSocket::bind(bind).await?;
            let _ = sock.send_to(&[0], (address, k.port)).await;
        } else {
            let _ = tokio::time::timeout(Duration::from_millis(300), TcpStream::connect((address, k.port))).await;
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn knocks_are_parsed_like_on_android() {
        assert_eq!(
            parse_knocks("7000, u:8000;T:9000").unwrap(),
            vec![
                Knock { port: 7000, udp: false },
                Knock { port: 8000, udp: true },
                Knock { port: 9000, udp: false }
            ]
        );
        assert!(parse_knocks("7000,abc").is_err());
        assert!(parse_knocks("0").is_err());
    }

    #[tokio::test]
    async fn socks5_and_http_proxies_reach_the_target() {
        use tokio::net::TcpListener;
        // A fake SOCKS5 proxy that checks the request then echoes.
        let socks = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socks_port = socks.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (mut c, _) = socks.accept().await.unwrap();
            let mut b = [0u8; 3];
            c.read_exact(&mut b).await.unwrap();
            assert_eq!(b, [5, 1, 0]);
            c.write_all(&[5, 0]).await.unwrap();
            let mut h = [0u8; 5];
            c.read_exact(&mut h).await.unwrap();
            let mut name = vec![0u8; h[4] as usize + 2];
            c.read_exact(&mut name).await.unwrap();
            assert_eq!(&name[..name.len() - 2], b"example.org");
            c.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
            c.write_all(b"SSH-2.0-test\r\n").await.unwrap();
        });
        let p = Proxy {
            kind: ProxyKind::Socks5,
            host: "127.0.0.1".into(),
            port: socks_port,
            username: None,
            password: None,
        };
        let mut s = connect("example.org", 22, Some(&p)).await.unwrap();
        let mut line = [0u8; 14];
        s.read_exact(&mut line).await.unwrap();
        assert_eq!(&line, b"SSH-2.0-test\r\n");

        let http = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_port = http.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (mut c, _) = http.accept().await.unwrap();
            let mut buf = vec![0u8; 1024];
            let n = c.read(&mut buf).await.unwrap();
            let req = String::from_utf8_lossy(&buf[..n]).to_string();
            assert!(req.starts_with("CONNECT example.org:22 HTTP/1.1\r\n"), "{req}");
            assert!(req.contains("Proxy-Authorization: Basic dTpw\r\n"));
            c.write_all(b"HTTP/1.1 200 Connection established\r\n\r\nSSH-2.0-test\r\n")
                .await
                .unwrap();
        });
        let p = Proxy {
            kind: ProxyKind::Http,
            port: http_port,
            username: Some("u".into()),
            password: Some("p".into()),
            ..p
        };
        let mut s = connect("example.org", 22, Some(&p)).await.unwrap();
        s.read_exact(&mut line).await.unwrap();
        assert_eq!(&line, b"SSH-2.0-test\r\n");
    }
}
