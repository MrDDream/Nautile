//! « Mises à jour »: the latest release on GitHub, the file for this system, its download and its check.
//! Shared by the desktop's pop-up; Android does the same in `UpdateChecker.kt`.

use std::io::{Read, Write};
use std::path::Path;
use std::time::Duration;

use serde::Deserialize;
use sha2::{Digest, Sha256};

/// Where releases are published.
pub const REPO: &str = "MrDDream/Nautile";

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Asset {
    pub name: String,
    pub browser_download_url: String,
    #[serde(default)]
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Release {
    pub tag_name: String,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub body: Option<String>,
    #[serde(default)]
    pub prerelease: bool,
    #[serde(default)]
    pub html_url: String,
    #[serde(default)]
    pub assets: Vec<Asset>,
}

impl Release {
    /// `0.4.0` from `v0.4.0`.
    pub fn version(&self) -> &str {
        self.tag_name.trim_start_matches('v')
    }
}

fn agent(timeout: u64) -> ureq::Agent {
    ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(timeout)))
        .http_status_as_error(false)
        .user_agent(concat!("Nautile/", env!("CARGO_PKG_VERSION")))
        .tls_config(
            ureq::tls::TlsConfig::builder()
                .root_certs(ureq::tls::RootCerts::PlatformVerifier)
                .build(),
        )
        .build()
        .into()
}

/// The latest published release (drafts and pre-releases are not « latest » on GitHub).
pub fn latest() -> Result<Release, String> {
    let url = format!("https://api.github.com/repos/{REPO}/releases/latest");
    let mut r = agent(20)
        .get(&url)
        .header("Accept", "application/vnd.github+json")
        .call()
        .map_err(|e| e.to_string())?;
    if r.status() != 200 {
        return Err(format!("GitHub : HTTP {}", r.status().as_u16()));
    }
    let text = r.body_mut().read_to_string().map_err(|e| e.to_string())?;
    serde_json::from_str(&text).map_err(|e| e.to_string())
}

/// `1.2.3` as numbers; anything after a `-` (pre-release) is ignored.
fn parts(v: &str) -> Vec<u64> {
    v.trim_start_matches('v')
        .split('-')
        .next()
        .unwrap_or_default()
        .split('.')
        .map(|p| p.parse().unwrap_or(0))
        .collect()
}

/// Whether [candidate] is newer than [current].
pub fn is_newer(candidate: &str, current: &str) -> bool {
    let (a, b) = (parts(candidate), parts(current));
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
        if x != y {
            return x > y;
        }
    }
    false
}

/// What this desktop installs, to pick the right file.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Target {
    WindowsSetup,
    WindowsPortable,
    Deb,
    Rpm,
    Tarball,
    /// The CPU name in the file (`arm64`, `x86_64`).
    Mac(&'static str),
}

/// The file of [release] for [target], by the names `package.sh` gives them.
pub fn asset_for(release: &Release, target: Target) -> Option<&Asset> {
    let v = release.version();
    let name = match target {
        Target::WindowsSetup => format!("Nautile-{v}-setup.exe"),
        Target::WindowsPortable => format!("Nautile-{v}-portable.zip"),
        Target::Deb => format!("nautile_{v}_amd64.deb"),
        Target::Rpm => format!("nautile-{v}.x86_64.rpm"),
        Target::Tarball => format!("nautile-{v}-linux-x86_64.tar.gz"),
        Target::Mac(arch) => format!("Nautile-{v}-macos-{arch}.dmg"),
    };
    release.assets.iter().find(|a| a.name == name)
}

/// The SHA-256 of [file] listed in a `sha256sum` file, if any.
pub fn expected_sha256(sums: &str, file: &str) -> Option<String> {
    sums.lines().find_map(|l| {
        let (hash, name) = l.split_once(char::is_whitespace)?;
        (name.trim().trim_start_matches('*') == file).then(|| hash.trim().to_lowercase())
    })
}

/// A small text file of the release (the checksums).
pub fn fetch_text(url: &str) -> Result<String, String> {
    let mut r = agent(30).get(url).call().map_err(|e| e.to_string())?;
    if r.status() != 200 {
        return Err(format!("HTTP {}", r.status().as_u16()));
    }
    r.body_mut().read_to_string().map_err(|e| e.to_string())
}

