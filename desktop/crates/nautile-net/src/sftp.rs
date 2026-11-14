//! SFTP over an SSH connection (same chain, proxy and prompts as a shell): listings and transfers, folders
//! included, with their progress.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use russh_sftp::client::SftpSession;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};

use crate::session::{Event, RUNTIME, channels};
use crate::ssh::{self, Params};

/// A file or folder of a listing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    pub dir: bool,
    pub link: bool,
    pub size: u64,
    /// Seconds since 1970.
    pub modified: Option<i64>,
    pub mode: Option<u32>,
}

impl Entry {
    /// Folders first, then by name.
    pub fn sort(list: &mut [Entry]) {
        list.sort_by(|a, b| b.dir.cmp(&a.dir).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    }
}

#[derive(Debug, Clone)]
pub enum Cmd {
    List(String),
    Download {
        remote: String,
        local: PathBuf,
    },
    Upload {
        local: PathBuf,
        remote: String,
    },
    Mkdir(String),
    Rename(String, String),
    /// A file, or a folder with everything in it.
    Remove(String),
    /// A text file for the editor.
    Read(String),
    /// Saves the editor's text; `expect`: the modification time it was read with (refused if it changed).
    Write {
        path: String,
        text: String,
        expect: Option<u32>,
    },
}

/// The largest file the editor opens.
pub const MAX_EDIT_BYTES: u64 = 2 * 1024 * 1024;

#[derive(Debug, Clone)]
pub enum Update {
    /// Connection steps, questions, host keys, the end.
    Conn(Event),
    /// Connected; the remote home folder.
    Ready(String),
    Listing {
        path: String,
        entries: Vec<Entry>,
    },
    Progress {
        id: u64,
        done: u64,
        total: u64,
    },
    /// A text file read for the editor, and its modification time.
    Text {
        path: String,
        text: String,
        mtime: Option<u32>,
    },
    /// The editor's text was written; the file's new modification time.
    Saved {
        path: String,
        mtime: Option<u32>,
    },
    /// A command finished (`error` set when it failed).
    Finished {
        id: u64,
        error: Option<CmdError>,
    },
}

/// Why a command failed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CmdError {
    /// [Cmd::Write] refused: the file changed on the server since the editor read it (it may be overwritten
    /// by saving again with `expect: None`).
    Changed,
    Failed(String),
}

impl std::fmt::Display for CmdError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CmdError::Changed => f.write_str(&nautile_core::tr("Le fichier a changé sur le serveur depuis son ouverture")),
            CmdError::Failed(e) => f.write_str(e),
        }
    }
}

impl From<String> for CmdError {
    fn from(e: String) -> Self {
        CmdError::Failed(e)
    }
}

/// The interface's side.
pub struct Browser {
    pub cmd: UnboundedSender<(u64, Cmd)>,
    pub updates: UnboundedReceiver<Update>,
}

pub fn join(dir: &str, name: &str) -> String {
    if dir.ends_with('/') {
        format!("{dir}{name}")
    } else {
        format!("{dir}/{name}")
    }
}

/// The folder above [path] (`/` stays `/`).
pub fn parent(path: &str) -> String {
    let p = path.trim_end_matches('/');
    match p.rfind('/') {
        Some(0) | None => "/".into(),
        Some(i) => p[..i].into(),
    }
}

/// A name a server may give in a listing: one path component, never `.`, `..` or a path.
fn remote_name_ok(name: &str) -> bool {
    !name.is_empty() && name != "." && name != ".." && !name.contains(['/', '\0'])
}

/// True when [name], received from the server, can be created inside a local folder without leaving it: a
/// single normal component (no `..`, no absolute path, no separator, no Windows drive or stream).
pub fn safe_local_name(name: &str) -> bool {
    local_name_ok(name, cfg!(windows))
}

