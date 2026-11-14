//! The Wi-Fi device-to-device protocol, version 2, byte-compatible with the Android app
//! (app/.../backup/lan/LanProtocol.kt and Spake2.kt). `lp(x)` is `x` prefixed with its length as a u64
//! little-endian (as in RFC 9382), ids are the devices' ids (UTF-8, 1 to 64 bytes).
//!
//! Every connection opens with `NLAN` · version 2 · mode (`P` pair, `S` sync) · u8 length · initiator id, and the
//! responder answers `NLAN` · its version · status (0 ok, 1 version refused, 2 no code shown, 3 unknown device,
//! 4 busy); anything but 0 closes the connection.
//!
//! Pairing: SPAKE2-P256-SHA256-HKDF-HMAC (RFC 9382), A = initiator id, B = responder id,
//! `w = SHA-256("nautile-pair-v2" · code) mod n`.
//! 1. I → R: opening · `pA = x·G + w·M` (SEC1 uncompressed, 65 bytes).
//! 2. R → I: status 0 · `pB = y·G + w·N` · `cB = HMAC(KcB, TT)`.
//! 3. I checks `cB`, then I → R: `cA = HMAC(KcA, TT)`; R checks it.
//!
//! `TT = lp(A) lp(B) lp(pA) lp(pB) lp(K) lp(w)`, `Ke ‖ Ka = SHA-256(TT)`,
//! `KcA ‖ KcB = HKDF(salt ∅, Ka, "ConfirmationKeys", 32)`. Then
//! `i2r ‖ r2i ‖ secret = HKDF(salt ∅, Ke, "nautile-lan-v2 pair", 96)`: `secret` is kept for that one device.
//!
//! Synchronisation, with the secret of the device named in the opening:
//! 1. I → R: opening · X25519 public key; R → I: status 0 · its public key · `HMAC(kR, th)`.
//! 2. I checks it, then I → R: `HMAC(kI, th)`; R checks it.
//!
//! `th = SHA-256("nautile-lan-v2 sync" · lp(idI) · lp(idR) · pubI · pubR)`,
//! `i2r ‖ r2i ‖ kI ‖ kR = HKDF(salt secret, X25519 shared, th, 128)`.
//!
//! After either: frames of u32 big-endian length · AES-256-GCM (no associated data), nonce = 4 zero bytes ·
//! u64 big-endian counter per direction.

use std::fmt;
use std::io::{self, Read, Write};

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use hmac::{Hmac, Mac};
use rand::Rng;
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};

use crate::i18n::tr;

pub const VERSION: u8 = 2;
pub const MODE_PAIR: u8 = b'P';
pub const MODE_SYNC: u8 = b'S';
/// mDNS / DNS-SD service type.
pub const SERVICE_TYPE: &str = "_nautile._tcp.local.";
const MAGIC: &[u8; 4] = b"NLAN";
/// The largest frame (a whole `BackupData`).
pub const MAX_FRAME: usize = 64 * 1024 * 1024;
/// The largest small message (a hello).
pub const MAX_HELLO: usize = 64 * 1024;
/// The longest device id.
pub const MAX_ID: usize = 64;

/// The responder's answer to an opening.
pub mod status {
    pub const OK: u8 = 0;
    pub const VERSION: u8 = 1;
    pub const NO_CODE: u8 = 2;
    pub const UNKNOWN: u8 = 3;
    pub const BUSY: u8 = 4;
}

/// Why the other device refused, or could not be trusted.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LanError {
    /// It does not know the code or the pairing secret (or pretends to be someone else).
    AuthFailed,
    /// It runs an older version of Nautile.
    PeerTooOld,
    /// It runs a newer version of Nautile.
    PeerTooNew,
    /// No code is shown there (or it expired after too many tries).
    NoCode,
    /// It does not know this device: never paired, or forgotten.
    Unknown,
    /// Too many connections there right now.
    Busy,
    /// It hung up before answering: unreachable, overloaded, or it gave up (its announced version tells
    /// whether it is an older Nautile).
    Unreachable,
}

