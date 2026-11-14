//! What a device notes for itself (last connection, system, runs of a snippet) is not an edit: it must not
//! date the record as changed now, nor write back a copy older than a change merged from elsewhere.

use nautile_app::app::{App, Message};
use nautile_app::sessions::{Tab, TabState};
use nautile_app::storage::{Paths, Settings};
use nautile_app::term::Terminal;
use nautile_core::db::Db;
use nautile_core::model::{Host, HostGroup, Snippet};
use nautile_net::Event;

fn app() -> App {
    let data = std::env::temp_dir().join(format!("nautile-local-state-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&data);
    std::fs::create_dir_all(&data).unwrap();
    App::with(
        Paths { data, portable: true },
        Settings::default(),
        Some(Db::in_memory().unwrap()),
        None,
    )
}

fn stored<R: nautile_core::db::Record>(app: &App, id: i64) -> R {
    app.db.as_ref().unwrap().store().get::<R>(id).unwrap().unwrap()
}

#[test]
fn a_connection_keeps_a_change_merged_meanwhile() {
    let mut app = app();
    let id = app.db.as_ref().unwrap().store().insert(&Host::new("nas", "nas.lan")).unwrap();
    let sn: Snippet = serde_json::from_value(serde_json::json!({ "name": "Disque", "command": "df -h" })).unwrap();
    let sid = app.db.as_ref().unwrap().store().insert(&sn).unwrap();
    app.reload();

    // Another device renamed the host and changed the snippet; merged into the database, not into the lists.
    let mut h: Host = stored(&app, id);
    h.hostname = "10.0.0.5".into();
    h.updated_at += 1000;
    app.db.as_ref().unwrap().store().update(&h).unwrap();
    let mut s: Snippet = stored(&app, sid);
    s.command = "df -hT".into();
    s.updated_at += 1000;
    app.db.as_ref().unwrap().store().update(&s).unwrap();
    assert_eq!(app.hosts[0].hostname, "nas.lan", "the list is behind");

    let theme = app.term_theme();
    app.tabs.push(Tab {
        id: 1,
        host_id: Some(id),
        name: "nas".into(),
        color: 0,
        protocol: h.protocol,
        term: Terminal::new(80, 24, theme, app.cursor_shape()),
        input: None,
        state: TabState::Connecting(String::new()),
        remote_title: None,
        files: None,
        remote: None,
        log: None,
        monitor: None,
    });
    app.next_tab = 1;
    let _ = app.update(Message::Session(1, Event::Connected));
    let _ = app.update(Message::Session(1, Event::Os("debian".into())));
    let _ = app.update(Message::RunSnippet(sid));

    let after: Host = stored(&app, id);
    assert_eq!(after.hostname, "10.0.0.5");
    assert_eq!(after.updated_at, h.updated_at, "not an edit");
    assert!(after.last_connected_at.is_some());
    assert_eq!(after.os_id.as_deref(), Some("debian"));
    assert_eq!(app.hosts[0].os_id.as_deref(), Some("debian"));
    let after: Snippet = stored(&app, sid);
    assert_eq!(
        (after.command.as_str(), after.run_count, after.updated_at),
        ("df -hT", 1, s.updated_at)
    );

    // Moving the host to a group is an edit, made on the stored record.
    let group: HostGroup = serde_json::from_value(serde_json::json!({ "name": "Maison" })).unwrap();
    let gid = app.db.as_ref().unwrap().store().insert(&group).unwrap();
    let mut h: Host = stored(&app, id);
    h.username = "admin".into();
    h.updated_at += 1000;
    app.db.as_ref().unwrap().store().update(&h).unwrap();
    let _ = app.update(Message::MoveHost(id, Some(gid)));
    let after: Host = stored(&app, id);
    assert_eq!((after.group_id, after.username.as_str()), (Some(gid), "admin"));
    assert!(after.updated_at > h.updated_at);
    let _ = std::fs::remove_dir_all(&app.paths.data);
}
