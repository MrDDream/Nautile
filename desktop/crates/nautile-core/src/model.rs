//! The records, exactly as the Android app serialises them (core/data/.../model/Entities.kt, kotlinx.serialization):
//! same JSON names (camelCase), enums by name, every field written, nulls explicit.
//!
//! Each record also keeps the JSON fields it does not know (`extra`): a newer phone may add some, and the
//! desktop rewrites the whole sync file, so dropping them would erase them on every device.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

/// 0xFFFFB547, the app's amber.
pub const DEFAULT_COLOR: i64 = 0xFFFF_B547;

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn default_color() -> i64 {
    DEFAULT_COLOR
}
fn default_port() -> i32 {
    22
}
fn yes() -> bool {
    true
}
fn localhost() -> String {
    "127.0.0.1".into()
}

/// Debug output of a record as its JSON fields, the [secrets] ones (when set) printed as `***`.
fn debug_masked<T: Serialize>(f: &mut std::fmt::Formatter<'_>, name: &str, record: &T, secrets: &[&str]) -> std::fmt::Result {
    let mut v = serde_json::to_value(record).unwrap_or_default();
    if let Value::Object(fields) = &mut v {
        for k in secrets {
            if let Some(x) = fields.get_mut(*k).filter(|x| !x.is_null()) {
                *x = Value::from("***");
            }
        }
    }
    write!(f, "{name} {v}")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Protocol {
    #[default]
    Ssh,
    Mosh,
    Telnet,
    Serial,
    Local,
    Rdp,
    Vnc,
}

impl Protocol {
    pub fn default_port(self) -> i32 {
        match self {
            Protocol::Ssh | Protocol::Mosh => 22,
            Protocol::Telnet => 23,
            Protocol::Rdp => 3389,
            Protocol::Vnc => 5900,
            Protocol::Serial | Protocol::Local => 0,
        }
    }

    pub fn is_desktop(self) -> bool {
        matches!(self, Protocol::Rdp | Protocol::Vnc)
    }
}

/// `Agent` is a legacy value, treated as `Password`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum AuthMethod {
    #[default]
    Password,
    Key,
    Agent,
    Identity,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum ProxyKind {
    Socks5,
    Http,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum ForwardKind {
    Local,
    Remote,
    Dynamic,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostGroup {
    #[serde(default)]
    pub id: i64,
    pub name: String,
    #[serde(default = "default_color")]
    pub color: i64,
    #[serde(default)]
    pub sort_order: i32,
    #[serde(default)]
    pub sync_id: String,
    #[serde(default)]
    pub updated_at: i64,
    /// A Material Design Icons name (« server », « home »…) shown instead of the colour dot.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

/// Without its secrets, so that a `{:?}` never prints them.
impl std::fmt::Debug for Host {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        debug_masked(f, "Host", self, &["password", "proxyPassword", "identity"])
    }
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Host {
    #[serde(default)]
    pub id: i64,
    pub alias: String,
    pub hostname: String,
    #[serde(default = "default_port")]
    pub port: i32,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub protocol: Protocol,
    #[serde(default)]
    pub auth_method: AuthMethod,
    #[serde(default)]
    pub password: Option<String>,
    /// Legacy private key text (older Android versions).
    #[serde(default)]
    pub identity: Option<String>,
    #[serde(default)]
    pub key_id: Option<i64>,
    #[serde(default)]
    pub identity_id: Option<i64>,
    #[serde(default)]
    pub agent_forwarding: bool,
    #[serde(default)]
    pub proxy_type: Option<ProxyKind>,
    #[serde(default)]
    pub proxy_host: Option<String>,
    #[serde(default)]
    pub proxy_port: Option<i32>,
    #[serde(default)]
    pub proxy_username: Option<String>,
    #[serde(default)]
    pub proxy_password: Option<String>,
    /// e.g. `7000,8000,u:9000`.
    #[serde(default)]
    pub knock_sequence: Option<String>,
    #[serde(default)]
    pub serial_baud: Option<i32>,
    #[serde(default)]
    pub rdp_domain: Option<String>,
    /// `WIDTHxHEIGHT`; None fits the window.
    #[serde(default)]
    pub rdp_resolution: Option<String>,
    #[serde(default)]
    pub rdp_scale: Option<i32>,
    /// NLA, TLS or RDP; None negotiates.
    #[serde(default)]
    pub rdp_security: Option<String>,
    #[serde(default = "yes")]
    pub rdp_audio: bool,
    #[serde(default)]
    pub rdp_console: bool,
    /// Detected OS, local to each device.
    #[serde(default)]
    pub os_id: Option<String>,
    #[serde(default)]
    pub group_id: Option<i64>,
    #[serde(default)]
    pub jump_host_id: Option<i64>,
    #[serde(default = "default_color")]
    pub color: i64,
    /// Comma-separated.
    #[serde(default)]
    pub tags: String,
    #[serde(default)]
    pub startup_command: Option<String>,
    #[serde(default = "now_ms")]
    pub created_at: i64,
    /// Local to each device.
    #[serde(default)]
    pub last_connected_at: Option<i64>,
    #[serde(default)]
    pub sync_id: String,
    #[serde(default)]
    pub updated_at: i64,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Host {
    /// A new host with the Android defaults.
    pub fn new(alias: impl Into<String>, hostname: impl Into<String>) -> Self {
        serde_json::from_value(serde_json::json!({ "alias": alias.into(), "hostname": hostname.into() })).expect("defaults")
    }

    /// `user@host[:port]`, as shown under the alias.
    pub fn address(&self) -> String {
        let mut s = String::new();
        if !self.username.trim().is_empty() {
            s.push_str(&self.username);
            s.push('@');
        }
        s.push_str(&self.hostname);
        if self.port != self.protocol.default_port() {
            s.push(':');
            s.push_str(&self.port.to_string());
        }
        s
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KnownHost {
    #[serde(default)]
    pub id: i64,
    pub host: String,
    pub port: i32,
    /// sshj's name: ssh-ed25519, ssh-rsa, ecdsa-sha2-nistp256…
    pub key_type: String,
    /// Base64 of the SSH wire blob.
    pub public_key: String,
    /// `SHA256:` + unpadded base64.
    pub fingerprint: String,
    #[serde(default = "now_ms")]
    pub added_at: i64,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

/// Without its secrets, so that a `{:?}` never prints them.
impl std::fmt::Debug for SshKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        debug_masked(f, "SshKey", self, &["privateKey", "savedPassphrase"])
    }
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SshKey {
    #[serde(default)]
    pub id: i64,
    pub name: String,
    pub key_type: String,
    /// "ED25519", "RSA 4096", "ECDSA 256"…
    pub description: String,
    /// authorized_keys line.
    pub public_key: String,
    /// OpenSSH, PEM or PuTTY text, as imported.
    pub private_key: String,
    pub fingerprint: String,
    pub encrypted: bool,
    #[serde(default)]
    pub saved_passphrase: Option<String>,
    #[serde(default)]
    pub generated: bool,
    #[serde(default = "now_ms")]
    pub created_at: i64,
    #[serde(default)]
    pub sync_id: String,
    #[serde(default)]
    pub updated_at: i64,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

/// Without its secrets, so that a `{:?}` never prints them.
impl std::fmt::Debug for Identity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        debug_masked(f, "Identity", self, &["password"])
    }
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Identity {
    #[serde(default)]
    pub id: i64,
    pub name: String,
    pub username: String,
    #[serde(default)]
    pub password: Option<String>,
    #[serde(default)]
    pub key_id: Option<i64>,
    #[serde(default)]
    pub sync_id: String,
    #[serde(default)]
    pub updated_at: i64,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Snippet {
    #[serde(default)]
    pub id: i64,
    pub name: String,
    /// Variables are written `${NAME}`.
    pub command: String,
    #[serde(default)]
    pub tag: String,
    /// Local to each device.
    #[serde(default)]
    pub run_count: i32,
    #[serde(default = "now_ms")]
    pub created_at: i64,
    #[serde(default)]
    pub sync_id: String,
    #[serde(default)]
    pub updated_at: i64,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

/// The `${NAME}` variables of a command (letter or `_` first, then letters, digits, `_`), in order of first
/// appearance, like Android's `\$\{([A-Za-z_][A-Za-z0-9_]*)\}`.
pub fn snippet_variables(command: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let mut rest = command;

    while let Some(start) = rest.find("${") {
        let after = &rest[start + 2..];
        let valid = after.find('}').map(|end| &after[..end]).filter(|name| {
            let mut chars = name.chars();
            chars.next().is_some_and(|c| c.is_ascii_alphabetic() || c == '_') && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
        });
        match valid {
            Some(name) => {
                if !out.iter().any(|v| v == name) {
                    out.push(name.to_owned());
                }
                rest = &after[name.len() + 1..];
            }
            None => rest = after,
        }
    }
    out
}

impl Snippet {
    /// The `${NAME}` variables (letter or `_` first, then letters, digits, `_`), in order of first appearance,
    /// like Android's `\$\{([A-Za-z_][A-Za-z0-9_]*)\}`.
    pub fn variables(&self) -> Vec<String> {
        snippet_variables(&self.command)
    }

    /// The command with `${NAME}` replaced by `values`; unknown names are left as they are.
    pub fn fill(&self, values: &std::collections::HashMap<String, String>) -> String {
        let mut cmd = self.command.clone();
        for name in self.variables() {
            if let Some(v) = values.get(&name) {
                cmd = cmd.replace(&format!("${{{name}}}"), v);
            }
        }
        cmd
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PortForward {
    #[serde(default)]
    pub id: i64,
    pub name: String,
    pub host_id: i64,
    pub kind: ForwardKind,
    #[serde(default = "localhost")]
    pub bind_host: String,
    pub bind_port: i32,
    #[serde(default)]
    pub dest_host: String,
    #[serde(default)]
    pub dest_port: i32,
    #[serde(default)]
    pub auto_start: bool,
    #[serde(default)]
    pub sync_id: String,
    #[serde(default)]
    pub updated_at: i64,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

/// A deleted record, so the deletion reaches the other devices. `kind` is the table name
/// (host_group, host, ssh_key, identity, snippet, port_forward, known_host).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncTombstone {
    pub sync_id: String,
    pub kind: String,
    pub deleted_at: i64,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

/// Id of a known host's tombstone (known hosts have no sync id).
pub fn known_host_tombstone_id(host: &str, port: i32, key_type: &str) -> String {
    format!("kh:{host}:{port}:{key_type}")
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncDevice {
    /// UUID v4.
    pub id: String,
    pub name: String,
    pub last_seen: i64,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

/// What a backup or sync file holds (BackupCodec.kt).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupData {
    #[serde(default = "two")]
    pub version: i32,
    #[serde(default = "now_ms")]
    pub exported_at: i64,
    #[serde(default)]
    pub groups: Vec<HostGroup>,
    #[serde(default)]
    pub hosts: Vec<Host>,
    #[serde(default)]
    pub keys: Vec<SshKey>,
    #[serde(default)]
    pub identities: Vec<Identity>,
    #[serde(default)]
    pub known_hosts: Vec<KnownHost>,
    #[serde(default)]
    pub snippets: Vec<Snippet>,
    #[serde(default)]
    pub forwards: Vec<PortForward>,
    #[serde(default)]
    pub tombstones: Vec<SyncTombstone>,
    #[serde(default)]
    pub devices: Vec<SyncDevice>,
    /// The assistant's API keys, from the devices that synchronise them (never in a backup).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub ai_keys: Vec<SyncedAiKey>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

/// An AI provider's API key, synchronised only between devices that chose to (« Clés API de l'assistant IA »;
/// SyncedAiKey.kt). The latest change wins; an empty `key` is a key removed. `provider`: CLAUDE, OPENAI…
#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncedAiKey {
    pub provider: String,
    pub key: String,
    pub updated_at: i64,
}

impl std::fmt::Debug for SyncedAiKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SyncedAiKey")
            .field("provider", &self.provider)
            .field("key", &if self.key.is_empty() { "" } else { "***" })
            .field("updated_at", &self.updated_at)
            .finish()
    }
}

fn two() -> i32 {
    2
}

impl Default for BackupData {
    fn default() -> Self {
        serde_json::from_str("{}").expect("defaults")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn debug_hides_the_secrets_and_keeps_the_rest() {
        let mut h = Host::new("nas", "10.0.0.2");
        h.password = Some("hunter2".into());
        h.proxy_password = Some("proxy-pw".into());
        let printed = format!("{h:?}");
        assert!(printed.contains("10.0.0.2") && printed.contains("\"password\":\"***\""));
        assert!(!printed.contains("hunter2") && !printed.contains("proxy-pw"));
        assert!(printed.contains("\"identity\":null"), "unset secrets stay null");
        let k: SshKey = serde_json::from_value(serde_json::json!({
            "name": "k", "keyType": "ssh-ed25519", "description": "ED25519", "publicKey": "ssh-ed25519 AAAA",
            "privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----", "fingerprint": "SHA256:x", "encrypted": true,
            "savedPassphrase": "s3cret-words"
        }))
        .unwrap();
        let printed = format!("{k:?}");
        assert!(!printed.contains("BEGIN") && !printed.contains("s3cret-words") && printed.contains("SHA256:x"));
        let i: Identity = serde_json::from_value(serde_json::json!({ "name": "me", "username": "root", "password": "pw1" })).unwrap();
        assert!(!format!("{i:?}").contains("pw1"));
    }
}