/// [safe_local_name] for Windows' rules or the others' (testable everywhere). Windows also refuses `<>"|?*`,
/// control characters, a name ending with `.` or a space, and the devices' names (`CON`, `nul.txt`, `COM¹`…).
pub fn local_name_ok(name: &str, windows: bool) -> bool {
    use std::path::Component;
    if !remote_name_ok(name) {
        return false;
    }
    if windows {
        if name.chars().any(|c| c < ' ' || "\\:<>\"|?*".contains(c)) || name.ends_with(['.', ' ']) {
            return false;
        }
        let base = name.split('.').next().unwrap_or_default().trim_end_matches(' ').to_uppercase();
        let device = matches!(base.as_str(), "CON" | "PRN" | "AUX" | "NUL")
            || ["COM", "LPT"].iter().any(|p| {
                base.strip_prefix(p).is_some_and(|n| {
                    let mut c = n.chars();
                    matches!((c.next(), c.next()), (Some('0'..='9' | '¹' | '²' | '³'), None))
                })
            });
        if device {
            return false;
        }
    }
    let mut parts = Path::new(name).components();
    matches!((parts.next(), parts.next()), (Some(Component::Normal(n)), None) if n == std::ffi::OsStr::new(name))
}

fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

pub fn spawn(p: Params) -> Browser {
    let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<(u64, Cmd)>();
    let (up_tx, up_rx) = mpsc::unbounded_channel();
    let (mut session, events, _input) = channels();
    // Steps and questions of the connection go out as they come.
    let fwd = up_tx.clone();
    RUNTIME.spawn(async move {
        while let Some(e) = session.events.recv().await {
            let _ = fwd.send(Update::Conn(e));
        }
    });
    RUNTIME.spawn(async move {
        let started = async {
            let conn = ssh::connect(&p, &events).await?;
            let ch = conn.handle.channel_open_session().await.map_err(err)?;
            ch.request_subsystem(true, "sftp").await.map_err(err)?;
            let sftp = SftpSession::new(ch.into_stream()).await.map_err(err)?;
            let home = sftp.canonicalize(".").await.unwrap_or_else(|_| "/".into());
            Ok::<_, String>((conn, Arc::new(sftp), home))
        };
        let (conn, sftp, home) = match started.await {
            Ok(v) => v,
            Err(e) => {
                events.send(Event::Closed(Some(e)));
                return;
            }
        };
        let _ = up_tx.send(Update::Ready(home));
        while let Some((id, cmd)) = cmd_rx.recv().await {
            let (sftp, up) = (sftp.clone(), up_tx.clone());
            tokio::spawn(async move {
                let r = run(&sftp, id, cmd, &up).await;
                let _ = up.send(Update::Finished { id, error: r.err() });
            });
        }
        let _ = sftp.close().await;
        conn.close().await;
        events.send(Event::Closed(None));
    });
    Browser {
        cmd: cmd_tx,
        updates: up_rx,
    }
}

pub async fn list(sftp: &SftpSession, path: &str) -> Result<Vec<Entry>, String> {
    let mut out: Vec<Entry> = sftp
        .read_dir(path)
        .await
        .map_err(err)?
        .map(|e| {
            let m = e.metadata();
            let ft = e.file_type();
            Entry {
                name: e.file_name(),
                dir: ft.is_dir(),
                link: ft.is_symlink(),
                size: m.size.unwrap_or(0),
                modified: m.mtime.map(i64::from),
                mode: m.permissions,
            }
        })
        .filter(|e| remote_name_ok(&e.name))
        .collect();
    // A link to a folder opens like a folder.
    for e in out.iter_mut().filter(|e| e.link) {
        if let Ok(m) = sftp.metadata(join(path, &e.name)).await {
            e.dir = m.is_dir();
        }
    }
    Entry::sort(&mut out);
    Ok(out)
}

/// Counts bytes and reports them a few times a second.
struct Meter<'a> {
    id: u64,
    up: &'a UnboundedSender<Update>,
    done: u64,
    total: u64,
    last: Instant,
    /// Remote names that could not be created here, left out of a folder's download.
    skipped: Vec<String>,
    /// Links to folders, not followed (a link to « / » or to a parent would copy without end).
    links: Vec<String>,
}