impl fmt::Display for LanError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&match self {
            LanError::AuthFailed => tr("Code ou appairage refusé par l'autre appareil"),
            LanError::PeerTooOld => tr("Mettez à jour Nautile sur l'autre appareil"),
            LanError::PeerTooNew => tr("Mettez à jour Nautile sur cet appareil"),
            LanError::NoCode => tr("Aucun code valide sur l'autre appareil : affichez-en un nouveau"),
            LanError::Unknown => tr("L'autre appareil ne reconnaît pas celui-ci : associez-les à nouveau"),
            LanError::Busy => tr("L'autre appareil est occupé, réessayez dans un instant"),
            LanError::Unreachable => tr("L'autre appareil est injoignable ou occupé, réessayez"),
        })
    }
}

impl std::error::Error for LanError {}

impl LanError {
    /// The [LanError] inside an I/O error, if that is what it is.
    pub fn of(e: &io::Error) -> Option<LanError> {
        e.get_ref().and_then(|i| i.downcast_ref::<LanError>()).copied()
    }

    /// As an I/O error (`PermissionDenied` for [LanError::AuthFailed], `ConnectionRefused` otherwise).
    pub fn io(self) -> io::Error {
        let kind = match self {
            LanError::AuthFailed => io::ErrorKind::PermissionDenied,
            _ => io::ErrorKind::ConnectionRefused,
        };
        io::Error::new(kind, self)
    }

    fn from_status(version: u8, status: u8) -> LanError {
        match status {
            status::NO_CODE => LanError::NoCode,
            status::UNKNOWN => LanError::Unknown,
            status::BUSY => LanError::Busy,
            _ if version > VERSION => LanError::PeerTooNew,
            _ => LanError::PeerTooOld,
        }
    }
}

fn err(msg: String) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg)
}

fn invalid() -> io::Error {
    err(tr("Message réseau invalide"))
}

/// The other side does not know the code or the pairing secret.
pub fn auth_failed() -> io::Error {
    LanError::AuthFailed.io()
}

pub fn hmac(key: &[u8], data: &[u8]) -> [u8; 32] {
    let mut m = <Hmac<Sha256> as KeyInit>::new_from_slice(key).expect("any key length");
    m.update(data);
    m.finalize().into_bytes().into()
}

/// HKDF-SHA256 (RFC 5869); an empty `salt` is the default one (32 zero bytes).
pub fn hkdf(salt: &[u8], ikm: &[u8], info: &[u8], len: usize) -> Vec<u8> {
    let prk = hmac(if salt.is_empty() { &[0u8; 32] } else { salt }, ikm);
    let mut out = Vec::with_capacity(len + 32);
    let mut t: Vec<u8> = Vec::new();
    let mut i = 1u8;
    while out.len() < len {
        let mut data = t.clone();
        data.extend_from_slice(info);
        data.push(i);
        t = hmac(&prk, &data).to_vec();
        out.extend_from_slice(&t);
        i += 1;
    }
    out.truncate(len);
    out
}

/// `x` prefixed with its length, a u64 little-endian (RFC 9382).
fn lp(out: &mut Vec<u8>, x: &[u8]) {
    out.extend_from_slice(&(x.len() as u64).to_le_bytes());
    out.extend_from_slice(x);
}

fn sha256(data: &[u8]) -> [u8; 32] {
    Sha256::digest(data).into()
}

/// SPAKE2 over P-256 (RFC 9382, suite SPAKE2-P256-SHA256-HKDF-HMAC), with cofactor 1.
pub mod spake2 {
    use p256::elliptic_curve::group::Group;
    use p256::elliptic_curve::ops::Reduce;
    use p256::elliptic_curve::sec1::{FromSec1Point, ToSec1Point};
    use p256::elliptic_curve::{Field, PrimeField};
    use p256::{FieldBytes, ProjectivePoint, Scalar};
    use rand::Rng;

    use super::{hkdf, hmac, lp, sha256};

    /// RFC 9382's M for P-256: the first valid point from SHA-256 iterated over
    /// "1.2.840.10045.3.1.7 point generation seed (M)" (the RFC's appendix script); checked in the tests.
    pub const M: [u8; 33] = hex33("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f");
    /// RFC 9382's N for P-256, same with "… seed (N)".
    pub const N: [u8; 33] = hex33("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49");
    /// A point on the wire: SEC1 uncompressed.
    pub const POINT_LEN: usize = 65;

