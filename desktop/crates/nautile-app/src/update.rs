//! « Mises à jour » on the desktop: a check at start (once a day at most, only once the user said yes at the
//! first start), a pop-up with the release notes, then the right file is downloaded into a private folder,
//! checked against the release's signed checksums, checked again just before it is installed.

use std::path::{Path, PathBuf};

use iced::Task;
use iced::futures::SinkExt;
use iced::widget::markdown;
use nautile_core::{tr, trf};
use nautile_net::update::{self, Release, Target};

use crate::app::{App, Message};

#[derive(Default)]
pub struct UpdateState {
    /// A newer release, and its notes ready to show.
    pub available: Option<Release>,
    pub notes: Vec<markdown::Item>,
    pub checking: bool,
    /// Downloaded and total bytes, while downloading.
    pub progress: Option<(u64, u64)>,
    /// The result of « Vérifier maintenant », or what went wrong.
    pub status: Option<Result<String, String>>,
    /// Downloaded while the app was locked: installed once it is open again (the installer quits Nautile).
    pub pending: Option<(PathBuf, String)>,
}

#[derive(Debug, Clone)]
pub enum UpdateMsg {
    /// Checks GitHub; `true` from « Vérifier maintenant » (says « à jour » too).
    Check(bool),
    Checked(bool, Result<Release, String>),
    Download,
    Progress(u64, u64),
    /// The file and its SHA-256 from the signed checksums.
    Downloaded(Result<(PathBuf, String), String>),
    Skip,
    Later,
    /// The release page in the browser (no file for this system).
    OpenPage,
    AutoCheck(bool),
}

/// The kind of package this copy of Nautile was installed from.
pub fn target(portable: bool) -> Target {
    #[cfg(windows)]
    {
        if portable { Target::WindowsPortable } else { Target::WindowsSetup }
    }
    #[cfg(target_os = "macos")]
    {
        let _ = portable;
        Target::Mac(if cfg!(target_arch = "aarch64") { "arm64" } else { "x86_64" })
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        let _ = portable;
        let installed = std::env::current_exe().is_ok_and(|p| p.starts_with("/usr"));
        if !installed {
            Target::Tarball
        } else if Path::new("/etc/debian_version").exists() {
            Target::Deb
        } else if ["/etc/redhat-release", "/etc/fedora-release", "/etc/SuSE-release", "/etc/os-release"]
            .iter()
            .any(|f| {
                std::fs::read_to_string(f)
                    .is_ok_and(|t| t.contains("fedora") || t.contains("rhel") || t.contains("suse") || t.contains("Red Hat"))
            })
        {
            Target::Rpm
        } else {
            Target::Tarball
        }
    }
}

const CURRENT: &str = env!("CARGO_PKG_VERSION");

impl App {
    /// The check at start, when on and not done in the last day.
    pub fn update_boot(&mut self) -> Task<Message> {
        let now = nautile_core::model::now_ms();
        // What an earlier update left behind (the portable exe replaced while it ran).
        if let Ok(exe) = std::env::current_exe() {
            let _ = std::fs::remove_file(exe.with_extension("old"));
        }
        // Never asked: nothing goes to GitHub before the answer.
        if self.settings.update_check.is_none() {
            if self.modal.is_none() {
                self.modal = Some(crate::app::Modal::UpdateAsk);
            }
            return Task::none();
        }
        if self.settings.update_check() && now - self.settings.update_last_check > 20 * 3_600_000 {
            return self.update_msg(UpdateMsg::Check(false));
        }
        Task::none()
    }

