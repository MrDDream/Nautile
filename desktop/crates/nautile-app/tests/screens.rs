//! Renders the screens without a display (iced_test, tiny-skia), in dark and light:
//! `NAUTILE_SCREENSHOTS=1 cargo test -p nautile-app --test screens` writes them to desktop/screenshots.

use nautile_app::app::{App, Message, Page, SettingsPage};
use nautile_app::storage::{Appearance, Paths, Settings};
use nautile_app::{theme, ui};
use nautile_core::db::Db;
use nautile_core::model::{AuthMethod, Host, HostGroup, Protocol, Snippet, SshKey};
use std::path::PathBuf;

fn group(name: &str, color: i64, order: i32) -> HostGroup {
    serde_json::from_value(serde_json::json!({ "name": name, "color": color, "sortOrder": order })).unwrap()
}

#[allow(clippy::too_many_arguments)]
fn host(alias: &str, hostname: &str, user: &str, port: i32, protocol: Protocol, group: i64, color: i64, tags: &str) -> Host {
    Host {
        username: user.into(),
        port,
        protocol,
        group_id: Some(group),
        color,
        tags: tags.into(),
        ..Host::new(alias, hostname)
    }
}

/// The same demo data as the Android screenshots.
fn demo(english: bool, dark: bool) -> App {
    nautile_core::i18n::set_english(english);
    let db = Db::in_memory().unwrap();
    let s = db.store();
    let homelab = s.insert(&group("Homelab", 0xFFFFB547, 0)).unwrap();
    let vps = s.insert(&group("VPS", 0xFF7AA2F7, 1)).unwrap();
    let office = s.insert(&group(&nautile_core::tr("Bureau"), 0xFF5BD69A, 2)).unwrap();
    let key: SshKey = serde_json::from_value(serde_json::json!({
        "name": "laptop", "keyType": "ssh-ed25519", "description": "ED25519",
        "publicKey": "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGq0 laptop", "privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----",
        "fingerprint": "SHA256:Xq7rO0vqk5mHqJ8yQ1cD9g4w3s2Kp6Lr0fZt8uVbN2E", "encrypted": false
    }))
    .unwrap();
    let key_id = s.insert(&key).unwrap();
    let docker = Host {
        auth_method: AuthMethod::Key,
        key_id: Some(key_id),
        ..host(
            "docker-01",
            "10.0.0.20",
            "mrd",
            22,
            Protocol::Ssh,
            homelab,
            0xFFFFB547,
            "prod,docker",
        )
    };
    for h in [
        docker,
        host("nas", "nas.lan", "admin", 2222, Protocol::Mosh, homelab, 0xFF7AA2F7, ""),
        host("pihole", "10.0.0.53", "pi", 22, Protocol::Ssh, homelab, 0xFFF38BA8, "dns"),
        host("proxmox", "10.0.0.2", "root", 8022, Protocol::Ssh, homelab, 0xFFFFB547, ""),
        host("vps-paris", "vps.example.org", "deploy", 22, Protocol::Ssh, vps, 0xFF7AA2F7, "web"),
        host(
            "pc-bureau",
            "pc-bureau.lan",
            "Administrateur",
            3389,
            Protocol::Rdp,
            office,
            0xFF5BD69A,
            "",
        ),
        host("raspberry", "pi.lan", "", 5900, Protocol::Vnc, office, 0xFF5A6672, ""),
    ] {
        // Same OS and last connections as the Android screenshots.
        let now = nautile_core::model::now_ms();
        let (os, ago) = match h.alias.as_str() {
            "docker-01" => ("debian", 4),
            "nas" => ("truenas", 60),
            "pihole" => ("raspbian", 60 * 26),
            "proxmox" => ("proxmox", 60 * 50),
            "vps-paris" => ("ubuntu", 60 * 5),
            "pc-bureau" => ("windows", 60 * 24 * 9),
            _ => ("unknown", 0),
        };
        let h = Host {
            os_id: Some(os.into()),
            last_connected_at: (ago > 0).then(|| now - ago * 60_000),
            ..h
        };
        s.insert(&h).unwrap();
    }
    for (name, command, tag) in [
        (
            nautile_core::tr("Conteneurs Docker"),
            "docker ps -a --format 'table {{.Names}}\\t{{.Status}}'",
            "Docker",
        ),
        (
            nautile_core::tr("Journal d'un service"),
            "journalctl -u ${SERVICE} -n 100 --no-pager\nsystemctl status ${SERVICE}",
            "Système",
        ),
        (nautile_core::tr("Espace disque"), "df -h", "Système"),
    ] {
        let sn: Snippet =
            serde_json::from_value(serde_json::json!({ "name": name, "command": command, "tag": nautile_core::tr(tag) })).unwrap();
        s.insert(&sn).unwrap();
    }
    let docker_id = s.all::<Host>().unwrap().into_iter().find(|h| h.alias == "docker-01").unwrap().id;
    for v in [
        serde_json::json!({ "name": "Jellyfin", "hostId": docker_id, "kind": "LOCAL", "bindPort": 8096, "destHost": "127.0.0.1", "destPort": 8096, "autoStart": true }),
        serde_json::json!({ "name": "Proxy maison", "hostId": docker_id, "kind": "DYNAMIC", "bindPort": 1080 }),
    ] {
        s.insert(&serde_json::from_value::<nautile_core::model::PortForward>(v).unwrap())
            .unwrap();
    }
    s.insert(
        &serde_json::from_value::<nautile_core::model::Identity>(
            serde_json::json!({ "name": "admin", "username": "root", "keyId": key_id }),
        )
        .unwrap(),
    )
    .unwrap();
    s.insert(
        &serde_json::from_value::<nautile_core::model::KnownHost>(serde_json::json!({
            "host": "10.0.0.20", "port": 22, "keyType": "ssh-ed25519", "publicKey": "AAAAC3NzaC1lZDI1NTE5AAAAIGq0",
            "fingerprint": "SHA256:Xq7rO0vqk5mHqJ8yQ1cD9g4w3s2Kp6Lr0fZt8uVbN2E"
        }))
        .unwrap(),
    )
    .unwrap();
    let settings = Settings {
        appearance: if dark { Appearance::Dark } else { Appearance::Light },
        ..Settings::default()
    };
    App::with(
        Paths {
            data: PathBuf::from("/home/mrd/.local/share/nautile"),
            portable: false,
        },
        settings,
        Some(db),
        None,
    )
}

