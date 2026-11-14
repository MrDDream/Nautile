//! « Enregistrer les sessions »: a copy of what each new session shows, in `data/logs` (as Android's
//! `SessionLogs`): escape sequences are left out, lines end with `\n`. A log is encrypted like Android's:
//! the header `NTLS1`, then sealed chunks (u32 big-endian length + [crate::sealed] blob), each appended whole,
//! so a session cut short loses at most its last seconds. Logs written before encryption stay readable.

use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

const HEADER: &[u8] = b"NTLS1";
/// Output sealed as soon as this much waits (else at the next flush).
const CHUNK_BYTES: usize = 64 * 1024;
/// A length over this is not a chunk (a damaged file): reading stops there.
const MAX_CHUNK: usize = 16 << 20;

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
    out: File,
    key: [u8; 32],
    skip: Skip,
    /// Sealed and written at most every few seconds, by [SessionLog::flush] and when the log is dropped.
    flushed: Instant,
    /// Written since the last flush, not sealed yet.
    pending: Vec<u8>,
}

impl SessionLog {
    /// A new file named after the time and the session; None without the database key (never in clear).
    pub fn start(dir: &Path, name: &str) -> Option<SessionLog> {
        let key = crate::sealed::key(crate::sealed::LOGS)?;
        // The whole output of the terminals: for this user only.
        crate::storage::private_dir(dir).ok()?;
        let safe: String = name
            .chars()
            .map(|c| if c.is_alphanumeric() || "-_.@".contains(c) { c } else { '_' })
            .take(60)
            .collect();
        let stamp = chrono::Local::now().format("%Y-%m-%d_%H-%M-%S");
        let mut out = crate::storage::create_private(&dir.join(format!("{stamp}_{safe}.log"))).ok()?;
        out.write_all(HEADER).ok()?;
        Some(SessionLog {
            out,
            key,
            skip: Skip::None,
            flushed: Instant::now(),
            pending: Vec::new(),
        })
    }

    pub fn write(&mut self, bytes: &[u8]) {
        let text = strip(bytes, &mut self.skip);
        self.pending.extend_from_slice(&text);
        if self.pending.len() >= CHUNK_BYTES || self.flushed.elapsed() >= Duration::from_secs(2) {
            self.flush();
        }
    }

    /// Seals what is waiting and writes it (also called on a timer: a quiet session is not written otherwise).
    pub fn flush(&mut self) {
        if !self.pending.is_empty() {
            let blob = crate::sealed::seal(&self.key, &self.pending);
            self.pending.clear();
            // Length and blob in one write: a chunk is on disk whole, or (cut off) ignored by the reader.
            let chunk = [(blob.len() as u32).to_be_bytes().as_slice(), &blob].concat();
            let _ = self.out.write_all(&chunk);
        }
        self.flushed = Instant::now();
    }
}

impl Drop for SessionLog {
    fn drop(&mut self) {
        self.flush();
    }
}

/// The text of the log at [path]: its chunks opened (a damaged or cut-off end is left out), or the file as is
/// when it was written before encryption.
pub fn read(path: &Path) -> std::io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    File::open(path)?.read_to_end(&mut bytes)?;
    let Some(mut rest) = bytes.strip_prefix(HEADER) else {
        return Ok(bytes);
    };
    let key = crate::sealed::key(crate::sealed::LOGS)
        .ok_or_else(|| std::io::Error::other(nautile_core::tr("Clé de chiffrement indisponible")))?;
    let mut out = Vec::new();
    while rest.len() >= 4 {
        let len = u32::from_be_bytes([rest[0], rest[1], rest[2], rest[3]]) as usize;
        if len == 0 || len > MAX_CHUNK || rest.len() < 4 + len {
            break;
        }
        let Some(plain) = crate::sealed::open(&key, &rest[4..4 + len]) else {
            break;
        };
        out.extend_from_slice(&plain);
        rest = &rest[4 + len..];
    }
    Ok(out)
}

