//! The desktop's encrypted database (SQLCipher): the Android schema (Room v10), the same synchronisation
//! triggers (core/data/.../db/SyncSchema.kt), plus an `extra` column keeping the JSON fields this version does
//! not know, so a sync never drops what a newer phone wrote.

use rusqlite::types::Value as Sql;
use rusqlite::{Connection, OptionalExtension, params};
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::{Map, Value};

use crate::model::{Host, HostGroup, Identity, KnownHost, PortForward, Snippet, SshKey, SyncTombstone};

pub type Result<T> = rusqlite::Result<T>;

/// A table holding one kind of record. Columns are the JSON (camelCase) names, which are also the SQL ones.
pub trait Record: Serialize + DeserializeOwned + Clone {
    const TABLE: &'static str;
    const COLUMNS: &'static [&'static str];
    /// Columns stored as 0/1 but read back as JSON booleans.
    const BOOLS: &'static [&'static str] = &[];
    fn id(&self) -> i64;
}

macro_rules! record {
    ($t:ty, $table:literal, [$($c:literal),* $(,)?], bools: [$($b:literal),* $(,)?]) => {
        impl Record for $t {
            const TABLE: &'static str = $table;
            const COLUMNS: &'static [&'static str] = &[$($c),*];
            const BOOLS: &'static [&'static str] = &[$($b),*];
            fn id(&self) -> i64 { self.id }
        }
    };
}

record!(HostGroup, "host_group", ["id", "name", "color", "sortOrder", "syncId", "updatedAt", "icon"], bools: []);
record!(Host, "host", [
    "id", "alias", "hostname", "port", "username", "protocol", "authMethod", "password", "identity", "keyId", "identityId",
    "agentForwarding", "proxyType", "proxyHost", "proxyPort", "proxyUsername", "proxyPassword", "knockSequence", "serialBaud",
    "rdpDomain", "rdpResolution", "rdpScale", "rdpSecurity", "rdpAudio", "rdpConsole", "osId", "groupId", "jumpHostId", "color",
    "tags", "startupCommand", "createdAt", "lastConnectedAt", "syncId", "updatedAt",
], bools: ["agentForwarding", "rdpAudio", "rdpConsole"]);
record!(SshKey, "ssh_key", [
    "id", "name", "keyType", "description", "publicKey", "privateKey", "fingerprint", "encrypted", "savedPassphrase", "generated",
    "createdAt", "syncId", "updatedAt",
], bools: ["encrypted", "generated"]);
record!(Identity, "identity", ["id", "name", "username", "password", "keyId", "syncId", "updatedAt"], bools: []);
record!(Snippet, "snippet", ["id", "name", "command", "tag", "runCount", "createdAt", "syncId", "updatedAt"], bools: []);
record!(PortForward, "port_forward", [
    "id", "name", "hostId", "kind", "bindHost", "bindPort", "destHost", "destPort", "autoStart", "syncId", "updatedAt",
], bools: ["autoStart"]);
record!(KnownHost, "known_host", ["id", "host", "port", "keyType", "publicKey", "fingerprint", "addedAt"], bools: []);

