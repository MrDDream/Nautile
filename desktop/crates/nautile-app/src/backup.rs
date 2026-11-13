//! Backups to several places, each on its own schedule; synchronisation with the other devices through one of
//! them (download, merge, upload) and straight over the local network. Same behaviour and files as Android
//! (`AutoBackup.kt`, `SyncManager.kt`, `LanSync.kt`).

use std::path::PathBuf;

use chrono::{Datelike, Local, TimeZone};
use iced::Task;
use nautile_core::model::{now_ms, BackupData, SyncDevice};
use nautile_core::{backup, sync, tr, trf};
use nautile_net::lan_sync::{Found, Lan, LanEvent};
use nautile_net::store::{Dest, Remote, StoreError, KEEP};
use serde::{Deserialize, Serialize};

use crate::app::{App, Message};

pub const PASSWORD: &str = "backup-password";
const LAN_SECRET: &str = "lan-secret";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum Frequency {
    Hours6,
    Hours12,
    Day,
    #[default]
    Week,
    Month,
}

impl Frequency {
    pub const ALL: [Frequency; 5] = [
        Frequency::Hours6,
        Frequency::Hours12,
        Frequency::Day,
        Frequency::Week,
        Frequency::Month,
    ];

    pub fn hours(self) -> i64 {
        match self {
            Frequency::Hours6 => 6,
            Frequency::Hours12 => 12,
            Frequency::Day => 24,
            Frequency::Week => 24 * 7,
            Frequency::Month => 24 * 30,
        }
    }

    pub fn at_time(self) -> bool {
        self.hours() >= 24
    }

    pub fn label(self) -> String {
        match self {
            Frequency::Hours6 => tr("Toutes les 6 h"),
            Frequency::Hours12 => tr("Toutes les 12 h"),
            Frequency::Day => tr("Chaque jour"),
            Frequency::Week => tr("Chaque semaine"),
            Frequency::Month => tr("Chaque mois"),
        }
    }
}

/// One place backups go, with its own schedule and status.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Target {
    pub id: String,
    pub dest: Dest,
    #[serde(default)]
    pub auto: bool,
    #[serde(default)]
    pub frequency: Frequency,
    /// Minutes after midnight for the daily and longer ones.
    #[serde(default = "three_am")]
    pub time: u32,
    /// ISO day (1 = Monday) for the weekly one.
    #[serde(default = "sunday")]
    pub weekday: u32,
    #[serde(default)]
    pub last: Option<i64>,
    #[serde(default)]
    pub error: Option<String>,
}

fn three_am() -> u32 {
    180
}

fn sunday() -> u32 {
    7
}

/// A device paired for the Wi-Fi synchronisation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Peer {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub last: i64,
}

/// When the next automatic backup is due: one period after the last one, at the chosen time (and weekday) for
/// the daily and longer ones (Android's `AutoBackup.nextRun`).
pub fn next_run(f: Frequency, minutes: u32, weekday: u32, last: Option<i64>, now: i64) -> i64 {
    let period = f.hours() * 3_600_000;
    let slack = if f == Frequency::Month { 2 * 24 * 3_600_000 } else { period / 2 };
    let earliest = now.max(last.unwrap_or(0) + period - slack);
    if !f.at_time() {
        return (now + 15 * 60_000).max(last.unwrap_or(now) + period);
    }
    let Some(start) = Local.timestamp_millis_opt(earliest).single() else {
        return earliest;
    };
    let mut day = start.date_naive();
    for _ in 0..40 {
        let at = day
            .and_hms_opt(minutes / 60, minutes % 60, 0)
            .and_then(|t| Local.from_local_datetime(&t).earliest())
            .map(|t| t.timestamp_millis());
        if let Some(at) = at {
            if at >= earliest && (f != Frequency::Week || day.weekday().number_from_monday() == weekday) {
                return at;
            }
        }
        day = day.succ_opt().unwrap_or(day);
    }
    earliest
}

/// Copies of the last backups kept on this computer too (already encrypted).
pub fn versions_dir(app: &App) -> PathBuf {
    app.paths.data.join("backup-versions")
}

/// Newest first: (file, time in ms, size).
pub fn versions(app: &App) -> Vec<(PathBuf, i64, u64)> {
    let mut v: Vec<_> = std::fs::read_dir(versions_dir(app))
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().into_owned();
            let at: i64 = name.strip_suffix(".nautile")?.parse().ok()?;
            Some((e.path(), at, e.metadata().map(|m| m.len()).unwrap_or(0)))
        })
        .collect();
    v.sort_by_key(|a| std::cmp::Reverse(a.1));
    v
}

