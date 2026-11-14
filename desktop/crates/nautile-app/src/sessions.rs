//! Open sessions (one tab each), their questions, and the tunnels started from the Tunnels page.

use iced::Task;
use iced::futures::stream;
use nautile_core::model::now_ms;
use nautile_core::model::{AuthMethod, ForwardKind, Host, Identity, KnownHost, Protocol};
use nautile_core::{tr, trf};
use nautile_net::proxy::Proxy;
use nautile_net::ssh::{self, Auth, Forward, Params, Target};
use nautile_net::{Event, Input, KnownKey, Prompt};
use tokio::sync::mpsc::UnboundedSender;

use crate::app::{App, Message};
use crate::term::widget::Action;
use crate::term::{Terminal, themes};

/// Longest chain of jump hosts (as on Android).
const MAX_HOPS: usize = 6;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TabState {
    /// The current step.
    Connecting(String),
    Open,
    /// `None`: ended normally.
    Closed(Option<String>),
}

pub struct Tab {
    pub id: u64,
    /// The remote desktop of a VNC or RDP tab (no terminal then).
    pub remote: Option<Box<RemoteTab>>,
    /// The file browser of an SFTP tab (no terminal then).
    pub files: Option<Box<crate::files::Files>>,
    /// `None` for a local terminal opened without a saved host.
    pub host_id: Option<i64>,
    pub name: String,
    pub color: i64,
    pub protocol: Protocol,
    pub term: Terminal,
    pub input: Option<UnboundedSender<Input>>,
    pub state: TabState,
    /// What the shell put in the title (`user@host: ~`).
    pub remote_title: Option<String>,
    /// The copy of the output, when sessions are recorded.
    pub log: Option<crate::logs::SessionLog>,
    /// The readings of a monitor tab (no terminal then).
    pub monitor: Option<Box<MonitorState>>,
}

/// The last readings of a host's CPU, memory, disk and load, for the gauges and the small curves.
#[derive(Debug, Default)]
pub struct MonitorState {
    pub last: Option<nautile_net::monitor::Sample>,
    pub cpu: Option<f32>,
    /// The last two minutes, in percent.
    pub cpu_history: std::collections::VecDeque<f32>,
    pub mem_history: std::collections::VecDeque<f32>,
    /// The output was not a Linux one.
    pub unsupported: bool,
}

impl MonitorState {
    pub fn push(&mut self, text: &str) {
        let Some(s) = nautile_net::monitor::parse(text) else {
            self.unsupported = true;
            return;
        };
        self.unsupported = false;
        if let Some(prev) = &self.last {
            let cpu = s.cpu_percent(prev);
            self.cpu = Some(cpu);
            self.cpu_history.push_back(cpu);
        }
        self.mem_history.push_back(s.mem_percent());
        for h in [&mut self.cpu_history, &mut self.mem_history] {
            while h.len() > 40 {
                h.pop_front();
            }
        }
        self.last = Some(s);
    }
}

impl Tab {
    pub fn send_text(&self, s: &str) {
        self.send(Input::Data(s.as_bytes().to_vec()));
    }

    fn send(&self, i: Input) {
        if let Some(tx) = &self.input {
            let _ = tx.send(i);
        }
    }
}

/// A remote desktop being shown.
pub struct RemoteTab {
    pub input: std::sync::mpsc::Sender<nautile_remote::RemoteInput>,
    pub frame: nautile_remote::SharedFrame,
    pub image: Option<iced::widget::image::Handle>,
    /// A copy of the picture is already asked: the frames announced meanwhile need no copy of their own.
    pub frame_pending: bool,
    pub size: (u32, u32),
    /// The server's pointer: image, width, height, hot spot.
    pub cursor: Option<(iced::widget::image::Handle, u32, u32, u32, u32)>,
}

/// A question waiting for the user, from a tab or a tunnel.
#[derive(Debug, Clone)]
pub struct PendingPrompt {
    pub tab: Option<u64>,
    pub forward: Option<i64>,
    pub prompt: Prompt,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TunnelState {
    Starting(String),
    Running,
    Failed(String),
}

pub struct TunnelRun {
    pub input: UnboundedSender<Input>,
    pub state: TunnelState,
}

/// What a connection's thread sends, as a stream for [Task::run].
pub(crate) fn recv_stream<T: Send + 'static>(rx: tokio::sync::mpsc::UnboundedReceiver<T>) -> impl iced::futures::Stream<Item = T> {
    stream::unfold(rx, |mut rx| async move { rx.recv().await.map(|e| (e, rx)) })
}

/// Largest text a program may put in the clipboard (OSC 52).
const MAX_OSC52: usize = 100_000;

impl App {
    pub fn tab(&self, id: u64) -> Option<&Tab> {
        self.tabs.iter().find(|t| t.id == id)
    }