    const fn hex33(s: &str) -> [u8; 33] {
        const fn nibble(c: u8) -> u8 {
            match c {
                b'0'..=b'9' => c - b'0',
                b'a'..=b'f' => c - b'a' + 10,
                _ => panic!("hex"),
            }
        }
        let b = s.as_bytes();
        let mut out = [0u8; 33];
        let mut i = 0;
        while i < 33 {
            out[i] = nibble(b[2 * i]) << 4 | nibble(b[2 * i + 1]);
            i += 1;
        }
        out
    }

    fn point(bytes: &[u8]) -> ProjectivePoint {
        ProjectivePoint::from_sec1_bytes(bytes).expect("constant point")
    }

    pub fn m() -> ProjectivePoint {
        point(&M)
    }

    pub fn n() -> ProjectivePoint {
        point(&N)
    }

    /// `w` from the 6-digit code: SHA-256("nautile-pair-v2" · code) reduced mod n.
    pub fn password(code: &str) -> Scalar {
        let mut data = b"nautile-pair-v2".to_vec();
        data.extend_from_slice(code.as_bytes());
        <Scalar as Reduce<FieldBytes>>::reduce(&FieldBytes::from(sha256(&data)))
    }

    /// A scalar in [1, n-1] from its 32 big-endian bytes.
    pub fn scalar(bytes: &[u8; 32]) -> Option<Scalar> {
        Option::<Scalar>::from(Scalar::from_repr(FieldBytes::from(*bytes))).filter(|s| !bool::from(s.is_zero()))
    }

    pub fn scalar_bytes(s: &Scalar) -> [u8; 32] {
        s.to_repr().into()
    }

    /// 32 random bytes that make a valid scalar.
    pub fn random_scalar() -> [u8; 32] {
        loop {
            let mut b = [0u8; 32];
            rand::rng().fill_bytes(&mut b);
            if scalar(&b).is_some() {
                return b;
            }
        }
    }

    pub fn encode(p: &ProjectivePoint) -> [u8; POINT_LEN] {
        let e = p.to_sec1_point(false);
        let mut out = [0u8; POINT_LEN];
        out.copy_from_slice(e.as_bytes());
        out
    }

    /// A point from the other side: uncompressed, on the curve, not the identity.
    pub fn decode(bytes: &[u8]) -> Option<ProjectivePoint> {
        if bytes.len() != POINT_LEN || bytes[0] != 4 {
            return None;
        }
        ProjectivePoint::from_sec1_bytes(bytes)
            .ok()
            .filter(|p| !bool::from(p.is_identity()))
    }

    /// The message to send: `x·G + w·base` (`base` = M for A, N for B).
    pub fn message(x: &Scalar, w: &Scalar, base: &ProjectivePoint) -> ProjectivePoint {
        ProjectivePoint::GENERATOR * x + *base * w
    }

    /// The shared point `x·(peer − w·base)` (`base` = N for A, M for B); None for the identity.
    pub fn shared(x: &Scalar, w: &Scalar, base: &ProjectivePoint, peer: &ProjectivePoint) -> Option<ProjectivePoint> {
        let k = (*peer - *base * w) * x;
        (!bool::from(k.is_identity())).then_some(k)
    }

    pub fn transcript(a: &[u8], b: &[u8], pa: &[u8], pb: &[u8], k: &[u8], w: &Scalar) -> Vec<u8> {
        let mut tt = Vec::with_capacity(256);
        for x in [a, b, pa, pb, k, &scalar_bytes(w)[..]] {
            lp(&mut tt, x);
        }
        tt
    }

    /// The keys from a transcript.
    pub struct Keys {
        pub ke: [u8; 16],
        pub ka: [u8; 16],
        pub kc_a: [u8; 16],
        pub kc_b: [u8; 16],
    }