fn add_version(dir: PathBuf, bytes: &[u8], min_age_ms: i64) {
    let now = now_ms();
    let _ = std::fs::create_dir_all(&dir);
    let mut existing: Vec<i64> = std::fs::read_dir(&dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| e.file_name().to_string_lossy().strip_suffix(".nautile")?.parse().ok())
        .collect();
    existing.sort_unstable_by(|a, b| b.cmp(a));
    if existing.first().is_some_and(|t| now - t < min_age_ms) {
        return;
    }
    let _ = std::fs::write(dir.join(format!("{now}.nautile")), bytes);
    for old in existing.iter().skip(KEEP - 1) {
        let _ = std::fs::remove_file(dir.join(format!("{old}.nautile")));
    }
}

/// A new random device id (UUID v4, like Android).
fn uuid() -> String {
    let mut b: [u8; 16] = rand::random();
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let h = hex::encode(b);
    format!("{}-{}-{}-{}-{}", &h[..8], &h[8..12], &h[12..16], &h[16..20], &h[20..])
}

/// This computer's name, shown on the other devices.
pub fn device_name() -> String {
    let from_env = ["COMPUTERNAME", "HOSTNAME"]
        .iter()
        .find_map(|v| std::env::var(v).ok().filter(|s| !s.is_empty()));
    from_env
        .or_else(|| {
            std::fs::read_to_string("/etc/hostname")
                .ok()
                .map(|s| s.trim().to_owned())
                .filter(|s| !s.is_empty())
        })
        .unwrap_or_else(|| "Nautile Desktop".into())
}

/// The place being added in the settings.
#[derive(Debug, Clone, Default)]
pub struct PlaceForm {
    pub kind: PlaceKind,
    pub path: String,
    pub url: String,
    pub username: String,
    pub secret: String,
    pub endpoint: String,
    pub region: String,
    pub bucket: String,
    pub key: String,
    pub access_key: String,
    pub busy: bool,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PlaceKind {
    #[default]
    Folder,
    WebDav,
    S3,
}

impl PlaceForm {
    pub fn dest(&self) -> Option<Dest> {
        match self.kind {
            PlaceKind::Folder => (!self.path.trim().is_empty()).then(|| Dest::Folder {
                path: PathBuf::from(self.path.trim()),
            }),
            PlaceKind::WebDav => (self.url.trim().starts_with("http")).then(|| Dest::WebDav {
                url: self.url.trim().to_owned(),
                username: self.username.trim().to_owned(),
            }),
            PlaceKind::S3 => (!self.endpoint.trim().is_empty() && !self.bucket.trim().is_empty() && !self.access_key.trim().is_empty())
                .then(|| Dest::S3 {
                    endpoint: self.endpoint.trim().to_owned(),
                    region: self.region.trim().to_owned(),
                    bucket: self.bucket.trim().to_owned(),
                    key: if self.key.trim().is_empty() {
                        "nautile.backup".into()
                    } else {
                        self.key.trim().to_owned()
                    },
                    access_key: self.access_key.trim().to_owned(),
                }),
        }
    }
}

/// Everything about backups and synchronisation shown in the settings.
#[derive(Default)]
pub struct BackupState {
    pub place: Option<PlaceForm>,
    /// The place whose settings are open.
    pub open: Option<String>,
    pub password: String,
    pub password_confirm: String,
    pub running: Vec<String>,
    pub syncing: bool,
    sync_attempt: u32,
    pub lan: Option<Lan>,
    pub lan_found: Vec<Found>,
    pub lan_code: Option<String>,
    pub lan_status: Option<String>,
    /// The device being paired and the code typed.
    pub lan_pairing: Option<(Found, String)>,
    pub lan_busy: bool,
    lan_synced_change: i64,
    lan_last_round: i64,
    /// When each automatic backup runs next (computed once, again after it ran or its settings changed).
    scheduled: std::collections::HashMap<String, i64>,
    /// « Seulement via le VPN » and no VPN is up: the synchronisation waits.
    pub waiting_vpn: bool,
    /// The network was unreachable: the next try (silent) and how many came before.
    pub retry_at: Option<i64>,
    net_failures: u32,
    /// The pairing pop-up is on « Saisir un code ».
    pub lan_join: bool,
}

/// Whether an interface name is a VPN's: WireGuard, OpenVPN, Tailscale, ZeroTier, IPsec, PPP… on Linux,
/// Windows and macOS.
pub fn is_vpn_interface(name: &str) -> bool {
    let n = name.to_lowercase();
    [
        "wg",
        "tun",
        "tap",
        "utun",
        "ppp",
        "ipsec",
        "tailscale",
        "zt",
        "nordlynx",
        "proton",
        "mullvad",
    ]
    .iter()
    .any(|p| n.starts_with(p))
        || ["wireguard", "openvpn", "vpn", "tailscale", "zerotier", "tap-windows", "wintun"]
            .iter()
            .any(|w| n.contains(w))
}

/// A VPN is up on this computer.
pub fn vpn_up() -> bool {
    if_addrs::get_if_addrs()
        .map(|list| list.iter().any(|i| !i.is_loopback() && is_vpn_interface(&i.name)))
        .unwrap_or(false)
}

/// Whether an error means the server could not be reached (try again later, without alarming).
pub fn is_network_error(e: &str) -> bool {
    let e = e.to_lowercase();
    [
        "dns",
        "resolve",
        "lookup",
        "not known",
        "connection refused",
        "refused",
        "timed out",
        "timeout",
        "unreachable",
        "network",
        "no route",
        "connect",
        "host not found",
        "io:",
    ]
    .iter()
    .any(|w| e.contains(w))
}

#[derive(Debug, Clone)]
pub enum BackupMsg {
    PasswordInput(String),
    PasswordConfirm(String),
    SavePassword,
    NewPlace(PlaceKind),
    Place(PlaceField),
    PickFolder,
    FolderPicked(Option<PathBuf>),
    AddPlace,
    PlaceChecked(Result<(), String>),
    CancelPlace,
    Open(Option<String>),
    Remove(String),
    Auto(String, bool),
    Frequency(String, Frequency),
    Time(String, String),
    Weekday(String, u32),
    Now(String),
    Done(String, Result<(), String>),
    SyncEnabled(bool),
    SyncTarget(String),
    SyncMinutes(u32),
    SyncNow,
    Fetched(Box<Result<(Remote, Option<BackupData>), String>>),
    Written(Result<i64, StoreError>, Vec<SyncDevice>),
    RestoreVersion(PathBuf),
    LanEnabled(bool),
    Lan(LanEvent),
    LanShowCode,
    LanCancelCode,
    LanPairWith(Found),
    LanCode(String),
    LanPair,
    LanPaired(Result<(), String>),
    LanSyncNow,
    LanRound(usize),
    LanForget(String),
    /// « Sauvegarder partout ».
    NowAll,
    /// The pairing pop-up: « Saisir un code » (true) or the choice.
    LanJoin(bool),
    /// « Seulement via le VPN ».
    VpnOnly(bool),
}

#[derive(Debug, Clone)]
pub enum PlaceField {
    Path(String),
    Url(String),
    Username(String),
    Secret(String),
    Endpoint(String),
    Region(String),
    Bucket(String),
    Key(String),
    AccessKey(String),
}

fn run<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static, done: impl Fn(T) -> BackupMsg + Send + 'static) -> Task<Message> {
    Task::perform(async move { tokio::task::spawn_blocking(f).await }, move |r| match r {
        Ok(v) => Message::Backup(done(v)),
        Err(e) => Message::Backup(BackupMsg::Done(String::new(), Err(e.to_string()))),
    })
}

