//! SSH connections like Android's `SshConnector.kt`: a chain of jump hosts (ProxyJump), the first hop possibly
//! through a proxy and after port knocking, trust on first use of host keys, password / key / agent
//! authentication, an interactive shell, agent forwarding and tunnels.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine;
use nautile_core::model::ForwardKind;
use nautile_core::{tr, trf};
use russh::client::{self, Handle, KeyboardInteractiveAuthResponse, Msg};
use russh::keys::{PrivateKeyWithHashAlg, PublicKey, PublicKeyOrCertificate};
use russh::{Channel, ChannelMsg, MethodKind, Pty};
use sha2::Digest;
use tokio::net::TcpStream;
use tokio::sync::mpsc;

use crate::proxy::{self, Proxy};
use crate::session::{channels, Event, Events, HostKeyInfo, Input, KnownKey, Session, RUNTIME};

/// How to log in on one hop.
#[derive(Debug, Clone)]
pub enum Auth {
    /// `None`: ask when needed (the system agent is tried first).
    Password(Option<String>),
    /// OpenSSH, PEM or PuTTY text; the passphrase is asked when needed.
    Key { private: String, passphrase: Option<String> },
}

/// One hop of a connection: jump hosts first, the destination last.
#[derive(Debug, Clone)]
pub struct Target {
    pub host: String,
    pub port: u16,
    pub username: String,
    pub auth: Auth,
    /// The host's name in the app (`alias`), for the messages.
    pub label: String,
    /// The keys trusted earlier for host:port.
    pub known: Vec<KnownKey>,
}

/// A tunnel rule (`ssh -L`, `-R`, `-D`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Forward {
    pub kind: ForwardKind,
    pub bind_host: String,
    pub bind_port: u16,
    pub dest_host: String,
    pub dest_port: u16,
}

#[derive(Debug, Clone, Default)]
pub struct Params {
    pub chain: Vec<Target>,
    /// Applies to the first hop only.
    pub proxy: Option<Proxy>,
    /// Port knocking sequence for the first hop.
    pub knock: Option<String>,
    /// Lends this computer's SSH agent to the destination.
    pub agent_forwarding: bool,
    /// Typed once the shell is open.
    pub startup_command: Option<String>,
    pub forwards: Vec<Forward>,
}

/// The public key's description, as stored in the known hosts (same encoding as Android).
pub fn describe(host: &str, port: u16, key: &PublicKey) -> HostKeyInfo {
    let blob = key.to_bytes().unwrap_or_default();
    HostKeyInfo {
        host: host.into(),
        port,
        key_type: key.algorithm().as_str().to_owned(),
        public_key: base64::engine::general_purpose::STANDARD.encode(&blob),
        fingerprint: fingerprint(&blob),
    }
}

/// `SHA256:…` without padding, like OpenSSH.
pub fn fingerprint(blob: &[u8]) -> String {
    format!(
        "SHA256:{}",
        base64::engine::general_purpose::STANDARD_NO_PAD.encode(sha2::Sha256::digest(blob))
    )
}

/// Remote forwards: server port → where to connect from here.
type RemoteMap = Arc<Mutex<HashMap<u32, (String, u16)>>>;

pub struct Client {
    label: String,
    host: String,
    port: u16,
    known: Vec<KnownKey>,
    events: Events,
    remote: RemoteMap,
    agent_forwarding: bool,
}

impl client::Handler for Client {
    type Error = russh::Error;

