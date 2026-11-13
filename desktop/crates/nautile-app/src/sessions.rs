//! Open sessions (one tab each), their questions, and the tunnels started from the Tunnels page.

use iced::futures::stream;
use iced::Task;
use nautile_core::model::now_ms;
use nautile_core::model::{AuthMethod, ForwardKind, Host, KnownHost, Protocol};
use nautile_core::{tr, trf};
use nautile_net::proxy::Proxy;
use nautile_net::ssh::{self, Auth, Forward, Params, Target};
use nautile_net::{Event, Input, KnownKey, Prompt};
use tokio::sync::mpsc::UnboundedSender;

use crate::app::{App, Message};
use crate::term::widget::Action;
use crate::term::{themes, Terminal};

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
}

impl Tab {
    fn send(&self, i: Input) {
        if let Some(tx) = &self.input {
            let _ = tx.send(i);
        }
    }
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

fn events(rx: tokio::sync::mpsc::UnboundedReceiver<Event>) -> impl iced::futures::Stream<Item = Event> {
    stream::unfold(rx, |mut rx| async move { rx.recv().await.map(|e| (e, rx)) })
}

impl App {
    pub fn tab(&self, id: u64) -> Option<&Tab> {
        self.tabs.iter().find(|t| t.id == id)
    }

    fn tab_mut(&mut self, id: u64) -> Option<&mut Tab> {
        self.tabs.iter_mut().find(|t| t.id == id)
    }

