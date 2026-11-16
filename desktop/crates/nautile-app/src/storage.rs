//! Where Nautile keeps its data, its settings, and the database key.
//!
//! - Installed: `%APPDATA%\Nautile` (Windows), `~/.local/share/nautile` (Linux), `~/Library/Application Support/Nautile`.
//! - Portable: a `portable.txt` (or `portable`) file next to the executable: the whole app then lives in the
//!   `data` folder beside it, on a USB stick.
//!
//! The database key (32 random bytes) goes to the system keyring (Windows Credential Manager, GNOME Keyring /
//! KWallet, macOS Keychain). Without one it is kept in `db.key` next to the database, readable only by this user.
//! In portable mode it is never stored in clear: `db.key.enc` holds it encrypted with the master password
//! (Argon2id + AES-256-GCM, `nautile_core::lock::wrap_key`), asked before the database is opened.

use std::path::{Path, PathBuf};

use rand::Rng;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone)]
pub struct Paths {
    pub data: PathBuf,
    pub portable: bool,
}

impl Paths {
    pub fn detect() -> Paths {
        if let Some(p) = std::env::current_exe()
            .ok()
            .and_then(|e| e.parent().map(Path::to_path_buf))
            .and_then(|dir| Paths::portable_at(&dir))
        {
            return p;
        }
        let base = dirs::data_dir().unwrap_or_else(|| PathBuf::from("."));
        let name = if cfg!(target_os = "linux") { "nautile" } else { "Nautile" };
        Paths {
            data: base.join(name),
            portable: false,
        }
    }

    /// Portable mode in [dir] (the executable's folder): only with the `portable.txt` (or `portable`) marker. A
    /// `data` folder alone no longer switches it on, except when it already holds a database (older copies were
    /// switched on that way): its data would otherwise look lost. That copy is marked once (`data/.portable-migrated`
    /// remembers it: a marker the user removes afterwards is not put back), and its plain key gets encrypted by
    /// the master password asked at this start.
    pub fn portable_at(dir: &Path) -> Option<Paths> {
        let marked = dir.join("portable").exists() || dir.join("portable.txt").exists();
        let data = dir.join("data");
        let witness = data.join(".portable-migrated");
        if !marked && data.join("nautile.db").is_file() && !witness.exists() {
            eprintln!("{}: portable data without portable.txt, marked as portable", data.display());
            let _ = std::fs::write(dir.join("portable.txt"), PORTABLE_NOTE);
            let _ = std::fs::write(&witness, b"");
        } else if !marked {
            return None;
        }
        Some(Paths { data, portable: true })
    }

    pub fn db(&self) -> PathBuf {
        self.data.join("nautile.db")
    }

    /// Portable mode: the database key encrypted with the master password.
    pub fn wrapped_key(&self) -> PathBuf {
        self.data.join("db.key.enc")
    }

    /// What [write_private] writes before renaming it to `db.key.enc`.
    fn wrapped_tmp(&self) -> PathBuf {
        self.data.join("db.key.enc.tmp")
    }

    fn plain_key(&self) -> PathBuf {
        self.data.join("db.key")
    }

