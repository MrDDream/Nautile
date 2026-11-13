//! The Wi-Fi device-to-device protocol, version 1, byte-compatible with the Android app
//! (app/.../backup/lan/LanProtocol.kt):
//!
//! 1. Initiator → responder: `NLAN` · version 1 · mode (`P` pair, `S` sync) · X25519 public key;
//!    responder → initiator: its public key.
//! 2. `transcript = "nautile-lan-v1" · mode · pubI · pubR`, `prk = HMAC(transcript, shared)`,
//!    `i2r = HMAC(prk, "i2r")`, `r2i = HMAC(prk, "r2i")`, `proofKey = HMAC(prk, "proof" · auth)`;
//!    each side sends `HMAC(proofKey, transcript · role)` (role `I` then `R`). `auth` is the 6-digit code when
//!    pairing, the 32-byte pairing secret when syncing.
//! 3. Frames: u32 big-endian length, AES-256-GCM (no associated data), nonce = 4 zero bytes · u64 counter per direction.

use std::io::{self, Read, Write};

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use hmac::{Hmac, Mac};
use rand::RngCore;
use sha2::Sha256;
use x25519_dalek::{PublicKey, StaticSecret};

use crate::i18n::tr;

pub const VERSION: u8 = 1;
pub const MODE_PAIR: u8 = b'P';
pub const MODE_SYNC: u8 = b'S';
/// mDNS / DNS-SD service type.
pub const SERVICE_TYPE: &str = "_nautile._tcp.local.";
const MAGIC: &[u8; 4] = b"NLAN";
const MAX_FRAME: usize = 64 * 1024 * 1024;

fn err(msg: String) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg)
}

/// The other side does not know the code or the pairing secret.
pub fn auth_failed() -> io::Error {
    io::Error::new(io::ErrorKind::PermissionDenied, tr("Code ou appairage refusé par l'autre appareil"))
}

pub fn hmac(key: &[u8], data: &[u8]) -> [u8; 32] {
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("any key length");
    m.update(data);
    m.finalize().into_bytes().into()
}

/// An authenticated, encrypted channel over `stream`.
pub struct Channel<S> {
    stream: S,
    send: Aes256Gcm,
    receive: Aes256Gcm,
    sent: u64,
    received: u64,
}

fn nonce(n: u64) -> [u8; 12] {
    let mut b = [0u8; 12];
    b[4..].copy_from_slice(&n.to_be_bytes());
    b
}

impl<S: Read + Write> Channel<S> {
    pub fn send(&mut self, data: &[u8]) -> io::Result<()> {
        let sealed = self
            .send
            .encrypt(Nonce::from_slice(&nonce(self.sent)), data)
            .map_err(|_| err("encrypt".into()))?;
        self.sent += 1;
        self.stream.write_all(&(sealed.len() as u32).to_be_bytes())?;
        self.stream.write_all(&sealed)?;
        self.stream.flush()
    }

    pub fn receive(&mut self) -> io::Result<Vec<u8>> {
        let mut len = [0u8; 4];
        self.stream.read_exact(&mut len)?;
        let n = u32::from_be_bytes(len) as usize;
        if !(16..=MAX_FRAME).contains(&n) {
            return Err(err(tr("Message réseau invalide")));
        }
        let mut sealed = vec![0u8; n];
        self.stream.read_exact(&mut sealed)?;
        let plain = self
            .receive
            .decrypt(Nonce::from_slice(&nonce(self.received)), sealed.as_slice())
            .map_err(|_| err(tr("Message réseau invalide")))?;
        self.received += 1;
        Ok(plain)
    }

    pub fn send_text(&mut self, s: &str) -> io::Result<()> {
        self.send(s.as_bytes())
    }

    pub fn receive_text(&mut self) -> io::Result<String> {
        String::from_utf8(self.receive()?).map_err(|_| err(tr("Message réseau invalide")))
    }

    pub fn into_inner(self) -> S {
        self.stream
    }
}

struct Keys {
    i2r: [u8; 32],
    r2i: [u8; 32],
    proof_key: [u8; 32],
    transcript: Vec<u8>,
}

impl Keys {
    fn proof(&self, role: &[u8]) -> [u8; 32] {
        let mut data = self.transcript.clone();
        data.extend_from_slice(role);
        hmac(&self.proof_key, &data)
    }
}