    fn tab_mut(&mut self, id: u64) -> Option<&mut Tab> {
        self.tabs.iter_mut().find(|t| t.id == id)
    }

    /// A saved host, or one typed in the search bar (negative id, never saved).
    pub fn host_by(&self, id: i64) -> Option<&Host> {
        self.hosts
            .iter()
            .find(|h| h.id == id)
            .or_else(|| self.quick.iter().find(|h| h.id == id))
    }

    /// Connects to a machine typed in the search bar, without saving it.
    pub fn quick_connect(&mut self, mut h: Host) -> Task<Message> {
        let id = -(self.quick.len() as i64) - 1;
        h.id = id;
        self.quick.push(h);
        self.search.clear();
        self.connect(id)
    }

    pub fn term_theme(&self) -> themes::TermTheme {
        themes::by_id(self.settings.terminal_theme())
    }

    /// The password of [h]'s login: its identity's, else its own.
    pub fn host_password(&self, h: &Host) -> Option<String> {
        match self.identity_of(h) {
            Some(id) => id.password.clone(),
            None => h.password.clone(),
        }
        .filter(|p| !p.is_empty())
    }

    /// Types the host's password and Enter in the active session (for a sudo prompt).
    pub fn type_password(&mut self) {
        let Some(t) = self.active_tab.and_then(|id| self.tab(id)) else {
            return;
        };
        let Some(h) = t.host_id.and_then(|h| self.host_by(h)) else {
            return;
        };
        if let Some(p) = self.host_password(h) {
            t.send(Input::Data(format!("{p}\r").into_bytes()));
        }
    }

    /// The identity [h] logs in with, when it uses one.
    fn identity_of(&self, h: &Host) -> Option<&Identity> {
        (h.auth_method == AuthMethod::Identity)
            .then(|| h.identity_id.and_then(|i| self.identities.iter().find(|x| x.id == i)))
            .flatten()
    }

    /// The keys saved for [host]:[port] (SSH host keys, RDP certificates).
    fn known_keys(&self, host: &str, port: i32) -> Vec<KnownKey> {
        self.known_hosts
            .iter()
            .filter(|k| k.host == host && k.port == port)
            .map(|k| KnownKey {
                key_type: k.key_type.clone(),
                public_key: k.public_key.clone(),
                fingerprint: k.fingerprint.clone(),
            })
            .collect()
    }

    /// Shows a connection's question, unless what asked it is already closed.
    pub(crate) fn push_prompt(&mut self, tab: Option<u64>, forward: Option<i64>, prompt: Prompt) -> Task<Message> {
        let gone = match (tab, forward) {
            (Some(id), _) => self.tab(id).is_none(),
            (None, Some(f)) => !self.tunnels.contains_key(&f),
            (None, None) => false,
        };
        if gone {
            prompt.cancel();
            return Task::none();
        }
        let secret = matches!(prompt, Prompt::Secret { .. });
        self.prompts.push(PendingPrompt { tab, forward, prompt });
        if secret && self.prompts.len() == 1 {
            iced::widget::operation::focus("prompt-secret")
        } else {
            Task::none()
        }
    }

    pub fn cursor_shape(&self) -> alacritty_terminal::vte::ansi::CursorShape {
        crate::term::cursor_shape(self.settings.terminal_cursor())
    }

    /// The hops to [host]: outermost jump host first; and the host of the first hop.
    fn chain(&self, host: &Host) -> Result<(Vec<Target>, Host), String> {
        let mut chain = Vec::new();
        let mut seen = Vec::new();
        let mut current = Some(host.clone());
        let mut first = host.clone();
        while let Some(h) = current {
            if seen.contains(&h.id) || seen.len() >= MAX_HOPS {
                return Err(tr("Boucle ou chaîne de rebond trop longue"));
            }
            seen.push(h.id);
            chain.insert(0, self.target(&h)?);
            current = h.jump_host_id.and_then(|j| self.host_by(j).cloned());
            first = h;
        }
        Ok((chain, first))
    }

    fn key_auth(&self, alias: &str, key_id: Option<i64>) -> Result<Option<Auth>, String> {
        let Some(id) = key_id else { return Ok(None) };
        let key = self
            .keys
            .iter()
            .find(|k| k.id == id)
            .ok_or_else(|| trf("%1$s : la clé a été supprimée du trousseau", &[&alias]))?;
        Ok(Some(Auth::Key {
            private: key.private_key.clone(),
            passphrase: key.saved_passphrase.clone(),
        }))
    }

