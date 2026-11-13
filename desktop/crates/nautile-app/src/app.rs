//! The application: state, messages and their handling. The screens are drawn in `ui`.

use iced::widget::text_editor;
use iced::Task;
use nautile_core::db::Db;
use nautile_core::model::{
    AuthMethod, BackupData, ForwardKind, Host, HostGroup, Identity, KnownHost, PortForward, Protocol, Snippet, SshKey, DEFAULT_COLOR,
};
use nautile_core::{backup, i18n, sync, tr, trf};

use crate::sessions::{PendingPrompt, Tab, TunnelRun};
use crate::storage::{Appearance, Language, Paths, Settings};
use crate::term::widget::Action;

/// Colours offered for hosts and groups (HOST_COLORS on Android).
pub const COLORS: [i64; 5] = [0xFFFF_B547, 0xFF7A_A2F7, 0xFF5B_D69A, 0xFFF3_8BA8, 0xFF5A_6672];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Page {
    Hosts,
    Snippets,
    Keys,
    Tunnels,
    Settings,
}

/// A pop-up that is not an editor.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Modal {
    /// « Nouvel hôte »: which kind of machine.
    NewHost,
    /// The groups, their order, and their editor.
    Groups,
    /// An AI provider's key, address and model.
    AiProvider(nautile_net::ai::ProviderKind),
    /// « Choisir un modèle » for a provider.
    AiModels(nautile_net::ai::ProviderKind),
    /// A system prompt of the user's.
    AiPrompt,
    /// What the synchronisation does (five points).
    SyncInfo,
    /// What the Wi-Fi synchronisation does.
    LanInfo,
    /// Pairing a device for the Wi-Fi synchronisation.
    LanPair,
    /// « Ajouter un emplacement »: the kinds, then the form.
    AddPlace,
    /// The settings of the place in [crate::backup::BackupState::open].
    Target,
    /// The backup password.
    Password,
    /// « Exporter une copie dans un fichier ».
    Export,
}

/// A sub-page of the settings (`None` in [App::settings_page]: their home).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SettingsPage {
    Appearance,
    Terminal,
    Ai,
    Backup,
    Security,
    Data,
    /// The recorded sessions (under Terminal).
    Logs,
    /// The window, the notification area, starting with the session, updates.
    System,
}

/// The tabs of the terminal settings (as on Android).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum TermTab {
    #[default]
    Aspect,
    Keyboard,
    Sessions,
}

/// The tabs of the keychain page.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeysTab {
    Keys,
    Identities,
    KnownHosts,
}

/// An entry of a drop-down list (a group, a key, a jump host…): `None` is « none ».
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Choice {
    pub id: Option<i64>,
    pub label: String,
}

impl std::fmt::Display for Choice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.label)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProtocolChoice(pub Protocol);

impl ProtocolChoice {
    /// What the desktop offers (serial lines stay on the phone).
    pub const ALL: [ProtocolChoice; 6] = [
        ProtocolChoice(Protocol::Ssh),
        ProtocolChoice(Protocol::Mosh),
        ProtocolChoice(Protocol::Telnet),
        ProtocolChoice(Protocol::Rdp),
        ProtocolChoice(Protocol::Vnc),
        ProtocolChoice(Protocol::Local),
    ];
}

impl std::fmt::Display for ProtocolChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&protocol_label(self.0))
    }
}

pub fn protocol_label(p: Protocol) -> String {
    match p {
        Protocol::Ssh => "SSH".into(),
        Protocol::Mosh => "Mosh".into(),
        Protocol::Telnet => "Telnet".into(),
        Protocol::Rdp => "RDP".into(),
        Protocol::Vnc => "VNC".into(),
        Protocol::Local => tr("Terminal local"),
        Protocol::Serial => tr("Série"),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AuthChoice(pub AuthMethod);

impl AuthChoice {
    pub const ALL: [AuthChoice; 3] = [
        AuthChoice(AuthMethod::Password),
        AuthChoice(AuthMethod::Key),
        AuthChoice(AuthMethod::Identity),
    ];
}

impl std::fmt::Display for AuthChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&match self.0 {
            AuthMethod::Password | AuthMethod::Agent => tr("Mot de passe"),
            AuthMethod::Key => tr("Clé"),
            AuthMethod::Identity => tr("Identité"),
        })
    }
}

/// The host being created or edited, as typed.
#[derive(Debug, Clone)]
pub struct HostForm {
    pub original: Option<Host>,
    pub alias: String,
    pub hostname: String,
    pub port: String,
    pub username: String,
    pub protocol: Protocol,
    pub auth: AuthMethod,
    pub password: String,
    pub key: Option<i64>,
    pub identity: Option<i64>,
    pub group: Option<i64>,
    pub jump: Option<i64>,
    pub color: i64,
    pub tags: String,
    pub startup: String,
    pub rdp_domain: String,
    pub rdp_resolution: String,
    pub rdp_scale: String,
    pub confirm_delete: bool,
}

impl HostForm {
    fn new() -> Self {
        Self::from(None)
    }

    fn from(h: Option<Host>) -> Self {
        let base = h.clone().unwrap_or_else(|| Host::new("", ""));
        HostForm {
            alias: base.alias.clone(),
            hostname: base.hostname.clone(),
            port: base.port.to_string(),
            username: base.username.clone(),
            protocol: base.protocol,
            auth: if base.auth_method == AuthMethod::Agent {
                AuthMethod::Password
            } else {
                base.auth_method
            },
            password: base.password.clone().unwrap_or_default(),
            key: base.key_id,
            identity: base.identity_id,
            group: base.group_id,
            jump: base.jump_host_id,
            color: base.color,
            tags: base.tags.clone(),
            startup: base.startup_command.clone().unwrap_or_default(),
            rdp_domain: base.rdp_domain.clone().unwrap_or_default(),
            rdp_resolution: base.rdp_resolution.clone().unwrap_or_default(),
            rdp_scale: base.rdp_scale.map(|s| s.to_string()).unwrap_or_default(),
            confirm_delete: false,
            original: h,
        }
    }

    pub fn valid(&self) -> bool {
        (!self.alias.trim().is_empty() || !self.hostname.trim().is_empty())
            && (self.protocol == Protocol::Local || !self.hostname.trim().is_empty())
            && self
                .port
                .trim()
                .parse::<u16>()
                .is_ok_and(|p| p > 0 || self.protocol == Protocol::Local)
    }

    fn host(&self) -> Host {
        let mut h = self.original.clone().unwrap_or_else(|| Host::new("", ""));
        // The display name is optional, as on Android: the address otherwise.
        h.alias = Some(self.alias.trim())
            .filter(|a| !a.is_empty())
            .unwrap_or(self.hostname.trim())
            .to_owned();
        h.hostname = self.hostname.trim().to_owned();
        h.port = self.port.trim().parse().unwrap_or(self.protocol.default_port());
        h.username = self.username.trim().to_owned();
        h.protocol = self.protocol;
        h.auth_method = self.auth;
        h.password = Some(self.password.clone()).filter(|p| !p.is_empty() && self.auth == AuthMethod::Password);
        h.key_id = self.key.filter(|_| self.auth == AuthMethod::Key);
        h.identity_id = self.identity.filter(|_| self.auth == AuthMethod::Identity);
        h.group_id = self.group;
        h.jump_host_id = self.jump.filter(|j| Some(*j) != self.original.as_ref().map(|o| o.id));
        h.color = self.color;
        h.tags = self
            .tags
            .split(',')
            .map(str::trim)
            .filter(|t| !t.is_empty())
            .collect::<Vec<_>>()
            .join(",");
        h.startup_command = Some(self.startup.trim().to_owned()).filter(|s| !s.is_empty());
        if self.protocol == Protocol::Rdp {
            h.rdp_domain = Some(self.rdp_domain.trim().to_owned()).filter(|s| !s.is_empty());
            h.rdp_resolution = Some(self.rdp_resolution.trim().to_owned()).filter(|s| !s.is_empty());
            h.rdp_scale = self.rdp_scale.trim().parse().ok();
        }
        h
    }
}

#[derive(Debug, Clone)]
pub struct GroupForm {
    pub original: Option<HostGroup>,
    pub name: String,
    pub color: i64,
    pub confirm_delete: bool,
}

#[derive(Debug)]
pub struct SnippetForm {
    pub original: Option<Snippet>,
    pub name: String,
    pub command: text_editor::Content,
    pub tag: String,
    pub confirm_delete: bool,
}

#[derive(Debug, Clone)]
pub struct IdentityForm {
    pub original: Option<Identity>,
    pub name: String,
    pub username: String,
    pub password: String,
    pub key: Option<i64>,
    pub confirm_delete: bool,
}

