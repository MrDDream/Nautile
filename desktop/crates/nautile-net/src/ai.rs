//! The AI assistant's providers, ported from Android's `AiProviders.kt` / `AiAssistant.kt`: a request in plain
//! language (with the end of the terminal screen and attached files) becomes a shell command and an explanation.

use std::time::Duration;

use nautile_core::{i18n, tr, trf};
use serde_json::{json, Value};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum ProviderKind {
    Claude,
    Openai,
    Gemini,
    Grok,
    Mistral,
    Deepseek,
    Local,
    Custom,
}

impl ProviderKind {
    pub const ALL: [ProviderKind; 8] = [
        ProviderKind::Claude,
        ProviderKind::Openai,
        ProviderKind::Gemini,
        ProviderKind::Grok,
        ProviderKind::Mistral,
        ProviderKind::Deepseek,
        ProviderKind::Local,
        ProviderKind::Custom,
    ];

    pub fn label(self) -> String {
        match self {
            ProviderKind::Claude => "Claude (Anthropic)".into(),
            ProviderKind::Openai => "OpenAI".into(),
            ProviderKind::Gemini => "Google Gemini".into(),
            ProviderKind::Grok => "xAI Grok".into(),
            ProviderKind::Mistral => "Mistral".into(),
            ProviderKind::Deepseek => "DeepSeek".into(),
            ProviderKind::Local => tr("Local (Ollama, LM Studio…)"),
            ProviderKind::Custom => tr("Autre (compatible OpenAI)"),
        }
    }

    /// Name short enough for a chip.
    pub fn short_label(self) -> String {
        match self {
            ProviderKind::Claude => "Claude".into(),
            ProviderKind::Gemini => "Gemini".into(),
            ProviderKind::Grok => "Grok".into(),
            ProviderKind::Local => tr("Local"),
            ProviderKind::Custom => tr("Autre"),
            k => k.label(),
        }
    }

    /// The default model, editable (« Charger la liste » gives the current ones).
    pub fn default_model(self) -> &'static str {
        match self {
            ProviderKind::Claude => "claude-opus-5-5",
            ProviderKind::Openai => "gpt-5",
            ProviderKind::Gemini => "gemini-2.5-flash",
            ProviderKind::Grok => "grok-4",
            ProviderKind::Mistral => "mistral-large-latest",
            ProviderKind::Deepseek => "deepseek-chat",
            ProviderKind::Local => "llama3.1",
            ProviderKind::Custom => "",
        }
    }

    pub fn default_url(self) -> Option<&'static str> {
        match self {
            ProviderKind::Claude => None,
            ProviderKind::Openai => Some("https://api.openai.com/v1"),
            ProviderKind::Gemini => Some("https://generativelanguage.googleapis.com/v1beta"),
            ProviderKind::Grok => Some("https://api.x.ai/v1"),
            ProviderKind::Mistral => Some("https://api.mistral.ai/v1"),
            ProviderKind::Deepseek => Some("https://api.deepseek.com/v1"),
            ProviderKind::Local => Some("http://localhost:11434/v1"),
            ProviderKind::Custom => Some("https://openrouter.ai/api/v1"),
        }
    }

    pub fn needs_key(self) -> bool {
        self != ProviderKind::Local
    }

    pub fn editable_url(self) -> bool {
        matches!(self, ProviderKind::Local | ProviderKind::Custom)
    }

    pub fn key_hint(self) -> &'static str {
        match self {
            ProviderKind::Claude => "sk-ant-…",
            ProviderKind::Openai | ProviderKind::Deepseek => "sk-…",
            ProviderKind::Gemini => "AIza…",
            ProviderKind::Grok => "xai-…",
            _ => "",
        }
    }
}

impl std::fmt::Display for ProviderKind {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.label())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Config {
    pub kind: ProviderKind,
    pub api_key: Option<String>,
    pub model: String,
    pub base_url: Option<String>,
}

/// One message of a conversation: the user's or the model's.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AiMessage {
    pub from_user: bool,
    pub text: String,
}

