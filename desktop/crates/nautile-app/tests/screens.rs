//! Renders the screens without a display (iced_test, tiny-skia), in dark and light:
//! `NAUTILE_SCREENSHOTS=1 cargo test -p nautile-app --test screens` writes them to desktop/screenshots.

use nautile_app::app::{App, Message, Page};
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

fn shoot(app: &App, name: &str) {
    let settings = iced_test::core::Settings {
        fonts: vec![theme::FONT_UI.into(), theme::FONT_MONO.into()],
        default_font: theme::UI,
        ..Default::default()
    };
    let mut ui = iced_test::simulator::Simulator::with_size(settings, (1280.0, 800.0), ui::view(app));
    let snapshot = ui.snapshot(&app.theme()).expect("renders");
    if std::env::var_os("NAUTILE_SCREENSHOTS").is_some() {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../screenshots");
        let path = dir.join(format!("{name}.png"));
        // matches_image only writes a missing file: start from a clean one.
        for e in std::fs::read_dir(&dir).into_iter().flatten().flatten() {
            if e.file_name().to_string_lossy().starts_with(&format!("{name}-")) {
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
fn editing_a_host_saves_it() {
    let _one = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let mut app = demo(false, true);
    let _ = app.update(Message::NewHost);
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