impl Meter<'_> {
    /// What was left out, for the end of the transfer: None when everything was copied.
    fn left_out(&self, upload: bool) -> Option<String> {
        let mut parts = Vec::new();
        if !self.skipped.is_empty() {
            parts.push(nautile_core::trf(
                "Téléchargé, sauf %1$s élément(s) dont le nom n'est pas permis ici : %2$s",
                &[&self.skipped.len(), &self.skipped.join(", ")],
            ));
        }
        if !self.links.is_empty() {
            let text = if upload {
                "Envoyé, sauf %1$s lien(s) vers des dossiers, non suivis : %2$s"
            } else {
                "Téléchargé, sauf %1$s lien(s) vers des dossiers, non suivis : %2$s"
            };
            parts.push(nautile_core::trf(text, &[&self.links.len(), &self.links.join(", ")]));
        }
        (!parts.is_empty()).then(|| parts.join("\n"))
    }
}

impl Meter<'_> {
    fn add(&mut self, n: u64) {
        self.done += n;
        if self.last.elapsed() > Duration::from_millis(150) {
            self.last = Instant::now();
            let _ = self.up.send(Update::Progress {
                id: self.id,
                done: self.done,
                total: self.total,
            });
        }
    }
}

async fn remote_size(sftp: &SftpSession, path: &str) -> u64 {
    let Ok(m) = sftp.metadata(path).await else { return 0 };
    if !m.is_dir() {
        return m.size.unwrap_or(0);
    }
    let mut total = 0;
    let mut stack = vec![path.to_owned()];
    while let Some(dir) = stack.pop() {
        for e in list(sftp, &dir).await.unwrap_or_default() {
            if e.dir && !e.link {
                stack.push(join(&dir, &e.name));
            } else {
                total += e.size;
            }
        }
    }
    total
}

/// Bytes [upload] sends: the chosen path is followed, links to folders inside it are not (see [upload]).
fn local_size(path: &Path) -> u64 {
    let Ok(m) = std::fs::metadata(path) else { return 0 };
    if !m.is_dir() {
        return m.len();
    }
    let mut total = 0;
    let mut stack = vec![path.to_path_buf()];
    while let Some(dir) = stack.pop() {
        for e in std::fs::read_dir(&dir).into_iter().flatten().flatten() {
            let Ok(ft) = e.file_type() else { continue };
            if ft.is_dir() {
                stack.push(e.path());
            } else if let Ok(m) = std::fs::metadata(e.path()) {
                // A link to a file counts as the file; a link to a folder is skipped.
                if !m.is_dir() {
                    total += m.len();
                }
            }
        }
    }
    total
}

async fn run(sftp: &SftpSession, id: u64, cmd: Cmd, up: &UnboundedSender<Update>) -> Result<(), CmdError> {
    match cmd {
        Cmd::List(path) => {
            let entries = list(sftp, &path).await?;
            let _ = up.send(Update::Listing { path, entries });
        }
        Cmd::Mkdir(path) => sftp.create_dir(path).await.map_err(err)?,
        Cmd::Rename(from, to) => sftp.rename(from, to).await.map_err(err)?,
        Cmd::Remove(path) => remove(sftp, &path).await?,
        Cmd::Read(path) => {
            let meta = sftp.metadata(&path).await.map_err(err)?;
            if meta.size.unwrap_or(0) > MAX_EDIT_BYTES {
                return Err(nautile_core::tr("Fichier trop gros pour l'éditeur (2 Mo au plus)").into());
            }
            let f = sftp.open(&path).await.map_err(err)?;
            let mut bytes = Vec::new();
            // The announced size may lie: never read more than the limit.
            f.take(MAX_EDIT_BYTES + 1).read_to_end(&mut bytes).await.map_err(err)?;
            if bytes.len() as u64 > MAX_EDIT_BYTES {
                return Err(nautile_core::tr("Fichier trop gros pour l'éditeur (2 Mo au plus)").into());
            }
            let text = String::from_utf8(bytes).map_err(|_| nautile_core::tr("Ce fichier n'est pas du texte"))?;
            let _ = up.send(Update::Text {
                path,
                text,
                mtime: meta.mtime,
            });
        }
        Cmd::Write { path, text, expect } => {
            if let Some(m) = expect {
                let now = sftp.metadata(&path).await.ok().and_then(|x| x.mtime);
                if now.is_some_and(|n| n != m) {
                    return Err(CmdError::Changed);
                }
            }
            let mut f = sftp.create(&path).await.map_err(err)?;
            f.write_all(text.as_bytes()).await.map_err(err)?;
            f.shutdown().await.map_err(err)?;
            let mtime = sftp.metadata(&path).await.ok().and_then(|x| x.mtime);
            let _ = up.send(Update::Saved { path, mtime });
        }
        Cmd::Download { remote, local } => {
            let total = remote_size(sftp, &remote).await;
            let mut m = Meter {
                id,
                up,
                done: 0,
                total,
                last: Instant::now(),
                skipped: Vec::new(),
                links: Vec::new(),
            };
            download(sftp, &remote, &local, &mut m).await?;
            if let Some(e) = m.left_out(false) {
                return Err(CmdError::Failed(e));
            }
        }
        Cmd::Upload { local, remote } => {
            let total = local_size(&local);
            let mut m = Meter {
                id,
                up,
                done: 0,
                total,
                last: Instant::now(),
                skipped: Vec::new(),
                links: Vec::new(),
            };
            upload(sftp, &local, &remote, &mut m).await?;
            if let Some(e) = m.left_out(true) {
                return Err(CmdError::Failed(e));
            }
        }
    }
    Ok(())
}