    fn settings(&self) -> PathBuf {
        self.data.join("settings.json")
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum Language {
    #[default]
    System,
    Fr,
    En,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum Appearance {
    /// Follows the system's light or dark mode.
    System,
    #[default]
    Dark,
    Light,
    /// Pure black, for OLED screens.
    Oled,
}

/// Preferences of this computer (not synchronised).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub language: Language,
    pub appearance: Appearance,
    /// Where the database key lives: "keyring" or "file".
    pub key_store: Option<String>,
    /// The terminal colour scheme (ids of the Android app).
    pub terminal_theme: Option<String>,
    pub terminal_font: Option<f32>,
    /// The app's own lock (PIN or password), as on Android. Only the method: its hash and the wrong tries are
    /// in the encrypted database (`nautile_core::lock::stored`), which says whether the app locks.
    pub lock_method: Option<nautile_core::lock::LockMethod>,
    /// Older versions kept the hash here: moved into the database at the next start, then removed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lock_hash: Option<String>,
    /// Older versions' wrong tries, moved into the database with the hash.
    #[serde(skip_serializing)]
    pub lock_failures: u32,
    /// Locks again after this many idle minutes (0: never).
    pub lock_after: Option<u32>,
    /// Locks too when the window is hidden in the notification area or minimised, and after the computer slept.
    pub lock_on_hide: bool,
    /// This computer in the synchronisation (UUID).
    pub device_id: Option<String>,
    /// The backup places, in the order they were added.
    pub targets: Vec<crate::backup::Target>,
    pub sync_enabled: bool,
    /// The place whose file the synchronisation shares.
    pub sync_target: Option<String>,
    pub sync_minutes: Option<u32>,
    /// The last local change already synchronised.
    pub synced_change: i64,
    pub last_sync: Option<i64>,
    pub sync_error: Option<String>,
    /// The devices sharing the file: (id, name, last seen).
    pub devices: Vec<(String, String, i64)>,
    pub lan_enabled: bool,
    pub lan_peers: Vec<crate::backup::Peer>,
    /// The AI assistant's provider, and per provider (by name) its model and address. Keys stay in the database.
    pub ai_provider: Option<nautile_net::ai::ProviderKind>,
    pub ai_models: std::collections::HashMap<String, String>,
    pub ai_urls: std::collections::HashMap<String, String>,
    /// A system prompt of the user's (the built-in one when empty).
    pub ai_prompt: Option<String>,
    /// The terminal font (`TerminalFonts` ids: jetbrains, fira, hack, source, cascadia, system).
    pub terminal_family: Option<String>,
    /// The cursor: block, bar or underline.
    pub terminal_cursor: Option<String>,
    /// Ask before closing a connected session (on by default).
    pub confirm_close: Option<bool>,
    /// Keep a copy of each new session's output in `data/logs`.
    pub session_logs: bool,
    /// Remote desktops: never drawn larger than their real size (sharper), else stretched to the window.
    pub remote_no_upscale: bool,
    /// RDP without a resolution of its own: the size of the window (else 1600×900).
    pub rdp_window_size: Option<bool>,
    /// The remote clipboard is copied here (VNC; on by default).
    pub remote_clipboard: Option<bool>,
    /// A remote desktop opens in full screen (F11 to leave).
    pub remote_fullscreen: bool,
    /// Commands typed before on a host are offered as you type (on by default).
    pub history_suggestions: Option<bool>,
    /// Ctrl+Shift+P types the host's password (on by default).
    pub password_key: Option<bool>,
    /// Programs on the server may put text in this computer's clipboard (OSC 52: tmux, vim). Off by default:
    /// a server could replace what the user is about to paste.
    pub osc52: bool,
    /// « Option = Méta »: Alt (Option) sends ESC before the key. `None`: the system's default (off on macOS).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub option_meta: Option<bool>,
    /// The colour of the buttons.
    pub accent: crate::theme::Accent,
    pub button_style: crate::theme::ButtonStyle,
    /// Synchronise only when a VPN is up (the shared file is on a private network).
    pub sync_vpn_only: bool,
    /// The AI providers offered in the assistant (Android's switches).
    pub ai_enabled: Vec<nautile_net::ai::ProviderKind>,
    /// The user's own system prompts, and the one in use (`None`: the default one).
    pub ai_prompts: Vec<AiPrompt>,
    pub ai_prompt_id: Option<String>,
    /// Lines of the terminal sent with « Joindre l'écran » (40, 100, 200, 500).
    pub ai_lines: Option<usize>,
    /// Closing the window keeps Nautile in the notification area (on by default).
    pub close_to_tray: Option<bool>,
    /// System notifications while the window is hidden (on by default).
    pub notifications: Option<bool>,
    /// The window as it was left, and the page shown.
    pub window: Option<WindowState>,
    pub last_page: Option<String>,
    /// Look for a new version at start, once a day at most. `None`: not asked yet (asked at the first start),
    /// and nothing is checked until the answer.
    pub update_check: Option<bool>,
    pub update_last_check: i64,
    /// « Ignorer cette version ».
    pub update_skipped: Option<String>,
    /// The sidebar's width, when set by dragging its edge.
    pub sidebar_width: Option<f32>,
    /// The sidebar shows only its icons.
    pub sidebar_compact: bool,
    /// The monitor panel's width, when set by dragging its edge.
    #[serde(default)]
    pub monitor_width: Option<f32>,
    /// The themes this computer leaves out of the synchronisation (file and Wi-Fi).
    #[serde(default)]
    pub sync_skip: Vec<nautile_core::sync::SyncPart>,
    /// The first terminal's share of a split screen (0.2 to 0.8), when its divider was moved.
    #[serde(default)]
    pub split_ratio: Option<f32>,
    /// A split screen shows its terminals one above the other (else side by side).
    #[serde(default)]
    pub split_stacked: bool,
    /// « Accélération graphique »: drawn by the graphics card (wgpu) from the next start, the CPU renderer
    /// (tiny-skia) when it fails; off: tiny-skia only.
    #[serde(default)]
    pub gpu: bool,
}

impl Settings {
    pub fn load(paths: &Paths) -> Settings {
        let Ok(text) = std::fs::read_to_string(paths.settings()) else {
            return Settings::default();
        };
        match serde_json::from_str(&text) {
            Ok(s) => s,
            Err(e) => {
                // Kept aside: the next save would otherwise lose the backup places and the sync.
                eprintln!("settings.json unreadable ({e}): copied to settings.json.bak, defaults used");
                let _ = write_private(&paths.data.join("settings.json.bak"), text.as_bytes());
                // The lock is in the database; an older version's, still in this file, is taken from what can be
                // read, so that a broken file never starts unlocked.
                Settings {
                    lock_hash: json_string(&text, "lock_hash").filter(|h| h.starts_with("pbkdf2-sha256:")),
                    lock_method: match json_string(&text, "lock_method").as_deref() {
                        Some("Pin") => Some(nautile_core::lock::LockMethod::Pin),
                        Some("Password") => Some(nautile_core::lock::LockMethod::Password),
                        _ => None,
                    },
                    ..Settings::default()
                }
            }
        }
    }

    /// Written whole or not at all (a cut in the middle never leaves half a file), readable only by this user.
    pub fn save(&self, paths: &Paths) -> std::io::Result<()> {
        let json = serde_json::to_string_pretty(self).map_err(std::io::Error::other)?;
        let r = private_dir(&paths.data).and_then(|()| write_private(&paths.settings(), json.as_bytes()));
        if let Err(e) = &r {
            eprintln!("settings.json not saved: {e}");
        }
        r
    }

    pub fn terminal_theme(&self) -> &str {
        self.terminal_theme.as_deref().unwrap_or("nautile")
    }

    pub fn sync_minutes(&self) -> u32 {
        self.sync_minutes.unwrap_or(15)
    }

    /// Whether the app has a lock: the method is set from the database at start (`App::lock_boot`).
    pub fn locks(&self) -> bool {
        self.lock_method.is_some()
    }

    pub fn lock_after(&self) -> u32 {
        self.lock_after.unwrap_or(15)
    }

    /// Off until the user said yes (`None`: the question at start is still to come).
    pub fn update_check(&self) -> bool {
        self.update_check.unwrap_or(false)
    }

    pub fn close_to_tray(&self) -> bool {
        self.close_to_tray.unwrap_or(true)
    }

    pub fn notifications(&self) -> bool {
        self.notifications.unwrap_or(true)
    }

    pub fn ai_lines(&self) -> usize {
        self.ai_lines.unwrap_or(100)
    }

    pub fn terminal_family(&self) -> crate::term::fonts::TermFont {
        crate::term::fonts::by_id(self.terminal_family.as_deref().unwrap_or("jetbrains"))
    }

    pub fn terminal_cursor(&self) -> &str {
        self.terminal_cursor.as_deref().unwrap_or("block")
    }

    pub fn confirm_close(&self) -> bool {
        self.confirm_close.unwrap_or(true)
    }

    pub fn rdp_window_size(&self) -> bool {
        self.rdp_window_size.unwrap_or(true)
    }

    pub fn remote_clipboard(&self) -> bool {
        self.remote_clipboard.unwrap_or(true)
    }

    pub fn history_suggestions(&self) -> bool {
        self.history_suggestions.unwrap_or(true)
    }

    pub fn option_meta(&self) -> bool {
        self.option_meta.unwrap_or(!cfg!(target_os = "macos"))
    }

    pub fn password_key(&self) -> bool {
        self.password_key.unwrap_or(true)
    }

    pub fn terminal_font(&self) -> f32 {
        self.terminal_font.unwrap_or(14.0)
    }

    /// English unless the setting or, by default, the system is French (as on Android).
    pub fn english(&self) -> bool {
        match self.language {
            Language::Fr => false,
            Language::En => true,
            Language::System => !system_is_french(),
        }
    }
}

/// The string value of "[name]": "…" in a JSON text that may be broken (no escapes in the values looked for).
fn json_string(text: &str, name: &str) -> Option<String> {
    let at = text.find(&format!("\"{name}\""))? + name.len() + 2;
    let rest = text[at..].trim_start().strip_prefix(':')?.trim_start().strip_prefix('"')?;
    let end = rest.find('"')?;
    Some(rest[..end].to_owned()).filter(|v| !v.contains('\\'))
}

fn system_is_french() -> bool {
    ["LC_ALL", "LC_MESSAGES", "LANG"]
        .iter()
        .find_map(|v| std::env::var(v).ok().filter(|s| !s.is_empty()))
        .map(|l| l.to_lowercase().starts_with("fr"))
        .unwrap_or_else(windows_is_french)
}

#[cfg(windows)]
fn windows_is_french() -> bool {
    // The user's UI language, without a crate: `GetUserDefaultUILanguage` primary language 0x0C is French.
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetUserDefaultUILanguage() -> u16;
    }
    unsafe { GetUserDefaultUILanguage() & 0x3ff == 0x0c }
}

#[cfg(not(windows))]
fn windows_is_french() -> bool {
    false
}

const SERVICE: &str = "Nautile";
const ACCOUNT: &str = "database-key";

/// The key of the database, created the first time (installed mode; the portable mode goes through
/// [vault_state] and the master password).
pub fn database_key(paths: &Paths, settings: &mut Settings) -> std::io::Result<[u8; 32]> {
    if paths.portable {
        return Err(std::io::Error::other(
            "Mode portable : la clé de la base demande le mot de passe maître",
        ));
    }
    private_dir(&paths.data)?;
    let file = paths.plain_key();
    let use_file = settings.key_store.as_deref() == Some("file");
    if !use_file && let Ok(entry) = keyring::Entry::new(SERVICE, ACCOUNT) {
        if let Ok(k) = entry.get_secret()
            && let Ok(key) = <[u8; 32]>::try_from(k.as_slice())
        {
            return Ok(key);
        }
        // First start: a new key, unless a database already exists (its key would be lost).
        if settings.key_store.is_none() && !paths.db().exists() {
            let key = new_key();
            if entry.set_secret(&key).is_ok() && entry.get_secret().is_ok_and(|k| k == key) {
                settings.key_store = Some("keyring".into());
                let _ = settings.save(paths);
                return Ok(key);
            }
        } else if settings.key_store.as_deref() == Some("keyring") {
            return Err(std::io::Error::other("Le trousseau du système ne donne plus la clé de la base"));
        }
    }
    // No keyring (headless Linux): a file readable only by this user.
    if let Ok(k) = std::fs::read(&file)
        && let Ok(key) = <[u8; 32]>::try_from(k.as_slice())
    {
        return Ok(key);
    }
    // A new key would never open the existing database, and would replace the keyring for good: better
    // to stop (the keyring may only be locked yet, at a start with the session).
    if paths.db().exists() {
        return Err(std::io::Error::other(
            "La clé de la base est introuvable (trousseau du système verrouillé ou indisponible ?)",
        ));
    }
    let key = new_key();
    write_private(&file, &key)?;
    settings.key_store = Some("file".into());
    let _ = settings.save(paths);
    Ok(key)
}

/// The master password of the portable mode, before the database opens.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Vault {
    /// No `db.key.enc` yet: the password is created (`legacy`: an older copy's plain `db.key` gets encrypted).
    Create {
        legacy: bool,
    },
    Open,
}

