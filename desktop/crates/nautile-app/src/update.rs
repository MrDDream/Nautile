//! « Mises à jour » on the desktop: a check at start (once a day at most), a pop-up with the release notes,
//! then the right file is downloaded, checked against the release's checksums and installed.

use std::path::{Path, PathBuf};

use iced::futures::SinkExt;
use iced::widget::markdown;
use iced::Task;
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
}

#[derive(Debug, Clone)]
pub enum UpdateMsg {
    /// Checks GitHub; `true` from « Vérifier maintenant » (says « à jour » too).
    Check(bool),
    Checked(bool, Result<Release, String>),
    Download,
    Progress(u64, u64),
    Downloaded(Result<PathBuf, String>),
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
        if portable {
            Target::WindowsPortable
        } else {
            Target::WindowsSetup
        }
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
                        let _ = self.settings.save(&self.paths);
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
                let sums = release
                    .assets
                    .iter()
                    .find(|a| a.name == "SHA256SUMS-desktop.txt")
                    .map(|a| a.browser_download_url.clone());
                let dir = dirs::download_dir().unwrap_or_else(std::env::temp_dir);
                let dest = dir.join(&asset.name);
                self.updates.progress = Some((0, asset.size));
                self.updates.status = None;
                return Task::run(download(asset.browser_download_url, asset.name, sums, dest), Message::Update);
            }
            UpdateMsg::Progress(done, total) => self.updates.progress = Some((done, total)),
            UpdateMsg::Downloaded(r) => {
                self.updates.progress = None;
                match r {
                    Ok(path) => return self.install(&path),
                    Err(e) => self.updates.status = Some(Err(e)),
                }
            }
            UpdateMsg::Skip => {
                self.settings.update_skipped = self.updates.available.as_ref().map(|r| r.version().to_owned());
                let _ = self.settings.save(&self.paths);
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
                let _ = opener::open(url);
            }
            UpdateMsg::AutoCheck(v) => {
                self.settings.update_check = Some(v);
                let _ = self.settings.save(&self.paths);
            }
        }
        Task::none()
    }

    /// Hands the downloaded file to the system: the Windows installer (then Nautile quits), the portable exe
    /// replaced in place, a package opened with the system's installer, or the folder of the archive.
    fn install(&mut self, path: &Path) -> Task<Message> {
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
                let _ = opener::open(path.parent().unwrap_or(path));
                self.updates.status = Some(Ok(tr("Archive téléchargée : remplacez l'ancien dossier par celui-ci.")));
                Task::none()
            }
            Target::Deb | Target::Rpm | Target::Mac(_) => {
                let _ = opener::open(path);
                self.updates.status = Some(Ok(tr(
                    "Le paquet est ouvert dans l'installeur du système. Redémarrez Nautile ensuite.",
                )));
                Task::none()
            }
        }
    }
}

/// The download in a thread, its progress (a few steps, not every block) and its checksum check.
fn download(url: String, name: String, sums: Option<String>, dest: PathBuf) -> impl iced::futures::Stream<Item = UpdateMsg> {
    iced::stream::channel(32, async move |mut out: iced::futures::channel::mpsc::Sender<UpdateMsg>| {
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        std::thread::spawn(move || {
            let progress = tx.clone();
            let mut shown = 0u64;
            let result = (|| -> Result<PathBuf, String> {
                let expected = match &sums {
                    Some(u) => update::expected_sha256(&update::fetch_text(u)?, &name),
                    None => None,
                };
                let got = update::download(&url, &dest, |done, total| {
                    let step = (total / 100).max(256 * 1024);
                    if done >= shown + step || done == total {
                        shown = done;
                        let _ = progress.send(UpdateMsg::Progress(done, total));
                    }
                })?;
                if let Some(want) = expected {
                    if want != got {
                        let _ = std::fs::remove_file(&dest);
                        return Err(tr(
                            "Le fichier téléchargé ne correspond pas à la somme de contrôle de la version : il a été supprimé.",
                        ));
                    }
                }
                Ok(dest)
            })();
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
    let status = std::process::Command::new("powershell")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "Expand-Archive",
            "-Force",
            "-LiteralPath",
        ])
        .arg(zip)
        .arg("-DestinationPath")
        .arg(&tmp)
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