fn keys(secret: &StaticSecret, pub_i: &[u8; 32], pub_r: &[u8; 32], initiator: bool, mode: u8, auth: &[u8]) -> io::Result<Keys> {
    let peer = PublicKey::from(if initiator { *pub_r } else { *pub_i });
    let shared = secret.diffie_hellman(&peer);
    if !shared.was_contributory() {
        return Err(err(tr("Clé réseau invalide")));
    }
    let mut transcript = b"nautile-lan-v1".to_vec();
    transcript.push(mode);
    transcript.extend_from_slice(pub_i);
    transcript.extend_from_slice(pub_r);
    let prk = hmac(&transcript, shared.as_bytes());
    let mut proof_data = b"proof".to_vec();
    proof_data.extend_from_slice(auth);
    Ok(Keys {
        i2r: hmac(&prk, b"i2r"),
        r2i: hmac(&prk, b"r2i"),
        proof_key: hmac(&prk, &proof_data),
        transcript,
    })
}

fn eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn random_secret() -> [u8; 32] {
    let mut b = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut b);
    b
}

fn cipher(key: &[u8; 32]) -> Aes256Gcm {
    Aes256Gcm::new_from_slice(key).expect("32-byte key")
}

/// Initiator side.
pub fn connect<S: Read + Write>(stream: S, mode: u8, auth: &[u8]) -> io::Result<Channel<S>> {
    connect_with(stream, mode, auth, random_secret())
}

/// [connect] with a given X25519 private key (the compatibility tests replay Android's recordings).
pub fn connect_with<S: Read + Write>(mut stream: S, mode: u8, auth: &[u8], private: [u8; 32]) -> io::Result<Channel<S>> {
    let secret = StaticSecret::from(private);
    let public = PublicKey::from(&secret).to_bytes();
    let mut hello = MAGIC.to_vec();
    hello.extend_from_slice(&[VERSION, mode]);
    hello.extend_from_slice(&public);
    stream.write_all(&hello)?;
    stream.flush()?;
    let mut peer = [0u8; 32];
    stream.read_exact(&mut peer)?;
    let k = keys(&secret, &public, &peer, true, mode, auth)?;
    stream.write_all(&k.proof(b"I"))?;
    stream.flush()?;
    let mut theirs = [0u8; 32];
    stream.read_exact(&mut theirs).map_err(|_| auth_failed())?;
    if !eq(&theirs, &k.proof(b"R")) {
        return Err(auth_failed());
    }
    Ok(Channel {
        stream,
        send: cipher(&k.i2r),
        receive: cipher(&k.r2i),
        sent: 0,
        received: 0,
    })
}

/// What an incoming connection asks for (`MODE_PAIR` or `MODE_SYNC`), read before choosing the secret to check.
pub fn read_mode<S: Read>(stream: &mut S) -> io::Result<u8> {
    let mut head = [0u8; 6];
    stream.read_exact(&mut head)?;
    if &head[..4] != MAGIC {
        return Err(err(tr("Ce n'est pas un appareil Nautile")));
    }
    if head[4] != VERSION {
        return Err(err(tr("Version de Nautile incompatible sur l'autre appareil")));
    }
    Ok(head[5])
}

/// Responder side, after [read_mode].
pub fn accept<S: Read + Write>(stream: S, mode: u8, auth: &[u8]) -> io::Result<Channel<S>> {
    accept_with(stream, mode, auth, random_secret())
}

pub fn accept_with<S: Read + Write>(mut stream: S, mode: u8, auth: &[u8], private: [u8; 32]) -> io::Result<Channel<S>> {
    let mut peer = [0u8; 32];
    stream.read_exact(&mut peer)?;
    let secret = StaticSecret::from(private);
    let public = PublicKey::from(&secret).to_bytes();
    stream.write_all(&public)?;
    stream.flush()?;
    let k = keys(&secret, &peer, &public, false, mode, auth)?;
    let mut theirs = [0u8; 32];
    stream.read_exact(&mut theirs)?;
    if !eq(&theirs, &k.proof(b"I")) {
        return Err(auth_failed());
    }
    stream.write_all(&k.proof(b"R"))?;
    stream.flush()?;
    Ok(Channel {
        stream,
        send: cipher(&k.r2i),
        receive: cipher(&k.i2r),
        sent: 0,
        received: 0,
    })
}

/// A 6-digit pairing code.
pub fn new_code() -> String {
    (100_000 + rand::random::<u32>() % 900_000).to_string()
}

pub fn new_secret() -> [u8; 32] {
    random_secret()
}
