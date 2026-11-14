//! Plain HTTP only towards the local network (a NAS, Ollama on a PC, a VPN address), as Android's
//! `CleartextGuard.kt`: anywhere else passwords, API keys and terminal contents would cross the Internet
//! unencrypted. [check] runs before a request is built (nothing is sent when it fails), and the agents of
//! [agent] connect plain-HTTP requests to local addresses only (DNS answers that changed, redirects).

use std::net::{IpAddr, SocketAddr, ToSocketAddrs};

use nautile_core::trf;
use ureq::http::Uri;
use ureq::unversioned::resolver::{DefaultResolver, ResolvedSocketAddrs, Resolver};
use ureq::unversioned::transport::{DefaultConnector, NextTimeout};

/// The refusal shown for [host].
pub fn refused(host: &str) -> String {
    trf(
        "Connexion non chiffrée (http) refusée vers %1$s : utilisez https, ou une adresse du réseau local.",
        &[&host.trim_start_matches('[').trim_end_matches(']')],
    )
}

/// Loopback, private ranges (RFC 1918, IPv6 ULA), link-local and the CGNAT range VPNs such as Tailscale use.
pub fn is_private(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(a) => {
            let b = a.octets();
            a.is_loopback() || a.is_private() || a.is_link_local() || (b[0] == 100 && b[1] & 0xC0 == 64)
        }
        IpAddr::V6(a) => {
            if let Some(v4) = a.to_ipv4_mapped() {
                return is_private(IpAddr::V4(v4));
            }
            let s = a.segments();
            a.is_loopback() || s[0] & 0xFE00 == 0xFC00 || s[0] & 0xFFC0 == 0xFE80
        }
    }
}

/// Prefixes shorter than these are not a local network (a whole provider's range at most).
const MIN_PREFIX_V4: u8 = 16;
const MIN_PREFIX_V6: u8 = 48;

/// Whether [ip] is inside [net]/[prefix] (same family, prefix long enough to be one network).
pub fn in_prefix(ip: IpAddr, net: IpAddr, prefix: u8) -> bool {
    let (x, n, min): (Vec<u8>, Vec<u8>, u8) = match (ip, net) {
        (IpAddr::V4(a), IpAddr::V4(b)) => (a.octets().to_vec(), b.octets().to_vec(), MIN_PREFIX_V4),
        (IpAddr::V6(a), IpAddr::V6(b)) => (a.octets().to_vec(), b.octets().to_vec(), MIN_PREFIX_V6),
        _ => return false,
    };
    if prefix < min || usize::from(prefix) > x.len() * 8 {
        return false;
    }
    let full = usize::from(prefix / 8);
    if x[..full] != n[..full] {
        return false;
    }
    let rest = prefix % 8;
    if rest == 0 {
        return true;
    }
    let mask = 0xFFu8 << (8 - rest);
    x[full] & mask == n[full] & mask
}

/// The networks this computer is on (address and prefix length), loopback excluded.
fn own_networks() -> Vec<(IpAddr, u8)> {
    if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|i| !i.is_loopback())
        .map(|i| match i.addr {
            if_addrs::IfAddr::V4(a) => (IpAddr::V4(a.ip), a.prefixlen),
            if_addrs::IfAddr::V6(a) => (IpAddr::V6(a.ip), a.prefixlen),
        })
        .collect()
}

/// [is_private], or inside the prefix of one of this computer's own networks (a home network's global IPv6).
pub fn is_local(ip: IpAddr) -> bool {
    is_private(ip) || own_networks().into_iter().any(|(n, p)| in_prefix(ip, n, p))
}

fn is_http(uri: &Uri) -> bool {
    uri.scheme_str().is_some_and(|s| s.eq_ignore_ascii_case("http"))
}

/// Before anything is sent to [url]: https always passes; plain http only when the host has a local address
/// ([prepare] then sends it directly, never through a proxy that would carry it further unencrypted).
pub fn check(url: &str) -> Result<(), String> {
    let Ok(uri) = url.trim().parse::<Uri>() else {
        return Ok(()); // the request builder reports a malformed address
    };
    if !is_http(&uri) {
        return Ok(());
    }
    let host = uri.host().unwrap_or_default();
    let port = uri.port_u16().unwrap_or(80);
    let local = (host, port)
        .to_socket_addrs()
        .or_else(|_| format!("{host}:{port}").to_socket_addrs())
        .map(|mut a| a.any(|a| is_local(a.ip())))
        .unwrap_or(false);
    if local { Ok(()) } else { Err(refused(host)) }
}

/// [req] checked by [check], and set up to be sent by an agent of [agent]: redirects are not followed (behind
/// a proxy, a redirection to a public plain-HTTP address would never meet the resolver's check, and the request
/// body would follow it), and plain HTTP, local only, goes straight to its address, never through a proxy.
pub fn prepare<B: ureq::AsSendBody>(agent: &ureq::Agent, req: ureq::http::Request<B>) -> Result<ureq::http::Request<B>, String> {
    check(&req.uri().to_string())?;
    let http = is_http(req.uri());
    let b = agent.configure_request(req).max_redirects(0);
    Ok(if http { b.proxy(None) } else { b }.build())
}