    impl Keys {
        pub fn new(tt: &[u8]) -> Keys {
            let h = sha256(tt);
            let mut ke = [0u8; 16];
            let mut ka = [0u8; 16];
            ke.copy_from_slice(&h[..16]);
            ka.copy_from_slice(&h[16..]);
            let kc = hkdf(&[], &ka, b"ConfirmationKeys", 32);
            let mut kc_a = [0u8; 16];
            let mut kc_b = [0u8; 16];
            kc_a.copy_from_slice(&kc[..16]);
            kc_b.copy_from_slice(&kc[16..]);
            Keys { ke, ka, kc_a, kc_b }
        }

        /// A's confirmation message.
        pub fn confirm_a(&self, tt: &[u8]) -> [u8; 32] {
            hmac(&self.kc_a, tt)
        }

        /// B's confirmation message.
        pub fn confirm_b(&self, tt: &[u8]) -> [u8; 32] {
            hmac(&self.kc_b, tt)
        }
    }
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
    fn new(stream: S, send: &[u8], receive: &[u8]) -> Channel<S> {
        Channel {
            stream,
            send: Aes256Gcm::new_from_slice(send).expect("32-byte key"),
            receive: Aes256Gcm::new_from_slice(receive).expect("32-byte key"),
            sent: 0,
            received: 0,
        }
    }

    pub fn send(&mut self, data: &[u8]) -> io::Result<()> {
        if data.len() > MAX_FRAME - 16 {
            return Err(invalid());
        }
        let sealed = self
            .send
            .encrypt(&Nonce::from(nonce(self.sent)), data)
            .map_err(|_| err("encrypt".into()))?;
        self.sent += 1;
        let mut frame = (sealed.len() as u32).to_be_bytes().to_vec();
        frame.extend_from_slice(&sealed);
        self.stream.write_all(&frame)?;
        self.stream.flush()
    }

    /// The next frame, at most `max` bytes once decrypted.
    pub fn receive_max(&mut self, max: usize) -> io::Result<Vec<u8>> {
        let mut len = [0u8; 4];
        self.stream.read_exact(&mut len)?;
        let n = u32::from_be_bytes(len) as usize;
        if !(16..=max.min(MAX_FRAME - 16) + 16).contains(&n) {
            return Err(invalid());
        }
        let mut sealed = vec![0u8; n];
        self.stream.read_exact(&mut sealed)?;
        let plain = self
            .receive
            .decrypt(&Nonce::from(nonce(self.received)), sealed.as_slice())
            .map_err(|_| invalid())?;
        self.received += 1;
        Ok(plain)
    }

    pub fn receive(&mut self) -> io::Result<Vec<u8>> {
        self.receive_max(MAX_FRAME)
    }

    pub fn send_text(&mut self, s: &str) -> io::Result<()> {
        self.send(s.as_bytes())
    }

    pub fn receive_text(&mut self) -> io::Result<String> {
        String::from_utf8(self.receive()?).map_err(|_| invalid())
    }

    /// A small text message (a hello).
    pub fn receive_short_text(&mut self) -> io::Result<String> {
        String::from_utf8(self.receive_max(MAX_HELLO)?).map_err(|_| invalid())
    }

    pub fn get_ref(&self) -> &S {
        &self.stream
    }

    pub fn get_mut(&mut self) -> &mut S {
        &mut self.stream
    }

    pub fn into_inner(self) -> S {
        self.stream
    }
}

fn eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn random_secret() -> [u8; 32] {
    let mut b = [0u8; 32];
    rand::rng().fill_bytes(&mut b);
    b
}

fn check_id(id: &str) -> io::Result<()> {
    if id.is_empty() || id.len() > MAX_ID || id.chars().any(char::is_control) {
        return Err(invalid());
    }
    Ok(())
}

fn write_opening<S: Write>(stream: &mut S, mode: u8, me: &str, rest: &[u8]) -> io::Result<()> {
    check_id(me)?;
    let mut b = MAGIC.to_vec();
    b.extend_from_slice(&[VERSION, mode, me.len() as u8]);
    b.extend_from_slice(me.as_bytes());
    b.extend_from_slice(rest);
    stream.write_all(&b)?;
    stream.flush()
}

