//! Synchronisation straight between devices on the same network (Android's `LanSync.kt`): each announces itself
//! (mDNS « _nautile._tcp »), pairs once with a 6-digit code, then exchanges its data with its paired devices
//! found nearby (protocol v2 in `nautile_core::lan`). Each paired device has its own secret, from its pairing;
//! forgetting a device drops it, and a device without one is refused. The data itself stays on the interface's
//! side: it is asked for ([LanEvent::Snapshot]) and handed back to merge ([LanEvent::Merge]).

use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::net::{IpAddr, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use nautile_core::lan::{self, LanError};
use nautile_core::{tr, trf};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};

use crate::session::Reply;

/// Wrong codes allowed before the shown one is dropped.
pub const MAX_TRIES: u32 = 3;
/// Connections handled at once; the next ones are answered « busy ».
pub const MAX_CONNECTIONS: usize = 4;
/// « Busy » answers under way at once; past them, connections are closed straight away.
const MAX_REFUSALS: usize = 4;
/// The whole handshake, hellos included: a peer sending slowly cannot hold a connection longer.
const HANDSHAKE_DEADLINE: Duration = if cfg!(test) {
    Duration::from_secs(2)
} else {
    Duration::from_secs(15)
};
/// The whole data exchange (a database each way).
const TRANSFER_DEADLINE: Duration = Duration::from_secs(300);
/// The longest wait for one read or write.
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);
/// How long a refusal waits for the other side to hang up.
const REFUSE_LINGER: Duration = Duration::from_secs(2);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);

/// A device announcing itself on the network.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Found {
    pub id: String,
    pub name: String,
    pub addr: SocketAddr,
    /// Its protocol version (the mDNS `v`, 1 when missing).
    pub version: u8,
}

#[derive(Debug, Clone)]
pub enum LanEvent {
    /// The Nautile devices seen on the network now (this one excepted).
    Found(Vec<Found>),
    /// Paired with a device; `secret`: the one to keep for it from now on.
    Paired {
        id: String,
        name: String,
        /// Its system (« android », « windows », « ubuntu »…), unknown for older versions.
        platform: Option<String>,
        secret: Vec<u8>,
    },
    /// This device's data (`BackupData` JSON) is needed for an exchange.
    Snapshot(Reply<String>),
    /// The data of a paired device, to merge.
    Merge {
        id: String,
        name: String,
        platform: Option<String>,
        json: String,
        /// Set on the responder: this device's data (`BackupData` JSON) once merged, sent back to the initiator,
        /// whose data then already holds this one's (sync::merge_ties: only this side moves a tie past). None on the
        /// initiator, which merges what came back: sync::merge_ties with `move_ties` false.
        merged: Option<Reply<String>>,
    },
    Status(String),
    /// The code shown was dropped after too many wrong tries: a new one is needed.
    CodeExpired,
}

struct Shared {
    device_id: String,
    device_name: String,
    code: Option<String>,
    attempts: u32,
    /// The secret of each paired device.
    secrets: HashMap<String, Vec<u8>>,
    found: HashMap<String, Found>,
    /// mDNS full name → device id, to drop exactly the one that left.
    names: HashMap<String, String>,
    /// None once stopped: nothing reaches the interface any more.
    events: Option<UnboundedSender<LanEvent>>,
}

impl Shared {
    fn emit(&self, e: LanEvent) -> bool {
        self.events.as_ref().is_some_and(|t| t.send(e).is_ok())
    }
}

fn lock(s: &Mutex<Shared>) -> MutexGuard<'_, Shared> {
    s.lock().unwrap_or_else(|e| e.into_inner())
}

/// The running Wi-Fi synchronisation (server, announcement, discovery).
#[derive(Clone)]
pub struct Lan {
    shared: Arc<Mutex<Shared>>,
    daemon: Option<mdns_sd::ServiceDaemon>,
    port: u16,
    stopped: Arc<AtomicBool>,
}

/// Who this device is, sent to the other one (its `platform`: shown as its icon there).
fn hello(id: &str, name: &str) -> String {
    serde_json::json!({ "id": id, "name": name, "platform": nautile_core::os::local() }).to_string()
}

fn platform(v: &serde_json::Value) -> Option<String> {
    v.get("platform")
        .and_then(|x| x.as_str())
        .filter(|p| !p.is_empty())
        .map(str::to_owned)
}

