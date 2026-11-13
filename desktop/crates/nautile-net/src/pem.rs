//! The traditional PEM keys (`BEGIN RSA PRIVATE KEY`, `BEGIN EC PRIVATE KEY`), possibly with
//! `Proc-Type: 4,ENCRYPTED`: russh only reads encrypted RSA ones in AES-128, so they are decrypted here and
//! EC keys are rewritten in OpenSSH form.

use base64::Engine;
use russh::keys::PrivateKey;

use crate::keys::KeyError;

/// `Some` when [text] is a traditional PEM key (handled here), `None` for the other formats.
pub fn decode(text: &str, passphrase: Option<&str>) -> Option<Result<PrivateKey, KeyError>> {
    let t = text.trim();
    let kind = if t.starts_with("-----BEGIN RSA PRIVATE KEY-----") {
        Kind::Rsa
    } else if t.starts_with("-----BEGIN EC PRIVATE KEY-----") {
        Kind::Ec
    } else {
        return None;
    };
    let encrypted = t.contains("Proc-Type: 4,ENCRYPTED");
    if kind == Kind::Rsa && !encrypted {
        return None; // russh reads it.
    }
    Some(decode_pem(t, kind, passphrase))
}

#[derive(PartialEq, Eq, Clone, Copy)]
enum Kind {
    Rsa,
    Ec,
}

fn invalid(e: impl ToString) -> KeyError {
    KeyError::Invalid(e.to_string())
}

fn decode_pem(t: &str, kind: Kind, passphrase: Option<&str>) -> Result<PrivateKey, KeyError> {
    let mut dek = None;
    let mut body = String::new();
    for l in t.lines().map(str::trim) {
        if l.starts_with("-----") || l.is_empty() || l.starts_with("Proc-Type:") {
            continue;
        }
        if let Some(v) = l.strip_prefix("DEK-Info:") {
            dek = Some(v.trim().to_owned());
        } else {
            body.push_str(l);
        }
    }
    let mut der = base64::engine::general_purpose::STANDARD.decode(body).map_err(invalid)?;
    if let Some(dek) = dek {
        let pass = passphrase.filter(|p| !p.is_empty()).ok_or(KeyError::PassphraseRequired)?;
        der = decrypt(&dek, pass, &der).ok_or(KeyError::WrongPassphrase)?;
        // A wrong passphrase usually fails the padding; when it does not, the DER is garbage.
        if der.first() != Some(&0x30) {
            return Err(KeyError::WrongPassphrase);
        }
    }
    match kind {
        Kind::Rsa => {
            let b64 = base64::engine::general_purpose::STANDARD.encode(&der);
            let pem = format!("-----BEGIN RSA PRIVATE KEY-----\n{b64}\n-----END RSA PRIVATE KEY-----\n");
            russh::keys::decode_secret_key(&pem, None).map_err(|_| KeyError::WrongPassphrase)
        }
        Kind::Ec => {
            let bytes = sec1_to_openssh(&der).ok_or(KeyError::WrongPassphrase)?;
            PrivateKey::from_bytes(&bytes).map_err(invalid)
        }
    }
}

/// OpenSSL's `EVP_BytesToKey` with MD5, one iteration: the key of traditional PEM files.
fn bytes_to_key(pass: &[u8], salt: &[u8], len: usize) -> Vec<u8> {
    let mut key = Vec::new();
    let mut prev: Vec<u8> = Vec::new();
    while key.len() < len {
        let mut c = md5::Context::new();
        c.consume(&prev);
        c.consume(pass);
        c.consume(salt);
        prev = c.finalize().0.to_vec();
        key.extend_from_slice(&prev);
    }
    key.truncate(len);
    key
}