/// The app's fonts and the terminal ones (loaded on demand in the app).
fn test_fonts() -> Vec<std::borrow::Cow<'static, [u8]>> {
    let mut f: Vec<std::borrow::Cow<'static, [u8]>> = vec![theme::FONT_UI.into(), theme::FONT_MONO.into(), theme::FONT_ICONS.into()];
    f.extend(
        nautile_app::term::fonts::ALL
            .iter()
            .filter_map(|x| x.bytes)
            .map(std::borrow::Cow::from),
    );
    f
}

fn shoot(app: &App, name: &str) {
    // The settings page is long: shown whole.
    let height = if name.ends_with("backups") { 1600.0 } else { 800.0 };
    let settings = iced_test::core::Settings {
        fonts: test_fonts(),
        default_font: theme::UI,
        ..Default::default()
    };
    let mut ui = iced_test::simulator::Simulator::with_size(settings, (1280.0, height), ui::view(app));
    let snapshot = ui.snapshot(&app.theme()).expect("renders");
    if std::env::var_os("NAUTILE_SCREENSHOTS").is_some() {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../screenshots");
        let path = dir.join(format!("{name}.png"));
        // matches_image only writes a missing file: start from a clean one.
        for e in std::fs::read_dir(&dir).into_iter().flatten().flatten() {
            // Only this shot's files (« settings » must not remove « settings-ai »).
            let file = e.file_name().to_string_lossy().into_owned();
            if file
                .strip_prefix(&format!("{name}-"))
                .is_some_and(|rest| !rest.contains('-') || rest == "tiny-skia.png")
            {
                let _ = std::fs::remove_file(e.path());
            }
        }
        assert!(snapshot.matches_image(&path).unwrap());
    }
}

/// The language is global: one screen at a time.
static SERIAL: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn each(f: impl Fn(&mut App) -> &'static str) {
    let _one = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    for (lang, english) in [("fr", false), ("en", true)] {
        for (mode, dark) in [("dark", true), ("light", false)] {
            let mut app = demo(english, dark);
            let name = f(&mut app);
            shoot(&app, &format!("{lang}-{mode}-{name}"));
        }
    }
}

#[test]
fn hosts() {
    each(|_| "hosts");
}

#[test]
fn host_editor() {
    each(|app| {
        let id = app.hosts.iter().find(|h| h.alias == "docker-01").unwrap().id;
        let _ = app.update(Message::EditHost(id));
        "host-editor"
    });
}

#[test]
fn new_host() {
    each(|app| {
        let _ = app.update(Message::NewHost);
        "new-host"
    });
}

#[test]
fn groups() {
    each(|app| {
        let _ = app.update(Message::OpenModal(Some(nautile_app::app::Modal::Groups)));
        "groups"
    });
}

#[test]
fn compact_sidebar() {
    each(|app| {
        let _ = app.update(Message::SidebarToggle);
        let _ = app.update(Message::Nav(Page::Snippets));
        "compact-sidebar"
    });
}

#[test]
fn snippets() {
    each(|app| {
        let _ = app.update(Message::Nav(Page::Snippets));
        let id = app.snippets[0].id;
        let _ = app.update(Message::EditSnippet(id));
        "snippets"
    });
}

#[test]
fn keys() {
    each(|app| {
        let _ = app.update(Message::Nav(Page::Keys));
        "keys"
    });
}

#[test]
fn tunnels() {
    each(|app| {
        let _ = app.update(Message::Nav(Page::Tunnels));
        let id = app.forwards[0].id;
        let _ = app.update(Message::EditForward(id));
        "tunnels"
    });
}

#[test]
fn identities() {
    each(|app| {
        let _ = app.update(Message::Nav(Page::Keys));
        let _ = app.update(Message::KeysTab(nautile_app::app::KeysTab::Identities));
        "identities"
    });
}

