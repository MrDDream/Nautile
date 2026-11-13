//! SFTP over an SSH connection (same chain, proxy and prompts as a shell): listings and transfers, folders
//! included, with their progress.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use russh_sftp::client::SftpSession;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};

use crate::session::{channels, Event, RUNTIME};
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
}

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
    /// A command finished (`error` set when it failed).
    Finished {
        id: u64,
        error: Option<String>,
    },
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
        .filter(|e| e.name != "." && e.name != "..")
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

fn local_size(path: &Path) -> u64 {
    let Ok(m) = std::fs::metadata(path) else { return 0 };
    if !m.is_dir() {
        return m.len();
    }
    std::fs::read_dir(path)
        .map(|rd| rd.flatten().map(|e| local_size(&e.path())).sum())
        .unwrap_or(0)
}

async fn run(sftp: &SftpSession, id: u64, cmd: Cmd, up: &UnboundedSender<Update>) -> Result<(), String> {
    match cmd {
        Cmd::List(path) => {
            let entries = list(sftp, &path).await?;
            let _ = up.send(Update::Listing { path, entries });
        }
        Cmd::Mkdir(path) => sftp.create_dir(path).await.map_err(err)?,
        Cmd::Rename(from, to) => sftp.rename(from, to).await.map_err(err)?,
        Cmd::Remove(path) => remove(sftp, &path).await?,
        Cmd::Download { remote, local } => {
            let total = remote_size(sftp, &remote).await;
            let mut m = Meter {
                id,
                up,
                done: 0,
                total,
                last: Instant::now(),
            };
            download(sftp, &remote, &local, &mut m).await?;
        }
        Cmd::Upload { local, remote } => {
            let total = local_size(&local);
            let mut m = Meter {
                id,
                up,
                done: 0,
                total,
                last: Instant::now(),
            };
            upload(sftp, &local, &remote, &mut m).await?;
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
}