impl App {
    pub fn device_id(&mut self) -> String {
        if let Some(id) = &self.settings.device_id {
            return id.clone();
        }
        let id = uuid();
        self.settings.device_id = Some(id.clone());
        self.save_settings();
        id
    }

    pub fn save_settings(&self) {
        let _ = self.settings.save(&self.paths);
    }

    fn secret(&self, name: &str) -> Option<String> {
        self.db.as_ref().and_then(|db| db.secret(name))
    }

    pub fn has_backup_password(&self) -> bool {
        self.secret(PASSWORD).is_some()
    }

    fn backup_target(&self, id: &str) -> Option<&crate::backup::Target> {
        self.settings.targets.iter().find(|t| t.id == id)
    }

    fn target_mut(&mut self, id: &str) -> Option<&mut crate::backup::Target> {
        self.settings.targets.iter_mut().find(|t| t.id == id)
    }

    /// Starts what the settings ask for when the app opens.
    pub fn backup_boot(&mut self) -> Task<Message> {
        if self.settings.lan_enabled {
            return self.lan_start();
        }
        Task::none()
    }

    /// Every half minute: due backups, synchronisation, Wi-Fi rounds.
    pub fn backup_tick(&mut self) -> Task<Message> {
        if self.db.is_none() || !self.has_backup_password() {
            return self.lan_tick();
        }
        let now = now_ms();
        let mut tasks = Vec::new();
        let sync_target = self.settings.sync_target.clone().filter(|_| self.settings.sync_enabled);
        let mut due = Vec::new();
        for t in &self.settings.targets {
            if !t.auto || Some(&t.id) == sync_target.as_ref() || self.backup.running.contains(&t.id) {
                continue;
            }
            // Never backed up here, every few hours: the first one now.
            let at = *self.backup.scheduled.entry(t.id.clone()).or_insert_with(|| {
                if t.last.is_none() && !t.frequency.at_time() {
                    now
                } else {
                    next_run(t.frequency, t.time, t.weekday, t.last, now)
                }
            });
            if at <= now {
                due.push(t.id.clone());
            }
        }
        for id in due {
            tasks.push(self.backup_now(id));
        }
        let vpn_wait = sync_target.is_some() && self.settings.sync_vpn_only && !vpn_up();
        if vpn_wait != self.backup.waiting_vpn {
            self.backup.waiting_vpn = vpn_wait;
            if !vpn_wait {
                // The VPN came up: now, without waiting for the next retry.
                self.backup.retry_at = None;
            }
        }
        let retry_later = self.backup.retry_at.is_some_and(|at| now < at);
        if sync_target.is_some() && !self.backup.syncing && !vpn_wait && !retry_later {
            let last_change = self.db.as_ref().and_then(|db| db.store().last_change().ok()).unwrap_or(0);
            let interval = self.settings.sync_minutes() as i64 * 60_000;
            let dirty = last_change > self.settings.synced_change;
            if dirty || self.settings.last_sync.is_none_or(|l| now - l >= interval) {
                tasks.push(self.sync_start());
            }
        }
        tasks.push(self.lan_tick());
        Task::batch(tasks)
    }

