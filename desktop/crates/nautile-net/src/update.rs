//! « Mises à jour »: the latest release on GitHub, the file for this system, its download and its check.
//! Shared by the desktop's pop-up; Android does the same in `UpdateChecker.kt`. A desktop update is installed only
//! when the release's checksums list the downloaded file with that very hash.

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

/// `1.2.3-rc1` as its numbers and its pre-release suffix (build metadata after `+` left out).
fn parts(v: &str) -> (Vec<u64>, Option<&str>) {
    let v = v.trim_start_matches('v');
    let v = v.split('+').next().unwrap_or_default();
    let (numbers, pre) = match v.split_once('-') {
        Some((n, p)) => (n, Some(p)),
        None => (v, None),
    };
    (numbers.split('.').map(|p| p.parse().unwrap_or(0)).collect(), pre)
}

/// Two pre-release suffixes, their runs of digits compared as numbers (`rc10` after `rc9`).
fn compare_pre(a: &str, b: &str) -> std::cmp::Ordering {
    fn runs(s: &str) -> Vec<&str> {
        let b = s.as_bytes();
        let mut out = Vec::new();
        let mut start = 0;
        for i in 1..b.len() {
            // Between an ASCII digit and anything else: always a character boundary.
            if b[i].is_ascii_digit() != b[i - 1].is_ascii_digit() {
                out.push(&s[start..i]);
                start = i;
            }
        }
        if !s.is_empty() {
            out.push(&s[start..]);
        }
        out
    }
    let (x, y) = (runs(a), runs(b));
    for (rx, ry) in x.iter().zip(&y) {
        let o = match (rx.parse::<u64>(), ry.parse::<u64>()) {
            (Ok(nx), Ok(ny)) => nx.cmp(&ny),
            _ => rx.cmp(ry),
        };
        if o.is_ne() {
            return o;
        }
    }
    x.len().cmp(&y.len())
}

/// Whether [candidate] is newer than [current]: by their numbers, then a release comes after its pre-releases
/// (`0.5.0` after `0.5.0-rc1`).
pub fn is_newer(candidate: &str, current: &str) -> bool {
    let ((a, pre_a), (b, pre_b)) = (parts(candidate), parts(current));
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
        if x != y {
            return x > y;
        }
    }
    match (pre_a, pre_b) {
        (None, Some(_)) => true,
        (Some(x), Some(y)) => compare_pre(x, y).is_gt(),
        _ => false,
    }
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
    // The .deb and .rpm carry a package revision (`nautile_0.4.0-1_amd64.deb`), and write a pre-release
    // `0.5.0~rc1` (sorted before 0.5.0), which package.sh renames `0.5.0.rc1` (GitHub changes `~` in file names).
    let packaged = [v.to_owned(), v.replacen('-', "~", 1), v.replacen('-', ".", 1)];
    let (start, end): (Vec<String>, &str) = match target {
        Target::WindowsSetup => (vec![format!("Nautile-{v}-setup.exe")], ""),
        Target::WindowsPortable => (vec![format!("Nautile-{v}-portable.zip")], ""),
        Target::Deb => (packaged.iter().map(|p| format!("nautile_{p}")).collect(), "_amd64.deb"),
        Target::Rpm => (packaged.iter().map(|p| format!("nautile-{p}")).collect(), ".x86_64.rpm"),
        Target::Tarball => (vec![format!("nautile-{v}-linux-x86_64.tar.gz")], ""),
        Target::Mac(arch) => (vec![format!("Nautile-{v}-macos-{arch}.dmg")], ""),
    };
    release.assets.iter().find(|a| {
        start.iter().any(|start| {
            if end.is_empty() {
                a.name == *start
            } else {
                a.name.len() >= start.len() + end.len()
                    && a.name.starts_with(start.as_str())
                    && a.name.ends_with(end)
                    && a.name[start.len()..a.name.len() - end.len()]
                        .bytes()
                        .all(|b| b == b'-' || b.is_ascii_digit())
            }
        })
    })
}

