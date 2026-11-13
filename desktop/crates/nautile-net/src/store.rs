//! Backup places, as on Android (`BackupStore.kt`): a folder of this computer (also the one a Drive, OneDrive,
//! Nextcloud or Syncthing client keeps in step), WebDAV or S3. Each reads the file, writes it (only over what was
//! read, when the place has versions), and keeps the older copies as `name.1` … `name.4`.

use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use hmac::{Hmac, Mac};
use nautile_core::{tr, trf};
use sha2::{Digest, Sha256};
use ureq::http;

pub const FILE_NAME: &str = "nautile.backup";
/// Copies kept, the current one included.
pub const KEEP: usize = 5;

/// What a place holds: the file's text (None when absent) and its version tag when the place has one.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Remote {
    pub text: Option<String>,
    pub etag: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StoreError {
    /// Another device wrote in between: read again, merge, retry.
    Conflict,
    Other(String),
}

impl std::fmt::Display for StoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StoreError::Conflict => f.write_str("conflict"),
            StoreError::Other(e) => f.write_str(e),
        }
    }
}

type R<T> = Result<T, StoreError>;

fn other(e: impl std::fmt::Display) -> StoreError {
    StoreError::Other(e.to_string())
}

/// Where backups go (same fields as Android's `BackupDestination`).
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(tag = "kind")]
pub enum Dest {
    /// A folder here; the file inside is `nautile.backup`.
    Folder { path: PathBuf },
    /// A WebDAV URL (Nextcloud…); one ending with `/` is a folder.
    WebDav { url: String, username: String },
    S3 {
        endpoint: String,
        region: String,
        bucket: String,
        key: String,
        access_key: String,
    },
}

impl Dest {
    /// « Nextcloud › Documents », « S3 › bucket/key »…
    pub fn describe(&self) -> String {
        match self {
            Dest::Folder { path } => path.display().to_string(),
            Dest::WebDav { url, .. } => url.clone(),
            Dest::S3 { bucket, key, endpoint, .. } => format!("S3 › {endpoint} › {bucket}/{key}"),
        }
    }

    /// Opens the place; [secret] is the WebDAV password or the S3 secret key.
    pub fn open(&self, secret: &str) -> Box<dyn Store + Send> {
        match self {
            Dest::Folder { path } => Box::new(FolderStore { dir: path.clone() }),
            Dest::WebDav { url, username } => Box::new(WebDavStore {
                url: webdav_file_url(url),
                username: username.clone(),
                password: secret.to_owned(),
                agent: agent(),
            }),
            Dest::S3 {
                endpoint,
                region,
                bucket,
                key,
                access_key,
            } => Box::new(S3Store {
                s3: S3Client::new(endpoint, region, bucket, access_key, secret),
                key: key.clone(),
            }),
        }
    }
}

pub trait Store {
    fn read(&self) -> R<Remote>;
    /// Writes [bytes]; with [expected], fails with [StoreError::Conflict] if the file changed since (places with versions).
    fn write(&self, bytes: &[u8], expected: Option<&Remote>) -> R<()>;
    /// Before a backup overwrites the file: the previous ones become name.1 … name.4 (best effort).
    fn rotate(&self) {}
    /// Checks the place by writing a small file next to the backup, then deleting it.
    fn probe(&self) -> R<()> {
        self.read().map(|_| ())
    }
}

/// A WebDAV URL ending with `/` is a folder: the file goes inside.
pub fn webdav_file_url(url: &str) -> String {
    if url.ends_with('/') {
        format!("{url}{FILE_NAME}")
    } else {
        url.to_owned()
    }
}

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(120)))
        .http_status_as_error(false)
        .tls_config(
            ureq::tls::TlsConfig::builder()
                .root_certs(ureq::tls::RootCerts::PlatformVerifier)
                .build(),
        )
        .build()
        .into()
}

// --- Folder ---

pub struct FolderStore {
    pub dir: PathBuf,
}