    fn lan_tick(&mut self) -> Task<Message> {
        let Some(lan) = self.backup.lan.clone() else { return Task::none() };
        if self.settings.lan_peers.is_empty() || self.backup.lan_busy {
            return Task::none();
        }
        let now = now_ms();
        let last_change = self.db.as_ref().and_then(|db| db.store().last_change().ok()).unwrap_or(0);
        let paired: Vec<String> = self.settings.lan_peers.iter().map(|p| p.id.clone()).collect();
        let nearby = self.backup.lan_found.iter().any(|f| paired.contains(&f.id));
        if !nearby || (last_change <= self.backup.lan_synced_change && now - self.backup.lan_last_round < 15 * 60_000) {
            return Task::none();
        }
        self.backup.lan_busy = true;
        self.backup.lan_last_round = now;
        self.backup.lan_synced_change = last_change;
        run(move || lan.sync_all(&paired), BackupMsg::LanRound)
    }

    /// Writes a backup to [id] now (the synchronised place is synchronised instead).
    fn backup_now(&mut self, id: String) -> Task<Message> {
        if self.settings.sync_enabled && self.settings.sync_target.as_ref() == Some(&id) {
            return self.sync_start();
        }
        let (Some(db), Some(t)) = (&self.db, self.backup_target(&id).cloned()) else {
            return Task::none();
        };
        let Some(pw) = db.secret(PASSWORD) else {
            return self.say(tr("Mot de passe de sauvegarde non enregistré"));
        };
        let Ok(mut data) = sync::snapshot(db, vec![]) else {
            return Task::none();
        };
        data.tombstones.clear();
        let secret = db.secret(&format!("target-{id}")).unwrap_or_default();
        let versions = versions_dir(self);
        self.backup.running.push(id.clone());
        let done_id = id.clone();
        run(
            move || -> Result<(), String> {
                let text = backup::export(&data, &pw).map_err(|e| e.to_string())?;
                add_version(versions, text.as_bytes(), 0);
                let store = t.dest.open(&secret);
                store.rotate();
                store.write(text.as_bytes(), None).map_err(|e| e.to_string())
            },
            move |r| BackupMsg::Done(done_id.clone(), r),
        )
    }

    /// Synchronisation, step 1: read the shared file and decrypt it.
    fn sync_start(&mut self) -> Task<Message> {
        if self.backup.syncing {
            return Task::none();
        }
        let Some(t) = self.settings.sync_target.clone().and_then(|id| self.backup_target(&id).cloned()) else {
            return self.say(tr("Aucun emplacement de synchronisation choisi"));
        };
        let Some(pw) = self.secret(PASSWORD) else {
            return self.say(tr("Mot de passe de sauvegarde non enregistré"));
        };
        let secret = self.secret(&format!("target-{}", t.id)).unwrap_or_default();
        self.backup.syncing = true;
        run(
            move || -> Result<(Remote, Option<BackupData>), String> {
                let remote = t.dest.open(&secret).read().map_err(|e| e.to_string())?;
                let data = match &remote.text {
                    Some(text) => Some(backup::import(text, &pw).map_err(|e| match e {
                        backup::BackupError::WrongPassword(_) => tr("Le mot de passe ne déchiffre pas le fichier de synchronisation"),
                        e => e.to_string(),
                    })?),
                    None => None,
                };
                Ok((remote, data))
            },
            |r| BackupMsg::Fetched(Box::new(r)),
        )
    }