#[derive(Debug, Clone)]
pub struct ForwardForm {
    pub original: Option<PortForward>,
    pub name: String,
    pub host: Option<i64>,
    pub kind: ForwardKind,
    pub bind_host: String,
    pub bind_port: String,
    pub dest_host: String,
    pub dest_port: String,
    pub auto_start: bool,
    pub confirm_delete: bool,
}

fn port(s: &str) -> Option<i32> {
    s.trim().parse::<u16>().ok().filter(|p| *p > 0).map(i32::from)
}

impl ForwardForm {
    pub fn valid(&self) -> bool {
        self.host.is_some()
            && !self.name.trim().is_empty()
            && port(&self.bind_port).is_some()
            && (self.kind == ForwardKind::Dynamic || (port(&self.dest_port).is_some() && !self.dest_host.trim().is_empty()))
    }
}

/// A key being generated or imported.
#[derive(Debug)]
pub struct KeyForm {
    pub generate: bool,
    pub name: String,
    pub algorithm: nautile_net::keys::KeyAlgorithm,
    /// The private key pasted or read from a file.
    pub text: text_editor::Content,
    pub passphrase: String,
    /// Keeps the passphrase of an imported key so it is not asked at each connection.
    pub remember: bool,
    pub busy: bool,
    pub error: Option<String>,
}

/// Choosing or changing the lock in the settings.
#[derive(Debug, Clone, Default)]
pub struct LockForm {
    /// `None`: no lock.
    pub method: Option<nautile_core::lock::LockMethod>,
    pub current: String,
    pub new: String,
    pub confirm: String,
    pub error: Option<String>,
}

/// The panel on the right.
#[derive(Debug)]
pub enum Editor {
    Host(Box<HostForm>),
    Group(GroupForm),
    Snippet(SnippetForm),
    Identity(IdentityForm),
    Forward(ForwardForm),
    Key(KeyForm),
}

/// Restoring a backup file.
#[derive(Debug, Default)]
pub struct ImportState {
    pub file: Option<(String, String)>,
    pub password: String,
    pub busy: bool,
}

#[derive(Debug, Default)]
pub struct ExportState {
    pub password: String,
    pub confirm: String,
    pub busy: bool,
}

#[derive(Debug, Clone)]
pub enum HostField {
    Alias(String),
    Hostname(String),
    Port(String),
    Username(String),
    Protocol(ProtocolChoice),
    Auth(AuthChoice),
    Password(String),
    Key(Choice),
    Identity(Choice),
    Group(Choice),
    Jump(Choice),
    Color(i64),
    Tags(String),
    Startup(String),
    RdpDomain(String),
    RdpResolution(String),
    RdpScale(String),
}

#[derive(Debug, Clone)]
pub enum Message {
    Nav(Page),
    OpenModal(Option<Modal>),
    /// « Nouvel hôte » of this kind.
    NewHostOf(Protocol),
    MoveGroup(i64, bool),
    /// Échap: closes the pop-up on top.
    Escape,
    WindowResized(iced::Size),
    SidebarGrab,
    SidebarMove(f32),
    SidebarRelease,
    SidebarToggle,
    SystemTheme(iced::theme::Mode),
    /// Does nothing (previews).
    Noop,
    /// Several messages, in order (a button that does two things).
    Batch(Vec<Message>),
    TermTab(TermTab),
    TermFamily(String),
    TermCursor(String),
    FontLoaded,
    ConfirmClose(bool),
    SessionLogs(bool),
    PasswordKey(bool),
    /// « Fermer » (true) or « Annuler » for the session in [App::closing].
    CloseAnswer(bool),
    /// Ctrl+Shift+P: types the host's password in the active session.
    TypePassword,
    /// Opens a file or folder with the system.
    OpenPath(std::path::PathBuf),
    DeleteLog(std::path::PathBuf),
    Tray(crate::tray::TrayEvent),
    /// The window's close button: hide in the notification area, or quit.
    CloseRequested(iced::window::Id),
    WindowMoved(iced::Point),
    WindowFocused(bool),
    /// Shows the window again (from the notification area).
    ShowWindow,
    Quit,
    CloseToTray(bool),
    Autostart(bool),
    Notifications(bool),
    Accent(crate::theme::Accent),
    ButtonStyle(crate::theme::ButtonStyle),
    SettingsPage(Option<SettingsPage>),
    Search(String),
    GroupFilter(Option<i64>),
    SnippetFilter(Option<String>),
    CloseEditor,
    NewHost,
    EditHost(i64),
    Host(HostField),
    SaveHost,
    SaveHostAndConnect,
    DeleteHost,
    NewGroup,
    EditGroup(i64),
    GroupName(String),
    GroupColor(i64),
    SaveGroup,
    DeleteGroup,
    NewSnippet,
    EditSnippet(i64),
    SnippetName(String),
    SnippetCommand(text_editor::Action),
    SnippetTag(String),
    /// Types this into the command, where the cursor is.
    SnippetInsert(String),
    SaveSnippet,
    DeleteSnippet,
    CopySnippet(i64),
    DeleteKey(i64),
    NewKey(bool),
    KeyName(String),
    KeyAlgorithm(nautile_net::keys::KeyAlgorithm),
    KeyText(text_editor::Action),
    KeyPassphrase(String),
    KeyRemember(bool),
    KeyPickFile,
    KeyFileLoaded(Option<(String, String)>),
    SaveKey,
    KeyReady(Result<nautile_net::keys::KeyMaterial, String>),
    CopyPublicKey(i64),
    KeysTab(KeysTab),
    NewIdentity,
    EditIdentity(i64),
    IdentityName(String),
    IdentityUser(String),
    IdentityPassword(String),
    IdentityKey(Choice),
    SaveIdentity,
    DeleteIdentity,
    DeleteKnownHost(i64),
    NewForward,
    EditForward(i64),
    ForwardName(String),
    ForwardHost(Choice),
    ForwardKind(ForwardKind),
    ForwardBindHost(String),
    ForwardBindPort(String),
    ForwardDestHost(String),
    ForwardDestPort(String),
    ForwardAuto(bool),
    SaveForward,
    DeleteForward,
    ImportPick,
    ImportPicked(Option<(String, String)>),
    ImportPassword(String),
    ImportCancel,
    ImportRun,
    ImportDecoded(Box<Result<BackupData, String>>),
    ImportDone(Result<sync::Stats, String>),
    ExportPassword(String),
    ExportConfirm(String),
    ExportRun,
    ExportDone(Result<String, String>),
    Language(Language),
    Appearance(Appearance),
    TermTheme(String),
    TermFont(f32),
    ToastClear,
    // --- Lock ---
    LockInput(String),
    Unlock,
    LockNow,
    LockPick(Option<nautile_core::lock::LockMethod>),
    LockCurrent(String),
    LockNew(String),
    LockConfirm(String),
    LockSave,
    LockAfter(u32),
    Tick,
    BackupTick,
    Backup(crate::backup::BackupMsg),
    Ai(crate::assistant::AiMsg),
    // --- Sessions ---
    Connect(i64),
    OpenFiles(i64),
    Files(u64, crate::files::FilesMsg),
    Sftp(u64, nautile_net::sftp::Update),
    Remote(u64, nautile_remote::RemoteEvent),
    RemoteAction(u64, crate::remote_view::RemoteAction),
    FileDropped(std::path::PathBuf),
    OpenLocal,
    SelectTab(Option<u64>),
    CloseTab(u64),
    Reconnect(u64),
    NextTab(bool),
    CloseActiveTab,
    Session(u64, nautile_net::Event),
    Term(u64, Action),
    PromptInput(String),
    PromptAnswer(bool),
    RunSnippet(i64),
    SnippetVar(usize, String),
    SnippetRunGo,
    SnippetRunCancel,
    ToggleTunnel(i64),
    Tunnel(i64, nautile_net::Event),
}