    pub fn update_msg(&mut self, m: UpdateMsg) -> Task<Message> {
        match m {
            UpdateMsg::Check(manual) => {
                if self.updates.checking {
                    return Task::none();
                }
                self.updates.checking = true;
                if manual {
                    self.updates.status = None;
                }
                return Task::perform(
                    async move {
                        tokio::task::spawn_blocking(update::latest)
                            .await
                            .map_err(|e| e.to_string())
                            .and_then(|r| r)
                    },
                    move |r| Message::Update(UpdateMsg::Checked(manual, r)),
                );
            }
            UpdateMsg::Checked(manual, r) => {
                self.updates.checking = false;
                match r {
                    Ok(release) => {
                        self.settings.update_last_check = nautile_core::model::now_ms();
                        self.save_settings();
                        let newer = !release.prerelease && update::is_newer(release.version(), CURRENT);
                        let skipped = self.settings.update_skipped.as_deref() == Some(release.version());
                        if newer && (manual || !skipped) {
                            self.updates.notes = markdown::parse(release.body.as_deref().unwrap_or_default()).collect();
                            self.notify(
                                trf("Nautile %1$s est disponible", &[&release.tag_name]),
                                tr("Ouvrez Nautile pour l'installer."),
                            );
                            self.updates.status = Some(Ok(trf("%1$s disponible", &[&release.tag_name])));
                            self.updates.available = Some(release);
                            self.modal = Some(crate::app::Modal::Update);
                        } else if manual {
                            self.updates.status = Some(Ok(trf("Nautile est à jour (v%1$s)", &[&CURRENT])));
                        }
                    }
                    Err(e) => {
                        if manual {
                            self.updates.status = Some(Err(e));
                        }
                    }
                }
            }
            UpdateMsg::Download => {
                let Some(release) = self.updates.available.clone() else {
                    return Task::none();
                };
                let Some(asset) = update::asset_for(&release, target(self.paths.portable)).cloned() else {
                    return self.update_msg(UpdateMsg::OpenPage);
                };
                let target = target(self.paths.portable);
                let dir = match download_dir(target) {
                    Ok(d) => d,
                    Err(e) => {
                        self.updates.status = Some(Err(e.to_string()));
                        return Task::none();
                    }
                };
                let dest = dir.join(&asset.name);
                self.updates.progress = Some((0, asset.size));
                self.updates.status = None;
                return Task::run(download(release, asset, dest), Message::Update);
            }
            UpdateMsg::Progress(done, total) => self.updates.progress = Some((done, total)),
            UpdateMsg::Downloaded(r) => {
                self.updates.progress = None;
                match r {
                    Ok((path, sha256)) if self.locked || self.vault.is_some() => self.updates.pending = Some((path, sha256)),
                    Ok((path, sha256)) => return self.install(&path, &sha256),
                    Err(e) => self.updates.status = Some(Err(e)),
                }
            }
            UpdateMsg::Skip => {
                self.settings.update_skipped = self.updates.available.as_ref().map(|r| r.version().to_owned());
                self.save_settings();
                self.modal = None;
            }
            UpdateMsg::Later => self.modal = None,
            UpdateMsg::OpenPage => {
                let url = self
                    .updates
                    .available
                    .as_ref()
                    .map(|r| r.html_url.clone())
                    .filter(|u| u.starts_with("https://"))
                    .unwrap_or_else(|| format!("https://github.com/{}/releases/latest", update::REPO));
                crate::kit::open_external(url);
            }
            UpdateMsg::AutoCheck(v) => {
                self.settings.update_check = Some(v);
                self.save_settings();
                if self.modal == Some(crate::app::Modal::UpdateAsk) {
                    self.modal = None;
                    if v {
                        return self.update_boot();
                    }
                }
            }
        }
        Task::none()
    }

    /// The update downloaded while the app was locked, now that it is open again.
    pub fn install_pending(&mut self) -> Task<Message> {
        match self.updates.pending.take() {
            Some((path, sha256)) => self.install(&path, &sha256),
            None => Task::none(),
        }
    }

    /// Hands the downloaded file to the system: the Windows installer (then Nautile quits), the portable exe
    /// replaced in place, a package opened with the system's installer, or the folder of the archive. The file
    /// is hashed again first: what is handed over is what was checked.
    fn install(&mut self, path: &Path, sha256: &str) -> Task<Message> {
        if !nautile_net::update::sha256_file(path).is_ok_and(|h| h.eq_ignore_ascii_case(sha256)) {
            let _ = std::fs::remove_file(path);
            self.updates.status = Some(Err(tr(
                "Le fichier téléchargé a changé depuis sa vérification : il a été supprimé.",
            )));
            return Task::none();
        }
        match target(self.paths.portable) {
            Target::WindowsSetup => match std::process::Command::new(path).spawn() {
                Ok(_) => iced::exit(),
                Err(e) => {
                    self.updates.status = Some(Err(e.to_string()));
                    Task::none()
                }
            },
            Target::WindowsPortable => match replace_portable(path) {
                Ok(()) => iced::exit(),
                Err(e) => {
                    self.updates.status = Some(Err(e));
                    Task::none()
                }
            },
            Target::Tarball => {
                crate::kit::open_external(path.parent().unwrap_or(path));
                self.updates.status = Some(Ok(tr("Archive téléchargée : remplacez l'ancien dossier par celui-ci.")));
                Task::none()
            }
            Target::Deb | Target::Rpm | Target::Mac(_) => {
                crate::kit::open_external(path);
                self.updates.status = Some(Ok(tr(
                    "Le paquet est ouvert dans l'installeur du système. Redémarrez Nautile ensuite.",
                )));
                Task::none()
            }
        }
    }
}

