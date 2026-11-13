//! « Importer »: hosts from an OpenSSH `~/.ssh/config` or a CSV file (Android's `SshConfigParser` and
//! `CsvHostParser`, same rules).

/// A host read from an external source, before it is saved.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ImportedHost {
    pub alias: String,
    pub hostname: String,
    pub port: i32,
    pub username: String,
    pub identity_file: Option<String>,
    /// Alias of the jump host (first hop of ProxyJump).
    pub proxy_jump: Option<String>,
    pub group: Option<String>,
    pub tags: Vec<String>,
}

/// A CSV with a « hostname » column, else an OpenSSH config.
pub fn parse(text: &str) -> Result<Vec<ImportedHost>, String> {
    let first = text.lines().find(|l| !l.trim().is_empty()).unwrap_or_default().to_lowercase();
    let words: Vec<&str> = first.split(|c: char| !c.is_alphanumeric()).collect();
    let csv = first.contains(',')
        && words.iter().any(|w| ["hostname", "host", "address"].contains(w))
        && !first.trim_start().starts_with("host ");
    if csv {
        parse_csv(text)
    } else {
        Ok(parse_ssh_config(text))
    }
}

struct Block {
    patterns: Vec<String>,
    options: Vec<(String, String)>,
}

fn tokenize(v: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut rest = v.trim();
    while !rest.is_empty() {
        if let Some(r) = rest.strip_prefix('"') {
            let end = r.find('"').unwrap_or(r.len());
            out.push(r[..end].to_owned());
            rest = r.get(end + 1..).unwrap_or_default().trim_start();
        } else {
            let end = rest.find(char::is_whitespace).unwrap_or(rest.len());
            out.push(rest[..end].to_owned());
            rest = rest[end..].trim_start();
        }
    }
    out
}

fn glob(pattern: &str, s: &str) -> bool {
    fn go(p: &[u8], s: &[u8]) -> bool {
        match (p.first(), s.first()) {
            (None, None) => true,
            (Some(b'*'), _) => go(&p[1..], s) || !s.is_empty() && go(p, &s[1..]),
            (Some(b'?'), Some(_)) => go(&p[1..], &s[1..]),
            (Some(a), Some(b)) if a.eq_ignore_ascii_case(b) => go(&p[1..], &s[1..]),
            _ => false,
        }
    }
    go(pattern.as_bytes(), s.as_bytes())
}

fn matches(patterns: &[String], alias: &str) -> bool {
    let mut matched = false;
    for p in patterns {
        let (negated, g) = match p.strip_prefix('!') {
            Some(g) => (true, g),
            None => (false, p.as_str()),
        };
        if glob(g, alias) {
            if negated {
                return false;
            }
            matched = true;
        }
    }
    matched
}

/// Only concrete `Host` aliases are imported; wildcard patterns apply as defaults, first value wins.
pub fn parse_ssh_config(text: &str) -> Vec<ImportedHost> {
    let mut blocks = vec![Block {
        patterns: vec!["*".into()],
        options: Vec::new(),
    }];
    for raw in text.lines() {
        let line = raw.split('#').next().unwrap_or_default().trim();
        if line.is_empty() {
            continue;
        }
        let Some(i) = line.find([' ', '\t', '=']).filter(|i| *i > 0) else {
            continue;
        };
        let key = line[..i].to_lowercase();
        let value = line[i..].trim_start_matches([' ', '\t', '=']).trim();
        if value.is_empty() {
            continue;
        }
        match key.as_str() {
            "host" => blocks.push(Block {
                patterns: tokenize(value),
                options: Vec::new(),
            }),
            // Match blocks are not supported: their options are swallowed.
            "match" => blocks.push(Block {
                patterns: Vec::new(),
                options: Vec::new(),
            }),
            _ => {
                let b = blocks.last_mut().expect("one block");
                if !b.options.iter().any(|(k, _)| *k == key) {
                    let v = value.strip_prefix('"').and_then(|v| v.strip_suffix('"')).unwrap_or(value);
                    b.options.push((key, v.to_owned()));
                }
            }
        }
    }
    let mut aliases: Vec<String> = Vec::new();
    for b in &blocks {
        for p in &b.patterns {
            if !p.contains(['*', '?', '!']) && !aliases.contains(p) {
                aliases.push(p.clone());
            }
        }
    }
    aliases
        .into_iter()
        .map(|alias| {
            let mut opts: Vec<(String, String)> = Vec::new();
            for b in blocks.iter().filter(|b| matches(&b.patterns, &alias)) {
                for (k, v) in &b.options {
                    if !opts.iter().any(|(x, _)| x == k) {
                        opts.push((k.clone(), v.clone()));
                    }
                }
            }
            let get = |k: &str| opts.iter().find(|(x, _)| x == k).map(|(_, v)| v.clone());
            ImportedHost {
                hostname: get("hostname").map(|h| h.replace("%h", &alias)).unwrap_or_else(|| alias.clone()),
                port: get("port").and_then(|p| p.parse().ok()).unwrap_or(22),
                username: get("user").unwrap_or_default(),
                identity_file: get("identityfile"),
                proxy_jump: get("proxyjump")
                    .and_then(|j| j.split(',').next().map(|s| s.trim().to_owned()))
                    .filter(|j| !j.eq_ignore_ascii_case("none")),
                alias,
                ..Default::default()
            }
        })
        .collect()
}

