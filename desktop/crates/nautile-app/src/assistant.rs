//! The AI assistant beside a session: one conversation per server, kept on this computer, with the end of the
//! screen and files attached on demand; the command it suggests can be typed into the session.

use std::collections::HashMap;
use std::path::PathBuf;

use iced::widget::markdown;
use iced::Task;
use nautile_core::model::now_ms;
use nautile_core::{tr, trf};
use nautile_net::ai::{self, AiFile, AiMessage, Config, ProviderKind};
use serde::{Deserialize, Serialize};
use sha2::Digest;

use crate::app::{App, Message};

/// Earlier exchanges sent along with a new question.
const MAX_CONTEXT_TURNS: usize = 8;
/// Exchanges kept per server.
const MAX_KEPT_TURNS: usize = 40;
/// What is kept of a sent message (its attached files may be large).
const MAX_SENT_CHARS: usize = 80_000;

/// One exchange with the assistant (same fields as Android's `AiTurn`).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Turn {
    pub request: String,
    /// The full message sent (machine, screen, files), replayed as context.
    pub sent: String,
    #[serde(default)]
    pub files: Vec<String>,
    #[serde(default)]
    pub screen: bool,
    pub provider: ProviderKind,
    #[serde(default)]
    pub model: String,
    pub raw: String,
    #[serde(default)]
    pub at: i64,
}

/// A turn ready to show: its command and its explanation as Markdown.
pub struct Shown {
    pub turn: Turn,
    pub command: Option<String>,
    pub explanation: markdown::Content,
}

impl Shown {
    pub fn new(turn: Turn) -> Shown {
        let s = ai::parse(&turn.raw);
        Shown {
            command: s.command,
            explanation: markdown::Content::parse(&s.explanation),
            turn,
        }
    }
}

#[derive(Default)]
pub struct AssistantState {
    /// The panel is shown beside the sessions.
    pub open: bool,
    pub input: String,
    pub screen: bool,
    pub files: Vec<AiFile>,
    pub busy: bool,
    pub error: Option<String>,
    /// Conversations by key (`host:<id>`, `local`), loaded on first use.
    pub conversations: HashMap<String, Vec<Shown>>,
    /// Settings: the provider being edited and its fields.
    pub edit: Option<ProviderKind>,
    pub key_input: String,
    pub model_input: String,
    pub url_input: String,
    pub models: Vec<String>,
    pub status: Option<Result<String, String>>,
    pub testing: bool,
    /// The provider is switched on once its settings are saved.
    pub enable_after: bool,
    pub show_key: bool,
    /// « Choisir un modèle »: the search and the filter.
    pub model_search: String,
    pub model_filter: ModelFilter,
    /// The system prompt being edited: `Some(None)` for a new one.
    pub prompt_edit: Option<Option<String>>,
    pub prompt_name: String,
    pub prompt_text: iced::widget::text_editor::Content,
    pub prompt_confirm_delete: bool,
}

/// The filters of « Choisir un modèle » (Android's `ModelFilter`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ModelFilter {
    #[default]
    Text,
    Fast,
    Strong,
    Reasoning,
    All,
}

impl ModelFilter {
    pub const ALL: [ModelFilter; 5] = [
        ModelFilter::Text,
        ModelFilter::Fast,
        ModelFilter::Strong,
        ModelFilter::Reasoning,
        ModelFilter::All,
    ];

    pub fn label(self) -> String {
        tr(match self {
            ModelFilter::Text => "Texte",
            ModelFilter::Fast => "Rapides",
            ModelFilter::Strong => "Puissants",
            ModelFilter::Reasoning => "Raisonnement",
            ModelFilter::All => "Tous",
        })
    }

