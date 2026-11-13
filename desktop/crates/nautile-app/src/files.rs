//! The SFTP tab: this computer on the left, the server on the right, transfers below.

use std::path::{Path, PathBuf};

use iced::Task;
use nautile_core::model::Protocol;
use nautile_core::{tr, trf};
use nautile_net::sftp::{self, Cmd, Entry, Update};
use tokio::sync::mpsc::UnboundedSender;

use crate::app::{App, Message};
use crate::sessions::{PendingPrompt, TabState};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Job {
    pub id: u64,
    pub label: String,
    pub done: u64,
    pub total: u64,
    pub finished: bool,
    pub error: Option<String>,
    /// A transfer (shown below) rather than a quick operation.
    pub transfer: bool,
    pub refresh: Refresh,
}

/// What to list again once a command is done.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refresh {
    Nothing,
    Local,
    Remote,
}

/// What is being typed: a new name or a new folder, on one side.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Edit {
    Rename { remote: bool, old: String, new: String },
    NewFolder { remote: bool, name: String },
    Delete { remote: bool, name: String },
}

pub struct Files {
    pub cmd: Option<UnboundedSender<(u64, Cmd)>>,
    pub remote_path: String,
    pub remote: Vec<Entry>,
    pub remote_input: String,
    pub local_path: PathBuf,
    pub local: Vec<Entry>,
    pub local_error: Option<String>,
    pub selected: Option<(bool, String)>,
    pub jobs: Vec<Job>,
    pub edit: Option<Edit>,
    next_id: u64,
    /// The remote folder asked last (answers to older listings are ignored).
    asked: String,
}

#[derive(Debug, Clone)]
pub enum FilesMsg {
    Open { remote: bool, name: String },
    Select { remote: bool, name: String },
    Up(bool),
    Refresh(bool),
    LocalHome,
    RemoteInput(String),
    RemoteGo,
    Transfer { remote: bool, name: String },
    StartEdit(Edit),
    EditInput(String),
    EditGo,
    EditCancel,
    ClearJobs,
}

fn home() -> PathBuf {
    dirs::home_dir().unwrap_or_else(|| PathBuf::from("/"))
}

pub fn list_local(path: &Path) -> Result<Vec<Entry>, String> {
    let mut out = Vec::new();
    for e in std::fs::read_dir(path).map_err(|e| e.to_string())?.flatten() {
        let name = e.file_name().to_string_lossy().into_owned();
        let Ok(m) = std::fs::metadata(e.path()) else { continue };
        out.push(Entry {
            dir: m.is_dir(),
            link: e.file_type().is_ok_and(|t| t.is_symlink()),
            size: m.len(),
            modified: m
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs() as i64),
            mode: None,
            name,
        });
    }
    Entry::sort(&mut out);
    Ok(out)
}

/// `2026-11-13 14:02` in UTC.
pub fn date(secs: i64) -> String {
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // Civil from days (Howard Hinnant).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02} {:02}:{:02}", rem / 3600, rem % 3600 / 60)
}

impl Default for Files {
    fn default() -> Self {
        Files::new()
    }
}

impl Files {
    pub fn new() -> Files {
        let local_path = home();
        let mut f = Files {
            cmd: None,
            remote_path: String::new(),
            remote: Vec::new(),
            remote_input: String::new(),
            local_path,
            local: Vec::new(),
            local_error: None,
            selected: None,
            jobs: Vec::new(),
            edit: None,
            next_id: 0,
            asked: String::new(),
        };
        f.refresh_local();
        f
    }

    fn refresh_local(&mut self) {
        match list_local(&self.local_path) {
            Ok(l) => {
                self.local = l;
                self.local_error = None;
            }
            Err(e) => self.local_error = Some(e),
        }
    }

    fn send(&mut self, cmd: Cmd, label: Option<String>) -> u64 {
        let refresh = match cmd {
            Cmd::List(_) => Refresh::Nothing,
            Cmd::Download { .. } => Refresh::Local,
            _ => Refresh::Remote,
        };
        self.next_id += 1;
        let id = self.next_id;
        if let Some(tx) = &self.cmd {
            let _ = tx.send((id, cmd));
        }
        self.jobs.push(Job {
            id,
            label: label.clone().unwrap_or_default(),
            done: 0,
            total: 0,
            finished: false,
            error: None,
            transfer: label.is_some(),
            refresh,
        });
        id
    }

    fn cd_remote(&mut self, path: String) {
        self.asked = path.clone();
        self.remote_input = path.clone();
        self.send(Cmd::List(path), None);
    }

    pub fn remote_ready(&self) -> bool {
        self.cmd.is_some() && !self.remote_path.is_empty()
    }