#[test]
fn settings() {
    each(|app| {
        let _ = app.update(Message::Nav(Page::Settings));
        "settings"
    });
}

#[test]
fn settings_appearance() {
    each(|app| {
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::Appearance)));
        let _ = app.update(Message::Accent(nautile_app::theme::Accent::Lagon));
        let _ = app.update(Message::ButtonStyle(nautile_app::theme::ButtonStyle::Tonal));
        "settings-appearance"
    });
}

#[test]
fn oled_hosts() {
    each(|app| {
        let _ = app.update(Message::Appearance(nautile_app::storage::Appearance::Oled));
        let _ = app.update(Message::Accent(nautile_app::theme::Accent::Lavande));
        let _ = app.update(Message::ButtonStyle(nautile_app::theme::ButtonStyle::Contour));
        "oled-hosts"
    });
}

#[test]
fn settings_terminal() {
    each(|app| {
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::Terminal)));
        "settings-terminal"
    });
}

#[test]
fn settings_terminal_sessions() {
    each(|app| {
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::Terminal)));
        let _ = app.update(Message::TermTab(nautile_app::app::TermTab::Sessions));
        let _ = app.update(Message::TermCursor("bar".into()));
        let _ = app.update(Message::TermFamily("cascadia".into()));
        "settings-terminal-sessions"
    });
}

/// Two providers with a key, plus the local one.
fn with_ai(app: &mut App) {
    use nautile_net::ai::ProviderKind as K;
    let db = app.db.as_ref().unwrap();
    db.set_secret("ai-key-CLAUDE", Some("sk-ant-demo")).unwrap();
    db.set_secret("ai-key-OPENAI", Some("sk-demo")).unwrap();
    app.settings.ai_enabled = vec![K::Claude, K::Openai, K::Local];
    app.settings.ai_models.insert("CLAUDE".into(), "claude-sonnet-5-5".into());
    app.settings.ai_models.insert("OPENAI".into(), "gpt-5-mini".into());
    app.settings.ai_models.insert("LOCAL".into(), "qwen3:8b".into());
    app.settings.ai_prompts = vec![nautile_app::storage::AiPrompt {
        id: "p1".into(),
        name: "Pédagogue".into(),
        text: "Explique chaque option de la commande, pas à pas.".into(),
    }];
}

#[test]
fn settings_ai() {
    each(|app| {
        with_ai(app);
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::Ai)));
        "settings-ai"
    });
}

#[test]
fn settings_ai_provider() {
    each(|app| {
        with_ai(app);
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::Ai)));
        let _ = app.update(Message::Ai(nautile_app::assistant::AiMsg::Provider(
            nautile_net::ai::ProviderKind::Claude,
        )));
        "settings-ai-provider"
    });
}

#[test]
fn settings_ai_models() {
    each(|app| {
        use nautile_app::assistant::AiMsg as A;
        with_ai(app);
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::Ai)));
        let _ = app.update(Message::Ai(A::Provider(nautile_net::ai::ProviderKind::Openai)));
        let models = [
            "gpt-5",
            "gpt-5-mini",
            "gpt-5-nano",
            "o4-mini",
            "o3",
            "gpt-4.1",
            "text-embedding-3-large",
            "tts-1",
        ];
        let _ = app.update(Message::Ai(A::Models(Ok(models.map(String::from).to_vec()))));
        let _ = app.update(Message::Ai(A::OpenModels));
        "settings-ai-models"
    });
}

#[test]
fn settings_system() {
    each(|app| {
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::System)));
        "settings-system"
    });
}

#[test]
fn update_available() {
    each(|app| {
        use nautile_app::update::UpdateMsg as U;
        use nautile_net::update::{Asset, Release};
        let asset = |n: &str| Asset {
            name: n.into(),
            browser_download_url: format!("https://example.org/{n}"),
            size: 31_000_000,
        };
        let release = Release {
            tag_name: "v0.5.0".into(),
            name: Some("Nautile v0.5.0".into()),
            body: Some("## Nouveautés\n\n- **Bureau** : formulaires en pop-up, barre latérale réglable\n- Synchro qui attend le VPN\n- Mises à jour depuis l'appli\n\n## Corrections\n\n- Le terminal garde sa police après une reconnexion".into()),
            prerelease: false,
            html_url: "https://github.com/MrDDream/Nautile/releases/tag/v0.5.0".into(),
            assets: ["nautile-0.5.0-linux-x86_64.tar.gz", "Nautile-0.5.0-setup.exe", "Nautile-0.5.0-portable.zip", "nautile_0.5.0_amd64.deb"]
                .map(asset)
                .to_vec(),
        };
        let _ = app.update(Message::Update(U::Checked(true, Ok(release))));
        let _ = app.update(Message::Update(U::Progress(12_400_000, 31_000_000)));
        "update-available"
    });
}

#[test]
fn quick_connect() {
    each(|app| {
        let _ = app.update(Message::Search("root@192.168.1.40".into()));
        "quick-connect"
    });
}

