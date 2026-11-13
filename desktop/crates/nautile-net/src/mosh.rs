//! Mosh (State Synchronization Protocol over UDP) without local echo prediction, ported from Android's
//! `MoshChannel.kt`: `mosh-server` is started over SSH, then the screen updates of the server (terminal byte
//! strings) are fed to the emulator, which acts as the client framebuffer.

use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::time::{Duration, Instant};

use base64::Engine;
use nautile_core::{tr, trf};
use ocb3::aead::{Aead, KeyInit};
use ocb3::Ocb3;
use russh::ChannelMsg;
use tokio::net::UdpSocket;

use crate::session::{channels, Event, Events, Input, Session, RUNTIME};
use crate::ssh::{self, Params};

const DIRECTION_BIT: u64 = 1 << 63;
const FRAGMENT_BYTES: usize = 400;
const TICK: Duration = Duration::from_millis(50);
const RESEND: Duration = Duration::from_millis(300);
const HEARTBEAT: Duration = Duration::from_millis(3000);
pub const SERVER_COMMAND: &str = "mosh-server new -s -c 256 -l LANG=C.UTF-8";

type Cipher = Ocb3<aes::Aes128>;

struct Crypto(Cipher);

impl Crypto {
    fn new(key: &[u8; 16]) -> Crypto {
        Crypto(Cipher::new(key.into()))
    }

    fn nonce(v: u64) -> [u8; 12] {
        let mut n = [0u8; 12];
        n[4..].copy_from_slice(&v.to_be_bytes());
        n
    }

    fn seal(&self, seq: u64, plain: &[u8]) -> Vec<u8> {
        let nonce = seq & !DIRECTION_BIT; // direction bit 0: to the server
        let sealed = self.0.encrypt(&Self::nonce(nonce).into(), plain).unwrap_or_default();
        [nonce.to_be_bytes().as_slice(), &sealed].concat()
    }

    fn open(&self, packet: &[u8]) -> Option<Vec<u8>> {
        let nonce = u64::from_be_bytes(packet.get(..8)?.try_into().ok()?);
        if nonce & DIRECTION_BIT == 0 {
            return None; // not from the server
        }
        self.0.decrypt(&Self::nonce(nonce).into(), &packet[8..]).ok()
    }
}

/// Hand-written protobuf for mosh's three tiny message types.
mod proto {
    pub struct Instruction {
        pub version: u64,
        pub old_num: u64,
        pub new_num: u64,
        pub ack_num: u64,
        pub diff: Vec<u8>,
    }

    fn varint(out: &mut Vec<u8>, mut v: u64) {
        while v >= 0x80 {
            out.push((v as u8 & 0x7f) | 0x80);
            v >>= 7;
        }
        out.push(v as u8);
    }

    fn varint_field(out: &mut Vec<u8>, f: u64, v: u64) {
        varint(out, f << 3);
        varint(out, v);
    }

    fn bytes_field(out: &mut Vec<u8>, f: u64, v: &[u8]) {
        varint(out, (f << 3) | 2);
        varint(out, v.len() as u64);
        out.extend_from_slice(v);
    }

    pub fn instruction(old_num: u64, new_num: u64, ack_num: u64, throwaway: u64, diff: &[u8]) -> Vec<u8> {
        let mut o = Vec::new();
        varint_field(&mut o, 1, 2);
        varint_field(&mut o, 2, old_num);
        varint_field(&mut o, 3, new_num);
        varint_field(&mut o, 4, ack_num);
        varint_field(&mut o, 5, throwaway);
        bytes_field(&mut o, 6, diff);
        o
    }

    pub fn keystroke(keys: &[u8]) -> Vec<u8> {
        let mut inner = Vec::new();
        bytes_field(&mut inner, 4, keys);
        let mut o = Vec::new();
        bytes_field(&mut o, 2, &inner);
        o
    }