    async fn check_server_key(&mut self, key: &PublicKeyOrCertificate) -> Result<bool, Self::Error> {
        let key = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.clone(),
            PublicKeyOrCertificate::Certificate(c) => PublicKey::from(c.public_key().clone()),
        };
        let info = describe(&self.host, self.port, &key);
        if self
            .known
            .iter()
            .any(|k| k.key_type == info.key_type && k.public_key == info.public_key)
        {
            return Ok(true);
        }
        let previous = self.known.iter().find(|k| k.key_type == info.key_type).cloned();
        if !self.events.confirm_host_key(&self.label, info.clone(), previous.clone()).await {
            return Ok(false);
        }
        self.events.send(Event::Trust {
            info,
            replace: previous.is_some(),
        });
        Ok(true)
    }

    async fn server_channel_open_forwarded_tcpip(
        &mut self,
        channel: Channel<Msg>,
        connected_address: &str,
        connected_port: u32,
        _originator_address: &str,
        _originator_port: u32,
        reply: client::ChannelOpenHandle,
        _session: &mut client::Session,
    ) -> Result<(), Self::Error> {
        let dest = self.remote.lock().unwrap_or_else(|e| e.into_inner()).get(&connected_port).cloned();
        let Some((host, port)) = dest else {
            let _ = connected_address;
            return Ok(());
        };
        reply.accept().await;
        tokio::spawn(async move {
            if let Ok(mut local) = TcpStream::connect((host.as_str(), port)).await {
                let mut remote = channel.into_stream();
                let _ = tokio::io::copy_bidirectional(&mut local, &mut remote).await;
            }
        });
        Ok(())
    }

    async fn server_channel_open_agent_forward(
        &mut self,
        channel: Channel<Msg>,
        reply: client::ChannelOpenHandle,
        _session: &mut client::Session,
    ) -> Result<(), Self::Error> {
        if !self.agent_forwarding {
            return Ok(());
        }
        let Some(agent) = agent::stream().await else {
            return Ok(());
        };
        reply.accept().await;
        tokio::spawn(async move {
            let mut agent = agent;
            let mut chan = channel.into_stream();
            let _ = tokio::io::copy_bidirectional(&mut agent, &mut chan).await;
        });
        Ok(())
    }
}

/// The system SSH agent (OpenSSH agent, Pageant on Windows).
mod agent {
    use russh::keys::agent::client::{AgentClient, AgentStream};

    pub type Dyn = AgentClient<Box<dyn AgentStream + Send + Unpin + 'static>>;

    pub async fn client() -> Option<Dyn> {
        #[cfg(unix)]
        {
            AgentClient::connect_env().await.ok().map(|c| c.dynamic())
        }
        #[cfg(windows)]
        {
            if let Ok(c) = AgentClient::connect_named_pipe(r"\\.\pipe\openssh-ssh-agent").await {
                return Some(c.dynamic());
            }
            AgentClient::connect_pageant().await.ok().map(|c| c.dynamic())
        }
        #[cfg(not(any(unix, windows)))]
        {
            None
        }
    }

    /// A raw connection to the agent, for forwarding.
    pub async fn stream() -> Option<Box<dyn AgentStream + Send + Unpin + 'static>> {
        client().await.map(|c| c.into_inner())
    }
}

/// An authenticated connection; dropping it also closes the jump hosts.
pub struct Connection {
    pub handle: Arc<Handle<Client>>,
    hops: Vec<Arc<Handle<Client>>>,
    remote: RemoteMap,
}

impl Connection {
    pub async fn close(&self) {
        let _ = self.handle.disconnect(russh::Disconnect::ByApplication, "", "en").await;
        for h in self.hops.iter().rev() {
            let _ = h.disconnect(russh::Disconnect::ByApplication, "", "en").await;
        }
    }
}

fn config() -> Arc<client::Config> {
    Arc::new(client::Config {
        keepalive_interval: Some(Duration::from_secs(30)),
        keepalive_max: 3,
        nodelay: true,
        ..Default::default()
    })
}

/// A readable reason for a failed connection.
fn reason(e: russh::Error) -> String {
    match e {
        russh::Error::UnknownKey => tr("Clé du serveur refusée"),
        russh::Error::ConnectionTimeout | russh::Error::KeepaliveTimeout | russh::Error::InactivityTimeout => {
            tr("Délai de connexion dépassé")
        }
        russh::Error::Disconnect | russh::Error::HUP => tr("Connexion fermée par le serveur"),
        russh::Error::NoAuthMethod => tr("Aucune méthode d'authentification acceptée"),
        russh::Error::IO(e) => e.to_string(),
        e => e.to_string(),
    }
}