#[test]
fn connections() {
    each(|app| {
        use nautile_core::db::ConnectionLog;
        let _ = with_session(app);
        let now = nautile_core::model::now_ms();
        let docker = app.hosts.iter().find(|h| h.alias == "docker-01").unwrap().clone();
        let db = app.db.as_ref().unwrap();
        let log = |host: Option<i64>, alias: &str, p: &str, user: &str, host_name: &str, port: i32, ago: i64| ConnectionLog {
            id: 0,
            host_id: host,
            alias: alias.into(),
            protocol: p.into(),
            username: user.into(),
            hostname: host_name.into(),
            port,
            at: now - ago,
        };
        for e in [
            log(Some(docker.id), "docker-01", "SSH", "mrd", "10.0.0.20", 22, 4 * 60_000),
            log(None, "root@192.168.1.40", "SSH", "root", "192.168.1.40", 22, 50 * 60_000),
            log(None, "admin@pc-salon", "RDP", "admin", "pc-salon", 3389, 26 * 3_600_000),
        ] {
            db.log_connection(&e).unwrap();
        }
        let _ = app.update(Message::Nav(Page::Connections));
        "connections"
    });
}

fn sample_import() -> Vec<nautile_core::import::ImportedHost> {
    nautile_core::import::parse(
        "Host bastion\n  HostName bastion.example.org\n  User ops\n\nHost db-1\n  HostName 10.1.0.5\n  ProxyJump bastion\n  User postgres\n\nHost docker-01\n  HostName 10.0.0.20\n",
    )
    .unwrap()
}

#[test]
fn import_hosts_preview() {
    each(|app| {
        let _ = app.update(Message::ImportHostsRead(Some(Ok(sample_import()))));
        "import-hosts"
    });
}

#[test]
fn importing_hosts_links_jump_hosts_and_skips_existing_ones() {
    let _one = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let mut app = demo(false, true);
    let before = app.hosts.len();
    let _ = app.update(Message::ImportHostsRead(Some(Ok(sample_import()))));
    let _ = app.update(Message::ImportHostsRun);
    assert_eq!(app.hosts.len(), before + 2, "docker-01 already there");
    let bastion = app.hosts.iter().find(|h| h.alias == "bastion").unwrap().id;
    let db = app.hosts.iter().find(|h| h.alias == "db-1").unwrap();
    assert_eq!(
        (db.hostname.as_str(), db.username.as_str(), db.jump_host_id),
        ("10.1.0.5", "postgres", Some(bastion))
    );
}

#[test]
fn monitor() {
    each(|app| {
        let id = with_session(app);
        let t = app.tabs.iter_mut().find(|t| t.id == id).unwrap();
        t.name = "docker-01 — moniteur".into();
        let mut m = nautile_app::sessions::MonitorState::default();
        let (mut total, mut idle) = (3_700_000u64, 3_600_000u64);
        for i in 0..30u64 {
            let busy = [12, 18, 35, 22, 64, 41, 27, 15, 33, 52][(i % 10) as usize];
            total += 400;
            idle += 400 - busy * 4;
            m.push(&format!(
                "cpu  {} 0 0 {idle} 0 0 0 0 0 0\nMemTotal: 8040260 kB\nMemAvailable: {} kB\n/dev/sda1 61255492 22849808 35264996 38% /\n0.42 0.36 0.30 2/412 31337\n3566841.35 361072.11\n4\n",
                total - idle,
                5_023_412 - i * 12_000,
            ));
        }
        t.monitor = Some(Box::new(m));
        "monitor"
    });
}

#[test]
fn sftp_editor() {
    each(|app| {
        let id = with_session(app);
        let t = app.tabs.iter_mut().find(|t| t.id == id).unwrap();
        t.name = "docker-01 — fichiers".into();
        let mut f = nautile_app::files::Files::new();
        let text = "services:\n  jellyfin:\n    image: jellyfin/jellyfin:10.10\n    restart: unless-stopped\n    ports:\n      - \"8096:8096\"\n    volumes:\n      - ./config:/config\n      - /srv/media:/media:ro\n";
        f.editor = Some(nautile_app::files::RemoteEditor {
            path: "/home/mrd/compose/jellyfin.yml".into(),
            content: iced::widget::text_editor::Content::with_text(&text.replace("10.10", "10.11")),
            original: text.into(),
            mtime: Some(1),
            saving: false,
            error: Some("Le fichier a changé sur le serveur depuis son ouverture".into()),
            conflict: true,
        });
        t.files = Some(Box::new(f));
        "sftp-editor"
    });
}