/// Where the file goes: a folder of this user's only (0700 in the app's cache) for what an installer opens — never
/// the shared Downloads folder, where another program could swap it after the check. The archive (put in place by
/// hand) goes to Downloads.
fn download_dir(target: Target) -> std::io::Result<PathBuf> {
    if target == Target::Tarball
        && let Some(d) = dirs::download_dir()
    {
        return Ok(d);
    }
    let base = dirs::cache_dir().unwrap_or_else(std::env::temp_dir);
    let dir = base
        .join(if cfg!(target_os = "linux") { "nautile" } else { "Nautile" })
        .join("updates");
    crate::storage::private_dir(&dir)?;
    Ok(dir)
}

/// The download in a thread, its progress (a few steps, not every block) and its checksum check.
fn download(release: Release, asset: nautile_net::update::Asset, dest: PathBuf) -> impl iced::futures::Stream<Item = UpdateMsg> {
    iced::stream::channel(32, async move |mut out: iced::futures::channel::mpsc::Sender<UpdateMsg>| {
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        std::thread::spawn(move || {
            let progress = tx.clone();
            let mut shown = 0u64;
            // Refused without the release's signed checksum of this very file: nothing unchecked is ever installed.
            let result = update::download_checked(&release, &asset, &dest, |done, total| {
                let step = (total / 100).max(256 * 1024);
                if done >= shown + step || done == total {
                    shown = done;
                    let _ = progress.send(UpdateMsg::Progress(done, total));
                }
            })
            .map(|sha256| (dest, sha256));
            let _ = tx.send(UpdateMsg::Downloaded(result));
        });
        while let Some(m) = rx.recv().await {
            let _ = out.send(m).await;
        }
    })
}

/// The portable copy: the new exe takes the place of the running one (renamed `.old`, removed at next start),
/// then starts.
#[cfg(windows)]
fn replace_portable(zip: &Path) -> Result<(), String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let tmp = std::env::temp_dir().join(format!("nautile-update-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&tmp);
    // One script, its paths quoted for PowerShell: split into several arguments, a path with a space (the
    // user's profile) would be cut in two.
    let script = format!(
        "Expand-Archive -Force -LiteralPath {} -DestinationPath {}",
        ps_quote(zip),
        ps_quote(&tmp)
    );
    let status = std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", script.as_str()])
        .status()
        .map_err(|e| e.to_string())?;
    if !status.success() {
        return Err(tr("Impossible d'ouvrir l'archive téléchargée"));
    }
    let new = find(&tmp, "nautile.exe").ok_or_else(|| tr("Pas de nautile.exe dans l'archive"))?;
    let old = exe.with_extension("old");
    let _ = std::fs::remove_file(&old);
    std::fs::rename(&exe, &old).map_err(|e| e.to_string())?;
    if let Err(e) = std::fs::copy(&new, &exe) {
        let _ = std::fs::rename(&old, &exe);
        return Err(e.to_string());
    }
    let _ = std::fs::remove_dir_all(&tmp);
    std::process::Command::new(&exe).spawn().map_err(|e| e.to_string())?;
    Ok(())
}

#[cfg(windows)]
fn find(dir: &Path, name: &str) -> Option<PathBuf> {
    for e in std::fs::read_dir(dir).ok()?.flatten() {
        let p = e.path();
        if p.is_dir() {
            if let Some(f) = find(&p, name) {
                return Some(f);
            }
        } else if p.file_name().is_some_and(|f| f.eq_ignore_ascii_case(name)) {
            return Some(p);
        }
    }
    None
}

#[cfg(not(windows))]
fn replace_portable(_zip: &Path) -> Result<(), String> {
    Err(tr("Mode portable : Windows seulement"))
}

/// [path] as a PowerShell single-quoted string: its quotes doubled (PowerShell also takes the typographic ones
/// for quotes).
#[cfg_attr(not(windows), allow(dead_code))]
fn ps_quote(path: &Path) -> String {
    let mut out = String::from("'");
    for c in path.to_string_lossy().chars() {
        if matches!(c, '\'' | '\u{2018}' | '\u{2019}' | '\u{201A}' | '\u{201B}') {
            out.push(c);
        }
        out.push(c);
    }
    out.push('\'');
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths_are_quoted_for_powershell() {
        assert_eq!(ps_quote(Path::new(r"C:\Users\Jean Dupont\x.zip")), r"'C:\Users\Jean Dupont\x.zip'");
        assert_eq!(ps_quote(Path::new("C:/O'Brien/x.zip")), "'C:/O''Brien/x.zip'");
        assert_eq!(ps_quote(Path::new("C:/a\u{2019}b")), "'C:/a\u{2019}\u{2019}b'");
    }
}