async fn remove(sftp: &SftpSession, path: &str) -> Result<(), String> {
    let m = sftp.symlink_metadata(path).await.map_err(err)?;
    if !m.is_dir() {
        return sftp.remove_file(path).await.map_err(err);
    }
    for e in list(sftp, path).await? {
        Box::pin(remove(sftp, &join(path, &e.name))).await?;
    }
    sftp.remove_dir(path).await.map_err(err)
}

async fn download(sftp: &SftpSession, remote: &str, local: &Path, m: &mut Meter<'_>) -> Result<(), String> {
    let meta = sftp.metadata(remote).await.map_err(err)?;
    if meta.is_dir() {
        tokio::fs::create_dir_all(local).await.map_err(err)?;
        for e in list(sftp, remote).await? {
            // Names come from the server: none may lead out of the local folder. Skipped (and told at the
            // end), the rest is still copied.
            if !safe_local_name(&e.name) {
                m.skipped.push(e.name.clone());
                continue;
            }
            // Links to folders are not followed (a link to « / » would copy the whole server).
            if e.link && e.dir {
                m.links.push(join(remote, &e.name));
                continue;
            }
            Box::pin(download(sftp, &join(remote, &e.name), &local.join(&e.name), m)).await?;
        }
        return Ok(());
    }
    let mut src = sftp.open(remote).await.map_err(err)?;
    let mut dst = tokio::fs::File::create(local).await.map_err(err)?;
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = src.read(&mut buf).await.map_err(err)?;
        if n == 0 {
            break;
        }
        dst.write_all(&buf[..n]).await.map_err(err)?;
        m.add(n as u64);
    }
    dst.flush().await.map_err(err)
}

async fn upload(sftp: &SftpSession, local: &Path, remote: &str, m: &mut Meter<'_>) -> Result<(), String> {
    let meta = tokio::fs::metadata(local).await.map_err(err)?;
    if meta.is_dir() {
        if !sftp.try_exists(remote).await.unwrap_or(false) {
            sftp.create_dir(remote).await.map_err(err)?;
        }
        let mut rd = tokio::fs::read_dir(local).await.map_err(err)?;
        while let Some(e) = rd.next_entry().await.map_err(err)? {
            // Links to folders are skipped: a link to a parent would loop forever.
            if e.file_type().await.map_err(err)?.is_symlink() && tokio::fs::metadata(e.path()).await.map(|m| m.is_dir()).unwrap_or(true) {
                m.links.push(e.path().display().to_string());
                continue;
            }
            let name = e.file_name().to_string_lossy().into_owned();
            Box::pin(upload(sftp, &e.path(), &join(remote, &name), m)).await?;
        }
        return Ok(());
    }
    let mut src = tokio::fs::File::open(local).await.map_err(err)?;
    let mut dst = sftp.create(remote).await.map_err(err)?;
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = src.read(&mut buf).await.map_err(err)?;
        if n == 0 {
            break;
        }
        dst.write_all(&buf[..n]).await.map_err(err)?;
        m.add(n as u64);
    }
    dst.shutdown().await.map_err(err)
}