#[test]
fn importing_a_theme_and_a_font_uses_them() {
    let _one = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let mut app = demo(false, true);
    let tmp = std::env::temp_dir().join(format!("nautile-import-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&tmp);
    app.paths.data = tmp.clone();
    let toml = "[colors.primary]\nbackground = '#101820'\nforeground = '#e0e0e0'\n[colors.normal]\nblack = '#000000'\nred = '#ff5555'\ngreen = '#50fa7b'\nyellow = '#f1fa8c'\nblue = '#6272a4'\nmagenta = '#ff79c6'\ncyan = '#8be9fd'\nwhite = '#bbbbbb'\n";
    let _ = app.update(Message::ThemeFile(Some(("Mer du Nord".into(), toml.into()))));
    assert_eq!(app.settings.terminal_theme(), "custom-mer-du-nord");
    assert_eq!(app.term_theme().name, "Mer du Nord");
    let _ = app.update(Message::FontFile(Some((
        "Hack.ttf".into(),
        include_bytes!("../assets/fonts/HackNerd.ttf").to_vec(),
    ))));
    assert_eq!(app.settings.terminal_family().name, "Hack Nerd Font Mono");
    let _ = app.update(Message::DeleteTheme("custom-mer-du-nord".into()));
    let _ = app.update(Message::DeleteFont("file:Hack.ttf".into()));
    assert_eq!(
        (app.settings.terminal_theme(), app.settings.terminal_family().id),
        ("nautile", "jetbrains")
    );
    let _ = std::fs::remove_dir_all(tmp);
}

/// A session where `docker compose ps` and `docker logs -f jellyfin` were typed, and `do` is being typed.
fn with_history(app: &mut App) -> u64 {
    use nautile_app::term::widget::Action;
    let id = with_session(app);
    let p = "\x1b[1;32mmrd@docker-01\x1b[0m:\x1b[1;34m~\x1b[0m$ ";
    for cmd in ["docker compose ps", "docker logs -f jellyfin", "docker compose ps"] {
        let t = app.tabs.iter_mut().find(|t| t.id == id).unwrap();
        t.term.feed(cmd.as_bytes());
        let _ = app.update(Message::Term(id, Action::Input(b"\r".to_vec())));
        let t = app.tabs.iter_mut().find(|t| t.id == id).unwrap();
        t.term.feed(format!("\r\n{p}").as_bytes());
    }
    let t = app.tabs.iter_mut().find(|t| t.id == id).unwrap();
    t.term.feed(b"do");
    id
}

#[test]
fn typed_commands_are_suggested_again() {
    let _one = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let mut app = demo(false, true);
    let id = with_history(&mut app);
    let (typed, list) = app.suggestions(id);
    assert_eq!(typed, "do");
    assert_eq!(list, ["docker compose ps", "docker logs -f jellyfin"], "most used first");
    let host = app.tab(id).unwrap().host_id.unwrap();
    assert_eq!(app.db.as_ref().unwrap().commands(host)[0], ("docker compose ps".to_owned(), 2));
}

#[test]
fn terminal_suggestions() {
    each(|app| {
        let _ = with_history(app);
        "terminal-suggestions"
    });
}

#[test]
fn settings_remote() {
    each(|app| {
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::Remote)));
        "settings-remote"
    });
}

#[test]
fn palette() {
    each(|app| {
        let _ = app.update(Message::Palette(true));
        let _ = app.update(Message::PaletteQuery("doc".into()));
        let _ = app.update(Message::PaletteMove(1));
        "palette"
    });
}

#[test]
fn host_menu() {
    each(|app| {
        let id = app.hosts.iter().find(|h| h.alias == "nas").unwrap().id;
        let _ = app.update(Message::HostMenu(id, iced::Point::new(900.0, 420.0)));
        let _ = app.update(Message::MenuMove);
        "host-menu"
    });
}

#[test]
fn palette_menu_and_drag_do_what_they_say() {
    use nautile_app::app::{Drag, Drop};
    let _one = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let mut app = demo(false, true);
    // Ctrl+K, « prox », Enter: connects.
    let _ = app.update(Message::Palette(true));
    let _ = app.update(Message::PaletteQuery("prox".into()));
    let _ = app.update(Message::PaletteEnter);
    assert!(app.palette.is_none());
    assert_eq!(app.tabs.last().map(|t| t.name.as_str()), Some("proxmox"));
    // A host dragged onto a group.
    let pi = app.hosts.iter().find(|h| h.alias == "pihole").unwrap().id;
    let vps = app.groups.iter().find(|g| g.name == "VPS").unwrap().id;
    let _ = app.update(Message::DragStart(Drag::Host(pi)));
    let _ = app.update(Message::DropOver(Some(Drop::Group(Some(vps)))));
    let _ = app.update(Message::DragEnd);
    assert_eq!(app.hosts.iter().find(|h| h.id == pi).unwrap().group_id, Some(vps));
    // Duplicated from its menu.
    let before = app.hosts.len();
    let _ = app.update(Message::DuplicateHost(pi));
    assert_eq!(app.hosts.len(), before + 1);
    assert!(app.hosts.iter().any(|h| h.alias == "pihole (copie)"));
    // Removed, after a confirmation.
    let copy = app.hosts.iter().find(|h| h.alias == "pihole (copie)").unwrap().id;
    let _ = app.update(Message::HostMenu(copy, iced::Point::ORIGIN));
    let _ = app.update(Message::DeleteHostNow(copy));
    assert_eq!(app.hosts.len(), before + 1, "asks first");
    let _ = app.update(Message::DeleteHostNow(copy));
    assert_eq!(app.hosts.len(), before);
}

#[test]
fn settings_lock() {
    each(|app| {
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::Security)));
        let _ = app.update(Message::LockPick(Some(nautile_core::lock::LockMethod::Pin)));
        "settings-lock"
    });
}

