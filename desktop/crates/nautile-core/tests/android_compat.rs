//! Reads what the Android app wrote (desktop/tests/fixtures, made by core/data's DesktopFixturesTest) and writes
//! `desktop.backup` back for Android to check (`NAUTILE_WRITE_FIXTURES=1 cargo test`).

use nautile_core::backup;
use nautile_core::model::BackupData;
use serde_json::Value;
use std::path::PathBuf;

const PASSWORD: &str = "nautile-compat ✓";

fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../tests/fixtures")
}

fn expected() -> Value {
    serde_json::from_str(&std::fs::read_to_string(fixtures().join("android-backup.json")).unwrap()).unwrap()
}

#[test]
fn opens_the_android_backup() {
    let text = std::fs::read_to_string(fixtures().join("android.backup")).unwrap();
    let plain: Value = serde_json::from_slice(&backup::decrypt(&text, PASSWORD).unwrap()).unwrap();
    assert_eq!(plain, expected());

    let data = backup::import(&text, PASSWORD).unwrap();
    assert_eq!(data.hosts.len(), 7);
    assert!(backup::import(&text, "wrong").is_err());
}

#[test]
fn nothing_is_lost_through_rust() {
    let data: BackupData = serde_json::from_value(expected()).unwrap();
    assert_eq!(serde_json::to_value(&data).unwrap(), expected());
}

#[test]
fn keeps_fields_it_does_not_know() {
    let mut json = expected();
    json["hosts"][0]["futureField"] = Value::from("from a newer phone");
    json["futureList"] = Value::from(vec![1, 2]);
    let data: BackupData = serde_json::from_value(json.clone()).unwrap();
    assert_eq!(serde_json::to_value(&data).unwrap(), json);
}

#[test]
fn writes_a_backup_android_can_open() {
    let data: BackupData = serde_json::from_value(expected()).unwrap();
    let file = backup::export(&data, PASSWORD).unwrap();
    assert_eq!(backup::import(&file, PASSWORD).unwrap(), data);
    if std::env::var_os("NAUTILE_WRITE_FIXTURES").is_some() {
        std::fs::write(fixtures().join("desktop.backup"), file).unwrap();
    }
}