    fn target(&self, h: &Host) -> Result<Target, String> {
        let mut username = h.username.clone();
        let auth = match h.auth_method {
            AuthMethod::Key => match self.key_auth(&h.alias, h.key_id)? {
                Some(a) => a,
                None => match h.identity.as_deref().filter(|t| t.contains("PRIVATE KEY")) {
                    Some(text) => Auth::Key {
                        private: text.to_owned(),
                        passphrase: None,
                    },
                    None => return Err(trf("%1$s : aucune clé choisie", &[&h.alias])),
                },
            },
            AuthMethod::Identity => {
                let id = self.identity_of(h).ok_or_else(|| trf("%1$s : identité introuvable", &[&h.alias]))?;
                username = id.username.clone();
                match self.key_auth(&h.alias, id.key_id)? {
                    Some(a) => a,
                    None => Auth::Password(id.password.clone().filter(|p| !p.is_empty())),
                }
            }
            AuthMethod::Password | AuthMethod::Agent => Auth::Password(h.password.clone().filter(|p| !p.is_empty())),
        };
        let known = self.known_keys(&h.hostname, h.port);
        Ok(Target {
            host: h.hostname.clone(),
            port: h.port.clamp(1, 65535) as u16,
            username: if username.trim().is_empty() { "root".into() } else { username },
            auth,
            label: h.alias.clone(),
            known,
        })
    }

    fn forward(f: &nautile_core::model::PortForward) -> Forward {
        Forward {
            kind: f.kind,
            bind_host: f.bind_host.clone(),
            bind_port: f.bind_port.clamp(0, 65535) as u16,
            dest_host: f.dest_host.clone(),
            dest_port: f.dest_port.clamp(0, 65535) as u16,
        }
    }

    /// Everything needed to reach [host] over SSH.
    pub(crate) fn ssh_params(&self, host: &Host) -> Result<Params, String> {
        let (chain, first) = self.chain(host)?;
        let proxy = first.proxy_type.and_then(|kind| {
            Some(Proxy {
                kind,
                host: first.proxy_host.clone().filter(|h| !h.is_empty())?,
                port: first.proxy_port.unwrap_or(1080).clamp(1, 65535) as u16,
                username: first.proxy_username.clone(),
                password: first.proxy_password.clone(),
            })
        });
        Ok(Params {
            chain,
            proxy,
            knock: first.knock_sequence.clone(),
            agent_forwarding: host.agent_forwarding,
            startup_command: host.startup_command.clone(),
            forwards: self
                .forwards
                .iter()
                .filter(|f| f.host_id == host.id && f.auto_start)
                .map(Self::forward)
                .collect(),
        })
    }

    pub(crate) fn open_tab(&mut self, host_id: Option<i64>, name: String, color: i64, protocol: Protocol) -> u64 {
        self.next_tab += 1;
        let (cols, rows) = self.grid;
        self.tabs.push(Tab {
            id: self.next_tab,
            host_id,
            name,
            color,
            protocol,
            term: Terminal::new(cols, rows, self.term_theme(), self.cursor_shape()),
            input: None,
            state: TabState::Connecting(String::new()),
            remote_title: None,
            files: None,
            remote: None,
            log: None,
            monitor: None,
        });
        self.active_tab = Some(self.next_tab);
        self.last_tab = self.active_tab;
        self.next_tab
    }

    /// Opens the remote desktop of tab [id] (its ports already knocked).
    fn start_remote(&mut self, id: u64, h: &Host) -> Task<Message> {
        let r = if h.protocol == Protocol::Vnc {
            nautile_remote::vnc::spawn(
                h.hostname.clone(),
                h.port.clamp(1, 65535) as u16,
                h.alias.clone(),
                h.password.clone().filter(|p| !p.is_empty()),
            )
        } else {
            nautile_remote::rdp::spawn(self.rdp_params(h))
        };
        if let Some(t) = self.tab_mut(id) {
            t.state = TabState::Connecting(String::new());
            t.remote = Some(Box::new(RemoteTab {
                input: r.input,
                frame: r.frame,
                image: None,
                frame_pending: false,
                size: (0, 0),
                cursor: None,
            }));
        }
        Task::run(recv_stream(r.events), move |e| Message::Remote(id, e))
    }

    /// The ports of a remote desktop's host were knocked: connects, or says why not.
    pub fn knocked(&mut self, id: u64, r: Result<(), String>) -> Task<Message> {
        let Some(h) = self.tab(id).and_then(|t| t.host_id).and_then(|h| self.host_by(h)).cloned() else {
            return Task::none();
        };
        match r {
            Ok(()) => self.start_remote(id, &h),
            Err(e) => {
                if let Some(t) = self.tab_mut(id) {
                    t.state = TabState::Closed(Some(e));
                }
                Task::none()
            }
        }
    }