#[test]
fn editing_a_host_saves_it() {
    let _one = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let mut app = demo(false, true);
    let _ = app.update(Message::NewHost);
    assert!(app.editor.is_none(), "the kind comes first");
    let _ = app.update(Message::NewHostOf(nautile_core::model::Protocol::Ssh));
    for m in [
        Message::Host(nautile_app::app::HostField::Alias("bastion".into())),
        Message::Host(nautile_app::app::HostField::Hostname("b.example.org".into())),
        Message::Host(nautile_app::app::HostField::Username("ops".into())),
    ] {
        let _ = app.update(m);
    }
    let _ = app.update(Message::SaveHost);
    let h = app.hosts.iter().find(|h| h.alias == "bastion").expect("saved");
    assert_eq!((h.hostname.as_str(), h.port, h.username.as_str()), ("b.example.org", 22, "ops"));
    assert_eq!(h.sync_id.len(), 32, "synced like on Android");
}

/// A session as it looks after a few commands (same content as the Android terminal screenshot).
fn with_session(app: &mut App) -> u64 {
    use nautile_app::sessions::{Tab, TabState};
    use nautile_app::term::Terminal;
    let host = app.hosts.iter().find(|h| h.alias == "docker-01").unwrap().clone();
    let mut term = Terminal::new(132, 38, app.term_theme(), app.cursor_shape());
    let p = "\x1b[1;32mmrd@docker-01\x1b[0m:\x1b[1;34m~\x1b[0m$ ";
    let demo = format!(
        "{p}uptime\r\n 14:02:11 up 41 days,  3:12,  1 user,  load average: 0.31, 0.27, 0.22\r\n\
         {p}ls --color\r\n\x1b[1;34mcompose\x1b[0m  \x1b[1;34mbackups\x1b[0m  \x1b[1;32mdeploy.sh\x1b[0m  notes.md  \x1b[1;31mrelease.tar.gz\x1b[0m\r\n\
         {p}docker ps --format 'table {{{{.Names}}}}\\t{{{{.Status}}}}'\r\n\
         NAMES        STATUS\r\njellyfin     Up 3 days (healthy)\r\nnextcloud    Up 3 days\r\nnginx        Up 12 days\r\n\
         {p}git status -sb\r\n## \x1b[32mmain\x1b[0m...\x1b[31morigin/main\x1b[0m [ahead 1]\r\n \x1b[31mM\x1b[0m compose/jellyfin.yml\r\n\x1b[32mA\x1b[0m  compose/immich.yml\r\n\
         {p}echo \"$TERM · 256 couleurs\" && for i in $(seq 16 51); do printf '\\e[48;5;%sm  ' $i; done; echo\r\n\
         xterm-256color · 256 couleurs\r\n"
    );
    term.feed(demo.as_bytes());
    let mut bar = String::new();
    for i in 16..52 {
        bar.push_str(&format!("\x1b[48;5;{i}m  "));
    }
    term.feed(format!("{bar}\x1b[0m\r\n{p}").as_bytes());
    app.tabs.push(Tab {
        id: 1,
        host_id: Some(host.id),
        name: host.alias.clone(),
        color: host.color,
        protocol: host.protocol,
        term,
        input: None,
        state: TabState::Open,
        remote_title: Some("mrd@docker-01: ~".into()),
        files: None,
        remote: None,
        log: None,
        monitor: None,
    });
    app.next_tab = 1;
    let _ = app.update(Message::SelectTab(Some(1)));
    1
}

#[test]
fn terminal() {
    each(|app| {
        with_session(app);
        "terminal"
    });
}

#[test]
fn host_key_question() {
    each(|app| {
        with_session(app);
        let (session, _events, _input) = nautile_net::session::channels();
        drop(session);
        let _ = app.update(Message::Session(
            1,
            nautile_net::Event::Prompt(nautile_net::Prompt::HostKey {
                label: "vps-paris".into(),
                info: nautile_net::HostKeyInfo {
                    host: "vps.example.org".into(),
                    port: 22,
                    key_type: "ssh-ed25519".into(),
                    public_key: String::new(),
                    fingerprint: "SHA256:PhDBqaTp44bX5NNvghkgs0U/CrJL/eZA5N1z61PFORg".into(),
                },
                previous: None,
                reply: test_reply(),
            }),
        ));
        "host-key"
    });
}

fn test_reply() -> nautile_net::session::Reply<bool> {
    nautile_net::session::Reply::detached()
}

#[test]
fn typing_in_the_terminal_sends_the_keys() {
    let _one = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let mut app = demo(false, true);
    with_session(&mut app);
    let settings = iced_test::core::Settings {
        fonts: test_fonts(),
        default_font: theme::UI,
        ..Default::default()
    };
    let mut ui = iced_test::simulator::Simulator::with_size(settings, (1280.0, 800.0), ui::view(&app));
    ui.typewrite("ls");
    ui.tap_key(iced::keyboard::key::Named::Enter);
    let sent: Vec<u8> = ui
        .into_messages()
        .filter_map(|m| match m {
            Message::Term(1, nautile_app::term::widget::Action::Input(b)) => Some(b),
            _ => None,
        })
        .flatten()
        .collect();
    assert_eq!(sent, b"ls\r");
}