pub struct App {
    pub paths: Paths,
    pub settings: Settings,
    pub db: Option<Db>,
    /// Why the database could not open.
    pub fatal: Option<String>,
    pub page: Page,
    pub hosts: Vec<Host>,
    pub groups: Vec<HostGroup>,
    pub snippets: Vec<Snippet>,
    pub keys: Vec<SshKey>,
    pub identities: Vec<Identity>,
    pub known_hosts: Vec<KnownHost>,
    pub forwards: Vec<PortForward>,
    pub keys_tab: KeysTab,
    pub search: String,
    pub editor: Option<Editor>,
    pub toast: Option<String>,
    pub import: ImportState,
    pub export: ExportState,
    pub tabs: Vec<Tab>,
    /// `None`: the hosts and the other pages.
    pub active_tab: Option<u64>,
    pub next_tab: u64,
    /// The last grid size of the terminal area, for new sessions.
    pub grid: (u16, u16),
    /// Questions from the connections, the first one shown.
    pub prompts: Vec<PendingPrompt>,
    pub prompt_input: String,
    /// The session shown last, where snippets go from the Snippets page.
    pub last_tab: Option<u64>,
    /// A snippet waiting for its variables: (snippet, name → value).
    pub snippet_run: Option<(i64, Vec<(String, String)>)>,
    /// Tunnels started from the Tunnels page, by rule.
    pub tunnels: std::collections::HashMap<i64, TunnelRun>,
    pub locked: bool,
    pub lock_input: String,
    pub lock_error: Option<String>,
    pub lock_form: LockForm,
    pub last_activity: std::time::Instant,
    pub backup: crate::backup::BackupState,
    pub assistant: crate::assistant::AssistantState,
    /// The group whose hosts are shown (`None`: all).
    pub group_filter: Option<i64>,
    /// The snippet tag shown (`None`: all).
    pub snippet_tag: Option<String>,
    /// The settings sub-page open (`None`: their home).
    pub settings_page: Option<SettingsPage>,
    /// The pop-up shown when no editor is open (or under the group editor).
    pub modal: Option<Modal>,
    /// The window's size, for the pop-ups' height.
    pub window: iced::Size,
    /// The sidebar's edge is being dragged.
    pub sidebar_drag: bool,
    /// The system is in dark mode (for « Système »).
    pub os_dark: bool,
    pub term_tab: TermTab,
    /// The terminal fonts already given to the renderer.
    pub fonts_loaded: Vec<&'static str>,
    /// A connected session waiting for « Fermer ? ».
    pub closing: Option<u64>,
    /// The icon is in the notification area.
    pub tray: bool,
    /// The window is hidden in the notification area, or not in front.
    pub hidden: bool,
    pub focused: bool,
    pub window_pos: Option<iced::Point>,
}

impl App {
    /// Starts on this computer's data.
    pub fn boot() -> (App, Task<Message>) {
        let paths = Paths::detect();
        let mut settings = Settings::load(&paths);
        i18n::set_english(settings.english());
        let opened = crate::storage::database_key(&paths, &mut settings)
            .map_err(|e| e.to_string())
            .and_then(|key| Db::open(&paths.db(), &key).map_err(|e| e.to_string()));
        let (db, fatal) = match opened {
            Ok(db) => (Some(db), None),
            Err(e) => (None, Some(e)),
        };
        let mut app = App::with(paths, settings, db, fatal);
        let focus = if app.locked {
            iced::widget::operation::focus("lock")
        } else {
            Task::none()
        };
        let backups = app.backup_boot();
        let os_theme = iced::system::theme().map(Message::SystemTheme);
        let font = app.settings.terminal_family().id;
        let font = app.load_font(font);
        app.tray = crate::tray::start();
        // Started with the session, hidden: shown anyway when there is no notification area to find it in.
        let minimized = std::env::args().any(|a| a == "--minimized");
        app.hidden = minimized;
        let show = if minimized && !app.tray {
            app.update(Message::ShowWindow)
        } else {
            Task::none()
        };
        (app, Task::batch([focus, backups, os_theme, font, show]))
    }

    pub fn with(paths: Paths, settings: Settings, db: Option<Db>, fatal: Option<String>) -> App {
        let mut app = App {
            paths,
            settings,
            db,
            fatal,
            page: Page::Hosts,
            hosts: vec![],
            groups: vec![],
            snippets: vec![],
            keys: vec![],
            identities: vec![],
            known_hosts: vec![],
            forwards: vec![],
            keys_tab: KeysTab::Keys,
            search: String::new(),
            editor: None,
            toast: None,
            import: ImportState::default(),
            export: ExportState::default(),
            tabs: Vec::new(),
            active_tab: None,
            next_tab: 0,
            grid: (100, 30),
            prompts: Vec::new(),
            prompt_input: String::new(),
            last_tab: None,
            snippet_run: None,
            tunnels: Default::default(),
            locked: false,
            lock_input: String::new(),
            lock_error: None,
            lock_form: LockForm::default(),
            last_activity: std::time::Instant::now(),
            backup: Default::default(),
            assistant: Default::default(),
            group_filter: None,
            snippet_tag: None,
            settings_page: None,
            modal: None,
            window: iced::Size::new(1280.0, 800.0),
            sidebar_drag: false,
            os_dark: true,
            term_tab: TermTab::Aspect,
            fonts_loaded: Vec::new(),
            closing: None,
            tray: false,
            hidden: false,
            focused: true,
            window_pos: None,
        };
        // Before several providers could be on, the chosen one was the only one.
        if app.settings.ai_enabled.is_empty() {
            if let Some(k) = app.settings.ai_provider {
                app.settings.ai_enabled.push(k);
            }
        }
        app.page = match app.settings.last_page.as_deref() {
            Some("snippets") => Page::Snippets,
            Some("keys") => Page::Keys,
            Some("tunnels") => Page::Tunnels,
            Some("settings") => Page::Settings,
            _ => Page::Hosts,
        };
        app.locked = app.settings.locks();
        app.lock_form.method = app.settings.lock_method;
        app.reload();
        app
    }

    pub fn title(&self) -> String {
        match self.active_tab.and_then(|id| self.tab(id)) {
            Some(t) => format!("{} — Nautile", t.remote_title.as_deref().unwrap_or(&t.name)),
            None => "Nautile".into(),
        }
    }

    /// Window shortcuts: new local terminal, close / switch tabs.
    pub fn subscription(&self) -> iced::Subscription<Message> {
        let mut subs = vec![
            iced::keyboard::listen().filter_map(shortcut),
            iced::event::listen_with(dropped_file),
            iced::time::every(std::time::Duration::from_secs(30)).map(|_| Message::BackupTick),
            iced::window::resize_events().map(|(_, size)| Message::WindowResized(size)),
            crate::tray::subscription().map(Message::Tray),
            iced::window::close_requests().map(Message::CloseRequested),
            iced::window::events().filter_map(|(_, e)| match e {
                iced::window::Event::Moved(p) => Some(Message::WindowMoved(p)),
                iced::window::Event::Focused => Some(Message::WindowFocused(true)),
                iced::window::Event::Unfocused => Some(Message::WindowFocused(false)),
                _ => None,
            }),
        ];
        if self.sidebar_drag {
            subs.push(iced::event::listen_with(sidebar_drag));
        }
        if self.settings.appearance == Appearance::System {
            subs.push(iced::system::theme_changes().map(Message::SystemTheme));
        }
        if self.settings.locks() && !self.locked && self.settings.lock_after() > 0 {
            subs.push(iced::time::every(std::time::Duration::from_secs(20)).map(|_| Message::Tick));
        }
        iced::Subscription::batch(subs)
    }

    pub fn theme(&self) -> iced::Theme {
        use crate::theme::Base;
        let base = match self.settings.appearance {
            Appearance::Dark => Base::Dark,
            Appearance::Light => Base::Light,
            Appearance::Oled => Base::Oled,
            Appearance::System if self.os_dark => Base::Dark,
            Appearance::System => Base::Light,
        };
        crate::theme::theme(base, self.settings.accent, self.settings.button_style)
    }

    /// Reads every list again from the database.
    pub fn reload(&mut self) {
        let Some(db) = &self.db else { return };
        let s = db.store();
        self.hosts = s.all().unwrap_or_default();
        self.hosts.sort_by_key(|h| h.alias.to_lowercase());
        self.groups = s.all().unwrap_or_default();
        self.groups.sort_by(|a, b| {
            a.sort_order
                .cmp(&b.sort_order)
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        });
        self.snippets = s.all().unwrap_or_default();
        self.snippets.sort_by_key(|x| x.name.to_lowercase());
        self.keys = s.all().unwrap_or_default();
        self.identities = s.all().unwrap_or_default();
        self.identities.sort_by_key(|x| x.name.to_lowercase());
        self.known_hosts = s.all().unwrap_or_default();
        self.known_hosts.sort_by(|a, b| a.host.cmp(&b.host).then(a.port.cmp(&b.port)));
        self.forwards = s.all().unwrap_or_default();
        self.forwards.sort_by_key(|x| x.name.to_lowercase());
    }

    pub(crate) fn say(&mut self, msg: String) -> Task<Message> {
        self.toast = Some(msg);
        Task::perform(async { tokio::time::sleep(std::time::Duration::from_secs(4)).await }, |_| {
            Message::ToastClear
        })
    }

    fn fail<E: std::fmt::Display>(&mut self, e: E) -> Task<Message> {
        self.say(trf("Échec : %1$s", &[&e]))
    }