/// What the portable start asks for.
pub fn vault_state(paths: &Paths) -> Result<Vault, String> {
    // A `db.key.enc.tmp` alone: a migration cut before its rename, maybe the only copy of the key.
    if paths.wrapped_key().exists() || (paths.wrapped_tmp().is_file() && !paths.plain_key().exists()) {
        return Ok(Vault::Open);
    }
    let legacy = paths.plain_key().is_file();
    if !legacy && paths.db().exists() {
        return Err(nautile_core::tr(
            "La clé de la base est introuvable : ni db.key.enc ni db.key dans le dossier data",
        ));
    }
    Ok(Vault::Create { legacy })
}

/// Whether [key] opens the database (true when there is none yet).
fn opens_database(paths: &Paths, key: &[u8; 32]) -> bool {
    !paths.db().exists() || nautile_core::db::Db::open(&paths.db(), key).is_ok()
}

/// The plain `db.key` of an older copy: None when there is none; an error when it cannot be the key.
fn legacy_key(paths: &Paths) -> Result<Option<[u8; 32]>, String> {
    match std::fs::read(paths.plain_key()) {
        Ok(k) => match <[u8; 32]>::try_from(k.as_slice()) {
            // All zeros: what a wipe cut before the removal leaves, never a key.
            Ok(key) if key != [0; 32] => Ok(Some(key)),
            _ => Err(nautile_core::tr("Le fichier db.key est illisible")),
        },
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

/// Creates the master password: the plain `db.key` of an older copy (or, without a database, a new key) is
/// encrypted into `db.key.enc`, read back and made durable, then the plain file is overwritten and removed.
pub fn create_master(paths: &Paths, password: &str) -> Result<[u8; 32], String> {
    let s = |e: std::io::Error| e.to_string();
    private_dir(&paths.data).map_err(s)?;
    if paths.wrapped_key().exists() {
        return Err(nautile_core::tr("Un mot de passe maître existe déjà"));
    }
    let key = match legacy_key(paths)? {
        Some(key) => key,
        None if !paths.db().exists() => new_key(),
        None => {
            return Err(nautile_core::tr(
                "La clé de la base est introuvable : ni db.key.enc ni db.key dans le dossier data",
            ));
        }
    };
    // Checked before the plain file goes: a wrong key encrypted, then the right one removed, would lose the data.
    if !opens_database(paths, &key) {
        return Err(nautile_core::tr("Le fichier db.key n'ouvre pas la base de données"));
    }
    seal_key(paths, &key, password)?;
    wipe(&paths.plain_key()).map_err(s)?;
    sync_dir(&paths.data).map_err(s)?;
    Ok(key)
}

fn unwrap_error(e: nautile_core::lock::UnwrapError, current: bool) -> String {
    match e {
        nautile_core::lock::UnwrapError::Rejected if current => nautile_core::tr("Mot de passe maître actuel incorrect"),
        nautile_core::lock::UnwrapError::Rejected => nautile_core::tr("Mot de passe maître incorrect"),
        nautile_core::lock::UnwrapError::Format => nautile_core::tr("Le fichier db.key.enc est illisible"),
    }
}

/// The database key, with the master password. Finishes a migration that a cut left half done: a
/// `db.key.enc.tmp` never renamed, a plain `db.key` still there.
pub fn open_master(paths: &Paths, password: &str) -> Result<[u8; 32], String> {
    let s = |e: std::io::Error| e.to_string();
    // db.key.enc first, then a temporary file a cut left behind (a change of password, a migration).
    let (mut error, mut stale, mut found) = (None, false, None);
    for (file, is_tmp) in [(paths.wrapped_key(), false), (paths.wrapped_tmp(), true)] {
        let data = match std::fs::read(&file) {
            Ok(d) => d,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(e) => {
                error.get_or_insert(e.to_string());
                continue;
            }
        };
        match nautile_core::lock::unwrap_key(&data, password) {
            Ok(key) if opens_database(paths, &key) => {
                found = Some((key, is_tmp));
                break;
            }
            Ok(_) => stale = true,
            Err(e) => {
                error.get_or_insert(unwrap_error(e, false));
            }
        }
    }
    let legacy = legacy_key(paths).ok().flatten();
    let key = match found {
        Some((key, true)) => {
            // The temporary file holds the key with this password: it becomes db.key.enc.
            std::fs::rename(paths.wrapped_tmp(), paths.wrapped_key()).map_err(s)?;
            sync_dir(&paths.data).map_err(s)?;
            key
        }
        Some((key, false)) => key,
        // The password is right but its key does not open the database, while the plain key does: that one
        // is encrypted instead.
        None if stale && legacy.is_some_and(|k| paths.db().exists() && opens_database(paths, &k)) => {
            let key = legacy.unwrap_or_default();
            seal_key(paths, &key, password)?;
            key
        }
        None if stale => return Err(nautile_core::tr("La clé de db.key.enc n'ouvre pas la base de données")),
        None => return Err(error.unwrap_or_else(|| unwrap_error(nautile_core::lock::UnwrapError::Format, false))),
    };
    // A migration cut after db.key.enc was written: the plain key goes now, only when it is this very key
    // (db.key.enc is on disk for good by then).
    if legacy == Some(key) {
        sync_dir(&paths.data).map_err(s)?;
        let _ = wipe(&paths.plain_key());
    }
    Ok(key)
}

/// A new master password for the same database key.
pub fn change_master(paths: &Paths, current: &str, new: &str) -> Result<(), String> {
    let data = std::fs::read(paths.wrapped_key()).map_err(|e| e.to_string())?;
    let key = nautile_core::lock::unwrap_key(&data, current).map_err(|e| unwrap_error(e, true))?;
    seal_key(paths, &key, new)
}

/// Writes `db.key.enc` (whole or not at all), reads it back and makes the rename durable: the key is never left
/// only in memory, nor only in a rename a power cut could undo.
fn seal_key(paths: &Paths, key: &[u8; 32], password: &str) -> Result<(), String> {
    let s = |e: std::io::Error| e.to_string();
    write_private(&paths.wrapped_key(), &nautile_core::lock::wrap_key(key, password)).map_err(s)?;
    sync_dir(&paths.data).map_err(s)?;
    let back = std::fs::read(paths.wrapped_key()).map_err(s)?;
    if nautile_core::lock::unwrap_key(&back, password).ok() != Some(*key) {
        return Err(nautile_core::tr("Le fichier db.key.enc n'a pas pu être relu"));
    }
    Ok(())
}

/// Makes the renames and removals in [dir] durable (Unix; Windows has no such call for a folder, and NTFS
/// journals its metadata).
fn sync_dir(dir: &Path) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        std::fs::File::open(dir)?.sync_all()
    }
    #[cfg(not(unix))]
    {
        let _ = dir;
        Ok(())
    }
}