fn json_field(v: &serde_json::Value, k: &str) -> io::Result<String> {
    v.get(k)
        .and_then(|x| x.as_str())
        .map(str::to_owned)
        .ok_or_else(|| io::Error::other(format!("missing {k}")))
}

fn parse(text: &str) -> io::Result<serde_json::Value> {
    serde_json::from_str(text).map_err(io::Error::other)
}

/// The other side's hello, which must name the device the handshake was made with.
fn their_hello(text: &str, id: &str) -> io::Result<serde_json::Value> {
    let v = parse(text)?;
    if json_field(&v, "id")? != id {
        return Err(io::Error::other("hello from another device"));
    }
    Ok(v)
}

fn stopped_error() -> io::Error {
    io::Error::other("Wi-Fi synchronisation stopped")
}

/// A connection with an overall deadline: each read or write only waits for what is left of it.
struct Timed {
    stream: TcpStream,
    deadline: Instant,
}

impl Timed {
    fn new(stream: TcpStream, total: Duration) -> Timed {
        Timed {
            stream,
            deadline: Instant::now() + total,
        }
    }

    /// A new deadline, from now (the handshake done, the transfer has its own).
    fn renew(&mut self, total: Duration) {
        self.deadline = Instant::now() + total;
    }

    fn left(&self) -> io::Result<Duration> {
        let left = self.deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "LAN connection deadline"));
        }
        Ok(left.min(IDLE_TIMEOUT))
    }
}

/// macOS refuses a new timeout (EINVAL) on a socket the peer has already closed: the read or write that follows
/// reports that end itself (and the previous, shorter-or-equal timeout still bounds it), so it is not an error here.
fn settle(r: io::Result<()>) -> io::Result<()> {
    match r {
        Err(e) if e.kind() == io::ErrorKind::InvalidInput => Ok(()),
        other => other,
    }
}

impl Read for Timed {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let t = self.left()?;
        settle(self.stream.set_read_timeout(Some(t)))?;
        self.stream.read(buf)
    }
}

impl Write for Timed {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let t = self.left()?;
        settle(self.stream.set_write_timeout(Some(t)))?;
        self.stream.write(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.stream.flush()
    }
}

fn connect(addr: &SocketAddr) -> io::Result<Timed> {
    Ok(Timed::new(TcpStream::connect_timeout(addr, CONNECT_TIMEOUT)?, HANDSHAKE_DEADLINE))
}

/// Answers an opening with a refusal, then waits a little for the other side to hang up: closing with its
/// bytes unread would reset the connection before it reads the answer.
fn refuse(stream: &mut Timed, status: Option<u8>) {
    if let Some(status) = status {
        let _ = lan::refuse(stream, status);
    }
    let _ = stream.stream.shutdown(std::net::Shutdown::Write);
    stream.renew(REFUSE_LINGER);
    let mut sink = [0u8; 512];
    while matches!(stream.read(&mut sink), Ok(n) if n > 0) {}
}

/// Lets a connection's slot go when its thread ends.
struct Slot(Arc<AtomicUsize>);

