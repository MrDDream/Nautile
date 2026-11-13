//! Imported terminal themes (iTerm2 `.itermcolors`, Xresources, Alacritty TOML or YAML, as Android's
//! `ThemeImporter`) and fonts (`.ttf`, `.otf`), kept in `data/themes` and `data/fonts`.

use std::collections::HashMap;
use std::path::Path;

use iced::Color;
use nautile_core::{tr, trf};

use super::fonts::TermFont;
use super::themes::TermTheme;

const NAMES: [&str; 8] = ["black", "red", "green", "yellow", "blue", "magenta", "cyan", "white"];

fn hex(v: &str) -> Option<u32> {
    let h = v.trim().trim_matches(|c| c == '\'' || c == '"' || c == ',');
    let h = h
        .strip_prefix('#')
        .or_else(|| h.strip_prefix("0x"))
        .or_else(|| h.strip_prefix("0X"))
        .unwrap_or(h);
    (h.len() == 6).then(|| u32::from_str_radix(h, 16).ok()).flatten()
}

fn color(c: u32) -> Color {
    Color::from_rgb8((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

fn iterm(text: &str) -> HashMap<String, u32> {
    let mut out = HashMap::new();
    let mut rest = text;
    while let Some(k) = rest.find("<key>") {
        let after = &rest[k + 5..];
        let Some(end) = after.find("</key>") else { break };
        let key = after[..end].trim().to_owned();
        let after = &after[end + 6..];
        rest = after;
        let trimmed = after.trim_start();
        if !trimmed.starts_with("<dict>") {
            continue;
        }
        let Some(close) = trimmed.find("</dict>") else { break };
        let dict = &trimmed[6..close];
        let comp = |n: &str| -> f64 {
            let tag = format!("<key>{n} Component</key>");
            dict.find(&tag)
                .and_then(|i| {
                    let a = &dict[i + tag.len()..];
                    let s = a.find("<real>")? + 6;
                    let e = a[s..].find("</real>")? + s;
                    a[s..e].trim().parse().ok()
                })
                .unwrap_or(0.0)
        };
        let to = |v: f64| ((v * 255.0).round() as i64).clamp(0, 255) as u32;
        let rgb = to(comp("Red")) << 16 | to(comp("Green")) << 8 | to(comp("Blue"));
        if let Some(n) = key
            .strip_prefix("Ansi ")
            .and_then(|k| k.split(' ').next())
            .and_then(|n| n.parse::<u32>().ok())
        {
            out.insert(format!("color{n}"), rgb);
        } else {
            match key.as_str() {
                "Background Color" => {
                    out.insert("background".into(), rgb);
                }
                "Foreground Color" => {
                    out.insert("foreground".into(), rgb);
                }
                "Cursor Color" => {
                    out.insert("cursor".into(), rgb);
                }
                _ => {}
            }
        }
        rest = &trimmed[close..];
    }
    out
}

fn xresources(text: &str) -> HashMap<String, u32> {
    let mut out = HashMap::new();
    for l in text.lines() {
        let l = l.trim();
        if l.starts_with('!') {
            continue;
        }
        let Some((k, v)) = l.split_once(':') else { continue };
        let k = k.trim();
        let key = ["foreground", "background", "cursorColor"]
            .into_iter()
            .find(|n| k.ends_with(n))
            .map(|n| if n == "cursorColor" { "cursor".to_owned() } else { n.to_owned() })
            .or_else(|| {
                let i = k.rfind("color")?;
                let n = &k[i + 5..];
                (!n.is_empty() && n.bytes().all(|b| b.is_ascii_digit())).then(|| format!("color{n}"))
            });
        if let (Some(key), Some(c)) = (key, hex(v.split_whitespace().next().unwrap_or_default())) {
            out.insert(key, c);
        }
    }
    out
}

/// Alacritty: `[colors.normal]` + `red = '#…'` (TOML) or nested `colors: normal: red: '0x…'` (YAML).
fn alacritty(text: &str) -> HashMap<String, u32> {
    let mut out = HashMap::new();
    let mut section = String::new();
    let mut yaml: Vec<(usize, String)> = Vec::new();
    for raw in text.lines() {
        let l = if raw.contains("'#") || raw.contains("\"#") {
            raw
        } else {
            raw.split('#').next().unwrap_or_default()
        };
        if l.trim().is_empty() {
            continue;
        }
        let t = l.trim();
        if t.starts_with('[') {
            section = t.trim_matches(|c| c == '[' || c == ']').trim_start_matches("colors.").to_owned();
            continue;
        }
        let indent = l.len() - l.trim_start().len();
        let sep = if t.contains('=') { '=' } else { ':' };
        let (key, value) = t.split_once(sep).unwrap_or((t, ""));
        let (key, value) = (key.trim().trim_matches('"'), value.trim());
        if sep == ':' {
            while yaml.last().is_some_and(|(i, _)| *i >= indent) {
                yaml.pop();
            }
            if value.is_empty() {
                yaml.push((indent, key.to_owned()));
                continue;
            }
        }
        let group = if sep == '=' {
            section.clone()
        } else {
            yaml.last().map(|(_, k)| k.clone()).unwrap_or_default()
        };
        let Some(c) = hex(value) else { continue };
        match (group.as_str(), key) {
            ("normal", k) if NAMES.contains(&k) => {
                out.insert(format!("color{}", NAMES.iter().position(|n| *n == k).unwrap_or(0)), c);
            }
            ("bright", k) if NAMES.contains(&k) => {
                out.insert(format!("color{}", NAMES.iter().position(|n| *n == k).unwrap_or(0) + 8), c);
            }
            ("primary", "background" | "foreground") | ("cursor", "cursor") => {
                out.insert(key.to_owned(), c);
            }
            _ => {}
        }
    }
    out
}

/// A theme read from [text]; its id is `custom-` and its name in lower case.
pub fn parse_theme(text: &str, name: &str) -> Result<SavedTheme, String> {
    let c = if text.contains("<plist") || text.contains("Ansi 0 Color") {
        iterm(text)
    } else if text.lines().any(|l| {
        // `*.color4:` or `URxvt.color4:`
        let l = l.trim();
        let Some((k, _)) = l.split_once(':') else { return false };
        let n = k.rfind("color").map(|i| &k[i + 5..]).unwrap_or_default();
        !l.starts_with(['!', '[']) && k.contains(['*', '.']) && !n.is_empty() && n.bytes().all(|b| b.is_ascii_digit())
    }) {
        xresources(text)
    } else {
        alacritty(text)
    };
    let mut ansi = [0u32; 16];
    for (i, a) in ansi.iter_mut().enumerate() {
        *a = *c
            .get(&format!("color{i}"))
            .or_else(|| c.get(&format!("color{}", i % 8)))
            .ok_or_else(|| trf("Couleur %1$s absente du thème", &[&i]))?;
    }
    let bg = c.get("background").copied().unwrap_or(ansi[0]);
    let fg = c.get("foreground").copied().unwrap_or(ansi[7]);
    let slug: String = name
        .to_lowercase()
        .chars()
        .map(|ch| if ch.is_ascii_alphanumeric() { ch } else { '-' })
        .collect::<String>()
        .split('-')
        .filter(|p| !p.is_empty())
        .collect::<Vec<_>>()
        .join("-");
    Ok(SavedTheme {
        id: format!("custom-{slug}"),
        name: name.to_owned(),
        background: format!("{bg:06X}"),
        foreground: format!("{fg:06X}"),
        cursor: format!("{:06X}", c.get("cursor").copied().unwrap_or(fg)),
        ansi: ansi.iter().map(|a| format!("{a:06X}")).collect(),
    })
}

/// A theme as saved in `data/themes/<id>.json`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SavedTheme {
    pub id: String,
    pub name: String,
    pub background: String,
    pub foreground: String,
    pub cursor: String,
    pub ansi: Vec<String>,
}

impl SavedTheme {
    /// For the renderer: its names live as long as the app (a few small strings per imported theme).
    pub fn to_theme(&self) -> Option<TermTheme> {
        let c = |s: &str| u32::from_str_radix(s, 16).ok().map(color);
        let mut ansi = [Color::BLACK; 16];
        for (i, a) in self.ansi.iter().enumerate().take(16) {
            ansi[i] = c(a)?;
        }
        Some(TermTheme {
            id: Box::leak(self.id.clone().into_boxed_str()),
            name: Box::leak(self.name.clone().into_boxed_str()),
            background: c(&self.background)?,
            foreground: c(&self.foreground)?,
            cursor: c(&self.cursor)?,
            ansi,
        })
    }
}

pub fn load_themes(dir: &Path) -> Vec<TermTheme> {
    let mut v: Vec<TermTheme> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| serde_json::from_str::<SavedTheme>(&std::fs::read_to_string(e.path()).ok()?).ok())
        .filter_map(|t| t.to_theme())
        .collect();
    v.sort_by_key(|t| t.name.to_lowercase());
    v
}

pub fn save_theme(dir: &Path, t: &SavedTheme) -> std::io::Result<()> {
    std::fs::create_dir_all(dir)?;
    std::fs::write(dir.join(format!("{}.json", t.id)), serde_json::to_vec_pretty(t)?)
}

fn be16(b: &[u8], i: usize) -> Option<u16> {
    Some(u16::from_be_bytes(b.get(i..i + 2)?.try_into().ok()?))
}

fn be32(b: &[u8], i: usize) -> Option<u32> {
    Some(u32::from_be_bytes(b.get(i..i + 4)?.try_into().ok()?))
}

/// The family name of a TrueType / OpenType font and whether it says it is monospace (`post.isFixedPitch`).
pub fn font_info(b: &[u8]) -> Option<(String, bool)> {
    let tables = be16(b, 4)? as usize;
    let mut name = None;
    let mut mono = false;
    for t in 0..tables {
        let rec = 12 + t * 16;
        let tag = b.get(rec..rec + 4)?;
        let off = be32(b, rec + 8)? as usize;
        match tag {
            b"post" => mono = be32(b, off + 12)? != 0,
            b"name" => {
                let count = be16(b, off + 2)? as usize;
                let strings = off + be16(b, off + 4)? as usize;
                let mut best: Option<(u16, String)> = None;
                for i in 0..count {
                    let r = off + 6 + i * 12;
                    let (platform, id, len, at) = (be16(b, r)?, be16(b, r + 6)?, be16(b, r + 8)? as usize, be16(b, r + 10)? as usize);
                    // The typographic family (16) first, else the family (1).
                    if id != 16 && id != 1 {
                        continue;
                    }
                    let raw = b.get(strings + at..strings + at + len)?;
                    let s = if platform == 0 || platform == 3 {
                        String::from_utf16_lossy(&raw.as_chunks::<2>().0.iter().map(|c| u16::from_be_bytes(*c)).collect::<Vec<_>>())
                    } else {
                        String::from_utf8_lossy(raw).into_owned()
                    };
                    let rank = if id == 16 { 2 } else { 1 };
                    if best.as_ref().is_none_or(|(r, _)| rank > *r) && !s.trim().is_empty() {
                        best = Some((rank, s));
                    }
                }
                name = best.map(|(_, s)| s);
            }
            _ => {}
        }
    }
    Some((name?, mono))
}

/// The imported fonts: each file's family, for the renderer (loaded at start).
pub fn load_fonts(dir: &Path) -> Vec<(TermFont, Vec<u8>)> {
    std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| {
            e.path()
                .extension()
                .is_some_and(|x| x.eq_ignore_ascii_case("ttf") || x.eq_ignore_ascii_case("otf"))
        })
        .filter_map(|e| {
            let bytes = std::fs::read(e.path()).ok()?;
            let (family, _) = font_info(&bytes)?;
            let id: &'static str = Box::leak(format!("file:{}", e.file_name().to_string_lossy()).into_boxed_str());
            let family: &'static str = Box::leak(family.into_boxed_str());
            let nerd = family.contains("Nerd");
            Some((
                TermFont {
                    id,
                    name: family,
                    font: iced::Font::with_name(family),
                    bytes: None,
                    nerd,
                },
                bytes,
            ))
        })
        .collect()
}