#[test]
fn a_generated_key_is_saved_like_on_android() {
    let _one = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let mut app = demo(false, true);
    let _ = app.update(Message::NewKey(true));
    let _ = app.update(Message::KeyName("bureau".into()));
    let k = nautile_net::keys::generate(nautile_net::keys::KeyAlgorithm::Ed25519, "bureau").unwrap();
    let _ = app.update(Message::KeyReady(Ok(k.clone())));
    let saved = app.keys.iter().find(|x| x.name == "bureau").expect("saved");
    assert_eq!(saved.fingerprint, k.fingerprint);
    assert_eq!((saved.key_type.as_str(), saved.description.as_str()), ("ssh-ed25519", "ED25519"));
    assert!(saved.generated && !saved.encrypted);
    assert_eq!(saved.sync_id.len(), 32);
}

#[test]
fn the_lock_takes_a_pin() {
    let _one = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let mut app = demo(false, true);
    let _ = app.update(Message::LockPick(Some(nautile_core::lock::LockMethod::Pin)));
    let _ = app.update(Message::LockNew("12".into()));
    let _ = app.update(Message::LockConfirm("12".into()));
    let _ = app.update(Message::LockSave);
    assert!(!app.settings.locks(), "too short");
    let _ = app.update(Message::LockNew("2468".into()));
    let _ = app.update(Message::LockConfirm("2468".into()));
    let _ = app.update(Message::LockSave);
    assert!(app.settings.locks());
    let _ = app.update(Message::LockNow);
    assert!(app.locked);
    let _ = app.update(Message::Nav(Page::Keys));
    assert_eq!(app.page, Page::Hosts, "nothing else while locked");
    let _ = app.update(Message::LockInput("1111".into()));
    let _ = app.update(Message::Unlock);
    assert!(app.locked && app.lock_error.is_some());
    let _ = app.update(Message::LockInput("2468".into()));
    let _ = app.update(Message::Unlock);
    assert!(!app.locked);
}

#[test]
fn lock_screen() {
    each(|app| {
        app.settings.lock_method = Some(nautile_core::lock::LockMethod::Pin);
        app.settings.lock_hash = Some(nautile_core::lock::hash_with("2468", 1000));
        let _ = app.update(Message::LockNow);
        "lock"
    });
}

#[test]
fn files() {
    each(|app| {
        use nautile_app::files::{Files, Job, Refresh};
        use nautile_net::sftp::Entry;
        with_session(app);
        let e = |name: &str, dir: bool, size: u64, t: i64| Entry {
            name: name.into(),
            dir,
            link: false,
            size,
            modified: Some(1_794_500_000 + t),
            mode: None,
        };
        let mut f = Files::new();
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        std::mem::forget(rx);
        f.cmd = Some(tx);
        f.local_path = PathBuf::from("/home/mrd/Projets");
        f.local = vec![
            e("nautile", true, 0, 0),
            e("site", true, 0, 9_000),
            e("compose.yml", false, 2_418, 50_000),
            e("photo-serveur.jpg", false, 3_481_210, 7_000),
        ];
        f.remote_path = "/home/mrd".into();
        f.remote_input = "/home/mrd".into();
        f.remote = vec![
            e("backups", true, 0, 0),
            e("compose", true, 0, 3_000),
            e(".bashrc", false, 3_771, 1_000),
            e("deploy.sh", false, 1_204, 20_000),
            e("release.tar.gz", false, 48_912_311, 30_000),
        ];
        f.selected = Some((true, "release.tar.gz".into()));
        f.jobs = vec![Job {
            id: 1,
            label: "↓ release.tar.gz".into(),
            done: 31_000_000,
            total: 48_912_311,
            finished: false,
            error: None,
            transfer: true,
            refresh: Refresh::Local,
        }];
        let t = app.tabs.iter_mut().find(|t| t.id == 1).unwrap();
        t.name = "docker-01 — fichiers".into();
        t.files = Some(Box::new(f));
        "files"
    });
}

#[test]
fn backups() {
    each(|app| {
        use nautile_app::backup::{Frequency, Peer, Target};
        use nautile_net::store::Dest;
        app.settings.targets = vec![
            Target {
                id: "nc".into(),
                dest: Dest::WebDav {
                    url: "https://cloud.example.org/remote.php/dav/files/mrd/Nautile/".into(),
                    username: "mrd".into(),
                },
                auto: false,
                frequency: Frequency::Week,
                time: 180,
                weekday: 7,
                last: Some(1_794_570_000_000),
                error: None,
            },
            Target {
                id: "usb".into(),
                dest: Dest::Folder {
                    path: PathBuf::from("/media/mrd/USB/Sauvegardes"),
                },
                auto: true,
                frequency: Frequency::Week,
                time: 180,
                weekday: 7,
                last: Some(1_794_000_000_000),
                error: None,
            },
        ];
        app.settings.sync_enabled = true;
        app.settings.sync_target = Some("nc".into());
        app.settings.last_sync = Some(1_794_578_000_000);
        app.settings.device_id = Some("me".into());
        app.settings.devices = vec![
            ("phone".into(), "Pixel 8".into(), 1_794_577_000_000),
            ("me".into(), "pc-bureau".into(), 1_794_578_000_000),
        ];
        app.settings.lan_enabled = true;
        app.settings.lan_peers = vec![Peer {
            id: "phone".into(),
            name: "Pixel 8".into(),
            last: 1_794_560_000_000,
        }];
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::Backup)));
        "backups"
    });
}