    /// Step 2: merge here, then write the merged file back (only over what was read).
    fn sync_fetched(&mut self, r: Result<(Remote, Option<BackupData>), String>) -> Task<Message> {
        let (remote, data) = match r {
            Ok(v) => v,
            Err(e) => return self.sync_failed(e),
        };
        let device = self.device_id();
        let Some(db) = &mut self.db else { return Task::none() };
        let dirty_before = db.store().last_change().unwrap_or(0) > self.settings.synced_change;
        let stats = match &data {
            Some(d) => match sync::merge(db, d) {
                Ok(s) => s,
                Err(e) => return self.sync_failed(e.to_string()),
            },
            None => sync::Stats::default(),
        };
        let now = now_ms();
        let me = SyncDevice {
            id: device.clone(),
            name: device_name(),
            last_seen: now,
            extra: Default::default(),
        };
        let theirs = data.as_ref().map(|d| d.devices.clone()).unwrap_or_default();
        let my_last = theirs.iter().find(|d| d.id == device).map(|d| d.last_seen).unwrap_or(0);
        let mut devices: Vec<SyncDevice> = theirs
            .into_iter()
            .filter(|d| d.id != device && now - d.last_seen < 180 * 24 * 3_600_000)
            .collect();
        devices.push(me);
        devices.sort_by_key(|d| std::cmp::Reverse(d.last_seen));
        let changed = stats.added + stats.updated + stats.deleted > 0;
        if changed {
            self.reload();
        }
        let Some(db) = &self.db else { return Task::none() };
        if data.is_some() && !dirty_before && !changed && now - my_last <= 12 * 3_600_000 {
            return self.backup_update(BackupMsg::Written(Ok(db.store().last_change().unwrap_or(0)), devices));
        }
        let Ok(snapshot) = sync::snapshot(db, devices.clone()) else {
            return Task::none();
        };
        let Some(pw) = db.secret(PASSWORD) else { return Task::none() };
        let Some(t) = self.settings.sync_target.clone().and_then(|id| self.backup_target(&id).cloned()) else {
            return Task::none();
        };
        let secret = db.secret(&format!("target-{}", t.id)).unwrap_or_default();
        let change = db.store().last_change().unwrap_or(0);
        let versions = versions_dir(self);
        run(
            move || -> Result<i64, StoreError> {
                let text = backup::export(&snapshot, &pw).map_err(|e| StoreError::Other(e.to_string()))?;
                t.dest.open(&secret).write(text.as_bytes(), Some(&remote))?;
                // The synchronised file is also the backup: a daily copy here.
                add_version(versions, text.as_bytes(), 20 * 3_600_000);
                Ok(change)
            },
            move |r| BackupMsg::Written(r, devices.clone()),
        )
    }

    fn sync_failed(&mut self, e: String) -> Task<Message> {
        self.backup.syncing = false;
        self.backup.sync_attempt = 0;
        if is_network_error(&e) {
            // A VPN not up yet, a server asleep: again in 1, 2, then 5 minutes, without a red error.
            let wait = [1, 2, 5][self.backup.net_failures.min(2) as usize] * 60_000;
            self.backup.net_failures += 1;
            self.backup.retry_at = Some(now_ms() + wait);
            if self.backup.net_failures < 4 {
                return Task::none();
            }
        }
        self.notify(tr("Synchronisation échouée"), e.clone());
        self.settings.sync_error = Some(e);
        self.save_settings();
        Task::none()
    }

    fn lan_start(&mut self) -> Task<Message> {
        if self.backup.lan.is_some() {
            return Task::none();
        }
        let id = self.device_id();
        let secret = self.secret(LAN_SECRET).and_then(|s| {
            use base64::Engine;
            base64::engine::general_purpose::STANDARD.decode(s).ok()
        });
        match Lan::start(id, device_name(), secret, true) {
            Ok((lan, rx)) => {
                self.backup.lan = Some(lan);
                let stream = iced::futures::stream::unfold(rx, |mut rx| async move { rx.recv().await.map(|e| (e, rx)) });
                Task::run(stream, |e| Message::Backup(BackupMsg::Lan(e)))
            }
            Err(e) => {
                self.backup.lan_status = Some(e.to_string());
                Task::none()
            }
        }
    }

