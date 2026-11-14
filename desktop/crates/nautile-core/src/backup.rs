//! The encrypted backup / sync file, byte-compatible with the Android app (core/data/.../backup/BackupCodec.kt):
//!
//! ```text
//! NAUTILE-BACKUP-1
//! {"kdf":"argon2id","m":19456,"t":2,"p":1,"salt":"<b64>","nonce":"<b64>"}
//! <AES-256-GCM(JSON BackupData), header line as associated data, base64 MIME in 76 columns>
//! ```
//! Key = Argon2id v1.3 (password as UTF-8, 16-byte salt, 32-byte output).

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use argon2::{Algorithm, Argon2, Params, Version};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use rand::Rng;
use serde::Deserialize;

use crate::i18n::tr;
use crate::model::BackupData;

pub const MAGIC: &str = "NAUTILE-BACKUP-1";
/// Android's defaults (OWASP's minimum for Argon2id).
pub const DEFAULT_MEMORY_KIB: u32 = 19_456;
pub const DEFAULT_ITERATIONS: u32 = 2;
/// Bounds on the KDF parameters read from a file, checked before anything is authenticated (same as Android's
/// `BackupCodec`): a crafted header must not make every sync derive gigabytes.
pub const MAX_MEMORY_KIB: u32 = 262_144;
pub const MAX_ITERATIONS: u32 = 10;
pub const MAX_PARALLELISM: u32 = 8;

/// Whether a file's Argon2id parameters are within the bounds above (Argon2 needs 8 KiB per lane).
fn parameters_ok(m: u32, t: u32, p: u32) -> bool {
    (1..=MAX_PARALLELISM).contains(&p) && (8 * p..=MAX_MEMORY_KIB).contains(&m) && (1..=MAX_ITERATIONS).contains(&t)
}

#[derive(Debug, thiserror::Error)]
pub enum BackupError {
    #[error("{0}")]
    NotABackup(String),
    #[error("{0}")]
    BadParameters(String),
    #[error("{0}")]
    WrongPassword(String),
    #[error("{0}")]
    Invalid(String),
}

#[derive(Deserialize)]
struct Header {
    kdf: String,
    m: u32,
    t: u32,
    p: u32,
    salt: String,
    nonce: String,
}

fn derive(password: &str, salt: &[u8], m: u32, t: u32, p: u32) -> Result<[u8; 32], BackupError> {
    let params = Params::new(m, t, p, Some(32)).map_err(|_| BackupError::BadParameters(tr("Paramètres de sauvegarde invalides")))?;
    let mut key = [0u8; 32];
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(password.as_bytes(), salt, &mut key)
        .map_err(|_| BackupError::BadParameters(tr("Paramètres de sauvegarde invalides")))?;
    Ok(key)
}

/// Base64 cut in lines of 76 characters, like `Base64.getMimeEncoder(76, "\n")`.
fn mime(bytes: &[u8]) -> String {
    let flat = STANDARD.encode(bytes);
    let mut out = String::with_capacity(flat.len() + flat.len() / 76);
    for (i, chunk) in flat.as_bytes().chunks(76).enumerate() {
        if i > 0 {
            out.push('\n');
        }
        out.push_str(std::str::from_utf8(chunk).expect("base64 is ascii"));
    }
    out
}

/// Encrypts `data` with Android's default Argon2id parameters.
pub fn export(data: &BackupData, password: &str) -> Result<String, BackupError> {
    export_with(data, password, DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS)
}

pub fn export_with(data: &BackupData, password: &str, memory_kib: u32, iterations: u32) -> Result<String, BackupError> {
    let mut salt = [0u8; 16];
    let mut nonce = [0u8; 12];
    rand::rng().fill_bytes(&mut salt);
    rand::rng().fill_bytes(&mut nonce);
    // Same text as kotlinx.serialization writes (field order, no spaces): it is the associated data.
    let header = format!(
        r#"{{"kdf":"argon2id","m":{memory_kib},"t":{iterations},"p":1,"salt":"{}","nonce":"{}"}}"#,
        STANDARD.encode(salt),
        STANDARD.encode(nonce)
    );
    let key = derive(password, &salt, memory_kib, iterations, 1)?;
    let plain = serde_json::to_vec(data).map_err(|e| BackupError::Invalid(e.to_string()))?;
    let sealed = Aes256Gcm::new_from_slice(&key)
        .expect("32-byte key")
        .encrypt(
            &Nonce::from(nonce),
            Payload {
                msg: &plain,
                aad: header.as_bytes(),
            },
        )
        .map_err(|e| BackupError::Invalid(e.to_string()))?;
    Ok(format!("{MAGIC}\n{header}\n{}\n", mime(&sealed)))
}