    pub fn resize(width: u16, height: u16) -> Vec<u8> {
        let mut inner = Vec::new();
        varint_field(&mut inner, 5, width as u64);
        varint_field(&mut inner, 6, height as u64);
        let mut o = Vec::new();
        bytes_field(&mut o, 3, &inner);
        o
    }

    /// UserMessage { repeated Instruction instruction = 1 }
    pub fn user_message(instructions: &[Vec<u8>]) -> Vec<u8> {
        let mut o = Vec::new();
        for i in instructions {
            bytes_field(&mut o, 1, i);
        }
        o
    }

    /// (field, varint value, bytes) of each field.
    fn fields(b: &[u8]) -> Vec<(u64, u64, Option<&[u8]>)> {
        let mut out = Vec::new();
        let mut i = 0;
        let read = |i: &mut usize| -> Option<u64> {
            let mut r = 0u64;
            let mut shift = 0;
            loop {
                let x = *b.get(*i)?;
                *i += 1;
                r |= ((x & 0x7f) as u64) << shift;
                if x < 0x80 {
                    return Some(r);
                }
                shift += 7;
                if shift > 63 {
                    return None;
                }
            }
        };
        while i < b.len() {
            let Some(tag) = read(&mut i) else { break };
            let field = tag >> 3;
            match tag & 7 {
                0 => match read(&mut i) {
                    Some(v) => out.push((field, v, None)),
                    None => break,
                },
                2 => {
                    let Some(len) = read(&mut i) else { break };
                    let Some(v) = b.get(i..i + len as usize) else { break };
                    out.push((field, 0, Some(v)));
                    i += len as usize;
                }
                1 => i += 8,
                5 => i += 4,
                _ => break,
            }
        }
        out
    }

    pub fn parse_instruction(b: &[u8]) -> Instruction {
        let mut inst = Instruction {
            version: 0,
            old_num: 0,
            new_num: 0,
            ack_num: 0,
            diff: Vec::new(),
        };
        for (f, v, bytes) in fields(b) {
            match f {
                1 => inst.version = v,
                2 => inst.old_num = v,
                3 => inst.new_num = v,
                4 => inst.ack_num = v,
                6 => inst.diff = bytes.unwrap_or_default().to_vec(),
                _ => {}
            }
        }
        inst
    }

    /// HostMessage → the hoststring of each HostBytes instruction.
    pub fn host_strings(diff: &[u8]) -> Vec<Vec<u8>> {
        let mut out = Vec::new();
        for (f, _, inst) in fields(diff) {
            let (1, Some(inst)) = (f, inst) else { continue };
            for (ext, _, host_bytes) in fields(inst) {
                let (2, Some(hb)) = (ext, host_bytes) else { continue };
                for (hf, _, s) in fields(hb) {
                    if let (4, Some(s)) = (hf, s) {
                        out.push(s.to_vec());
                    }
                }
            }
        }
        out
    }
}

fn zlib(b: &[u8]) -> Vec<u8> {
    let mut e = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
    let _ = e.write_all(b);
    e.finish().unwrap_or_default()
}

fn unzlib(b: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    flate2::read::ZlibDecoder::new(b).take(16 << 20).read_to_end(&mut out).ok()?;
    Some(out)
}

#[derive(Default)]
struct Assembly {
    id: Option<u64>,
    parts: BTreeMap<u16, Vec<u8>>,
    total: Option<usize>,
}

impl Assembly {
    fn add(&mut self, id: u64, num: u16, last: bool, data: &[u8]) -> Option<Vec<u8>> {
        if self.id != Some(id) {
            self.id = Some(id);
            self.parts.clear();
            self.total = None;
        }
        self.parts.insert(num, data.to_vec());
        if last {
            self.total = Some(num as usize + 1);
        }
        if self.total? != self.parts.len() {
            return None;
        }
        let whole = self.parts.values().flatten().copied().collect();
        *self = Assembly::default();
        Some(whole)
    }
}