    /// Sends a file or folder of this computer to the remote folder shown.
    pub fn upload(&mut self, local: PathBuf) {
        let name = local.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let remote = sftp::join(&self.remote_path, &name);
        self.send(Cmd::Upload { local, remote }, Some(format!("↑ {name}")));
    }

    fn update(&mut self, m: FilesMsg) {
        match m {
            FilesMsg::Open { remote, name } => {
                if remote {
                    let e = self.remote.iter().find(|e| e.name == name);
                    if e.is_some_and(|e| e.dir) {
                        let p = sftp::join(&self.remote_path, &name);
                        self.cd_remote(p);
                    } else {
                        self.selected = Some((true, name));
                    }
                } else if self.local.iter().any(|e| e.name == name && e.dir) {
                    self.local_path = self.local_path.join(&name);
                    self.selected = None;
                    self.refresh_local();
                } else {
                    self.selected = Some((false, name));
                }
            }
            FilesMsg::Select { remote, name } => self.selected = Some((remote, name)),
            FilesMsg::Up(true) => {
                let p = sftp::parent(&self.remote_path);
                self.cd_remote(p);
            }
            FilesMsg::Up(false) => {
                if let Some(p) = self.local_path.parent() {
                    self.local_path = p.to_path_buf();
                    self.refresh_local();
                }
            }
            FilesMsg::Refresh(true) => {
                let p = self.remote_path.clone();
                self.cd_remote(p);
            }
            FilesMsg::Refresh(false) => self.refresh_local(),
            FilesMsg::LocalHome => {
                self.local_path = home();
                self.refresh_local();
            }
            FilesMsg::RemoteInput(v) => self.remote_input = v,
            FilesMsg::RemoteGo => {
                let p = self.remote_input.trim().to_owned();
                if !p.is_empty() {
                    self.cd_remote(p);
                }
            }
            FilesMsg::Transfer { remote, name } => {
                if remote {
                    let src = sftp::join(&self.remote_path, &name);
                    let local = self.local_path.join(&name);
                    self.send(Cmd::Download { remote: src, local }, Some(format!("↓ {name}")));
                } else {
                    self.upload(self.local_path.join(&name));
                }
            }
            FilesMsg::StartEdit(e) => self.edit = Some(e),
            FilesMsg::EditInput(v) => match &mut self.edit {
                Some(Edit::Rename { new, .. }) => *new = v,
                Some(Edit::NewFolder { name, .. }) => *name = v,
                _ => {}
            },
            FilesMsg::EditCancel => self.edit = None,
            FilesMsg::EditGo => {
                let Some(e) = self.edit.take() else { return };
                match e {
                    Edit::Rename { remote: true, old, new } if !new.trim().is_empty() && new != old => {
                        let (a, b) = (sftp::join(&self.remote_path, &old), sftp::join(&self.remote_path, new.trim()));
                        self.send(Cmd::Rename(a, b), None);
                    }
                    Edit::NewFolder { remote: true, name } if !name.trim().is_empty() => {
                        let p = sftp::join(&self.remote_path, name.trim());
                        self.send(Cmd::Mkdir(p), None);
                    }
                    Edit::Delete { remote: true, name } => {
                        let p = sftp::join(&self.remote_path, &name);
                        self.send(Cmd::Remove(p), None);
                    }
                    Edit::Rename { remote: false, old, new } if !new.trim().is_empty() => {
                        let r = std::fs::rename(self.local_path.join(&old), self.local_path.join(new.trim()));
                        self.local_error = r.err().map(|e| e.to_string());
                        self.refresh_local_keep_error();
                    }
                    Edit::NewFolder { remote: false, name } if !name.trim().is_empty() => {
                        let r = std::fs::create_dir(self.local_path.join(name.trim()));
                        self.local_error = r.err().map(|e| e.to_string());
                        self.refresh_local_keep_error();
                    }
                    Edit::Delete { remote: false, name } => {
                        let p = self.local_path.join(&name);
                        let r = if p.is_dir() {
                            std::fs::remove_dir_all(&p)
                        } else {
                            std::fs::remove_file(&p)
                        };
                        self.local_error = r.err().map(|e| e.to_string());
                        self.refresh_local_keep_error();
                    }
                    _ => {}
                }
                self.selected = None;
            }
            FilesMsg::ClearJobs => self.jobs.retain(|j| !j.finished),
        }
    }

    fn refresh_local_keep_error(&mut self) {
        let e = self.local_error.take();
        self.refresh_local();
        if e.is_some() {
            self.local_error = e;
        }
    }

