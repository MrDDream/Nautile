//! The app's own PIN or password, as on Android (core/security/.../LockSecret.kt): only a salted
//! PBKDF2-HMAC-SHA256 hash is kept (`pbkdf2-sha256:iterations:salt:hash`, base64), never the code itself.

use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use rand::RngCore;
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
    rand::thread_rng().fill_bytes(&mut salt);
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

#[cfg(test)]
mod tests {
    //! The Android LockSecretTest.
    use super::*;

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