/// Downloads [url] to [dest], telling [progress] (done, total) along the way; returns its SHA-256.
pub fn download(url: &str, dest: &Path, mut progress: impl FnMut(u64, u64)) -> Result<String, String> {
    let mut r = agent(30 * 60).get(url).call().map_err(|e| e.to_string())?;
    if r.status() != 200 {
        return Err(format!("HTTP {}", r.status().as_u16()));
    }
    let total = r
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);
    let part = dest.with_extension("part");
    let mut out = std::fs::File::create(&part).map_err(|e| e.to_string())?;
    let mut body = r.body_mut().with_config().limit(1 << 31).reader();
    let mut hash = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    let mut done = 0u64;
    loop {
        let n = body.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n]).map_err(|e| e.to_string())?;
        hash.update(&buf[..n]);
        done += n as u64;
        progress(done, total);
    }
    out.flush().map_err(|e| e.to_string())?;
    drop(out);
    std::fs::rename(&part, dest).map_err(|e| e.to_string())?;
    Ok(hex::encode(hash.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_compare_by_numbers() {
        assert!(is_newer("v0.4.1", "0.4.0"));
        assert!(is_newer("0.10.0", "0.9.9"));
        assert!(is_newer("1.0", "0.99.0"));
        assert!(!is_newer("v0.4.0", "0.4.0"));
        assert!(!is_newer("0.3.0", "0.4.0"));
        assert!(!is_newer("0.4.0-beta", "0.4.0"));
    }

    #[test]
    fn each_system_gets_its_own_file() {
        let asset = |n: &str| Asset {
            name: n.into(),
            browser_download_url: format!("https://example.org/{n}"),
            size: 1,
        };
        let r = Release {
            tag_name: "v0.5.0".into(),
            name: None,
            body: None,
            prerelease: false,
            html_url: String::new(),
            assets: [
                "Nautile-v0.5.0.apk",
                "Nautile-0.5.0-setup.exe",
                "Nautile-0.5.0-portable.zip",
                "nautile_0.5.0_amd64.deb",
                "nautile-0.5.0.x86_64.rpm",
                "nautile-0.5.0-linux-x86_64.tar.gz",
                "Nautile-0.5.0-macos-arm64.dmg",
            ]
            .map(asset)
            .to_vec(),
        };
        assert_eq!(asset_for(&r, Target::WindowsSetup).unwrap().name, "Nautile-0.5.0-setup.exe");
        assert_eq!(asset_for(&r, Target::WindowsPortable).unwrap().name, "Nautile-0.5.0-portable.zip");
        assert_eq!(asset_for(&r, Target::Deb).unwrap().name, "nautile_0.5.0_amd64.deb");
        assert_eq!(asset_for(&r, Target::Rpm).unwrap().name, "nautile-0.5.0.x86_64.rpm");
        assert_eq!(asset_for(&r, Target::Tarball).unwrap().name, "nautile-0.5.0-linux-x86_64.tar.gz");
        assert_eq!(asset_for(&r, Target::Mac("arm64")).unwrap().name, "Nautile-0.5.0-macos-arm64.dmg");
        assert!(asset_for(&r, Target::Mac("x86_64")).is_none());
    }

    #[test]
    fn checksums_are_read_like_sha256sum_writes_them() {
        let sums = "abc123  Nautile-0.5.0-setup.exe\nDEF456 *nautile_0.5.0_amd64.deb\n";
        assert_eq!(expected_sha256(sums, "Nautile-0.5.0-setup.exe").as_deref(), Some("abc123"));
        assert_eq!(expected_sha256(sums, "nautile_0.5.0_amd64.deb").as_deref(), Some("def456"));
        assert_eq!(expected_sha256(sums, "missing"), None);
    }

    /// Against the real repository (not run by default: it needs the network).
    #[test]
    #[ignore]
    fn the_latest_release_is_read() {
        let r = latest().unwrap();
        assert!(r.tag_name.starts_with('v'));
        assert!(!r.assets.is_empty());
        // A real download, checked against the release's own checksums.
        let apk = r.assets.iter().find(|a| a.name.ends_with("-x86_64.apk")).unwrap();
        let sums = r.assets.iter().find(|a| a.name == "SHA256SUMS.txt").unwrap();
        let want = expected_sha256(&fetch_text(&sums.browser_download_url).unwrap(), &apk.name).unwrap();
        let dest = std::env::temp_dir().join(&apk.name);
        let mut last = 0;
        let got = download(&apk.browser_download_url, &dest, |d, _| last = d).unwrap();
        assert_eq!(got, want);
        assert_eq!(last, std::fs::metadata(&dest).unwrap().len());
        let _ = std::fs::remove_file(dest);
    }
}