    /// Whether a model id belongs here (the same words as Android's regular expressions).
    pub fn keep(self, id: &str) -> bool {
        let m = id.to_lowercase();
        if self == ModelFilter::All {
            return true;
        }
        let non_chat = [
            "embed",
            "tts",
            "whisper",
            "dall-e",
            "dalle",
            "moderation",
            "transcri",
            "image",
            "audio",
            "realtime",
            "search",
            "computer-use",
            "davinci",
            "babbage",
            "imagen",
            "veo",
            "aqa",
            "rerank",
            "ocr",
            "guard",
        ];
        if non_chat.iter().any(|w| m.contains(w)) {
            return false;
        }
        let small_b = |m: &str| (1..=9).any(|n| m.contains(&format!("-{n}b")) || m.ends_with(&format!(":{n}b")));
        match self {
            ModelFilter::Fast => {
                ["mini", "nano", "flash", "haiku", "lite", "small", "tiny", "instant", "fast"]
                    .iter()
                    .any(|w| m.contains(w))
                    || small_b(&m)
            }
            ModelFilter::Strong => {
                ["opus", "sonnet", "-pro", "large", "ultra", "70b", "405b"]
                    .iter()
                    .any(|w| m.contains(w))
                    || m.ends_with("pro")
                    || m.ends_with("max")
                    || m == "gpt-5"
                    || m.starts_with("gpt-5.") && !m.contains('-')
                    || m.starts_with("grok-4") && !m.contains("fast") && !m.contains("mini")
            }
            ModelFilter::Reasoning => {
                (m.starts_with('o') && m.chars().nth(1).is_some_and(|c| c.is_ascii_digit()))
                    || ["reason", "think", "r1", "qwq", "magistral"].iter().any(|w| m.contains(w))
            }
            ModelFilter::Text | ModelFilter::All => true,
        }
    }
}

#[derive(Debug, Clone)]
pub enum AiMsg {
    Toggle,
    Input(String),
    Screen(bool),
    Attach,
    Attached(Option<(String, Vec<u8>)>),
    RemoveFile(usize),
    Send,
    Answer(String, Result<Turn, String>),
    Insert(String),
    Copy(String),
    Clear,
    OpenLink(String),
    // Settings.
    Provider(ProviderKind),
    Key(String),
    Model(String),
    Url(String),
    Save,
    LoadModels,
    Models(Result<Vec<String>, String>),
    PickModel(String),
    Test,
    Tested(Result<String, String>),
    ForgetKey,
    /// The switch of a provider in the settings.
    Enable(ProviderKind, bool),
    /// The provider used now (from the assistant's header); it becomes the first one.
    Use(ProviderKind),
    ShowKey,
    OpenModels,
    ModelSearch(String),
    Filter(ModelFilter),
    /// A model chosen in the list (or typed): back to the provider.
    UseModel(String),
    /// The system prompt in use (`None`: the default one).
    PromptPick(Option<String>),
    /// Opens a prompt to edit (`None`: a new one).
    PromptOpen(Option<String>),
    PromptName(String),
    PromptText(iced::widget::text_editor::Action),
    PromptFromDefault,
    PromptSave,
    PromptDelete,
    Lines(usize),
}

fn file_for(dir: &std::path::Path, key: &str) -> PathBuf {
    let h = hex::encode(sha2::Sha256::digest(key.as_bytes()));
    dir.join(format!("{}.json", &h[..24]))
}

impl App {
    fn ai_dir(&self) -> PathBuf {
        self.paths.data.join("ai-conversations")
    }

    /// Whether [k] has what it needs (its key, when it needs one).
    pub fn ai_configured(&self, k: ProviderKind) -> bool {
        !k.needs_key() || self.ai_config(k).api_key.is_some()
    }

    /// The providers switched on and ready, the one used last first.
    pub fn ai_ready_providers(&self) -> Vec<ProviderKind> {
        let mut v: Vec<ProviderKind> = self
            .settings
            .ai_enabled
            .iter()
            .copied()
            .filter(|k| self.ai_configured(*k))
            .collect();
        if let Some(i) = self.settings.ai_provider.and_then(|p| v.iter().position(|k| *k == p)) {
            let k = v.remove(i);
            v.insert(0, k);
        }
        v
    }

    /// The provider the assistant uses: the last one used if still on, else the first ready one.
    pub fn ai_provider(&self) -> ProviderKind {
        self.ai_ready_providers()
            .first()
            .copied()
            .or(self.settings.ai_provider)
            .unwrap_or(ProviderKind::Claude)
    }