/// Opens a file written by Android or by the desktop.
pub fn import(text: &str, password: &str) -> Result<BackupData, BackupError> {
    let plain = decrypt(text, password)?;
    serde_json::from_slice(&plain).map_err(|e| BackupError::Invalid(format!("{}: {e}", tr("Ce fichier n'est pas une sauvegarde Nautile"))))
}

/// The decrypted JSON, as written (for tests and diagnostics).
pub fn decrypt(text: &str, password: &str) -> Result<Vec<u8>, BackupError> {
    let lines: Vec<&str> = text.trim().lines().collect();
    if lines.len() < 3 || lines[0].trim() != MAGIC {
        return Err(BackupError::NotABackup(tr("Ce fichier n'est pas une sauvegarde Nautile")));
    }
    let header_line = lines[1].trim();
    let bad = || BackupError::BadParameters(tr("Paramètres de sauvegarde invalides"));
    let h: Header = serde_json::from_str(header_line).map_err(|_| bad())?;
    if h.kdf != "argon2id" {
        return Err(bad());
    }
    if !parameters_ok(h.m, h.t, h.p) {
        return Err(BackupError::BadParameters(tr(
            "Paramètres de chiffrement hors limites (au plus 256 Mio, 10 passes, 8 voies) : fichier altéré ou trop exigeant",
        )));
    }
    // The MIME decoder ignores anything outside the base64 alphabet (line breaks…).
    let body: String = lines[2..]
        .concat()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '/' | '='))
        .collect();
    let sealed = STANDARD
        .decode(body)
        .map_err(|_| BackupError::NotABackup(tr("Ce fichier n'est pas une sauvegarde Nautile")))?;
    let salt = STANDARD.decode(&h.salt).map_err(|_| bad())?;
    let nonce: [u8; 12] = STANDARD.decode(&h.nonce).map_err(|_| bad())?.try_into().map_err(|_| bad())?;
    let key = derive(password, &salt, h.m, h.t, h.p)?;
    Aes256Gcm::new_from_slice(&key)
        .expect("32-byte key")
        .decrypt(
            &Nonce::from(nonce),
            Payload {
                msg: &sealed,
                aad: header_line.as_bytes(),
            },
        )
        .map_err(|_| BackupError::WrongPassword(tr("Mot de passe incorrect ou fichier altéré")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Host;

    #[test]
    fn round_trip_and_wrong_password() {
        let mut data = BackupData::default();
        data.hosts.push(Host::new("nas", "10.0.0.2"));
        data.hosts[0].password = Some("s3cret".into());
        let file = export_with(&data, "correct horse", 64, 1).unwrap();
        assert!(file.starts_with(MAGIC));
        assert!(!file.contains("s3cret") && !file.contains("nas"));
        assert_eq!(import(&file, "correct horse").unwrap(), data);
        assert!(matches!(import(&file, "nope"), Err(BackupError::WrongPassword(_))));
        // The header is authenticated.
        assert!(import(&file.replace("\"t\":1", "\"t\":2"), "correct horse").is_err());
        assert!(matches!(import("hello", "x"), Err(BackupError::NotABackup(_))));
    }

    #[test]
    fn argon2_parameters_from_a_file_are_bounded() {
        assert!(parameters_ok(DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS, 1));
        assert!(parameters_ok(MAX_MEMORY_KIB, MAX_ITERATIONS, MAX_PARALLELISM));
        assert!(!parameters_ok(MAX_MEMORY_KIB + 1, 2, 1), "over 256 MiB");
        assert!(!parameters_ok(1_048_576, 2, 1), "1 GiB");
        assert!(!parameters_ok(19_456, 11, 1));
        assert!(!parameters_ok(19_456, 0, 1));
        assert!(!parameters_ok(19_456, 2, 9));
        assert!(!parameters_ok(19_456, 2, 0));
        assert!(!parameters_ok(16, 2, 4), "less than 8 KiB per lane");
        // A file asking for 1 GiB is refused before any derivation.
        let file = export_with(&BackupData::default(), "pw", 64, 1).unwrap();
        let greedy = file.replace("\"m\":64", "\"m\":1048576");
        assert!(matches!(import(&greedy, "pw"), Err(BackupError::BadParameters(_))));
        let greedy = file.replace("\"t\":1", "\"t\":20");
        assert!(matches!(import(&greedy, "pw"), Err(BackupError::BadParameters(_))));
    }
}