/// Both sides of the synchronisation.
struct State {
    crypto: Crypto,
    // Client → server: a growing list of user events; state n = the first `events_at[n]` events.
    events: Vec<Vec<u8>>,
    events_at: BTreeMap<u64, usize>,
    current: u64,
    acked: u64,
    last_sent: Option<u64>,
    last_send: Option<Instant>,
    // Server → client.
    remote: u64,
    send_seq: u64,
    fragment_id: u64,
    assembly: Assembly,
    start: Instant,
}

impl State {
    fn new(key: &[u8; 16]) -> State {
        State {
            crypto: Crypto::new(key),
            events: Vec::new(),
            events_at: BTreeMap::from([(0, 0)]),
            current: 0,
            acked: 0,
            last_sent: None,
            last_send: None,
            remote: 0,
            send_seq: 0,
            fragment_id: 0,
            assembly: Assembly::default(),
            start: Instant::now(),
        }
    }

    fn push(&mut self, event: Vec<u8>) {
        self.events.push(event);
        self.current += 1;
        self.events_at.insert(self.current, self.events.len());
    }

    /// The packets to send now: our state (diff from what the server acknowledged) and our ack of its state.
    fn packets(&mut self, force: bool, shutdown: bool) -> Vec<Vec<u8>> {
        let now = Instant::now();
        let since = self.last_send.map(|t| now - t).unwrap_or(Duration::MAX);
        let pending = self.last_sent != Some(self.current) || self.acked != self.current;
        if !force && !shutdown && !(pending && since > RESEND) && since < HEARTBEAT {
            return Vec::new();
        }
        let from = self.events_at.get(&self.acked).copied().unwrap_or(0);
        let diff = if self.current == self.acked && !shutdown {
            Vec::new()
        } else {
            proto::user_message(&self.events[from..])
        };
        let new_num = if shutdown { u64::MAX } else { self.current };
        let inst = proto::instruction(self.acked, new_num, self.remote, self.acked, &diff);
        self.last_sent = Some(self.current);
        self.last_send = Some(now);
        let compressed = zlib(&inst);
        let id = self.fragment_id;
        self.fragment_id += 1;
        let chunks: Vec<&[u8]> = if compressed.is_empty() {
            vec![&[]]
        } else {
            compressed.chunks(FRAGMENT_BYTES).collect()
        };
        let last = chunks.len() - 1;
        let timestamp = (self.start.elapsed().as_millis() % 65536) as u16;
        chunks
            .iter()
            .enumerate()
            .map(|(i, chunk)| {
                let mut plain = Vec::with_capacity(14 + chunk.len());
                plain.extend_from_slice(&timestamp.to_be_bytes());
                plain.extend_from_slice(&0xffffu16.to_be_bytes());
                plain.extend_from_slice(&id.to_be_bytes());
                plain.extend_from_slice(&((if i == last { 0x8000 } else { 0 }) | i as u16).to_be_bytes());
                plain.extend_from_slice(chunk);
                let p = self.crypto.seal(self.send_seq, &plain);
                self.send_seq += 1;
                p
            })
            .collect()
    }

    /// A datagram from the server: the bytes for the terminal, and whether the server said goodbye.
    fn receive(&mut self, packet: &[u8]) -> Option<(Vec<Vec<u8>>, bool, bool)> {
        let plain = self.crypto.open(packet)?;
        if plain.len() < 14 {
            return None;
        }
        let id = u64::from_be_bytes(plain[4..12].try_into().ok()?);
        let num_final = u16::from_be_bytes(plain[12..14].try_into().ok()?);
        let whole = self.assembly.add(id, num_final & 0x7fff, num_final & 0x8000 != 0, &plain[14..])?;
        let inst = proto::parse_instruction(&unzlib(&whole)?);
        if inst.version != 2 {
            return None;
        }
        if self.events_at.contains_key(&inst.ack_num) && inst.ack_num > self.acked {
            self.events_at.retain(|k, _| *k >= inst.ack_num);
            self.acked = inst.ack_num;
        }
        if inst.new_num == u64::MAX {
            return Some((Vec::new(), true, true));
        }
        if inst.new_num > self.remote && inst.old_num == self.remote {
            self.remote = inst.new_num;
            let out = proto::host_strings(&inst.diff).into_iter().filter(|s| !s.is_empty()).collect();
            return Some((out, true, false));
        }
        Some((Vec::new(), false, false))
    }
}

