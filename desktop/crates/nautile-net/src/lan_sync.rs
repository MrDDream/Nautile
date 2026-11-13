//! Synchronisation straight between devices on the same network (Android's `LanSync.kt`): each announces itself
//! (mDNS « _nautile._tcp »), pairs once with a 6-digit code, then exchanges its data with its paired devices
//! found nearby (protocol v1 in `nautile_core::lan`). The data itself stays on the interface's side: it is asked
//! for ([LanEvent::Snapshot]) and handed back to merge ([LanEvent::Merge]).

use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, SocketAddr, TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine;
use nautile_core::lan;
use nautile_core::{tr, trf};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};

use crate::session::Reply;

/// A device announcing itself on the network.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Found {
    pub id: String,
    pub name: String,
    pub addr: SocketAddr,
}

#[derive(Debug, Clone)]
pub enum LanEvent {
    /// The Nautile devices seen on the network now (this one excepted).
    Found(Vec<Found>),
    /// Paired with a device; `secret`: the pairing secret to keep from now on.
    Paired {
        id: String,
        name: String,
        secret: Vec<u8>,
    },
    /// This device's data (`BackupData` JSON) is needed for an exchange.
    Snapshot(Reply<String>),
    /// The data of a paired device, to merge.
    Merge {
        id: String,
        name: String,
        json: String,
    },
    Status(String),
}

struct Shared {
    device_id: String,
    device_name: String,
    code: Option<String>,
    attempts: u32,
    secret: Option<Vec<u8>>,
    found: HashMap<String, Found>,
    events: UnboundedSender<LanEvent>,
}

/// The running Wi-Fi synchronisation (server, announcement, discovery).
#[derive(Clone)]
pub struct Lan {
    shared: Arc<Mutex<Shared>>,
    daemon: Option<mdns_sd::ServiceDaemon>,
    port: u16,
}

fn b64(b: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(b)
}

fn json_field(v: &serde_json::Value, k: &str) -> io::Result<String> {
    v.get(k)
        .and_then(|x| x.as_str())
        .map(str::to_owned)
        .ok_or_else(|| io::Error::other(format!("missing {k}")))
}