/// The Android schema (Room v10, core/data/schemas), with an `extra` JSON column on shared tables.
const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS host_group (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `syncId` TEXT NOT NULL DEFAULT '', `updatedAt` INTEGER NOT NULL DEFAULT 0, `icon` TEXT DEFAULT NULL, `extra` TEXT NOT NULL DEFAULT '{}');
CREATE UNIQUE INDEX IF NOT EXISTS index_host_group_name ON host_group (`name`);
CREATE TABLE IF NOT EXISTS ssh_key (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `keyType` TEXT NOT NULL, `description` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `privateKey` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `encrypted` INTEGER NOT NULL, `savedPassphrase` TEXT, `generated` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `syncId` TEXT NOT NULL DEFAULT '', `updatedAt` INTEGER NOT NULL DEFAULT 0, `extra` TEXT NOT NULL DEFAULT '{}');
CREATE UNIQUE INDEX IF NOT EXISTS index_ssh_key_fingerprint ON ssh_key (`fingerprint`);
CREATE TABLE IF NOT EXISTS identity (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `username` TEXT NOT NULL, `password` TEXT, `keyId` INTEGER, `syncId` TEXT NOT NULL DEFAULT '', `updatedAt` INTEGER NOT NULL DEFAULT 0, `extra` TEXT NOT NULL DEFAULT '{}', FOREIGN KEY(`keyId`) REFERENCES `ssh_key`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL);
CREATE INDEX IF NOT EXISTS index_identity_keyId ON identity (`keyId`);
CREATE TABLE IF NOT EXISTS host (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `alias` TEXT NOT NULL, `hostname` TEXT NOT NULL, `port` INTEGER NOT NULL, `username` TEXT NOT NULL, `protocol` TEXT NOT NULL, `authMethod` TEXT NOT NULL, `password` TEXT, `identity` TEXT, `keyId` INTEGER, `identityId` INTEGER, `agentForwarding` INTEGER NOT NULL DEFAULT 0, `proxyType` TEXT, `proxyHost` TEXT, `proxyPort` INTEGER, `proxyUsername` TEXT, `proxyPassword` TEXT, `knockSequence` TEXT, `serialBaud` INTEGER, `rdpDomain` TEXT, `rdpResolution` TEXT, `rdpScale` INTEGER, `rdpSecurity` TEXT, `rdpAudio` INTEGER NOT NULL DEFAULT 1, `rdpConsole` INTEGER NOT NULL DEFAULT 0, `osId` TEXT, `groupId` INTEGER, `jumpHostId` INTEGER, `color` INTEGER NOT NULL, `tags` TEXT NOT NULL, `startupCommand` TEXT, `createdAt` INTEGER NOT NULL, `lastConnectedAt` INTEGER, `syncId` TEXT NOT NULL DEFAULT '', `updatedAt` INTEGER NOT NULL DEFAULT 0, `extra` TEXT NOT NULL DEFAULT '{}', FOREIGN KEY(`groupId`) REFERENCES `host_group`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`jumpHostId`) REFERENCES `host`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`keyId`) REFERENCES `ssh_key`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`identityId`) REFERENCES `identity`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL);
CREATE INDEX IF NOT EXISTS index_host_groupId ON host (`groupId`);
CREATE INDEX IF NOT EXISTS index_host_jumpHostId ON host (`jumpHostId`);
CREATE INDEX IF NOT EXISTS index_host_keyId ON host (`keyId`);
CREATE INDEX IF NOT EXISTS index_host_identityId ON host (`identityId`);
CREATE TABLE IF NOT EXISTS known_host (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `keyType` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `extra` TEXT NOT NULL DEFAULT '{}');
CREATE UNIQUE INDEX IF NOT EXISTS index_known_host_host_port_keyType ON known_host (`host`, `port`, `keyType`);
CREATE TABLE IF NOT EXISTS snippet (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `command` TEXT NOT NULL, `tag` TEXT NOT NULL, `runCount` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, `syncId` TEXT NOT NULL DEFAULT '', `updatedAt` INTEGER NOT NULL DEFAULT 0, `extra` TEXT NOT NULL DEFAULT '{}');
CREATE TABLE IF NOT EXISTS port_forward (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `hostId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `bindHost` TEXT NOT NULL, `bindPort` INTEGER NOT NULL, `destHost` TEXT NOT NULL, `destPort` INTEGER NOT NULL, `autoStart` INTEGER NOT NULL DEFAULT 0, `syncId` TEXT NOT NULL DEFAULT '', `updatedAt` INTEGER NOT NULL DEFAULT 0, `extra` TEXT NOT NULL DEFAULT '{}', FOREIGN KEY(`hostId`) REFERENCES `host`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE);
CREATE INDEX IF NOT EXISTS index_port_forward_hostId ON port_forward (`hostId`);
CREATE TABLE IF NOT EXISTS command_history (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hostId` INTEGER NOT NULL, `command` TEXT NOT NULL, `count` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL, FOREIGN KEY(`hostId`) REFERENCES `host`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE);
CREATE UNIQUE INDEX IF NOT EXISTS index_command_history_hostId_command ON command_history (`hostId`, `command`);
CREATE TABLE IF NOT EXISTS connection_log (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hostId` INTEGER, `alias` TEXT NOT NULL, `protocol` TEXT NOT NULL, `username` TEXT NOT NULL, `hostname` TEXT NOT NULL, `port` INTEGER NOT NULL, `at` INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS index_connection_log_at ON connection_log (`at`);
CREATE INDEX IF NOT EXISTS index_connection_log_hostId ON connection_log (`hostId`);
CREATE TABLE IF NOT EXISTS sync_tombstone (`syncId` TEXT NOT NULL, `kind` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`syncId`));
CREATE INDEX IF NOT EXISTS index_host_group_syncId ON host_group (`syncId`);
CREATE INDEX IF NOT EXISTS index_host_syncId ON host (`syncId`);
CREATE INDEX IF NOT EXISTS index_host_alias ON host (`alias`);
CREATE INDEX IF NOT EXISTS index_ssh_key_syncId ON ssh_key (`syncId`);
CREATE INDEX IF NOT EXISTS index_identity_syncId ON identity (`syncId`);
CREATE INDEX IF NOT EXISTS index_snippet_syncId ON snippet (`syncId`);
CREATE INDEX IF NOT EXISTS index_port_forward_syncId ON port_forward (`syncId`);
"#;

/// Columns added since the first desktop version (table, column, SQL type): Room's version 11, the groups' icon.
const ADDED_COLUMNS: &[(&str, &str, &str)] = &[("host_group", "icon", "TEXT DEFAULT NULL")];

/// Epoch millis in SQL.
const NOW: &str = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)";

/// The highest stamp ever written (2^62): far enough from i64::MAX that `stamp + 1` stays an integer
/// (SyncSchema.MAX_STAMP).
pub const MAX_STAMP: i64 = 4_611_686_018_427_387_904;

/// How far ahead of this clock a stamp is believed (a year); beyond, it is brought back (see [stamp_cap]).
pub const FUTURE_LIMIT_MS: i64 = 31_536_000_000;

/// 2026-11-01 00:00 UTC, before this version existed: a clock earlier than that is wrong (started before the network
/// time, no battery-backed clock…), and the stamps are not judged by it ([stamp_cap]). SyncSchema.CLOCK_FLOOR_MS.
pub const CLOCK_FLOOR_MS: i64 = 1_793_491_200_000;

/// The latest stamp believed at `now`: a year ahead, never above [MAX_STAMP]; only [MAX_STAMP] when `now` is before
/// [CLOCK_FLOOR_MS], a clock that wrong would otherwise bring back every stamp (SyncSchema.stampCap).
pub fn stamp_cap(now: i64) -> i64 {
    if now < CLOCK_FLOOR_MS {
        MAX_STAMP
    } else {
        now.saturating_add(FUTURE_LIMIT_MS).min(MAX_STAMP)
    }
}

/// One past `at`, never above [MAX_STAMP] (SyncSchema.bump).
pub fn bump(at: i64) -> i64 {
    at.min(MAX_STAMP - 1) + 1
}

/// Tombstone id of the known host being inserted, in SQL (model::known_host_tombstone_id).
const KH_NEW: &str = "'kh:' || NEW.host || ':' || NEW.port || ':' || NEW.keyType";

/// Synced tables and the columns whose change is an edit (SyncSchema.TABLES).
pub const SYNCED: &[(&str, &[&str])] = &[
    ("host_group", &["name", "color", "sortOrder", "icon"]),
    (
        "host",
        &[
            "alias",
            "hostname",
            "port",
            "username",
            "protocol",
            "authMethod",
            "password",
            "identity",
            "keyId",
            "identityId",
            "agentForwarding",
            "proxyType",
            "proxyHost",
            "proxyPort",
            "proxyUsername",
            "proxyPassword",
            "knockSequence",
            "serialBaud",
            "rdpDomain",
            "rdpResolution",
            "rdpScale",
            "rdpSecurity",
            "rdpAudio",
            "rdpConsole",
            "groupId",
            "jumpHostId",
            "color",
            "tags",
            "startupCommand",
        ],
    ),
    (
        "ssh_key",
        &[
            "name",
            "keyType",
            "description",
            "publicKey",
            "privateKey",
            "fingerprint",
            "encrypted",
            "savedPassphrase",
            "generated",
        ],
    ),
    ("identity", &["name", "username", "password", "keyId"]),
    ("snippet", &["name", "command", "tag"]),
    (
        "port_forward",
        &[
            "name",
            "hostId",
            "kind",
            "bindHost",
            "bindPort",
            "destHost",
            "destPort",
            "autoStart",
        ],
    ),
];

/// References between synced records (SyncSchema.REFS): column → referenced table. Row ids differ between devices,
/// so a record's canonical content (sync::content_hash) names the referenced record by its sync id.
pub const REFS: &[(&str, &[(&str, &str)])] = &[
    (
        "host",
        &[
            ("keyId", "ssh_key"),
            ("identityId", "identity"),
            ("groupId", "host_group"),
            ("jumpHostId", "host"),
        ],
    ),
    ("identity", &[("keyId", "ssh_key")]),
    ("port_forward", &[("hostId", "host")]),
];

/// The references of `table` (empty for a table without any).
pub fn refs(table: &str) -> &'static [(&'static str, &'static str)] {
    REFS.iter().find(|(t, _)| *t == table).map(|(_, r)| *r).unwrap_or(&[])
}

/// True outside a merge. `sync_ctl.merging` is 1 while another device's data is written (sync::merge, in its
/// transaction): the triggers then leave updatedAt and the tombstones alone, so what SQLite does on its own
/// (ON DELETE SET NULL / CASCADE when a remote deletion is applied) is not taken for a local edit.
const NOT_MERGING: &str = "NOT EXISTS (SELECT 1 FROM sync_ctl WHERE merging != 0)";

/// The triggers of SyncSchema.statements(), word for word.
fn sync_statements() -> Vec<String> {
    let mut out = vec![
        "CREATE TABLE IF NOT EXISTS sync_ctl (merging INTEGER NOT NULL)".to_owned(),
        "INSERT INTO sync_ctl (merging) SELECT 0 WHERE NOT EXISTS (SELECT 1 FROM sync_ctl)".to_owned(),
        // A merge never outlives its transaction; this only guards against a value left by hand.
        "UPDATE sync_ctl SET merging = 0".to_owned(),
    ];
    for (table, columns) in SYNCED {
        out.push(format!("UPDATE {table} SET syncId = lower(hex(randomblob(16))) WHERE syncId = ''"));
        out.push(format!("UPDATE {table} SET updatedAt = 1 WHERE updatedAt = 0"));
        // Recreated each time (like the others): their body changes with the versions.
        out.push(format!("DROP TRIGGER IF EXISTS sync_ins_{table}"));
        out.push(format!(
            "CREATE TRIGGER sync_ins_{table} AFTER INSERT ON {table} BEGIN
                UPDATE {table} SET
                    syncId = CASE WHEN NEW.syncId = '' THEN lower(hex(randomblob(16))) ELSE NEW.syncId END,
                    updatedAt = CASE WHEN NEW.updatedAt = 0 THEN {NOW} ELSE MIN(NEW.updatedAt, {MAX_STAMP}) END
                WHERE id = NEW.id;
            END"
        ));
        // An edit that moves updatedAt forward keeps it. Any other edit, including one saved from a stale copy
        // (older updatedAt), is stamped now, and never below the previous value, so a row synced from a device
        // whose clock runs ahead still moves forward; never above MAX_STAMP, so it stays an integer. Not during a
        // merge, which writes updatedAt itself.
        out.push(format!("DROP TRIGGER IF EXISTS sync_upd_{table}"));
        out.push(format!(
            "CREATE TRIGGER sync_upd_{table} AFTER UPDATE OF {} ON {table}
            WHEN NEW.updatedAt <= OLD.updatedAt AND {NOT_MERGING} BEGIN
                UPDATE {table} SET updatedAt = MIN(MAX({NOW}, OLD.updatedAt + 1), {MAX_STAMP}) WHERE id = NEW.id;
            END",
            columns.join(", ")
        ));
        // A deletion is dated after the row's last change, even one stamped by a clock running ahead, or it
        // would apply nowhere. Not during a merge, which writes the other device's tombstones itself.
        out.push(format!("DROP TRIGGER IF EXISTS sync_del_{table}"));
        out.push(format!(
            "CREATE TRIGGER sync_del_{table} AFTER DELETE ON {table} WHEN OLD.syncId != '' AND {NOT_MERGING} BEGIN
                INSERT OR REPLACE INTO sync_tombstone(syncId, kind, deletedAt)
                VALUES (OLD.syncId, '{table}', MIN(MAX({NOW}, OLD.updatedAt + 1), {MAX_STAMP}));
            END"
        ));
    }
    out.push("DROP TRIGGER IF EXISTS sync_del_known_host".to_owned());
    out.push(format!(
        "CREATE TRIGGER sync_del_known_host AFTER DELETE ON known_host WHEN {NOT_MERGING} BEGIN
            INSERT OR REPLACE INTO sync_tombstone(syncId, kind, deletedAt)
            VALUES ('kh:' || OLD.host || ':' || OLD.port || ':' || OLD.keyType, 'known_host', MIN(MAX({NOW}, OLD.addedAt + 1), {MAX_STAMP}));
        END"
    ));
    // Trusting a key again cancels its tombstone, and is dated after it: a tombstone dated ahead (the key was trusted
    // on a device whose clock runs ahead) still held by another device would otherwise delete the new trust again.
    out.push("DROP TRIGGER IF EXISTS sync_ins_known_host".to_owned());
    out.push(format!(
        "CREATE TRIGGER sync_ins_known_host AFTER INSERT ON known_host BEGIN
            UPDATE known_host SET addedAt = MIN((SELECT deletedAt FROM sync_tombstone WHERE syncId = {KH_NEW}) + 1, {MAX_STAMP})
                WHERE id = NEW.id AND addedAt <= (SELECT deletedAt FROM sync_tombstone WHERE syncId = {KH_NEW});
            DELETE FROM sync_tombstone WHERE syncId = {KH_NEW};
        END"
    ));
    out.extend(stamp_repairs(crate::model::now_ms()));
    out
}

/// Brings back the stamps beyond [stamp_cap] at `now` (written before stamps were bounded, or restored from a file):
/// a stamp that far ahead would win over every later edit, and one past i64::MAX is stored as a REAL. Tombstones are
/// brought back the same way, never dropped: the deletion still applies. With a clock before [CLOCK_FLOOR_MS] only
/// what is past [MAX_STAMP] is. Run at each opening and before each merge; only updatedAt / addedAt / deletedAt
/// change, which the triggers do not watch. SyncSchema.stampRepairs, word for word.
fn stamp_repairs(now: i64) -> Vec<String> {
    let cap = stamp_cap(now);
    let mut out: Vec<String> = SYNCED
        .iter()
        .map(|(table, _)| format!("UPDATE {table} SET updatedAt = {cap} WHERE updatedAt > {cap}"))
        .collect();
    out.push(format!("UPDATE known_host SET addedAt = {cap} WHERE addedAt > {cap}"));
    out.push(format!("UPDATE sync_tombstone SET deletedAt = {cap} WHERE deletedAt > {cap}"));
    out
}

fn to_sql(v: Value) -> Sql {
    match v {
        Value::Null => Sql::Null,
        Value::Bool(b) => Sql::Integer(b as i64),
        Value::Number(n) => n.as_i64().map(Sql::Integer).unwrap_or_else(|| Sql::Real(n.as_f64().unwrap_or(0.0))),
        Value::String(s) => Sql::Text(s),
        other => Sql::Text(other.to_string()),
    }
}

fn to_json(v: Sql, boolean: bool) -> Value {
    match v {
        Sql::Null => Value::Null,
        Sql::Integer(i) if boolean => Value::Bool(i != 0),
        Sql::Integer(i) => Value::from(i),
        // Every column is an INTEGER: a REAL is a value past i64 (`i64::MAX + 1` in a trigger of an older version),
        // read as the integer it bounds to rather than refused by the record's type.
        Sql::Real(f) => Value::from(real_to_int(f)),
        Sql::Text(s) => Value::String(s),
        Sql::Blob(b) => Value::String(String::from_utf8_lossy(&b).into_owned()),
    }
}

/// A REAL read where an integer is stored, bounded to ±[MAX_STAMP] (NaN: 0).
fn real_to_int(f: f64) -> i64 {
    if f.is_nan() {
        0
    } else {
        f.clamp(-(MAX_STAMP as f64), MAX_STAMP as f64) as i64
    }
}

/// An integer column read whatever SQLite stored in it (see [real_to_int]).
fn int_at(row: &rusqlite::Row, i: usize) -> Result<i64> {
    Ok(match row.get::<_, Sql>(i)? {
        Sql::Integer(n) => n,
        Sql::Real(f) => real_to_int(f),
        other => return Err(rusqlite::Error::InvalidColumnType(i, "integer".into(), other.data_type())),
    })
}

/// The column values of `r` (id left out when 0, so SQLite numbers it) and its unknown fields as JSON.
fn split<R: Record>(r: &R) -> (Vec<(&'static str, Sql)>, String) {
    let Value::Object(mut map) = serde_json::to_value(r).expect("records serialise") else {
        unreachable!()
    };
    let mut cols = Vec::new();
    for c in R::COLUMNS {
        let v = map.remove(*c).unwrap_or(Value::Null);
        if *c == "id" && v.as_i64() == Some(0) {
            continue;
        }
        cols.push((*c, to_sql(v)));
    }
    (cols, Value::Object(map).to_string())
}

fn read<R: Record>(row: &rusqlite::Row) -> Result<R> {
    let mut map: Map<String, Value> = match row.get::<_, String>("extra") {
        Ok(s) => serde_json::from_str(&s).unwrap_or_default(),
        Err(_) => Map::new(),
    };
    for c in R::COLUMNS {
        map.insert((*c).to_owned(), to_json(row.get::<_, Sql>(*c)?, R::BOOLS.contains(c)));
    }
    serde_json::from_value(Value::Object(map)).map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))
}

/// The database: a SQLite connection, opened with the synchronisation triggers in place.
pub struct Db {
    pub conn: Connection,
}

impl Db {
    /// Opens (or creates) the database at `path`, encrypted with `key` (32 random bytes kept by the system keyring).
    pub fn open(path: &std::path::Path, key: &[u8; 32]) -> Result<Db> {
        // SQLCipher's first keying (its crypto provider set up on first use) is not safe from several threads at
        // once: opening is serialized. The app opens one database; parallel tests open many.
        static KEYING: std::sync::Mutex<()> = std::sync::Mutex::new(());
        let _keying = KEYING.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let conn = Connection::open(path)?;
        // Raw key: no passphrase derivation, the key is already random.
        conn.execute_batch(&format!("PRAGMA key = \"x'{}'\";", hex_upper(key)))?;
        Self::setup(conn)
    }

    /// For tests: in memory, not encrypted.
    pub fn in_memory() -> Result<Db> {
        Self::setup(Connection::open_in_memory()?)
    }

    /// For tests: the same connection, set up again as when the database opens.
    #[cfg(test)]
    pub(crate) fn reopened(self) -> Db {
        Self::setup(self.conn).unwrap()
    }

    fn setup(conn: Connection) -> Result<Db> {
        // Fails here, not later, when the key is wrong.
        conn.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok(()))?;
        conn.execute_batch("PRAGMA foreign_keys = ON; PRAGMA recursive_triggers = OFF;")?;
        conn.execute_batch(SCHEMA)?;
        for (table, column, ty) in ADDED_COLUMNS {
            let present: bool = conn
                .query_row(
                    &format!("SELECT count(*) FROM pragma_table_info('{table}') WHERE name = '{column}'"),
                    [],
                    |r| r.get::<_, i64>(0),
                )
                .map(|n| n > 0)?;
            if !present {
                conn.execute_batch(&format!("ALTER TABLE {table} ADD COLUMN `{column}` {ty}"))?;
            }
            // A version without the column kept the field in `extra` (unknown JSON fields, same name): moved into
            // the column, which read() would otherwise put over it. Run on each opening: cheap, and idempotent.
            conn.execute_batch(&format!(
                "UPDATE {table} SET `{column}` = json_extract(extra, '$.{column}')
                    WHERE `{column}` IS NULL AND CASE WHEN json_valid(extra) THEN json_type(extra, '$.{column}') IS NOT NULL ELSE 0 END;
                UPDATE {table} SET extra = json_remove(extra, '$.{column}')
                    WHERE CASE WHEN json_valid(extra) THEN json_type(extra, '$.{column}') IS NOT NULL ELSE 0 END;"
            ))?;
        }
        for s in sync_statements() {
            conn.execute_batch(&s)?;
        }
        // This computer's secrets (backup password, keys of the backup places): encrypted with the rest, never synchronised.
        conn.execute_batch("CREATE TABLE IF NOT EXISTS desktop_secret (name TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")?;
        Ok(Db { conn })
    }

    /// A secret of this computer (not part of the backups).
    pub fn secret(&self, name: &str) -> Option<String> {
        self.conn
            .query_row("SELECT value FROM desktop_secret WHERE name = ?1", [name], |r| r.get(0))
            .ok()
    }

    /// Saves (or with `None` forgets) a secret of this computer.
    pub fn set_secret(&self, name: &str, value: Option<&str>) -> Result<()> {
        match value {
            Some(v) => self.conn.execute(
                "INSERT INTO desktop_secret (name, value) VALUES (?1, ?2) ON CONFLICT(name) DO UPDATE SET value = ?2",
                [name, v],
            )?,
            None => self.conn.execute("DELETE FROM desktop_secret WHERE name = ?1", [name])?,
        };
        Ok(())
    }

    /// Notes a connection for « Connexions › Historique » (kept on this computer; 500 at most).
    pub fn log_connection(&self, e: &ConnectionLog) -> Result<()> {
        self.conn.execute(
            "INSERT INTO connection_log (hostId, alias, protocol, username, hostname, port, at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            rusqlite::params![e.host_id, e.alias, e.protocol, e.username, e.hostname, e.port, e.at],
        )?;
        self.conn.execute(
            "DELETE FROM connection_log WHERE id NOT IN (SELECT id FROM connection_log ORDER BY at DESC LIMIT 500)",
            [],
        )?;
        Ok(())
    }

    /// The connections made, newest first.
    pub fn connection_log(&self, limit: usize) -> Vec<ConnectionLog> {
        let Ok(mut st) = self
            .conn
            .prepare("SELECT id, hostId, alias, protocol, username, hostname, port, at FROM connection_log ORDER BY at DESC LIMIT ?1")
        else {
            return Vec::new();
        };
        st.query_map([limit as i64], |r| {
            Ok(ConnectionLog {
                id: r.get(0)?,
                host_id: r.get(1)?,
                alias: r.get(2)?,
                protocol: r.get(3)?,
                username: r.get(4)?,
                hostname: r.get(5)?,
                port: r.get(6)?,
                at: r.get(7)?,
            })
        })
        .map(|rows| rows.flatten().collect())
        .unwrap_or_default()
    }

    /// Notes a command typed on [host_id] (Android's `CommandHistory`: count and last use).
    pub fn add_command(&self, host_id: i64, command: &str) -> Result<()> {
        self.conn.execute(
            "INSERT INTO command_history (hostId, command, count, lastUsed) VALUES (?1, ?2, 1, ?3)
             ON CONFLICT(hostId, command) DO UPDATE SET count = count + 1, lastUsed = ?3",
            rusqlite::params![host_id, command, crate::model::now_ms()],
        )?;
        Ok(())
    }

    /// The commands typed on [host_id], most used first.
    pub fn commands(&self, host_id: i64) -> Vec<(String, i64)> {
        let Ok(mut st) = self
            .conn
            .prepare("SELECT command, count FROM command_history WHERE hostId = ?1 ORDER BY count DESC, lastUsed DESC LIMIT 500")
        else {
            return Vec::new();
        };
        st.query_map([host_id], |r| Ok((r.get(0)?, r.get(1)?)))
            .map(|rows| rows.flatten().collect())
            .unwrap_or_default()
    }

    pub fn command_count(&self) -> i64 {
        self.conn
            .query_row("SELECT count(*) FROM command_history", [], |r| r.get(0))
            .unwrap_or(0)
    }

    pub fn clear_commands(&self) -> Result<()> {
        self.conn.execute("DELETE FROM command_history", [])?;
        Ok(())
    }

    pub fn delete_connection_log(&self, id: i64) -> Result<()> {
        self.conn.execute("DELETE FROM connection_log WHERE id = ?1", [id])?;
        Ok(())
    }

    pub fn store(&self) -> Store<'_> {
        Store(&self.conn)
    }

    /// Runs `f` in a transaction: all or nothing.
    pub fn transaction<T, E: From<rusqlite::Error>>(
        &mut self,
        f: impl FnOnce(Store<'_>) -> std::result::Result<T, E>,
    ) -> std::result::Result<T, E> {
        let tx = self.conn.transaction()?;
        let out = f(Store(&tx))?;
        tx.commit()?;
        Ok(out)
    }
}

fn hex_upper(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02X}")).collect()
}

/// Reads and writes records on a connection (or inside a transaction).
#[derive(Clone, Copy)]
pub struct Store<'a>(pub &'a Connection);

impl<'a> Store<'a> {
    pub fn all<R: Record>(&self) -> Result<Vec<R>> {
        self.query(&format!("SELECT * FROM {} ORDER BY id", R::TABLE), [])
    }

    pub fn query<R: Record, P: rusqlite::Params>(&self, sql: &str, p: P) -> Result<Vec<R>> {
        let mut st = self.0.prepare(sql)?;
        let rows = st.query_map(p, |row| read::<R>(row))?;
        rows.collect()
    }

    pub fn one<R: Record, P: rusqlite::Params>(&self, sql: &str, p: P) -> Result<Option<R>> {
        Ok(self.query(sql, p)?.into_iter().next())
    }

    pub fn get<R: Record>(&self, id: i64) -> Result<Option<R>> {
        self.one(&format!("SELECT * FROM {} WHERE id = ?1", R::TABLE), [id])
    }

    pub fn by_sync_id<R: Record>(&self, sync_id: &str) -> Result<Option<R>> {
        self.one(&format!("SELECT * FROM {} WHERE syncId = ?1", R::TABLE), [sync_id])
    }

    fn insert_with<R: Record>(&self, r: &R, verb: &str) -> Result<Option<i64>> {
        let (cols, extra) = split(r);
        let names: Vec<String> = cols.iter().map(|(c, _)| format!("`{c}`")).collect();
        let marks: Vec<String> = (1..=cols.len() + 1).map(|i| format!("?{i}")).collect();
        let sql = format!(
            "{verb} INTO {} ({}, `extra`) VALUES ({})",
            R::TABLE,
            names.join(", "),
            marks.join(", ")
        );
        let mut values: Vec<Sql> = cols.into_iter().map(|(_, v)| v).collect();
        values.push(Sql::Text(extra));
        let n = self.0.execute(&sql, rusqlite::params_from_iter(values))?;
        Ok((n > 0).then(|| self.0.last_insert_rowid()))
    }

    /// Inserts `r` (a new id when its id is 0) and returns its id.
    pub fn insert<R: Record>(&self, r: &R) -> Result<i64> {
        Ok(self.insert_with(r, "INSERT")?.unwrap_or(-1))
    }

    /// Like Room's `OnConflictStrategy.IGNORE`: None when a unique constraint refused it.
    pub fn insert_or_ignore<R: Record>(&self, r: &R) -> Result<Option<i64>> {
        self.insert_with(r, "INSERT OR IGNORE")
    }

    /// Rewrites every column of the record with `r`'s id.
    pub fn update<R: Record>(&self, r: &R) -> Result<usize> {
        let (cols, extra) = split(r);
        let sets: Vec<String> = cols
            .iter()
            .filter(|(c, _)| *c != "id")
            .enumerate()
            .map(|(i, (c, _))| format!("`{c}` = ?{}", i + 1))
            .collect();
        let mut values: Vec<Sql> = cols.into_iter().filter(|(c, _)| *c != "id").map(|(_, v)| v).collect();
        values.push(Sql::Text(extra));
        values.push(Sql::Integer(r.id()));
        let n = values.len();
        let sql = format!(
            "UPDATE {} SET {}, `extra` = ?{} WHERE id = ?{}",
            R::TABLE,
            sets.join(", "),
            n - 1,
            n
        );
        self.0.execute(&sql, rusqlite::params_from_iter(values))
    }

    /// Like Room's `@Upsert`: inserts a new record (id 0) or rewrites an existing one; returns its id.
    pub fn upsert<R: Record>(&self, r: &R) -> Result<i64> {
        if r.id() != 0 && self.update(r)? > 0 {
            return Ok(r.id());
        }
        self.insert(r)
    }

    pub fn delete<R: Record>(&self, id: i64) -> Result<usize> {
        self.0.execute(&format!("DELETE FROM {} WHERE id = ?1", R::TABLE), [id])
    }

    // --- Synchronisation (SyncDao) ---

    pub fn tombstones(&self) -> Result<Vec<SyncTombstone>> {
        let mut st = self.0.prepare("SELECT syncId, kind, deletedAt FROM sync_tombstone")?;
        let rows = st.query_map([], |r| {
            Ok(SyncTombstone {
                sync_id: r.get(0)?,
                kind: r.get(1)?,
                deleted_at: int_at(r, 2)?,
                extra: Map::new(),
            })
        })?;
        rows.collect()
    }

    pub fn put_tombstone(&self, t: &SyncTombstone) -> Result<()> {
        self.0.execute(
            "INSERT OR REPLACE INTO sync_tombstone(syncId, kind, deletedAt) VALUES (?1, ?2, ?3)",
            params![t.sync_id, t.kind, t.deleted_at],
        )?;
        Ok(())
    }

    pub fn drop_tombstone(&self, sync_id: &str) -> Result<()> {
        self.0.execute("DELETE FROM sync_tombstone WHERE syncId = ?1", [sync_id])?;
        Ok(())
    }

    pub fn prune_tombstones(&self, before: i64) -> Result<()> {
        self.0.execute("DELETE FROM sync_tombstone WHERE deletedAt < ?1", [before])?;
        Ok(())
    }

    /// The system detected on a host: local state, not an edit (`osId` is outside the triggers' columns).
    pub fn set_os(&self, host_id: i64, os_id: &str) -> Result<()> {
        self.0.execute("UPDATE host SET osId = ?1 WHERE id = ?2", params![os_id, host_id])?;
        Ok(())
    }

    /// HostDao.markConnected: local state, outside the triggers' columns.
    pub fn mark_connected(&self, host_id: i64, at: i64) -> Result<()> {
        self.0
            .execute("UPDATE host SET lastConnectedAt = ?1 WHERE id = ?2", params![at, host_id])?;
        Ok(())
    }

    /// One more run of a snippet (ToolsDao): local state, outside the triggers' columns.
    pub fn count_run(&self, snippet_id: i64) -> Result<()> {
        self.0
            .execute("UPDATE snippet SET runCount = runCount + 1 WHERE id = ?1", [snippet_id])?;
        Ok(())
    }

    /// Brings back the stamps from too far ahead of `now` (see [stamp_repairs]), before a merge.
    pub fn repair_stamps(&self, now: i64) -> Result<()> {
        for s in stamp_repairs(now) {
            self.0.execute_batch(&s)?;
        }
        Ok(())
    }

    /// Sets updatedAt of row `id` of `table` (a column the triggers do not watch).
    pub fn restamp(&self, table: &str, id: i64, updated_at: i64) -> Result<()> {
        self.0
            .execute(&format!("UPDATE {table} SET updatedAt = ?1 WHERE id = ?2"), params![updated_at, id])?;
        Ok(())
    }

    pub fn set_sync_id(&self, table: &str, id: i64, sync_id: &str) -> Result<()> {
        self.0
            .execute(&format!("UPDATE {table} SET syncId = ?1 WHERE id = ?2"), params![sync_id, id])?;
        Ok(())
    }

    /// The sync id of row `id` of `table`, None when there is no such row.
    pub fn sync_id_of(&self, table: &str, id: i64) -> Result<Option<String>> {
        self.0
            .query_row(&format!("SELECT syncId FROM {table} WHERE id = ?1"), [id], |r| r.get(0))
            .optional()
    }

    /// Puts the triggers aside (`true`) while another device's data is written, then back (see NOT_MERGING).
    pub fn set_merging(&self, on: bool) -> Result<()> {
        self.0.execute("UPDATE sync_ctl SET merging = ?1", [on as i64])?;
        Ok(())
    }

    /// Applies another device's deletion unless the record changed after it; the number of rows removed.
    pub fn delete_synced(&self, kind: &str, sync_id: &str, at: i64) -> Result<usize> {
        match kind {
            // A local shell or a serial port belongs to its device: a deletion from another one never removes it.
            "host" => self.0.execute(
                "DELETE FROM host WHERE syncId = ?1 AND updatedAt <= ?2 AND protocol NOT IN ('LOCAL', 'SERIAL')",
                params![sync_id, at],
            ),
            "host_group" | "ssh_key" | "identity" | "snippet" | "port_forward" => self.0.execute(
                &format!("DELETE FROM {kind} WHERE syncId = ?1 AND updatedAt <= ?2"),
                params![sync_id, at],
            ),
            "known_host" => self.0.execute(
                "DELETE FROM known_host WHERE 'kh:' || host || ':' || port || ':' || keyType = ?1 AND addedAt <= ?2",
                params![sync_id, at],
            ),
            _ => Ok(0),
        }
    }

    /// The latest local change, to know whether there is something to send.
    pub fn last_change(&self) -> Result<i64> {
        self.0
            .query_row(
                "SELECT MAX(m) FROM (
                    SELECT MAX(updatedAt) AS m FROM host UNION ALL SELECT MAX(updatedAt) FROM host_group
                    UNION ALL SELECT MAX(updatedAt) FROM ssh_key UNION ALL SELECT MAX(updatedAt) FROM identity
                    UNION ALL SELECT MAX(updatedAt) FROM snippet UNION ALL SELECT MAX(updatedAt) FROM port_forward
                    UNION ALL SELECT MAX(addedAt) FROM known_host UNION ALL SELECT MAX(deletedAt) FROM sync_tombstone)",
                [],
                |r| match r.get::<_, Sql>(0)? {
                    Sql::Null => Ok(None),
                    _ => int_at(r, 0).map(Some),
                },
            )
            .optional()
            .map(|v| v.flatten().unwrap_or(0))
    }

    // --- Lookups by natural key, used when two devices meet for the first time ---

    pub fn group_by_name(&self, name: &str) -> Result<Option<HostGroup>> {
        self.one("SELECT * FROM host_group WHERE name = ?1", [name])
    }

    pub fn key_by_fingerprint(&self, fingerprint: &str) -> Result<Option<SshKey>> {
        self.one("SELECT * FROM ssh_key WHERE fingerprint = ?1", [fingerprint])
    }

    /// Trusts `k` in place of the key known for the same host, port and type.
    pub fn replace_known_host(&self, k: &KnownHost) -> Result<()> {
        self.0.execute(
            "DELETE FROM known_host WHERE host = ?1 AND port = ?2 AND keyType = ?3",
            params![k.host, k.port, k.key_type],
        )?;
        self.insert(k)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sync_lookups_are_indexed() {
        let db = Db::in_memory().unwrap();
        let plan: String = db
            .conn
            .query_row("EXPLAIN QUERY PLAN SELECT * FROM host WHERE syncId = 'x'", [], |r| r.get(3))
            .unwrap();
        assert!(plan.contains("index_host_syncId"), "{plan}");
        let plan: String = db
            .conn
            .query_row("EXPLAIN QUERY PLAN SELECT * FROM host WHERE alias = 'x'", [], |r| r.get(3))
            .unwrap();
        assert!(plan.contains("index_host_alias"), "{plan}");
    }

    #[test]
    fn secrets_stay_on_this_computer() {
        let db = Db::in_memory().unwrap();
        assert_eq!(db.secret("backup-password"), None);
        db.set_secret("backup-password", Some("s3cret")).unwrap();
        db.set_secret("backup-password", Some("other")).unwrap();
        assert_eq!(db.secret("backup-password").as_deref(), Some("other"));
        db.set_secret("backup-password", None).unwrap();
        assert_eq!(db.secret("backup-password"), None);
    }

    #[test]
    fn encrypted_file_needs_its_key() {
        let dir = std::env::temp_dir().join(format!("nautile-db-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("test.db");
        let _ = std::fs::remove_file(&path);
        let key = [7u8; 32];
        {
            let db = Db::open(&path, &key).unwrap();
            db.store().insert(&Host::new("nas", "10.0.0.2")).unwrap();
        }
        let head = std::fs::read(&path).unwrap();
        assert!(!head.starts_with(b"SQLite format 3"), "the file must be encrypted");
        let db = Db::open(&path, &key).unwrap();
        assert_eq!(db.store().all::<Host>().unwrap()[0].alias, "nas");
        drop(db);
        assert!(Db::open(&path, &[8u8; 32]).is_err());
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn records_round_trip_with_unknown_fields() {
        let db = Db::in_memory().unwrap();
        let s = db.store();
        let mut h = Host::new("pc", "pc.lan");
        h.protocol = crate::model::Protocol::Rdp;
        h.rdp_audio = false;
        h.extra.insert("futureField".into(), Value::from("kept"));
        let id = s.insert(&h).unwrap();
        let back: Host = s.get(id).unwrap().unwrap();
        assert_eq!(back.extra["futureField"], "kept");
        assert!(!back.rdp_audio);
        assert_eq!(back.sync_id.len(), 32, "the trigger gives it a sync id");
        assert!(back.updated_at > 1);
    }

    #[test]
    fn a_field_kept_in_extra_by_an_older_version_moves_into_its_new_column() {
        // The desktop 0.4.0 had no `icon` column: the icon synchronised from a phone stayed in `extra`.
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE host_group (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL,
                `sortOrder` INTEGER NOT NULL, `syncId` TEXT NOT NULL DEFAULT '', `updatedAt` INTEGER NOT NULL DEFAULT 0,
                `extra` TEXT NOT NULL DEFAULT '{}');
            INSERT INTO host_group (name, color, sortOrder, syncId, updatedAt, extra)
                VALUES ('Lab', 1, 0, 'a', 5, '{\"icon\":\"server\",\"future\":1}'), ('Plain', 1, 1, 'b', 5, '{}'), ('Odd', 1, 2, 'c', 5, 'not json');",
        )
        .unwrap();
        let db = Db::setup(conn).unwrap();
        let groups: Vec<HostGroup> = db.store().all().unwrap();
        assert_eq!(groups[0].icon.as_deref(), Some("server"));
        assert_eq!(groups[0].extra.get("future"), Some(&Value::from(1)));
        assert!(!groups[0].extra.contains_key("icon"));
        assert_eq!(groups[1].icon, None);
        assert_eq!(groups[0].updated_at, 5, "moving a field is not an edit");
        // Saved again, then opened again: still there.
        db.store().update(&groups[0]).unwrap();
        let again = Db::setup(db.conn).unwrap();
        assert_eq!(
            again.store().get::<HostGroup>(groups[0].id).unwrap().unwrap().icon.as_deref(),
            Some("server")
        );
    }

    #[test]
    fn a_deletion_is_dated_after_a_change_stamped_in_the_future() {
        let db = Db::in_memory().unwrap();
        let s = db.store();
        let ahead = crate::model::now_ms() + 3_600_000;
        let id = s
            .insert(&Host {
                updated_at: ahead,
                ..Host::new("nas", "n")
            })
            .unwrap();
        let k = s
            .insert(&KnownHost {
                id: 0,
                host: "n".into(),
                port: 22,
                key_type: "ssh-ed25519".into(),
                public_key: "AAAA".into(),
                fingerprint: "SHA256:x".into(),
                added_at: ahead,
                extra: Map::new(),
            })
            .unwrap();
        s.delete::<Host>(id).unwrap();
        s.delete::<KnownHost>(k).unwrap();
        let tombs = s.tombstones().unwrap();
        assert_eq!(tombs.len(), 2);
        assert!(tombs.iter().all(|t| t.deleted_at == ahead + 1), "{tombs:?}");
    }
}

#[cfg(test)]
mod forward_tests {
    use super::*;
    use crate::model::PortForward;

    #[test]
    fn forwards_round_trip() {
        let db = Db::in_memory().unwrap();
        let s = db.store();
        let h = s.insert(&Host::new("a", "b")).unwrap();
        let f: PortForward =
            serde_json::from_value(serde_json::json!({ "name": "x", "hostId": h, "kind": "DYNAMIC", "bindPort": 1080 })).unwrap();
        s.insert(&f).unwrap();
        let all: Result<Vec<PortForward>> = s.all();
        assert_eq!(all.unwrap().len(), 1);
    }
}

/// One connection made from this computer (Android's `ConnectionLog`, same table).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConnectionLog {
    pub id: i64,
    /// The saved host; `None` for a quick connection (`root@192.168.1.2`).
    pub host_id: Option<i64>,
    pub alias: String,
    /// The protocol's name, as Android stores it (`SSH`, `RDP`…).
    pub protocol: String,
    pub username: String,
    pub hostname: String,
    pub port: i32,
    pub at: i64,
}