    /// The system prompt in use: the user's chosen one, else the default.
    pub fn ai_system_prompt(&self) -> String {
        let s = &self.settings;
        s.ai_prompt_id
            .as_ref()
            .and_then(|id| s.ai_prompts.iter().find(|p| &p.id == id))
            .map(|p| p.text.clone())
            .or_else(|| s.ai_prompt.clone())
            .filter(|p| !p.trim().is_empty())
            .unwrap_or_else(|| ai::default_prompt().to_owned())
    }

    pub fn ai_config(&self, kind: ProviderKind) -> Config {
        let k = format!("{kind:?}").to_uppercase();
        Config {
            kind,
            api_key: self
                .db
                .as_ref()
                .and_then(|db| db.secret(&format!("ai-key-{k}")))
                .filter(|s| !s.is_empty()),
            model: self
                .settings
                .ai_models
                .get(&k)
                .cloned()
                .filter(|m| !m.is_empty())
                .unwrap_or_else(|| kind.default_model().to_owned()),
            base_url: self
                .settings
                .ai_urls
                .get(&k)
                .cloned()
                .filter(|u| !u.is_empty())
                .or_else(|| kind.default_url().map(str::to_owned)),
        }
    }

    pub fn ai_ready(&self) -> bool {
        let c = self.ai_config(self.ai_provider());
        (!c.kind.needs_key() || c.api_key.is_some()) && !c.model.is_empty()
    }

    /// The conversation of the session shown: its saved host, or this computer.
    pub fn ai_key(&self) -> Option<String> {
        let t = self.active_tab.and_then(|id| self.tab(id))?;
        Some(match t.host_id {
            Some(h) => format!("host:{h}"),
            None => "local".into(),
        })
    }

    pub fn ai_conversation(&self) -> &[Shown] {
        self.ai_key()
            .and_then(|k| self.assistant.conversations.get(&k))
            .map(Vec::as_slice)
            .unwrap_or_default()
    }

    fn ai_load(&mut self, key: &str) {
        if self.assistant.conversations.contains_key(key) {
            return;
        }
        let turns: Vec<Turn> = std::fs::read_to_string(file_for(&self.ai_dir(), key))
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        self.assistant
            .conversations
            .insert(key.to_owned(), turns.into_iter().map(Shown::new).collect());
    }

    fn ai_store(&self, key: &str) {
        let Some(c) = self.assistant.conversations.get(key) else { return };
        let turns: Vec<&Turn> = c.iter().map(|s| &s.turn).collect();
        let dir = self.ai_dir();
        let _ = std::fs::create_dir_all(&dir);
        if let Ok(json) = serde_json::to_string(&turns) {
            let _ = std::fs::write(file_for(&dir, key), json);
        }
    }

    /// « mrd@docker-01 (10.0.0.20, SSH) » for the request.
    fn ai_machine(&self) -> String {
        let Some(t) = self.active_tab.and_then(|id| self.tab(id)) else {
            return String::new();
        };
        match t.host_id.and_then(|h| self.hosts.iter().find(|x| x.id == h)) {
            Some(h) => format!("{} ({}, {})", h.alias, h.address(), crate::app::protocol_label(h.protocol)),
            None => format!("{} ({})", tr("cet ordinateur"), std::env::consts::OS),
        }
    }