    /// Starts the connection of tab [id].
    fn start(&mut self, id: u64) -> Task<Message> {
        let Some(tab) = self.tab(id) else { return Task::none() };
        let (cols, rows) = (tab.term.cols(), tab.term.rows());
        let host = tab.host_id.and_then(|h| self.host_by(h).cloned());
        if let (Protocol::Vnc | Protocol::Rdp, Some(h)) = (tab.protocol, host.clone()) {
            // The ports are knocked first, as for SSH.
            if let Some(seq) = h.knock_sequence.clone().filter(|s| !s.trim().is_empty()) {
                if let Some(t) = self.tab_mut(id) {
                    t.state = TabState::Connecting(trf("Port knocking sur %1$s (%2$s)…", &[&h.hostname, &seq]));
                }
                let address = h.hostname.clone();
                return Task::perform(
                    async move {
                        let knocks = nautile_net::proxy::parse_knocks(&seq)?;
                        nautile_net::proxy::knock(&address, &knocks).await.map_err(|e| e.to_string())
                    },
                    move |r| Message::Knocked(id, r),
                );
            }
            return self.start_remote(id, &h);
        }
        let session = match (tab.protocol, host) {
            (Protocol::Local, _) => nautile_net::local::spawn(None, cols, rows),
            (Protocol::Telnet, Some(h)) => nautile_net::telnet::spawn(h.hostname.clone(), h.port.clamp(1, 65535) as u16, cols, rows),
            (Protocol::Ssh | Protocol::Mosh, Some(h)) => match self.ssh_params(&h) {
                Ok(p) if h.protocol == Protocol::Mosh => nautile_net::mosh::spawn(p, cols, rows),
                Ok(p) => ssh::spawn(p, cols, rows),
                Err(e) => {
                    if let Some(t) = self.tab_mut(id) {
                        t.state = TabState::Closed(Some(e));
                    }
                    return Task::none();
                }
            },
            _ => {
                if let Some(t) = self.tab_mut(id) {
                    t.state = TabState::Closed(Some(tr("Ce type de connexion arrive bientôt sur ordinateur")));
                }
                return Task::none();
            }
        };
        if let Some(t) = self.tab_mut(id) {
            t.input = Some(session.input);
            t.state = TabState::Connecting(String::new());
        }
        Task::run(recv_stream(session.events), move |e| Message::Session(id, e))
    }

    /// Opens a session to a saved host.
    pub fn connect(&mut self, host_id: i64) -> Task<Message> {
        let Some(h) = self.host_by(host_id).cloned() else {
            return Task::none();
        };
        self.editor = None;
        let id = self.open_tab(Some(h.id), h.alias.clone(), h.color, h.protocol);
        self.start(id)
    }

    pub fn open_local(&mut self) -> Task<Message> {
        self.editor = None;
        let id = self.open_tab(None, tr("Terminal local"), nautile_core::model::DEFAULT_COLOR, Protocol::Local);
        self.start(id)
    }

    /// Opens the CPU, memory and disk of an SSH host, read every few seconds.
    pub fn open_monitor(&mut self, host_id: i64) -> Task<Message> {
        let Some(h) = self.host_by(host_id).cloned() else {
            return Task::none();
        };
        if !matches!(h.protocol, Protocol::Ssh | Protocol::Mosh) {
            return self.say(tr("Le moniteur passe par SSH"));
        }
        let params = match self.ssh_params(&h) {
            Ok(mut p) => {
                p.forwards.clear();
                p.startup_command = None;
                p
            }
            Err(e) => return self.say(e),
        };
        self.editor = None;
        let id = self.open_tab(Some(h.id), trf("%1$s — moniteur", &[&h.alias]), h.color, h.protocol);
        let session = nautile_net::monitor::spawn(params);
        if let Some(t) = self.tab_mut(id) {
            t.input = Some(session.input);
            t.monitor = Some(Box::default());
        }
        Task::run(recv_stream(session.events), move |e| Message::Session(id, e))
    }

    pub fn reconnect(&mut self, id: u64) -> Task<Message> {
        if let Some(t) = self.tab(id).filter(|t| t.monitor.is_some()) {
            let host = t.host_id;
            self.close_tab(id);
            return host.map(|h| self.open_monitor(h)).unwrap_or_else(Task::none);
        }
        if let Some(t) = self.tab(id).filter(|t| t.files.is_some()) {
            let host = t.host_id;
            self.close_tab(id);
            return host.map(|h| self.open_files(h)).unwrap_or_else(Task::none);
        }
        let theme = self.term_theme();
        let cursor = self.cursor_shape();
        let (cols, rows) = self.grid;
        if let Some(t) = self.tab_mut(id) {
            t.send(Input::Close);
            if let Some(r) = t.remote.take() {
                let _ = r.input.send(nautile_remote::RemoteInput::Close);
            }
            t.term = Terminal::new(cols, rows, theme, cursor);
            t.log = None;
            t.remote_title = None;
        }
        self.start(id)
    }