    pub fn update(&mut self, message: Message) -> Task<Message> {
        if !matches!(
            message,
            Message::Session(..)
                | Message::Tunnel(..)
                | Message::Sftp(..)
                | Message::Remote(..)
                | Message::Tick
                | Message::ToastClear
                | Message::BackupTick
                | Message::WindowMoved(_)
                | Message::WindowFocused(_)
                | Message::WindowResized(_)
                | Message::SystemTheme(_)
                | Message::FontLoaded
                | Message::Backup(
                    crate::backup::BackupMsg::Lan(_)
                        | crate::backup::BackupMsg::Fetched(_)
                        | crate::backup::BackupMsg::Written(..)
                        | crate::backup::BackupMsg::Done(..)
                        | crate::backup::BackupMsg::LanRound(_)
                )
        ) {
            self.last_activity = std::time::Instant::now();
        }
        // Locked: only the code goes through (connections keep running behind).
        if self.locked
            && !matches!(
                message,
                Message::LockInput(_)
                    | Message::Unlock
                    | Message::Tray(_)
                    | Message::CloseRequested(_)
                    | Message::ShowWindow
                    | Message::Quit
                    | Message::WindowMoved(_)
                    | Message::WindowFocused(_)
                    | Message::WindowResized(_)
                    | Message::SystemTheme(_)
                    | Message::FontLoaded
                    | Message::Session(..)
                    | Message::Tunnel(..)
                    | Message::Sftp(..)
                    | Message::Remote(..)
                    | Message::ToastClear
                    | Message::Tick
                    | Message::BackupTick
                    | Message::Ai(crate::assistant::AiMsg::Answer(..))
                    | Message::Backup(
                        crate::backup::BackupMsg::Lan(_)
                            | crate::backup::BackupMsg::Fetched(_)
                            | crate::backup::BackupMsg::Written(..)
                            | crate::backup::BackupMsg::Done(..)
                            | crate::backup::BackupMsg::LanRound(_)
                    )
            )
        {
            return Task::none();
        }
        match message {
            Message::Nav(p) => {
                self.active_tab = None;
                self.page = p;
                self.editor = None;
                self.search.clear();
                self.settings_page = None;
            }
            Message::SettingsPage(p) => {
                self.settings_page = p;
                self.search.clear();
                if p == Some(SettingsPage::Terminal) {
                    // The fonts are shown in their own face.
                    let ids: Vec<&str> = crate::term::fonts::ALL.iter().map(|f| f.id).collect();
                    return Task::batch(ids.into_iter().map(|id| self.load_font(id)));
                }
            }
            Message::Search(s) => self.search = s,
            Message::GroupFilter(g) => self.group_filter = g,
            Message::SnippetFilter(t) => self.snippet_tag = t,
            Message::CloseEditor => self.editor = None,
            Message::ToastClear => self.toast = None,

            // --- Hosts ---
            Message::NewHost => {
                self.editor = None;
                self.modal = Some(Modal::NewHost);
            }
            Message::NewHostOf(p) => {
                let mut f = HostForm::new();
                f.port = p.default_port().to_string();
                f.protocol = p;
                if p == Protocol::Local {
                    f.alias = tr("Terminal local");
                }
                self.modal = None;
                self.editor = Some(Editor::Host(Box::new(f)));
            }
            Message::OpenModal(m) => {
                self.modal = m;
                if m.is_none() {
                    // Whatever was half done in the pop-up is dropped.
                    self.editor = None;
                    self.backup.place = None;
                    self.backup.open = None;
                    self.backup.lan_join = false;
                    if self.backup.lan_code.take().is_some() {
                        if let Some(l) = &self.backup.lan {
                            l.cancel_code();
                        }
                    }
                }
            }
            Message::Escape => {
                if self.import.file.is_some() {
                    self.import = ImportState::default();
                } else if self.modal.is_some() && self.editor.is_none() {
                    return self.update(Message::OpenModal(None));
                } else if self.closing.is_some() {
                    self.closing = None;
                } else if self.snippet_run.is_some() {
                    self.snippet_run = None;
                } else if self.editor.is_some() {
                    self.editor = None;
                } else {
                    self.modal = None;
                }
            }
            Message::WindowResized(size) => self.window = size,
            Message::Noop => {}
            Message::Batch(list) => return Task::batch(list.into_iter().map(|m| self.update(m)).collect::<Vec<_>>()),
            Message::TermTab(t) => self.term_tab = t,
            Message::TermFamily(id) => {
                self.settings.terminal_family = Some(id.clone());
                let _ = self.settings.save(&self.paths);
                return self.load_font(&id);
            }
            Message::TermCursor(id) => {
                let shape = crate::term::cursor_shape(&id);
                self.settings.terminal_cursor = Some(id);
                let _ = self.settings.save(&self.paths);
                for t in &mut self.tabs {
                    t.term.set_cursor(shape);
                }
            }
            Message::FontLoaded => {}
            Message::ConfirmClose(v) => {
                self.settings.confirm_close = Some(v);
                let _ = self.settings.save(&self.paths);
            }
            Message::SessionLogs(v) => {
                self.settings.session_logs = v;
                let _ = self.settings.save(&self.paths);
            }
            Message::PasswordKey(v) => {
                self.settings.password_key = Some(v);
                let _ = self.settings.save(&self.paths);
            }
            Message::CloseAnswer(yes) => {
                if let Some(id) = self.closing.take() {
                    if yes {
                        self.close_tab(id);
                    }
                }
            }
            Message::TypePassword => {
                if self.settings.password_key() {
                    self.type_password();
                }
            }
            Message::OpenPath(p) => {
                let _ = opener::open(&p);
            }
            Message::Tray(e) => {
                use crate::tray::TrayEvent as T;
                return match e {
                    T::Open => self.update(Message::ShowWindow),
                    T::NewLocal => Task::batch([self.update(Message::ShowWindow), self.update(Message::OpenLocal)]),
                    T::Sync => self.update(Message::Backup(crate::backup::BackupMsg::SyncNow)),
                    T::Lock => self.update(Message::LockNow),
                    T::Quit => self.update(Message::Quit),
                };
            }
            Message::CloseRequested(id) => {
                self.remember_window();
                if self.settings.close_to_tray() {
                    self.hidden = true;
                    return if self.tray {
                        iced::window::set_mode(id, iced::window::Mode::Hidden)
                    } else {
                        // No notification area (some GNOME desktops): minimised, never lost.
                        iced::window::minimize(id, true)
                    };
                }
                return iced::exit();
            }
            Message::WindowMoved(p) => self.window_pos = Some(p),
            Message::WindowFocused(f) => {
                self.focused = f;
                if f {
                    self.hidden = false;
                }
            }
            Message::ShowWindow => {
                self.hidden = false;
                return iced::window::latest().and_then(|id| {
                    Task::batch([
                        iced::window::set_mode(id, iced::window::Mode::Windowed),
                        iced::window::minimize(id, false),
                        iced::window::gain_focus(id),
                    ])
                });
            }
            Message::Quit => {
                self.remember_window();
                return iced::exit();
            }
            Message::CloseToTray(v) => {
                self.settings.close_to_tray = Some(v);
                let _ = self.settings.save(&self.paths);
            }
            Message::Autostart(v) => {
                return match crate::autostart::set(v) {
                    Ok(()) => Task::none(),
                    Err(e) => self.say(e.to_string()),
                };
            }
            Message::Notifications(v) => {
                self.settings.notifications = Some(v);
                let _ = self.settings.save(&self.paths);
            }
            Message::DeleteLog(p) => {
                // Only the recorded sessions, never anything else.
                if p.parent() == Some(self.paths.data.join("logs").as_path()) {
                    let _ = std::fs::remove_file(&p);
                }
            }
            Message::SystemTheme(mode) => match mode {
                iced::theme::Mode::Dark => self.os_dark = true,
                iced::theme::Mode::Light => self.os_dark = false,
                iced::theme::Mode::None => {}
            },
            Message::Accent(a) => {
                self.settings.accent = a;
                let _ = self.settings.save(&self.paths);
            }
            Message::ButtonStyle(b) => {
                self.settings.button_style = b;
                let _ = self.settings.save(&self.paths);
            }
            Message::SidebarGrab => self.sidebar_drag = true,
            Message::SidebarMove(x) => {
                if self.sidebar_drag {
                    if x < 130.0 {
                        self.settings.sidebar_compact = true;
                    } else {
                        self.settings.sidebar_compact = false;
                        self.settings.sidebar_width = Some(x.clamp(180.0, 360.0));
                    }
                }
            }
            Message::SidebarRelease => {
                if self.sidebar_drag {
                    self.sidebar_drag = false;
                    let _ = self.settings.save(&self.paths);
                }
            }
            Message::SidebarToggle => {
                self.settings.sidebar_compact = !self.settings.sidebar_compact;
                let _ = self.settings.save(&self.paths);
            }
            Message::MoveGroup(id, up) => {
                let Some(db) = &self.db else { return Task::none() };
                let Some(i) = self.groups.iter().position(|g| g.id == id) else {
                    return Task::none();
                };
                let j = if up {
                    i.checked_sub(1)
                } else {
                    Some(i + 1).filter(|j| *j < self.groups.len())
                };
                if let Some(j) = j {
                    let mut list = self.groups.clone();
                    list.swap(i, j);
                    for (n, g) in list.iter_mut().enumerate() {
                        if g.sort_order != n as i32 {
                            g.sort_order = n as i32;
                            let _ = db.store().upsert(&*g);
                        }
                    }
                    self.reload();
                }
            }
            Message::EditHost(id) => {
                if let Some(h) = self.hosts.iter().find(|h| h.id == id) {
                    self.editor = Some(Editor::Host(Box::new(HostForm::from(Some(h.clone())))));
                }
            }
            Message::Host(field) => {
                if let Some(Editor::Host(f)) = &mut self.editor {
                    f.confirm_delete = false;
                    match field {
                        HostField::Alias(v) => f.alias = v,
                        HostField::Hostname(v) => {
                            // « user@address:port » pasted in the address fills the three fields.
                            let mut rest = v.trim().to_owned();
                            if let Some((user, host)) = rest.clone().split_once('@') {
                                if !user.is_empty() && !host.is_empty() {
                                    f.username = user.to_owned();
                                    rest = host.to_owned();
                                }
                            }
                            if let Some((host, port)) = rest.clone().rsplit_once(':') {
                                if !host.contains(':') && port.parse::<u16>().is_ok() {
                                    f.port = port.to_owned();
                                    rest = host.to_owned();
                                }
                            }
                            f.hostname = if rest == v.trim() { v } else { rest };
                        }
                        HostField::Port(v) => f.port = v.chars().filter(char::is_ascii_digit).take(5).collect(),
                        HostField::Username(v) => f.username = v,
                        HostField::Protocol(p) => {
                            // Follow the protocol's port unless the user typed another one.
                            if f.port.parse::<i32>().ok() == Some(f.protocol.default_port()) || f.port.is_empty() {
                                f.port = p.0.default_port().to_string();
                            }
                            f.protocol = p.0;
                        }
                        HostField::Auth(a) => f.auth = a.0,
                        HostField::Password(v) => f.password = v,
                        HostField::Key(c) => f.key = c.id,
                        HostField::Identity(c) => f.identity = c.id,
                        HostField::Group(c) => f.group = c.id,
                        HostField::Jump(c) => f.jump = c.id,
                        HostField::Color(c) => f.color = c,
                        HostField::Tags(v) => f.tags = v,
                        HostField::Startup(v) => f.startup = v,
                        HostField::RdpDomain(v) => f.rdp_domain = v,
                        HostField::RdpResolution(v) => f.rdp_resolution = v,
                        HostField::RdpScale(v) => f.rdp_scale = v.chars().filter(char::is_ascii_digit).take(3).collect(),
                    }
                }
            }
            Message::SaveHost => {
                let (Some(Editor::Host(f)), Some(db)) = (&self.editor, &self.db) else {
                    return Task::none();
                };
                if !f.valid() {
                    return Task::none();
                }
                let host = f.host();
                let new = f.original.is_none();
                return match db.store().upsert(&host) {
                    Ok(_) => {
                        self.editor = None;
                        self.reload();
                        self.say(if new {
                            trf("« %1$s » ajouté", &[&host.alias])
                        } else {
                            tr("Modifications enregistrées")
                        })
                    }
                    Err(e) => self.fail(e),
                };
            }
            Message::SaveHostAndConnect => {
                let (Some(Editor::Host(f)), Some(db)) = (&self.editor, &self.db) else {
                    return Task::none();
                };
                if !f.valid() {
                    return Task::none();
                }
                return match db.store().upsert(&f.host()) {
                    Ok(id) => {
                        self.editor = None;
                        self.reload();
                        self.connect(id)
                    }
                    Err(e) => self.fail(e),
                };
            }
            Message::DeleteHost => {
                let (Some(Editor::Host(f)), Some(db)) = (&mut self.editor, &self.db) else {
                    return Task::none();
                };
                let Some(h) = f.original.clone() else { return Task::none() };
                if !f.confirm_delete {
                    f.confirm_delete = true;
                    return Task::none();
                }
                let _ = db.store().delete::<Host>(h.id);
                self.editor = None;
                self.reload();
                return self.say(trf("« %1$s » supprimé", &[&h.alias]));
            }

            // --- Groups ---
            Message::NewGroup => {
                self.editor = Some(Editor::Group(GroupForm {
                    original: None,
                    name: String::new(),
                    color: DEFAULT_COLOR,
                    confirm_delete: false,
                }))
            }
            Message::EditGroup(id) => {
                if let Some(g) = self.groups.iter().find(|g| g.id == id) {
                    self.editor = Some(Editor::Group(GroupForm {
                        original: Some(g.clone()),
                        name: g.name.clone(),
                        color: g.color,
                        confirm_delete: false,
                    }));
                }
            }
            Message::GroupName(v) => {
                if let Some(Editor::Group(f)) = &mut self.editor {
                    f.name = v;
                    f.confirm_delete = false;
                }
            }
            Message::GroupColor(c) => {
                if let Some(Editor::Group(f)) = &mut self.editor {
                    f.color = c;
                }
            }
            Message::SaveGroup => {
                let (Some(Editor::Group(f)), Some(db)) = (&self.editor, &self.db) else {
                    return Task::none();
                };
                let name = f.name.trim().to_owned();
                if name.is_empty() {
                    return Task::none();
                }
                if self
                    .groups
                    .iter()
                    .any(|g| g.name == name && Some(g.id) != f.original.as_ref().map(|o| o.id))
                {
                    return self.say(tr("Un groupe porte déjà ce nom"));
                }
                let mut g: HostGroup = f.original.clone().unwrap_or_else(|| {
                    serde_json::from_value(serde_json::json!({ "name": "", "sortOrder": self.groups.len() })).expect("defaults")
                });
                g.name = name;
                g.color = f.color;
                return match db.store().upsert(&g) {
                    Ok(_) => {
                        self.editor = None;
                        self.reload();
                        Task::none()
                    }
                    Err(e) => self.fail(e),
                };
            }
            Message::DeleteGroup => {
                let (Some(Editor::Group(f)), Some(db)) = (&mut self.editor, &self.db) else {
                    return Task::none();
                };
                let Some(g) = f.original.clone() else { return Task::none() };
                if !f.confirm_delete {
                    f.confirm_delete = true;
                    return Task::none();
                }
                // Its hosts stay, without a group (ON DELETE SET NULL).
                let _ = db.store().delete::<HostGroup>(g.id);
                self.editor = None;
                self.reload();
            }

            // --- Snippets ---
            Message::NewSnippet => {
                self.editor = Some(Editor::Snippet(SnippetForm {
                    original: None,
                    name: String::new(),
                    command: text_editor::Content::new(),
                    tag: String::new(),
                    confirm_delete: false,
                }))
            }
            Message::EditSnippet(id) => {
                if let Some(s) = self.snippets.iter().find(|s| s.id == id) {
                    self.editor = Some(Editor::Snippet(SnippetForm {
                        original: Some(s.clone()),
                        name: s.name.clone(),
                        command: text_editor::Content::with_text(&s.command),
                        tag: s.tag.clone(),
                        confirm_delete: false,
                    }));
                }
            }
            Message::SnippetName(v) => {
                if let Some(Editor::Snippet(f)) = &mut self.editor {
                    f.name = v;
                }
            }
            Message::SnippetTag(v) => {
                if let Some(Editor::Snippet(f)) = &mut self.editor {
                    f.tag = v;
                }
            }
            Message::SnippetInsert(v) => {
                if let Some(Editor::Snippet(f)) = &mut self.editor {
                    f.command
                        .perform(text_editor::Action::Edit(text_editor::Edit::Paste(std::sync::Arc::new(v))));
                }
            }
            Message::SnippetCommand(a) => {
                if let Some(Editor::Snippet(f)) = &mut self.editor {
                    f.command.perform(a);
                }
            }
            Message::SaveSnippet => {
                let (Some(Editor::Snippet(f)), Some(db)) = (&self.editor, &self.db) else {
                    return Task::none();
                };
                let command = f.command.text().trim_end().to_owned();
                if f.name.trim().is_empty() || command.trim().is_empty() {
                    return Task::none();
                }
                let mut s: Snippet = f
                    .original
                    .clone()
                    .unwrap_or_else(|| serde_json::from_value(serde_json::json!({ "name": "", "command": "" })).expect("defaults"));
                s.name = f.name.trim().to_owned();
                s.command = command;
                s.tag = f.tag.trim().to_owned();
                return match db.store().upsert(&s) {
                    Ok(_) => {
                        self.editor = None;
                        self.reload();
                        Task::none()
                    }
                    Err(e) => self.fail(e),
                };
            }
            Message::DeleteSnippet => {
                let (Some(Editor::Snippet(f)), Some(db)) = (&mut self.editor, &self.db) else {
                    return Task::none();
                };
                let Some(s) = f.original.clone() else { return Task::none() };
                if !f.confirm_delete {
                    f.confirm_delete = true;
                    return Task::none();
                }
                let _ = db.store().delete::<Snippet>(s.id);
                self.editor = None;
                self.reload();
            }
            Message::CopySnippet(id) => {
                if let Some(s) = self.snippets.iter().find(|s| s.id == id) {
                    let (cmd, name) = (s.command.clone(), s.name.clone());
                    let toast = self.say(trf("« %1$s » copié", &[&name]));
                    return Task::batch([iced::clipboard::write(cmd), toast]);
                }
            }

            // --- Keys ---
            Message::DeleteKey(id) => {
                if let Some(db) = &self.db {
                    let _ = db.store().delete::<SshKey>(id);
                    self.reload();
                }
            }

            Message::NewKey(generate) => {
                self.editor = Some(Editor::Key(KeyForm {
                    generate,
                    name: String::new(),
                    algorithm: nautile_net::keys::KeyAlgorithm::Ed25519,
                    text: text_editor::Content::new(),
                    passphrase: String::new(),
                    remember: false,
                    busy: false,
                    error: None,
                }))
            }
            Message::KeyName(_)
            | Message::KeyAlgorithm(_)
            | Message::KeyText(_)
            | Message::KeyPassphrase(_)
            | Message::KeyRemember(_)
            | Message::KeyFileLoaded(_) => {
                if let Some(Editor::Key(f)) = &mut self.editor {
                    f.error = None;
                    match message {
                        Message::KeyName(v) => f.name = v,
                        Message::KeyAlgorithm(a) => f.algorithm = a,
                        Message::KeyText(a) => f.text.perform(a),
                        Message::KeyPassphrase(v) => f.passphrase = v,
                        Message::KeyRemember(v) => f.remember = v,
                        Message::KeyFileLoaded(Some((name, text))) => {
                            f.text = text_editor::Content::with_text(text.trim());
                            if f.name.is_empty() {
                                f.name = name.trim_end_matches(".pem").trim_end_matches(".ppk").to_owned();
                            }
                        }
                        _ => {}
                    }
                }
            }
            Message::KeyPickFile => {
                return Task::perform(
                    async {
                        let file = rfd::AsyncFileDialog::new().set_title(tr("Importer une clé")).pick_file().await?;
                        let text = String::from_utf8(file.read().await).ok()?;
                        Some((file.file_name(), text))
                    },
                    Message::KeyFileLoaded,
                )
            }
            Message::SaveKey => {
                let Some(Editor::Key(f)) = &mut self.editor else {
                    return Task::none();
                };
                if f.busy || f.name.trim().is_empty() {
                    return Task::none();
                }
                f.busy = true;
                let (generate, alg, name, text, pass) = (
                    f.generate,
                    f.algorithm,
                    f.name.trim().to_owned(),
                    f.text.text(),
                    f.passphrase.clone(),
                );
                return Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            use nautile_net::keys;
                            let pass = Some(pass.as_str()).filter(|p| !p.is_empty());
                            if generate {
                                let mut k = keys::generate(alg, &name)?;
                                if let Some(p) = pass {
                                    k.private_key = keys::export(&k.private_key, None, Some(p), &name)?;
                                    k.encrypted = true;
                                }
                                Ok(k)
                            } else {
                                keys::parse(&text, pass, &name)
                            }
                        })
                        .await
                        .map_err(|e| e.to_string())?
                        .map_err(|e: nautile_net::keys::KeyError| e.to_string())
                    },
                    Message::KeyReady,
                );
            }
            Message::KeyReady(r) => {
                let (Some(Editor::Key(f)), Some(db)) = (&mut self.editor, &self.db) else {
                    return Task::none();
                };
                f.busy = false;
                match r {
                    Err(e) => f.error = Some(e),
                    Ok(k) => {
                        let saved = (!f.generate && f.remember && !f.passphrase.is_empty()).then(|| f.passphrase.clone());
                        let key: Result<SshKey, _> = serde_json::from_value(serde_json::json!({
                            "name": f.name.trim(), "keyType": k.key_type, "description": k.description,
                            "publicKey": k.public_key, "privateKey": k.private_key, "fingerprint": k.fingerprint,
                            "encrypted": k.encrypted, "savedPassphrase": saved, "generated": f.generate,
                            "createdAt": nautile_core::model::now_ms(),
                        }));
                        let name = f.name.trim().to_owned();
                        return match key
                            .map_err(|e| e.to_string())
                            .and_then(|k| db.store().insert(&k).map_err(|e| e.to_string()))
                        {
                            Ok(_) => {
                                self.editor = None;
                                self.reload();
                                self.say(trf("« %1$s » ajouté", &[&name]))
                            }
                            Err(e) => self.fail(e),
                        };
                    }
                }
            }
            Message::CopyPublicKey(id) => {
                if let Some(k) = self.keys.iter().find(|k| k.id == id) {
                    let text = k.public_key.clone();
                    let toast = self.say(tr("Clé publique copiée"));
                    return Task::batch([iced::clipboard::write(text), toast]);
                }
            }
            Message::KeysTab(t) => {
                self.keys_tab = t;
                self.editor = None;
            }
            Message::NewIdentity => {
                self.editor = Some(Editor::Identity(IdentityForm {
                    original: None,
                    name: String::new(),
                    username: String::new(),
                    password: String::new(),
                    key: None,
                    confirm_delete: false,
                }))
            }
            Message::EditIdentity(id) => {
                if let Some(i) = self.identities.iter().find(|i| i.id == id) {
                    self.editor = Some(Editor::Identity(IdentityForm {
                        original: Some(i.clone()),
                        name: i.name.clone(),
                        username: i.username.clone(),
                        password: i.password.clone().unwrap_or_default(),
                        key: i.key_id,
                        confirm_delete: false,
                    }));
                }
            }
            Message::IdentityName(v) => {
                if let Some(Editor::Identity(f)) = &mut self.editor {
                    f.name = v;
                }
            }
            Message::IdentityUser(v) => {
                if let Some(Editor::Identity(f)) = &mut self.editor {
                    f.username = v;
                }
            }
            Message::IdentityPassword(v) => {
                if let Some(Editor::Identity(f)) = &mut self.editor {
                    f.password = v;
                }
            }
            Message::IdentityKey(c) => {
                if let Some(Editor::Identity(f)) = &mut self.editor {
                    f.key = c.id;
                }
            }
            Message::SaveIdentity => {
                let (Some(Editor::Identity(f)), Some(db)) = (&self.editor, &self.db) else {
                    return Task::none();
                };
                if f.name.trim().is_empty() || f.username.trim().is_empty() {
                    return Task::none();
                }
                let mut i: Identity = f
                    .original
                    .clone()
                    .unwrap_or_else(|| serde_json::from_value(serde_json::json!({ "name": "", "username": "" })).expect("defaults"));
                i.name = f.name.trim().to_owned();
                i.username = f.username.trim().to_owned();
                i.password = Some(f.password.clone()).filter(|p| !p.is_empty());
                i.key_id = f.key;
                return match db.store().upsert(&i) {
                    Ok(_) => {
                        self.editor = None;
                        self.reload();
                        Task::none()
                    }
                    Err(e) => self.fail(e),
                };
            }
            Message::DeleteIdentity => {
                let (Some(Editor::Identity(f)), Some(db)) = (&mut self.editor, &self.db) else {
                    return Task::none();
                };
                let Some(i) = f.original.clone() else { return Task::none() };
                if !f.confirm_delete {
                    f.confirm_delete = true;
                    return Task::none();
                }
                let _ = db.store().delete::<Identity>(i.id);
                self.editor = None;
                self.reload();
            }
            Message::DeleteKnownHost(id) => {
                if let Some(db) = &self.db {
                    let _ = db.store().delete::<KnownHost>(id);
                    self.reload();
                }
            }
            Message::NewForward => {
                self.editor = Some(Editor::Forward(ForwardForm {
                    original: None,
                    name: String::new(),
                    host: self.hosts.iter().find(|h| h.protocol == Protocol::Ssh).map(|h| h.id),
                    kind: ForwardKind::Local,
                    bind_host: "127.0.0.1".into(),
                    bind_port: String::new(),
                    dest_host: "127.0.0.1".into(),
                    dest_port: String::new(),
                    auto_start: false,
                    confirm_delete: false,
                }))
            }
            Message::EditForward(id) => {
                if let Some(x) = self.forwards.iter().find(|x| x.id == id) {
                    self.editor = Some(Editor::Forward(ForwardForm {
                        original: Some(x.clone()),
                        name: x.name.clone(),
                        host: Some(x.host_id),
                        kind: x.kind,
                        bind_host: x.bind_host.clone(),
                        bind_port: x.bind_port.to_string(),
                        dest_host: x.dest_host.clone(),
                        dest_port: if x.dest_port > 0 { x.dest_port.to_string() } else { String::new() },
                        auto_start: x.auto_start,
                        confirm_delete: false,
                    }));
                }
            }
            Message::ForwardName(_)
            | Message::ForwardHost(_)
            | Message::ForwardKind(_)
            | Message::ForwardBindHost(_)
            | Message::ForwardBindPort(_)
            | Message::ForwardDestHost(_)
            | Message::ForwardDestPort(_)
            | Message::ForwardAuto(_) => {
                let digits = |v: String| v.chars().filter(char::is_ascii_digit).take(5).collect::<String>();
                if let Some(Editor::Forward(f)) = &mut self.editor {
                    f.confirm_delete = false;
                    match message {
                        Message::ForwardName(v) => f.name = v,
                        Message::ForwardHost(c) => f.host = c.id,
                        Message::ForwardKind(k) => f.kind = k,
                        Message::ForwardBindHost(v) => f.bind_host = v,
                        Message::ForwardBindPort(v) => f.bind_port = digits(v),
                        Message::ForwardDestHost(v) => f.dest_host = v,
                        Message::ForwardDestPort(v) => f.dest_port = digits(v),
                        Message::ForwardAuto(v) => f.auto_start = v,
                        _ => {}
                    }
                }
            }
            Message::SaveForward => {
                let (Some(Editor::Forward(f)), Some(db)) = (&self.editor, &self.db) else {
                    return Task::none();
                };
                if !f.valid() {
                    return Task::none();
                }
                let mut x: PortForward = f.original.clone().unwrap_or_else(|| {
                    serde_json::from_value(serde_json::json!({ "name": "", "hostId": 0, "kind": "LOCAL", "bindPort": 0 }))
                        .expect("defaults")
                });
                x.name = f.name.trim().to_owned();
                x.host_id = f.host.unwrap_or_default();
                x.kind = f.kind;
                x.bind_host = Some(f.bind_host.trim().to_owned())
                    .filter(|s| !s.is_empty())
                    .unwrap_or_else(|| "127.0.0.1".into());
                x.bind_port = port(&f.bind_port).unwrap_or_default();
                let dynamic = f.kind == ForwardKind::Dynamic;
                x.dest_host = if dynamic { String::new() } else { f.dest_host.trim().to_owned() };
                x.dest_port = if dynamic { 0 } else { port(&f.dest_port).unwrap_or_default() };
                x.auto_start = f.auto_start;
                return match db.store().upsert(&x) {
                    Ok(_) => {
                        self.editor = None;
                        self.reload();
                        Task::none()
                    }
                    Err(e) => self.fail(e),
                };
            }
            Message::DeleteForward => {
                let (Some(Editor::Forward(f)), Some(db)) = (&mut self.editor, &self.db) else {
                    return Task::none();
                };
                let Some(x) = f.original.clone() else { return Task::none() };
                if !f.confirm_delete {
                    f.confirm_delete = true;
                    return Task::none();
                }
                let _ = db.store().delete::<PortForward>(x.id);
                self.editor = None;
                self.reload();
            }

            // --- Backup ---
            Message::ImportPick => {
                return Task::perform(
                    async {
                        let file = rfd::AsyncFileDialog::new()
                            .set_title(tr("Restaurer une sauvegarde"))
                            .pick_file()
                            .await?;
                        let text = String::from_utf8(file.read().await).ok()?;
                        Some((file.file_name(), text))
                    },
                    Message::ImportPicked,
                )
            }
            Message::ImportPicked(f) => {
                if let Some((name, text)) = f {
                    if !text.trim_start().starts_with(backup::MAGIC) {
                        return self.say(tr("Ce fichier n'est pas une sauvegarde Nautile"));
                    }
                    self.import.file = Some((name, text));
                }
            }
            Message::ImportPassword(v) => self.import.password = v,
            Message::ImportCancel => self.import = ImportState::default(),
            Message::ImportRun => {
                let Some((_, text)) = self.import.file.clone() else {
                    return Task::none();
                };
                let password = self.import.password.clone();
                self.import.busy = true;
                return Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || backup::import(&text, &password).map_err(|e| e.to_string()))
                            .await
                            .map_err(|e| e.to_string())
                            .and_then(|r| r)
                    },
                    |r| Message::ImportDecoded(Box::new(r)),
                );
            }
            Message::ImportDecoded(r) => {
                let result = match (*r, &mut self.db) {
                    (Ok(data), Some(db)) => sync::merge(db, &data).map_err(|e| e.to_string()),
                    (Err(e), _) => Err(e),
                    (_, None) => Err(String::new()),
                };
                return self.update(Message::ImportDone(result));
            }
            Message::ImportDone(r) => {
                self.import.busy = false;
                return match r {
                    Ok(st) => {
                        self.import = ImportState::default();
                        self.reload();
                        self.say(trf(
                            "Sauvegarde restaurée : %1$s ajout(s), %2$s mise(s) à jour",
                            &[&st.added, &st.updated],
                        ))
                    }
                    Err(e) => self.say(e),
                };
            }
            Message::ExportPassword(v) => self.export.password = v,
            Message::ExportConfirm(v) => self.export.confirm = v,
            Message::ExportRun => {
                let Some(db) = &self.db else { return Task::none() };
                if self.export.password.chars().count() < 8 || self.export.password != self.export.confirm {
                    return Task::none();
                }
                let Ok(mut data) = sync::snapshot(db, vec![]) else {
                    return Task::none();
                };
                data.tombstones.clear();
                let password = self.export.password.clone();
                self.export.busy = true;
                return Task::perform(
                    async move {
                        let text = tokio::task::spawn_blocking(move || backup::export(&data, &password))
                            .await
                            .map_err(|e| e.to_string())?
                            .map_err(|e| e.to_string())?;
                        let file = rfd::AsyncFileDialog::new()
                            .set_title(tr("Exporter une sauvegarde"))
                            .set_file_name("nautile.backup")
                            .save_file()
                            .await
                            .ok_or_else(String::new)?;
                        file.write(text.as_bytes()).await.map_err(|e| e.to_string())?;
                        Ok(file.file_name())
                    },
                    Message::ExportDone,
                );
            }
            Message::ExportDone(r) => {
                self.export.busy = false;
                return match r {
                    Ok(name) => {
                        self.export = ExportState::default();
                        self.say(trf("Sauvegarde enregistrée : %1$s", &[&name]))
                    }
                    Err(e) if e.is_empty() => Task::none(),
                    Err(e) => self.fail(e),
                };
            }

            // --- Settings ---
            Message::Language(l) => {
                self.settings.language = l;
                i18n::set_english(self.settings.english());
                let _ = self.settings.save(&self.paths);
            }
            Message::Appearance(a) => {
                self.settings.appearance = a;
                let _ = self.settings.save(&self.paths);
            }
            Message::TermTheme(id) => {
                self.settings.terminal_theme = Some(id);
                let _ = self.settings.save(&self.paths);
                let theme = self.term_theme();
                for t in &mut self.tabs {
                    t.term.palette = theme.clone();
                }
            }
            Message::TermFont(size) => {
                self.settings.terminal_font = Some(size.clamp(9.0, 28.0));
                let _ = self.settings.save(&self.paths);
            }

            // --- Lock ---
            Message::LockInput(v) => {
                self.lock_input = v;
                self.lock_error = None;
            }
            Message::Unlock => return self.unlock(),
            Message::LockNow => {
                if self.settings.locks() {
                    self.locked = true;
                    self.lock_input.clear();
                    return iced::widget::operation::focus("lock");
                }
            }
            Message::Tick => {
                let after = self.settings.lock_after() as u64 * 60;
                if self.settings.locks() && after > 0 && self.last_activity.elapsed().as_secs() >= after {
                    self.locked = true;
                    self.lock_input.clear();
                    return iced::widget::operation::focus("lock");
                }
            }
            Message::LockPick(m) => {
                self.lock_form = LockForm {
                    method: m,
                    ..LockForm::default()
                }
            }
            Message::LockCurrent(v) => self.lock_form.current = v,
            Message::LockNew(v) => self.lock_form.new = v,
            Message::LockConfirm(v) => self.lock_form.confirm = v,
            Message::LockSave => return self.save_lock(),
            Message::BackupTick => return self.backup_tick(),
            Message::Backup(m) => return self.backup_update(m),
            Message::Ai(m) => return self.ai_update(m),
            Message::LockAfter(m) => {
                self.settings.lock_after = Some(m);
                let _ = self.settings.save(&self.paths);
            }

            // --- Sessions ---
            Message::Connect(id) => return self.connect(id),
            Message::OpenFiles(id) => return self.open_files(id),
            Message::Files(id, m) => self.on_files(id, m),
            Message::Sftp(id, u) => return self.on_sftp(id, u),
            Message::Remote(id, e) => return self.on_remote(id, e),
            Message::RemoteAction(id, a) => self.on_remote_action(id, a),
            Message::FileDropped(p) => return self.dropped(p),
            Message::OpenLocal => return self.open_local(),
            Message::SelectTab(id) => {
                self.active_tab = id;
                if id.is_some() {
                    self.last_tab = id;
                }
                self.editor = None;
            }
            Message::CloseTab(id) => self.ask_close(id),
            Message::Reconnect(id) => return self.reconnect(id),
            Message::NextTab(forward) => self.cycle_tab(forward),
            Message::CloseActiveTab => {
                if let Some(id) = self.active_tab {
                    self.ask_close(id);
                }
            }
            Message::Session(id, e) => return self.on_session(id, e),
            Message::Term(id, a) => self.on_term(id, a),
            Message::PromptInput(v) => self.prompt_input = v,
            Message::PromptAnswer(ok) => self.answer(ok),
            Message::RunSnippet(id) => return self.run_snippet(id),
            Message::SnippetVar(i, v) => {
                if let Some((_, vars)) = &mut self.snippet_run {
                    if let Some(x) = vars.get_mut(i) {
                        x.1 = v;
                    }
                }
            }
            Message::SnippetRunGo => self.run_filled_snippet(),
            Message::SnippetRunCancel => self.snippet_run = None,
            Message::ToggleTunnel(id) => return self.toggle_tunnel(id),
            Message::Tunnel(id, e) => return self.on_tunnel(id, e),
        }
        Task::none()
    }
}