/// Connects through the whole chain and logs in on each hop.
pub async fn connect(p: &Params, events: &Events) -> Result<Connection, String> {
    let Some(first) = p.chain.first() else {
        return Err(tr("Aucun hôte"));
    };
    if let Some(seq) = p.knock.as_deref().filter(|s| !s.trim().is_empty()) {
        events.status(trf("Port knocking sur %1$s (%2$s)…", &[&first.host, &seq]));
        let knocks = proxy::parse_knocks(seq)?;
        proxy::knock(&first.host, &knocks).await.map_err(|e| e.to_string())?;
    }
    let remote: RemoteMap = Arc::default();
    let mut hops: Vec<Arc<Handle<Client>>> = Vec::new();
    let last = p.chain.len() - 1;
    for (i, t) in p.chain.iter().enumerate() {
        events.status(if i == last {
            trf("Connexion à %1$s…", &[&t.label])
        } else {
            trf("Rebond via %1$s…", &[&t.label])
        });
        let handler = Client {
            label: t.label.clone(),
            host: t.host.clone(),
            port: t.port,
            known: t.known.clone(),
            events: events.clone(),
            remote: remote.clone(),
            agent_forwarding: i == last && p.agent_forwarding,
        };
        let mut handle = match hops.last() {
            None => {
                let stream = proxy::connect(&t.host, t.port, p.proxy.as_ref()).await.map_err(|e| e.to_string())?;
                client::connect_stream(config(), stream, handler).await.map_err(reason)?
            }
            Some(prev) => {
                let ch = prev
                    .channel_open_direct_tcpip(t.host.clone(), t.port as u32, "127.0.0.1", 0)
                    .await
                    .map_err(|e| trf("Rebond vers %1$s impossible : %2$s", &[&t.label, &reason(e)]))?;
                client::connect_stream(config(), ch.into_stream(), handler).await.map_err(reason)?
            }
        };
        authenticate(&mut handle, t, events).await?;
        hops.push(Arc::new(handle));
    }
    let handle = hops.pop().expect("one hop");
    Ok(Connection { handle, hops, remote })
}

async fn authenticate(h: &mut Handle<Client>, t: &Target, events: &Events) -> Result<(), String> {
    let user = t.username.clone();
    match &t.auth {
        Auth::Key { private, passphrase } => {
            let key = load_key(private, passphrase.clone(), t, events).await?;
            let hash = h.best_supported_rsa_hash().await.ok().flatten().flatten();
            let r = h
                .authenticate_publickey(user, PrivateKeyWithHashAlg::new(Arc::new(key), hash))
                .await
                .map_err(reason)?;
            if r.success() {
                Ok(())
            } else {
                Err(tr("Clé refusée par le serveur"))
            }
        }
        Auth::Password(saved) => {
            if saved.is_none() && with_agent(h, &user).await {
                return Ok(());
            }
            let mut password = saved.clone();
            for _ in 0..3 {
                let pw = match password.take() {
                    Some(p) => p,
                    None => events
                        .secret(&t.label, tr("Mot de passe"), format!("{}@{}", t.username, t.host))
                        .await
                        .ok_or_else(|| tr("Connexion annulée"))?,
                };
                let r = h.authenticate_password(user.clone(), pw.clone()).await.map_err(reason)?;
                if r.success() {
                    return Ok(());
                }
                if let russh::client::AuthResult::Failure { remaining_methods, .. } = &r {
                    if remaining_methods.contains(&MethodKind::KeyboardInteractive) && keyboard_interactive(h, t, &pw, events).await? {
                        return Ok(());
                    }
                }
                events.status(tr("Mot de passe refusé"));
            }
            Err(tr("Mot de passe refusé"))
        }
    }
}

/// Answers the server's questions: the password where it is asked, the user for the rest (codes…).
async fn keyboard_interactive(h: &mut Handle<Client>, t: &Target, password: &str, events: &Events) -> Result<bool, String> {
    let mut r = h
        .authenticate_keyboard_interactive_start(t.username.clone(), None)
        .await
        .map_err(reason)?;
    let mut used = false;
    for _ in 0..6 {
        match r {
            KeyboardInteractiveAuthResponse::Success => return Ok(true),
            KeyboardInteractiveAuthResponse::Failure { .. } => return Ok(false),
            KeyboardInteractiveAuthResponse::InfoRequest { prompts, .. } => {
                let mut answers = Vec::new();
                for q in &prompts {
                    let lower = q.prompt.to_lowercase();
                    if !used && !q.echo && (lower.contains("password") || lower.contains("mot de passe")) {
                        used = true;
                        answers.push(password.to_owned());
                    } else {
                        let a = events
                            .secret(&t.label, q.prompt.trim().to_owned(), format!("{}@{}", t.username, t.host))
                            .await
                            .ok_or_else(|| tr("Connexion annulée"))?;
                        answers.push(a);
                    }
                }
                r = h.authenticate_keyboard_interactive_respond(answers).await.map_err(reason)?;
            }
        }
    }
    Ok(false)
}