/// The checksums of the desktop files (`sha256sum` format), published with each release by the publish-desktop
/// job. The APKs have their own, `SHA256SUMS.txt`, which does not list the desktop files.
pub const DESKTOP_SUMS_FILE: &str = "SHA256SUMS-desktop.txt";

/// The checksums file of [release] for the desktop files, if it has one.
pub fn desktop_sums(release: &Release) -> Option<&Asset> {
    release.assets.iter().find(|a| a.name == DESKTOP_SUMS_FILE)
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

/// Whether a file of SHA-256 [got] may be installed as [file]: only when [sums] (the release's checksums file,
/// `None` when it has none) lists it with that very hash. A missing file or line is a refusal, not a pass.
pub fn check_sha256(sums: Option<&str>, file: &str, got: &str) -> Result<(), String> {
    let want = sums
        .and_then(|s| expected_sha256(s, file))
        .ok_or_else(|| nautile_core::tr("Cette version ne publie pas la somme de contrôle du fichier : mise à jour refusée."))?;
    if want != got.to_lowercase() {
        return Err(nautile_core::tr(
            "Le fichier téléchargé ne correspond pas à la somme de contrôle de la version : il a été supprimé.",
        ));
    }
    Ok(())
}

/// The release's desktop checksums; refused when it publishes none.
pub fn release_sums(release: &Release) -> Result<String, String> {
    let sums = desktop_sums(release)
        .ok_or_else(|| nautile_core::tr("Cette version ne publie pas la somme de contrôle du fichier : mise à jour refusée."))?;
    fetch_text(&sums.browser_download_url)
}

/// Downloads [asset] of [release] to [dest], checked against the release's checksums: refused when they are
/// missing, do not list the file or do not match it (then nothing is
/// left at [dest]). Returns the file's SHA-256, to check it again just before it is installed. The only
/// download meant for installing.
pub fn download_checked(release: &Release, asset: &Asset, dest: &Path, progress: impl FnMut(u64, u64)) -> Result<String, String> {
    let sums = release_sums(release)?;
    // Checked before the long download, then again against the downloaded bytes.
    let Some(want) = expected_sha256(&sums, &asset.name) else {
        return check_sha256(Some(&sums), &asset.name, "").map(|()| String::new());
    };
    let part = part_path(dest);
    let got = fetch_to(&asset.browser_download_url, &part, progress)?;
    if let Err(e) = check_sha256(Some(&sums), &asset.name, &got) {
        let _ = std::fs::remove_file(&part);
        return Err(e);
    }
    std::fs::rename(&part, dest).map_err(|e| e.to_string())?;
    Ok(want)
}

/// The SHA-256 of a file on disk (lowercase hex).
pub fn sha256_file(path: &Path) -> std::io::Result<String> {
    let mut f = std::fs::File::open(path)?;
    let mut hash = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hash.update(&buf[..n]);
    }
    Ok(hex::encode(hash.finalize()))
}

fn part_path(dest: &Path) -> std::path::PathBuf {
    dest.with_extension("part")
}

/// Downloads [url] to [dest], telling [progress] (done, total) along the way; returns its SHA-256.
/// Not checked: installing goes through [download_checked].
pub fn download(url: &str, dest: &Path, progress: impl FnMut(u64, u64)) -> Result<String, String> {
    let part = part_path(dest);
    let hash = fetch_to(url, &part, progress)?;
    std::fs::rename(&part, dest).map_err(|e| e.to_string())?;
    Ok(hash)
}