impl FolderStore {
    fn file(&self, suffix: &str) -> PathBuf {
        self.dir.join(format!("{FILE_NAME}{suffix}"))
    }
}

impl Store for FolderStore {
    fn read(&self) -> R<Remote> {
        match std::fs::read_to_string(self.file("")) {
            Ok(t) => Ok(Remote {
                text: Some(t).filter(|t| !t.trim().is_empty()),
                etag: None,
            }),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                if self.dir.is_dir() {
                    Ok(Remote::default())
                } else {
                    Err(other(trf("Dossier introuvable : %1$s", &[&self.dir.display()])))
                }
            }
            Err(e) => Err(other(e)),
        }
    }

    fn write(&self, bytes: &[u8], _expected: Option<&Remote>) -> R<()> {
        // Written aside then renamed: a sync client never sees half a file.
        let tmp = self.file(".tmp");
        std::fs::write(&tmp, bytes).map_err(other)?;
        std::fs::rename(&tmp, self.file("")).map_err(other)
    }

    fn rotate(&self) {
        for i in (1..KEEP - 1).rev() {
            let _ = std::fs::rename(self.file(&format!(".{i}")), self.file(&format!(".{}", i + 1)));
        }
        let _ = std::fs::copy(self.file(""), self.file(".1"));
    }

    fn probe(&self) -> R<()> {
        let f = self.file(".test");
        std::fs::write(&f, b"nautile").map_err(other)?;
        let _ = std::fs::remove_file(f);
        Ok(())
    }
}

// --- WebDAV ---

pub struct WebDavStore {
    url: String,
    username: String,
    password: String,
    agent: ureq::Agent,
}

impl WebDavStore {
    fn request(&self, method: &str, url: &str) -> http::request::Builder {
        let mut b = http::Request::builder().method(method).uri(url);
        if !self.username.is_empty() {
            use base64::Engine;
            let token = base64::engine::general_purpose::STANDARD.encode(format!("{}:{}", self.username, self.password));
            b = b.header("Authorization", format!("Basic {token}"));
        }
        b
    }

    fn error(code: u16) -> StoreError {
        StoreError::Other(match code {
            401 | 403 => tr("WebDAV : identifiants refusés"),
            404 | 409 => tr("WebDAV : dossier introuvable"),
            c => trf("WebDAV : erreur %1$s", &[&c]),
        })
    }

    fn put(&self, url: &str, bytes: &[u8], expected: Option<&Remote>) -> R<()> {
        let mut b = self.request("PUT", url).header("Content-Type", "application/octet-stream");
        match expected {
            // A weak tag (Apache, just after a write) never matches: write plainly then.
            Some(Remote { etag: Some(e), .. }) if !e.starts_with("W/") => b = b.header("If-Match", e.as_str()),
            Some(Remote { etag: Some(_), .. }) => {}
            Some(Remote { text: None, .. }) => b = b.header("If-None-Match", "*"),
            _ => {}
        }
        let r = self.agent.run(b.body(bytes.to_vec()).map_err(other)?).map_err(other)?;
        match r.status().as_u16() {
            412 => Err(StoreError::Conflict),
            c if !(200..300).contains(&c) => Err(Self::error(c)),
            _ => Ok(()),
        }
    }

    fn send(&self, b: http::request::Builder) {
        if let Ok(req) = b.body(Vec::new()) {
            let _ = self.agent.run(req);
        }
    }
}

impl Store for WebDavStore {
    fn read(&self) -> R<Remote> {
        let req = self.request("GET", &self.url).body(Vec::new()).map_err(other)?;
        let mut r = self.agent.run(req).map_err(other)?;
        match r.status().as_u16() {
            404 => Ok(Remote::default()),
            c if (200..300).contains(&c) => {
                let etag = r.headers().get("ETag").and_then(|v| v.to_str().ok()).map(str::to_owned);
                let text = r.body_mut().with_config().limit(64 << 20).read_to_string().map_err(other)?;
                Ok(Remote {
                    text: Some(text).filter(|t| !t.trim().is_empty()),
                    etag,
                })
            }
            c => Err(Self::error(c)),
        }
    }