/// A model id as people say it: claude-sonnet-5-5 → Sonnet 5.5, gpt-5-mini → GPT-5 mini.
pub fn pretty_model(id: &str) -> String {
    if id.trim().is_empty() {
        return id.into();
    }
    let mut m = id.rsplit('/').next().unwrap_or(id).to_lowercase();
    for suffix in ["-latest", "-preview"] {
        if let Some(s) = m.strip_suffix(suffix) {
            m = s.to_owned();
        }
    }
    // A trailing date (-20250101 or -2025-01-01).
    let parts: Vec<&str> = m.split('-').collect();
    if parts.last().is_some_and(|p| p.len() == 8 && p.chars().all(|c| c.is_ascii_digit())) {
        m = parts[..parts.len() - 1].join("-");
    } else if parts.len() > 3 {
        let n = parts.len();
        if parts[n - 3].len() == 4 && parts[n - 3..].iter().all(|p| p.chars().all(|c| c.is_ascii_digit())) {
            m = parts[..n - 3].join("-");
        }
    }
    let cap = |w: &str| {
        let mut c = w.chars();
        c.next()
            .map(|f| f.to_uppercase().collect::<String>() + c.as_str())
            .unwrap_or_default()
    };
    let parts: Vec<&str> = m.split('-').collect();
    if parts.first() == Some(&"claude") && parts.len() >= 3 {
        let families = ["opus", "sonnet", "haiku", "fable", "mythos"];
        if families.contains(&parts[1]) {
            let version = parts[2..]
                .iter()
                .take_while(|p| p.chars().all(|c| c.is_ascii_digit()))
                .copied()
                .collect::<Vec<_>>()
                .join(".");
            return format!("{} {version}", cap(parts[1]));
        }
        if let Some(f) = parts.iter().find(|p| families.contains(p)) {
            let version = parts[1..]
                .iter()
                .take_while(|p| p.chars().all(|c| c.is_ascii_digit()))
                .copied()
                .collect::<Vec<_>>()
                .join(".");
            return format!("{} {version}", cap(f));
        }
    }
    if let Some(rest) = m.strip_prefix("deepseek-") {
        return format!("DeepSeek {}", rest.split('-').map(cap).collect::<Vec<_>>().join(" "));
    }
    if let Some(rest) = m.strip_prefix("gpt-") {
        let mut it = rest.splitn(2, '-');
        let version = it.next().unwrap_or_default();
        return match it.next() {
            Some(more) => format!("GPT-{version} {}", more.replace('-', " ")),
            None => format!("GPT-{version}"),
        };
    }
    m.split('-')
        .filter(|w| !w.is_empty())
        .map(|w| {
            if w.chars().next().is_some_and(char::is_alphabetic) {
                cap(w)
            } else {
                w.to_owned()
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .timeout_connect(Some(Duration::from_secs(15)))
        .timeout_global(Some(Duration::from_secs(300)))
        .http_status_as_error(false)
        .tls_config(
            ureq::tls::TlsConfig::builder()
                .root_certs(ureq::tls::RootCerts::PlatformVerifier)
                .build(),
        )
        .build()
        .into()
}

/// Sends [req] and returns its JSON, or a readable error.
fn send(req: ureq::http::Request<Vec<u8>>, provider: &str) -> Result<Value, String> {
    let mut r = agent().run(req).map_err(|e| trf("%1$s injoignable : %2$s", &[&provider, &e]))?;
    let code = r.status().as_u16();
    let body = r.body_mut().with_config().limit(32 << 20).read_to_string().unwrap_or_default();
    if !(200..300).contains(&code) {
        let parsed: Option<Value> = serde_json::from_str(&body).ok();
        let detail = parsed
            .as_ref()
            .and_then(|o| {
                o.pointer("/error/message")
                    .and_then(Value::as_str)
                    .or_else(|| o.get("error").and_then(Value::as_str))
                    .map(str::to_owned)
            })
            .unwrap_or_else(|| body.chars().take(200).collect());
        return Err(match code {
            401 | 403 => trf("%1$s : clé API refusée", &[&provider]),
            404 => trf("%1$s : modèle ou adresse introuvable (%2$s)", &[&provider, &detail]),
            429 => trf("%1$s : quota ou limite atteint (%2$s)", &[&provider, &detail]),
            c => format!("{provider} ({c}) : {detail}"),
        });
    }
    serde_json::from_str(&body).map_err(|e| e.to_string())
}

fn post(url: &str, headers: &[(&str, String)], body: &Value) -> Result<ureq::http::Request<Vec<u8>>, String> {
    let mut b = ureq::http::Request::builder()
        .method("POST")
        .uri(url)
        .header("Content-Type", "application/json");
    for (k, v) in headers {
        b = b.header(*k, v.as_str());
    }
    b.body(body.to_string().into_bytes()).map_err(|e| e.to_string())
}

fn get(url: &str, headers: &[(&str, String)]) -> Result<ureq::http::Request<Vec<u8>>, String> {
    let mut b = ureq::http::Request::builder().method("GET").uri(url);
    for (k, v) in headers {
        b = b.header(*k, v.as_str());
    }
    b.body(Vec::new()).map_err(|e| e.to_string())
}

const ANTHROPIC: &str = "https://api.anthropic.com/v1";
const ANTHROPIC_VERSION: &str = "2026-10-21";

/// Effort and the server-side refusal fallback exist on the Claude 5 generation only.
fn claude_5(model: &str) -> bool {
    ["claude-opus-5", "claude-sonnet-5-5", "claude-fable-5", "claude-mythos-5"]
        .iter()
        .any(|p| model.starts_with(p))
}

fn claude_headers(key: &str, model: &str) -> Vec<(&'static str, String)> {
    let mut h = vec![("x-api-key", key.to_owned()), ("anthropic-version", ANTHROPIC_VERSION.to_owned())];
    if claude_5(model) {
        h.push(("anthropic-beta", "server-side-fallback-2026-07-01".to_owned()));
    }
    h
}

/// The body of a Messages API request (Claude).
pub fn claude_body(model: &str, system: &str, messages: &[AiMessage]) -> Value {
    let msgs: Vec<Value> = messages
        .iter()
        .map(|m| json!({ "role": if m.from_user { "user" } else { "assistant" }, "content": m.text }))
        .collect();
    let mut body = json!({ "model": model, "max_tokens": 16000, "system": system, "messages": msgs });
    if claude_5(model) {
        body["output_config"] = json!({ "effort": "medium" });
        // On a policy decline, the API re-runs the request on a fallback model it chooses.
        body["fallbacks"] = json!("default");
    }
    body
}

/// Answers the last user message of [messages], with the earlier ones as context.
pub fn chat(c: &Config, system: &str, messages: &[AiMessage]) -> Result<String, String> {
    let key = || {
        c.api_key
            .clone()
            .filter(|k| !k.trim().is_empty())
            .ok_or_else(|| tr("Clé API manquante"))
    };
    match c.kind {
        ProviderKind::Claude => {
            let key = key()?;
            let base = c
                .base_url
                .clone()
                .filter(|u| !u.trim().is_empty())
                .unwrap_or_else(|| ANTHROPIC.into());
            let o = send(
                post(
                    &format!("{}/messages", base.trim_end_matches('/')),
                    &claude_headers(&key, &c.model),
                    &claude_body(&c.model, system, messages),
                )?,
                "Anthropic",
            )
            .map_err(|e| {
                if e.contains("clé API refusée") {
                    tr("Clé API refusée par Anthropic")
                } else {
                    e
                }
            })?;
            if o.get("stop_reason").and_then(Value::as_str) == Some("refusal") {
                return Err(tr("La demande a été refusée par le modèle."));
            }
            let text: Vec<&str> = o
                .get("content")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter(|b| b.get("type").and_then(Value::as_str) == Some("text"))
                .filter_map(|b| b.get("text").and_then(Value::as_str))
                .collect();
            Ok(text.join("\n"))
        }
        ProviderKind::Gemini => {
            let key = key()?;
            let base = c
                .base_url
                .clone()
                .unwrap_or_else(|| ProviderKind::Gemini.default_url().unwrap_or_default().into());
            let contents: Vec<Value> = messages
                .iter()
                .map(|m| json!({ "role": if m.from_user { "user" } else { "model" }, "parts": [{ "text": m.text }] }))
                .collect();
            let body = json!({ "system_instruction": { "parts": [{ "text": system }] }, "contents": contents });
            let name = c.model.trim_start_matches("models/");
            let o = send(
                post(
                    &format!("{}/models/{name}:generateContent", base.trim_end_matches('/')),
                    &[("x-goog-api-key", key)],
                    &body,
                )?,
                "Gemini",
            )?;
            let parts = o.pointer("/candidates/0/content/parts").and_then(Value::as_array);
            let text: String = parts
                .into_iter()
                .flatten()
                .filter_map(|p| p.get("text").and_then(Value::as_str))
                .collect();
            if text.trim().is_empty() {
                Err(tr("Gemini : réponse vide (filtrée ?)"))
            } else {
                Ok(text)
            }
        }
        kind => {
            if c.model.trim().is_empty() {
                return Err(tr("Choisissez un modèle"));
            }
            let base = c
                .base_url
                .clone()
                .or_else(|| kind.default_url().map(str::to_owned))
                .ok_or_else(|| tr("URL manquante"))?;
            let mut msgs = vec![json!({ "role": "system", "content": system })];
            msgs.extend(
                messages
                    .iter()
                    .map(|m| json!({ "role": if m.from_user { "user" } else { "assistant" }, "content": m.text })),
            );
            let headers: Vec<(&str, String)> = c
                .api_key
                .iter()
                .filter(|k| !k.trim().is_empty())
                .map(|k| ("Authorization", format!("Bearer {k}")))
                .collect();
            let name = kind.label();
            let o = send(
                post(
                    &format!("{}/chat/completions", base.trim_end_matches('/')),
                    &headers,
                    &json!({ "model": c.model, "messages": msgs }),
                )?,
                &name,
            )?;
            o.pointer("/choices/0/message/content")
                .and_then(Value::as_str)
                .map(str::to_owned)
                .ok_or_else(|| trf("%1$s : réponse vide", &[&name]))
        }
    }
}

/// The models the account can use, sorted.
pub fn models(c: &Config) -> Result<Vec<String>, String> {
    let mut out: Vec<String> = match c.kind {
        ProviderKind::Claude => {
            let key = c.api_key.clone().ok_or_else(|| tr("Clé API manquante"))?;
            let mut all = Vec::new();
            let mut after: Option<String> = None;
            loop {
                let url = match &after {
                    Some(a) => format!("{ANTHROPIC}/models?limit=1000&after_id={a}"),
                    None => format!("{ANTHROPIC}/models?limit=1000"),
                };
                let o = send(get(&url, &claude_headers(&key, ""))?, "Anthropic")?;
                let data = o.get("data").and_then(Value::as_array).cloned().unwrap_or_default();
                all.extend(data.iter().filter_map(|m| m.get("id").and_then(Value::as_str).map(str::to_owned)));
                match (o.get("has_more").and_then(Value::as_bool), o.get("last_id").and_then(Value::as_str)) {
                    (Some(true), Some(last)) => after = Some(last.to_owned()),
                    _ => break,
                }
            }
            all
        }
        ProviderKind::Gemini => {
            let key = c.api_key.clone().ok_or_else(|| tr("Clé API manquante"))?;
            let base = c
                .base_url
                .clone()
                .unwrap_or_else(|| ProviderKind::Gemini.default_url().unwrap_or_default().into());
            let o = send(
                get(
                    &format!("{}/models?pageSize=200", base.trim_end_matches('/')),
                    &[("x-goog-api-key", key)],
                )?,
                "Gemini",
            )?;
            o.get("models")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter(|m| {
                    m.get("supportedGenerationMethods")
                        .and_then(Value::as_array)
                        .is_some_and(|a| a.iter().any(|x| x.as_str() == Some("generateContent")))
                })
                .filter_map(|m| {
                    m.get("name")
                        .and_then(Value::as_str)
                        .map(|n| n.trim_start_matches("models/").to_owned())
                })
                .collect()
        }
        kind => {
            let base = c
                .base_url
                .clone()
                .or_else(|| kind.default_url().map(str::to_owned))
                .unwrap_or_default();
            let headers: Vec<(&str, String)> = c.api_key.iter().map(|k| ("Authorization", format!("Bearer {k}"))).collect();
            let o = send(get(&format!("{}/models", base.trim_end_matches('/')), &headers)?, &kind.label())?;
            o.get("data")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(|m| m.get("id").and_then(Value::as_str).map(str::to_owned))
                .collect()
        }
    };
    out.sort();
    Ok(out)
}

/// A shell command and its explanation, from the model's answer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Suggestion {
    pub command: Option<String>,
    pub explanation: String,
}

/// The first ```bash block is the command, the rest the explanation.
pub fn parse(text: &str) -> Suggestion {
    let mut search = 0;
    while let Some(start) = text[search..].find("```").map(|i| i + search) {
        let after = &text[start + 3..];
        let Some(nl) = after.find('\n') else { break };
        let lang = after[..nl].trim();
        let body = &after[nl + 1..];
        let Some(end) = body.find("```") else { break };
        if !["", "bash", "sh", "shell", "zsh"].contains(&lang) {
            // Another language (JSON, YAML…): skip the whole block.
            search = start + 3 + nl + 1 + end + 3;
            continue;
        }
        let command = body[..end].trim().to_owned();
        let whole_end = start + 3 + nl + 1 + end + 3;
        let explanation = format!("{}{}", &text[..start], &text[whole_end..]).trim().to_owned();
        return Suggestion {
            command: Some(command).filter(|c| !c.is_empty()),
            explanation,
        };
    }
    Suggestion {
        command: None,
        explanation: text.trim().to_owned(),
    }
}

/// The built-in system prompt, in the interface language.
pub fn default_prompt() -> &'static str {
    if i18n::is_english() {
        "You are the assistant of an SSH client on a computer. The user describes what they want to do on the server \
they are connected to, asks to explain an error visible on screen, or attaches files \
(configuration, logs, JSON…) to analyse or fix.
Answer in English, briefly:
- first ONE shell command ready to paste, in a single ```bash``` block (several commands chained with && \
if needed); leave the block out if no command fits;
- then a structured, readable explanation: one summary sentence, then if useful short bullets (one idea per \
bullet, **bold** for key items, `code` for names and values), and a short « ### » heading only to separate \
distinct parts; no long paragraphs;
- clearly flag anything destructive or irreversible.
Do not invent paths or service names absent from the request or the screen: use explicit generic \
placeholders (<service>) when information is missing."
    } else {
        "Tu es l'assistant d'un client SSH sur ordinateur. L'utilisateur décrit ce qu'il veut faire sur le serveur \
auquel il est connecté, demande d'expliquer une erreur visible à l'écran, ou joint des fichiers \
(configuration, journaux, JSON…) à analyser ou corriger.
Réponds en français, brièvement :
- d'abord UNE commande shell prête à coller, dans un seul bloc ```bash``` (plusieurs commandes enchaînées \
avec && si nécessaire) ; omets le bloc si aucune commande ne convient ;
- puis une explication structurée et lisible : une phrase de synthèse, puis si utile des puces courtes (une \
idée par puce, **gras** pour les éléments clés, `code` pour les noms et valeurs), et un court titre « ### » \
seulement pour séparer des parties distinctes ; pas de longs paragraphes ;
- signale clairement tout effet destructeur ou irréversible.
N'invente pas de chemins ou de noms de services absents de la demande ou de l'écran : utilise des \
emplacements génériques explicites (<service>) quand une information manque."
    }
}

/// A text file joined to a request, cut to a size the models accept.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AiFile {
    pub name: String,
    pub text: String,
    pub size: u64,
    pub cut: bool,
    /// The end was kept (logs) rather than the beginning.
    pub tail: bool,
}