    pub fn close_tab(&mut self, id: u64) {
        if let Some(t) = self.tab(id) {
            t.send(Input::Close);
            if let Some(r) = &t.remote {
                let _ = r.input.send(nautile_remote::RemoteInput::Close);
            }
        }
        self.prompts.retain(|p| {
            let mine = p.tab == Some(id);
            if mine {
                p.prompt.cancel();
            }
            !mine
        });
        let index = self.tabs.iter().position(|t| t.id == id);
        self.tabs.retain(|t| t.id != id);
        if self.active_tab == Some(id) {
            self.active_tab = index
                .and_then(|i| self.tabs.get(i.min(self.tabs.len().saturating_sub(1))))
                .map(|t| t.id);
        }
    }

    /// Next (or previous) tab; the hosts page counts as the first one.
    pub fn cycle_tab(&mut self, forward: bool) {
        let mut ids: Vec<Option<u64>> = vec![None];
        ids.extend(self.tabs.iter().map(|t| Some(t.id)));
        let i = ids.iter().position(|x| *x == self.active_tab).unwrap_or(0);
        let n = ids.len();
        self.active_tab = ids[if forward { (i + 1) % n } else { (i + n - 1) % n }];
    }

    fn trust(&mut self, info: nautile_net::HostKeyInfo) {
        let Some(db) = &self.db else { return };
        let k: KnownHost = KnownHost {
            id: 0,
            host: info.host,
            port: info.port as i32,
            key_type: info.key_type,
            public_key: info.public_key,
            fingerprint: info.fingerprint,
            added_at: now_ms(),
            extra: Default::default(),
        };
        let _ = db.store().replace_known_host(&k);
        self.known_hosts = db.store().all().unwrap_or_default();
        self.known_hosts.sort_by(|a, b| a.host.cmp(&b.host).then(a.port.cmp(&b.port)));
    }

    /// Remembers a saved host's operating system (for its logo); `replace: false` keeps one already known.
    fn set_host_os(&mut self, host_id: i64, os: &str, replace: bool) {
        let Some(db) = &self.db else { return };
        let Some(h) = self.hosts.iter_mut().find(|h| h.id == host_id && host_id > 0) else {
            return;
        };
        if h.os_id.as_deref() == Some(os) || (!replace && h.os_id.is_some()) {
            return;
        }
        // Targeted write: a full rewrite from this copy would count as an edit and undo merged changes.
        if db.store().set_os(host_id, os).is_ok() {
            h.os_id = Some(os.to_owned());
        }
    }

    pub fn on_session(&mut self, id: u64, e: Event) -> Task<Message> {
        match e {
            Event::Status(s) => {
                if let Some(t) = self.tab_mut(id) {
                    if matches!(t.state, TabState::Connecting(_)) {
                        t.state = TabState::Connecting(s);
                    } else {
                        // A tunnel that could not start, once connected.
                        let msg = format!("\r\n\x1b[33m{s}\x1b[0m\r\n");
                        t.term.feed(msg.as_bytes());
                    }
                }
            }
            Event::Connected => {
                if let Some(h) = self.tab(id).and_then(|t| t.host_id).filter(|h| *h > 0)
                    && !self.history.contains_key(&h)
                {
                    let list = self.db.as_ref().map(|db| db.commands(h)).unwrap_or_default();
                    self.history.insert(h, list);
                }
                let mut host = None;
                let record = self.settings.session_logs;
                let logs = self.paths.data.join("logs");
                if let Some(t) = self.tab_mut(id) {
                    t.state = TabState::Open;
                    host = t.host_id;
                    if record && t.log.is_none() && t.remote.is_none() && t.files.is_none() {
                        t.log = crate::logs::SessionLog::start(&logs, &t.name);
                    }
                }
                if let (Some(h), Some(db)) = (host.and_then(|h| self.hosts.iter_mut().find(|x| x.id == h)), &self.db) {
                    let at = now_ms();
                    if db.store().mark_connected(h.id, at).is_ok() {
                        h.last_connected_at = Some(at);
                    }
                }
                // « Connexions › Historique », quick connections included.
                if let (Some(h), Some(db)) = (host.and_then(|h| self.host_by(h)), &self.db) {
                    let _ = db.log_connection(&nautile_core::db::ConnectionLog {
                        id: 0,
                        host_id: (h.id > 0).then_some(h.id),
                        alias: h.alias.clone(),
                        protocol: protocol_name(h.protocol),
                        username: h.username.clone(),
                        hostname: h.hostname.clone(),
                        port: h.port,
                        at: now_ms(),
                    });
                }
            }
            Event::Output(bytes) => {
                let Some(t) = self.tab_mut(id) else { return Task::none() };
                if let Some(m) = &mut t.monitor {
                    m.push(&String::from_utf8_lossy(&bytes));
                    return Task::none();
                }
                let fx = t.term.feed(&bytes);
                if let Some(log) = &mut t.log {
                    log.write(&bytes);
                }
                if !fx.reply.is_empty() {
                    t.send(Input::Data(fx.reply));
                }
                if let Some(title) = fx.title {
                    t.remote_title = Some(title).filter(|s| !s.is_empty());
                }
                // OSC 52: only when allowed (off by default: a server could put a command there to paste).
                let allowed = self.settings.osc52 && !self.locked;
                if let Some(text) = fx.clipboard.filter(|t| allowed && t.len() <= MAX_OSC52) {
                    return iced::clipboard::write(text);
                }
            }
            Event::Prompt(p) => return self.push_prompt(Some(id), None, p),
            Event::Trust { info, .. } => self.trust(info),
            Event::Os(os) => {
                if let Some(h) = self.tab(id).and_then(|t| t.host_id) {
                    self.set_host_os(h, &os, true);
                }
            }
            Event::Closed(reason) => {
                if let Some(t) = self.tab_mut(id) {
                    t.input = None;
                    t.state = TabState::Closed(reason);
                    // The session's copy ends here (dropping it writes the rest).
                    t.log = None;
                }
                // A shell that ended normally closes its tab, like a terminal window.
                if self
                    .tab(id)
                    .is_some_and(|t| t.state == TabState::Closed(None) && t.protocol == Protocol::Local)
                {
                    self.close_tab(id);
                }
            }
        }
        Task::none()
    }

