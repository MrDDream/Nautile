//! Where Nautile keeps its data, its settings, and the database key.
//!
//! - Installed: `%APPDATA%\Nautile` (Windows), `~/.local/share/nautile` (Linux), `~/Library/Application Support/Nautile`.
//! - Portable: a `data` folder next to the executable when it exists (or a `portable.txt` file is there): the
//!   whole app then lives on a USB stick.
//!
//! The database key (32 random bytes) goes to the system keyring (Windows Credential Manager, GNOME Keyring /
//! KWallet, macOS Keychain). Without one — or in portable mode — it is kept in `db.key` next to the database.

use std::path::{Path, PathBuf};

use rand::RngCore;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone)]
pub struct Paths {
    pub data: PathBuf,
    pub portable: bool,
}

impl Paths {
    pub fn detect() -> Paths {
        if let Some(dir) = std::env::current_exe().ok().and_then(|e| e.parent().map(Path::to_path_buf)) {
            if dir.join("data").is_dir() || dir.join("portable").exists() || dir.join("portable.txt").exists() {
                return Paths {
                    data: dir.join("data"),
                    portable: true,
                };
            }
        }
        let base = dirs::data_dir().unwrap_or_else(|| PathBuf::from("."));
        let name = if cfg!(target_os = "linux") { "nautile" } else { "Nautile" };
        Paths {
            data: base.join(name),
            portable: false,
        }
    }

    pub fn db(&self) -> PathBuf {
        self.data.join("nautile.db")
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
    /// The app's own lock (PIN or password), as on Android.
    pub lock_method: Option<nautile_core::lock::LockMethod>,
    /// `pbkdf2-sha256:…`, never the code itself.
    pub lock_hash: Option<String>,
    pub lock_failures: u32,
    /// No try before this time (ms) after too many wrong codes.
    pub lock_until: i64,
    /// Locks again after this many idle minutes (0: never).
    pub lock_after: Option<u32>,
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
    /// Ctrl+Shift+P types the host's password (on by default).
    pub password_key: Option<bool>,
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
    /// Look for a new version at start, once a day at most (on by default).
    pub update_check: Option<bool>,
    pub update_last_check: i64,
    /// « Ignorer cette version ».
    pub update_skipped: Option<String>,
    /// The sidebar's width, when set by dragging its edge.
    pub sidebar_width: Option<f32>,
    /// The sidebar shows only its icons.
    pub sidebar_compact: bool,
}

impl Settings {
    pub fn load(paths: &Paths) -> Settings {
        std::fs::read_to_string(paths.settings())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, paths: &Paths) -> std::io::Result<()> {
        std::fs::create_dir_all(&paths.data)?;
        std::fs::write(paths.settings(), serde_json::to_string_pretty(self).unwrap_or_default())
    }

    pub fn terminal_theme(&self) -> &str {
        self.terminal_theme.as_deref().unwrap_or("nautile")
    }

    pub fn sync_minutes(&self) -> u32 {
        self.sync_minutes.unwrap_or(15)
    }

    pub fn locks(&self) -> bool {
        self.lock_method.is_some() && self.lock_hash.is_some()
    }

    pub fn lock_after(&self) -> u32 {
        self.lock_after.unwrap_or(15)
    }

    pub fn update_check(&self) -> bool {
        self.update_check.unwrap_or(true)
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
    extern "system" {
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

/// The key of the database, created the first time.
pub fn database_key(paths: &Paths, settings: &mut Settings) -> std::io::Result<[u8; 32]> {
    std::fs::create_dir_all(&paths.data)?;
    let file = paths.data.join("db.key");
    let use_file = paths.portable || settings.key_store.as_deref() == Some("file");
    if !use_file {
        if let Ok(entry) = keyring::Entry::new(SERVICE, ACCOUNT) {
            if let Ok(k) = entry.get_secret() {
                if let Ok(key) = <[u8; 32]>::try_from(k.as_slice()) {
                    return Ok(key);
                }
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
    }
    // No keyring (headless Linux, portable mode): a file readable only by this user.
    if let Ok(k) = std::fs::read(&file) {
        if let Ok(key) = <[u8; 32]>::try_from(k.as_slice()) {
            return Ok(key);
        }
    }
    let key = new_key();
    write_private(&file, &key)?;
    settings.key_store = Some("file".into());
    let _ = settings.save(paths);
    Ok(key)
}

fn new_key() -> [u8; 32] {
    let mut k = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut k);
    k
}

fn write_private(path: &Path, data: &[u8]) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        use std::io::Write;
        use std::os::unix::fs::OpenOptionsExt;
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(path)?;
        f.write_all(data)
    }
    #[cfg(not(unix))]
    {
        std::fs::write(path, data)
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