impl Drop for Slot {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

/// What to say about an error, for the interface: the protocol's own message, or `fallback`.
pub fn describe(e: &io::Error, fallback: String) -> String {
    match LanError::of(e) {
        Some(l) => l.to_string(),
        None => fallback,
    }
}

impl Lan {
    /// Listens, announces this device and looks for the others. `secrets`: those of the paired devices.
    /// `announce: false` for tests (no mDNS).
    pub fn start(
        device_id: String,
        device_name: String,
        secrets: HashMap<String, Vec<u8>>,
        announce: bool,
    ) -> io::Result<(Lan, UnboundedReceiver<LanEvent>)> {
        let (tx, rx) = mpsc::unbounded_channel();
        let shared = Arc::new(Mutex::new(Shared {
            device_id: device_id.clone(),
            device_name: device_name.clone(),
            code: None,
            attempts: 0,
            secrets,
            found: HashMap::new(),
            names: HashMap::new(),
            events: Some(tx),
        }));
        let listener = TcpListener::bind("0.0.0.0:0")?;
        let port = listener.local_addr()?.port();
        let stopped = Arc::new(AtomicBool::new(false));
        let (s, stop) = (shared.clone(), stopped.clone());
        std::thread::Builder::new().name("lan-sync-server".into()).spawn(move || {
            let active = Arc::new(AtomicUsize::new(0));
            let refusing = Arc::new(AtomicUsize::new(0));
            for stream in listener.incoming() {
                if stop.load(Ordering::SeqCst) {
                    break;
                }
                let Ok(stream) = stream else { continue };
                // Only this loop adds connections: checking then adding cannot race. Past the handled ones,
                // a few are told « busy »; past those too, closed without an answer.
                let busy = active.load(Ordering::SeqCst) >= MAX_CONNECTIONS;
                let count = if !busy {
                    &active
                } else if refusing.load(Ordering::SeqCst) < MAX_REFUSALS {
                    &refusing
                } else {
                    continue;
                };
                count.fetch_add(1, Ordering::SeqCst);
                let slot = Slot(count.clone());
                let (s, stop) = (s.clone(), stop.clone());
                let _ = std::thread::Builder::new().name("lan-sync-peer".into()).spawn(move || {
                    let _slot = slot;
                    let mut stream = Timed::new(stream, HANDSHAKE_DEADLINE);
                    if busy {
                        refuse(&mut stream, Some(lan::status::BUSY));
                    } else {
                        let _ = handle(&s, &stop, stream);
                    }
                });
            }
            // The listener is dropped here: the port closes.
        })?;
        let mut me = Lan {
            shared,
            daemon: None,
            port,
            stopped,
        };
        if announce {
            me.daemon = me.announce(&device_id, &device_name).ok();
        }
        Ok((me, rx))
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    fn announce(&self, id: &str, name: &str) -> Result<mdns_sd::ServiceDaemon, mdns_sd::Error> {
        let daemon = mdns_sd::ServiceDaemon::new()?;
        let host = format!("nautile-{}.local.", id.chars().take(12).collect::<String>());
        let version = lan::VERSION.to_string();
        let props = [("id", id), ("v", version.as_str())];
        let info =
            mdns_sd::ServiceInfo::new(lan::SERVICE_TYPE, &format!("Nautile {name}"), &host, "", self.port, &props[..])?.enable_addr_auto();
        daemon.register(info)?;
        let browse = daemon.browse(lan::SERVICE_TYPE)?;
        let (shared, stopped) = (self.shared.clone(), self.stopped.clone());
        std::thread::Builder::new()
            .name("lan-sync-browse".into())
            .spawn(move || {
                while let Ok(e) = browse.recv() {
                    if stopped.load(Ordering::SeqCst) {
                        break;
                    }
                    let mut s = lock(&shared);
                    match e {
                        mdns_sd::ServiceEvent::ServiceResolved(info) => {
                            let Some(id) = info.get_property_val_str("id").map(str::to_owned) else {
                                continue;
                            };
                            if id == s.device_id || id.is_empty() || id.len() > lan::MAX_ID {
                                continue;
                            }
                            let Some(ip) = info.get_addresses_v4().into_iter().next() else {
                                continue;
                            };
                            let version = info.get_property_val_str("v").and_then(|v| v.parse().ok()).unwrap_or(1);
                            let fullname = info.get_fullname().to_owned();
                            let name = fullname
                                .split("._nautile")
                                .next()
                                .unwrap_or_default()
                                .trim_start_matches("Nautile ")
                                .replace("\\032", " ")
                                .replace("\\ ", " ");
                            s.names.insert(fullname, id.clone());
                            s.found.insert(
                                id.clone(),
                                Found {
                                    id,
                                    name,
                                    addr: SocketAddr::new(IpAddr::V4(ip), info.get_port()),
                                    version,
                                },
                            );
                        }
                        mdns_sd::ServiceEvent::ServiceRemoved(_, fullname) => {
                            // Exactly the one that left: « Bureau » leaving keeps « Bureau 2 ».
                            if let Some(id) = s.names.remove(&fullname)
                                && !s.names.values().any(|v| *v == id)
                            {
                                s.found.remove(&id);
                            }
                        }
                        _ => continue,
                    }
                    let list: Vec<Found> = s.found.values().cloned().collect();
                    s.emit(LanEvent::Found(list));
                }
            })
            .map_err(|e| mdns_sd::Error::Msg(e.to_string()))?;
        Ok(daemon)
    }

    /// Stops everything: no more connection accepted, nothing more merged, the announcement withdrawn.
    pub fn stop(&self) {
        self.stopped.store(true, Ordering::SeqCst);
        {
            let mut s = lock(&self.shared);
            s.code = None;
            s.secrets.clear();
            s.found.clear();
            s.events = None;
        }
        if let Some(d) = &self.daemon {
            let _ = d.shutdown();
        }
        // Wakes the server up so that it sees the flag and closes its port.
        let _ = TcpStream::connect_timeout(&SocketAddr::from(([127, 0, 0, 1], self.port)), Duration::from_secs(1));
    }

    pub fn is_stopped(&self) -> bool {
        self.stopped.load(Ordering::SeqCst)
    }

    /// Forgets a paired device: its secret goes, it is refused from now on.
    pub fn forget(&self, id: &str) {
        lock(&self.shared).secrets.remove(id);
    }

    /// Shows a code on this device: another one types it to pair.
    pub fn show_code(&self) -> String {
        let code = lan::new_code();
        let mut s = lock(&self.shared);
        s.code = Some(code.clone());
        s.attempts = 0;
        code
    }

    pub fn cancel_code(&self) {
        lock(&self.shared).code = None;
    }

    pub fn found(&self) -> Vec<Found> {
        lock(&self.shared).found.values().cloned().collect()
    }

    fn me(&self) -> io::Result<(String, String)> {
        if self.is_stopped() {
            return Err(stopped_error());
        }
        let s = lock(&self.shared);
        Ok((s.device_id.clone(), s.device_name.clone()))
    }

    /// Pairs with [peer], which shows [code] (blocking).
    pub fn pair(&self, peer: &Found, code: &str) -> io::Result<()> {
        if peer.version < lan::VERSION {
            return Err(LanError::PeerTooOld.io());
        }
        let (id, name) = self.me()?;
        let lan::Paired { mut channel, secret } = lan::pair_connect(connect(&peer.addr)?, &id, &peer.id, code)?;
        channel.send_text(&hello(&id, &name))?;
        // Kept only once the other side answered: it hangs up instead when its code was cancelled meanwhile.
        let them = their_hello(&channel.receive_short_text()?, &peer.id)?;
        let their_name = json_field(&them, "name")?;
        let mut s = lock(&self.shared);
        if self.is_stopped() {
            return Err(stopped_error());
        }
        s.secrets.insert(peer.id.clone(), secret.to_vec());
        s.emit(LanEvent::Paired {
            id: peer.id.clone(),
            name: their_name.clone(),
            platform: platform(&them),
            secret: secret.to_vec(),
        });
        s.emit(LanEvent::Status(trf("%1$s associé", &[&their_name])));
        Ok(())
    }

    /// One round with the paired devices found on the network (blocking); the number reached.
    pub fn sync_all(&self, paired: &[String]) -> usize {
        let nearby: Vec<Found> = {
            let s = lock(&self.shared);
            s.found
                .values()
                .filter(|f| paired.contains(&f.id) && s.secrets.contains_key(&f.id))
                .cloned()
                .collect()
        };
        if self.is_stopped() {
            return 0;
        }
        if nearby.is_empty() {
            lock(&self.shared).emit(LanEvent::Status(tr("Aucun appareil associé à portée")));
            return 0;
        }
        let mut reached = 0;
        let mut refused = None;
        for p in &nearby {
            match self.sync_with(p) {
                Ok(()) => reached += 1,
                Err(e) => {
                    if let Some(l) = LanError::of(&e) {
                        refused = Some(trf("%1$s : %2$s", &[&p.name, &l]));
                    }
                }
            }
        }
        lock(&self.shared).emit(LanEvent::Status(match (reached, refused) {
            (0, Some(why)) => why,
            (0, None) => tr("Appareils associés injoignables"),
            (1, _) => tr("Synchronisé avec 1 appareil"),
            (n, _) => trf("Synchronisé avec %1$s appareils", &[&n]),
        }));
        reached
    }

    /// One exchange with [peer] (blocking), with the secret of its pairing.
    pub fn sync_with(&self, peer: &Found) -> io::Result<()> {
        if peer.version < lan::VERSION {
            return Err(LanError::PeerTooOld.io());
        }
        let (id, _) = self.me()?;
        let secret = lock(&self.shared)
            .secrets
            .get(&peer.id)
            .cloned()
            .ok_or_else(|| io::Error::other("not paired"))?;
        let mut ch = lan::sync_connect(connect(&peer.addr)?, &id, &peer.id, &secret)?;
        ch.get_mut().renew(TRANSFER_DEADLINE);
        exchange(&self.shared, &self.stopped, &mut ch, &peer.id, None)
    }
}

/// Both sides send their data and merge the other's: the initiator (`them` None) sends first; the responder merges
/// it first, then answers with its data merged, so both hold the same in one exchange, even where two versions decide
/// a tie differently. The messages and their order are those of every protocol v2 version (Android's LanExchange).
/// `peer`: the device authenticated by the handshake.
fn exchange(
    shared: &Arc<Mutex<Shared>>,
    stopped: &AtomicBool,
    ch: &mut lan::Channel<Timed>,
    peer: &str,
    them: Option<serde_json::Value>,
) -> io::Result<()> {
    let (id, name) = {
        let s = lock(shared);
        (s.device_id.clone(), s.device_name.clone())
    };
    let hello = hello(&id, &name);
    let mine = || -> io::Result<String> {
        let (reply, rx) = Reply::new();
        if !lock(shared).emit(LanEvent::Snapshot(reply)) {
            return Err(stopped_error());
        }
        rx.blocking_recv().map_err(io::Error::other)
    };
    // Stopped, or the device forgotten, in the meantime: nothing is merged.
    let merge = |them: &serde_json::Value, json: String, merged: Option<Reply<String>>| -> io::Result<()> {
        let s = lock(shared);
        if stopped.load(Ordering::SeqCst) || !s.secrets.contains_key(peer) {
            return Err(stopped_error());
        }
        let sent = s.emit(LanEvent::Merge {
            id: peer.to_owned(),
            name: json_field(them, "name")?,
            platform: platform(them),
            json,
            merged,
        });
        if sent { Ok(()) } else { Err(stopped_error()) }
    };
    match them {
        None => {
            let data = mine()?;
            ch.send_text(&hello)?;
            ch.send_text(&data)?;
            let them = their_hello(&ch.receive_short_text()?, peer)?;
            let theirs = ch.receive_text()?;
            merge(&them, theirs, None)
        }
        Some(them) => {
            let theirs = ch.receive_text()?;
            let (reply, rx) = Reply::new();
            merge(&them, theirs, Some(reply))?;
            // This device's data once merged (the lock is not held meanwhile).
            let data = rx.blocking_recv().map_err(io::Error::other)?;
            ch.send_text(&hello)?;
            ch.send_text(&data)?;
            Ok(())
        }
    }
}

/// An incoming connection: pairing (with the code shown) or synchronisation (with the paired device's secret).
fn handle(shared: &Arc<Mutex<Shared>>, stopped: &AtomicBool, mut stream: Timed) -> io::Result<()> {
    let hello_in = match lan::read_hello(&mut stream) {
        Ok(h) => h,
        Err(e) => {
            let why = match LanError::of(&e) {
                Some(LanError::PeerTooOld) => Some(tr(
                    "Un appareil avec une ancienne version de Nautile a essayé de se connecter : mettez-le à jour",
                )),
                Some(LanError::PeerTooNew) => Some(tr(
                    "Un appareil avec une version plus récente de Nautile a essayé de se connecter : mettez à jour celui-ci",
                )),
                _ => None,
            };
            if let Some(why) = why {
                // Already answered by read_hello.
                refuse(&mut stream, None);
                lock(shared).emit(LanEvent::Status(why));
            }
            return Err(e);
        }
    };
    if stopped.load(Ordering::SeqCst) {
        return Err(stopped_error());
    }
    let (me, my_name) = {
        let s = lock(shared);
        (s.device_id.clone(), s.device_name.clone())
    };
    match hello_in.mode {
        lan::MODE_PAIR => {
            // The try is counted before the handshake, under the lock: parallel connections get no more than
            // MAX_TRIES between them.
            let code = {
                let mut s = lock(shared);
                match s.code.clone() {
                    Some(c) if s.attempts < MAX_TRIES => {
                        s.attempts += 1;
                        Some(c)
                    }
                    _ => None,
                }
            };
            let Some(code) = code else {
                refuse(&mut stream, Some(lan::status::NO_CODE));
                return Err(LanError::NoCode.io());
            };
            let lan::Paired { mut channel, secret } = match lan::pair_accept(stream, &hello_in, &me, &code) {
                Ok(p) => p,
                Err(e) => {
                    let mut s = lock(shared);
                    if s.attempts >= MAX_TRIES && s.code.as_deref() == Some(code.as_str()) {
                        s.code = None;
                        s.emit(LanEvent::CodeExpired);
                    }
                    return Err(e);
                }
            };
            let them = their_hello(&channel.receive_short_text()?, &hello_in.id)?;
            let their_name = json_field(&them, "name")?;
            {
                // Kept only if the code is still the one shown and the server still runs (not cancelled or
                // stopped during the handshake); otherwise no answer, and the other side keeps nothing either.
                let mut s = lock(shared);
                if stopped.load(Ordering::SeqCst) || s.code.as_deref() != Some(code.as_str()) {
                    return Err(stopped_error());
                }
                s.code = None;
                s.secrets.insert(hello_in.id.clone(), secret.to_vec());
                s.emit(LanEvent::Paired {
                    id: hello_in.id.clone(),
                    name: their_name.clone(),
                    platform: platform(&them),
                    secret: secret.to_vec(),
                });
                s.emit(LanEvent::Status(trf("%1$s associé", &[&their_name])));
            }
            channel.send_text(&hello(&me, &my_name))?;
        }
        lan::MODE_SYNC => {
            let secret = lock(shared).secrets.get(&hello_in.id).cloned();
            let Some(secret) = secret else {
                // Never paired here, or forgotten.
                refuse(&mut stream, Some(lan::status::UNKNOWN));
                return Err(LanError::Unknown.io());
            };
            let mut ch = lan::sync_accept(stream, &hello_in, &me, &secret)?;
            ch.get_mut().renew(TRANSFER_DEADLINE);
            let them = their_hello(&ch.receive_short_text()?, &hello_in.id)?;
            exchange(shared, stopped, &mut ch, &hello_in.id, Some(them))?;
        }
        _ => return Err(io::Error::other("unknown mode")),
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// What a device's interface saw.
    #[derive(Default)]
    struct Seen {
        paired: Vec<String>,
        merged: Vec<(String, String)>,
        status: Vec<String>,
        expired: usize,
    }

    fn start(id: &str, name: &str) -> (Lan, Arc<Mutex<Seen>>) {
        let (lan, mut rx) = Lan::start(id.into(), name.into(), HashMap::new(), false).unwrap();
        let seen = Arc::new(Mutex::new(Seen::default()));
        let (s, me) = (seen.clone(), id.to_owned());
        // Answers the snapshot requests with its own data (and, once merged, the device merged), until stopped.
        std::thread::spawn(move || {
            while let Some(e) = rx.blocking_recv() {
                let mut s = s.lock().unwrap();
                match e {
                    LanEvent::Snapshot(reply) => reply.send(format!("{{\"from\":\"{me}\"}}")),
                    LanEvent::Merge { id, json, merged, .. } => {
                        if let Some(reply) = merged {
                            reply.send(format!("{{\"from\":\"{me}\",\"merged\":\"{id}\"}}"));
                        }
                        s.merged.push((id, json));
                    }
                    LanEvent::Paired { id, .. } => s.paired.push(id),
                    LanEvent::Status(m) => s.status.push(m),
                    LanEvent::CodeExpired => s.expired += 1,
                    LanEvent::Found(_) => {}
                }
            }
        });
        (lan, seen)
    }

    fn at(lan: &Lan, id: &str) -> Found {
        Found {
            id: id.into(),
            name: id.into(),
            addr: SocketAddr::from(([127, 0, 0, 1], lan.port())),
            version: lan::VERSION,
        }
    }

    fn wait(what: &str, ok: impl Fn() -> bool) {
        let start = Instant::now();
        while !ok() {
            assert!(start.elapsed() < Duration::from_secs(5), "{what}");
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    fn secret(lan: &Lan, of: &str) -> Option<Vec<u8>> {
        lock(&lan.shared).secrets.get(of).cloned()
    }

    fn error_of(r: io::Result<()>) -> Option<LanError> {
        LanError::of(&r.unwrap_err())
    }

    /// A device that never paired with anyone, talking straight to `to`.
    fn stranger_sync(to: &Lan, id: &str, to_id: &str) -> io::Result<()> {
        let s = TcpStream::connect(("127.0.0.1", to.port()))?;
        lan::sync_connect(s, id, to_id, &[9u8; 32]).map(|_| ())
    }

    fn stranger_pair(to: &Lan, id: &str, to_id: &str, code: &str) -> io::Result<()> {
        let s = TcpStream::connect(("127.0.0.1", to.port()))?;
        lan::pair_connect(s, id, to_id, code).map(|_| ())
    }

    #[test]
    fn pairing_synchronisation_and_refusals() {
        let (a, seen_a) = start("aaaa", "Bureau");
        let (b, seen_b) = start("bbbb", "Pixel");
        let (c, _) = start("cccc", "Portable");

        // A wrong code is refused on both sides; the right one pairs.
        let code = b.show_code();
        let wrong = if code == "111111" { "222222" } else { "111111" };
        assert_eq!(error_of(a.pair(&at(&b, "bbbb"), wrong)), Some(LanError::AuthFailed));
        assert!(secret(&a, "bbbb").is_none() && secret(&b, "aaaa").is_none());
        a.pair(&at(&b, "bbbb"), &code).unwrap();
        wait("paired", || {
            seen_a.lock().unwrap().paired == ["bbbb"] && seen_b.lock().unwrap().paired == ["aaaa"]
        });
        assert_eq!(secret(&a, "bbbb"), secret(&b, "aaaa"), "same secret on both sides");
        // The code served once.
        assert_eq!(error_of(a.pair(&at(&b, "bbbb"), &code)), Some(LanError::NoCode));

        // Both ways, each merging the other's data: B answers with its data once merged.
        a.sync_with(&at(&b, "bbbb")).unwrap();
        wait("merged", || {
            seen_a
                .lock()
                .unwrap()
                .merged
                .contains(&("bbbb".into(), "{\"from\":\"bbbb\",\"merged\":\"aaaa\"}".into()))
                && seen_b
                    .lock()
                    .unwrap()
                    .merged
                    .contains(&("aaaa".into(), "{\"from\":\"aaaa\"}".into()))
        });

        // A second pairing keeps the first one working, with another secret.
        let code_c = c.show_code();
        a.pair(&at(&c, "cccc"), &code_c).unwrap();
        assert_ne!(secret(&a, "bbbb"), secret(&a, "cccc"));
        a.sync_with(&at(&b, "bbbb")).unwrap();
        a.sync_with(&at(&c, "cccc")).unwrap();

        // An unknown device, or one claiming to be A without its secret, is refused.
        assert_eq!(error_of(stranger_sync(&b, "zzzz", "bbbb")), Some(LanError::Unknown));
        assert_eq!(error_of(stranger_sync(&b, "aaaa", "bbbb")), Some(LanError::AuthFailed));
        // C is not paired with B.
        assert!(c.sync_with(&at(&b, "bbbb")).is_err());

        // Forgotten on B: A is refused there, and nothing more is merged.
        let merged = seen_b.lock().unwrap().merged.len();
        b.forget("aaaa");
        assert_eq!(error_of(a.sync_with(&at(&b, "bbbb"))), Some(LanError::Unknown));
        assert_eq!(seen_b.lock().unwrap().merged.len(), merged);

        // An old device is told to update.
        let mut old = at(&b, "bbbb");
        old.version = 1;
        assert_eq!(error_of(a.pair(&old, "123456")), Some(LanError::PeerTooOld));
        // A current one hanging up without an answer is not taken for an old one.
        let mute = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut gone = at(&b, "bbbb");
        gone.addr = mute.local_addr().unwrap();
        std::thread::spawn(move || {
            if let Ok((s, _)) = mute.accept() {
                drop(s);
            }
        });
        assert_eq!(error_of(a.pair(&gone, "123456")), Some(LanError::Unreachable));
    }

    #[test]
    fn three_tries_even_in_parallel() {
        let (b, seen) = start("bbbb", "Pixel");
        let code = b.show_code();
        let wrong = if code == "111111" { "222222" } else { "111111" };
        let barrier = Arc::new(std::sync::Barrier::new(8));
        let tries: Vec<_> = (0..8)
            .map(|i| {
                let (b, barrier) = (b.clone(), barrier.clone());
                std::thread::spawn(move || {
                    barrier.wait();
                    LanError::of(&stranger_pair(&b, &format!("x{i}"), "bbbb", wrong).unwrap_err())
                })
            })
            .collect();
        let results: Vec<_> = tries.into_iter().map(|t| t.join().unwrap()).collect();
        let guessed = results.iter().filter(|r| **r == Some(LanError::AuthFailed)).count();
        assert!(guessed <= MAX_TRIES as usize, "{results:?}");
        wait("tries counted", || {
            let s = lock(&b.shared);
            s.attempts >= MAX_TRIES && s.code.is_none()
        });
        // The right code no longer works: a new one is needed, and the interface is told so once.
        assert_eq!(error_of(stranger_pair(&b, "aaaa", "bbbb", &code)), Some(LanError::NoCode));
        wait("expired", || seen.lock().unwrap().expired == 1);
    }

    #[test]
    fn cancelling_or_stopping_during_the_handshake_pairs_nobody() {
        for stop in [false, true] {
            let (b, seen) = start("bbbb", "Pixel");
            let code = b.show_code();
            let s = TcpStream::connect(("127.0.0.1", b.port())).unwrap();
            let lan::Paired { mut channel, .. } = lan::pair_connect(s, "aaaa", "bbbb", &code).unwrap();
            if stop {
                b.stop();
            } else {
                b.cancel_code();
            }
            channel.send_text(&hello("aaaa", "Bureau")).unwrap();
            // No hello back: the initiator keeps nothing either.
            assert!(channel.receive_short_text().is_err());
            assert!(secret(&b, "aaaa").is_none());
            assert!(seen.lock().unwrap().paired.is_empty());
        }
    }

    #[test]
    fn a_slow_handshake_is_cut_at_its_deadline() {
        let (b, _) = start("bbbb", "Pixel");
        let mut s = TcpStream::connect(("127.0.0.1", b.port())).unwrap();
        s.write_all(b"NLAN\x02P\x40").unwrap();
        // One byte of the id at a time, each well within the per-read wait.
        let mut drip = s.try_clone().unwrap();
        std::thread::spawn(move || {
            for _ in 0..40 {
                if drip.write_all(b"a").is_err() {
                    break;
                }
                std::thread::sleep(Duration::from_millis(150));
            }
        });
        let start = Instant::now();
        s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let r = s.read(&mut [0u8; 1]);
        assert!(matches!(r, Ok(0)) || r.is_err_and(|e| e.kind() != io::ErrorKind::WouldBlock && e.kind() != io::ErrorKind::TimedOut));
        assert!(
            start.elapsed() < HANDSHAKE_DEADLINE + Duration::from_secs(1),
            "{:?}",
            start.elapsed()
        );
    }

    #[test]
    fn old_versions_are_answered_and_reported() {
        let (b, seen) = start("bbbb", "Pixel");
        let mut s = TcpStream::connect(("127.0.0.1", b.port())).unwrap();
        s.write_all(b"NLAN\x01S").unwrap();
        s.write_all(&[7u8; 32]).unwrap();
        let mut reply = Vec::new();
        s.read_to_end(&mut reply).unwrap();
        assert_eq!(reply, b"NLAN\x02\x01");
        wait("reported", || {
            seen.lock().unwrap().status.iter().any(|m| m.contains("ancienne version"))
        });
    }

    #[test]
    fn connections_are_capped() {
        let (b, _) = start("bbbb", "Pixel");
        let idle: Vec<TcpStream> = (0..MAX_CONNECTIONS)
            .map(|_| TcpStream::connect(("127.0.0.1", b.port())).unwrap())
            .collect();
        std::thread::sleep(Duration::from_millis(200));
        // Told « busy », not left to guess.
        assert_eq!(error_of(stranger_sync(&b, "zzzz", "bbbb")), Some(LanError::Busy));
        let mut raw = TcpStream::connect(("127.0.0.1", b.port())).unwrap();
        let mut reply = Vec::new();
        raw.read_to_end(&mut reply).unwrap();
        assert_eq!(reply, b"NLAN\x02\x04");
        drop(idle);
        std::thread::sleep(Duration::from_millis(200));
        // Slots free again: a real request is answered.
        assert_eq!(error_of(stranger_sync(&b, "zzzz", "bbbb")), Some(LanError::Unknown));
    }

    #[test]
    fn stopping_closes_the_server() {
        let (a, _) = start("aaaa", "Bureau");
        let (b, seen_b) = start("bbbb", "Pixel");
        let code = b.show_code();
        a.pair(&at(&b, "bbbb"), &code).unwrap();
        a.sync_with(&at(&b, "bbbb")).unwrap();
        wait("merged", || seen_b.lock().unwrap().merged.len() == 1);
        b.stop();
        wait("port closed", || TcpStream::connect(("127.0.0.1", b.port())).is_err());
        assert!(a.sync_with(&at(&b, "bbbb")).is_err());
        assert_eq!(seen_b.lock().unwrap().merged.len(), 1);
        assert!(secret(&b, "aaaa").is_none());
    }
}