    /// The commands typed before on the tab's host that begin with what is typed now (at most four).
    pub fn suggestions(&self, id: u64) -> (String, Vec<String>) {
        let none = (String::new(), Vec::new());
        if !self.settings.history_suggestions() {
            return none;
        }
        let Some(t) = self
            .tab(id)
            .filter(|t| t.monitor.is_none() && t.files.is_none() && t.remote.is_none())
        else {
            return none;
        };
        let (Some(host), Some(typed)) = (t.host_id, t.term.typed_command()) else {
            return none;
        };
        if typed.chars().count() < 2 {
            return none;
        }
        let list: Vec<String> = self
            .history
            .get(&host)
            .into_iter()
            .flatten()
            .filter(|(c, _)| c.starts_with(&typed) && *c != typed)
            .take(4)
            .map(|(c, _)| c.clone())
            .collect();
        (typed, list)
    }

    pub fn on_term(&mut self, id: u64, a: Action) {
        if let Action::Resize(c, r) = a {
            self.grid = (c, r);
        }
        if let Action::Input(bytes) = &a {
            // → at the end of the line takes the first suggestion (as in fish).
            if bytes == b"\x1b[C" || bytes == b"\x1bOC" {
                let (typed, list) = self.suggestions(id);
                if let (Some(first), Some(t)) = (list.first(), self.tab(id)) {
                    t.send_text(&first[typed.len()..]);
                    return;
                }
            }
            // Enter: the command shown after the prompt goes to the host's history.
            if bytes == b"\r" && self.settings.history_suggestions() {
                let typed = self
                    .tab(id)
                    .and_then(|t| Some((t.host_id.filter(|h| *h > 0)?, t.term.command_line()?)));
                if let (Some((host, cmd)), Some(db)) = (typed.filter(|(_, c)| !c.trim().is_empty()), &self.db) {
                    let cmd = cmd.trim().to_owned();
                    let _ = db.add_command(host, &cmd);
                    let list = self.history.entry(host).or_default();
                    match list.iter_mut().find(|(c, _)| *c == cmd) {
                        Some(e) => e.1 += 1,
                        None => list.push((cmd, 1)),
                    }
                    list.sort_by_key(|e| std::cmp::Reverse(e.1));
                }
            }
        }
        let Some(t) = self.tab_mut(id) else { return };
        match a {
            Action::Input(bytes) => {
                t.term.scroll_to_bottom();
                t.term.clear_selection();
                t.send(Input::Data(bytes));
            }
            Action::Resize(cols, rows) => {
                t.term.resize(cols, rows);
                t.send(Input::Resize { cols, rows });
            }
            Action::Scroll(n) => t.term.scroll(n),
            Action::SelectStart(kind, row, col, side) => t.term.select_start(kind, row, col, side),
            Action::SelectUpdate(row, col, side) => t.term.select_update(row, col, side),
            Action::ClearSelection => t.term.clear_selection(),
        }
    }

    /// Answers the question shown (the first one).
    pub fn answer(&mut self, accept: bool) {
        if self.prompts.is_empty() {
            return;
        }
        let p = self.prompts.remove(0);
        match &p.prompt {
            Prompt::HostKey { reply, .. } => reply.send(accept),
            Prompt::Secret { reply, .. } => reply.send(accept.then(|| std::mem::take(&mut self.prompt_input))),
        }
        self.prompt_input.clear();
    }

    /// The session snippets go to: the one shown, else the last one shown.
    pub fn target_tab(&self) -> Option<&Tab> {
        let id = self.active_tab.or(self.last_tab)?;
        self.tab(id).filter(|t| t.state == TabState::Open)
    }