/// Checks a font file before it is copied: its family, and a warning when it is not monospace.
pub fn check_font(bytes: &[u8]) -> Result<(String, Option<String>), String> {
    if bytes.len() > 30 * 1024 * 1024 {
        return Err(tr("Police trop lourde (30 Mo au plus)"));
    }
    let (family, mono) = font_info(bytes).ok_or_else(|| tr("Ce fichier n'est pas une police TrueType ou OpenType"))?;
    let warning = (!mono).then(|| tr("Cette police n'est pas à chasse fixe : les colonnes du terminal risquent d'être décalées."));
    Ok((family, warning))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rgb(t: &SavedTheme, i: usize) -> &str {
        &t.ansi[i]
    }

    #[test]
    fn iterm_xresources_and_alacritty_themes_are_read() {
        let plist = r#"<plist><dict>
<key>Ansi 0 Color</key><dict><key>Blue Component</key><real>0</real><key>Green Component</key><real>0</real><key>Red Component</key><real>0</real></dict>
<key>Ansi 1 Color</key><dict><key>Blue Component</key><real>0</real><key>Green Component</key><real>0</real><key>Red Component</key><real>1</real></dict>
<key>Ansi 2 Color</key><dict><key>Blue Component</key><real>0</real><key>Green Component</key><real>1</real><key>Red Component</key><real>0</real></dict>
<key>Ansi 3 Color</key><dict><key>Blue Component</key><real>0</real><key>Green Component</key><real>1</real><key>Red Component</key><real>1</real></dict>
<key>Ansi 4 Color</key><dict><key>Blue Component</key><real>1</real><key>Green Component</key><real>0</real><key>Red Component</key><real>0</real></dict>
<key>Ansi 5 Color</key><dict><key>Blue Component</key><real>1</real><key>Green Component</key><real>0</real><key>Red Component</key><real>1</real></dict>
<key>Ansi 6 Color</key><dict><key>Blue Component</key><real>1</real><key>Green Component</key><real>1</real><key>Red Component</key><real>0</real></dict>
<key>Ansi 7 Color</key><dict><key>Blue Component</key><real>1</real><key>Green Component</key><real>1</real><key>Red Component</key><real>1</real></dict>
<key>Background Color</key><dict><key>Blue Component</key><real>0.1</real><key>Green Component</key><real>0.1</real><key>Red Component</key><real>0.1</real></dict>
</dict></plist>"#;
        let t = parse_theme(plist, "Mon Thème!").unwrap();
        assert_eq!(t.id, "custom-mon-th-me");
        assert_eq!((rgb(&t, 1), rgb(&t, 9), t.background.as_str()), ("FF0000", "FF0000", "1A1A1A"));

        let x = "! comment\n*.foreground: #c5c8c6\n*.background: #1d1f21\n*.cursorColor: #ffcc00\n".to_owned()
            + &(0..16).map(|i| format!("*.color{i}: #0000{i:02x}\n")).collect::<String>();
        let t = parse_theme(&x, "x").unwrap();
        assert_eq!(
            (t.foreground.as_str(), t.cursor.as_str(), rgb(&t, 15)),
            ("C5C8C6", "FFCC00", "00000F")
        );

        let toml = "[colors.primary]\nbackground = '#101010'\nforeground = '#e0e0e0'\n[colors.normal]\n".to_owned()
            + &NAMES
                .iter()
                .enumerate()
                .map(|(i, n)| format!("{n} = '#00000{i}'\n"))
                .collect::<String>();
        let t = parse_theme(&toml, "a").unwrap();
        assert_eq!((t.background.as_str(), rgb(&t, 2), rgb(&t, 10)), ("101010", "000002", "000002"));

        let yaml = "colors:\n  primary:\n    background: '0x202020'\n  normal:\n".to_owned()
            + &NAMES
                .iter()
                .enumerate()
                .map(|(i, n)| format!("    {n}: '0x00000{i}'\n"))
                .collect::<String>();
        let t = parse_theme(&yaml, "y").unwrap();
        assert_eq!((t.background.as_str(), rgb(&t, 7)), ("202020", "000007"));
        assert!(parse_theme("nothing here", "z").is_err());
    }

    #[test]
    fn a_bundled_font_is_read() {
        let (family, mono) = font_info(include_bytes!("../../assets/fonts/HackNerd.ttf")).unwrap();
        assert_eq!((family.as_str(), mono), ("Hack Nerd Font Mono", true));
        let (family, mono) = font_info(include_bytes!("../../assets/fonts/Inter.ttf")).unwrap();
        assert!(family.starts_with("Inter") && !mono);
    }
}