    pub fn backup_update(&mut self, m: BackupMsg) -> Task<Message> {
        match m {
            BackupMsg::PasswordInput(v) => self.backup.password = v,
            BackupMsg::PasswordConfirm(v) => self.backup.password_confirm = v,
            BackupMsg::SavePassword => {
                let pw = self.backup.password.clone();
                if pw.chars().count() < 8 || pw != self.backup.password_confirm {
                    return Task::none();
                }
                if let Some(db) = &self.db {
                    let _ = db.set_secret(PASSWORD, Some(&pw));
                }
                self.backup.password.clear();
                self.backup.password_confirm.clear();
                return self.say(tr("Mot de passe de sauvegarde enregistré"));
            }
            BackupMsg::NewPlace(kind) => {
                self.backup.place = Some(PlaceForm {
                    kind,
                    region: "us-east-1".into(),
                    ..Default::default()
                })
            }
            BackupMsg::Place(f) => {
                if let Some(p) = &mut self.backup.place {
                    p.error = None;
                    match f {
                        PlaceField::Path(v) => p.path = v,
                        PlaceField::Url(v) => p.url = v,
                        PlaceField::Username(v) => p.username = v,
                        PlaceField::Secret(v) => p.secret = v,
                        PlaceField::Endpoint(v) => p.endpoint = v,
                        PlaceField::Region(v) => p.region = v,
                        PlaceField::Bucket(v) => p.bucket = v,
                        PlaceField::Key(v) => p.key = v,
                        PlaceField::AccessKey(v) => p.access_key = v,
                    }
                }
            }
            BackupMsg::PickFolder => {
                return Task::perform(
                    async {
                        rfd::AsyncFileDialog::new()
                            .set_title(tr("Dossier de sauvegarde"))
                            .pick_folder()
                            .await
                            .map(|f| f.path().to_path_buf())
                    },
                    |p| Message::Backup(BackupMsg::FolderPicked(p)),
                )
            }
            BackupMsg::FolderPicked(p) => {
                if let (Some(p), Some(f)) = (p, &mut self.backup.place) {
                    f.path = p.display().to_string();
                }
            }
            BackupMsg::AddPlace => {
                let Some(f) = &mut self.backup.place else { return Task::none() };
                let Some(dest) = f.dest() else { return Task::none() };
                f.busy = true;
                let secret = f.secret.clone();
                return run(
                    move || dest.open(&secret).probe().map_err(|e| e.to_string()),
                    BackupMsg::PlaceChecked,
                );
            }
            BackupMsg::PlaceChecked(r) => {
                let Some(f) = &mut self.backup.place else { return Task::none() };
                f.busy = false;
                match r {
                    Err(e) => f.error = Some(e),
                    Ok(()) => {
                        let Some(dest) = f.dest() else { return Task::none() };
                        let id = hex::encode(rand::random::<[u8; 6]>());
                        if let Some(db) = &self.db {
                            if !f.secret.is_empty() {
                                let _ = db.set_secret(&format!("target-{id}"), Some(&f.secret));
                            }
                        }
                        self.settings.targets.push(Target {
                            id: id.clone(),
                            dest,
                            auto: false,
                            frequency: Frequency::Week,
                            time: three_am(),
                            weekday: sunday(),
                            last: None,
                            error: None,
                        });
                        if self.settings.sync_target.is_none() {
                            self.settings.sync_target = Some(id.clone());
                        }
                        self.backup.place = None;
                        self.backup.open = Some(id);
                        self.modal = Some(crate::app::Modal::Target);
                        self.save_settings();
                        return self.say(tr("Emplacement ajouté"));
                    }
                }
            }
            BackupMsg::CancelPlace => self.backup.place = None,
            BackupMsg::Open(id) => {
                self.modal = id.is_some().then_some(crate::app::Modal::Target);
                self.backup.open = id;
            }
            BackupMsg::Remove(id) => {
                self.settings.targets.retain(|t| t.id != id);
                if self.settings.sync_target.as_ref() == Some(&id) {
                    self.settings.sync_target = self.settings.targets.first().map(|t| t.id.clone());
                }
                if let Some(db) = &self.db {
                    let _ = db.set_secret(&format!("target-{id}"), None);
                }
                self.backup.open = None;
                self.modal = None;
                self.save_settings();
            }
            BackupMsg::Auto(id, v) => {
                self.backup.scheduled.remove(&id);
                if let Some(t) = self.target_mut(&id) {
                    t.auto = v;
                }
                self.save_settings();
            }
            BackupMsg::Frequency(id, f) => {
                self.backup.scheduled.remove(&id);
                if let Some(t) = self.target_mut(&id) {
                    t.frequency = f;
                }
                self.save_settings();
            }
            BackupMsg::Time(id, v) => {
                self.backup.scheduled.remove(&id);
                let parsed = v
                    .split_once(':')
                    .and_then(|(h, m)| Some(h.trim().parse::<u32>().ok()? * 60 + m.trim().parse::<u32>().ok()?));
                if let (Some(t), Some(min)) = (self.target_mut(&id), parsed.filter(|m| *m < 24 * 60)) {
                    t.time = min;
                }
                self.save_settings();
            }
            BackupMsg::Weekday(id, d) => {
                self.backup.scheduled.remove(&id);
                if let Some(t) = self.target_mut(&id) {
                    t.weekday = d;
                }
                self.save_settings();
            }
            BackupMsg::Now(id) => return self.backup_now(id),
            BackupMsg::NowAll => {
                let ids: Vec<String> = self.settings.targets.iter().map(|t| t.id.clone()).collect();
                return Task::batch(ids.into_iter().map(|id| self.backup_now(id)));
            }
            BackupMsg::LanJoin(v) => self.backup.lan_join = v,
            BackupMsg::VpnOnly(v) => {
                self.settings.sync_vpn_only = v;
                self.backup.retry_at = None;
                self.save_settings();
                return self.backup_tick();
            }
            BackupMsg::Done(id, r) => {
                self.backup.running.retain(|x| *x != id);
                self.backup.scheduled.remove(&id);
                let ok = r.is_ok();
                if let Some(t) = self.target_mut(&id) {
                    match &r {
                        Ok(()) => {
                            t.last = Some(now_ms());
                            t.error = None;
                        }
                        Err(e) => t.error = Some(e.clone()),
                    }
                }
                self.save_settings();
                return match r {
                    Ok(()) if ok => self.say(tr("Sauvegarde effectuée")),
                    Err(e) => {
                        self.notify(tr("Sauvegarde échouée"), e.clone());
                        self.say(trf("Échec : %1$s", &[&e]))
                    }
                    _ => Task::none(),
                };
            }
            BackupMsg::SyncEnabled(v) => {
                self.settings.sync_enabled = v;
                self.save_settings();
                if v {
                    return self.sync_start();
                }
            }
            BackupMsg::SyncTarget(id) => {
                self.settings.sync_target = Some(id);
                self.settings.synced_change = 0;
                self.save_settings();
            }
            BackupMsg::SyncMinutes(m) => {
                self.settings.sync_minutes = Some(m);
                self.save_settings();
            }
            BackupMsg::SyncNow => return self.sync_start(),
            BackupMsg::Fetched(r) => return self.sync_fetched(*r),
            BackupMsg::Written(r, devices) => match r {
                Ok(change) => {
                    let now = now_ms();
                    self.backup.syncing = false;
                    self.backup.sync_attempt = 0;
                    self.backup.net_failures = 0;
                    self.backup.retry_at = None;
                    self.settings.synced_change = change;
                    self.settings.last_sync = Some(now);
                    self.settings.sync_error = None;
                    self.settings.devices = devices.into_iter().map(|d| (d.id, d.name, d.last_seen)).collect();
                    if let Some(id) = self.settings.sync_target.clone() {
                        if let Some(t) = self.target_mut(&id) {
                            t.last = Some(now);
                            t.error = None;
                        }
                    }
                    self.save_settings();
                }
                Err(StoreError::Conflict) if self.backup.sync_attempt < 2 => {
                    // Another device wrote in between: start again from its version.
                    self.backup.sync_attempt += 1;
                    self.backup.syncing = false;
                    return self.sync_start();
                }
                Err(StoreError::Conflict) => return self.sync_failed(tr("Conflit d'écriture répété avec un autre appareil")),
                Err(StoreError::Other(e)) => return self.sync_failed(e),
            },
            BackupMsg::RestoreVersion(path) => {
                let Some(pw) = self.secret(PASSWORD) else {
                    return self.say(tr("Mot de passe de sauvegarde non enregistré"));
                };
                return Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            let text = std::fs::read_to_string(path).map_err(|e| e.to_string())?;
                            backup::import(&text, &pw).map_err(|e| e.to_string())
                        })
                        .await
                        .map_err(|e| e.to_string())
                        .and_then(|r| r)
                    },
                    |r| Message::ImportDecoded(Box::new(r)),
                );
            }
            BackupMsg::LanEnabled(v) => {
                self.settings.lan_enabled = v;
                self.save_settings();
                if v {
                    return self.lan_start();
                }
                if let Some(l) = self.backup.lan.take() {
                    l.stop();
                }
                self.backup.lan_found.clear();
                self.backup.lan_code = None;
            }
            BackupMsg::Lan(e) => return self.on_lan(e),
            BackupMsg::LanShowCode => self.backup.lan_code = self.backup.lan.as_ref().map(|l| l.show_code()),
            BackupMsg::LanCancelCode => {
                if let Some(l) = &self.backup.lan {
                    l.cancel_code();
                }
                self.backup.lan_code = None;
            }
            BackupMsg::LanPairWith(f) => self.backup.lan_pairing = Some((f, String::new())),
            BackupMsg::LanCode(v) => {
                if let Some(p) = &mut self.backup.lan_pairing {
                    p.1 = v.chars().filter(char::is_ascii_digit).take(6).collect();
                }
            }
            BackupMsg::LanPair => {
                let (Some(lan), Some((peer, code))) = (self.backup.lan.clone(), self.backup.lan_pairing.clone()) else {
                    return Task::none();
                };
                self.backup.lan_busy = true;
                return run(
                    move || lan.pair(&peer, &code).map_err(|_| tr("Code refusé ou appareil injoignable")),
                    BackupMsg::LanPaired,
                );
            }
            BackupMsg::LanPaired(r) => {
                self.backup.lan_busy = false;
                match r {
                    Ok(()) => {
                        self.backup.lan_pairing = None;
                        self.backup.lan_synced_change = -1;
                        self.backup.lan_last_round = 0;
                        return self.lan_tick();
                    }
                    Err(e) => self.backup.lan_status = Some(e),
                }
            }
            BackupMsg::LanSyncNow => {
                self.backup.lan_synced_change = -1;
                self.backup.lan_last_round = 0;
                return self.lan_tick();
            }
            BackupMsg::LanRound(_) => self.backup.lan_busy = false,
            BackupMsg::LanForget(id) => {
                self.settings.lan_peers.retain(|p| p.id != id);
                if self.settings.lan_peers.is_empty() {
                    if let Some(db) = &self.db {
                        let _ = db.set_secret(LAN_SECRET, None);
                    }
                    if let Some(l) = &self.backup.lan {
                        l.set_secret(None);
                    }
                }
                self.save_settings();
            }
        }
        Task::none()
    }

    fn on_lan(&mut self, e: LanEvent) -> Task<Message> {
        match e {
            LanEvent::Found(list) => self.backup.lan_found = list,
            LanEvent::Paired { id, name, secret } => {
                use base64::Engine;
                if let Some(db) = &self.db {
                    let _ = db.set_secret(LAN_SECRET, Some(&base64::engine::general_purpose::STANDARD.encode(&secret)));
                }
                self.settings.lan_peers.retain(|p| p.id != id);
                self.settings.lan_peers.push(Peer { id, name, last: 0 });
                self.backup.lan_code = None;
                self.save_settings();
            }
            LanEvent::Snapshot(reply) => {
                let json = self
                    .db
                    .as_ref()
                    .and_then(|db| sync::snapshot(db, vec![]).ok())
                    .and_then(|d| serde_json::to_string(&d).ok())
                    .unwrap_or_default();
                reply.send(json);
            }
            LanEvent::Merge { id, name, json } => {
                let Ok(data) = serde_json::from_str::<BackupData>(&json) else {
                    return Task::none();
                };
                let Some(db) = &mut self.db else { return Task::none() };
                let changed = sync::merge(db, &data).map(|s| s.added + s.updated + s.deleted > 0).unwrap_or(false);
                self.backup.lan_synced_change = db.store().last_change().unwrap_or(0);
                if let Some(p) = self.settings.lan_peers.iter_mut().find(|p| p.id == id) {
                    p.last = now_ms();
                    p.name = name.clone();
                }
                self.save_settings();
                if changed {
                    self.reload();
                    self.backup.lan_status = Some(trf("Synchronisé avec %1$s", &[&name]));
                }
            }
            LanEvent::Status(s) => self.backup.lan_status = Some(s),
        }
        Task::none()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hourly_backups_come_one_period_after_the_last() {
        let now = 1_000_000_000_000;
        assert_eq!(next_run(Frequency::Hours6, 0, 7, Some(now - 3_600_000), now), now + 5 * 3_600_000);
        assert_eq!(next_run(Frequency::Hours6, 0, 7, None, now), now + 6 * 3_600_000);
    }

    #[test]
    fn daily_backups_come_at_the_chosen_time() {
        let now = Local.with_ymd_and_hms(2026, 11, 13, 14, 0, 0).unwrap().timestamp_millis();
        let at = next_run(Frequency::Day, 3 * 60, 7, Some(now - 3_600_000), now);
        let t = Local.timestamp_millis_opt(at).unwrap();
        assert_eq!((t.day(), t.format("%H:%M").to_string()), (14, "03:00".into()));
        // Weekly on Sunday: the coming one (Nov 15th 2026) when the last one is old enough.
        let at = next_run(Frequency::Week, 3 * 60, 7, Some(now - 4 * 24 * 3_600_000), now);
        let t = Local.timestamp_millis_opt(at).unwrap();
        assert_eq!(t.weekday().number_from_monday(), 7);
        assert_eq!(t.day(), 15);
    }

    #[test]
    fn device_ids_are_uuids() {
        let id = uuid();
        assert_eq!(id.len(), 36);
        assert_eq!(&id[14..15], "4");
    }
}

#[cfg(test)]
mod vpn_tests {
    use super::*;

    #[test]
    fn vpn_interfaces_are_recognised_on_every_system() {
        for name in [
            "wg0",
            "tun0",
            "tailscale0",
            "utun3",
            "ppp0",
            "zt7nnig26",
            "WireGuard Tunnel",
            "OpenVPN TAP-Windows6",
            "Tailscale",
            "ProtonVPN",
            "nordlynx",
            "Mullvad",
        ] {
            assert!(is_vpn_interface(name), "{name}");
        }
        for name in [
            "eth0", "enp3s0", "wlan0", "wlp2s0", "lo", "en0", "Ethernet", "Wi-Fi", "docker0", "br-1a2b", "virbr0",
        ] {
            assert!(!is_vpn_interface(name), "{name}");
        }
    }

    #[test]
    fn unreachable_servers_are_retried_silently() {
        assert!(is_network_error("io: Connection refused (os error 111)"));
        assert!(is_network_error("Host not found: cloud.home.lan"));
        assert!(is_network_error("timeout: global"));
        assert!(!is_network_error("Le mot de passe ne déchiffre pas le fichier de synchronisation"));
        assert!(!is_network_error("HTTP 401"));
    }
}