/// The mouse while the sidebar's edge is dragged.
fn sidebar_drag(e: iced::Event, _: iced::event::Status, _: iced::window::Id) -> Option<Message> {
    use iced::mouse::{Button, Event as M};
    match e {
        iced::Event::Mouse(M::CursorMoved { position }) => Some(Message::SidebarMove(position.x)),
        iced::Event::Mouse(M::ButtonReleased(Button::Left)) => Some(Message::SidebarRelease),
        _ => None,
    }
}

fn shortcut(e: iced::keyboard::Event) -> Option<Message> {
    use iced::keyboard::{key::Named, Event, Key};
    let Event::KeyPressed { key, modifiers: m, .. } = e else {
        return None;
    };
    match key.as_ref() {
        Key::Named(Named::Tab) if m.control() => Some(Message::NextTab(!m.shift())),
        Key::Character("t" | "T") if m.control() && m.shift() => Some(Message::OpenLocal),
        Key::Character("w" | "W") if m.control() && m.shift() => Some(Message::CloseActiveTab),
        Key::Character("a" | "A") if m.control() && m.shift() => Some(Message::Ai(crate::assistant::AiMsg::Toggle)),
        Key::Character("p" | "P") if m.control() && m.shift() => Some(Message::TypePassword),
        Key::Named(Named::Escape) => Some(Message::Escape),
        _ => None,
    }
}