/// Reads the responder's header; anything but [status::OK] is the matching [LanError].
fn read_reply<S: Read>(stream: &mut S) -> io::Result<()> {
    let mut head = [0u8; 6];
    // Hanging up without an answer: an older Nautile does (its mDNS version, checked before connecting, says so),
    // but so does one that is overloaded or stopping.
    stream.read_exact(&mut head).map_err(|e| match e.kind() {
        io::ErrorKind::UnexpectedEof | io::ErrorKind::ConnectionReset | io::ErrorKind::ConnectionAborted => LanError::Unreachable.io(),
        _ => e,
    })?;
    if &head[..4] != MAGIC {
        return Err(err(tr("Ce n'est pas un appareil Nautile")));
    }
    if head[5] != status::OK || head[4] != VERSION {
        return Err(LanError::from_status(head[4], head[5]).io());
    }
    Ok(())
}

/// Answers an opening with `status` (anything but OK, then the connection is closed).
pub fn refuse<S: Write>(stream: &mut S, status: u8) -> io::Result<()> {
    let mut b = MAGIC.to_vec();
    b.extend_from_slice(&[VERSION, status]);
    stream.write_all(&b)?;
    stream.flush()
}

fn accept_header(mode_extra: &[u8]) -> Vec<u8> {
    let mut b = MAGIC.to_vec();
    b.extend_from_slice(&[VERSION, status::OK]);
    b.extend_from_slice(mode_extra);
    b
}

/// What an incoming connection asks for and who it says it is (authenticated only by what follows).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Hello {
    pub mode: u8,
    pub id: String,
}

/// Reads an opening. Another version of the protocol is answered with [status::VERSION] and comes back as
/// [LanError::PeerTooOld] or [LanError::PeerTooNew].
pub fn read_hello<S: Read + Write>(stream: &mut S) -> io::Result<Hello> {
    let mut head = [0u8; 7];
    stream.read_exact(&mut head[..6])?;
    if &head[..4] != MAGIC {
        return Err(err(tr("Ce n'est pas un appareil Nautile")));
    }
    if head[4] != VERSION {
        let _ = refuse(stream, status::VERSION);
        return Err(if head[4] < VERSION {
            LanError::PeerTooOld
        } else {
            LanError::PeerTooNew
        }
        .io());
    }
    let mode = head[5];
    if mode != MODE_PAIR && mode != MODE_SYNC {
        return Err(invalid());
    }
    stream.read_exact(&mut head[6..7])?;
    let mut id = vec![0u8; head[6] as usize];
    stream.read_exact(&mut id)?;
    let id = String::from_utf8(id).map_err(|_| invalid())?;
    check_id(&id)?;
    Ok(Hello { mode, id })
}

/// A finished pairing: the channel for the hellos, and the secret kept for that device.
pub struct Paired<S> {
    pub channel: Channel<S>,
    pub secret: [u8; 32],
}

fn paired<S: Read + Write>(stream: S, ke: &[u8], initiator: bool) -> Paired<S> {
    let okm = hkdf(&[], ke, b"nautile-lan-v2 pair", 96);
    let (i2r, r2i) = (&okm[..32], &okm[32..64]);
    let channel = if initiator {
        Channel::new(stream, i2r, r2i)
    } else {
        Channel::new(stream, r2i, i2r)
    };
    let mut secret = [0u8; 32];
    secret.copy_from_slice(&okm[64..]);
    Paired { channel, secret }
}

/// Pairs, as the initiator, with device `peer` which shows `code`.
pub fn pair_connect<S: Read + Write>(stream: S, me: &str, peer: &str, code: &str) -> io::Result<Paired<S>> {
    pair_connect_with(stream, me, peer, code, spake2::random_scalar())
}