/// The system's resolver, keeping only local addresses for a plain-HTTP target (a proxy's own address is
/// left alone: [prepare] sends plain HTTP directly, never through it).
#[derive(Debug, Default)]
struct LocalForHttp(DefaultResolver);

impl Resolver for LocalForHttp {
    fn resolve(&self, uri: &Uri, config: &ureq::config::Config, timeout: NextTimeout) -> Result<ResolvedSocketAddrs, ureq::Error> {
        let all = self.0.resolve(uri, config, timeout)?;
        let to_proxy = config.proxy().is_some_and(|p| Some(p.host()) == uri.host());
        if !is_http(uri) || to_proxy {
            return Ok(all);
        }
        let mut kept = self.empty();
        for a in all.iter().filter(|a: &&SocketAddr| is_local(a.ip())) {
            kept.push(*a);
        }
        if kept.is_empty() {
            Err(ureq::Error::Io(std::io::Error::new(
                std::io::ErrorKind::PermissionDenied,
                refused(uri.host().unwrap_or_default()),
            )))
        } else {
            Ok(kept)
        }
    }
}

/// An agent with [config] whose plain-HTTP connections go to local addresses only.
pub fn agent(config: ureq::config::Config) -> ureq::Agent {
    ureq::Agent::with_parts(config, DefaultConnector::default(), LocalForHttp::default())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(s: &str) -> IpAddr {
        s.parse().unwrap()
    }

    #[test]
    fn local_ranges_are_recognised() {
        for a in [
            "127.0.0.1",
            "10.1.2.3",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.10",
            "169.254.3.4",
            "100.64.0.1",
            "100.127.255.254",
            "::1",
            "fd12::1",
            "fc00::1",
            "fe80::1",
            "::ffff:192.168.0.1",
        ] {
            assert!(is_private(ip(a)), "{a}");
        }
        for a in [
            "8.8.8.8",
            "172.32.0.1",
            "100.128.0.1",
            "100.63.0.1",
            "2001:db8::1",
            "::ffff:1.1.1.1",
            "fec0::1",
        ] {
            assert!(!is_private(ip(a)), "{a}");
        }
    }

    #[test]
    fn prefixes_of_own_networks() {
        assert!(in_prefix(ip("2a01:e0a:1:2::99"), ip("2a01:e0a:1:2::1"), 64));
        assert!(!in_prefix(ip("2a01:e0a:1:3::99"), ip("2a01:e0a:1:2::1"), 64));
        assert!(in_prefix(ip("81.2.3.100"), ip("81.2.3.1"), 25));
        assert!(!in_prefix(ip("81.2.3.100"), ip("81.2.3.129"), 25));
        // Too wide to be a local network, or another family.
        assert!(!in_prefix(ip("81.9.9.9"), ip("81.2.3.1"), 8));
        assert!(!in_prefix(ip("2a01::1"), ip("2a01::2"), 32));
        assert!(!in_prefix(ip("::1"), ip("127.0.0.1"), 24));
    }

    #[test]
    fn plain_http_only_to_the_local_network() {
        assert!(check("https://api.example.com/v1").is_ok());
        assert!(check("http://127.0.0.1:11434/v1").is_ok());
        assert!(check("http://[::1]:8080/dav/").is_ok());
        assert!(check("http://localhost/dav").is_ok());
        let e = check("http://8.8.8.8/dav").unwrap_err();
        assert!(e.contains("8.8.8.8"), "{e}");
        assert!(check("HTTP://1.1.1.1/").is_err());
    }

    /// A server on 127.0.0.1 answering every connection with [reply]; the number of connections it got.
    fn server(reply: &'static str) -> (u16, std::sync::Arc<std::sync::atomic::AtomicUsize>) {
        use std::io::{Read, Write};
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        let hits = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let count = hits.clone();
        std::thread::spawn(move || {
            for mut c in l.incoming().flatten() {
                count.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                let mut buf = [0u8; 4096];
                let _ = c.read(&mut buf);
                let _ = c.write_all(reply.as_bytes());
            }
        });
        (port, hits)
    }

    #[test]
    fn redirects_are_not_followed_and_local_http_skips_the_proxy() {
        let (port, hits) = server("HTTP/1.1 302 Found\r\nLocation: http://1.1.1.1:9/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        // A proxy that is not there: local plain HTTP goes straight to its address instead.
        let proxy = ureq::Proxy::new("http://127.0.0.1:1").unwrap();
        let a = agent(ureq::Agent::config_builder().proxy(Some(proxy)).http_status_as_error(false).build());
        let req = ureq::http::Request::builder()
            .uri(format!("http://127.0.0.1:{port}/dav"))
            .body(Vec::new())
            .unwrap();
        let r = a.run(prepare(&a, req).unwrap()).unwrap();
        assert_eq!(r.status().as_u16(), 302, "the redirection is not followed");
        assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
        let public = ureq::http::Request::builder().uri("http://8.8.8.8/").body(Vec::new()).unwrap();
        assert!(prepare(&a, public).is_err());
    }

    #[test]
    fn the_agent_refuses_a_public_address_over_http() {
        let a = agent(ureq::Agent::config_builder().proxy(None).build());
        let req = ureq::http::Request::builder().uri("http://1.1.1.1:9/").body(Vec::new()).unwrap();
        let e = a.run(req).unwrap_err().to_string();
        assert!(e.contains("1.1.1.1"), "{e}");
    }
}
