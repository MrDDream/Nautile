//! Files only this computer opens, as Android encrypts its session logs and AI conversations: AES-256-GCM with
//! keys derived (HKDF-SHA256, one `info` per use) from the database key, which never leaves the keyring or
//! `db.key`. A blob is `nonce (12 bytes) ‖ ciphertext ‖ tag`.

use std::sync::Mutex;

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use hmac::{Hmac, Mac};
use rand::Rng;
use sha2::Sha256;

/// Session recordings (`data/logs`).
pub const LOGS: &str = "nautile/session-logs/v1";
/// AI conversations (`data/ai-conversations`).
pub const CONVERSATIONS: &str = "nautile/ai-conversations/v1";

const SALT: &[u8] = b"nautile-desktop-files";

static DATABASE_KEY: Mutex<Option<[u8; 32]>> = Mutex::new(None);

/// Remembers the database key once it is read (at start).
pub fn set_database_key(key: &[u8; 32]) {
    if let Ok(mut k) = DATABASE_KEY.lock() {
        *k = Some(*key);
    }
}

/// The key for [info], or None while there is no database key (nothing is then written in clear).
pub fn key(info: &str) -> Option<[u8; 32]> {
    let db = (*DATABASE_KEY.lock().ok()?)?;
    Some(hkdf_sha256(SALT, &db, info.as_bytes()))
}

/// RFC 5869 with SHA-256, 32 bytes of output.
pub fn hkdf_sha256(salt: &[u8], ikm: &[u8], info: &[u8]) -> [u8; 32] {
    let mut extract = <Hmac<Sha256> as KeyInit>::new_from_slice(salt).expect("any key size");
    extract.update(ikm);
    let prk = extract.finalize().into_bytes();
    let mut expand = <Hmac<Sha256> as KeyInit>::new_from_slice(&prk).expect("any key size");
    expand.update(info);
    expand.update(&[1]);
    expand.finalize().into_bytes().into()
}

pub fn seal(key: &[u8; 32], plain: &[u8]) -> Vec<u8> {
    let mut nonce = [0u8; 12];
    rand::rng().fill_bytes(&mut nonce);
    let sealed = Aes256Gcm::new(key.into())
        .encrypt(&Nonce::from(nonce), plain)
        .expect("AES-GCM encryption");
    [nonce.as_slice(), &sealed].concat()
}

/// The plain bytes of [blob], None when it was not sealed with [key] or was altered.
pub fn open(key: &[u8; 32], blob: &[u8]) -> Option<Vec<u8>> {
    if blob.len() < 12 + 16 {
        return None;
    }
    let (nonce, sealed) = blob.split_at(12);
    Aes256Gcm::new(key.into()).decrypt(&Nonce::try_from(nonce).ok()?, sealed).ok()
}

/// A whole small file (a conversation): magic then one blob.
pub const FILE_MAGIC: &[u8] = b"NTSF1";

pub fn seal_file(key: &[u8; 32], plain: &[u8]) -> Vec<u8> {
    [FILE_MAGIC, &seal(key, plain)].concat()
}

/// What [bytes] holds: opened when sealed (None when it cannot be), as is when written before encryption.
pub fn open_file(key: &[u8; 32], bytes: &[u8]) -> Option<Vec<u8>> {
    match bytes.strip_prefix(FILE_MAGIC) {
        Some(blob) => open(key, blob),
        None => Some(bytes.to_vec()),
    }
}

/// Once the database key is known (at start, after the master password): what older versions left in clear is
/// sealed. The conversations (small) at once, before the assistant reads or writes them; the session logs (maybe
/// large) in a thread: none of them is written again by the app, a new log being sealed from its first byte.
pub fn seal_old_files(data: &std::path::Path) {
    if let Some(k) = key(CONVERSATIONS) {
        seal_old_conversations(&data.join("ai-conversations"), &k);
    }
    if let Some(k) = key(LOGS) {
        let dir = data.join("logs");
        if dir.is_dir() {
            std::thread::spawn(move || crate::logs::seal_old(&dir, &k));
        }
    }
}

fn seal_old_conversations(dir: &std::path::Path, key: &[u8; 32]) {
    for e in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        let path = e.path();
        if path.extension().is_none_or(|x| x != "json") {
            continue;
        }
        let Ok(bytes) = std::fs::read(&path) else { continue };
        if !bytes.starts_with(FILE_MAGIC)
            && let Err(e) = crate::storage::write_private(&path, &seal_file(key, &bytes))
        {
            eprintln!("{}: not sealed ({e})", path.display());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hkdf_matches_rfc_5869() {
        // RFC 5869, test case 1 (first 32 bytes of the 42).
        let ikm = [0x0b; 22];
        let salt = hex::decode("000102030405060708090a0b0c").unwrap();
        let info = hex::decode("f0f1f2f3f4f5f6f7f8f9").unwrap();
        assert_eq!(
            hex::encode(hkdf_sha256(&salt, &ikm, &info)),
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
        );
    }

    #[test]
    fn sealed_files_open_only_with_their_key() {
        let a = hkdf_sha256(SALT, &[7; 32], LOGS.as_bytes());
        let b = hkdf_sha256(SALT, &[7; 32], CONVERSATIONS.as_bytes());
        assert_ne!(a, b);
        let f = seal_file(&a, b"[{\"text\":\"ls\"}]");
        assert!(!f.windows(2).any(|w| w == b"ls"));
        assert_eq!(open_file(&a, &f).unwrap(), b"[{\"text\":\"ls\"}]");
        assert_eq!(open_file(&b, &f), None);
        let mut altered = f.clone();
        *altered.last_mut().unwrap() ^= 1;
        assert_eq!(open_file(&a, &altered), None);
        // Written before encryption: read as is.
        assert_eq!(open_file(&a, b"[]").unwrap(), b"[]");
    }

    #[test]
    fn older_conversations_are_sealed_in_place() {
        let dir = std::env::temp_dir().join(format!("nautile-sealed-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let k = hkdf_sha256(SALT, &[4; 32], CONVERSATIONS.as_bytes());
        std::fs::write(dir.join("a.json"), b"[{\"text\":\"secret\"}]").unwrap();
        let sealed = seal_file(&k, b"[]");
        std::fs::write(dir.join("b.json"), &sealed).unwrap();
        std::fs::write(dir.join("notes.txt"), b"left alone").unwrap();
        seal_old_conversations(&dir, &k);
        let a = std::fs::read(dir.join("a.json")).unwrap();
        assert!(a.starts_with(FILE_MAGIC) && !a.windows(6).any(|w| w == b"secret"));
        assert_eq!(open_file(&k, &a).unwrap(), b"[{\"text\":\"secret\"}]");
        assert_eq!(std::fs::read(dir.join("b.json")).unwrap(), sealed);
        assert_eq!(std::fs::read(dir.join("notes.txt")).unwrap(), b"left alone");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