/// [pair_connect] with a given SPAKE2 scalar (the compatibility tests replay Android's recordings).
pub fn pair_connect_with<S: Read + Write>(mut stream: S, me: &str, peer: &str, code: &str, x: [u8; 32]) -> io::Result<Paired<S>> {
    let x = spake2::scalar(&x).ok_or_else(invalid)?;
    let w = spake2::password(code.trim());
    let pa = spake2::encode(&spake2::message(&x, &w, &spake2::m()));
    write_opening(&mut stream, MODE_PAIR, me, &pa)?;
    read_reply(&mut stream)?;
    let mut pb = [0u8; spake2::POINT_LEN];
    let mut cb = [0u8; 32];
    stream.read_exact(&mut pb)?;
    stream.read_exact(&mut cb)?;
    let pb_point = spake2::decode(&pb).ok_or_else(invalid)?;
    let k = spake2::shared(&x, &w, &spake2::n(), &pb_point).ok_or_else(invalid)?;
    let tt = spake2::transcript(me.as_bytes(), peer.as_bytes(), &pa, &pb, &spake2::encode(&k), &w);
    let keys = spake2::Keys::new(&tt);
    // Nothing else leaves before the responder proved it knows the code.
    if !eq(&cb, &keys.confirm_b(&tt)) {
        return Err(auth_failed());
    }
    stream.write_all(&keys.confirm_a(&tt))?;
    stream.flush()?;
    Ok(paired(stream, &keys.ke, true))
}

/// Pairs, as the responder showing `code`, after [read_hello].
pub fn pair_accept<S: Read + Write>(stream: S, hello: &Hello, me: &str, code: &str) -> io::Result<Paired<S>> {
    pair_accept_with(stream, hello, me, code, spake2::random_scalar())
}

pub fn pair_accept_with<S: Read + Write>(mut stream: S, hello: &Hello, me: &str, code: &str, y: [u8; 32]) -> io::Result<Paired<S>> {
    let y = spake2::scalar(&y).ok_or_else(invalid)?;
    let mut pa = [0u8; spake2::POINT_LEN];
    stream.read_exact(&mut pa)?;
    let pa_point = spake2::decode(&pa).ok_or_else(invalid)?;
    let w = spake2::password(code.trim());
    let pb = spake2::encode(&spake2::message(&y, &w, &spake2::n()));
    let k = spake2::shared(&y, &w, &spake2::m(), &pa_point).ok_or_else(invalid)?;
    let tt = spake2::transcript(hello.id.as_bytes(), me.as_bytes(), &pa, &pb, &spake2::encode(&k), &w);
    let keys = spake2::Keys::new(&tt);
    let mut reply = accept_header(&pb);
    reply.extend_from_slice(&keys.confirm_b(&tt));
    stream.write_all(&reply)?;
    stream.flush()?;
    // A wrong code: the initiator hangs up instead of answering.
    let mut ca = [0u8; 32];
    stream.read_exact(&mut ca).map_err(|_| auth_failed())?;
    if !eq(&ca, &keys.confirm_a(&tt)) {
        return Err(auth_failed());
    }
    Ok(paired(stream, &keys.ke, false))
}

struct SyncKeys {
    i2r: Vec<u8>,
    r2i: Vec<u8>,
    proof_i: [u8; 32],
    proof_r: [u8; 32],
}

#[allow(clippy::too_many_arguments)]
fn sync_keys(
    private: &StaticSecret,
    peer: &[u8; 32],
    id_i: &str,
    id_r: &str,
    pub_i: &[u8; 32],
    pub_r: &[u8; 32],
    secret: &[u8],
) -> io::Result<SyncKeys> {
    if secret.is_empty() {
        return Err(auth_failed());
    }
    let shared = private.diffie_hellman(&PublicKey::from(*peer));
    if !shared.was_contributory() {
        return Err(err(tr("Clé réseau invalide")));
    }
    let mut t = b"nautile-lan-v2 sync".to_vec();
    lp(&mut t, id_i.as_bytes());
    lp(&mut t, id_r.as_bytes());
    t.extend_from_slice(pub_i);
    t.extend_from_slice(pub_r);
    let th = sha256(&t);
    let okm = hkdf(secret, shared.as_bytes(), &th, 128);
    Ok(SyncKeys {
        i2r: okm[..32].to_vec(),
        r2i: okm[32..64].to_vec(),
        proof_i: hmac(&okm[64..96], &th),
        proof_r: hmac(&okm[96..], &th),
    })
}

/// Opens a synchronisation, as the initiator, with device `peer` and the secret shared with it.
pub fn sync_connect<S: Read + Write>(stream: S, me: &str, peer: &str, secret: &[u8]) -> io::Result<Channel<S>> {
    sync_connect_with(stream, me, peer, secret, random_secret())
}