    /// Runs a snippet in the session, asking its `${VARIABLES}` first.
    pub fn run_snippet(&mut self, snippet_id: i64) -> Task<Message> {
        let Some(sn) = self.snippets.iter().find(|s| s.id == snippet_id).cloned() else {
            return Task::none();
        };
        // Counted as on Android: the most used come first.
        if let Some(db) = &self.db
            && db.store().count_run(snippet_id).is_ok()
            && let Some(x) = self.snippets.iter_mut().find(|s| s.id == snippet_id)
        {
            x.run_count += 1;
        }
        let vars = sn.variables();
        if !vars.is_empty() {
            self.snippet_run = Some((snippet_id, vars.into_iter().map(|v| (v, String::new())).collect()));
            return Task::none();
        }
        self.send_command(&sn.command);
        Task::none()
    }

    pub fn run_filled_snippet(&mut self) {
        let Some((id, values)) = self.snippet_run.take() else { return };
        let Some(sn) = self.snippets.iter().find(|s| s.id == id) else {
            return;
        };
        let cmd = sn.fill(&values.into_iter().collect());
        self.send_command(&cmd);
    }

    /// Types [command] (possibly multi-line) in the target session, each line followed by Enter.
    fn send_command(&mut self, command: &str) {
        let Some(id) = self.target_tab().map(|t| t.id) else { return };
        let text = command.trim_end_matches('\n').replace("\r\n", "\n").replace('\n', "\r") + "\r";
        if let Some(t) = self.tab_mut(id) {
            t.term.scroll_to_bottom();
            t.send(Input::Data(text.into_bytes()));
        }
        self.active_tab = Some(id);
        self.editor = None;
    }

    // --- Tunnels started by hand ---

    pub fn toggle_tunnel(&mut self, forward_id: i64) -> Task<Message> {
        // A question it was still asking goes with it.
        self.prompts.retain(|p| {
            let mine = p.forward == Some(forward_id);
            if mine {
                p.prompt.cancel();
            }
            !mine
        });
        if let Some(run) = self.tunnels.remove(&forward_id)
            && !matches!(run.state, TunnelState::Failed(_))
        {
            let _ = run.input.send(Input::Close);
            return Task::none();
        }
        let Some(f) = self.forwards.iter().find(|f| f.id == forward_id).cloned() else {
            return Task::none();
        };
        let Some(host) = self.hosts.iter().find(|h| h.id == f.host_id).cloned() else {
            return Task::none();
        };
        let mut params = match self.ssh_params(&host) {
            Ok(p) => p,
            Err(e) => return self.say(e),
        };
        params.forwards = vec![Self::forward(&f)];
        params.startup_command = None;
        let session = ssh::spawn_tunnels(params);
        self.tunnels.insert(
            forward_id,
            TunnelRun {
                input: session.input,
                state: TunnelState::Starting(String::new()),
            },
        );
        Task::run(recv_stream(session.events), move |e| Message::Tunnel(forward_id, e))
    }

    pub fn on_tunnel(&mut self, forward_id: i64, e: Event) -> Task<Message> {
        match e {
            Event::Status(s) => {
                if let Some(r) = self.tunnels.get_mut(&forward_id) {
                    if matches!(r.state, TunnelState::Starting(_)) {
                        r.state = TunnelState::Starting(s);
                    } else {
                        r.state = TunnelState::Failed(s);
                    }
                }
            }
            Event::Connected => {
                if let Some(r) = self.tunnels.get_mut(&forward_id)
                    && !matches!(r.state, TunnelState::Failed(_))
                {
                    r.state = TunnelState::Running;
                }
            }
            Event::Prompt(p) => return self.push_prompt(None, Some(forward_id), p),
            Event::Trust { info, .. } => self.trust(info),
            Event::Closed(reason) => match reason {
                Some(r) => {
                    let name = self.forwards.iter().find(|f| f.id == forward_id).map(|f| f.name.clone());
                    if let Some(run) = self.tunnels.get_mut(&forward_id) {
                        run.state = TunnelState::Failed(r.clone());
                    }
                    if let Some(name) = name {
                        self.notify(trf("Tunnel « %1$s » coupé", &[&name]), r);
                    }
                }
                None => {
                    self.tunnels.remove(&forward_id);
                }
            },
            Event::Output(_) | Event::Os(_) => {}
        }
        Task::none()
    }

    /// The local address a running tunnel listens on, for the Tunnels page.
    pub fn tunnel_hint(f: &nautile_core::model::PortForward) -> Option<String> {
        match f.kind {
            ForwardKind::Remote => None,
            _ => Some(format!(
                "{}:{}",
                if f.bind_host.is_empty() { "127.0.0.1" } else { &f.bind_host },
                f.bind_port
            )),
        }
    }
}

