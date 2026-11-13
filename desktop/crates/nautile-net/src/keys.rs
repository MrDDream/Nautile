//! SSH keys like Android's `SshKeys.kt`: import (OpenSSH, PEM, PKCS#8, PuTTY .ppk), generation and re-encoding
//! in OpenSSH format, with the same fields as the app (`keyType`, `description`, `publicKey`, `fingerprint`).

use std::fmt;

use base64::Engine;
use nautile_core::{tr, trf};
use russh::keys::ssh_key::private::{KeypairData, RsaKeypair};
use russh::keys::ssh_key::LineEnding;
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
#[derive(Debug, Clone, PartialEq, Eq)]
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

/// The private key, decrypted with [passphrase] when it is protected.
pub fn decode(text: &str, passphrase: Option<&str>) -> Result<PrivateKey, KeyError> {
    let t = text.trim();
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

fn rng() -> rand10::rngs::ThreadRng {
    rand10::rng()
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
