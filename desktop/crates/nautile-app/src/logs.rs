//! « Enregistrer les sessions »: a plain-text copy of what each new session shows, in `data/logs`
//! (as Android's `SessionLogs`): escape sequences are left out, lines end with `\n`.

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

/// Where an escape sequence being skipped is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Skip {
    None,
    Esc,
    /// `ESC [` … final byte.
    Csi,
    /// `ESC ]` … BEL or `ESC \`.
    Osc,
    OscEsc,
}

pub struct SessionLog {
    out: BufWriter<File>,
    skip: Skip,
}

impl SessionLog {
    /// A new file named after the time and the session.
    pub fn start(dir: &Path, name: &str) -> Option<SessionLog> {
        std::fs::create_dir_all(dir).ok()?;
        let safe: String = name
            .chars()
            .map(|c| if c.is_alphanumeric() || "-_.@".contains(c) { c } else { '_' })
            .take(60)
            .collect();
        let stamp = chrono::Local::now().format("%Y-%m-%d_%H-%M-%S");
        let file = File::create(dir.join(format!("{stamp}_{safe}.log"))).ok()?;
        Some(SessionLog {
            out: BufWriter::new(file),
            skip: Skip::None,
        })
    }

    pub fn write(&mut self, bytes: &[u8]) {
        let text = strip(bytes, &mut self.skip);
        let _ = self.out.write_all(&text);
        let _ = self.out.flush();
    }
}

/// The printable part of [bytes], [skip] carrying a sequence cut between two reads.
fn strip(bytes: &[u8], skip: &mut Skip) -> Vec<u8> {
    let mut out = Vec::with_capacity(bytes.len());
    for &b in bytes {
        *skip = match (*skip, b) {
            (Skip::None, 0x1b) => Skip::Esc,
            (Skip::None, b'\n' | b'\t') => {
                out.push(b);
                Skip::None
            }
            (Skip::None, b) if b < 0x20 || b == 0x7f => Skip::None,
            (Skip::None, b) => {
                out.push(b);
                Skip::None
            }
            (Skip::Esc, b'[') => Skip::Csi,
            (Skip::Esc, b']' | b'P' | b'_' | b'^') => Skip::Osc,
            (Skip::Esc, b'(' | b')' | b'#' | b'%') => Skip::Esc,
            (Skip::Esc, _) => Skip::None,
            (Skip::Csi, 0x40..=0x7e) => Skip::None,
            (Skip::Csi, _) => Skip::Csi,
            (Skip::Osc, 0x07) => Skip::None,
            (Skip::Osc, 0x1b) => Skip::OscEsc,
            (Skip::Osc, _) => Skip::Osc,
            (Skip::OscEsc, b'\\') => Skip::None,
            (Skip::OscEsc, _) => Skip::Osc,
        };
    }
    out
}

/// A recorded session: its file, name, size and date.
#[derive(Debug, Clone)]
pub struct LogFile {
    pub path: PathBuf,
    pub name: String,
    pub size: u64,
    /// Milliseconds since the epoch.
    pub modified: i64,
}

/// The recorded sessions, newest first.
pub fn list(dir: &Path) -> Vec<LogFile> {
    let mut out: Vec<LogFile> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| e.path().extension().is_some_and(|x| x == "log"))
        .filter_map(|e| {
            let meta = e.metadata().ok()?;
            let modified = meta.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok()?.as_millis() as i64;
            Some(LogFile {
                name: e.file_name().to_string_lossy().into_owned(),
                path: e.path(),
                size: meta.len(),
                modified,
            })
        })
        .collect();
    out.sort_by_key(|f| std::cmp::Reverse(f.modified));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colours_titles_and_carriage_returns_are_left_out() {
        let mut skip = Skip::None;
        let a = strip(b"\x1b]0;mrd@docker: ~\x07\x1b[1;32mmrd\x1b[0m:~$ ls\r\n", &mut skip);
        assert_eq!(String::from_utf8(a).unwrap(), "mrd:~$ ls\n");
        // A sequence cut between two reads.
        let b = strip(b"ok\x1b[3", &mut skip);
        let c = strip(b"1mred\x1b[0m\r\n", &mut skip);
        assert_eq!(String::from_utf8([b, c].concat()).unwrap(), "okred\n");
    }
}