impl App {
    pub fn on_remote(&mut self, id: u64, e: nautile_remote::RemoteEvent) -> Task<Message> {
        use nautile_remote::RemoteEvent as E;
        match e {
            E::Status(s) => {
                if let Some(t) = self.tab_mut(id) {
                    t.state = TabState::Connecting(s);
                }
            }
            E::Ready(name) => {
                let mut rdp_host = None;
                if let Some(t) = self.tab_mut(id) {
                    t.state = TabState::Open;
                    t.remote_title = Some(name).filter(|n| !n.is_empty());
                    rdp_host = t.host_id.filter(|_| t.protocol == Protocol::Rdp);
                }
                // A Remote Desktop server is a Windows one, unless known otherwise.
                if let Some(h) = rdp_host {
                    self.set_host_os(h, "windows", false);
                }
                if self.settings.remote_fullscreen {
                    return iced::window::latest().and_then(|w| iced::window::set_mode(w, iced::window::Mode::Fullscreen));
                }
            }
            // The picture is read once for all the frames announced before it is read (the server may
            // announce them faster than they are drawn).
            E::Frame => {
                if let Some(r) = self.tab_mut(id).and_then(|t| t.remote.as_mut())
                    && !r.frame_pending
                {
                    r.frame_pending = true;
                    return Task::done(Message::RemoteFrame(id));
                }
            }
            E::Cursor(c) => {
                if let Some(r) = self.tab_mut(id).and_then(|t| t.remote.as_mut()) {
                    r.cursor = c.map(|c| {
                        (
                            iced::widget::image::Handle::from_rgba(c.width, c.height, c.rgba),
                            c.width,
                            c.height,
                            c.hot_x,
                            c.hot_y,
                        )
                    });
                }
            }
            E::Clipboard(text) if self.settings.remote_clipboard() => return iced::clipboard::write(text),
            E::Clipboard(_) => {}
            E::Trust(info) => self.trust(info),
            E::Prompt(p) => return self.push_prompt(Some(id), None, p),
            E::Closed(reason) => {
                if let Some(t) = self.tab_mut(id) {
                    t.state = TabState::Closed(reason);
                }
            }
        }
        Task::none()
    }

    /// Hands the remote picture to the renderer (asked by a `RemoteEvent::Frame`).
    pub fn remote_frame(&mut self, id: u64) {
        let Some(r) = self.tab_mut(id).and_then(|t| t.remote.as_mut()) else {
            return;
        };
        r.frame_pending = false;
        let picture = r.frame.lock().unwrap_or_else(|e| e.into_inner()).picture();
        if let Some((width, height, rgba)) = picture {
            r.size = (width, height);
            r.image = Some(iced::widget::image::Handle::from_rgba(width, height, rgba));
        }
    }

    pub fn on_remote_action(&mut self, id: u64, a: crate::remote_view::RemoteAction) {
        let Some(r) = self.tab(id).and_then(|t| t.remote.as_ref()) else {
            return;
        };
        let _ = r.input.send(match a {
            crate::remote_view::RemoteAction::Input(i) => i,
            crate::remote_view::RemoteAction::Paste(text) => nautile_remote::RemoteInput::Clipboard(text),
        });
    }
}

impl App {
    /// The RDP settings of [h]: its own or its identity's credentials, resolution, scale, domain.
    fn rdp_params(&self, h: &Host) -> nautile_remote::rdp::RdpParams {
        let (username, password) = match self.identity_of(h) {
            Some(id) => (id.username.clone(), id.password.clone()),
            None => (h.username.clone(), h.password.clone()),
        };
        let size = h
            .rdp_resolution
            .as_deref()
            .and_then(|r| {
                let (w, hh) = r.trim().split_once(['x', 'X', '×'])?;
                Some((w.trim().parse().ok()?, hh.trim().parse().ok()?))
            })
            .unwrap_or_else(|| {
                if self.settings.rdp_window_size() {
                    // The area the desktop is drawn in: the window less the sidebar and the tab bar.
                    let w = (self.window.width - 260.0).max(800.0) as u16;
                    let h = (self.window.height - 60.0).max(600.0) as u16;
                    (w & !1, h & !1)
                } else {
                    (1600, 900)
                }
            });
        nautile_remote::rdp::RdpParams {
            host: h.hostname.clone(),
            port: h.port.clamp(1, 65535) as u16,
            label: h.alias.clone(),
            username,
            password: password.filter(|p| !p.is_empty()),
            domain: h.rdp_domain.clone(),
            size,
            scale: h.rdp_scale.map(|s| s.clamp(100, 500) as u32),
            known: self.known_keys(&h.hostname, h.port),
        }
    }
}

/// A protocol's name as Android stores it (`SSH`, `RDP`…).
pub fn protocol_name(p: Protocol) -> String {
    serde_json::to_value(p)
        .ok()
        .and_then(|v| v.as_str().map(str::to_owned))
        .unwrap_or_default()
}