/// Tries each key of the system agent.
async fn with_agent(h: &mut Handle<Client>, user: &str) -> bool {
    let Some(mut agent) = agent::client().await else {
        return false;
    };
    let Ok(ids) = agent.request_identities().await else {
        return false;
    };
    for id in ids {
        let russh::keys::agent::AgentIdentity::PublicKey { key, .. } = id else {
            continue;
        };
        let hash = h.best_supported_rsa_hash().await.ok().flatten().flatten();
        if let Ok(r) = h.authenticate_publickey_with(user.to_owned(), key, hash, &mut agent).await {
            if r.success() {
                return true;
            }
        }
    }
    false
}

async fn load_key(text: &str, passphrase: Option<String>, t: &Target, events: &Events) -> Result<russh::keys::PrivateKey, String> {
    let mut pass = passphrase;
    for attempt in 0..4 {
        match crate::keys::decode(text, pass.as_deref()) {
            Ok(k) => return Ok(k),
            Err(crate::keys::KeyError::PassphraseRequired) | Err(crate::keys::KeyError::WrongPassphrase) if attempt < 3 => {
                pass = Some(
                    events
                        .secret(&t.label, tr("Phrase de passe de la clé"), t.label.clone())
                        .await
                        .ok_or_else(|| tr("Connexion annulée"))?,
                );
            }
            Err(e) => return Err(e.to_string()),
        }
    }
    Err(tr("Phrase de passe incorrecte"))
}

/// An interactive shell: connects, opens a PTY of `cols`×`rows`, then relays until the end.
pub fn spawn(p: Params, cols: u16, rows: u16) -> Session {
    let (session, events, input) = channels();
    RUNTIME.spawn(async move {
        let reason = match connect(&p, &events).await {
            Err(e) => Some(e),
            Ok(conn) => {
                let r = shell(&conn, &p, cols, rows, &events, input).await;
                conn.close().await;
                r
            }
        };
        events.send(Event::Closed(reason));
    });
    session
}

async fn shell(
    conn: &Connection,
    p: &Params,
    cols: u16,
    rows: u16,
    events: &Events,
    mut input: mpsc::UnboundedReceiver<Input>,
) -> Option<String> {
    let ch = match conn.handle.channel_open_session().await {
        Ok(c) => c,
        Err(e) => return Some(reason(e)),
    };
    if p.agent_forwarding {
        let _ = ch.agent_forward(false).await;
    }
    if let Err(e) = ch
        .request_pty(false, "xterm-256color", cols as u32, rows as u32, 0, 0, &[(Pty::ECHO, 1)])
        .await
    {
        return Some(reason(e));
    }
    if let Err(e) = ch.request_shell(false).await {
        return Some(reason(e));
    }
    events.send(Event::Connected);
    // Tunnels declared with the session run as long as it does.
    let _tunnels = start_forwards(conn, &p.forwards, events).await;
    if let Some(cmd) = p.startup_command.as_deref().filter(|c| !c.trim().is_empty()) {
        let line = cmd.trim_end_matches('\n').replace("\r\n", "\n").replace('\n', "\r") + "\r";
        let _ = ch.data_bytes(line.into_bytes()).await;
    }
    let (mut read, write) = ch.split();
    let mut exit = None;
    loop {
        tokio::select! {
            msg = read.wait() => match msg {
                Some(ChannelMsg::Data { data }) => events.output(&data),
                Some(ChannelMsg::ExtendedData { data, .. }) => events.output(&data),
                Some(ChannelMsg::ExitStatus { exit_status }) => exit = Some(exit_status),
                Some(ChannelMsg::Close) | None => break,
                _ => {}
            },
            i = input.recv() => match i {
                Some(Input::Data(d)) => {
                    if write.data_bytes(d).await.is_err() {
                        break;
                    }
                }
                Some(Input::Resize { cols, rows }) => {
                    let _ = write.window_change(cols as u32, rows as u32, 0, 0).await;
                }
                Some(Input::Close) | None => {
                    let _ = write.close().await;
                    return None;
                }
            },
        }
    }
    match exit {
        Some(0) | None => None,
        Some(code) => Some(trf("Session terminée (code %1$s)", &[&code])),
    }
}

