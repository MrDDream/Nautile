//! A machine typed in the search bar to connect to without saving it (Android's `QuickConnect`):
//! `root@192.168.1.2`, `host:2222`, `rdp://admin@pc`, `telnet://switch`, `ssh user@host -p 22`…

use crate::model::{Host, Protocol};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QuickTarget {
    pub protocol: Protocol,
    pub username: String,
    pub hostname: String,
    pub port: i32,
}

impl QuickTarget {
    /// `user@host[:port]` as typed back to the user.
    pub fn label(&self) -> String {
        let mut s = String::new();
        if !self.username.is_empty() {
            s.push_str(&self.username);
            s.push('@');
        }
        if self.hostname.contains(':') {
            s.push_str(&format!("[{}]", self.hostname));
        } else {
            s.push_str(&self.hostname);
        }
        if self.port != self.protocol.default_port() {
            s.push_str(&format!(":{}", self.port));
        }
        s
    }

    /// A host that is never saved.
    pub fn to_host(&self) -> Host {
        let mut h = Host::new(self.label(), &self.hostname);
        h.port = self.port;
        h.username = self.username.clone();
        h.protocol = self.protocol;
        h
    }

    /// The same machine with another protocol (its port follows when it was the default one).
    pub fn with_protocol(&self, p: Protocol) -> QuickTarget {
        let port = if self.port == self.protocol.default_port() {
            p.default_port()
        } else {
            self.port
        };
        QuickTarget {
            protocol: p,
            port,
            ..self.clone()
        }
    }
}

fn is_host(s: &str) -> bool {
    let b = s.as_bytes();
    !b.is_empty()
        && b[0].is_ascii_alphanumeric()
        && b[b.len() - 1].is_ascii_alphanumeric()
        && b.iter().all(|c| c.is_ascii_alphanumeric() || *c == b'.' || *c == b'-')
}

fn is_user(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|c| c.is_ascii_alphanumeric() || b"._\\-".contains(&c))
}

pub fn parse(input: &str) -> Option<QuickTarget> {
    let mut text = input.trim().to_owned();
    let command = text.starts_with("ssh ") || text.starts_with("mosh ");
    if text.is_empty() || text.contains(' ') && !command {
        return None;
    }
    let mut protocol = Protocol::Ssh;
    let mut port: Option<i32> = None;
    // `ssh [-p N] user@host [-p N]`
    if command {
        let words: Vec<&str> = text.split_whitespace().collect();
        protocol = if words[0] == "mosh" { Protocol::Mosh } else { Protocol::Ssh };
        let mut target: Option<&str> = None;
        let mut i = 1;
        while i < words.len() {
            if words[i] == "-p" && i + 1 < words.len() {
                port = Some(words[i + 1].parse().ok()?);
                i += 1;
            } else if words[i].starts_with('-') || target.is_some() {
                return None;
            } else {
                target = Some(words[i]);
            }
            i += 1;
        }
        text = target?.to_owned();
    }
    if let Some((scheme, rest)) = text.split_once("://") {
        if !scheme.is_empty() && scheme.chars().all(|c| c.is_ascii_alphabetic()) {
            protocol = match scheme.to_lowercase().as_str() {
                "ssh" => Protocol::Ssh,
                "mosh" => Protocol::Mosh,
                "telnet" => Protocol::Telnet,
                "rdp" => Protocol::Rdp,
                "vnc" => Protocol::Vnc,
                _ => return None,
            };
            text = rest.trim_end_matches('/').to_owned();
        }
    }
    let (user, rest) = match text.rfind('@') {
        Some(i) => (text[..i].to_owned(), text[i + 1..].to_owned()),
        None => (String::new(), text.clone()),
    };
    if !user.is_empty() && !is_user(&user) {
        return None;
    }
    let colons = rest.matches(':').count();
    let host = if let Some(inner) = rest.strip_prefix('[') {
        let (h, after) = inner.split_once(']')?;
        let after = after.strip_prefix(':').unwrap_or(after);
        if !after.is_empty() {
            port = Some(after.parse().ok()?);
        }
        if !h.contains(':') {
            return None;
        }
        h.to_owned()
    } else if colons == 1 {
        let (h, p) = rest.split_once(':')?;
        port = Some(p.parse().ok()?);
        h.to_owned()
    } else {
        // A bare IPv6 address, or a name.
        rest.clone()
    };
    if host.is_empty() || !host.contains(':') && !is_host(&host) {
        return None;
    }
    let p = port.unwrap_or(protocol.default_port());
    if !(1..=65535).contains(&p) {
        return None;
    }
    Some(QuickTarget {
        protocol,
        username: user,
        hostname: host,
        port: p,
    })
}

/// Whether [input] reads as a machine to reach rather than a search word (`root@x`, `x:22`, an IP, a scheme…).
pub fn looks_like_target(input: &str) -> bool {
    let t = input.trim();
    let Some(q) = parse(t) else { return false };
    let ipv4 = q.hostname.split('.').count() == 4
        && q.hostname
            .split('.')
            .all(|p| !p.is_empty() && p.len() <= 3 && p.bytes().all(|c| c.is_ascii_digit()));
    t.contains('@')
        || t.contains("://")
        || t.starts_with("ssh ")
        || t.starts_with("mosh ")
        || t.contains(':')
        || ipv4
        || q.hostname.contains('.')
}

#[cfg(test)]
mod tests {
    use super::*;

    fn q(p: Protocol, u: &str, h: &str, port: i32) -> QuickTarget {
        QuickTarget {
            protocol: p,
            username: u.into(),
            hostname: h.into(),
            port,
        }
    }

    #[test]
    fn reads_what_android_reads() {
        assert_eq!(parse("root@192.168.1.2"), Some(q(Protocol::Ssh, "root", "192.168.1.2", 22)));
        assert_eq!(parse("host:2222"), Some(q(Protocol::Ssh, "", "host", 2222)));
        assert_eq!(parse("rdp://admin@pc"), Some(q(Protocol::Rdp, "admin", "pc", 3389)));
        assert_eq!(parse("telnet://switch/"), Some(q(Protocol::Telnet, "", "switch", 23)));
        assert_eq!(parse("ssh mrd@nas.lan -p 2200"), Some(q(Protocol::Ssh, "mrd", "nas.lan", 2200)));
        assert_eq!(parse("mosh -p 2222 pi@raspberry"), Some(q(Protocol::Mosh, "pi", "raspberry", 2222)));
        assert_eq!(parse("admin@[fe80::1]:2022"), Some(q(Protocol::Ssh, "admin", "fe80::1", 2022)));
        assert_eq!(parse("vnc://10.0.0.9:5901"), Some(q(Protocol::Vnc, "", "10.0.0.9", 5901)));
        assert_eq!(parse("docker prod"), None);
        assert_eq!(parse("ftp://x"), None);
        assert_eq!(parse("x:99999"), None);
        assert_eq!(parse("bad user@x"), None);
    }

    #[test]
    fn only_addresses_look_like_targets() {
        for t in ["root@x", "10.0.0.2", "nas.lan", "x:22", "ssh x", "rdp://pc"] {
            assert!(looks_like_target(t), "{t}");
        }
        for t in ["docker", "prod", "", "jelly fin"] {
            assert!(!looks_like_target(t), "{t}");
        }
    }

    #[test]
    fn labels_and_protocol_changes() {
        let t = parse("root@nas:2222").unwrap();
        assert_eq!(t.label(), "root@nas:2222");
        let r = parse("admin@pc").unwrap().with_protocol(Protocol::Rdp);
        assert_eq!((r.port, r.label()), (3389, "admin@pc".to_owned()));
    }
}