impl App {
    /// Gives a terminal font to the renderer, once.
    fn load_font(&mut self, id: &str) -> Task<Message> {
        let f = crate::term::fonts::by_id(id);
        match f.bytes {
            Some(bytes) if !self.fonts_loaded.contains(&f.id) => {
                self.fonts_loaded.push(f.id);
                iced::font::load(bytes).map(|_| Message::FontLoaded)
            }
            _ => Task::none(),
        }
    }

    /// Closes a session, asking first when it is connected (« Confirmer la fermeture »).
    /// Keeps the window's size and place and the page shown, for the next start.
    fn remember_window(&mut self) {
        self.settings.window = Some(crate::storage::WindowState {
            width: self.window.width,
            height: self.window.height,
            x: self.window_pos.map(|p| p.x),
            y: self.window_pos.map(|p| p.y),
        });
        self.settings.last_page = Some(
            match self.page {
                Page::Hosts => "hosts",
                Page::Snippets => "snippets",
                Page::Keys => "keys",
                Page::Tunnels => "tunnels",
                Page::Settings => "settings",
            }
            .into(),
        );
        let _ = self.settings.save(&self.paths);
    }

    /// A system notification, only while the window is hidden or in the background.
    pub fn notify(&self, title: String, body: String) {
        if self.settings.notifications() && (self.hidden || !self.focused) {
            crate::tray::notify(title, body);
        }
    }