fn decrypt(dek: &str, pass: &str, data: &[u8]) -> Option<Vec<u8>> {
    use aes::cipher::{block_padding::Pkcs7, BlockModeDecrypt, KeyIvInit};
    let (alg, iv) = dek.split_once(',')?;
    let iv = hex(iv.trim())?;
    if iv.len() < 8 {
        return None;
    }
    let mut buf = data.to_vec();
    let out = match alg.trim() {
        "AES-128-CBC" => cbc::Decryptor::<aes::Aes128>::new_from_slices(&bytes_to_key(pass.as_bytes(), &iv[..8], 16), &iv)
            .ok()?
            .decrypt_padded::<Pkcs7>(&mut buf)
            .ok()?
            .to_vec(),
        "AES-192-CBC" => cbc::Decryptor::<aes::Aes192>::new_from_slices(&bytes_to_key(pass.as_bytes(), &iv[..8], 24), &iv)
            .ok()?
            .decrypt_padded::<Pkcs7>(&mut buf)
            .ok()?
            .to_vec(),
        "AES-256-CBC" => cbc::Decryptor::<aes::Aes256>::new_from_slices(&bytes_to_key(pass.as_bytes(), &iv[..8], 32), &iv)
            .ok()?
            .decrypt_padded::<Pkcs7>(&mut buf)
            .ok()?
            .to_vec(),
        "DES-EDE3-CBC" => cbc::Decryptor::<des::TdesEde3>::new_from_slices(&bytes_to_key(pass.as_bytes(), &iv[..8], 24), &iv)
            .ok()?
            .decrypt_padded::<Pkcs7>(&mut buf)
            .ok()?
            .to_vec(),
        _ => return None,
    };
    Some(out)
}

fn hex(s: &str) -> Option<Vec<u8>> {
    (0..s.len())
        .step_by(2)
        .map(|i| s.get(i..i + 2).and_then(|b| u8::from_str_radix(b, 16).ok()))
        .collect()
}

/// A DER element: (tag, content, rest).
fn der(input: &[u8]) -> Option<(u8, &[u8], &[u8])> {
    let (&tag, rest) = input.split_first()?;
    let (&first, mut rest) = rest.split_first()?;
    let len = if first < 0x80 {
        first as usize
    } else {
        let n = (first & 0x7f) as usize;
        if n == 0 || n > 4 || rest.len() < n {
            return None;
        }
        let len = rest[..n].iter().fold(0usize, |a, &b| (a << 8) | b as usize);
        rest = &rest[n..];
        len
    };
    if rest.len() < len {
        return None;
    }
    Some((tag, &rest[..len], &rest[len..]))
}

/// SEC1 `ECPrivateKey` → the binary `openssh-key-v1` form (not encrypted).
fn sec1_to_openssh(input: &[u8]) -> Option<Vec<u8>> {
    let (0x30, seq, _) = der(input)? else { return None };
    let (0x02, _version, rest) = der(seq)? else { return None };
    let (0x04, d, mut rest) = der(rest)? else { return None };
    let mut curve = None;
    let mut point = None;
    while !rest.is_empty() {
        let (tag, content, next) = der(rest)?;
        match tag {
            0xa0 => {
                let (0x06, oid, _) = der(content)? else { return None };
                curve = Some(match oid {
                    [0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07] => "nistp256",
                    [0x2b, 0x81, 0x04, 0x00, 0x22] => "nistp384",
                    [0x2b, 0x81, 0x04, 0x00, 0x23] => "nistp521",
                    _ => return None,
                });
            }
            0xa1 => {
                let (0x03, bits, _) = der(content)? else { return None };
                point = Some(bits.get(1..)?);
            }
            _ => {}
        }
        rest = next;
    }
    let (curve, q) = (curve?, point?);
    let key_type = format!("ecdsa-sha2-{curve}");

    fn string(out: &mut Vec<u8>, b: &[u8]) {
        out.extend_from_slice(&(b.len() as u32).to_be_bytes());
        out.extend_from_slice(b);
    }
    let mut public = Vec::new();
    string(&mut public, key_type.as_bytes());
    string(&mut public, curve.as_bytes());
    string(&mut public, q);

    let mut private = Vec::new();
    let check = rand::random::<u32>().to_be_bytes();
    private.extend_from_slice(&check);
    private.extend_from_slice(&check);
    string(&mut private, key_type.as_bytes());
    string(&mut private, curve.as_bytes());
    string(&mut private, q);
    let d: Vec<u8> = d.iter().copied().skip_while(|&b| b == 0).collect();
    let mut mpint = Vec::new();
    if d.first().is_some_and(|&b| b & 0x80 != 0) {
        mpint.push(0);
    }
    mpint.extend_from_slice(&d);
    string(&mut private, &mpint);
    string(&mut private, b"");
    let mut pad = 1u8;
    while private.len() % 8 != 0 {
        private.push(pad);
        pad += 1;
    }

    let mut out = b"openssh-key-v1\0".to_vec();
    string(&mut out, b"none");
    string(&mut out, b"none");
    string(&mut out, b"");
    out.extend_from_slice(&1u32.to_be_bytes());
    string(&mut out, &public);
    string(&mut out, &private);
    Some(out)
}