/// The body of [url] into [part]; its SHA-256.
fn fetch_to(url: &str, part: &Path, mut progress: impl FnMut(u64, u64)) -> Result<String, String> {
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
    let mut out = std::fs::File::create(part).map_err(|e| e.to_string())?;
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
        // A release after its pre-releases, pre-releases in their order.
        assert!(is_newer("0.5.0", "0.5.0-rc1"));
        assert!(is_newer("v0.5.0-rc2", "0.5.0-rc1"));
        assert!(is_newer("0.5.0-rc10", "0.5.0-rc9"));
        assert!(is_newer("0.5.0-rc.1", "0.5.0-beta.3"));
        assert!(!is_newer("0.5.0-rc1", "0.5.0-rc1"));
        assert!(is_newer("0.5.0-rc1", "0.4.9"));
        assert!(!is_newer("0.5.0+build7", "0.5.0"));
    }

    #[test]
    fn pre_release_packages_are_found_by_their_debian_names() {
        let r = Release {
            tag_name: "v0.5.0-rc1".into(),
            name: None,
            body: None,
            prerelease: true,
            html_url: String::new(),
            assets: [
                "nautile_0.5.0.rc1-1_amd64.deb",
                "nautile-0.5.0~rc1-1.x86_64.rpm",
                "Nautile-0.5.0-rc1-setup.exe",
            ]
            .map(|n| Asset {
                name: n.into(),
                browser_download_url: String::new(),
                size: 1,
            })
            .to_vec(),
        };
        assert_eq!(asset_for(&r, Target::Deb).unwrap().name, "nautile_0.5.0.rc1-1_amd64.deb");
        assert_eq!(asset_for(&r, Target::Rpm).unwrap().name, "nautile-0.5.0~rc1-1.x86_64.rpm");
        assert_eq!(asset_for(&r, Target::WindowsSetup).unwrap().name, "Nautile-0.5.0-rc1-setup.exe");
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
                "nautile_0.5.0-1_amd64.deb",
                "nautile-0.5.0-1.x86_64.rpm",
                "nautile-0.5.0-linux-x86_64.tar.gz.sig",
                "nautile-0.5.0-linux-x86_64.tar.gz",
                "Nautile-0.5.0-macos-arm64.dmg",
            ]
            .map(asset)
            .to_vec(),
        };
        assert_eq!(asset_for(&r, Target::WindowsSetup).unwrap().name, "Nautile-0.5.0-setup.exe");
        assert_eq!(asset_for(&r, Target::WindowsPortable).unwrap().name, "Nautile-0.5.0-portable.zip");
        assert_eq!(asset_for(&r, Target::Deb).unwrap().name, "nautile_0.5.0-1_amd64.deb");
        assert_eq!(asset_for(&r, Target::Rpm).unwrap().name, "nautile-0.5.0-1.x86_64.rpm");
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

    #[test]
    fn an_update_without_a_matching_checksum_is_refused() {
        let sums = "abc123  Nautile-0.5.0-setup.exe\n";
        assert!(check_sha256(Some(sums), "Nautile-0.5.0-setup.exe", "abc123").is_ok());
        assert!(check_sha256(Some(sums), "Nautile-0.5.0-setup.exe", "ABC123").is_ok());
        assert!(check_sha256(Some(sums), "Nautile-0.5.0-setup.exe", "abc124").is_err(), "wrong hash");
        assert!(
            check_sha256(Some(sums), "nautile_0.5.0-1_amd64.deb", "abc123").is_err(),
            "not listed"
        );
        assert!(
            check_sha256(None, "Nautile-0.5.0-setup.exe", "abc123").is_err(),
            "no checksums file"
        );
        // Without checksums, nothing is downloaded at all.
        let dest = std::env::temp_dir().join(format!("nautile-update-{}.exe", std::process::id()));
        let asset = |n: &str| Asset {
            name: n.into(),
            browser_download_url: format!("https://invalid.invalid/{n}"),
            size: 1,
        };
        let r = Release {
            tag_name: "v0.5.0".into(),
            name: None,
            body: None,
            prerelease: false,
            html_url: String::new(),
            assets: vec![asset("x.exe")],
        };
        let missing = download_checked(&r, &r.assets[0].clone(), &dest, |_, _| {}).unwrap_err();
        assert!(missing.contains("somme") || missing.contains("checksum"), "{missing}");
        assert!(!dest.exists());
    }

    /// Against the real repository (not run by default: it needs the network).
    #[test]
    #[ignore]
    fn the_latest_release_is_read() {
        let r = latest().unwrap();
        assert!(r.tag_name.starts_with('v'));
        assert!(!r.assets.is_empty());
        // A desktop release publishes its checksums.
        if desktop_sums(&r).is_some() {
            release_sums(&r).unwrap();
        }
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