impl Lan {
    /// Listens, announces this device and looks for the others. `announce: false` for tests (no mDNS).
    pub fn start(
        device_id: String,
        device_name: String,
        secret: Option<Vec<u8>>,
        announce: bool,
    ) -> io::Result<(Lan, UnboundedReceiver<LanEvent>)> {
        let (tx, rx) = mpsc::unbounded_channel();
        let shared = Arc::new(Mutex::new(Shared {
            device_id: device_id.clone(),
            device_name: device_name.clone(),
            code: None,
            attempts: 0,
            secret,
            found: HashMap::new(),
            events: tx,
        }));
        let listener = TcpListener::bind("0.0.0.0:0")?;
        let port = listener.local_addr()?.port();
        let s = shared.clone();
        std::thread::Builder::new().name("lan-sync-server".into()).spawn(move || {
            for stream in listener.incoming().flatten() {
                let s = s.clone();
                std::thread::spawn(move || {
                    let _ = handle(&s, stream);
                });
            }
        })?;
        let mut me = Lan {
            shared,
            daemon: None,
            port,
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
        let host = format!("nautile-{}.local.", &id[..id.len().min(12)]);
        let props = [("id", id), ("v", "1")];
        let info =
            mdns_sd::ServiceInfo::new(lan::SERVICE_TYPE, &format!("Nautile {name}"), &host, "", self.port, &props[..])?.enable_addr_auto();
        daemon.register(info)?;
        let browse = daemon.browse(lan::SERVICE_TYPE)?;
        let shared = self.shared.clone();
        std::thread::Builder::new()
            .name("lan-sync-browse".into())
            .spawn(move || {
                while let Ok(e) = browse.recv() {
                    let mut s = shared.lock().unwrap_or_else(|e| e.into_inner());
                    match e {
                        mdns_sd::ServiceEvent::ServiceResolved(info) => {
                            let Some(id) = info.get_property_val_str("id").map(str::to_owned) else {
                                continue;
                            };
                            if id == s.device_id {
                                continue;
                            }
                            let Some(ip) = info.get_addresses_v4().into_iter().next() else {
                                continue;
                            };
                            let name = info
                                .get_fullname()
                                .split("._nautile")
                                .next()
                                .unwrap_or_default()
                                .trim_start_matches("Nautile ")
                                .replace("\\032", " ")
                                .replace("\\ ", " ");
                            s.found.insert(
                                id.clone(),
                                Found {
                                    id,
                                    name,
                                    addr: SocketAddr::new(IpAddr::V4(ip), info.get_port()),
                                },
                            );
                        }
                        mdns_sd::ServiceEvent::ServiceRemoved(_, fullname) => {
                            s.found.retain(|_, f| !fullname.starts_with(&format!("Nautile {}", f.name)));
                        }
                        _ => continue,
                    }
                    let list: Vec<Found> = s.found.values().cloned().collect();
                    let _ = s.events.send(LanEvent::Found(list));
                }
            })
            .map_err(|e| mdns_sd::Error::Msg(e.to_string()))?;
        Ok(daemon)
    }

    pub fn stop(&self) {
        if let Some(d) = &self.daemon {
            let _ = d.shutdown();
        }
    }

    pub fn set_secret(&self, secret: Option<Vec<u8>>) {
        self.shared.lock().unwrap_or_else(|e| e.into_inner()).secret = secret;
    }

    /// Shows a code on this device: another one types it to pair.
    pub fn show_code(&self) -> String {
        let code = lan::new_code();
        let mut s = self.shared.lock().unwrap_or_else(|e| e.into_inner());
        s.code = Some(code.clone());
        s.attempts = 0;
        code
    }

    pub fn cancel_code(&self) {
        self.shared.lock().unwrap_or_else(|e| e.into_inner()).code = None;
    }

    pub fn found(&self) -> Vec<Found> {
        self.shared
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .found
            .values()
            .cloned()
            .collect()
    }

    /// Pairs with [peer], which shows [code] (blocking).
    pub fn pair(&self, peer: &Found, code: &str) -> io::Result<()> {
        let (id, name, events) = {
            let s = self.shared.lock().unwrap_or_else(|e| e.into_inner());
            (s.device_id.clone(), s.device_name.clone(), s.events.clone())
        };
        let stream = TcpStream::connect_timeout(&peer.addr, Duration::from_secs(8))?;
        stream.set_read_timeout(Some(Duration::from_secs(30)))?;
        let mut ch = lan::connect(stream, lan::MODE_PAIR, code.trim().as_bytes())?;
        ch.send_text(&serde_json::json!({ "id": id, "name": name }).to_string())?;
        let them: serde_json::Value = serde_json::from_str(&ch.receive_text()?).map_err(io::Error::other)?;
        let secret = base64::engine::general_purpose::STANDARD
            .decode(json_field(&them, "secret")?)
            .map_err(io::Error::other)?;
        self.set_secret(Some(secret.clone()));
        let _ = events.send(LanEvent::Paired {
            id: json_field(&them, "id")?,
            name: json_field(&them, "name")?,
            secret,
        });
        let _ = events.send(LanEvent::Status(trf("%1$s associé", &[&peer.name])));
        Ok(())
    }

    /// One round with the paired devices found on the network (blocking); the number reached.
    pub fn sync_all(&self, paired: &[String]) -> usize {
        let (secret, nearby, events) = {
            let s = self.shared.lock().unwrap_or_else(|e| e.into_inner());
            let nearby: Vec<Found> = s.found.values().filter(|f| paired.contains(&f.id)).cloned().collect();
            (s.secret.clone(), nearby, s.events.clone())
        };
        let Some(secret) = secret else { return 0 };
        if nearby.is_empty() {
            let _ = events.send(LanEvent::Status(tr("Aucun appareil associé à portée")));
            return 0;
        }
        let reached = nearby.iter().filter(|p| self.sync_with(p, &secret).is_ok()).count();
        let _ = events.send(LanEvent::Status(if reached > 0 {
            trf("Synchronisé avec %1$s appareil(s)", &[&reached])
        } else {
            tr("Appareils associés injoignables")
        }));
        reached
    }

    /// One exchange with [peer] (blocking).
    pub fn sync_with(&self, peer: &Found, secret: &[u8]) -> io::Result<()> {
        let stream = TcpStream::connect_timeout(&peer.addr, Duration::from_secs(8))?;
        stream.set_read_timeout(Some(Duration::from_secs(60)))?;
        let mut ch = lan::connect(stream, lan::MODE_SYNC, secret)?;
        exchange(&self.shared, &mut ch, None)
    }
}

/// Both sides send their data and merge the other's: initiator (`them` None) sends first.
fn exchange<S: io::Read + io::Write>(
    shared: &Arc<Mutex<Shared>>,
    ch: &mut lan::Channel<S>,
    them: Option<serde_json::Value>,
) -> io::Result<()> {
    let (id, name, events) = {
        let s = shared.lock().unwrap_or_else(|e| e.into_inner());
        (s.device_id.clone(), s.device_name.clone(), s.events.clone())
    };
    let hello = serde_json::json!({ "id": id, "name": name }).to_string();
    let mine = || -> io::Result<String> {
        let (reply, rx) = Reply::new();
        events.send(LanEvent::Snapshot(reply)).map_err(io::Error::other)?;
        rx.blocking_recv().map_err(io::Error::other)
    };
    let (them, theirs) = match them {
        None => {
            let data = mine()?;
            ch.send_text(&hello)?;
            ch.send_text(&data)?;
            let them: serde_json::Value = serde_json::from_str(&ch.receive_text()?).map_err(io::Error::other)?;
            (them, ch.receive_text()?)
        }
        Some(them) => {
            let theirs = ch.receive_text()?;
            let data = mine()?;
            ch.send_text(&hello)?;
            ch.send_text(&data)?;
            (them, theirs)
        }
    };
    let _ = events.send(LanEvent::Merge {
        id: json_field(&them, "id")?,
        name: json_field(&them, "name")?,
        json: theirs,
    });
    Ok(())
}

/// An incoming connection: pairing (with the code shown) or synchronisation (with the pairing secret).
fn handle(shared: &Arc<Mutex<Shared>>, mut stream: TcpStream) -> io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(60)))?;
    let mode = lan::read_mode(&mut stream)?;
    match mode {
        lan::MODE_PAIR => {
            let code = shared
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .code
                .clone()
                .ok_or_else(|| io::Error::other("no pairing in progress"))?;
            let mut ch = match lan::accept(stream, mode, code.as_bytes()) {
                Ok(c) => c,
                Err(e) => {
                    let mut s = shared.lock().unwrap_or_else(|e| e.into_inner());
                    s.attempts += 1;
                    if s.attempts >= 3 {
                        s.code = None;
                        let _ = s.events.send(LanEvent::Status(tr("Trop d'essais : nouveau code nécessaire")));
                    }
                    return Err(e);
                }
            };
            let them: serde_json::Value = serde_json::from_str(&ch.receive_text()?).map_err(io::Error::other)?;
            let (id, name, secret, events) = {
                let mut s = shared.lock().unwrap_or_else(|e| e.into_inner());
                let secret = s.secret.clone().unwrap_or_else(|| lan::new_secret().to_vec());
                s.secret = Some(secret.clone());
                s.code = None;
                (s.device_id.clone(), s.device_name.clone(), secret, s.events.clone())
            };
            ch.send_text(&serde_json::json!({ "id": id, "name": name, "secret": b64(&secret) }).to_string())?;
            let their_name = json_field(&them, "name")?;
            let _ = events.send(LanEvent::Paired {
                id: json_field(&them, "id")?,
                name: their_name.clone(),
                secret,
            });
            let _ = events.send(LanEvent::Status(trf("%1$s associé", &[&their_name])));
        }
        lan::MODE_SYNC => {
            let secret = shared
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .secret
                .clone()
                .ok_or_else(|| io::Error::other("not paired"))?;
            let mut ch = lan::accept(stream, mode, &secret)?;
            let them: serde_json::Value = serde_json::from_str(&ch.receive_text()?).map_err(io::Error::other)?;
            exchange(shared, &mut ch, Some(them))?;
        }
        _ => return Err(io::Error::other("unknown mode")),
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Two devices on this computer: pairing with the code, then an exchange both ways.
    #[test]
    fn two_devices_pair_then_sync() {
        let (a, mut ra) = Lan::start("aaaa".into(), "Bureau".into(), None, false).unwrap();
        let (b, mut rb) = Lan::start("bbbb".into(), "Pixel".into(), None, false).unwrap();
        // Each side answers its snapshot requests with its own data.
        let serve = |mut r: UnboundedReceiver<LanEvent>, data: &'static str| {
            std::thread::spawn(move || {
                let mut got = Vec::new();
                while let Some(e) = r.blocking_recv() {
                    match e {
                        LanEvent::Snapshot(reply) => reply.send(data.to_owned()),
                        LanEvent::Merge { json, id, .. } => got.push((id, json)),
                        LanEvent::Paired { id, .. } => got.push((id, "paired".into())),
                        _ => {}
                    }
                    if got.len() == 2 {
                        break;
                    }
                }
                got
            })
        };
        let code = b.show_code();
        let peer_b = Found {
            id: "bbbb".into(),
            name: "Pixel".into(),
            addr: SocketAddr::from(([127, 0, 0, 1], b.port())),
        };
        assert!(a.pair(&peer_b, "000000").is_err() || code == "000000");
        let code = b.show_code();
        a.pair(&peer_b, &code).unwrap();
        let ta = serve(std::mem::replace(&mut ra, mpsc::unbounded_channel().1), "{\"from\":\"a\"}");
        let tb = serve(std::mem::replace(&mut rb, mpsc::unbounded_channel().1), "{\"from\":\"b\"}");
        let secret = a.shared.lock().unwrap().secret.clone().unwrap();
        assert_eq!(Some(secret.clone()), b.shared.lock().unwrap().secret.clone(), "same secret");
        a.sync_with(&peer_b, &secret).unwrap();
        let got_a = ta.join().unwrap();
        let got_b = tb.join().unwrap();
        assert!(got_a.contains(&("bbbb".into(), "paired".into())));
        assert!(got_a.contains(&("bbbb".into(), "{\"from\":\"b\"}".into())));
        assert!(got_b.contains(&("aaaa".into(), "paired".into())));
        assert!(got_b.contains(&("aaaa".into(), "{\"from\":\"a\"}".into())));
    }
}