/// Port and key from `mosh-server`'s answer.
pub fn parse_connect(out: &str) -> Result<(u16, [u8; 16]), String> {
    for line in out.lines() {
        let mut w = line.split_whitespace();
        if let (Some("MOSH"), Some("CONNECT"), Some(port), Some(key)) = (w.next(), w.next(), w.next(), w.next()) {
            let port = port.parse().map_err(|_| tr("Réponse inattendue de mosh-server"))?;
            let key = base64::engine::general_purpose::STANDARD_NO_PAD
                .decode(key.trim_end_matches('='))
                .ok()
                .and_then(|k| <[u8; 16]>::try_from(k).ok())
                .ok_or_else(|| tr("Réponse inattendue de mosh-server"))?;
            return Ok((port, key));
        }
    }
    Err(if out.contains("not found") {
        tr("mosh-server n'est pas installé sur le serveur")
    } else {
        trf(
            "Réponse inattendue de mosh-server : %1$s",
            &[&out.trim().chars().take(200).collect::<String>()],
        )
    })
}

async fn start_server(conn: &ssh::Connection) -> Result<String, String> {
    let mut ch = conn.handle.channel_open_session().await.map_err(|e| e.to_string())?;
    ch.exec(true, format!("{SERVER_COMMAND} 2>&1 < /dev/null"))
        .await
        .map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
    loop {
        match tokio::time::timeout_at(deadline, ch.wait()).await {
            Ok(Some(ChannelMsg::Data { data })) | Ok(Some(ChannelMsg::ExtendedData { data, .. })) => out.extend_from_slice(&data),
            Ok(Some(ChannelMsg::Eof | ChannelMsg::Close)) | Ok(None) | Err(_) => break,
            Ok(Some(_)) => {}
        }
        if String::from_utf8_lossy(&out).contains("MOSH CONNECT") && out.ends_with(b"\n") {
            break;
        }
    }
    Ok(String::from_utf8_lossy(&out).into_owned())
}

/// Starts mosh-server through SSH, then talks to it over UDP.
pub fn spawn(p: Params, cols: u16, rows: u16) -> Session {
    let (session, events, input) = channels();
    RUNTIME.spawn(async move {
        let reason = run(p, cols, rows, &events, input).await.err();
        events.send(Event::Closed(reason));
    });
    session
}

