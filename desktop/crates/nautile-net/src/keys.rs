//! SSH keys like Android's `SshKeys.kt`: import (OpenSSH, PEM, PKCS#8, PuTTY .ppk), generation and re-encoding
//! in OpenSSH format, with the same fields as the app (`keyType`, `description`, `publicKey`, `fingerprint`).

use std::fmt;

use base64::Engine;
use nautile_core::{tr, trf};
use russh::keys::ssh_key::LineEnding;
use russh::keys::ssh_key::private::{KeypairData, RsaKeypair};
use russh::keys::{Algorithm, EcdsaCurve, PrivateKey, PublicKey};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyAlgorithm {
    Ed25519,
    Rsa3072,
    Rsa4096,
    Ecdsa256,
    Ecdsa384,
    Ecdsa521,
}

impl KeyAlgorithm {
    pub const ALL: [KeyAlgorithm; 6] = [
        KeyAlgorithm::Ed25519,
        KeyAlgorithm::Rsa3072,
        KeyAlgorithm::Rsa4096,
        KeyAlgorithm::Ecdsa256,
        KeyAlgorithm::Ecdsa384,
        KeyAlgorithm::Ecdsa521,
    ];

    pub fn label(self) -> &'static str {
        match self {
            KeyAlgorithm::Ed25519 => "Ed25519",
            KeyAlgorithm::Rsa3072 => "RSA 3072",
            KeyAlgorithm::Rsa4096 => "RSA 4096",
            KeyAlgorithm::Ecdsa256 => "ECDSA P-256",
            KeyAlgorithm::Ecdsa384 => "ECDSA P-384",
            KeyAlgorithm::Ecdsa521 => "ECDSA P-521",
        }
    }
}

impl fmt::Display for KeyAlgorithm {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// A key as the app stores it.
#[derive(Clone, PartialEq, Eq)]
pub struct KeyMaterial {
    /// The text as imported or generated.
    pub private_key: String,
    /// `type base64 comment`, ready for authorized_keys.
    pub public_key: String,
    pub key_type: String,
    /// `ED25519`, `RSA 4096`, `ECDSA 256`…
    pub description: String,
    pub fingerprint: String,
    /// The private text itself is protected by a passphrase.
    pub encrypted: bool,
}

/// Without the private text, so that a `{:?}` never prints it.
impl std::fmt::Debug for KeyMaterial {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("KeyMaterial")
            .field("private_key", &"***")
            .field("public_key", &self.public_key)
            .field("key_type", &self.key_type)
            .field("description", &self.description)
            .field("fingerprint", &self.fingerprint)
            .field("encrypted", &self.encrypted)
            .finish()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum KeyError {
    PassphraseRequired,
    WrongPassphrase,
    Invalid(String),
}

impl fmt::Display for KeyError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&match self {
            KeyError::PassphraseRequired => tr("Phrase de passe requise"),
            KeyError::WrongPassphrase => tr("Phrase de passe incorrecte"),
            KeyError::Invalid(e) => trf("Clé illisible : %1$s", &[e]),
        })
    }
}

impl std::error::Error for KeyError {}

/// Whether the text needs a passphrase (without trying one).
fn looks_encrypted(text: &str) -> bool {
    let t = text.trim();
    if t.starts_with("PuTTY-User-Key-File-") {
        return t.lines().any(|l| l.starts_with("Encryption:") && !l.contains("none"));
    }
    if t.contains("ENCRYPTED") {
        return true;
    }
    matches!(russh::keys::decode_secret_key(t, None), Err(russh::keys::Error::KeyIsEncrypted))
}

/// A key file larger than this is not read (a key is a few kilobytes).
pub const MAX_FILE: u64 = 1 << 20;