    fn ask_close(&mut self, id: u64) {
        let open = self.tab(id).is_some_and(|t| t.state == crate::sessions::TabState::Open);
        if open && self.settings.confirm_close() {
            self.closing = Some(id);
        } else {
            self.close_tab(id);
        }
    }

    fn unlock(&mut self) -> Task<Message> {
        use nautile_core::lock;
        let now = nautile_core::model::now_ms();
        if now < self.settings.lock_until {
            let secs = (self.settings.lock_until - now + 999) / 1000;
            self.lock_error = Some(trf("Trop d'essais : réessayez dans %1$s s", &[&secs]));
            return Task::none();
        }
        let ok = self
            .settings
            .lock_hash
            .as_deref()
            .is_some_and(|h| lock::verify(&self.lock_input, h));
        self.lock_input.clear();
        if ok {
            self.locked = false;
            self.lock_error = None;
            self.settings.lock_failures = 0;
            self.settings.lock_until = 0;
        } else {
            self.settings.lock_failures += 1;
            let wait = lock::lockout_ms(self.settings.lock_failures);
            self.settings.lock_until = now + wait as i64;
            let left = lock::FREE_TRIES.saturating_sub(self.settings.lock_failures);
            self.lock_error = Some(if wait > 0 {
                trf("Trop d'essais : réessayez dans %1$s s", &[&(wait / 1000)])
            } else {
                trf("Code incorrect, %1$s essai(s) restant(s)", &[&left])
            });
        }
        let _ = self.settings.save(&self.paths);
        Task::none()
    }