    pub fn term_theme(&self) -> themes::TermTheme {
        themes::by_id(self.settings.terminal_theme())
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
            current = h.jump_host_id.and_then(|j| self.hosts.iter().find(|x| x.id == j).cloned());
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
                let id = h
                    .identity_id
                    .and_then(|i| self.identities.iter().find(|x| x.id == i))
                    .ok_or_else(|| trf("%1$s : identité introuvable", &[&h.alias]))?;
                username = id.username.clone();
                match self.key_auth(&h.alias, id.key_id)? {
                    Some(a) => a,
                    None => Auth::Password(id.password.clone().filter(|p| !p.is_empty())),
                }
            }
            AuthMethod::Password | AuthMethod::Agent => Auth::Password(h.password.clone().filter(|p| !p.is_empty())),
        };
        let known = self
            .known_hosts
            .iter()
            .filter(|k| k.host == h.hostname && k.port == h.port)
            .map(|k| KnownKey {
                key_type: k.key_type.clone(),
                public_key: k.public_key.clone(),
                fingerprint: k.fingerprint.clone(),
            })
            .collect();
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
    fn ssh_params(&self, host: &Host) -> Result<Params, String> {
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

    pub(crate) fn ssh_params_for(&self, host: &Host) -> Result<Params, String> {
        self.ssh_params(host)
    }

    pub(crate) fn new_tab(&mut self, host_id: Option<i64>, name: String, color: i64, protocol: Protocol) -> u64 {
        self.open_tab(host_id, name, color, protocol)
    }

    fn open_tab(&mut self, host_id: Option<i64>, name: String, color: i64, protocol: Protocol) -> u64 {
        self.next_tab += 1;
        let (cols, rows) = self.grid;
        self.tabs.push(Tab {
            id: self.next_tab,
            host_id,
            name,
            color,
            protocol,
            term: Terminal::new(cols, rows, self.term_theme()),
            input: None,
            state: TabState::Connecting(String::new()),
            remote_title: None,
            files: None,
        });
        self.active_tab = Some(self.next_tab);
        self.last_tab = self.active_tab;
        self.next_tab
    }

    /// Starts the connection of tab [id].
    fn start(&mut self, id: u64) -> Task<Message> {
        let Some(tab) = self.tab(id) else { return Task::none() };
        let (cols, rows) = (tab.term.cols(), tab.term.rows());
        let host = tab.host_id.and_then(|h| self.hosts.iter().find(|x| x.id == h).cloned());
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
        Task::run(events(session.events), move |e| Message::Session(id, e))
    }

    /// Opens a session to a saved host.
    pub fn connect(&mut self, host_id: i64) -> Task<Message> {
        let Some(h) = self.hosts.iter().find(|h| h.id == host_id).cloned() else {
            return Task::none();
        };
        if h.protocol.is_desktop() {
            return self.say(tr("Le bureau à distance (RDP, VNC) arrive bientôt sur ordinateur"));
        }
        self.editor = None;
        let id = self.open_tab(Some(h.id), h.alias.clone(), h.color, h.protocol);
        self.start(id)
    }

    pub fn open_local(&mut self) -> Task<Message> {
        self.editor = None;
        let id = self.open_tab(None, tr("Terminal local"), nautile_core::model::DEFAULT_COLOR, Protocol::Local);
        self.start(id)
    }

    pub fn reconnect(&mut self, id: u64) -> Task<Message> {
        if let Some(t) = self.tab(id).filter(|t| t.files.is_some()) {
            let host = t.host_id;
            self.close_tab(id);
            return host.map(|h| self.open_files(h)).unwrap_or_else(Task::none);
        }
        let theme = self.term_theme();
        let (cols, rows) = self.grid;
        if let Some(t) = self.tab_mut(id) {
            t.send(Input::Close);
            t.term = Terminal::new(cols, rows, theme);
            t.remote_title = None;
        }
        self.start(id)
    }

    pub fn close_tab(&mut self, id: u64) {
        if let Some(t) = self.tab(id) {
            t.send(Input::Close);
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
                let mut host = None;
                if let Some(t) = self.tab_mut(id) {
                    t.state = TabState::Open;
                    host = t.host_id;
                }
                if let (Some(h), Some(db)) = (host.and_then(|h| self.hosts.iter_mut().find(|x| x.id == h)), &self.db) {
                    h.last_connected_at = Some(now_ms());
                    let _ = db.store().update(&*h);
                }
            }
            Event::Output(bytes) => {
                let Some(t) = self.tab_mut(id) else { return Task::none() };
                let fx = t.term.feed(&bytes);
                if !fx.reply.is_empty() {
                    t.send(Input::Data(fx.reply));
                }
                if let Some(title) = fx.title {
                    t.remote_title = Some(title).filter(|s| !s.is_empty());
                }
                if let Some(text) = fx.clipboard {
                    return iced::clipboard::write(text);
                }
            }
            Event::Prompt(p) => {
                let secret = matches!(p, Prompt::Secret { .. });
                self.prompts.push(PendingPrompt {
                    tab: Some(id),
                    forward: None,
                    prompt: p,
                });
                if secret && self.prompts.len() == 1 {
                    return iced::widget::operation::focus("prompt-secret");
                }
            }
            Event::Trust { info, .. } => self.trust(info),
            Event::Closed(reason) => {
                if let Some(t) = self.tab_mut(id) {
                    t.input = None;
                    t.state = TabState::Closed(reason);
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

    pub fn on_term(&mut self, id: u64, a: Action) {
        if let Action::Resize(c, r) = a {
            self.grid = (c, r);
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
        if let Some(run) = self.tunnels.remove(&forward_id) {
            if !matches!(run.state, TunnelState::Failed(_)) {
                let _ = run.input.send(Input::Close);
                return Task::none();
            }
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
        Task::run(events(session.events), move |e| Message::Tunnel(forward_id, e))
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
                if let Some(r) = self.tunnels.get_mut(&forward_id) {
                    if !matches!(r.state, TunnelState::Failed(_)) {
                        r.state = TunnelState::Running;
                    }
                }
            }
            Event::Prompt(p) => self.prompts.push(PendingPrompt {
                tab: None,
                forward: Some(forward_id),
                prompt: p,
            }),
            Event::Trust { info, .. } => self.trust(info),
            Event::Closed(reason) => match reason {
                Some(r) => {
                    if let Some(run) = self.tunnels.get_mut(&forward_id) {
                        run.state = TunnelState::Failed(r);
                    }
                }
                None => {
                    self.tunnels.remove(&forward_id);
                }
            },
            Event::Output(_) => {}
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
