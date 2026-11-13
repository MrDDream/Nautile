//! Against a real OpenSSH server and proxies in Docker: `scripts/ssh-it.sh --desktop` (skipped without `IT_PORT`).

use std::time::Duration;

use nautile_core::model::{ForwardKind, ProxyKind};
use nautile_net::proxy::Proxy;
use nautile_net::ssh::{self, Auth, Forward, Params, Target};
use nautile_net::{Event, Input, Prompt, Session};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

fn env(name: &str) -> Option<u16> {
    std::env::var(name).ok()?.parse().ok()
}

fn target(host: &str, port: u16, auth: Auth) -> Target {
    Target {
        host: host.into(),
        port,
        username: "nautile".into(),
        auth,
        label: host.into(),
        known: vec![],
    }
}

fn password() -> Auth {
    Auth::Password(Some("nautile".into()))
}

/// Answers the questions (host keys accepted) and gathers the output until [marker] shows up.
async fn expect(s: &mut Session, marker: &str) -> String {
    let mut out = Vec::new();
    let deadline = tokio::time::Instant::now() + Duration::from_secs(40);
    loop {
        let e = tokio::time::timeout_at(deadline, s.events.recv())
            .await
            .unwrap_or_else(|_| panic!("timeout waiting for {marker:?}: {}", String::from_utf8_lossy(&out)));
        match e {
            Some(Event::Output(d)) => out.extend(d),
            Some(Event::Prompt(Prompt::HostKey { reply, .. })) => reply.send(true),
            Some(Event::Prompt(p)) => p.cancel(),
            Some(Event::Closed(r)) => panic!("closed ({r:?}) before {marker:?}: {}", String::from_utf8_lossy(&out)),
            None => panic!("ended"),
            _ => {}
        }
        let text = String::from_utf8_lossy(&out).to_string();
        if text.contains(marker) {
            return text;
        }
    }
}

fn run(s: &Session, cmd: &str) {
    s.input.send(Input::Data(format!("{cmd}\r").into_bytes())).unwrap();
}

fn free_port() -> u16 {
    std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port()
}

async fn banner(port: u16, socks_to: Option<(&str, u16)>) -> String {
    let mut s = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    if let Some((host, p)) = socks_to {
        s.write_all(&[5, 1, 0]).await.unwrap();
        let mut two = [0u8; 2];
        s.read_exact(&mut two).await.unwrap();
        let mut req = vec![5, 1, 0, 3, host.len() as u8];
        req.extend_from_slice(host.as_bytes());
        req.extend_from_slice(&p.to_be_bytes());
        s.write_all(&req).await.unwrap();
        let mut rep = [0u8; 10];
        tokio::time::timeout(Duration::from_secs(10), s.read_exact(&mut rep))
            .await
            .expect("SOCKS")
            .unwrap();
        assert_eq!(rep[1], 0, "SOCKS reply");
    }
    let mut buf = [0u8; 8];
    tokio::time::timeout(Duration::from_secs(10), s.read_exact(&mut buf))
        .await
        .expect("banner")
        .unwrap();
    String::from_utf8_lossy(&buf).into_owned()
}

