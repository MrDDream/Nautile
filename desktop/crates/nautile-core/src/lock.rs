//! The app's own PIN or password, as on Android (core/security/.../LockSecret.kt): only a salted
//! PBKDF2-HMAC-SHA256 hash is kept (`pbkdf2-sha256:iterations:salt:hash`, base64), never the code itself.
//! The hash and the count of wrong tries live in the encrypted database (`desktop_secret`), not in settings.json.
//!
//! Also the portable mode's master password: the database key wrapped with Argon2id + AES-256-GCM
//! (see [wrap_key]).

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use rand::Rng;
use sha2::Sha256;

const PREFIX: &str = "pbkdf2-sha256";
pub const ITERATIONS: u32 = 60_000;
/// Wrong codes allowed before a wait.
pub const FREE_TRIES: u32 = 5;
pub const PIN_MIN: usize = 4;
pub const PIN_MAX: usize = 8;
pub const PASSWORD_MIN: usize = 6;

/// How the app is unlocked (the desktop has no fingerprint prompt yet).
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum LockMethod {
    Pin,
    Password,
}

fn derive(secret: &str, salt: &[u8], iterations: u32) -> [u8; 32] {
    let mut out = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(secret.as_bytes(), salt, iterations, &mut out);
    out
}

pub fn hash(secret: &str) -> String {
    hash_with(secret, ITERATIONS)
}

pub fn hash_with(secret: &str, iterations: u32) -> String {
    let mut salt = [0u8; 16];
    rand::rng().fill_bytes(&mut salt);
    format!(
        "{PREFIX}:{iterations}:{}:{}",
        STANDARD.encode(salt),
        STANDARD.encode(derive(secret, &salt, iterations))
    )
}

pub fn verify(secret: &str, stored: &str) -> bool {
    let parts: Vec<&str> = stored.split(':').collect();
    if parts.len() != 4 || parts[0] != PREFIX {
        return false;
    }
    let (Ok(iterations), Ok(salt), Ok(expected)) = (parts[1].parse::<u32>(), STANDARD.decode(parts[2]), STANDARD.decode(parts[3])) else {
        return false;
    };
    let got = derive(secret, &salt, iterations);
    got.len() == expected.len() && got.iter().zip(&expected).fold(0u8, |a, (x, y)| a | (x ^ y)) == 0
}

/// The lock kept in the database: method, hash, wrong tries since the last right one.
const SECRET_METHOD: &str = "app-lock-method";
const SECRET_HASH: &str = "app-lock-hash";
const SECRET_FAILURES: &str = "app-lock-failures";

/// The lock saved in [db], if any.
pub fn stored(db: &crate::db::Db) -> Option<(LockMethod, String)> {
    let hash = db.secret(SECRET_HASH)?;
    let method = match db.secret(SECRET_METHOD).as_deref() {
        Some("pin") => LockMethod::Pin,
        // A password field takes digits too: never a lock that cannot be typed.
        _ => LockMethod::Password,
    };
    Some((method, hash))
}

/// Saves (or with `None` removes) the lock; the wrong tries start again from zero.
pub fn store(db: &crate::db::Db, lock: Option<(LockMethod, &str)>) -> crate::db::Result<()> {
    match lock {
        Some((method, hash)) => {
            let m = match method {
                LockMethod::Pin => "pin",
                LockMethod::Password => "password",
            };
            db.set_secret(SECRET_METHOD, Some(m))?;
            db.set_secret(SECRET_HASH, Some(hash))?;
        }
        None => {
            db.set_secret(SECRET_HASH, None)?;
            db.set_secret(SECRET_METHOD, None)?;
        }
    }
    set_failures(db, 0)
}

pub fn failures(db: &crate::db::Db) -> u32 {
    db.secret(SECRET_FAILURES).and_then(|v| v.parse().ok()).unwrap_or(0)
}

pub fn set_failures(db: &crate::db::Db, n: u32) -> crate::db::Result<()> {
    db.set_secret(SECRET_FAILURES, (n > 0).then(|| n.to_string()).as_deref())
}

/// After 5 wrong tries: wait 30 s, then twice as long each time, up to 15 min.
pub fn lockout_ms(failures: u32) -> u64 {
    if failures < FREE_TRIES {
        return 0;
    }
    (30_000u64 << (failures - FREE_TRIES).min(5)).min(15 * 60_000)
}

pub fn valid_pin(pin: &str) -> bool {
    (PIN_MIN..=PIN_MAX).contains(&pin.chars().count()) && pin.chars().all(|c| c.is_ascii_digit())
}

pub fn valid_password(pw: &str) -> bool {
    pw.chars().count() >= PASSWORD_MIN
}

/// The master password of the portable mode: at least this many characters.
pub const MASTER_MIN: usize = 8;

/// Argon2id cost of the master password (memory in KiB, passes, lanes).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KdfParams {
    pub memory_kib: u32,
    pub iterations: u32,
    pub lanes: u32,
}