#[test]
fn backup_target() {
    each(|app| {
        use nautile_app::backup::{BackupMsg as B, Frequency, Target};
        use nautile_net::store::Dest;
        app.settings.targets = vec![Target {
            id: "usb".into(),
            dest: Dest::Folder {
                path: PathBuf::from("/media/mrd/USB/Sauvegardes"),
            },
            auto: true,
            frequency: Frequency::Week,
            time: 180,
            weekday: 7,
            last: Some(1_794_000_000_000),
            error: None,
        }];
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::Backup)));
        let _ = app.update(Message::Backup(B::Open(Some("usb".into()))));
        "backup-target"
    });
}

#[test]
fn backup_add_place() {
    each(|app| {
        use nautile_app::backup::{BackupMsg as B, PlaceKind};
        let _ = app.update(Message::Nav(Page::Settings));
        let _ = app.update(Message::SettingsPage(Some(SettingsPage::Backup)));
        let _ = app.update(Message::OpenModal(Some(nautile_app::app::Modal::AddPlace)));
        let _ = app.update(Message::Backup(B::NewPlace(PlaceKind::S3)));
        "backup-add-place"
    });
}

#[test]
fn assistant() {
    each(|app| {
        use nautile_app::assistant::{Shown, Turn};
        with_ai(app);
        let id = with_session(app);
        let host = app.tab(id).unwrap().host_id.unwrap();
        let en = nautile_core::i18n::is_english();
        let raw = if en {
            "```bash\ndocker logs --tail 50 jellyfin 2>&1 | grep -iE 'error|warn'\n```\nShows the **last 50 lines** of the `jellyfin` container, errors and warnings only.\n\n- `--tail 50`: only the end of the log\n- `2>&1`: the errors go through the filter too"
        } else {
            "```bash\ndocker logs --tail 50 jellyfin 2>&1 | grep -iE 'error|warn'\n```\nAffiche les **50 dernières lignes** du conteneur `jellyfin`, erreurs et avertissements seulement.\n\n- `--tail 50` : seulement la fin du journal\n- `2>&1` : les erreurs passent aussi par le filtre"
        };
        let turn = Turn {
            request: if en {
                "why does jellyfin keep restarting?"
            } else {
                "pourquoi jellyfin redémarre en boucle ?"
            }
            .into(),
            sent: String::new(),
            files: vec![],
            screen: true,
            provider: nautile_net::ai::ProviderKind::Claude,
            model: "claude-opus-5-5".into(),
            raw: raw.into(),
            at: 0,
        };
        app.assistant.conversations.insert(format!("host:{host}"), vec![Shown::new(turn)]);
        app.assistant.open = true;
        // What the terminal asks once laid out beside the panel.
        let _ = app.update(Message::Term(id, nautile_app::term::widget::Action::Resize(73, 38)));
        "assistant"
    });
}

#[test]
fn vnc_desktop() {
    each(|app| {
        use nautile_app::sessions::{RemoteTab, Tab, TabState};
        let host = app.hosts.iter().find(|h| h.alias == "raspberry").unwrap().clone();
        // A desktop: a blue gradient, a window with a title bar.
        let (w, h) = (1024u32, 640u32);
        let mut rgba = vec![0u8; (w * h * 4) as usize];
        for y in 0..h {
            for x in 0..w {
                let i = ((y * w + x) * 4) as usize;
                let inside = (180..760).contains(&x) && (120..500).contains(&y);
                let bar = inside && y < 150;
                let (r, g, b) = if bar {
                    (60, 64, 72)
                } else if inside {
                    (246, 244, 239)
                } else {
                    (20 + (y * 30 / h) as u8, 60 + (y * 60 / h) as u8, 120 + (x * 80 / w) as u8)
                };
                rgba[i..i + 4].copy_from_slice(&[r, g, b, 255]);
            }
        }
        let (tx, rx) = std::sync::mpsc::channel();
        std::mem::forget(rx);
        app.tabs.push(Tab {
            id: 7,
            host_id: Some(host.id),
            name: host.alias.clone(),
            color: host.color,
            protocol: host.protocol,
            term: nautile_app::term::Terminal::new(10, 2, app.term_theme(), app.cursor_shape()),
            input: None,
            state: TabState::Open,
            remote_title: Some("raspberry:0".into()),
            files: None,
            remote: Some(Box::new(RemoteTab {
                input: tx,
                frame: Default::default(),
                image: Some(iced::widget::image::Handle::from_rgba(w, h, rgba)),
                size: (w, h),
                cursor: None,
            })),
            log: None,
            monitor: None,
        });
        let _ = app.update(Message::SelectTab(Some(7)));
        "vnc"
    });
}