/// Running tunnels; dropping this stops the local listeners.
pub struct Tunnels(Vec<tokio::task::JoinHandle<()>>);

impl Drop for Tunnels {
    fn drop(&mut self) {
        for t in &self.0 {
            t.abort();
        }
    }
}

/// Starts [forwards] on [conn]; a rule that cannot start is reported and skipped.
pub async fn start_forwards(conn: &Connection, forwards: &[Forward], events: &Events) -> Tunnels {
    let mut tasks = Vec::new();
    for f in forwards {
        match start_forward(conn, f).await {
            Ok(Some(t)) => tasks.push(t),
            Ok(None) => {}
            Err(e) => events.status(trf("Tunnel %1$s impossible : %2$s", &[&f.bind_port, &e])),
        }
    }
    Tunnels(tasks)
}

async fn start_forward(conn: &Connection, f: &Forward) -> Result<Option<tokio::task::JoinHandle<()>>, String> {
    let bind = if f.bind_host.is_empty() {
        "127.0.0.1"
    } else {
        f.bind_host.as_str()
    };
    match f.kind {
        ForwardKind::Remote => {
            let port = conn
                .handle
                .tcpip_forward(bind.to_owned(), f.bind_port as u32)
                .await
                .map_err(reason)?;
            let port = if port == 0 { f.bind_port as u32 } else { port };
            conn.remote
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .insert(port, (f.dest_host.clone(), f.dest_port));
            Ok(None)
        }
        ForwardKind::Local | ForwardKind::Dynamic => {
            let listener = tokio::net::TcpListener::bind((bind, f.bind_port))
                .await
                .map_err(|e| e.to_string())?;
            let handle = conn.handle.clone();
            let f = f.clone();
            Ok(Some(tokio::spawn(async move {
                while let Ok((mut sock, peer)) = listener.accept().await {
                    let handle = handle.clone();
                    let f = f.clone();
                    tokio::spawn(async move {
                        let dest = if f.kind == ForwardKind::Dynamic {
                            match crate::socks::accept(&mut sock).await {
                                Ok(d) => d,
                                Err(_) => return,
                            }
                        } else {
                            (f.dest_host.clone(), f.dest_port)
                        };
                        let opened = handle
                            .channel_open_direct_tcpip(dest.0, dest.1 as u32, peer.ip().to_string(), peer.port() as u32)
                            .await;
                        if f.kind == ForwardKind::Dynamic {
                            let _ = crate::socks::reply(&mut sock, opened.is_ok()).await;
                        }
                        if let Ok(ch) = opened {
                            let mut remote = ch.into_stream();
                            let _ = tokio::io::copy_bidirectional(&mut sock, &mut remote).await;
                        }
                    });
                }
            })))
        }
    }
}

/// Tunnels alone, without a shell (the Tunnels page): connected until [Input::Close].
pub fn spawn_tunnels(p: Params) -> Session {
    let (session, events, mut input) = channels();
    RUNTIME.spawn(async move {
        let reason = match connect(&p, &events).await {
            Err(e) => Some(e),
            Ok(conn) => {
                let tunnels = start_forwards(&conn, &p.forwards, &events).await;
                events.send(Event::Connected);
                let r = loop {
                    tokio::select! {
                        i = input.recv() => if matches!(i, Some(Input::Close) | None) { break None },
                        _ = tokio::time::sleep(Duration::from_secs(5)) => {
                            if conn.handle.is_closed() {
                                break Some(tr("Connexion fermée par le serveur"));
                            }
                        }
                    }
                };
                drop(tunnels);
                conn.close().await;
                r
            }
        };
        events.send(Event::Closed(reason));
    });
    session
}