/// Characters sent per file: about 16 000 tokens.
pub const MAX_FILE_BYTES: usize = 64 * 1024;

fn is_log(name: &str) -> bool {
    let n = name.to_lowercase();
    n.ends_with(".log")
        || n.contains(".log.")
        || n.contains("syslog")
        || n.contains("journal")
        || n.ends_with(".out")
        || n.ends_with(".err")
}

/// Reads a file as text: logs keep their end, other files their beginning. Binary files are refused.
pub fn read_file(name: &str, bytes: &[u8]) -> Result<AiFile, String> {
    if bytes.iter().take(8192).any(|&b| b == 0) {
        return Err(trf(
            "« %1$s » semble binaire : seuls les fichiers texte peuvent être joints.",
            &[&name],
        ));
    }
    let tail = is_log(name);
    let cut = bytes.len() > MAX_FILE_BYTES;
    let slice = match (cut, tail) {
        (false, _) => bytes,
        (true, true) => &bytes[bytes.len() - MAX_FILE_BYTES..],
        (true, false) => &bytes[..MAX_FILE_BYTES],
    };
    let text = String::from_utf8_lossy(slice).trim_matches('\u{FFFD}').to_owned();
    Ok(AiFile {
        name: name.into(),
        text,
        size: bytes.len() as u64,
        cut,
        tail: tail && cut,
    })
}