/// RFC 4180-style rows: quoted fields, doubled quotes, commas and newlines inside quotes.
fn rows(text: &str) -> Vec<Vec<String>> {
    let mut rows = Vec::new();
    let mut row = Vec::new();
    let mut field = String::new();
    let mut quoted = false;
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        if quoted {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    field.push('"');
                    chars.next();
                } else {
                    quoted = false;
                }
            } else {
                field.push(c);
            }
        } else {
            match c {
                '"' => quoted = true,
                ',' => row.push(std::mem::take(&mut field)),
                '\r' => {}
                '\n' => {
                    row.push(std::mem::take(&mut field));
                    rows.push(std::mem::take(&mut row));
                }
                c => field.push(c),
            }
        }
    }
    if !field.is_empty() || !row.is_empty() {
        row.push(field);
        rows.push(row);
    }
    rows
}

/// A CSV with a header row: `alias|label|name`, `hostname|host|address`, `port`, `username|user`, `group`,
/// `tags` (separated by `;` or `|`), in any order.
pub fn parse_csv(text: &str) -> Result<Vec<ImportedHost>, String> {
    let rows: Vec<Vec<String>> = rows(text).into_iter().filter(|r| r.iter().any(|c| !c.trim().is_empty())).collect();
    let Some(header) = rows.first() else { return Ok(Vec::new()) };
    let header: Vec<String> = header.iter().map(|h| h.trim().to_lowercase()).collect();
    let col = |names: &[&str]| header.iter().position(|h| names.contains(&h.as_str()));
    let (alias, host, port, user, group, tags) = (
        col(&["alias", "label", "name"]),
        col(&["hostname", "host", "address"]).ok_or_else(|| crate::tr("Colonne « hostname » introuvable"))?,
        col(&["port"]),
        col(&["username", "user"]),
        col(&["group"]),
        col(&["tags"]),
    );
    Ok(rows[1..]
        .iter()
        .filter_map(|r| {
            let cell = |i: Option<usize>| i.and_then(|i| r.get(i)).map(|c| c.trim().to_owned()).unwrap_or_default();
            let h = cell(Some(host));
            if h.is_empty() {
                return None;
            }
            let a = cell(alias);
            let g = cell(group);
            Some(ImportedHost {
                alias: if a.is_empty() { h.clone() } else { a },
                port: cell(port).parse().unwrap_or(22),
                username: cell(user),
                group: (!g.is_empty()).then_some(g),
                tags: cell(tags)
                    .split([';', '|'])
                    .map(|t| t.trim().to_owned())
                    .filter(|t| !t.is_empty())
                    .collect(),
                hostname: h,
                ..Default::default()
            })
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_an_openssh_config_like_android() {
        let text = "Host nas nas.lan\n  HostName 192.168.1.10\n  Port 2222\n  IdentityFile ~/.ssh/id_ed25519\n\nHost web-*\n  User deploy\n\nHost web-1\n  HostName %h.example.org\n  ProxyJump nas\n\nHost !bad\nMatch host x\n  User nobody\nHost *\n  User mrd\n  Port 22\n";
        let hosts = parse(text).unwrap();
        let aliases: Vec<&str> = hosts.iter().map(|h| h.alias.as_str()).collect();
        assert_eq!(aliases, ["nas", "nas.lan", "web-1"]);
        assert_eq!(
            (hosts[0].hostname.as_str(), hosts[0].port, hosts[0].username.as_str()),
            ("192.168.1.10", 2222, "mrd")
        );
        assert_eq!(hosts[0].identity_file.as_deref(), Some("~/.ssh/id_ed25519"));
        // As in OpenSSH, the first value met wins: `Host web-*` before `Host *`.
        assert_eq!(
            (hosts[2].hostname.as_str(), hosts[2].username.as_str()),
            ("web-1.example.org", "deploy")
        );
        assert_eq!(hosts[2].proxy_jump.as_deref(), Some("nas"));
    }

    #[test]
    fn reads_a_csv_with_quotes_and_tags() {
        let text = "alias,hostname,port,user,group,tags\ndocker,10.0.0.20,,mrd,Homelab,prod;docker\n\"pi, salon\",pi.lan,2222,pi,,\"dns|home\"\n,skipped-empty-alias-uses-host,,,,\n";
        let hosts = parse(text).unwrap();
        assert_eq!(hosts.len(), 3);
        assert_eq!((hosts[0].port, hosts[0].group.as_deref()), (22, Some("Homelab")));
        assert_eq!(hosts[0].tags, ["prod", "docker"]);
        assert_eq!((hosts[1].alias.as_str(), hosts[1].port), ("pi, salon", 2222));
        assert_eq!(hosts[1].tags, ["dns", "home"]);
        assert_eq!(hosts[2].alias, "skipped-empty-alias-uses-host");
        assert!(parse_csv("name,port\nx,1\n").is_err());
    }
}
