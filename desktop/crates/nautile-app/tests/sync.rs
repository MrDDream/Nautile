//! Two computers sharing a backup folder: what one adds or deletes reaches the other (the Android rules).

use iced::futures::StreamExt;
use iced::Task;
use nautile_app::app::{App, Message};
use nautile_app::backup::{BackupMsg, Frequency, Target, PASSWORD};
use nautile_app::storage::{Paths, Settings};
use nautile_core::db::Db;
use nautile_core::model::{Host, HostGroup};
use nautile_net::store::Dest;

/// Runs [first] and every task its messages lead to (toasts aside), like the app's runtime would.
fn drive(app: &mut App, first: Task<Message>) {
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
    rt.block_on(async {
        let mut queue = vec![first];
        while let Some(t) = queue.pop() {
            let Some(mut s) = iced_runtime::task::into_stream(t) else {
                continue;
            };
            while let Some(a) = s.next().await {
                if let iced_runtime::Action::Output(m) = a {
                    if !matches!(m, Message::ToastClear) {
                        queue.push(app.update(m));
                    }
                }
            }
        }
    });
}

fn computer(name: &str, shared: &std::path::Path) -> App {
    let data = std::env::temp_dir().join(format!("nautile-sync-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&data);
    std::fs::create_dir_all(&data).unwrap();
    let db = Db::in_memory().unwrap();
    db.set_secret(PASSWORD, Some("même mot de passe")).unwrap();
    let settings = Settings {
        targets: vec![Target {
            id: "shared".into(),
            dest: Dest::Folder {
                path: shared.to_path_buf(),
            },
            auto: false,
            frequency: Frequency::Week,
            time: 180,
            weekday: 7,
            last: None,
            error: None,
        }],
        sync_enabled: true,
        sync_target: Some("shared".into()),
        ..Settings::default()
    };
    App::with(Paths { data, portable: true }, settings, Some(db), None)
}

fn sync(app: &mut App) {
    let t = app.update(Message::Backup(BackupMsg::SyncNow));
    drive(app, t);
    assert_eq!(app.settings.sync_error, None);
    assert!(!app.backup.syncing);
}

#[test]
fn two_computers_stay_in_step() {
    nautile_core::i18n::set_english(false);
    let shared = std::env::temp_dir().join(format!("nautile-sync-{}-shared", std::process::id()));
    let _ = std::fs::remove_dir_all(&shared);
    std::fs::create_dir_all(&shared).unwrap();
    let mut a = computer("a", &shared);
    let mut b = computer("b", &shared);

    let group: HostGroup = serde_json::from_value(serde_json::json!({ "name": "Homelab" })).unwrap();
    let gid = a.db.as_ref().unwrap().store().insert(&group).unwrap();
    for name in ["docker-01", "nas"] {
        let h = Host {
            group_id: Some(gid),
            ..Host::new(name, format!("{name}.lan"))
        };
        a.db.as_ref().unwrap().store().insert(&h).unwrap();
    }
    a.reload();
    sync(&mut a);
    assert!(shared.join("nautile.backup").exists());

    sync(&mut b);
    let names: Vec<_> = b.hosts.iter().map(|h| h.alias.as_str()).collect();
    assert_eq!(names, ["docker-01", "nas"]);
    assert_eq!(b.groups.len(), 1);

    // A deletion on B reaches A.
    let nas = b.hosts.iter().find(|h| h.alias == "nas").unwrap().id;
    b.db.as_ref().unwrap().store().delete::<Host>(nas).unwrap();
    b.reload();
    sync(&mut b);
    sync(&mut a);
    let names: Vec<_> = a.hosts.iter().map(|h| h.alias.as_str()).collect();
    assert_eq!(names, ["docker-01"]);
    // Both computers are listed in the shared file.
    assert_eq!(a.settings.devices.len(), 2);

    // A plain backup elsewhere, with its older copies.
    let other = std::env::temp_dir().join(format!("nautile-sync-{}-other", std::process::id()));
    let _ = std::fs::remove_dir_all(&other);
    std::fs::create_dir_all(&other).unwrap();
    a.settings.targets.push(Target {
        id: "other".into(),
        dest: Dest::Folder { path: other.clone() },
        auto: true,
        frequency: Frequency::Hours6,
        time: 0,
        weekday: 7,
        last: None,
        error: None,
    });
    let t = a.update(Message::BackupTick);
    drive(&mut a, t);
    let target = a.settings.targets.iter().find(|t| t.id == "other").unwrap();
    assert!(target.last.is_some() && target.error.is_none(), "{:?}", target.error);
    let text = std::fs::read_to_string(other.join("nautile.backup")).unwrap();
    let data = nautile_core::backup::import(&text, "même mot de passe").unwrap();
    assert_eq!(data.hosts.len(), 1);
    assert!(nautile_app::backup::versions(&a).len() >= 2);
}