/// Seals in place the logs of [dir] written before encryption (no `NTLS1` header): each is rewritten whole
/// (a temporary file renamed over it, for this user only), so that no session is left in clear on the disk.
pub fn seal_old(dir: &Path, key: &[u8; 32]) {
    for f in list(dir) {
        if let Err(e) = seal_plain(&f.path, key) {
            eprintln!("{}: not sealed ({e})", f.path.display());
        }
    }
}

fn seal_plain(path: &Path, key: &[u8; 32]) -> std::io::Result<()> {
    let mut head = [0u8; HEADER.len()];
    let mut file = File::open(path)?;
    let n = file.read(&mut head)?;
    if n == HEADER.len() && head == HEADER {
        return Ok(());
    }
    let mut bytes = head[..n].to_vec();
    file.read_to_end(&mut bytes)?;
    drop(file);
    let mut out = HEADER.to_vec();
    for chunk in bytes.chunks(CHUNK_BYTES) {
        let blob = crate::sealed::seal(key, chunk);
        out.extend_from_slice(&(blob.len() as u32).to_be_bytes());
        out.extend_from_slice(&blob);
    }
    crate::storage::write_private(path, &out)
}

/// Writes the readable text of the logs [files] into [dir] (`name.txt`), for another program to open.
pub fn export(files: &[PathBuf], dir: &Path) -> std::io::Result<Vec<PathBuf>> {
    let mut done = Vec::new();
    for f in files {
        let text = read(f)?;
        let stem = f
            .file_stem()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_else(|| "session".into());
        let to = dir.join(format!("{stem}.txt"));
        std::fs::write(&to, text)?;
        done.push(to);
    }
    Ok(done)
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

    #[test]
    fn a_flush_seals_what_is_waiting() {
        crate::sealed::set_database_key(&[3; 32]);
        let dir = std::env::temp_dir().join(format!("nautile-logs-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let mut log = SessionLog::start(&dir, "docker-01").unwrap();
        log.write(b"uptime\r\n");
        let path = list(&dir)[0].path.clone();
        assert_eq!(read(&path).unwrap(), b"", "kept in memory at first");
        log.flush();
        assert_eq!(read(&path).unwrap(), b"uptime\n");
        log.write(b"ls\r\n");
        drop(log);
        assert_eq!(read(&path).unwrap(), b"uptime\nls\n");
        let raw = std::fs::read(&path).unwrap();
        assert!(raw.starts_with(HEADER));
        assert!(!raw.windows(6).any(|w| w == b"uptime"), "never in clear on the disk");
        // A chunk cut off at the end (a crash while writing) is left out.
        std::fs::write(&path, &raw[..raw.len() - 3]).unwrap();
        assert_eq!(read(&path).unwrap(), b"uptime\n");
        // A log from before encryption is read as is, and exported as text.
        let old = dir.join("old.log");
        std::fs::write(&old, "whoami\n").unwrap();
        assert_eq!(read(&old).unwrap(), b"whoami\n");
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let done = export(std::slice::from_ref(&old), &out).unwrap();
        assert_eq!(std::fs::read_to_string(&done[0]).unwrap(), "whoami\n");
        // Then sealed in place, once the key is known; a sealed log is left as it is.
        let big: Vec<u8> = (0..CHUNK_BYTES * 2 + 10).map(|i| b'a' + (i % 26) as u8).collect();
        let older = dir.join("older.log");
        std::fs::write(&older, &big).unwrap();
        let sealed_before = std::fs::read(&path).unwrap();
        seal_old(&dir, &crate::sealed::key(crate::sealed::LOGS).unwrap());
        assert_eq!(std::fs::read(&path).unwrap(), sealed_before);
        for (f, text) in [(&old, b"whoami\n".to_vec()), (&older, big)] {
            let raw = std::fs::read(f).unwrap();
            assert!(raw.starts_with(HEADER) && !raw.windows(6).any(|w| w == b"whoami" || w == b"abcdef"));
            assert_eq!(read(f).unwrap(), text);
        }
        assert!(!dir.join("old.log.tmp").exists());
        let _ = std::fs::remove_dir_all(dir);
    }
}
