//! The app's lock lives in the encrypted database (not in settings.json), and the portable mode opens nothing
//! before its master password.

use nautile_app::app::{App, Message, Modal, Page};
use nautile_app::storage::{self, Paths, Settings, Vault};
use nautile_app::vault::{VaultForm, VaultMsg};
use nautile_core::db::Db;
use nautile_core::lock::{self, LockMethod};

fn temp(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("nautile-lock-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn app_with(name: &str, settings: Settings, db: Db) -> App {
    App::with(
        Paths {
            data: temp(name),
            portable: false,
        },
        settings,
        Some(db),
        None,
    )
}

#[test]
fn an_older_lock_moves_into_the_database() {
    let settings = Settings {
        lock_method: Some(LockMethod::Pin),
        lock_hash: Some(lock::hash_with("2468", 1_000)),
        lock_failures: 2,
        ..Settings::default()
    };
    let mut app = app_with("move", settings, Db::in_memory().unwrap());
    assert!(app.locked, "locked at start");
    assert!(app.settings.lock_hash.is_none(), "no longer in settings.json");
    let db = app.db.as_ref().unwrap();
    assert_eq!(lock::stored(db).map(|(m, _)| m), Some(LockMethod::Pin));
    assert_eq!(lock::failures(db), 2);
    let saved = std::fs::read_to_string(app.paths.data.join("settings.json")).unwrap();
    assert!(!saved.contains("lock_hash") && !saved.contains("pbkdf2"), "{saved}");
    let _ = app.update(Message::LockInput("2468".into()));
    let _ = app.update(Message::Unlock);
    assert!(!app.locked);
    assert_eq!(lock::failures(app.db.as_ref().unwrap()), 0);
}

#[test]
fn lost_settings_never_start_unlocked() {
    // settings.json unreadable or removed: the defaults, but the database still has the lock.
    let db = Db::in_memory().unwrap();
    lock::store(&db, Some((LockMethod::Password, &lock::hash_with("secret1", 1_000)))).unwrap();
    let mut app = app_with("lost", Settings::default(), db);
    assert!(app.locked);
    assert_eq!(app.settings.lock_method, Some(LockMethod::Password));
    assert_eq!(app.title(), "Nautile");
    let _ = app.update(Message::Nav(Page::Keys));
    assert_eq!(app.page, Page::Hosts, "nothing else while locked");
    // Quitting from the notification area waits for the code.
    let _ = app.update(Message::Quit);
    assert!(app.locked && app.lock_error.is_some());
}

#[test]
fn wrong_codes_are_counted_in_the_database_and_wait_on_the_monotonic_clock() {
    let db = Db::in_memory().unwrap();
    lock::store(&db, Some((LockMethod::Pin, &lock::hash_with("2468", 1_000)))).unwrap();
    let mut app = app_with("tries", Settings::default(), db);
    for _ in 0..lock::FREE_TRIES {
        let _ = app.update(Message::LockInput("0000".into()));
        let _ = app.update(Message::Unlock);
    }
    assert_eq!(lock::failures(app.db.as_ref().unwrap()), lock::FREE_TRIES);
    assert!(app.lock_until.is_some());
    // Even the right code waits.
    let _ = app.update(Message::LockInput("2468".into()));
    let _ = app.update(Message::Unlock);
    assert!(app.locked);
    assert_eq!(
        lock::failures(app.db.as_ref().unwrap()),
        lock::FREE_TRIES,
        "not counted while waiting"
    );
    // A restart does not skip the wait.
    let db = app.db.take().unwrap();
    let again = app_with("tries2", Settings::default(), db);
    assert!(again.locked && again.lock_until.is_some());
}

#[test]
fn the_portable_mode_opens_nothing_before_the_master_password() {
    let data = temp("vault");
    let paths = Paths {
        data: data.clone(),
        portable: true,
    };
    // An older portable copy: a plain key next to its database.
    let key = [5u8; 32];
    std::fs::write(data.join("db.key"), key).unwrap();
    drop(Db::open(&paths.db(), &key).unwrap());
    let mut app = App::with(paths.clone(), Settings::default(), None, None);
    let mode = storage::vault_state(&paths).unwrap();
    assert_eq!(mode, Vault::Create { legacy: true });
    app.vault = Some(VaultForm::new(mode));
    let _ = app.update(Message::Nav(Page::Keys));
    assert_eq!(app.page, Page::Hosts, "nothing before the password");
    assert_eq!(app.title(), "Nautile");
    // Too short, then two different entries.
    let _ = app.update(Message::Vault(VaultMsg::Password("short".into())));
    let _ = app.update(Message::Vault(VaultMsg::Confirm("short".into())));
    let _ = app.update(Message::Vault(VaultMsg::Submit));
    assert!(app.vault.as_ref().unwrap().error.is_some() && !app.vault.as_ref().unwrap().busy);
    let _ = app.update(Message::Vault(VaultMsg::Password("long enough".into())));
    let _ = app.update(Message::Vault(VaultMsg::Confirm("long enougH".into())));
    let _ = app.update(Message::Vault(VaultMsg::Submit));
    assert!(app.vault.as_ref().unwrap().error.is_some());
    // What the task does off the interface thread, then its answer.
    let opened = storage::create_master(&paths, "long enough");
    assert_eq!(opened, Ok(key));
    assert!(!data.join("db.key").exists(), "the plain key is gone");
    let _ = app.update(Message::Vault(VaultMsg::Opened(opened)));
    assert!(app.vault.is_none() && app.db.is_some() && app.fatal.is_none());

    // Next start: a wrong password waits longer and longer, in memory.
    let mut next = App::with(paths.clone(), Settings::default(), None, None);
    next.vault = Some(VaultForm::new(storage::vault_state(&paths).unwrap()));
    for _ in 0..3 {
        let _ = next.update(Message::Vault(VaultMsg::Opened(storage::open_master(&paths, "nope nope"))));
    }
    let v = next.vault.as_ref().unwrap();
    assert!(v.failures == 3 && v.until.is_some() && v.error.is_some());
    let _ = next.update(Message::Vault(VaultMsg::Password("long enough".into())));
    let _ = next.update(Message::Vault(VaultMsg::Submit));
    assert!(!next.vault.as_ref().unwrap().busy, "refused during the wait");
    let _ = next.update(Message::Vault(VaultMsg::Opened(storage::open_master(&paths, "long enough"))));
    assert!(next.vault.is_none() && next.db.is_some());
    let _ = std::fs::remove_dir_all(&data);
}

#[test]
fn updates_are_checked_only_after_a_yes() {
    let mut app = app_with("updates", Settings::default(), Db::in_memory().unwrap());
    assert!(!app.settings.update_check());
    let _ = app.update_boot();
    assert_eq!(app.modal, Some(Modal::UpdateAsk));
    assert!(!app.updates.checking, "nothing sent before the answer");
    let _ = app.update(Message::Update(nautile_app::update::UpdateMsg::AutoCheck(false)));
    assert_eq!(app.modal, None);
    assert_eq!(app.settings.update_check, Some(false));
    let _ = app.update_boot();
    assert_eq!(app.modal, None, "asked once");
}