/// [sync_connect] with a given X25519 private key (the compatibility tests).
pub fn sync_connect_with<S: Read + Write>(mut stream: S, me: &str, peer: &str, secret: &[u8], private: [u8; 32]) -> io::Result<Channel<S>> {
    let private = StaticSecret::from(private);
    let pub_i = PublicKey::from(&private).to_bytes();
    write_opening(&mut stream, MODE_SYNC, me, &pub_i)?;
    read_reply(&mut stream)?;
    let mut pub_r = [0u8; 32];
    let mut proof = [0u8; 32];
    stream.read_exact(&mut pub_r)?;
    stream.read_exact(&mut proof)?;
    let k = sync_keys(&private, &pub_r, me, peer, &pub_i, &pub_r, secret)?;
    if !eq(&proof, &k.proof_r) {
        return Err(auth_failed());
    }
    stream.write_all(&k.proof_i)?;
    stream.flush()?;
    Ok(Channel::new(stream, &k.i2r, &k.r2i))
}

/// Accepts a synchronisation, after [read_hello], with the secret of the device it named.
pub fn sync_accept<S: Read + Write>(stream: S, hello: &Hello, me: &str, secret: &[u8]) -> io::Result<Channel<S>> {
    sync_accept_with(stream, hello, me, secret, random_secret())
}

pub fn sync_accept_with<S: Read + Write>(
    mut stream: S,
    hello: &Hello,
    me: &str,
    secret: &[u8],
    private: [u8; 32],
) -> io::Result<Channel<S>> {
    let mut pub_i = [0u8; 32];
    stream.read_exact(&mut pub_i)?;
    let private = StaticSecret::from(private);
    let pub_r = PublicKey::from(&private).to_bytes();
    let k = sync_keys(&private, &pub_i, &hello.id, me, &pub_i, &pub_r, secret)?;
    let mut reply = accept_header(&pub_r);
    reply.extend_from_slice(&k.proof_r);
    stream.write_all(&reply)?;
    stream.flush()?;
    let mut proof = [0u8; 32];
    stream.read_exact(&mut proof).map_err(|_| auth_failed())?;
    if !eq(&proof, &k.proof_i) {
        return Err(auth_failed());
    }
    Ok(Channel::new(stream, &k.r2i, &k.i2r))
}