/// A readable size (`12,4 Mo` / `12.4 MB`).
pub fn human_size(n: u64) -> String {
    let units = if nautile_core::i18n::is_english() {
        ["B", "KB", "MB", "GB", "TB"]
    } else {
        ["o", "Ko", "Mo", "Go", "To"]
    };
    let mut v = n as f64;
    let mut u = 0;
    while v >= 1024.0 && u < units.len() - 1 {
        v /= 1024.0;
        u += 1;
    }
    let s = if u == 0 { format!("{n}") } else { format!("{v:.1}") };
    let s = if nautile_core::i18n::is_english() { s } else { s.replace('.', ",") };
    format!("{s} {}", units[u])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths() {
        assert_eq!(join("/home/mrd", "a"), "/home/mrd/a");
        assert_eq!(join("/", "etc"), "/etc");
        assert_eq!(parent("/home/mrd/"), "/home");
        assert_eq!(parent("/home"), "/");
        assert_eq!(parent("/"), "/");
    }

    #[test]
    fn folder_links_not_followed_are_told_at_the_end() {
        let (up, _rx) = tokio::sync::mpsc::unbounded_channel();
        let mut m = Meter {
            id: 1,
            up: &up,
            done: 0,
            total: 0,
            last: Instant::now(),
            skipped: Vec::new(),
            links: Vec::new(),
        };
        assert_eq!(m.left_out(false), None);
        m.links.push("/srv/www/current".into());
        let e = m.left_out(false).unwrap();
        assert!(e.contains("1 lien(s)") && e.contains("/srv/www/current"), "{e}");
        m.skipped.push("a:b".into());
        assert_eq!(m.left_out(false).unwrap().lines().count(), 2);
    }

    #[test]
    fn remote_names_cannot_leave_the_local_folder() {
        for windows in [false, true] {
            for ok in ["a", "notes.txt", ".bashrc", "a b", "..x", "COM10", "console.log", "LPT.txt"] {
                assert!(local_name_ok(ok, windows), "{ok} {windows}");
            }
            for bad in ["", ".", "..", "../x", "../../.bashrc", "/etc/passwd", "a/b", "a\0b"] {
                assert!(!local_name_ok(bad, windows), "{bad:?} {windows}");
            }
        }
        // Fine elsewhere, impossible (or dangerous) on Windows.
        for name in [
            "..\\x",
            "a\\b",
            "C:\\x",
            "a:b",
            "x..",
            "x.",
            "x ",
            "a<b",
            "a>b",
            "a\"b",
            "a|b",
            "a?b",
            "a*b",
            "a\tb",
            "CON",
            "con",
            "Nul.txt",
            "aux.tar.gz",
            "COM1",
            "com0",
            "LPT9.log",
            "COM\u{b9}",
            "lpt\u{b3}.txt",
            "CON .txt",
        ] {
            assert!(local_name_ok(name, false), "{name:?}");
            assert!(!local_name_ok(name, true), "{name:?}");
        }
        assert_eq!(safe_local_name("a:b"), !cfg!(windows));
        assert!(!remote_name_ok("../x") && !remote_name_ok("..") && remote_name_ok("a\\b"));
    }

    #[cfg(unix)]
    #[test]
    fn a_local_link_loop_is_not_followed() {
        let dir = std::env::temp_dir().join(format!("nautile-sftp-loop-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("a")).unwrap();
        std::fs::write(dir.join("a/f"), b"12345").unwrap();
        std::os::unix::fs::symlink("..", dir.join("a/loop")).unwrap();
        std::os::unix::fs::symlink("f", dir.join("a/f-link")).unwrap();
        assert_eq!(local_size(&dir), 10, "the file and the link to it, not the loop");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