/// PuTTY's Argon2 parameters accepted: at most 256 MiB and 8 lanes, and no more work than 256 MiB × 10 passes
/// (PuTTY picks the passes to take about 100 ms, so a key made on a fast computer may have more than 10 at 8 MiB).
const PPK_MAX_MEMORY_KIB: u64 = 262_144;
const PPK_MAX_PARALLELISM: u64 = 8;
const PPK_MAX_WORK: u64 = PPK_MAX_MEMORY_KIB * 10;
/// bcrypt rounds of an OpenSSH key accepted (`ssh-keygen` uses 16 unless told otherwise).
const OPENSSH_MAX_ROUNDS: u32 = 1024;
/// PBES2 parameters of a PKCS#8 key (`ENCRYPTED PRIVATE KEY`) accepted: OpenSSL writes 2048 PBKDF2 iterations
/// (OpenSSL 3: 2048, `-iter` for more) or scrypt with N = 2^14, r = 8, p = 1; scrypt's memory is 128 × N × r bytes.
const PKCS8_MAX_PBKDF2_ITERATIONS: u32 = 10_000_000;
const PKCS8_MAX_SCRYPT_COST: u64 = 1 << 20;
const PKCS8_MAX_SCRYPT_BLOCK_SIZE: u16 = 32;
const PKCS8_MAX_SCRYPT_PARALLELISM: u16 = 16;
const PKCS8_MAX_SCRYPT_MEMORY: u64 = 1 << 30;

fn out_of_bounds() -> KeyError {
    KeyError::Invalid(tr(
        "Paramètres de chiffrement de la clé hors limites : fichier altéré ou trop exigeant",
    ))
}

/// Before any decryption: a file asking for an absurd amount of memory or time (a crafted file) is refused.
/// The parameters are read the way ssh-key and russh will read them, so that no other spelling of the file slips by.
fn check_kdf(t: &str) -> Result<(), KeyError> {
    if t.len() as u64 > MAX_FILE {
        return Err(KeyError::Invalid(tr("Fichier trop volumineux pour une clé (1 Mio au plus)")));
    }
    if t.starts_with("PuTTY-User-Key-File-") {
        let values = ppk_values(t)?;
        let field = |name: &str| values.get(name).map(|v| v.parse::<u64>().map_err(|_| out_of_bounds())).transpose();
        let memory = field("Argon2-Memory")?.unwrap_or(0);
        let passes = field("Argon2-Passes")?.unwrap_or(0);
        let lanes = field("Argon2-Parallelism")?.unwrap_or(0);
        if memory > PPK_MAX_MEMORY_KIB || lanes > PPK_MAX_PARALLELISM || memory.saturating_mul(passes) > PPK_MAX_WORK {
            return Err(out_of_bounds());
        }
        return Ok(());
    }
    if let Some(rounds) = openssh_rounds(t)?
        && rounds > OPENSSH_MAX_ROUNDS
    {
        return Err(out_of_bounds());
    }
    check_pkcs8_kdf(t)
}

/// The `Key: value` lines of a PuTTY key, read as ssh-key reads them (`: ` separator, the lines of a
/// `*-Lines` block skipped). A key given twice is refused: PuTTY never writes one, and ssh-key keeps the last.
fn ppk_values(t: &str) -> Result<std::collections::BTreeMap<&str, &str>, KeyError> {
    let mut values = std::collections::BTreeMap::new();
    let mut lines = t.lines().skip(1);
    while let Some(line) = lines.next() {
        // ssh-key stops on such a line, before deriving anything.
        let Some((key, value)) = line.split_once(": ") else { break };
        if key.ends_with("-Lines") {
            let Ok(n) = value.parse::<usize>() else { break };
            for _ in 0..n {
                lines.next();
            }
        } else if values.insert(key, value).is_some() {
            return Err(out_of_bounds());
        }
    }
    Ok(values)
}

/// The format russh reads a PEM text in.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RusshFormat {
    Openssh,
    /// `PRIVATE KEY`, `ENCRYPTED PRIVATE KEY` or `EC PRIVATE KEY`: russh decrypts the body as PKCS#8 when given a
    /// passphrase, whatever the header says.
    Pkcs8,
    Other,
}

/// The base64 body russh decodes and the format it decodes it in, gathered with its own rules
/// (`decode_secret_key`: the header anywhere, the lines made only of base64 characters, the last header naming
/// the format), or None when there is no header russh knows.
fn russh_body(t: &str) -> Option<(RusshFormat, String)> {
    let base64_line = |l: &str| l.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '+' | '='));
    let (mut started, mut format, mut body) = (false, None, String::new());
    for l in t.lines() {
        if started {
            if l.starts_with("-----END ") {
                break;
            }
            if base64_line(l) {
                body.push_str(l);
            } else if l.starts_with("DEK-Info: AES-128-CBC,") {
                format = Some(RusshFormat::Other);
            }
        }
        match l {
            "-----BEGIN OPENSSH PRIVATE KEY-----" => (started, format) = (true, Some(RusshFormat::Openssh)),
            "-----BEGIN RSA PRIVATE KEY-----" => (started, format) = (true, Some(RusshFormat::Other)),
            "-----BEGIN ENCRYPTED PRIVATE KEY-----" | "-----BEGIN PRIVATE KEY-----" | "-----BEGIN EC PRIVATE KEY-----" => {
                (started, format) = (true, Some(RusshFormat::Pkcs8))
            }
            _ => {}
        }
    }
    format.map(|f| (f, body))
}