/// A 6-digit pairing code.
pub fn new_code() -> String {
    (100_000 + rand::random::<u32>() % 900_000).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ProjectivePoint;
    use p256::elliptic_curve::sec1::FromSec1Point;

    fn h(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }

    fn h32(s: &str) -> [u8; 32] {
        h(s).try_into().unwrap()
    }

    fn hex(b: &[u8]) -> String {
        b.iter().map(|x| format!("{x:02x}")).collect()
    }

    /// RFC 9382 appendix A: M and N come from SHA-256 iterated over a seed, the first candidate on the curve.
    #[test]
    fn m_and_n_are_the_rfc_points() {
        for (seed, expected) in [
            (&b"1.2.840.10045.3.1.7 point generation seed (M)"[..], spake2::M),
            (&b"1.2.840.10045.3.1.7 point generation seed (N)"[..], spake2::N),
        ] {
            let mut found = None;
            for i in 1..1000 {
                let mut hash = seed.to_vec();
                for _ in 0..i {
                    hash = sha256(&hash).to_vec();
                }
                let mut next = hash.clone();
                next = sha256(&next).to_vec();
                let mut cand = hash;
                cand.extend_from_slice(&next);
                cand.truncate(33);
                cand[0] = (cand[0] & 1) | 2;
                if ProjectivePoint::from_sec1_bytes(&cand).is_ok() {
                    found = Some(cand);
                    break;
                }
            }
            assert_eq!(found.unwrap(), expected.to_vec());
        }
    }

    /// RFC 9382 appendix B, the first P-256 vector (A = "server", B = "client").
    #[test]
    fn rfc9382_test_vector() {
        let w = spake2::scalar(&h32("2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f")).unwrap();
        let x = spake2::scalar(&h32("43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729")).unwrap();
        let y = spake2::scalar(&h32("dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be")).unwrap();
        let pa = spake2::encode(&spake2::message(&x, &w, &spake2::m()));
        let pb = spake2::encode(&spake2::message(&y, &w, &spake2::n()));
        assert_eq!(
            hex(&pa),
            "04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c"
        );
        assert_eq!(
            hex(&pb),
            "0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7"
        );
        let ka = spake2::shared(&x, &w, &spake2::n(), &spake2::decode(&pb).unwrap()).unwrap();
        let kb = spake2::shared(&y, &w, &spake2::m(), &spake2::decode(&pa).unwrap()).unwrap();
        let k = spake2::encode(&ka);
        assert_eq!(k, spake2::encode(&kb));
        assert_eq!(
            hex(&k),
            "0412af7e89717850671913e6b469ace67bd90a4df8ce45c2af19010175e37eed69f75897996d539356e2fa6a406d528501f907e04d97515fbe83db277b715d3325"
        );
        let tt = spake2::transcript(b"server", b"client", &pa, &pb, &k, &w);
        assert_eq!(
            hex(&tt),
            "06000000000000007365727665720600000000000000636c69656e74410000000000000004a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c41000000000000000406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b741000000000000000412af7e89717850671913e6b469ace67bd90a4df8ce45c2af19010175e37eed69f75897996d539356e2fa6a406d528501f907e04d97515fbe83db277b715d332520000000000000002ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f"
        );
        let keys = spake2::Keys::new(&tt);
        assert_eq!(hex(&keys.ke), "0e0672dc86f8e45565d338b0540abe69");
        assert_eq!(hex(&keys.ka), "15bdf72e2b35b5c9e5663168e960a91b");
        assert_eq!(hex(&keys.kc_a), "00c12546835755c86d8c0db7851ae86f");
        assert_eq!(hex(&keys.kc_b), "a9fa3406c3b781b93d804485430ca27a");
        assert_eq!(
            hex(&keys.confirm_a(&tt)),
            "58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0"
        );
        assert_eq!(
            hex(&keys.confirm_b(&tt)),
            "d3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b"
        );
    }

    /// RFC 5869, test case 1.
    #[test]
    fn hkdf_matches_rfc5869() {
        let okm = hkdf(&h("000102030405060708090a0b0c"), &[0x0b; 22], &h("f0f1f2f3f4f5f6f7f8f9"), 42);
        assert_eq!(
            hex(&okm),
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        );
    }

    #[test]
    fn bad_points_are_refused() {
        // The identity, a compressed point, a point off the curve, a coordinate past p.
        assert!(spake2::decode(&[0]).is_none());
        assert!(spake2::decode(&spake2::M).is_none());
        let mut off = spake2::encode(&spake2::m());
        off[64] ^= 1;
        assert!(spake2::decode(&off).is_none());
        let mut big = [0xffu8; 65];
        big[0] = 4;
        assert!(spake2::decode(&big).is_none());
        assert!(spake2::decode(&spake2::encode(&spake2::m())).is_some());
        // A peer point that cancels out: K would be the identity.
        let w = spake2::password("123456");
        let x = spake2::scalar(&[7u8; 32]).unwrap();
        assert!(spake2::shared(&x, &w, &spake2::m(), &(spake2::m() * w)).is_none());
        // Zero and n are not scalars.
        assert!(spake2::scalar(&[0u8; 32]).is_none());
        assert!(spake2::scalar(&h32("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")).is_none());
    }

    /// The same bytes in Kotlin (Spake2Test.passwordScalarIsStable).
    #[test]
    fn password_scalar_is_stable() {
        assert_eq!(
            hex(&spake2::scalar_bytes(&spake2::password("482916"))),
            hex(&spake2::scalar_bytes(&spake2::password(" 482916 ".trim())))
        );
        assert_eq!(hex(&spake2::scalar_bytes(&spake2::password("482916"))), PASSWORD_482916);
    }

    const PASSWORD_482916: &str = "7d687220bd43d3231991ab50ddd566c0a137de3832c9c0854f502d0b5baab37d";
}