    /// Saves the lock chosen in the settings (the current code is checked first when there is one).
    fn save_lock(&mut self) -> Task<Message> {
        use nautile_core::lock::{self, LockMethod};
        let f = self.lock_form.clone();
        if let Some(h) = self.settings.lock_hash.as_deref().filter(|_| self.settings.locks()) {
            if !lock::verify(&f.current, h) {
                self.lock_form.error = Some(tr("Code actuel incorrect"));
                return Task::none();
            }
        }
        match f.method {
            None => {
                self.settings.lock_method = None;
                self.settings.lock_hash = None;
            }
            Some(m) => {
                let valid = match m {
                    LockMethod::Pin => lock::valid_pin(&f.new),
                    LockMethod::Password => lock::valid_password(&f.new),
                };
                if !valid {
                    self.lock_form.error = Some(match m {
                        LockMethod::Pin => tr("Le code PIN doit faire 4 à 8 chiffres"),
                        LockMethod::Password => tr("Le mot de passe doit faire au moins 6 caractères"),
                    });
                    return Task::none();
                }
                if f.new != f.confirm {
                    self.lock_form.error = Some(tr("Les deux saisies ne correspondent pas"));
                    return Task::none();
                }
                self.settings.lock_method = Some(m);
                self.settings.lock_hash = Some(lock::hash(&f.new));
            }
        }
        self.settings.lock_failures = 0;
        self.settings.lock_until = 0;
        let _ = self.settings.save(&self.paths);
        self.lock_form = LockForm {
            method: self.settings.lock_method,
            ..LockForm::default()
        };
        self.say(if self.settings.locks() {
            tr("Verrouillage activé")
        } else {
            tr("Verrouillage désactivé")
        })
    }
}

fn dropped_file(e: iced::Event, _status: iced::event::Status, _window: iced::window::Id) -> Option<Message> {
    match e {
        iced::Event::Window(iced::window::Event::FileDropped(p)) => Some(Message::FileDropped(p)),
        _ => None,
    }
}