#[tokio::test(flavor = "multi_thread")]
async fn ssh_against_a_real_server() {
    let Some(port) = env("IT_PORT") else {
        eprintln!("IT_PORT not set: skipped");
        return;
    };
    let inner_host = std::env::var("IT_INNER_HOST").unwrap_or_else(|_| "sshd".into());
    let inner_port = env("IT_INNER_PORT").unwrap_or(2222);

    // Password, host key question, shell, local / dynamic / remote tunnels.
    let (local, dynamic, remote) = (free_port(), free_port(), free_port() + 1);
    let back = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let back_port = back.local_addr().unwrap().port();
    let p = Params {
        chain: vec![target("127.0.0.1", port, password())],
        forwards: vec![
            Forward {
                kind: ForwardKind::Local,
                bind_host: "127.0.0.1".into(),
                bind_port: local,
                dest_host: "127.0.0.1".into(),
                dest_port: inner_port,
            },
            Forward {
                kind: ForwardKind::Dynamic,
                bind_host: "127.0.0.1".into(),
                bind_port: dynamic,
                dest_host: String::new(),
                dest_port: 0,
            },
            Forward {
                kind: ForwardKind::Remote,
                bind_host: "127.0.0.1".into(),
                bind_port: remote,
                dest_host: "127.0.0.1".into(),
                dest_port: back_port,
            },
        ],
        startup_command: Some("echo started-$((1+1))".into()),
        ..Default::default()
    };
    let mut s = ssh::spawn(p, 100, 30);
    expect(&mut s, "started-2").await;
    run(&s, "echo nautile-$((6*7))");
    let out = expect(&mut s, "nautile-42").await;
    assert!(out.contains("nautile-42"));
    s.input.send(Input::Resize { cols: 123, rows: 40 }).unwrap();
    tokio::time::sleep(Duration::from_millis(300)).await;
    run(&s, "echo size=$(stty size)");
    expect(&mut s, "size=40 123").await;
    eprintln!("shell and resize OK");
    assert_eq!(banner(local, None).await, "SSH-2.0-");
    assert_eq!(banner(dynamic, Some(("127.0.0.1", inner_port))).await, "SSH-2.0-");
    run(&s, &format!("echo remote-ok | nc -w 2 127.0.0.1 {remote}"));
    let (mut c, _) = tokio::time::timeout(Duration::from_secs(10), back.accept()).await.unwrap().unwrap();
    let mut got = [0u8; 9];
    tokio::time::timeout(Duration::from_secs(10), c.read_exact(&mut got))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(&got, b"remote-ok");
    eprintln!("tunnels OK");

    // A key, installed with the password session.
    let key = nautile_net::keys::generate(nautile_net::keys::KeyAlgorithm::Ed25519, "it").unwrap();
    run(
        &s,
        &format!(
            "mkdir -p ~/.ssh && chmod go-w ~ && chmod 700 ~/.ssh && echo '{}' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && echo key-$((1+1))ok",
            key.public_key
        ),
    );
    expect(&mut s, "key-2ok").await;
    s.input.send(Input::Close).unwrap();

    let key_auth = Auth::Key {
        private: key.private_key.clone(),
        passphrase: None,
    };
    let mut s = ssh::spawn(
        Params {
            chain: vec![target("127.0.0.1", port, key_auth.clone())],
            ..Default::default()
        },
        80,
        24,
    );
    run(&s, "echo by-$((2+2))key");
    expect(&mut s, "by-4key").await;
    eprintln!("key OK");
    s.input.send(Input::Close).unwrap();

    // A jump host: the same server, reached again from inside its network.
    let mut s = ssh::spawn(
        Params {
            chain: vec![target("127.0.0.1", port, password()), target(&inner_host, inner_port, key_auth)],
            ..Default::default()
        },
        80,
        24,
    );
    run(&s, "echo jump-$((3+3))ed");
    expect(&mut s, "jump-6ed").await;
    eprintln!("jump OK");
    s.input.send(Input::Close).unwrap();

    // The proxies (they reach the server by its name in the Docker network).
    for (kind, port) in [(ProxyKind::Socks5, env("IT_SOCKS_PORT")), (ProxyKind::Http, env("IT_HTTP_PORT"))] {
        let Some(proxy_port) = port else { continue };
        let auth = kind == ProxyKind::Socks5;
        let mut s = ssh::spawn(
            Params {
                chain: vec![target(&inner_host, inner_port, password())],
                proxy: Some(Proxy {
                    kind,
                    host: "127.0.0.1".into(),
                    port: proxy_port,
                    username: auth.then(|| "puser".into()),
                    password: auth.then(|| "ppass".into()),
                }),
                ..Default::default()
            },
            80,
            24,
        );
        run(&s, "echo via-$((4+4))proxy");
        expect(&mut s, "via-8proxy").await;
        eprintln!("{kind:?} proxy OK");
        s.input.send(Input::Close).unwrap();
    }

    // Mosh: mosh-server started over SSH, then UDP (ports 60001-60005 published by the script).
    let mut s = nautile_net::mosh::spawn(
        Params {
            chain: vec![target("127.0.0.1", port, password())],
            ..Default::default()
        },
        80,
        24,
    );
    run(&s, "echo mosh-$((5+5))ok");
    expect(&mut s, "mosh-10ok").await;
    s.input.send(Input::Resize { cols: 91, rows: 20 }).unwrap();
    tokio::time::sleep(Duration::from_millis(500)).await;
    run(&s, "echo size=$(stty size)");
    expect(&mut s, "size=20 91").await;
    s.input.send(Input::Close).unwrap();
    eprintln!("mosh OK");

    // A wrong password ends with the reason.
    let (mut s, mut asked) = (
        ssh::spawn(
            Params {
                chain: vec![target("127.0.0.1", port, Auth::Password(Some("wrong".into())))],
                ..Default::default()
            },
            80,
            24,
        ),
        0,
    );
    loop {
        let e = tokio::time::timeout(Duration::from_secs(40), s.events.recv())
            .await
            .expect("refusal");
        match e {
            Some(Event::Prompt(Prompt::HostKey { reply, .. })) => reply.send(true),
            Some(Event::Prompt(Prompt::Secret { reply, .. })) => {
                asked += 1;
                reply.send(if asked < 2 { Some("still-wrong".into()) } else { None });
            }
            Some(Event::Closed(r)) => {
                assert!(r.is_some());
                break;
            }
            None => panic!("ended"),
            _ => {}
        }
    }
    assert_eq!(asked, 2, "asked again after a refusal");
}