/// The message sent for a request: the machine, the screen and the files, then the request itself.
pub fn user_message(request: &str, machine: &str, screen: Option<&str>, files: &[AiFile]) -> String {
    let en = i18n::is_english();
    let mut s = format!("{}{machine}\n\n", if en { "Machine: " } else { "Machine : " });
    if let Some(scr) = screen {
        s.push_str(&tr("Fin de l'écran du terminal :\n```\n"));
        s.push_str(scr);
        s.push_str("\n```\n\n");
    }
    for f in files {
        s.push_str(&trf("Fichier joint « %1$s »", &[&f.name]));
        if f.cut {
            let (max, size) = (crate::sftp::human_size(MAX_FILE_BYTES as u64), crate::sftp::human_size(f.size));
            s.push_str(&if f.tail {
                trf(" (fin du fichier, %1$s sur %2$s)", &[&max, &size])
            } else {
                trf(" (début du fichier, %1$s sur %2$s)", &[&max, &size])
            });
        }
        s.push_str(" :\n```\n");
        s.push_str(&f.text);
        s.push_str("\n```\n\n");
    }
    s.push_str(if en { "Request: " } else { "Demande : " });
    s.push_str(request);
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Read, Write};

    /// A one-request HTTP server: checks what it receives, answers [reply].
    fn server(reply: &'static str, check: impl FnOnce(String, String) + Send + 'static) -> String {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", l.local_addr().unwrap());
        std::thread::spawn(move || {
            let (s, _) = l.accept().unwrap();
            let mut r = BufReader::new(s.try_clone().unwrap());
            let mut head = String::new();
            let mut len = 0;
            loop {
                let mut line = String::new();
                r.read_line(&mut line).unwrap();
                if line.to_lowercase().starts_with("content-length:") {
                    len = line[15..].trim().parse().unwrap();
                }
                if line == "\r\n" {
                    break;
                }
                head.push_str(&line);
            }
            let mut body = vec![0u8; len];
            r.read_exact(&mut body).unwrap();
            check(head, String::from_utf8(body).unwrap());
            let mut s = s;
            write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{reply}",
                reply.len()
            )
            .unwrap();
        });
        url
    }

    fn ask(c: Config) -> Result<String, String> {
        chat(
            &c,
            "sys",
            &[AiMessage {
                from_user: true,
                text: "liste les fichiers".into(),
            }],
        )
    }

    #[test]
    fn each_provider_is_spoken_to_in_its_own_dialect() {
        let url = server(
            r#"{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},{"type":"text","text":"```bash\nls\n```"}]}"#,
            |head, body| {
                assert!(head.starts_with("POST /messages "), "{head}");
                let h = head.to_lowercase();
                assert!(h.contains("x-api-key: sk-ant-test") && h.contains("anthropic-version: 2026-10-21"));
                assert!(h.contains("anthropic-beta: server-side-fallback-2026-07-01"));
                let v: Value = serde_json::from_str(&body).unwrap();
                assert_eq!((v["system"].as_str(), v["max_tokens"].as_i64()), (Some("sys"), Some(16000)));
            },
        );
        let claude = Config {
            kind: ProviderKind::Claude,
            api_key: Some("sk-ant-test".into()),
            model: "claude-opus-5-5".into(),
            base_url: Some(url),
        };
        assert_eq!(ask(claude.clone()).unwrap(), "```bash\nls\n```");

        let url = server(r#"{"stop_reason":"refusal","content":[]}"#, |_, _| {});
        assert!(ask(Config {
            base_url: Some(url),
            ..claude
        })
        .unwrap_err()
        .contains("refus"));

        let url = server(r#"{"choices":[{"message":{"content":"ok"}}]}"#, |head, body| {
            assert!(head.starts_with("POST /v1/chat/completions "));
            assert!(head.to_lowercase().contains("authorization: bearer sk-x"));
            let v: Value = serde_json::from_str(&body).unwrap();
            assert_eq!(v["messages"][0]["role"], "system");
            assert_eq!(v["messages"][1]["content"], "liste les fichiers");
        });
        let openai = Config {
            kind: ProviderKind::Custom,
            api_key: Some("sk-x".into()),
            model: "m".into(),
            base_url: Some(format!("{url}/v1")),
        };
        assert_eq!(ask(openai).unwrap(), "ok");

        let url = server(
            r#"{"candidates":[{"content":{"parts":[{"text":"g"},{"text":"o"}]}}]}"#,
            |head, body| {
                assert!(head.starts_with("POST /models/gemini-2.5-flash:generateContent "));
                assert!(head.to_lowercase().contains("x-goog-api-key: aiza"));
                let v: Value = serde_json::from_str(&body).unwrap();
                assert_eq!(v["system_instruction"]["parts"][0]["text"], "sys");
            },
        );
        let gemini = Config {
            kind: ProviderKind::Gemini,
            api_key: Some("AIza".into()),
            model: "gemini-2.5-flash".into(),
            base_url: Some(url),
        };
        assert_eq!(ask(gemini).unwrap(), "go");
    }

    #[test]
    fn commands_are_taken_from_the_answer() {
        let s = parse("```bash\nsudo systemctl restart nginx\n```\nRedémarre **nginx**.");
        assert_eq!(s.command.as_deref(), Some("sudo systemctl restart nginx"));
        assert_eq!(s.explanation, "Redémarre **nginx**.");
        let s = parse("Voici :\n```json\n{}\n```\npuis\n```sh\nls -la\n```");
        assert_eq!(s.command.as_deref(), Some("ls -la"));
        let s = parse("Aucune commande ne convient.");
        assert_eq!((s.command, s.explanation.as_str()), (None, "Aucune commande ne convient."));
    }

    #[test]
    fn model_names_read_like_people_say_them() {
        assert_eq!(pretty_model("claude-sonnet-5-5"), "Sonnet 5.5");
        assert_eq!(pretty_model("claude-opus-5-5"), "Opus 5.5");
        assert_eq!(pretty_model("claude-3-5-haiku-20241022"), "Haiku 3.5");
        assert_eq!(pretty_model("gpt-5-mini"), "GPT-5 mini");
        assert_eq!(pretty_model("gemini-2.5-flash"), "Gemini 2.5 Flash");
        assert_eq!(pretty_model("deepseek-chat"), "DeepSeek Chat");
    }

    #[test]
    fn claude_requests_ask_for_effort_and_the_fallback() {
        let m = [AiMessage {
            from_user: true,
            text: "hello".into(),
        }];
        let b = claude_body("claude-opus-5-5", "sys", &m);
        assert_eq!(b["output_config"]["effort"], "medium");
        assert_eq!(b["fallbacks"], "default");
        assert_eq!(b["messages"][0]["role"], "user");
        let old = claude_body("claude-haiku-4-5", "sys", &m);
        assert!(old.get("fallbacks").is_none() && old.get("output_config").is_none());
        assert!(claude_headers("k", "claude-opus-5-5").iter().any(|(h, _)| *h == "anthropic-beta"));
    }

    #[test]
    fn logs_keep_their_end() {
        let mut big = vec![b'a'; MAX_FILE_BYTES];
        big.extend_from_slice(b"\nERROR at the end");
        let f = read_file("nginx.log", &big).unwrap();
        assert!(f.cut && f.tail && f.text.ends_with("ERROR at the end"));
        let f = read_file("config.yml", &big).unwrap();
        assert!(f.cut && !f.tail && f.text.starts_with('a'));
        assert!(read_file("x.bin", &[0, 1, 2]).is_err());
    }
}
