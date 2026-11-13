//! What every connection (SSH, Telnet, Mosh, local shell) exchanges with the interface.

use std::fmt;
use std::sync::{Arc, LazyLock, Mutex};

use tokio::sync::{mpsc, oneshot};

/// The network runtime, shared by every connection (the interface has its own thread).
pub static RUNTIME: LazyLock<tokio::runtime::Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("nautile-net")
        .enable_all()
        .build()
        .expect("tokio runtime")
});

/// What the server presented, for the known hosts check (same fields as Android's `HostKeyInfo`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostKeyInfo {
    pub host: String,
    pub port: u16,
    /// `ssh-ed25519`, `ssh-rsa`, `ecdsa-sha2-nistp256`…
    pub key_type: String,
    /// Base64 of the SSH wire encoding, as in a known_hosts line.
    pub public_key: String,
    /// `SHA256:…` as printed by OpenSSH.
    pub fingerprint: String,
}

/// A key trusted earlier.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KnownKey {
    pub key_type: String,
    pub public_key: String,
    pub fingerprint: String,
}

/// From a connection to the interface.
#[derive(Debug, Clone)]
pub enum Event {
    /// A step of the connection (« Connexion à … »).
    Status(String),
    /// The terminal is ready.
    Connected,
    /// Bytes for the terminal.
    Output(Vec<u8>),
    /// A question for the user.
    Prompt(Prompt),
    /// A host key the user accepted, to save in the known hosts.
    Trust { info: HostKeyInfo, replace: bool },
    /// The end: `None` when it ended normally, else the reason.
    Closed(Option<String>),
}

#[derive(Debug, Clone)]
pub enum Prompt {
    /// `previous` set: the key CHANGED since it was trusted.
    HostKey {
        label: String,
        info: HostKeyInfo,
        previous: Option<KnownKey>,
        reply: Reply<bool>,
    },
    /// A password or a passphrase; `None` cancels.
    Secret {
        label: String,
        title: String,
        detail: String,
        reply: Reply<Option<String>>,
    },
}

impl Prompt {
    /// Cancels the question (window closed, tab closed…).
    pub fn cancel(&self) {
        match self {
            Prompt::HostKey { reply, .. } => reply.send(false),
            Prompt::Secret { reply, .. } => reply.send(None),
        }
    }
}

/// The answer to a [Prompt]; clonable so that it can travel in the interface's messages.
pub struct Reply<T>(Arc<Mutex<Option<oneshot::Sender<T>>>>);

impl<T> Clone for Reply<T> {
    fn clone(&self) -> Self {
        Reply(self.0.clone())
    }
}

impl<T> fmt::Debug for Reply<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Reply")
    }
}

impl<T> Reply<T> {
    pub(crate) fn new() -> (Reply<T>, oneshot::Receiver<T>) {
        let (tx, rx) = oneshot::channel();
        (Reply(Arc::new(Mutex::new(Some(tx)))), rx)
    }

    /// A reply nobody waits for (tests, previews).
    pub fn detached() -> Reply<T> {
        Reply::new().0
    }

    /// Only the first answer counts.
    pub fn send(&self, value: T) {
        if let Some(tx) = self.0.lock().unwrap_or_else(|e| e.into_inner()).take() {
            let _ = tx.send(value);
        }
    }
}

/// From the interface to a connection.
#[derive(Debug, Clone)]
pub enum Input {
    Data(Vec<u8>),
    Resize { cols: u16, rows: u16 },
    Close,
}

/// The connection's side of the channels.
#[derive(Clone)]
pub struct Events(mpsc::UnboundedSender<Event>);

impl Events {
    pub fn send(&self, event: Event) {
        let _ = self.0.send(event);
    }

    pub fn status(&self, text: impl Into<String>) {
        self.send(Event::Status(text.into()));
    }

    pub fn output(&self, data: &[u8]) {
        if !data.is_empty() {
            self.send(Event::Output(data.to_vec()));
        }
    }

    /// Asks whether to trust [info]; false when the interface is gone.
    pub async fn confirm_host_key(&self, label: &str, info: HostKeyInfo, previous: Option<KnownKey>) -> bool {
        let (reply, rx) = Reply::new();
        self.send(Event::Prompt(Prompt::HostKey {
            label: label.into(),
            info,
            previous,
            reply,
        }));
        rx.await.unwrap_or(false)
    }

    pub async fn secret(&self, label: &str, title: String, detail: String) -> Option<String> {
        let (reply, rx) = Reply::new();
        self.send(Event::Prompt(Prompt::Secret {
            label: label.into(),
            title,
            detail,
            reply,
        }));
        rx.await.ok().flatten()
    }

    pub fn is_closed(&self) -> bool {
        self.0.is_closed()
    }
}

/// The interface's side: send input, receive events.
pub struct Session {
    pub input: mpsc::UnboundedSender<Input>,
    pub events: mpsc::UnboundedReceiver<Event>,
}

/// Both sides of a new connection.
pub fn channels() -> (Session, Events, mpsc::UnboundedReceiver<Input>) {
    let (in_tx, in_rx) = mpsc::unbounded_channel();
    let (ev_tx, ev_rx) = mpsc::unbounded_channel();
    (
        Session {
            input: in_tx,
            events: ev_rx,
        },
        Events(ev_tx),
        in_rx,
    )
}