/// Overwrites [path] with zeros, then removes it. On flash memory (USB sticks, SSDs) the old blocks may survive
/// the overwrite: it only removes the easy copy.
fn wipe(path: &Path) -> std::io::Result<()> {
    use std::io::Write;
    let len = match std::fs::metadata(path) {
        Ok(m) => m.len(),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(e),
    };
    if let Ok(mut f) = std::fs::OpenOptions::new().write(true).open(path) {
        let _ = f.write_all(&vec![0u8; len as usize]).and_then(|()| f.sync_all());
    }
    std::fs::remove_file(path)
}

/// Put next to the executable when an older portable copy is found without it.
const PORTABLE_NOTE: &str = include_str!("../../../packaging/portable.txt");

fn new_key() -> [u8; 32] {
    let mut k = [0u8; 32];
    rand::rng().fill_bytes(&mut k);
    k
}

/// Creates [dir] (and its parents) and keeps it to this user (0700 on Unix: logs, conversations and settings
/// are in it).
pub fn private_dir(dir: &Path) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::{DirBuilderExt, PermissionsExt};
        if !dir.is_dir() {
            std::fs::DirBuilder::new().recursive(true).mode(0o700).create(dir)?;
        }
        // Folders made by older versions were 0755; a USB stick may not have Unix rights at all.
        if let Ok(m) = std::fs::metadata(dir)
            && m.permissions().mode() & 0o077 != 0
        {
            let _ = std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700));
        }
        Ok(())
    }
    #[cfg(not(unix))]
    {
        std::fs::create_dir_all(dir)
    }
}