    pub fn ai_update(&mut self, m: AiMsg) -> Task<Message> {
        let st = &mut self.assistant;
        match m {
            AiMsg::Toggle => {
                st.open = !st.open;
                if st.open {
                    st.screen = true;
                    if let Some(k) = self.ai_key() {
                        self.ai_load(&k);
                    }
                    return iced::widget::operation::focus("ai-input");
                }
            }
            AiMsg::Input(v) => st.input = v,
            AiMsg::Screen(v) => st.screen = v,
            AiMsg::Attach => {
                return Task::perform(
                    async {
                        let f = rfd::AsyncFileDialog::new().set_title(tr("Joindre un fichier")).pick_file().await?;
                        Some((f.file_name(), f.read().await))
                    },
                    |r| Message::Ai(AiMsg::Attached(r)),
                )
            }
            AiMsg::Attached(Some((name, bytes))) => match ai::read_file(&name, &bytes) {
                Ok(f) => st.files.push(f),
                Err(e) => st.error = Some(e),
            },
            AiMsg::Attached(None) => {}
            AiMsg::RemoveFile(i) => {
                if i < st.files.len() {
                    st.files.remove(i);
                }
            }
            AiMsg::Clear => {
                if let Some(k) = self.ai_key() {
                    self.assistant.conversations.insert(k.clone(), Vec::new());
                    let _ = std::fs::remove_file(file_for(&self.ai_dir(), &k));
                }
            }
            AiMsg::Send => {
                let request = st.input.trim().to_owned();
                if request.is_empty() || st.busy {
                    return Task::none();
                }
                if !self.ai_ready() {
                    self.assistant.error = Some(tr("Configurez l'assistant dans Réglages › Assistant IA"));
                    return Task::none();
                }
                let Some(key) = self.ai_key() else { return Task::none() };
                self.ai_load(&key);
                let screen = self.assistant.screen.then(|| {
                    let text = self
                        .active_tab
                        .and_then(|id| self.tab(id))
                        .map(|t| t.term.screen_text())
                        .unwrap_or_default();
                    let lines: Vec<&str> = text.lines().collect();
                    lines[lines.len().saturating_sub(self.settings.ai_lines())..].join("\n")
                });
                let files = std::mem::take(&mut self.assistant.files);
                let user = ai::user_message(&request, &self.ai_machine(), screen.as_deref(), &files);
                let history: Vec<&Turn> = self.ai_conversation().iter().map(|s| &s.turn).collect();
                let start = history.len().saturating_sub(MAX_CONTEXT_TURNS);
                let history = &history[start..];
                let mut messages = Vec::new();
                for (i, t) in history.iter().enumerate() {
                    let recent = i + 2 >= history.len();
                    messages.push(AiMessage {
                        from_user: true,
                        text: if recent { t.sent.clone() } else { t.request.clone() },
                    });
                    messages.push(AiMessage {
                        from_user: false,
                        text: t.raw.clone(),
                    });
                }
                messages.push(AiMessage {
                    from_user: true,
                    text: user.clone(),
                });
                let config = self.ai_config(self.ai_provider());
                let system = self.ai_system_prompt();
                let turn = Turn {
                    request,
                    sent: user.chars().take(MAX_SENT_CHARS).collect(),
                    files: files.iter().map(|f| f.name.clone()).collect(),
                    screen: screen.is_some(),
                    provider: config.kind,
                    model: config.model.clone(),
                    raw: String::new(),
                    at: now_ms(),
                };
                self.assistant.busy = true;
                self.assistant.error = None;
                self.assistant.input.clear();
                return Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || ai::chat(&config, &system, &messages))
                            .await
                            .map_err(|e| e.to_string())
                            .and_then(|r| r)
                            .map(|raw| Turn {
                                raw: raw.trim().to_owned(),
                                ..turn
                            })
                    },
                    move |r| Message::Ai(AiMsg::Answer(key.clone(), r)),
                );
            }
            AiMsg::Answer(key, r) => {
                st.busy = false;
                match r {
                    Ok(turn) => {
                        let c = st.conversations.entry(key.clone()).or_default();
                        c.push(Shown::new(turn));
                        let extra = c.len().saturating_sub(MAX_KEPT_TURNS);
                        c.drain(..extra);
                        self.ai_store(&key);
                    }
                    Err(e) => st.error = Some(e),
                }
            }
            AiMsg::Insert(cmd) => {
                // Typed but not run: the user checks it, then presses Enter.
                let text = cmd.trim_end_matches('\n').replace("\r\n", "\n").replace('\n', "\r");
                if let Some(id) = self.active_tab {
                    self.on_term(id, crate::term::widget::Action::Input(text.into_bytes()));
                }
            }
            AiMsg::Copy(cmd) => {
                let toast = self.say(tr("Commande copiée"));
                return Task::batch([iced::clipboard::write(cmd), toast]);
            }
            AiMsg::OpenLink(url) => open_url(&url),
            AiMsg::Provider(k) => {
                self.ai_edit(k);
                self.assistant.enable_after = false;
                self.modal = Some(crate::app::Modal::AiProvider(k));
            }
            AiMsg::Enable(k, on) => {
                if !on {
                    self.settings.ai_enabled.retain(|x| *x != k);
                    self.save_settings();
                } else if self.ai_configured(k) {
                    if !self.settings.ai_enabled.contains(&k) {
                        self.settings.ai_enabled.push(k);
                    }
                    self.save_settings();
                } else {
                    // Its key first, then it is switched on.
                    self.ai_edit(k);
                    self.assistant.enable_after = true;
                    self.modal = Some(crate::app::Modal::AiProvider(k));
                }
            }
            AiMsg::Use(k) => {
                self.settings.ai_provider = Some(k);
                self.save_settings();
            }
            AiMsg::ShowKey => st.show_key = !st.show_key,
            AiMsg::OpenModels => {
                let k = st.edit.unwrap_or(ProviderKind::Claude);
                st.model_search.clear();
                self.modal = Some(crate::app::Modal::AiModels(k));
                if self.assistant.models.is_empty() {
                    return self.update(Message::Ai(AiMsg::LoadModels));
                }
            }
            AiMsg::ModelSearch(v) => st.model_search = v,
            AiMsg::Filter(f) => st.model_filter = f,
            AiMsg::UseModel(m) => {
                st.model_input = m;
                if let Some(k) = st.edit {
                    self.modal = Some(crate::app::Modal::AiProvider(k));
                }
            }
            AiMsg::PromptPick(id) => {
                self.settings.ai_prompt_id = id;
                self.save_settings();
            }
            AiMsg::PromptOpen(id) => {
                let found = id
                    .as_ref()
                    .and_then(|i| self.settings.ai_prompts.iter().find(|p| &p.id == i))
                    .cloned();
                let st = &mut self.assistant;
                st.prompt_confirm_delete = false;
                match found {
                    Some(p) => {
                        st.prompt_name = p.name;
                        st.prompt_text = iced::widget::text_editor::Content::with_text(&p.text);
                        st.prompt_edit = Some(Some(p.id));
                    }
                    None => {
                        st.prompt_name.clear();
                        st.prompt_text = iced::widget::text_editor::Content::new();
                        st.prompt_edit = Some(None);
                    }
                }
                self.modal = Some(crate::app::Modal::AiPrompt);
            }
            AiMsg::PromptName(v) => st.prompt_name = v,
            AiMsg::PromptText(a) => st.prompt_text.perform(a),
            AiMsg::PromptFromDefault => st.prompt_text = iced::widget::text_editor::Content::with_text(ai::default_prompt()),
            AiMsg::PromptSave => {
                let name = st.prompt_name.trim().to_owned();
                let text = st.prompt_text.text().trim().to_owned();
                if name.is_empty() || text.is_empty() {
                    return Task::none();
                }
                let id = match st.prompt_edit.clone().flatten() {
                    Some(id) => id,
                    None => hex::encode(rand::random::<[u8; 8]>()),
                };
                let prompts = &mut self.settings.ai_prompts;
                match prompts.iter_mut().find(|p| p.id == id) {
                    Some(p) => {
                        p.name = name;
                        p.text = text;
                    }
                    None => prompts.push(crate::storage::AiPrompt {
                        id: id.clone(),
                        name,
                        text,
                    }),
                }
                self.settings.ai_prompt_id = Some(id);
                self.save_settings();
                self.assistant.prompt_edit = None;
                self.modal = None;
            }
            AiMsg::PromptDelete => {
                let Some(Some(id)) = st.prompt_edit.clone() else {
                    return Task::none();
                };
                if !st.prompt_confirm_delete {
                    st.prompt_confirm_delete = true;
                    return Task::none();
                }
                self.settings.ai_prompts.retain(|p| p.id != id);
                if self.settings.ai_prompt_id.as_ref() == Some(&id) {
                    self.settings.ai_prompt_id = None;
                }
                self.save_settings();
                self.assistant.prompt_edit = None;
                self.modal = None;
            }
            AiMsg::Lines(n) => {
                self.settings.ai_lines = Some(n);
                self.save_settings();
            }
            AiMsg::Key(v) => st.key_input = v,
            AiMsg::Model(v) => st.model_input = v,
            AiMsg::Url(v) => st.url_input = v,
            AiMsg::PickModel(v) => st.model_input = v,
            AiMsg::Save => {
                let k = st.edit.unwrap_or(ProviderKind::Claude);
                let name = format!("{k:?}").to_uppercase();
                let st = &mut self.assistant;
                if !st.key_input.trim().is_empty() {
                    if let Some(db) = &self.db {
                        let _ = db.set_secret(&format!("ai-key-{name}"), Some(st.key_input.trim()));
                    }
                    st.key_input.clear();
                }
                self.settings.ai_models.insert(name.clone(), st.model_input.trim().to_owned());
                if k.editable_url() {
                    self.settings.ai_urls.insert(name, st.url_input.trim().to_owned());
                }
                if (self.assistant.enable_after || self.settings.ai_enabled.is_empty())
                    && self.ai_configured(k)
                    && !self.settings.ai_enabled.contains(&k)
                {
                    self.settings.ai_enabled.push(k);
                }
                self.assistant.enable_after = false;
                self.save_settings();
                self.modal = None;
                return self.say(tr("Assistant enregistré"));
            }
            AiMsg::ForgetKey => {
                let k = st.edit.unwrap_or(ProviderKind::Claude);
                let name = format!("{k:?}").to_uppercase();
                self.settings.ai_enabled.retain(|x| *x != k);
                self.save_settings();
                if let Some(db) = &self.db {
                    let _ = db.set_secret(&format!("ai-key-{name}"), None);
                }
            }
            AiMsg::LoadModels => {
                let c = self.ai_config_edited();
                return Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || ai::models(&c))
                            .await
                            .map_err(|e| e.to_string())
                            .and_then(|r| r)
                    },
                    |r| Message::Ai(AiMsg::Models(r)),
                );
            }
            AiMsg::Models(r) => match r {
                Ok(list) => {
                    st.status = Some(Ok(trf("%1$s modèle(s)", &[&list.len()])));
                    st.models = list;
                }
                Err(e) => st.status = Some(Err(e)),
            },
            AiMsg::Test => {
                let c = self.ai_config_edited();
                self.assistant.testing = true;
                return Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            ai::chat(
                                &c,
                                &tr("Réponds uniquement par le mot : OK"),
                                &[AiMessage {
                                    from_user: true,
                                    text: tr("Test de connexion"),
                                }],
                            )
                        })
                        .await
                        .map_err(|e| e.to_string())
                        .and_then(|r| r)
                    },
                    |r| Message::Ai(AiMsg::Tested(r)),
                );
            }
            AiMsg::Tested(r) => {
                st.testing = false;
                st.status = Some(r.map(|s| s.trim().chars().take(80).collect()));
            }
        }
        Task::none()
    }

    /// The configuration as typed in the settings (not saved yet).
    fn ai_config_edited(&self) -> Config {
        let st = &self.assistant;
        let mut c = self.ai_config(st.edit.unwrap_or_else(|| self.ai_provider()));
        if !st.key_input.trim().is_empty() {
            c.api_key = Some(st.key_input.trim().to_owned());
        }
        if !st.model_input.trim().is_empty() {
            c.model = st.model_input.trim().to_owned();
        }
        if c.kind.editable_url() && !st.url_input.trim().is_empty() {
            c.base_url = Some(st.url_input.trim().to_owned());
        }
        c
    }

    /// Fills the settings' fields with what is saved for [k].
    pub fn ai_edit(&mut self, k: ProviderKind) {
        let c = self.ai_config(k);
        let st = &mut self.assistant;
        st.edit = Some(k);
        st.key_input.clear();
        st.model_input = c.model;
        st.url_input = c.base_url.unwrap_or_default();
        st.models.clear();
        st.status = None;
    }
}

fn open_url(url: &str) {
    if !(url.starts_with("http://") || url.starts_with("https://")) {
        return;
    }
    #[cfg(windows)]
    let r = std::process::Command::new("rundll32")
        .args(["url.dll,FileProtocolHandler", url])
        .spawn();
    #[cfg(target_os = "macos")]
    let r = std::process::Command::new("open").arg(url).spawn();
    #[cfg(all(unix, not(target_os = "macos")))]
    let r = std::process::Command::new("xdg-open").arg(url).spawn();
    let _ = r;
}