    /// Returns a message to show, if any.
    fn on_update(&mut self, u: Update) -> Option<String> {
        match u {
            Update::Ready(home) => self.cd_remote(home),
            Update::Listing { path, entries } => {
                if path == self.asked {
                    self.remote_path = path.clone();
                    self.remote_input = path;
                    self.remote = entries;
                    if self.selected.as_ref().is_some_and(|s| s.0) {
                        self.selected = None;
                    }
                }
            }
            Update::Progress { id, done, total } => {
                if let Some(j) = self.jobs.iter_mut().find(|j| j.id == id) {
                    j.done = done;
                    j.total = total;
                }
            }
            Update::Finished { id, error } => {
                let i = self.jobs.iter().position(|j| j.id == id)?;
                let job = &mut self.jobs[i];
                let refresh = job.refresh;
                if job.transfer {
                    job.finished = true;
                    job.done = job.total.max(job.done);
                    job.error = error.clone();
                } else {
                    self.jobs.remove(i);
                }
                match refresh {
                    Refresh::Local => self.refresh_local(),
                    Refresh::Remote if !self.remote_path.is_empty() => {
                        let p = self.remote_path.clone();
                        self.cd_remote(p);
                    }
                    _ => {}
                }
                if let Some(e) = error {
                    return Some(e);
                }
            }
            Update::Conn(_) => {}
        }
        None
    }
}

impl App {
    /// Opens a file browser on a saved SSH host.
    pub fn open_files(&mut self, host_id: i64) -> Task<Message> {
        let Some(h) = self.host_by(host_id).cloned() else {
            return Task::none();
        };
        if !matches!(h.protocol, Protocol::Ssh | Protocol::Mosh) {
            return self.say(tr("Les fichiers passent par SSH"));
        }
        let params = match self.ssh_params_for(&h) {
            Ok(mut p) => {
                p.forwards.clear();
                p.startup_command = None;
                p
            }
            Err(e) => return self.say(e),
        };
        self.editor = None;
        let id = self.new_tab(Some(h.id), trf("%1$s — fichiers", &[&h.alias]), h.color, h.protocol);
        let browser = sftp::spawn(params);
        if let Some(t) = self.tabs.iter_mut().find(|t| t.id == id) {
            let mut f = Files::new();
            f.cmd = Some(browser.cmd);
            t.files = Some(Box::new(f));
        }
        let rx = browser.updates;
        let stream = iced::futures::stream::unfold(rx, |mut rx| async move { rx.recv().await.map(|e| (e, rx)) });
        Task::run(stream, move |u| Message::Sftp(id, u))
    }

    pub fn on_sftp(&mut self, id: u64, u: Update) -> Task<Message> {
        if let Update::Conn(e) = u {
            return match e {
                nautile_net::Event::Prompt(p) => {
                    let secret = matches!(p, nautile_net::Prompt::Secret { .. });
                    self.prompts.push(PendingPrompt {
                        tab: Some(id),
                        forward: None,
                        prompt: p,
                    });
                    if secret && self.prompts.len() == 1 {
                        iced::widget::operation::focus("prompt-secret")
                    } else {
                        Task::none()
                    }
                }
                nautile_net::Event::Closed(reason) => {
                    if let Some(t) = self.tabs.iter_mut().find(|t| t.id == id) {
                        t.state = TabState::Closed(reason);
                        if let Some(f) = &mut t.files {
                            f.cmd = None;
                        }
                    }
                    Task::none()
                }
                other => self.on_session(id, other),
            };
        }
        let ready = matches!(u, Update::Ready(_));
        let Some(t) = self.tabs.iter_mut().find(|t| t.id == id) else {
            return Task::none();
        };
        if ready {
            t.state = TabState::Open;
        }
        let msg = t.files.as_mut().and_then(|f| f.on_update(u));
        match msg {
            Some(m) => self.say(trf("Échec : %1$s", &[&m])),
            None => Task::none(),
        }
    }

    pub fn on_files(&mut self, id: u64, m: FilesMsg) {
        if let Some(f) = self.tabs.iter_mut().find(|t| t.id == id).and_then(|t| t.files.as_mut()) {
            f.update(m);
        }
    }

    /// A file dropped on the window goes to the server shown.
    pub fn dropped(&mut self, path: PathBuf) -> Task<Message> {
        let Some(id) = self.active_tab else { return Task::none() };
        match self.tabs.iter_mut().find(|t| t.id == id).and_then(|t| t.files.as_mut()) {
            Some(f) if f.remote_ready() => {
                f.upload(path);
                Task::none()
            }
            _ => Task::none(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dates_are_readable() {
        assert_eq!(date(0), "1970-01-01 00:00");
        assert_eq!(date(1_794_578_531), "2026-11-13 14:02");
    }
}