async fn run(
    p: Params,
    cols: u16,
    rows: u16,
    events: &Events,
    mut input: tokio::sync::mpsc::UnboundedReceiver<Input>,
) -> Result<(), String> {
    let udp_host = p.chain.last().map(|t| t.host.clone()).unwrap_or_default();
    let conn = ssh::connect(&p, events).await?;
    events.status(tr("Démarrage de mosh-server…"));
    let out = start_server(&conn).await;
    conn.close().await;
    let (port, key) = parse_connect(&out?)?;
    let addr = tokio::net::lookup_host((udp_host.as_str(), port))
        .await
        .map_err(|e| e.to_string())?
        .next()
        .ok_or_else(|| trf("Adresse introuvable : %1$s", &[&udp_host]))?;
    let sock = UdpSocket::bind(if addr.is_ipv6() { "[::]:0" } else { "0.0.0.0:0" })
        .await
        .map_err(|e| e.to_string())?;
    sock.connect(addr).await.map_err(|e| e.to_string())?;
    let mut st = State::new(&key);
    st.push(proto::resize(cols, rows));
    events.send(Event::Connected);
    let mut tick = tokio::time::interval(TICK);
    let mut buf = vec![0u8; 2048];
    let mut force = true;
    loop {
        for packet in st.packets(force, false) {
            let _ = sock.send(&packet).await;
        }
        force = false;
        tokio::select! {
            n = sock.recv(&mut buf) => {
                // Unreachable for now (no route, server not listening yet): mosh keeps trying.
                let Ok(n) = n else { continue };
                if let Some((data, ack, gone)) = st.receive(&buf[..n]) {
                    for d in data {
                        events.output(&d);
                    }
                    if gone {
                        for packet in st.packets(true, false) {
                            let _ = sock.send(&packet).await;
                        }
                        return Ok(());
                    }
                    force = ack;
                }
            }
            i = input.recv() => match i {
                Some(Input::Data(d)) => {
                    st.push(proto::keystroke(&d));
                    force = true;
                }
                Some(Input::Resize { cols, rows }) => {
                    st.push(proto::resize(cols, rows));
                    force = true;
                }
                Some(Input::Close) | None => {
                    for packet in st.packets(true, true) {
                        let _ = sock.send(&packet).await;
                    }
                    return Ok(());
                }
            },
            _ = tick.tick() => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mosh_server_answer_is_read() {
        let (port, key) = parse_connect("\r\nMOSH CONNECT 60001 4NeCCgvZFe2RnPgrcU1PQw\r\n\nmosh-server (mosh 1.4.0)").unwrap();
        assert_eq!(port, 60001);
        assert_eq!(key.len(), 16);
        assert!(parse_connect("sh: mosh-server: not found").unwrap_err().contains("mosh-server"));
    }

    /// A server built from the same pieces: its packets go through ours and back.
    #[test]
    fn packets_round_trip() {
        let key = [7u8; 16];
        let mut client = State::new(&key);
        client.push(proto::keystroke(b"ls\r"));
        let packets = client.packets(true, false);
        assert_eq!(packets.len(), 1);
        // Decode as the server would (direction bit 0).
        let c = Crypto::new(&key);
        let nonce = u64::from_be_bytes(packets[0][..8].try_into().unwrap());
        assert_eq!(nonce & DIRECTION_BIT, 0);
        let plain = c.0.decrypt(&Crypto::nonce(nonce).into(), &packets[0][8..]).unwrap();
        let inst = proto::parse_instruction(&unzlib(&plain[14..]).unwrap());
        assert_eq!((inst.version, inst.old_num, inst.new_num), (2, 0, 1));

        // The server's answer: screen bytes, acknowledging our state 1.
        let mut hb = Vec::new();
        hb.extend_from_slice(&[0x22]); // field 4, bytes
        hb.push(5);
        hb.extend_from_slice(b"hello");
        let mut ext = vec![0x12, hb.len() as u8];
        ext.extend(hb);
        let mut host_msg = vec![0x0a, ext.len() as u8];
        host_msg.extend(ext);
        let inst = proto::instruction(0, 1, 1, 0, &host_msg);
        let z = zlib(&inst);
        let mut plain = vec![0, 0, 0xff, 0xff];
        plain.extend_from_slice(&9u64.to_be_bytes());
        plain.extend_from_slice(&0x8000u16.to_be_bytes());
        plain.extend(z);
        let nonce = DIRECTION_BIT | 1;
        let sealed = c.0.encrypt(&Crypto::nonce(nonce).into(), plain.as_slice()).unwrap();
        let packet = [nonce.to_be_bytes().as_slice(), &sealed].concat();
        let (data, ack, gone) = client.receive(&packet).unwrap();
        assert_eq!(data, vec![b"hello".to_vec()]);
        assert!(ack && !gone);
        assert_eq!((client.acked, client.remote), (1, 1));
    }
}