/// A new file readable only by this user (0600 on Unix).
pub fn create_private(path: &Path) -> std::io::Result<std::fs::File> {
    let mut o = std::fs::OpenOptions::new();
    o.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        o.mode(0o600);
    }
    o.open(path)
}

/// Replaces [path] with [data] atomically (a temporary file renamed over it), readable only by this user.
pub fn write_private(path: &Path, data: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let mut name = path.file_name().unwrap_or_default().to_os_string();
    name.push(".tmp");
    let tmp = path.with_file_name(name);
    let written = create_private(&tmp).and_then(|mut f| {
        f.write_all(data)?;
        f.sync_all()
    });
    match written.and_then(|()| std::fs::rename(&tmp, path)) {
        Ok(()) => Ok(()),
        Err(e) => {
            let _ = std::fs::remove_file(&tmp);
            Err(e)
        }
    }
}

/// A system prompt of the user's (Android's « Prompts système »).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AiPrompt {
    pub id: String,
    pub name: String,
    pub text: String,
}

/// The window's size and place, to open it as it was left.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct WindowState {
    pub width: f32,
    pub height: f32,
    pub x: Option<f32>,
    pub y: Option<f32>,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("nautile-storage-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn writes_replace_the_file_whole() {
        let dir = temp_dir("atomic");
        private_dir(&dir).unwrap();
        let file = dir.join("settings.json");
        write_private(&file, b"first").unwrap();
        write_private(&file, b"second").unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), b"second");
        assert!(!dir.join("settings.json.tmp").exists(), "no temporary file left");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(std::fs::metadata(&file).unwrap().permissions().mode() & 0o777, 0o600);
            assert_eq!(std::fs::metadata(&dir).unwrap().permissions().mode() & 0o777, 0o700);
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn unreadable_settings_are_kept_aside() {
        let dir = temp_dir("corrupt");
        let paths = Paths {
            data: dir.clone(),
            portable: true,
        };
        private_dir(&dir).unwrap();
        std::fs::write(paths.settings(), "{\"lock_hash\": \"pbkdf2").unwrap();
        let s = Settings::load(&paths);
        assert!(s.lock_hash.is_none());
        assert_eq!(
            std::fs::read_to_string(dir.join("settings.json.bak")).unwrap(),
            "{\"lock_hash\": \"pbkdf2"
        );
        // An older version's lock, in a file cut in the middle: kept, to be moved into the database.
        let broken = "{\"lock_method\": \"Pin\",\n  \"lock_hash\": \"pbkdf2-sha256:1000:AA==:BB==\",\n  \"targets\": [";
        std::fs::write(paths.settings(), broken).unwrap();
        let s = Settings::load(&paths);
        assert_eq!(s.lock_hash.as_deref(), Some("pbkdf2-sha256:1000:AA==:BB=="));
        assert_eq!(s.lock_method, Some(nautile_core::lock::LockMethod::Pin));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_lock_hash_is_no_longer_written_to_settings() {
        let s = Settings {
            lock_method: Some(nautile_core::lock::LockMethod::Pin),
            lock_failures: 3,
            ..Settings::default()
        };
        let json = serde_json::to_string(&s).unwrap();
        assert!(json.contains("\"lock_method\":\"Pin\""));
        assert!(!json.contains("lock_hash") && !json.contains("lock_failures") && !json.contains("lock_until"));
        // An older file still loads (its hash is moved at start).
        let old: Settings =
            serde_json::from_str(r#"{"lock_method":"Pin","lock_hash":"pbkdf2-sha256:x","lock_failures":2,"lock_until":5}"#).unwrap();
        assert_eq!((old.lock_hash.as_deref(), old.lock_failures), (Some("pbkdf2-sha256:x"), 2));
        assert!(!Settings::default().update_check(), "never before the user said yes");
    }

    #[test]
    fn only_the_marker_switches_the_portable_mode_on() {
        let dir = temp_dir("marker");
        std::fs::create_dir_all(dir.join("data")).unwrap();
        assert!(Paths::portable_at(&dir).is_none(), "an empty data folder is not enough");
        std::fs::write(dir.join("portable.txt"), "x").unwrap();
        let p = Paths::portable_at(&dir).unwrap();
        assert!(p.portable && p.data == dir.join("data"));
        // An older portable copy (data with a database, no marker): kept portable, and marked.
        std::fs::remove_file(dir.join("portable.txt")).unwrap();
        std::fs::write(dir.join("data/nautile.db"), b"x").unwrap();
        assert!(Paths::portable_at(&dir).is_some());
        assert!(dir.join("portable.txt").is_file());
        // Marked once: a marker removed afterwards is the user's choice, not put back.
        std::fs::remove_file(dir.join("portable.txt")).unwrap();
        assert!(Paths::portable_at(&dir).is_none());
        assert!(!dir.join("portable.txt").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_portable_key_needs_the_master_password() {
        let dir = temp_dir("master");
        let paths = Paths {
            data: dir.clone(),
            portable: true,
        };
        let mut settings = Settings::default();
        assert!(database_key(&paths, &mut settings).is_err(), "never a plain key in portable mode");
        assert_eq!(vault_state(&paths), Ok(Vault::Create { legacy: false }));
        let key = create_master(&paths, "first password").unwrap();
        assert_eq!(vault_state(&paths), Ok(Vault::Open));
        assert!(!dir.join("db.key").exists());
        assert_eq!(open_master(&paths, "first password"), Ok(key));
        assert!(open_master(&paths, "wrong password").is_err());
        assert!(create_master(&paths, "again").is_err(), "never replaced");
        // A new password, the same key.
        assert!(change_master(&paths, "wrong password", "second password").is_err());
        change_master(&paths, "first password", "second password").unwrap();
        assert!(open_master(&paths, "first password").is_err());
        assert_eq!(open_master(&paths, "second password"), Ok(key));
        // A changed file is refused.
        let mut bytes = std::fs::read(paths.wrapped_key()).unwrap();
        bytes[60] ^= 1;
        std::fs::write(paths.wrapped_key(), &bytes).unwrap();
        assert!(open_master(&paths, "second password").is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_older_plain_key_is_encrypted_then_removed() {
        let dir = temp_dir("migrate");
        let paths = Paths {
            data: dir.clone(),
            portable: true,
        };
        private_dir(&dir).unwrap();
        let old = [9u8; 32];
        std::fs::write(dir.join("db.key"), old).unwrap();
        drop(nautile_core::db::Db::open(&paths.db(), &old).unwrap());
        assert_eq!(vault_state(&paths), Ok(Vault::Create { legacy: true }));
        // A plain key that does not open the database, or all zeros (a wipe cut short), is never encrypted.
        std::fs::write(dir.join("db.key"), [8u8; 32]).unwrap();
        assert!(create_master(&paths, "a master password").is_err());
        std::fs::write(dir.join("db.key"), [0u8; 32]).unwrap();
        assert!(create_master(&paths, "a master password").is_err());
        assert!(!paths.wrapped_key().exists() && dir.join("db.key").exists());
        std::fs::write(dir.join("db.key"), old).unwrap();
        assert_eq!(
            create_master(&paths, "a master password"),
            Ok(old),
            "the same key, the database still opens"
        );
        assert!(!dir.join("db.key").exists());
        assert!(!std::fs::read(paths.wrapped_key()).unwrap().windows(32).any(|w| w == old));
        assert_eq!(open_master(&paths, "a master password"), Ok(old));
        // Lost while the database is there: an error, never a new key.
        std::fs::remove_file(paths.wrapped_key()).unwrap();
        assert!(vault_state(&paths).is_err());
        assert!(create_master(&paths, "a master password").is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_cut_migration_is_finished_at_the_next_start() {
        let dir = temp_dir("cut");
        let paths = Paths {
            data: dir.clone(),
            portable: true,
        };
        private_dir(&dir).unwrap();
        let key = [5u8; 32];
        drop(nautile_core::db::Db::open(&paths.db(), &key).unwrap());
        // Cut before the rename: only db.key.enc.tmp, the plain key already gone. Tried, then renamed.
        std::fs::write(paths.wrapped_tmp(), nautile_core::lock::wrap_key(&key, "pass word 1")).unwrap();
        assert_eq!(vault_state(&paths), Ok(Vault::Open));
        assert!(open_master(&paths, "wrong").is_err());
        assert!(paths.wrapped_tmp().exists(), "kept after a wrong password");
        assert_eq!(open_master(&paths, "pass word 1"), Ok(key));
        assert!(paths.wrapped_key().exists() && !paths.wrapped_tmp().exists());
        // A plain db.key that is another key is not removed on a right password.
        std::fs::write(dir.join("db.key"), [6u8; 32]).unwrap();
        assert_eq!(open_master(&paths, "pass word 1"), Ok(key));
        assert!(dir.join("db.key").exists());
        // The same key: removed.
        std::fs::write(dir.join("db.key"), key).unwrap();
        assert_eq!(open_master(&paths, "pass word 1"), Ok(key));
        assert!(!dir.join("db.key").exists());
        // db.key.enc holding a key that does not open the database, the plain one does: that one is sealed.
        std::fs::write(paths.wrapped_key(), nautile_core::lock::wrap_key(&[7u8; 32], "pass word 1")).unwrap();
        assert!(open_master(&paths, "pass word 1").is_err(), "no key opens it");
        std::fs::write(dir.join("db.key"), key).unwrap();
        assert_eq!(open_master(&paths, "pass word 1"), Ok(key));
        assert!(!dir.join("db.key").exists());
        assert_eq!(
            nautile_core::lock::unwrap_key(&std::fs::read(paths.wrapped_key()).unwrap(), "pass word 1"),
            Ok(key)
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_existing_database_never_gets_a_new_key() {
        let dir = temp_dir("key");
        let paths = Paths {
            data: dir.clone(),
            portable: false,
        };
        private_dir(&dir).unwrap();
        // No keyring: the key file. Lost while the database is there: an error, not a new key.
        let mut settings = Settings {
            key_store: Some("file".into()),
            ..Settings::default()
        };
        let key = database_key(&paths, &mut settings).unwrap();
        assert_eq!(database_key(&paths, &mut settings).unwrap(), key);
        std::fs::write(paths.db(), b"x").unwrap();
        std::fs::remove_file(dir.join("db.key")).unwrap();
        assert!(database_key(&paths, &mut settings).is_err());
        assert!(!dir.join("db.key").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