/// The bytes of a body gathered by [russh_body].
fn russh_body_bytes(body: &str) -> Result<Vec<u8>, KeyError> {
    use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
    // At least as lenient as russh's decoder: whatever it accepts gives the same bytes here.
    let lenient = GeneralPurpose::new(
        &base64::alphabet::STANDARD,
        GeneralPurposeConfig::new()
            .with_decode_allow_trailing_bits(true)
            .with_decode_padding_mode(DecodePaddingMode::Indifferent),
    );
    lenient.decode(body).map_err(|e| KeyError::Invalid(e.to_string()))
}

/// The PBES2 parameters of a PKCS#8 key russh would decrypt, read with the same pkcs8 crate as russh: a body that
/// is no `EncryptedPrivateKeyInfo` is left to russh (it derives nothing from it), PBES1 is never decrypted.
fn check_pkcs8_kdf(t: &str) -> Result<(), KeyError> {
    use pkcs8::pkcs5::{EncryptionScheme, pbes2::Kdf};
    let Some((RusshFormat::Pkcs8, body)) = russh_body(t) else {
        return Ok(());
    };
    let bin = russh_body_bytes(&body)?;
    let Ok(info) = pkcs8::EncryptedPrivateKeyInfoRef::try_from(bin.as_slice()) else {
        return Ok(());
    };
    let EncryptionScheme::Pbes2(params) = &info.encryption_algorithm else {
        return Ok(());
    };
    let within = match &params.kdf {
        Kdf::Pbkdf2(p) => p.iteration_count <= PKCS8_MAX_PBKDF2_ITERATIONS,
        Kdf::Scrypt(p) => {
            let (n, r) = (p.cost_parameter, p.block_size);
            n <= PKCS8_MAX_SCRYPT_COST
                && r <= PKCS8_MAX_SCRYPT_BLOCK_SIZE
                && p.parallelization <= PKCS8_MAX_SCRYPT_PARALLELISM
                && 128 * n * u64::from(r) <= PKCS8_MAX_SCRYPT_MEMORY
        }
        _ => false,
    };
    if within { Ok(()) } else { Err(out_of_bounds()) }
}

/// The bcrypt rounds of an `openssh-key-v1` key protected by a passphrase; a body russh would try to read but
/// that cannot be analysed here is refused.
fn openssh_rounds(t: &str) -> Result<Option<u32>, KeyError> {
    let Some((RusshFormat::Openssh, body)) = russh_body(t) else {
        return Ok(None);
    };
    let bin = russh_body_bytes(&body)?;
    let key = PrivateKey::from_bytes(&bin).map_err(|e| KeyError::Invalid(e.to_string()))?;
    Ok(match key.kdf() {
        russh::keys::ssh_key::Kdf::Bcrypt { rounds, .. } => Some(*rounds),
        _ => None,
    })
}

/// The private key, decrypted with [passphrase] when it is protected.
pub fn decode(text: &str, passphrase: Option<&str>) -> Result<PrivateKey, KeyError> {
    let t = text.trim();
    check_kdf(t)?;
    if let Some(r) = crate::pem::decode(t, passphrase) {
        return r;
    }
    let encrypted = looks_encrypted(t);
    let pass = passphrase.filter(|p| !p.is_empty());
    if encrypted && pass.is_none() {
        return Err(KeyError::PassphraseRequired);
    }
    match russh::keys::decode_secret_key(t, if encrypted { pass } else { None }) {
        Ok(k) => Ok(k),
        Err(_) if encrypted => Err(KeyError::WrongPassphrase),
        Err(e) => Err(KeyError::Invalid(e.to_string())),
    }
}

/// Validates [text] and derives its public half.
pub fn parse(text: &str, passphrase: Option<&str>, comment: &str) -> Result<KeyMaterial, KeyError> {
    let key = decode(text, passphrase)?;
    Ok(describe(
        key.public_key(),
        text.trim().to_owned() + "\n",
        comment,
        looks_encrypted(text),
    ))
}