/// 64 MiB, 3 passes, 1 lane.
pub const MASTER_KDF: KdfParams = KdfParams {
    memory_kib: 64 * 1024,
    iterations: 3,
    lanes: 1,
};

/// `db.key.enc`: `NKEY`, version 1, Argon2id m/t/p (u32 little-endian), salt (16), nonce (12) — all of it the
/// AES-GCM associated data — then the 32-byte key encrypted with its 16-byte tag.
const WRAP_MAGIC: &[u8; 4] = b"NKEY";
const WRAP_VERSION: u8 = 1;
const WRAP_HEADER: usize = 4 + 1 + 12 + 16 + 12;
pub const WRAPPED_LEN: usize = WRAP_HEADER + 32 + 16;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum UnwrapError {
    /// Not a key file of this version (truncated, other format, parameters out of bounds).
    #[error("fichier de clé illisible")]
    Format,
    /// The password is wrong, or the file was changed: AES-GCM cannot tell one from the other.
    #[error("mot de passe incorrect ou fichier de clé modifié")]
    Rejected,
}

fn master_kek(password: &str, salt: &[u8], p: KdfParams) -> Option<[u8; 32]> {
    use argon2::{Algorithm, Argon2, Params, Version};
    let params = Params::new(p.memory_kib, p.iterations, p.lanes, Some(32)).ok()?;
    let mut out = [0u8; 32];
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(password.as_bytes(), salt, &mut out)
        .ok()?;
    Some(out)
}

/// The database [key] encrypted with [password] (Argon2id at [MASTER_KDF], AES-256-GCM).
pub fn wrap_key(key: &[u8; 32], password: &str) -> Vec<u8> {
    wrap_key_with(key, password, MASTER_KDF)
}

pub fn wrap_key_with(key: &[u8; 32], password: &str, p: KdfParams) -> Vec<u8> {
    use aes_gcm::aead::{Aead, KeyInit, Payload};
    use aes_gcm::{Aes256Gcm, Nonce};
    let mut salt = [0u8; 16];
    let mut nonce = [0u8; 12];
    rand::rng().fill_bytes(&mut salt);
    rand::rng().fill_bytes(&mut nonce);
    let mut out = Vec::with_capacity(WRAPPED_LEN);
    out.extend_from_slice(WRAP_MAGIC);
    out.push(WRAP_VERSION);
    for v in [p.memory_kib, p.iterations, p.lanes] {
        out.extend_from_slice(&v.to_le_bytes());
    }
    out.extend_from_slice(&salt);
    out.extend_from_slice(&nonce);
    let kek = master_kek(password, &salt, p).expect("valid Argon2id parameters");
    let sealed = Aes256Gcm::new_from_slice(&kek)
        .expect("32-byte key")
        .encrypt(&Nonce::from(nonce), Payload { msg: key, aad: &out })
        .expect("AES-GCM encryption");
    out.extend_from_slice(&sealed);
    out
}

/// The database key from a [wrap_key] file.
pub fn unwrap_key(data: &[u8], password: &str) -> Result<[u8; 32], UnwrapError> {
    use aes_gcm::aead::{Aead, KeyInit, Payload};
    use aes_gcm::{Aes256Gcm, Nonce};
    if data.len() != WRAPPED_LEN || &data[..4] != WRAP_MAGIC || data[4] != WRAP_VERSION {
        return Err(UnwrapError::Format);
    }
    let u32_at = |i: usize| u32::from_le_bytes([data[i], data[i + 1], data[i + 2], data[i + 3]]);
    let p = KdfParams {
        memory_kib: u32_at(5),
        iterations: u32_at(9),
        lanes: u32_at(13),
    };
    // A changed file must not make the computer derive with 4 GiB of memory.
    if !(8..=1024 * 1024).contains(&p.memory_kib) || !(1..=16).contains(&p.iterations) || !(1..=8).contains(&p.lanes) {
        return Err(UnwrapError::Format);
    }
    let (header, sealed) = data.split_at(WRAP_HEADER);
    let salt = &header[17..33];
    let nonce = &header[33..45];
    let kek = master_kek(password, salt, p).ok_or(UnwrapError::Format)?;
    let key = Aes256Gcm::new_from_slice(&kek)
        .expect("32-byte key")
        .decrypt(
            &Nonce::try_from(nonce).map_err(|_| UnwrapError::Format)?,
            Payload { msg: sealed, aad: header },
        )
        .map_err(|_| UnwrapError::Rejected)?;
    <[u8; 32]>::try_from(key.as_slice()).map_err(|_| UnwrapError::Format)
}

/// The wait (in memory) before the next master password try: none for the first 3, then 1 s, 2 s, 4 s… up to
/// 5 min. Tries are never limited: a forgotten password would otherwise lose the data for good.
pub fn master_wait_ms(failures: u32) -> u64 {
    if failures < 3 {
        return 0;
    }
    (1_000u64 << (failures - 3).min(9)).min(5 * 60_000)
}

#[cfg(test)]
mod tests {
    //! The Android LockSecretTest, then the master password.
    use super::*;