    fn write(&self, bytes: &[u8], expected: Option<&Remote>) -> R<()> {
        self.put(&self.url, bytes, expected)
    }

    fn rotate(&self) {
        let mv = |from: String, to: String| {
            self.send(self.request("MOVE", &from).header("Destination", to).header("Overwrite", "T"));
        };
        for i in (1..KEEP - 1).rev() {
            mv(format!("{}.{i}", self.url), format!("{}.{}", self.url, i + 1));
        }
        mv(self.url.clone(), format!("{}.1", self.url));
    }

    fn probe(&self) -> R<()> {
        let url = format!("{}.test", self.url);
        self.put(&url, b"nautile", None)?;
        self.send(self.request("DELETE", &url));
        Ok(())
    }
}

// --- S3 ---

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn sha256(b: &[u8]) -> Vec<u8> {
    Sha256::digest(b).to_vec()
}

fn hmac(key: &[u8], data: &str) -> Vec<u8> {
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("any key size");
    m.update(data.as_bytes());
    m.finalize().into_bytes().to_vec()
}

/// `20130524T000000Z` for a time in seconds since 1970 (UTC).
pub fn amz_date(secs: i64) -> String {
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}{m:02}{d:02}T{:02}{:02}{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

/// Each path segment percent-encoded as S3 expects (RFC 3986 unreserved kept).
pub fn encode_path(key: &str) -> String {
    key.trim_start_matches('/')
        .split('/')
        .map(|seg| {
            seg.bytes()
                .map(|c| {
                    if c.is_ascii_alphanumeric() || b"-_.~".contains(&c) {
                        (c as char).to_string()
                    } else {
                        format!("%{c:02X}")
                    }
                })
                .collect::<String>()
        })
        .collect::<Vec<_>>()
        .join("/")
}

/// The SigV4 `Authorization` header ([headers]: lower-case names, all signed), as Android's `S3Client.authorization`.
#[allow(clippy::too_many_arguments)]
pub fn authorization(
    method: &str,
    path: &str,
    query: &str,
    headers: &[(String, String)],
    payload_hash: &str,
    stamp: &str,
    region: &str,
    access_key: &str,
    secret_key: &str,
) -> String {
    let day = &stamp[..8];
    let mut sorted = headers.to_vec();
    sorted.sort();
    let names = sorted.iter().map(|(k, _)| k.as_str()).collect::<Vec<_>>().join(";");
    let canonical = [
        method.to_owned(),
        path.to_owned(),
        query.to_owned(),
        sorted.iter().map(|(k, v)| format!("{k}:{}\n", v.trim())).collect::<String>(),
        names.clone(),
        payload_hash.to_owned(),
    ]
    .join("\n");
    let scope = format!("{day}/{region}/s3/aws4_request");
    let to_sign = format!("AWS4-HMAC-SHA256\n{stamp}\n{scope}\n{}", hex(&sha256(canonical.as_bytes())));
    let mut key = hmac(format!("AWS4{secret_key}").as_bytes(), day);
    for part in [region, "s3", "aws4_request"] {
        key = hmac(&key, part);
    }
    format!(
        "AWS4-HMAC-SHA256 Credential={access_key}/{scope}, SignedHeaders={names}, Signature={}",
        hex(&hmac(&key, &to_sign))
    )
}

/// The few S3 calls a backup needs, path-style (`https://endpoint/bucket/key`).
pub struct S3Client {
    base: String,
    host: String,
    region: String,
    bucket: String,
    access_key: String,
    secret_key: String,
    agent: ureq::Agent,
}

pub struct S3Response {
    pub status: u16,
    pub etag: Option<String>,
    pub body: Vec<u8>,
}

impl S3Client {
    pub fn new(endpoint: &str, region: &str, bucket: &str, access_key: &str, secret_key: &str) -> S3Client {
        let e = endpoint.trim().trim_end_matches('/');
        let base = if e.contains("://") { e.to_owned() } else { format!("https://{e}") };
        let host = base
            .split("://")
            .nth(1)
            .unwrap_or_default()
            .split('/')
            .next()
            .unwrap_or_default()
            .to_owned();
        S3Client {
            base,
            host,
            region: if region.trim().is_empty() {
                "us-east-1".into()
            } else {
                region.trim().into()
            },
            bucket: bucket.trim().into(),
            access_key: access_key.trim().into(),
            secret_key: secret_key.into(),
            agent: agent(),
        }
    }

    pub fn call(&self, method: &str, key: Option<&str>, body: &[u8], extra: &[(&str, String)]) -> R<S3Response> {
        let path = match key {
            Some(k) => format!("/{}/{}", self.bucket, encode_path(k)),
            None => format!("/{}", self.bucket),
        };
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let stamp = amz_date(now);
        let hash = hex(&sha256(body));
        let mut headers = vec![
            ("host".to_owned(), self.host.clone()),
            ("x-amz-content-sha256".to_owned(), hash.clone()),
            ("x-amz-date".to_owned(), stamp.clone()),
        ];
        headers.extend(extra.iter().map(|(k, v)| (k.to_string(), v.clone())));
        let auth = authorization(
            method,
            &path,
            "",
            &headers,
            &hash,
            &stamp,
            &self.region,
            &self.access_key,
            &self.secret_key,
        );
        let mut b = http::Request::builder().method(method).uri(format!("{}{path}", self.base));
        for (k, v) in headers.iter().filter(|(k, _)| k != "host") {
            b = b.header(k.as_str(), v.as_str());
        }
        let req = b.header("Authorization", auth).body(body.to_vec()).map_err(other)?;
        let mut r = self.agent.run(req).map_err(|e| {
            let s = e.to_string();
            if s.contains("resolve") || s.contains("dns") {
                other(trf("S3 : serveur introuvable (%1$s)", &[&self.host]))
            } else {
                other(s)
            }
        })?;
        let etag = r.headers().get("ETag").and_then(|v| v.to_str().ok()).map(str::to_owned);
        let body = r.body_mut().with_config().limit(64 << 20).read_to_vec().unwrap_or_default();
        Ok(S3Response {
            status: r.status().as_u16(),
            etag,
            body,
        })
    }

    pub fn fail(r: &S3Response) -> StoreError {
        let text = String::from_utf8_lossy(&r.body);
        let code = text
            .split("<Code>")
            .nth(1)
            .and_then(|s| s.split("</Code>").next())
            .map(str::to_owned);
        StoreError::Other(match (r.status, code.as_deref()) {
            (403, Some("SignatureDoesNotMatch")) => tr("S3 : clé secrète ou région incorrecte"),
            (403, _) => tr("S3 : accès refusé (clé ou droits sur le bucket)"),
            (_, Some("NoSuchBucket")) => tr("S3 : bucket introuvable"),
            (c, code) => trf(
                "S3 : erreur %1$s",
                &[&[Some(c.to_string()), code.map(str::to_owned)]
                    .into_iter()
                    .flatten()
                    .collect::<Vec<_>>()
                    .join(" ")],
            ),
        })
    }

    pub fn create_bucket(&self) -> R<()> {
        let r = self.call("PUT", None, &[], &[])?;
        if (200..300).contains(&r.status) || r.status == 409 {
            Ok(())
        } else {
            Err(Self::fail(&r))
        }
    }

    /// Server-side copy; false when [from] does not exist.
    fn copy(&self, from: &str, to: &str) -> R<bool> {
        let src = format!("/{}/{}", self.bucket, encode_path(from));
        let r = self.call("PUT", Some(to), &[], &[("x-amz-copy-source", src)])?;
        match r.status {
            404 => Ok(false),
            c if (200..300).contains(&c) => Ok(true),
            _ => Err(Self::fail(&r)),
        }
    }
}

pub struct S3Store {
    s3: S3Client,
    key: String,
}

impl S3Store {
    fn put(&self, key: &str, bytes: &[u8], extra: &[(&str, String)]) -> R<()> {
        let r = self.s3.call("PUT", Some(key), bytes, extra)?;
        match r.status {
            412 | 409 => Err(StoreError::Conflict),
            c if (200..300).contains(&c) => Ok(()),
            _ => Err(S3Client::fail(&r)),
        }
    }
}

impl Store for S3Store {
    fn read(&self) -> R<Remote> {
        let r = self.s3.call("GET", Some(&self.key), &[], &[])?;
        match r.status {
            404 => Ok(Remote::default()),
            c if (200..300).contains(&c) => Ok(Remote {
                text: Some(String::from_utf8_lossy(&r.body).into_owned()).filter(|t| !t.trim().is_empty()),
                etag: r.etag,
            }),
            _ => Err(S3Client::fail(&r)),
        }
    }

    fn write(&self, bytes: &[u8], expected: Option<&Remote>) -> R<()> {
        let mut extra = Vec::new();
        match expected {
            Some(Remote { etag: Some(e), .. }) => extra.push(("if-match", e.clone())),
            Some(Remote { text: None, etag: None }) => extra.push(("if-none-match", "*".to_owned())),
            _ => {}
        }
        match self.put(&self.key, bytes, &extra) {
            // Services without conditional writes refuse the header: write plainly then.
            Err(StoreError::Other(e)) if !extra.is_empty() && e.contains("NotImplemented") => self.put(&self.key, bytes, &[]),
            r => r,
        }
    }

    fn rotate(&self) {
        for i in (1..KEEP - 1).rev() {
            let _ = self.s3.copy(&format!("{}.{i}", self.key), &format!("{}.{}", self.key, i + 1));
        }
        let _ = self.s3.copy(&self.key, &format!("{}.1", self.key));
    }

    fn probe(&self) -> R<()> {
        let k = format!("{}.test", self.key);
        self.put(&k, b"nautile", &[])?;
        let _ = self.s3.call("DELETE", Some(&k), &[], &[]);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// AWS docs, « Signature Calculations for the Authorization Header », GET Object (same as S3ClientTest.kt).
    #[test]
    fn signature_matches_the_aws_example() {
        let empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        let h = |k: &str, v: &str| (k.to_owned(), v.to_owned());
        let auth = authorization(
            "GET",
            "/test.txt",
            "",
            &[
                h("host", "examplebucket.s3.amazonaws.com"),
                h("range", "bytes=0-9"),
                h("x-amz-content-sha256", empty),
                h("x-amz-date", "20130524T000000Z"),
            ],
            empty,
            "20130524T000000Z",
            "us-east-1",
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        );
        assert_eq!(
            auth,
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, \
             SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, \
             Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
        );
        assert_eq!(amz_date(1_369_353_600), "20130524T000000Z");
    }

    #[test]
    fn keys_are_encoded() {
        assert_eq!(encode_path("/backups/nautile phone.backup"), "backups/nautile%20phone.backup");
        assert_eq!(encode_path("a/bé"), "a/b%C3%A9");
        assert_eq!(
            webdav_file_url("https://cloud/remote.php/dav/files/me/"),
            "https://cloud/remote.php/dav/files/me/nautile.backup"
        );
    }

    #[test]
    fn a_folder_keeps_older_copies() {
        let dir = std::env::temp_dir().join(format!("nautile-store-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let s = FolderStore { dir: dir.clone() };
        assert_eq!(s.read().unwrap(), Remote::default());
        for i in 0..7 {
            s.rotate();
            s.write(format!("v{i}").as_bytes(), None).unwrap();
        }
        assert_eq!(s.read().unwrap().text.as_deref(), Some("v6"));
        assert_eq!(std::fs::read_to_string(dir.join("nautile.backup.1")).unwrap(), "v5");
        assert_eq!(std::fs::read_to_string(dir.join("nautile.backup.4")).unwrap(), "v2");
        assert!(!dir.join("nautile.backup.5").exists());
        s.probe().unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }
}