fn rng() -> rand::rngs::ThreadRng {
    rand::rng()
}

/// A new key pair, written as `ssh-keygen` would (not encrypted).
pub fn generate(algorithm: KeyAlgorithm, comment: &str) -> Result<KeyMaterial, KeyError> {
    let bad = |e: russh::keys::ssh_key::Error| KeyError::Invalid(e.to_string());
    let mut key = match algorithm {
        KeyAlgorithm::Ed25519 => PrivateKey::random(&mut rng(), Algorithm::Ed25519).map_err(bad)?,
        KeyAlgorithm::Rsa3072 | KeyAlgorithm::Rsa4096 => {
            let bits = if algorithm == KeyAlgorithm::Rsa3072 { 3072 } else { 4096 };
            let pair = RsaKeypair::random(&mut rng(), bits).map_err(bad)?;
            PrivateKey::new(KeypairData::Rsa(pair), comment).map_err(bad)?
        }
        KeyAlgorithm::Ecdsa256 | KeyAlgorithm::Ecdsa384 | KeyAlgorithm::Ecdsa521 => {
            let curve = match algorithm {
                KeyAlgorithm::Ecdsa256 => EcdsaCurve::NistP256,
                KeyAlgorithm::Ecdsa384 => EcdsaCurve::NistP384,
                _ => EcdsaCurve::NistP521,
            };
            PrivateKey::random(&mut rng(), Algorithm::Ecdsa { curve }).map_err(bad)?
        }
    };
    key.set_comment(comment);
    let text = key.to_openssh(LineEnding::LF).map_err(bad)?.to_string();
    Ok(describe(key.public_key(), text, comment, false))
}

/// Re-encodes an existing key in OpenSSH format, protected by [new_passphrase] when given (aes256-ctr + bcrypt).
pub fn export(private_text: &str, passphrase: Option<&str>, new_passphrase: Option<&str>, comment: &str) -> Result<String, KeyError> {
    let bad = |e: russh::keys::ssh_key::Error| KeyError::Invalid(e.to_string());
    let mut key = decode(private_text, passphrase)?;
    key.set_comment(comment);
    let key = match new_passphrase.filter(|p| !p.is_empty()) {
        Some(p) => key.encrypt(&mut rng(), p).map_err(bad)?,
        None => key,
    };
    Ok(key.to_openssh(LineEnding::LF).map_err(bad)?.to_string())
}

