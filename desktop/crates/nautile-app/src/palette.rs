//! « Ctrl+K »: one search over the hosts, snippets, open tabs, settings pages and actions, run from the keyboard.

use nautile_core::{tr, trf};

use crate::app::{App, Message, Page, SettingsPage};

#[derive(Debug, Default)]
pub struct PaletteState {
    pub query: String,
    pub selected: usize,
}

/// One result: its icon, title, detail, shortcut and what it does.
pub struct Item {
    pub icon: char,
    pub title: String,
    pub detail: String,
    pub keys: Option<&'static str>,
    pub run: Message,
}

fn fold(s: &str) -> String {
    s.to_lowercase()
        .chars()
        .map(|c| match c {
            'à' | 'â' | 'ä' => 'a',
            'é' | 'è' | 'ê' | 'ë' => 'e',
            'î' | 'ï' => 'i',
            'ô' | 'ö' => 'o',
            'ù' | 'û' | 'ü' => 'u',
            'ç' => 'c',
            c => c,
        })
        .collect()
}

/// How well [query] matches [text]: its letters in order (higher is better, `None`: no match). Whole words and
/// a match at the start count more.
pub fn score(query: &str, text: &str) -> Option<i32> {
    let (q, t) = (fold(query), fold(text));
    if q.is_empty() {
        return Some(0);
    }
    if let Some(i) = t.find(&q) {
        return Some(1000 - i as i32 + if i == 0 { 500 } else { 0 });
    }
    let mut score = 0;
    let mut last = 0usize;
    let mut chars = t.char_indices().peekable();
    for qc in q.chars() {
        loop {
            let (i, tc) = chars.next()?;
            if tc == qc {
                score += if i == last + 1 { 8 } else { 1 };
                last = i;
                break;
            }
        }
    }
    Some(score)
}

const I_HOST: char = '\u{E875}';
const I_TERMINAL: char = '\u{EB8E}';
const I_SETTINGS: char = '\u{E429}';
const I_ADD: char = '\u{E145}';
const I_LOCK: char = '\u{E897}';
const I_SYNC: char = '\u{E627}';
const I_TAB: char = '\u{E8D8}';
const I_PLAY: char = '\u{E037}';
const I_PAGE: char = '\u{E5CC}';
const I_UPDATE: char = '\u{E62A}';
const I_IMPORT: char = '\u{EAF3}';

/// Everything that can be found, in its default order.
pub fn items(app: &App) -> Vec<Item> {
    let mut v = vec![
        Item {
            icon: I_ADD,
            title: tr("Nouvel hôte"),
            detail: tr("Action"),
            keys: Some("Ctrl+N"),
            run: Message::NewHost,
        },
        Item {
            icon: I_TERMINAL,
            title: tr("Nouveau terminal local"),
            detail: tr("Action"),
            keys: Some("Ctrl+Maj+T"),
            run: Message::OpenLocal,
        },
        Item {
            icon: I_IMPORT,
            title: tr("Importer des hôtes"),
            detail: tr("~/.ssh/config ou CSV"),
            keys: None,
            run: Message::ImportHostsPick,
        },
    ];
    if app.settings.locks() {
        v.push(Item {
            icon: I_LOCK,
            title: tr("Verrouiller"),
            detail: tr("Action"),
            keys: None,
            run: Message::LockNow,
        });
    }
    if app.settings.sync_enabled {
        v.push(Item {
            icon: I_SYNC,
            title: tr("Synchroniser maintenant"),
            detail: tr("Action"),
            keys: None,
            run: Message::Backup(crate::backup::BackupMsg::SyncNow),
        });
    }
    v.push(Item {
        icon: I_UPDATE,
        title: tr("Vérifier les mises à jour"),
        detail: tr("Action"),
        keys: None,
        run: Message::Update(crate::update::UpdateMsg::Check(true)),
    });
    for t in &app.tabs {
        v.push(Item {
            icon: I_TAB,
            title: t.name.clone(),
            detail: tr("Onglet ouvert"),
            keys: None,
            run: Message::SelectTab(Some(t.id)),
        });
    }
    for h in &app.hosts {
        v.push(Item {
            icon: I_HOST,
            title: h.alias.clone(),
            detail: trf("Se connecter · %1$s", &[&h.address()]),
            keys: None,
            run: Message::Connect(h.id),
        });
    }
    for s in &app.snippets {
        v.push(Item {
            icon: I_PLAY,
            title: s.name.clone(),
            detail: trf("Lancer le snippet · %1$s", &[&s.command.lines().next().unwrap_or_default()]),
            keys: None,
            run: Message::RunSnippet(s.id),
        });
    }
    for (p, name) in [
        (Page::Hosts, tr("Hôtes")),
        (Page::Connections, tr("Connexions")),
        (Page::Snippets, tr("Snippets")),
        (Page::Keys, tr("Trousseau")),
        (Page::Tunnels, tr("Tunnels")),
    ] {
        v.push(Item {
            icon: I_PAGE,
            title: name,
            detail: tr("Page"),
            keys: None,
            run: Message::Nav(p),
        });
    }
    v.push(Item {
        icon: I_SETTINGS,
        title: tr("Réglages"),
        detail: tr("Page"),
        keys: Some("Ctrl+,"),
        run: Message::Nav(Page::Settings),
    });
    for (p, name) in [
        (SettingsPage::Appearance, tr("Apparence")),
        (SettingsPage::Terminal, tr("Terminal")),
        (SettingsPage::Remote, tr("Bureau à distance")),
        (SettingsPage::Ai, tr("Assistant IA")),
        (SettingsPage::Backup, tr("Sauvegarde et synchro")),
        (SettingsPage::Security, tr("Sécurité")),
        (SettingsPage::System, tr("Fenêtre et démarrage")),
        (SettingsPage::Data, tr("Données")),
        (SettingsPage::Logs, tr("Journaux de session")),
    ] {
        v.push(Item {
            icon: I_SETTINGS,
            title: name,
            detail: tr("Réglages"),
            keys: None,
            run: Message::Batch(vec![Message::Nav(Page::Settings), Message::SettingsPage(Some(p))]),
        });
    }
    v
}

/// The results for what is typed, best first (ten at most).
pub fn results(app: &App, query: &str) -> Vec<Item> {
    let mut scored: Vec<(i32, usize, Item)> = items(app)
        .into_iter()
        .enumerate()
        .filter_map(|(i, it)| {
            let s = score(query, &it.title).map(|s| s + 100).or_else(|| score(query, &it.detail))?;
            Some((s, i, it))
        })
        .collect();
    if !query.trim().is_empty() {
        scored.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    }
    scored.into_iter().take(10).map(|(_, _, it)| it).collect()
}

#[cfg(test)]
mod tests {
    use super::score;

    #[test]
    fn letters_in_order_match_and_words_count_more() {
        assert!(score("dock", "docker-01").unwrap() > score("dock", "my docker").unwrap());
        assert!(score("dkr", "docker").is_some());
        assert!(score("xyz", "docker").is_none());
        assert!(score("reg", "Réglages").is_some(), "accents are ignored");
    }
}