    /// Cheap parameters for the tests (the real ones take a second in a debug build).
    const FAST: KdfParams = KdfParams {
        memory_kib: 64,
        iterations: 1,
        lanes: 1,
    };

    #[test]
    fn the_database_key_comes_back_only_with_the_right_password() {
        let key = [7u8; 32];
        let file = wrap_key_with(&key, "correct horse", FAST);
        assert_eq!(file.len(), WRAPPED_LEN);
        assert!(!file.windows(32).any(|w| w == key), "the key is not in the file");
        assert_eq!(unwrap_key(&file, "correct horse"), Ok(key));
        assert_eq!(unwrap_key(&file, "correct horsE"), Err(UnwrapError::Rejected));
        assert_eq!(unwrap_key(&file, ""), Err(UnwrapError::Rejected));
        // Salted: the same key and password never give the same file.
        assert_ne!(wrap_key_with(&key, "correct horse", FAST), file);
    }

    #[test]
    fn a_changed_key_file_is_refused() {
        let file = wrap_key_with(&[1u8; 32], "password1", FAST);
        // Each byte of the header (associated data) and of the sealed key.
        for i in [0, 4, 5, 9, 13, 20, 40, WRAP_HEADER, WRAPPED_LEN - 1] {
            let mut bad = file.clone();
            bad[i] ^= 0x01;
            assert!(unwrap_key(&bad, "password1").is_err(), "byte {i}");
        }
        assert_eq!(unwrap_key(&file[..WRAPPED_LEN - 1], "password1"), Err(UnwrapError::Format));
        // Parameters out of bounds: refused before any derivation.
        let mut huge = file.clone();
        huge[5..9].copy_from_slice(&u32::MAX.to_le_bytes());
        assert_eq!(unwrap_key(&huge, "password1"), Err(UnwrapError::Format));
    }

    #[test]
    fn the_real_parameters_are_the_chosen_ones() {
        let file = wrap_key(&[3u8; 32], "a master password");
        assert_eq!(&file[5..17], &[0, 0, 1, 0, 3, 0, 0, 0, 1, 0, 0, 0]);
        assert_eq!(unwrap_key(&file, "a master password"), Ok([3u8; 32]));
    }

    #[test]
    fn master_password_tries_wait_longer_and_longer() {
        assert_eq!(master_wait_ms(2), 0);
        assert_eq!(master_wait_ms(3), 1_000);
        assert_eq!(master_wait_ms(4), 2_000);
        assert_eq!(master_wait_ms(100), 5 * 60_000);
    }

    #[test]
    fn the_lock_lives_in_the_database() {
        let db = crate::db::Db::in_memory().unwrap();
        assert!(stored(&db).is_none());
        let h = hash_with("2468", 1_000);
        store(&db, Some((LockMethod::Pin, &h))).unwrap();
        assert_eq!(stored(&db), Some((LockMethod::Pin, h.clone())));
        set_failures(&db, 3).unwrap();
        assert_eq!(failures(&db), 3);
        store(&db, Some((LockMethod::Password, &h))).unwrap();
        assert_eq!(failures(&db), 0, "a new code starts again");
        store(&db, None).unwrap();
        assert!(stored(&db).is_none());
    }

    #[test]
    fn verifies_only_the_right_code() {
        let stored = hash_with("482916", 1_000);
        assert!(verify("482916", &stored));
        assert!(!verify("482917", &stored));
        assert!(!verify("", &stored));
        assert!(!stored.contains("482916"));
    }

    #[test]
    fn salted_each_time() {
        assert_ne!(hash_with("1234", 1_000), hash_with("1234", 1_000));
    }

    #[test]
    fn rejects_garbage() {
        assert!(!verify("1234", "nope"));
        assert!(!verify("1234", "pbkdf2-sha256:x:y:z"));
    }

    #[test]
    fn waits_longer_after_repeated_mistakes() {
        assert_eq!(lockout_ms(4), 0);
        assert_eq!(lockout_ms(5), 30_000);
        assert_eq!(lockout_ms(6), 60_000);
        assert_eq!(lockout_ms(30), 15 * 60_000);
    }

    #[test]
    fn pin_and_password_rules() {
        assert!(valid_pin("1234"));
        assert!(!valid_pin("123"));
        assert!(!valid_pin("12a4"));
        assert!(!valid_pin("123456789"));
        assert!(valid_password("abcdef"));
        assert!(!valid_password("abc"));
    }

    #[test]
    fn standard_pbkdf2_as_on_android() {
        // Independent value (Python hashlib.pbkdf2_hmac("sha256", b"1234", 16 zero bytes, 1000)), in LockSecret's format.
        let stored = "pbkdf2-sha256:1000:AAAAAAAAAAAAAAAAAAAAAA==:WF9XYD3HVhc6DsfLiZj6vRAw6dSsZScye2hcHU/yKlw=";
        assert!(verify("1234", stored));
        assert!(!verify("1235", stored));
    }
}