fn describe(public: &PublicKey, private_key: String, comment: &str, encrypted: bool) -> KeyMaterial {
    let blob = public.to_bytes().unwrap_or_default();
    let key_type = public.algorithm().as_str().to_owned();
    let data = public.key_data();
    let description = match public.algorithm() {
        Algorithm::Ed25519 => "ED25519".to_owned(),
        Algorithm::Rsa { .. } => match data.rsa() {
            Some(r) => format!("RSA {}", r.key_size()),
            None => "RSA".to_owned(),
        },
        Algorithm::Ecdsa { curve } => format!(
            "ECDSA {}",
            match curve {
                EcdsaCurve::NistP256 => 256,
                EcdsaCurve::NistP384 => 384,
                EcdsaCurve::NistP521 => 521,
            }
        ),
        other => other.as_str().to_owned(),
    };
    let b64 = base64::engine::general_purpose::STANDARD.encode(&blob);
    let public_key = [key_type.as_str(), b64.as_str(), comment]
        .iter()
        .filter(|s| !s.is_empty())
        .copied()
        .collect::<Vec<_>>()
        .join(" ");
    KeyMaterial {
        private_key,
        public_key,
        key_type,
        description,
        fingerprint: crate::ssh::fingerprint(&blob),
        encrypted,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `ssh-keygen -t ed25519 -N '' -C test`; `ssh-keygen -lf` gives the fingerprint.
    const ED25519: &str = "-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACBemhbfoH/quJOc9jdhTOXSY2IiyOSusmLe15d2ebER9gAAAIj5nzxo+Z88
aAAAAAtzc2gtZWQyNTUxOQAAACBemhbfoH/quJOc9jdhTOXSY2IiyOSusmLe15d2ebER9g
AAAEAh8Rw9Zqc9h3gk/TfnizTpLJOkT8Zh1DMe3au1WA4EmF6aFt+gf+q4k5z2N2FM5dJj
YiLI5K6yYt7Xl3Z5sRH2AAAABHRlc3QB
-----END OPENSSH PRIVATE KEY-----";

    /// `ssh-keygen -t ecdsa -m PEM -N nautile -C ec`: the old encrypted PEM format.
    const ECDSA_PEM: &str = "-----BEGIN EC PRIVATE KEY-----
Proc-Type: 4,ENCRYPTED
DEK-Info: AES-128-CBC,642F8E67E715DA982A04CD7E29794E1E

JpNnEORJv6dyAoD4/I6Z/txr8yp34pIRfI2HtolsJSc/DFIPBdPsR6/XECg3Cy3g
w7HYfOiHwJTlo7a7gqv9O+5QKC/Euqks0BdKyaT0OqL0risw+QgErccLV69lt1Y5
6HlgrImuc4fEkSSZYwBgm4SyFywm6ksZDdwesHCKxA4=
-----END EC PRIVATE KEY-----";

    #[test]
    fn generated_keys_read_back() {
        for alg in [KeyAlgorithm::Ed25519, KeyAlgorithm::Ecdsa256, KeyAlgorithm::Ecdsa384] {
            let k = generate(alg, "nautile").unwrap();
            assert!(k.private_key.starts_with("-----BEGIN OPENSSH PRIVATE KEY-----"));
            let again = parse(&k.private_key, None, "nautile").unwrap();
            assert_eq!(again.public_key, k.public_key);
            assert_eq!(again.fingerprint, k.fingerprint);
            assert!(!again.encrypted);
        }
        let k = generate(KeyAlgorithm::Ed25519, "x").unwrap();
        assert_eq!((k.key_type.as_str(), k.description.as_str()), ("ssh-ed25519", "ED25519"));
        let k = generate(KeyAlgorithm::Ecdsa256, "x").unwrap();
        assert_eq!((k.key_type.as_str(), k.description.as_str()), ("ecdsa-sha2-nistp256", "ECDSA 256"));
    }

    #[test]
    fn passphrases_are_asked_and_checked() {
        let k = generate(KeyAlgorithm::Ed25519, "c").unwrap();
        let locked = export(&k.private_key, None, Some("secret"), "c").unwrap();
        assert_eq!(parse(&locked, None, "c").unwrap_err(), KeyError::PassphraseRequired);
        assert_eq!(parse(&locked, Some("nope"), "c").unwrap_err(), KeyError::WrongPassphrase);
        let open = parse(&locked, Some("secret"), "c").unwrap();
        assert!(open.encrypted);
        assert_eq!(open.public_key, k.public_key);
    }

    #[test]
    fn known_keys_have_the_openssh_fingerprints() {
        let k = parse(ED25519, None, "test").unwrap();
        assert_eq!(k.key_type, "ssh-ed25519");
        assert_eq!(
            k.public_key,
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF6aFt+gf+q4k5z2N2FM5dJjYiLI5K6yYt7Xl3Z5sRH2 test"
        );
        assert_eq!(k.fingerprint, "SHA256:PhDBqaTp44bX5NNvghkgs0U/CrJL/eZA5N1z61PFORg");
        assert_eq!(parse(ECDSA_PEM, None, "ec").unwrap_err(), KeyError::PassphraseRequired);
        let e = parse(ECDSA_PEM, Some("nautile"), "ec").unwrap();
        assert_eq!(e.fingerprint, "SHA256:x/2J2Yc3+VOIuNvvw40R5dbsjNTZk6UZW6uRWLihaDM");
        assert_eq!((e.description.as_str(), e.encrypted), ("ECDSA 256", true));
        assert!(matches!(parse("garbage", None, ""), Err(KeyError::Invalid(_))));
    }

    #[test]
    fn absurd_key_derivation_parameters_are_refused_before_decryption() {
        let k = generate(KeyAlgorithm::Ed25519, "c").unwrap();
        let locked = export(&k.private_key, None, Some("secret"), "c").unwrap();
        assert_eq!(openssh_rounds(&locked), Ok(Some(16)));
        assert_eq!(openssh_rounds(ED25519), Ok(None), "not encrypted");
        // The same key asking for a million rounds.
        let body: String = locked.lines().filter(|l| !l.starts_with("-----")).collect();
        let mut bin = base64::engine::general_purpose::STANDARD.decode(body).unwrap();
        // « bcrypt », then the options: their length, the salt's length, 16 bytes of salt, the rounds.
        let at = bin.windows(6).position(|w| w == b"bcrypt").unwrap() + 6 + 4 + 4 + 16;
        assert_eq!(bin[at..at + 4], 16u32.to_be_bytes());
        bin[at..at + 4].copy_from_slice(&1_000_000u32.to_be_bytes());
        let b64 = base64::engine::general_purpose::STANDARD.encode(&bin);
        let heavy = format!("-----BEGIN OPENSSH PRIVATE KEY-----\n{b64}\n-----END OPENSSH PRIVATE KEY-----\n");
        assert_eq!(openssh_rounds(&heavy), Ok(Some(1_000_000)));
        assert_eq!(parse(&heavy, Some("secret"), "c").unwrap_err(), out_of_bounds());
        // russh skips the lines before the header and those that are not base64: the same file so disguised
        // is still read by russh, and still refused here.
        let disguise = |text: &str| {
            let mut lines: Vec<&str> = text.lines().collect();
            lines.insert(1, "Comment: not base64");
            format!("junk before the header\n{}\n", lines.join("\n"))
        };
        assert!(russh::keys::decode_secret_key(disguise(&locked).trim(), Some("secret")).is_ok());
        assert_eq!(parse(&disguise(&locked), Some("secret"), "c").unwrap().public_key, k.public_key);
        assert_eq!(openssh_rounds(&disguise(&heavy)), Ok(Some(1_000_000)));
        assert_eq!(parse(&disguise(&heavy), Some("secret"), "c").unwrap_err(), out_of_bounds());
        // A body russh would decode but that cannot be analysed is refused.
        let broken = "x\n-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----";
        assert!(matches!(check_kdf(broken), Err(KeyError::Invalid(_))));

        let ppk = |memory: u64, passes: u64, lanes: u64| {
            format!(
                "PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: aes256-cbc\nComment: x\nPublic-Lines: 1\nAAAA\nKey-Derivation: Argon2id\nArgon2-Memory: {memory}\nArgon2-Passes: {passes}\nArgon2-Parallelism: {lanes}\nArgon2-Salt: 00\nPrivate-Lines: 1\nAAAA\nPrivate-MAC: 00\n"
            )
        };
        for (m, p, l) in [(4_194_304, 1, 1), (8192, 1_000_000, 1), (262_144, 11, 1), (8192, 10, 64)] {
            assert_eq!(check_kdf(&ppk(m, p, l)), Err(out_of_bounds()), "{m} {p} {l}");
        }
        // PuTTY's defaults (8 MiB, passes picked for 100 ms) pass.
        for (m, p, l) in [(8192, 21, 1), (8192, 34, 1), (262_144, 10, 8)] {
            assert_eq!(check_kdf(&ppk(m, p, l)), Ok(()), "{m} {p} {l}");
        }
        // ssh-key keeps the last of two values: a harmless first one does not hide it.
        let twice = ppk(8192, 1, 1).replace("Argon2-Memory: 8192\n", "Argon2-Memory: 8192\nArgon2-Memory: 4194304\n");
        assert_eq!(check_kdf(&twice), Err(out_of_bounds()));
        // A line of the key's own data is not taken for a parameter.
        let data = ppk(8192, 1, 1).replace("Public-Lines: 1\nAAAA", "Public-Lines: 1\nArgon2-Memory: 4194304");
        assert_eq!(check_kdf(&data), Ok(()));
        assert!(check_kdf(&"x".repeat(2 << 20)).is_err());
    }

    #[test]
    fn absurd_pkcs8_key_derivation_parameters_are_refused_before_decryption() {
        use pkcs8::der::Encode;
        use pkcs8::pkcs5::EncryptionScheme;
        use pkcs8::pkcs5::pbes2::{Kdf, ScryptParams};
        let k = generate(KeyAlgorithm::Ed25519, "c").unwrap();
        let key = decode(&k.private_key, None).unwrap();
        let mut pem = Vec::new();
        russh::keys::encode_pkcs8_pem_encrypted(&key, b"secret", 2048, &mut pem).unwrap();
        let pem = String::from_utf8(pem).unwrap();
        assert_eq!(check_kdf(pem.trim()), Ok(()));
        assert_eq!(parse(&pem, Some("secret"), "c").unwrap().public_key, k.public_key);

        // The same file with other key derivation parameters.
        let (_, body) = russh_body(pem.trim()).unwrap();
        let der = russh_body_bytes(&body).unwrap();
        let with = |kdf: &dyn Fn(&mut Kdf)| {
            let mut info = pkcs8::EncryptedPrivateKeyInfoRef::try_from(der.as_slice()).unwrap();
            let EncryptionScheme::Pbes2(params) = &mut info.encryption_algorithm else {
                panic!("PBES2")
            };
            kdf(&mut params.kdf);
            let b64 = base64::engine::general_purpose::STANDARD.encode(info.to_der().unwrap());
            format!("-----BEGIN ENCRYPTED PRIVATE KEY-----\n{b64}\n-----END ENCRYPTED PRIVATE KEY-----")
        };
        let pbkdf2 = |iterations: u32| {
            with(&|kdf| match kdf {
                Kdf::Pbkdf2(p) => p.iteration_count = iterations,
                _ => unreachable!(),
            })
        };
        assert_eq!(check_kdf(&pbkdf2(10_000_000)), Ok(()));
        assert_eq!(check_kdf(&pbkdf2(10_000_001)), Err(out_of_bounds()));
        assert_eq!(check_kdf(&pbkdf2(u32::MAX)), Err(out_of_bounds()));
        assert_eq!(parse(&pbkdf2(u32::MAX), Some("secret"), "c").unwrap_err(), out_of_bounds());
        let scrypt = |n: u64, r: u16, p: u16| {
            with(&|kdf| {
                let Kdf::Pbkdf2(old) = kdf else { unreachable!() };
                *kdf = Kdf::Scrypt(ScryptParams {
                    salt: old.salt,
                    cost_parameter: n,
                    block_size: r,
                    parallelization: p,
                    key_length: None,
                });
            })
        };
        // OpenSSL's defaults, and the largest accepted.
        for (n, r, p) in [(1 << 14, 8, 1), (1 << 20, 8, 16), (1 << 15, 32, 1)] {
            assert_eq!(check_kdf(&scrypt(n, r, p)), Ok(()), "{n} {r} {p}");
        }
        // Too costly: N, r, p, or the memory (128 × N × r bytes: 2 GiB).
        for (n, r, p) in [
            (1 << 21, 8, 1),
            (1 << 62, 8, 1),
            (1 << 14, 33, 1),
            (1 << 14, 8, 17),
            (1 << 20, 16, 1),
        ] {
            assert_eq!(check_kdf(&scrypt(n, r, p)), Err(out_of_bounds()), "{n} {r} {p}");
        }
        // russh decrypts the body whatever its header says once a passphrase is given (« ENCRYPTED » anywhere).
        let disguised = format!("ENCRYPTED\n{}", pbkdf2(u32::MAX).replace("ENCRYPTED PRIVATE KEY", "PRIVATE KEY"));
        assert_eq!(check_kdf(&disguised), Err(out_of_bounds()));
        // An unencrypted PKCS#8 key is not concerned.
        let mut plain = Vec::new();
        russh::keys::encode_pkcs8_pem(&key, &mut plain).unwrap();
        assert_eq!(check_kdf(std::str::from_utf8(&plain).unwrap().trim()), Ok(()));
    }

    #[test]
    fn traditional_pem_keys_are_read() {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/keys");
        for (file, fp, encrypted) in [
            ("rsa-aes256.pem", "SHA256:D2aUaY4I4h558+ER99KNWGdKjMei8ASLJeV5fa1n00M", true),
            ("ecdsa384.pem", "SHA256:CrNIqKPK3lsyOKgBGSyZm3yqB/+vOLzGdsqb7sNiuss", false),
            ("ecdsa384-des3.pem", "SHA256:CrNIqKPK3lsyOKgBGSyZm3yqB/+vOLzGdsqb7sNiuss", true),
            ("ecdsa256-aes128.pem", "SHA256:x/2J2Yc3+VOIuNvvw40R5dbsjNTZk6UZW6uRWLihaDM", true),
        ] {
            let text = std::fs::read_to_string(dir.join(file)).unwrap();
            if encrypted {
                assert_eq!(parse(&text, None, "").unwrap_err(), KeyError::PassphraseRequired, "{file}");
                assert_eq!(parse(&text, Some("wrong"), "").unwrap_err(), KeyError::WrongPassphrase, "{file}");
            }
            let k = parse(&text, Some("nautile"), "").unwrap_or_else(|e| panic!("{file}: {e}"));
            assert_eq!(k.fingerprint, fp, "{file}");
            assert_eq!(k.encrypted, encrypted, "{file}");
        }
    }
}
