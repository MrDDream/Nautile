//! The settings: their home and sub-pages.

use super::*;
#[allow(unused_imports)]
use iced::widget::{button, column, container, image, pick_list, row, scrollable, stack, text, text_editor, text_input, Column, Space};

/// A settings section: its uppercase label, then its card.
pub(super) fn settings_card<'a>(name: String, body: impl Into<Element<'a, Message>>) -> Element<'a, Message> {
    column![
        container(kit::section_label(name)).padding([0, 4]),
        container(body.into()).padding(18).width(Fill).style(theme::card),
    ]
    .spacing(10)
    .into()
}

/// A settings page under its header, scrolled, at a readable width.
pub(super) fn settings_frame<'a>(head: Element<'a, Message>, body: Column<'a, Message>) -> Element<'a, Message> {
    column![
        head,
        scrollable(container(body.spacing(18).max_width(760)).padding(Padding {
            top: 4.0,
            right: 28.0,
            bottom: 28.0,
            left: 28.0
        }))
        .height(Fill),
    ]
    .into()
}

/// How a protection stands: done, to do, or simply off.
#[derive(Clone, Copy, PartialEq)]
pub(super) enum Standing {
    Ok,
    Todo,
    Off,
}

pub(super) fn standing_color(n: &theme::Nt, s: Standing) -> Color {
    match s {
        Standing::Ok => n.green,
        Standing::Todo => n.accent,
        Standing::Off => n.muted,
    }
}

/// One status of the protection card: its icon, what it is, how it stands.
pub(super) fn status_tile<'a>(c: char, name: String, value: String, s: Standing) -> Element<'a, Message> {
    container(
        column![
            kit::icon(c, 18.0).style(move |t: &iced::Theme| text::Style {
                color: Some(standing_color(&theme::nt(t), s)),
            }),
            text(name).size(11).style(|t: &iced::Theme| text::Style {
                color: Some(theme::nt(t).muted),
            }),
            text(value).size(13).font(BOLD).style(move |t: &iced::Theme| {
                let n = theme::nt(t);
                text::Style {
                    color: Some(if s == Standing::Off { n.text_soft } else { standing_color(&n, s) }),
                }
            }),
        ]
        .spacing(4)
        .align_x(Alignment::Center),
    )
    .padding([10, 4])
    .width(Fill)
    .center_x(Fill)
    .style(move |t: &iced::Theme| container::Style {
        background: Some(theme::alpha(standing_color(&theme::nt(t), s), 0.10).into()),
        border: iced::border::rounded(10),
        ..Default::default()
    })
    .into()
}

/// « 5 min », « 3 h », « 2 j »: how long ago, in few letters.
pub(super) fn short_ago(at: i64) -> String {
    let min = ((nautile_core::model::now_ms() - at) / 60_000).max(0);
    match min {
        m if m < 1 => tr("à l'instant"),
        m if m < 60 => trf("%1$s min", &[&m]),
        m if m < 60 * 24 => trf("%1$s h", &[&(m / 60)]),
        m => trf("%1$s j", &[&(m / (60 * 24))]),
    }
}

/// When the data was last saved somewhere: the latest backup, or the latest synchronisation.
pub(super) fn last_saved(app: &App) -> Option<i64> {
    let s = &app.settings;
    let backup = s.targets.iter().filter_map(|t| t.last).max();
    let sync = if s.sync_enabled { s.last_sync } else { None };
    backup.max(sync)
}

/// What protects the data, and how many things are left to do (Android's `ProtectionCard`).
pub(super) fn protection_card(app: &App) -> Element<'_, Message> {
    let s = &app.settings;
    let lock_on = s.locks();
    let sync_on = s.sync_enabled || s.lan_enabled;
    let last = last_saved(app);
    let stale = last.is_none_or(|l| nautile_core::model::now_ms() - l > 30 * 24 * 3600 * 1000);
    let todo = [!lock_on, stale].iter().filter(|b| **b).count();
    let mut head = row![
        kit::icon(I_SHIELD, 18.0).style(move |t: &iced::Theme| {
            let n = theme::nt(t);
            text::Style {
                color: Some(if todo > 0 { n.accent } else { n.green }),
            }
        }),
        text(tr("Vos données restent sur l'appareil")).size(14).font(BOLD).width(Fill),
    ]
    .spacing(8)
    .align_y(Alignment::Center);
    if todo > 0 {
        head = head.push(
            text(if todo == 1 {
                tr("1 à faire")
            } else {
                trf("%1$s à faire", &[&todo])
            })
            .size(12)
            .font(BOLD)
            .style(|t: &iced::Theme| text::Style {
                color: Some(theme::nt(t).accent),
            }),
        );
    }
    let tiles = row![
        status_tile(I_LOCK, tr("Base"), tr("Chiffrée"), Standing::Ok),
        status_tile(
            I_FINGERPRINT,
            tr("Verrou"),
            if lock_on { tr("Actif") } else { tr("Inactif") },
            if lock_on { Standing::Ok } else { Standing::Todo }
        ),
        status_tile(
            I_CLOUD_SYNC,
            tr("Synchro"),
            if sync_on { tr("Active") } else { tr("Inactive") },
            if sync_on { Standing::Ok } else { Standing::Off }
        ),
        status_tile(
            I_BACKUP,
            tr("Sauvegarde"),
            last.map(short_ago).unwrap_or_else(|| tr("Jamais")),
            if stale { Standing::Todo } else { Standing::Ok }
        ),
    ]
    .spacing(6);
    container(column![head, tiles].spacing(12))
        .padding([12, 14])
        .width(Fill)
        .style(move |t: &iced::Theme| {
            let n = theme::nt(t);
            container::Style {
                background: Some(n.surface.into()),
                border: iced::Border {
                    color: if todo > 0 { theme::alpha(n.accent, 0.45) } else { n.border },
                    width: 1.0,
                    radius: 16.0.into(),
                },
                ..Default::default()
            }
        })
        .into()
}

/// One row of the settings home, and the words it is found by.
pub(super) struct Entry {
    icon: char,
    tint: Color,
    title: String,
    detail: String,
    words: &'static str,
    dot: Option<Color>,
    page: crate::app::SettingsPage,
}

pub(super) fn fold(s: &str) -> String {
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

pub(super) fn entry_card<'a>(entries: Vec<Entry>) -> Element<'a, Message> {
    kit::card(
        entries
            .into_iter()
            .map(|e| {
                kit::nav_row(
                    e.icon,
                    e.tint,
                    e.title,
                    e.detail,
                    e.dot.map(|c| kit::dot(c, 8.0)),
                    Message::SettingsPage(Some(e.page)),
                )
            })
            .collect(),
    )
}

/// The settings' home: what protects the data, then one row per sub-page (as on Android).
pub(super) fn settings_home(app: &App) -> Element<'_, Message> {
    use crate::app::SettingsPage as P;
    let s = &app.settings;
    let n = theme::nt(&app.theme());
    let lavender = if n.dark {
        Color::from_rgb8(0xB3, 0x9D, 0xFF)
    } else {
        Color::from_rgb8(0x5B, 0x3F, 0xC4)
    };
    let teal = if n.dark {
        Color::from_rgb8(0x3D, 0xD6, 0xC6)
    } else {
        Color::from_rgb8(0x0F, 0x76, 0x6E)
    };

    let language = match s.language {
        Language::System => tr("Système"),
        Language::Fr => "Français".into(),
        Language::En => "English".into(),
    };
    let look = format!("{} · {}", appearance_label(s.appearance), s.accent.label());
    let term = themes::by_id(s.terminal_theme());
    let personal = vec![
        Entry {
            icon: I_PALETTE,
            tint: n.accent,
            title: tr("Apparence"),
            detail: format!("{look} · {language}"),
            words: "theme sombre clair dark light couleur color langue language",
            dot: None,
            page: P::Appearance,
        },
        Entry {
            icon: I_DESKTOP,
            tint: teal,
            title: tr("Bureau à distance"),
            detail: format!(
                "{} · {}",
                if s.remote_no_upscale {
                    tr("taille réelle")
                } else {
                    tr("ajusté à la fenêtre")
                },
                if s.rdp_window_size() {
                    tr("RDP à la taille de la fenêtre")
                } else {
                    "RDP 1600×900".into()
                }
            ),
            words: "rdp vnc bureau a distance remote desktop presse-papier clipboard plein ecran fullscreen taille resolution",
            dot: None,
            page: P::Remote,
        },
        Entry {
            icon: I_TERMINAL,
            tint: n.blue,
            title: tr("Terminal"),
            detail: format!("{} {} · {}", tr(s.terminal_family().name), s.terminal_font(), tr(term.name)),
            words: "police font taille size couleurs colors raccourcis shortcuts clavier keyboard copier coller",
            dot: None,
            page: P::Terminal,
        },
    ];

    let kind = app.ai_provider();
    let ai_ready = s.ai_provider.is_some() && (!kind.needs_key() || app.ai_config(kind).api_key.is_some());
    let backup_detail = if s.sync_enabled {
        s.last_sync
            .map(|l| trf("Synchro %1$s", &[&kit::relative_time(l, nautile_core::model::now_ms())]))
            .unwrap_or_else(|| tr("Synchro activée"))
    } else if s.targets.iter().any(|t| t.auto) {
        tr("Sauvegarde automatique")
    } else {
        last_saved(app)
            .map(|l| trf("Sauvegarde %1$s", &[&kit::relative_time(l, nautile_core::model::now_ms())]))
            .unwrap_or_else(|| tr("Jamais sauvegardé"))
    };
    let services = vec![
        Entry {
            icon: I_SPARKLE,
            tint: lavender,
            title: tr("Assistant IA"),
            detail: if ai_ready {
                format!("{} · {}", kind.label(), app.ai_config(kind).model)
            } else {
                tr("À configurer")
            },
            words: "ia ai intelligence claude anthropic openai gemini mistral deepseek ollama cle key api modele model",
            dot: Some(if ai_ready { n.green } else { n.accent }),
            page: P::Ai,
        },
        Entry {
            icon: I_CLOUD_SYNC,
            tint: n.blue,
            title: tr("Sauvegarde et synchro"),
            detail: backup_detail,
            words: "sauvegarde backup export import drive onedrive nextcloud webdav s3 cloud restaurer restore synchronisation sync appareils devices wi-fi wifi",
            dot: None,
            page: P::Backup,
        },
        Entry {
            icon: I_LOCK,
            tint: n.green,
            title: tr("Sécurité"),
            detail: if s.locks() { tr("Verrouillage activé") } else { tr("Verrouillage désactivé") },
            words: "securite security pin code mot de passe password verrou lock inactivite",
            dot: None,
            page: P::Security,
        },
        Entry {
            icon: I_DESKTOP,
            tint: n.blue,
            title: tr("Fenêtre et démarrage"),
            detail: if s.close_to_tray() {
                tr("Reste dans la zone de notification")
            } else {
                tr("Fermer quitte Nautile")
            },
            words: "fenetre window zone de notification tray systray demarrage startup session reduire minimize notifications mise a jour update",
            dot: None,
            page: P::System,
        },
        Entry {
            icon: I_STORAGE,
            tint: teal,
            title: tr("Données"),
            detail: tr("Base SQLCipher chiffrée, sans compte ni serveur."),
            words: "donnees data base database sqlcipher dossier folder portable trousseau keyring",
            dot: None,
            page: P::Data,
        },
    ];

    let q = fold(app.search.trim());
    let keep = |e: &Entry| q.is_empty() || fold(&e.title).contains(&q) || fold(&e.detail).contains(&q) || e.words.contains(&q);
    let personal: Vec<Entry> = personal.into_iter().filter(keep).collect();
    let services: Vec<Entry> = services.into_iter().filter(keep).collect();

    let mut body = column![kit::search(
        &app.search,
        tr("Rechercher"),
        Message::Search,
        Message::Search(String::new())
    )];
    if q.is_empty() {
        body = body.push(protection_card(app));
    }
    let nothing = personal.is_empty() && services.is_empty();
    if !personal.is_empty() {
        body = body.push(
            column![
                container(kit::section_label(tr("Personnalisation"))).padding([0, 4]),
                entry_card(personal)
            ]
            .spacing(10),
        );
    }
    if !services.is_empty() {
        body = body.push(
            column![
                container(kit::section_label(tr("Services et données"))).padding([0, 4]),
                entry_card(services)
            ]
            .spacing(10),
        );
    }
    if nothing {
        body = body.push(
            container(soft(trf("Aucun réglage pour « %1$s »", &[&app.search.trim()])).size(14))
                .padding([24, 0])
                .center_x(Fill),
        );
    }
    body = body.push(
        column![
            row![image(theme::mark()).height(22), text("Nautile").size(20).font(BOLD)]
                .spacing(8)
                .align_y(Alignment::Center),
            soft(trf("Version %1$s · client SSH et RDP libre (GPLv3)", &[&env!("CARGO_PKG_VERSION")])).size(12),
        ]
        .spacing(4)
        .padding(Padding {
            top: 12.0,
            ..Padding::ZERO
        }),
    );
    settings_frame(header(tr("Réglages"), None, None, vec![]), body)
}

pub(super) fn settings(app: &App) -> Element<'_, Message> {
    use crate::app::SettingsPage as P;
    let Some(page) = app.settings_page else {
        return settings_home(app);
    };
    let (name, body) = match page {
        P::Appearance => (tr("Apparence"), appearance_settings(app)),
        P::Terminal => (tr("Terminal"), terminal_settings(app)),
        P::Ai => (tr("Assistant IA"), ai_settings(app)),
        P::Backup => (tr("Sauvegarde et synchro"), backup_settings(app)),
        P::Security => (tr("Sécurité"), column![settings_card(tr("Verrouillage"), lock_settings(app))]),
        P::Data => (tr("Données"), data_settings(app)),
        P::Logs => (tr("Journaux de session"), logs_settings(app)),
        P::System => (tr("Fenêtre et démarrage"), system_settings(app)),
        P::Remote => (tr("Bureau à distance"), remote_settings(app)),
    };
    let back = if page == P::Logs {
        Message::SettingsPage(Some(P::Terminal))
    } else {
        Message::SettingsPage(None)
    };
    let head = container(kit::page_header(name, Some(back), vec![])).padding(Padding {
        top: 0.0,
        right: 28.0,
        bottom: 14.0,
        left: 20.0,
    });
    settings_frame(head.into(), body)
}

pub(super) fn appearance_label(a: Appearance) -> String {
    match a {
        Appearance::System => tr("Système"),
        Appearance::Dark => tr("Sombre"),
        Appearance::Light => tr("Clair"),
        Appearance::Oled => tr("Noir OLED"),
    }
}

/// What the colour and the style look like: chips, a switch, a host and its two buttons (as on Android).
fn appearance_preview<'a>() -> Element<'a, Message> {
    let chip = |name: &str, n: usize, selected: bool| -> Element<'a, Message> {
        kit::count_chip(name.to_owned(), n, selected, None, Message::Noop)
    };
    let host = row![
        container(text("D").size(17).font(BOLD).style(|t: &iced::Theme| text::Style {
            color: Some(theme::nt(t).accent),
        }))
        .center(Length::Fixed(40.0))
        .style(|t: &iced::Theme| theme::soft_tile(theme::nt(t).accent, 12.0)(t)),
        column![
            text("docker-01").size(15).font(BOLD),
            iced::widget::progress_bar(0.0..=100.0, 64.0)
                .girth(6)
                .style(|t: &iced::Theme| iced::widget::progress_bar::Style {
                    background: theme::nt(t).key_bg.into(),
                    bar: theme::nt(t).accent.into(),
                    border: iced::border::rounded(3),
                }),
        ]
        .spacing(8)
        .width(Fill),
    ]
    .spacing(12)
    .align_y(Alignment::Center);
    let buttons = row![
        button(text(tr("Enregistrer")).size(15).font(BOLD).center().width(Fill))
            .width(Fill)
            .padding([12, 18])
            .style(theme::secondary)
            .on_press(Message::Noop),
        button(text(tr("Connecter")).size(15).font(BOLD).center().width(Fill))
            .width(Fill)
            .padding([12, 18])
            .style(theme::primary)
            .on_press(Message::Noop),
    ]
    .spacing(10);
    let top = row![
        chip("Homelab", 4, true),
        chip("VPS", 1, false),
        Space::new().width(Fill),
        iced::widget::toggler(true)
            .on_toggle(|_| Message::Noop)
            .size(24)
            .style(theme::toggler),
    ]
    .spacing(8)
    .align_y(Alignment::Center);
    // Shown only: it does nothing when pressed.
    container(column![top, host, buttons].spacing(18))
        .padding(18)
        .width(Fill)
        .style(theme::card)
        .into()
}

/// One colour of the grid: its disc (with a check when chosen) and its name.
fn accent_swatch<'a>(a: crate::theme::Accent, selected: bool) -> Element<'a, Message> {
    let disc = container(if selected {
        Element::from(kit::icon('\u{E5CA}', 20.0).style(move |t: &iced::Theme| text::Style {
            color: Some(a.colors(theme::nt(t).dark).1),
        }))
    } else {
        Space::new().into()
    })
    .center(Length::Fixed(34.0))
    .style(move |t: &iced::Theme| container::Style {
        background: Some(a.colors(theme::nt(t).dark).0.into()),
        border: iced::border::rounded(17),
        ..Default::default()
    });
    button(column![disc, text(a.label()).size(12)].spacing(8).align_x(Alignment::Center))
        .width(Fill)
        .padding([12, 6])
        .style(move |t: &iced::Theme, st| {
            let n = theme::nt(t);
            button::Style {
                background: Some(if matches!(st, button::Status::Hovered) { n.key_bg } else { n.bg }.into()),
                text_color: n.text,
                border: iced::Border {
                    color: if selected { n.text } else { n.border },
                    width: if selected { 2.0 } else { 1.0 },
                    radius: 14.0.into(),
                },
                ..Default::default()
            }
        })
        .on_press(Message::Accent(a))
        .into()
}

pub(super) fn appearance_settings(app: &App) -> Column<'_, Message> {
    use crate::theme::{Accent, ButtonStyle};
    let s = &app.settings;
    let swatches = row(Accent::ALL.into_iter().map(|a| accent_swatch(a, a == s.accent))).spacing(8);
    column![
        appearance_preview(),
        settings_card(
            tr("Thème de l'app"),
            column![
                segmented(
                    [Appearance::System, Appearance::Dark, Appearance::Light, Appearance::Oled]
                        .into_iter()
                        .map(|a| (a, appearance_label(a)))
                        .collect(),
                    s.appearance,
                    Message::Appearance
                ),
                soft(tr("Le terminal garde ses propres couleurs : réglez-les dans Terminal.")),
            ]
            .spacing(10)
        ),
        settings_card(
            trf("Couleur · %1$s", &[&s.accent.label()]),
            column![
                swatches,
                field(
                    tr("Style des boutons"),
                    segmented(
                        [ButtonStyle::Plein, ButtonStyle::Tonal, ButtonStyle::Contour]
                            .into_iter()
                            .map(|b| (b, b.label()))
                            .collect(),
                        s.button_style,
                        Message::ButtonStyle
                    )
                ),
            ]
            .spacing(16)
        ),
        settings_card(
            tr("Langue"),
            segmented(
                vec![
                    (Language::System, tr("Système")),
                    (Language::Fr, "Français".into()),
                    (Language::En, "English".into())
                ],
                s.language,
                Message::Language
            )
        ),
    ]
}

/// The terminal as it will look: a few lines of `docker compose ps` in the chosen colours, font, size and cursor.
fn terminal_preview(app: &App) -> Element<'_, Message> {
    let s = &app.settings;
    let t = themes::by_id(s.terminal_theme());
    let font = s.terminal_family().font;
    let size = s.terminal_font();
    let (green, blue, yellow, red) = (t.ansi[2], t.ansi[4], t.ansi[3], t.ansi[1]);
    let span = |v: &str, c: iced::Color| iced::widget::span(v.to_owned()).color(c).font(font);
    let line =
        |spans: Vec<iced::widget::text::Span<'static, Message>>| iced::widget::rich_text(spans).size(size).line_height(1.5).font(font);
    let prompt = |rest: &str| {
        vec![
            span("mrd@docker-01", green),
            span(":", t.foreground),
            span("~", blue),
            span("$ ", t.foreground),
            span(rest, t.foreground),
        ]
    };
    let cursor_w = match s.terminal_cursor() {
        "bar" => 2.0,
        _ => size * 0.6,
    };
    let cursor_h = if s.terminal_cursor() == "underline" { 2.0 } else { size * 1.2 };
    let cursor_c = t.cursor;
    let last = row![
        line(prompt("")),
        container(Space::new().width(cursor_w).height(cursor_h)).style(move |_| container::Style {
            background: Some(cursor_c.into()),
            ..Default::default()
        })
    ]
    .align_y(if s.terminal_cursor() == "underline" {
        Alignment::End
    } else {
        Alignment::Center
    });
    let bg = t.background;
    container(
        column![
            line(prompt("docker compose ps")),
            line(vec![span("jellyfin    ", t.foreground), span("Up 3 days", green)]),
            line(vec![span("papote-api  ", t.foreground), span("Restarting", yellow)]),
            line(vec![span("Error: ECONNREFUSED :5432", red)]),
            last,
        ]
        .spacing(2),
    )
    .padding(16)
    .width(Fill)
    .style(move |th: &iced::Theme| container::Style {
        background: Some(bg.into()),
        border: iced::Border {
            color: theme::nt(th).border,
            width: 1.0,
            radius: 16.0.into(),
        },
        ..Default::default()
    })
    .into()
}

/// A choice in a grid of the terminal settings: drawn on the terminal's background, outlined when chosen.
fn term_tile<'a>(content: Element<'a, Message>, name: String, selected: bool, bg: iced::Color, on: Message) -> Element<'a, Message> {
    button(
        column![
            container(content).padding([10, 12]).width(Fill).style(move |_| container::Style {
                background: Some(bg.into()),
                border: iced::border::rounded(8),
                ..Default::default()
            }),
            text(name).size(13).font(if selected { BOLD } else { theme::UI }),
        ]
        .spacing(8),
    )
    .width(Fill)
    .padding(8)
    .style(move |th: &iced::Theme, st| {
        let n = theme::nt(th);
        button::Style {
            background: Some(
                if matches!(st, button::Status::Hovered) {
                    n.key_bg
                } else {
                    n.surface
                }
                .into(),
            ),
            text_color: n.text,
            border: iced::Border {
                color: if selected { n.accent } else { n.border },
                width: if selected { 2.0 } else { 1.0 },
                radius: 12.0.into(),
            },
            ..Default::default()
        }
    })
    .on_press(on)
    .into()
}

/// Items in rows of [per] equal columns.
fn grid<'a>(items: Vec<Element<'a, Message>>, per: usize) -> Element<'a, Message> {
    let mut rows = column![].spacing(10);
    let mut items = items.into_iter().peekable();
    while items.peek().is_some() {
        let mut r = row![].spacing(10);
        for _ in 0..per {
            r = r.push(match items.next() {
                Some(e) => container(e).width(Fill),
                None => container(Space::new()).width(Fill),
            });
        }
        rows = rows.push(r);
    }
    rows.into()
}

pub(super) fn terminal_settings(app: &App) -> Column<'_, Message> {
    use crate::app::TermTab;
    let tabs = segmented(
        vec![
            (TermTab::Aspect, tr("Aspect")),
            (TermTab::Keyboard, tr("Clavier")),
            (TermTab::Sessions, tr("Sessions")),
        ],
        app.term_tab,
        Message::TermTab,
    );
    let body = match app.term_tab {
        TermTab::Aspect => terminal_aspect(app),
        TermTab::Keyboard => terminal_keyboard(app),
        TermTab::Sessions => terminal_sessions(app),
    };
    column![terminal_preview(app), tabs].push(body.spacing(18))
}

fn terminal_aspect(app: &App) -> Column<'_, Message> {
    let s = &app.settings;
    let current = themes::by_id(s.terminal_theme());
    let themes = themes::all()
        .into_iter()
        .map(|t| {
            let dots = row([t.ansi[2], t.ansi[4], t.ansi[3], t.ansi[1]].map(|c| kit::dot(c, 10.0)))
                .spacing(6)
                .height(14)
                .align_y(Alignment::Center);
            term_tile(
                dots.into(),
                tr(t.name),
                t.id == current.id,
                t.background,
                Message::TermTheme(t.id.to_owned()),
            )
        })
        .collect();
    let family = s.terminal_family();
    let mut fonts: Vec<Element<Message>> = crate::term::fonts::all()
        .into_iter()
        .map(|f| {
            let sample = if f.nerd { "0Oo il1 {} \u{E0B0}" } else { "0Oo il1 {}" };
            let imported = f.id.starts_with("file:");
            let name = if imported {
                format!("{} · {}", f.name, tr("importée"))
            } else {
                tr(f.name)
            };
            term_tile(
                text(sample).font(f.font).size(16).color(current.foreground).into(),
                name,
                f.id == family.id,
                current.background,
                Message::TermFamily(f.id.to_owned()),
            )
        })
        .collect();
    fonts.push(term_tile(
        text(tr("+ Importer")).size(16).color(current.foreground).into(),
        ".ttf / .otf".into(),
        false,
        current.background,
        Message::ImportFont,
    ));
    let imported_fonts: Vec<Element<Message>> = crate::term::fonts::all()
        .into_iter()
        .filter(|f| f.id.starts_with("file:"))
        .map(|f| {
            row![
                text(f.name).size(13).width(Fill),
                icon_button(I_TRASH, Message::DeleteFont(f.id.to_owned()))
            ]
            .align_y(Alignment::Center)
            .into()
        })
        .collect();
    let imported_themes: Vec<Element<Message>> = themes::all()
        .into_iter()
        .filter(|t| themes::is_custom(t.id))
        .map(|t| {
            row![
                text(t.name).size(13).width(Fill),
                icon_button(I_TRASH, Message::DeleteTheme(t.id.to_owned()))
            ]
            .align_y(Alignment::Center)
            .into()
        })
        .collect();
    let size = s.terminal_font();
    column![
        settings_card(
            trf("Thème · %1$s", &[&tr(current.name)]),
            column![
                grid(themes, 5),
                row![secondary(
                    tr("Importer un thème (.itermcolors, Xresources, Alacritty)"),
                    Message::ImportTheme
                )],
            ]
            .push(column(imported_themes).spacing(4))
            .spacing(14)
        ),
        settings_card(
            tr("Curseur"),
            segmented(
                vec![("block", tr("Bloc")), ("bar", tr("Barre")), ("underline", tr("Souligné"))],
                match s.terminal_cursor() {
                    "bar" => "bar",
                    "underline" => "underline",
                    _ => "block",
                },
                |v: &str| Message::TermCursor(v.to_owned())
            )
        ),
        settings_card(
            trf("Police · %1$s", &[&tr(family.name)]),
            column![
                grid(fonts, 3),
                column(imported_fonts).spacing(4),
                row![
                    text(tr("Taille du texte")).size(14).width(Fill),
                    secondary("A−".into(), Message::TermFont(size - 1.0)),
                    text(format!("{size} px")).font(MONO).size(14),
                    secondary("A+".into(), Message::TermFont(size + 1.0)),
                ]
                .spacing(10)
                .align_y(Alignment::Center),
                soft(tr(
                    "Toutes les polices fournies sont des Nerd Fonts : symboles Powerline pour Powerlevel10k et Starship."
                )),
            ]
            .spacing(14)
        ),
        soft(tr(
            "Le terminal garde ses propres couleurs : l'app peut être en clair et le terminal en sombre."
        )),
    ]
}

fn terminal_keyboard(app: &App) -> Column<'_, Message> {
    let keys = [
        ("Ctrl+Maj+C".to_owned(), tr("Copier la sélection")),
        ("Ctrl+Maj+V".to_owned(), tr("Coller")),
        (tr("Clic droit"), tr("Copier la sélection, ou coller")),
        ("Ctrl+Maj+T".to_owned(), tr("Nouveau terminal local")),
        ("Ctrl+Maj+W".to_owned(), tr("Fermer l'onglet")),
        ("Ctrl+Tab".to_owned(), tr("Onglet suivant (Maj : précédent)")),
        ("Ctrl+Maj+A".to_owned(), tr("Assistant IA")),
        ("Ctrl+Maj+P".to_owned(), tr("Taper le mot de passe de l'hôte")),
        (tr("Échap"), tr("Fermer la fenêtre ouverte")),
    ];
    let rows: Vec<Element<Message>> = keys
        .into_iter()
        .map(|(k, what)| {
            row![
                text(what).size(14).width(Fill),
                container(text(k.replace("Maj", &tr("Maj"))).font(MONO).size(13))
                    .padding([4, 10])
                    .style(|t: &iced::Theme| container::Style {
                        background: Some(theme::nt(t).key_bg.into()),
                        border: iced::Border {
                            color: theme::nt(t).border,
                            width: 1.0,
                            radius: 8.0.into(),
                        },
                        ..Default::default()
                    }),
            ]
            .padding([12, 16])
            .align_y(Alignment::Center)
            .into()
        })
        .collect();
    column![
        column![container(kit::section_label(tr("Raccourcis"))).padding([0, 4]), kit::card(rows)].spacing(10),
        kit::card(vec![kit::toggle_row(
            None,
            tr("Raccourci mot de passe"),
            tr("Ctrl+Maj+P tape le mot de passe de l'hôte à une invite sudo"),
            app.settings.password_key(),
            Message::PasswordKey
        )]),
    ]
}

fn terminal_sessions(app: &App) -> Column<'_, Message> {
    let s = &app.settings;
    let logs = crate::logs::list(&app.paths.data.join("logs")).len();
    column![
        column![
            container(kit::section_label(tr("Fermeture"))).padding([0, 4]),
            kit::card(vec![kit::toggle_row(
                None,
                tr("Confirmer la fermeture"),
                tr("Demande avant de fermer une session connectée (terminal ou bureau à distance)"),
                s.confirm_close(),
                Message::ConfirmClose
            )]),
        ]
        .spacing(10),
        column![
            container(kit::section_label(tr("Historique et journaux"))).padding([0, 4]),
            kit::card(vec![
                kit::toggle_row(
                    None,
                    tr("Suggestions depuis l'historique"),
                    trf(
                        "Commandes affichées en écho, par hôte (%1$s enregistrées). Jamais les mots de passe.",
                        &[&app.db.as_ref().map(|d| d.command_count()).unwrap_or(0)]
                    ),
                    s.history_suggestions(),
                    Message::HistorySuggestions
                ),
                kit::toggle_row(
                    None,
                    tr("Enregistrer les sessions"),
                    tr("Copie de la sortie de chaque nouvelle session, stockée sur cet ordinateur"),
                    s.session_logs,
                    Message::SessionLogs
                ),
                kit::nav_row(
                    I_DESCRIPTION,
                    theme::nt(&app.theme()).text_soft,
                    tr("Journaux de session"),
                    if logs == 0 {
                        tr("Aucun journal")
                    } else {
                        trf("%1$s fichier(s)", &[&logs])
                    },
                    None,
                    Message::SettingsPage(Some(crate::app::SettingsPage::Logs))
                ),
            ]),
        ]
        .spacing(10),
        row![button(
            text(trf(
                "Effacer l'historique (%1$s commandes)",
                &[&app.db.as_ref().map(|d| d.command_count()).unwrap_or(0)]
            ))
            .size(14)
        )
        .padding([9, 14])
        .style(theme::danger)
        .on_press(Message::ClearHistory)],
    ]
}

/// The recorded sessions: open one, show the folder, delete.
pub(super) fn logs_settings(app: &App) -> Column<'_, Message> {
    let dir = app.paths.data.join("logs");
    let files = crate::logs::list(&dir);
    let now = nautile_core::model::now_ms();
    let mut c = column![row![
        soft(dir.display().to_string()).font(MONO).width(Fill),
        secondary(tr("Ouvrir le dossier"), Message::OpenPath(dir.clone())),
    ]
    .spacing(10)
    .align_y(Alignment::Center)];
    if files.is_empty() {
        c = c.push(soft(tr(
            "Aucun journal. Activez « Enregistrer les sessions » dans Terminal › Sessions.",
        )));
        return c;
    }
    let rows: Vec<Element<Message>> = files
        .into_iter()
        .map(|f| {
            let size = if f.size >= 1024 * 1024 {
                format!("{:.1} Mo", f.size as f64 / 1048576.0)
            } else {
                format!("{} Ko", f.size.div_ceil(1024))
            };
            row![
                button(
                    row![
                        kit::icon_tile(I_DESCRIPTION, theme::nt(&app.theme()).text_soft),
                        column![
                            text(f.name.clone()).size(14).font(MONO),
                            soft(format!("{size} · {}", kit::relative_time(f.modified, now))),
                        ]
                        .spacing(2)
                        .width(Fill),
                    ]
                    .spacing(12)
                    .align_y(Alignment::Center)
                )
                .width(Fill)
                .padding([10, 14])
                .style(theme::card_pick(false))
                .on_press(Message::OpenPath(f.path.clone())),
                icon_button(I_TRASH, Message::DeleteLog(f.path)),
                Space::new().width(6),
            ]
            .align_y(Alignment::Center)
            .into()
        })
        .collect();
    c.push(kit::card(rows))
}

pub(super) fn lock_settings(app: &App) -> Column<'_, Message> {
    let s = &app.settings;
    let lf = &app.lock_form;
    let mut lock_body = column![
        soft(tr(
            "Nautile demande ce code à l'ouverture et après une inactivité. Il ne sert qu'à cet ordinateur."
        )),
        segmented(
            vec![
                (None, tr("Aucun")),
                (Some(nautile_core::lock::LockMethod::Pin), tr("Code PIN")),
                (Some(nautile_core::lock::LockMethod::Password), tr("Mot de passe")),
            ],
            lf.method,
            Message::LockPick
        )
    ]
    .spacing(14);
    let changing = lf.method != s.lock_method || lf.method.is_some() && !s.locks();
    let secure = |ph: String, v: &str, on: fn(String) -> Message| {
        kit::float_field(ph, text_input("", v).on_input(on).on_submit(Message::LockSave).secure(true))
    };
    if s.locks() {
        lock_body = lock_body.push(secure(tr("Code actuel"), &lf.current, Message::LockCurrent));
    }
    if lf.method.is_some() {
        lock_body = lock_body.push(
            row![
                secure(
                    if lf.method == Some(nautile_core::lock::LockMethod::Pin) {
                        tr("Nouveau code PIN (4 à 8 chiffres)")
                    } else {
                        tr("Nouveau mot de passe")
                    },
                    &lf.new,
                    Message::LockNew
                ),
                secure(tr("Confirmer"), &lf.confirm, Message::LockConfirm),
            ]
            .spacing(10),
        );
    }
    if changing || lf.method.is_some() {
        lock_body = lock_body.push(row![primary(tr("Enregistrer"), Some(Message::LockSave))]);
    }
    if let Some(e) = &lf.error {
        lock_body = lock_body.push(text(e.clone()).size(13).style(|t: &iced::Theme| text::Style {
            color: Some(theme::nt(t).pink),
        }));
    }
    if s.locks() {
        lock_body = lock_body.push(field(
            tr("Verrouiller après une inactivité de"),
            segmented(
                vec![(5, "5 min".into()), (15, "15 min".into()), (60, "1 h".into()), (0, tr("Jamais"))],
                s.lock_after(),
                Message::LockAfter,
            ),
        ));
    }
    lock_body
}

pub(super) fn data_settings(app: &App) -> Column<'_, Message> {
    let key_place = match app.settings.key_store.as_deref() {
        Some("keyring") => tr("Clé de la base dans le trousseau du système"),
        _ if app.paths.portable => tr("Mode portable : la clé voyage avec le dossier data"),
        _ => tr("Clé de la base dans un fichier réservé à votre compte (pas de trousseau disponible)"),
    };
    column![settings_card(
        tr("Données"),
        column![
            soft(tr("Base SQLCipher chiffrée, sans compte ni serveur.")),
            soft(key_place),
            label(app.paths.data.display().to_string()).font(MONO),
        ]
        .spacing(8),
    )]
}

pub(super) fn place_icon(d: &nautile_net::store::Dest) -> char {
    match d {
        nautile_net::store::Dest::Folder { .. } => I_FOLDER,
        nautile_net::store::Dest::WebDav { .. } => I_HOSTS,
        nautile_net::store::Dest::S3 { .. } => I_INVENTORY,
    }
}

/// The kind of a place, as on Android's rows.
fn place_kind(d: &nautile_net::store::Dest) -> String {
    match d {
        nautile_net::store::Dest::Folder { .. } => tr("Dossier"),
        nautile_net::store::Dest::WebDav { .. } => "Nextcloud / WebDAV".into(),
        nautile_net::store::Dest::S3 { endpoint, .. } => format!("S3 · {endpoint}"),
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct TargetChoice(String, String);

impl std::fmt::Display for TargetChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.1)
    }
}

// --- AI assistant ---

/// A provider's logo on its tile (its own colours, or light / dark).
fn provider_tile<'a>(app: &App, k: nautile_net::ai::ProviderKind, size: f32) -> Element<'a, Message> {
    let dark = theme::nt(&app.theme()).dark;
    let logo: Element<'a, Message> = match theme::provider_logo(k, dark) {
        Some(h) => image(h).width(size * 0.55).height(size * 0.55).into(),
        None => kit::icon('\u{E9F4}', size * 0.5).into(),
    };
    container(logo)
        .center(Length::Fixed(size))
        .style(|t: &iced::Theme| container::Style {
            background: Some(theme::nt(t).bg.into()),
            border: iced::Border {
                color: theme::nt(t).border,
                width: 1.0,
                radius: 11.0.into(),
            },
            ..Default::default()
        })
        .into()
}

/// The assistant's settings: the providers and their switches, the system prompts, what is sent.
pub(super) fn ai_settings(app: &App) -> Column<'_, Message> {
    use crate::assistant::AiMsg as A;
    use nautile_net::ai::ProviderKind;
    let s = &app.settings;
    let default = app.ai_ready_providers().first().copied();
    let rows: Vec<Element<Message>> = ProviderKind::ALL
        .into_iter()
        .map(|k| {
            let ready = app.ai_configured(k);
            let on = s.ai_enabled.contains(&k) && ready;
            let mut name = row![text(k.label()).size(15).font(BOLD)].spacing(8).align_y(Alignment::Center);
            if default == Some(k) {
                name = name.push(
                    container(text(tr("Par défaut")).size(11).font(BOLD))
                        .padding([2, 8])
                        .style(|t: &iced::Theme| theme::chip(theme::nt(t).green)(t)),
                );
            }
            let detail: Element<Message> = if ready {
                soft(nautile_net::ai::pretty_model(&app.ai_config(k).model)).into()
            } else {
                text(tr("Non configuré"))
                    .size(13)
                    .style(|t: &iced::Theme| text::Style {
                        color: Some(theme::nt(t).accent),
                    })
                    .into()
            };
            row![
                button(
                    row![provider_tile(app, k, 40.0), column![name, detail].spacing(3).width(Fill)]
                        .spacing(14)
                        .align_y(Alignment::Center)
                )
                .width(Fill)
                .padding([12, 14])
                .style(theme::card_pick(false))
                .on_press(am(A::Provider(k))),
                button(icon(I_GEAR).size(22))
                    .padding(8)
                    .style(theme::ghost)
                    .on_press(am(A::Provider(k))),
                iced::widget::toggler(on)
                    .on_toggle(move |v| am(A::Enable(k, v)))
                    .size(24)
                    .style(theme::toggler),
                Space::new().width(10),
            ]
            .spacing(6)
            .align_y(Alignment::Center)
            .into()
        })
        .collect();

    // The system prompts: the default one, then the user's.
    let picked = s.ai_prompt_id.clone().filter(|id| s.ai_prompts.iter().any(|p| &p.id == id));
    let radio = |selected: bool| -> Element<Message> {
        kit::icon(if selected { '\u{E837}' } else { '\u{E836}' }, 22.0)
            .style(move |t: &iced::Theme| text::Style {
                color: Some(if selected { theme::nt(t).accent } else { theme::nt(t).muted }),
            })
            .into()
    };
    let mut prompts: Vec<Element<Message>> = vec![button(
        row![
            radio(picked.is_none()),
            column![
                text(tr("Par défaut")).size(15).font(BOLD),
                soft(tr("Une commande prête à coller, puis une courte explication"))
            ]
            .spacing(2)
            .width(Fill),
        ]
        .spacing(12)
        .align_y(Alignment::Center),
    )
    .width(Fill)
    .padding([12, 14])
    .style(theme::card_pick(false))
    .on_press(am(A::PromptPick(None)))
    .into()];
    for p in &s.ai_prompts {
        let selected = picked.as_ref() == Some(&p.id);
        let first_line: String = p.text.lines().next().unwrap_or_default().chars().take(90).collect();
        prompts.push(
            row![
                button(
                    row![
                        radio(selected),
                        column![text(p.name.clone()).size(15).font(BOLD), soft(first_line)]
                            .spacing(2)
                            .width(Fill),
                    ]
                    .spacing(12)
                    .align_y(Alignment::Center)
                )
                .width(Fill)
                .padding([12, 14])
                .style(theme::card_pick(false))
                .on_press(am(A::PromptPick(Some(p.id.clone())))),
                icon_button(I_EDIT, am(A::PromptOpen(Some(p.id.clone())))),
                Space::new().width(8),
            ]
            .align_y(Alignment::Center)
            .into(),
        );
    }
    prompts.push(
        button(
            row![icon(I_ADD).size(20), text(tr("Nouveau prompt")).size(15).font(BOLD)]
                .spacing(10)
                .align_y(Alignment::Center),
        )
        .width(Fill)
        .padding([14, 16])
        .style(|t: &iced::Theme, st| {
            let mut b = theme::card_pick(false)(t, st);
            b.text_color = theme::nt(t).accent;
            b
        })
        .on_press(am(A::PromptOpen(None)))
        .into(),
    );

    let lines = s.ai_lines();
    let sent = column![
        soft(tr("✓ Votre demande, l'adresse et le protocole de l'hôte")),
        soft(trf(
            "✓ Les %1$s dernières lignes du terminal, seulement si « Joindre l'écran » est coché",
            &[&lines]
        )),
        soft(trf(
            "✓ Les fichiers que vous joignez (texte, %1$s au plus chacun)",
            &[&format!("{} Ko", nautile_net::ai::MAX_FILE_BYTES / 1024)]
        )),
        soft(tr("✕ Jamais vos mots de passe, vos clés ni votre historique")),
    ]
    .spacing(8);
    column![
        soft(tr("Décrivez ce que vous voulez faire dans le terminal, l'assistant propose la commande. Rien n'est exécuté sans votre accord.")).size(14),
        column![container(kit::section_label(tr("Fournisseurs"))).padding([0, 4]), kit::card(rows)].spacing(10),
        soft(tr("Les fournisseurs activés sont proposés dans l'assistant du terminal ; le dernier utilisé passe en premier. La roue dentée règle la clé, l'adresse et le modèle.")),
        column![container(kit::section_label(tr("Prompts système"))).padding([0, 4]), kit::card(prompts)].spacing(10),
        column![
            container(kit::section_label(tr("Lignes du terminal jointes"))).padding([0, 4]),
            segmented(
                [40usize, 100, 200, 500].into_iter().map(|n| (n, n.to_string())).collect(),
                lines,
                |n| am(A::Lines(n))
            ),
            soft(tr("Plus de lignes donnent plus de contexte pour expliquer une erreur, mais la requête est plus longue et plus coûteuse. L'historique de défilement est inclus.")),
        ]
        .spacing(10),
        column![
            container(kit::section_label(tr("Ce qui est envoyé"))).padding([0, 4]),
            container(sent).padding(18).width(Fill).style(theme::card),
            soft(tr("Les clés API restent chiffrées sur cet ordinateur (base SQLCipher).")),
        ]
        .spacing(10),
    ]
}

/// A provider's key, address and model, in a pop-up.
pub(super) fn ai_provider_modal(app: &App, k: nautile_net::ai::ProviderKind, max_height: f32) -> Element<'_, Message> {
    use crate::assistant::AiMsg as A;
    let st = &app.assistant;
    let saved = app.ai_config(k);
    let has_key = saved.api_key.is_some();
    let status = if !k.needs_key() {
        tr("Sans clé API")
    } else if has_key {
        tr("Clé enregistrée")
    } else {
        tr("Clé API requise")
    };
    let mut body = column![].spacing(16);
    if k.editable_url() {
        body = body.push(kit::float_field(
            tr("Adresse de l'API"),
            text_input(k.default_url().unwrap_or_default(), &st.url_input)
                .on_input(|v| am(A::Url(v)))
                .font(MONO),
        ));
        if k == nautile_net::ai::ProviderKind::Local {
            body = body.push(label(tr(
                "Ollama : http://IP-du-PC:11434/v1 (lancez-le avec OLLAMA_HOST=0.0.0.0). LM Studio : http://IP-du-PC:1234/v1.",
            )));
        }
    }
    if k.needs_key() || k.editable_url() {
        let key_label = if has_key {
            tr("Nouvelle clé API (facultatif)")
        } else if k.needs_key() {
            tr("Clé API")
        } else {
            tr("Clé API (facultative)")
        };
        body = body.push(
            row![
                kit::float_field(
                    key_label,
                    text_input(k.key_hint(), &st.key_input)
                        .on_input(|v| am(A::Key(v)))
                        .secure(!st.show_key)
                ),
                button(icon(if st.show_key { '\u{E8F5}' } else { '\u{E8F4}' }).size(22))
                    .padding(10)
                    .style(theme::ghost)
                    .on_press(am(A::ShowKey)),
            ]
            .spacing(6)
            .align_y(Alignment::End),
        );
    }
    let model = if st.model_input.trim().is_empty() {
        saved.model.clone()
    } else {
        st.model_input.clone()
    };
    body = body.push(kit::float_box(
        tr("Modèle"),
        button(
            row![
                column![
                    text(nautile_net::ai::pretty_model(&model)).size(15),
                    text(model.clone()).font(MONO).size(12).style(|t: &iced::Theme| text::Style {
                        color: Some(theme::nt(t).muted),
                    }),
                ]
                .spacing(2)
                .width(Fill),
                icon('\u{E5CF}').size(22),
            ]
            .align_y(Alignment::Center),
        )
        .width(Fill)
        .padding([10, 14])
        .style(|t: &iced::Theme, s| {
            let mut b = theme::secondary(t, s);
            b.border.radius = 12.0.into();
            b
        })
        .on_press(am(A::OpenModels)),
    ));
    match &st.status {
        Some(Ok(s)) => {
            body = body.push(
                container(green(trf("Connexion OK. Réponse : %1$s", &[s])))
                    .padding([10, 14])
                    .width(Fill)
                    .style(|t: &iced::Theme| theme::soft_tile(theme::nt(t).green, 10.0)(t)),
            )
        }
        Some(Err(e)) => {
            body = body.push(
                container(pink(trf("Échec : %1$s", &[e])))
                    .padding([10, 14])
                    .width(Fill)
                    .style(|t: &iced::Theme| theme::soft_tile(theme::nt(t).pink, 10.0)(t)),
            )
        }
        None => {}
    }
    if has_key {
        body = body.push(
            button(text(trf("Oublier la clé %1$s", &[&k.label()])).size(14))
                .padding([8, 0])
                .style(|t: &iced::Theme, _| button::Style {
                    text_color: theme::nt(t).pink,
                    ..Default::default()
                })
                .on_press(am(A::ForgetKey)),
        );
    }
    let foot = row![
        Space::new().width(Fill),
        secondary(if st.testing { tr("Test…") } else { tr("Tester") }, am(A::Test)),
        primary(tr("Enregistrer"), Some(am(A::Save))),
    ]
    .spacing(10)
    .align_y(Alignment::Center);
    let head = row![
        provider_tile(app, k, 44.0),
        column![
            text(k.label()).size(20).font(BOLD),
            text(status).size(13).style(move |t: &iced::Theme| text::Style {
                color: Some(if k.needs_key() && !has_key {
                    theme::nt(t).accent
                } else {
                    theme::nt(t).muted
                }),
            }),
        ]
        .spacing(2)
        .width(Fill),
        button(kit::icon('\u{E5CD}', 22.0))
            .padding(6)
            .style(theme::ghost)
            .on_press(Message::OpenModal(None)),
    ]
    .spacing(14)
    .align_y(Alignment::Center);
    kit::overlay(
        kit::dialog(
            container(head)
                .padding(Padding {
                    top: 18.0,
                    right: 14.0,
                    bottom: 10.0,
                    left: 22.0,
                })
                .into(),
            body,
            Some(foot.into()),
            580.0,
            max_height,
        ),
        None,
    )
}

/// « Choisir un modèle »: the provider's models, searched and filtered.
pub(super) fn ai_models_modal(app: &App, k: nautile_net::ai::ProviderKind, max_height: f32) -> Element<'_, Message> {
    use crate::assistant::{AiMsg as A, ModelFilter};
    let st = &app.assistant;
    let q = st.model_search.trim().to_lowercase();
    let matching: Vec<&String> = st.models.iter().filter(|m| q.is_empty() || m.to_lowercase().contains(&q)).collect();
    let mut chips = row![].spacing(8);
    for f in ModelFilter::ALL {
        let n = matching.iter().filter(|m| f.keep(m)).count();
        chips = chips.push(kit::count_chip(f.label(), n, st.model_filter == f, None, am(A::Filter(f))));
    }
    let shown: Vec<&String> = matching.into_iter().filter(|m| st.model_filter.keep(m)).collect();
    let current = st.model_input.trim();
    let mut body = column![
        kit::search(
            &st.model_search,
            tr("Rechercher un modèle"),
            |v| am(A::ModelSearch(v)),
            am(A::ModelSearch(String::new()))
        ),
        chips.wrap(),
    ]
    .spacing(14);
    if !q.is_empty() && !st.models.iter().any(|m| m.to_lowercase() == q) {
        let typed = st.model_search.trim().to_owned();
        body = body.push(kit::card(vec![button(
            column![
                text(trf("Utiliser « %1$s »", &[&typed])).size(15).font(BOLD),
                soft(tr("Nom saisi à la main"))
            ]
            .spacing(2),
        )
        .width(Fill)
        .padding([12, 14])
        .style(theme::card_pick(false))
        .on_press(am(A::UseModel(typed)))
        .into()]));
    }
    if st.models.is_empty() {
        body = body.push(match &st.status {
            Some(Err(e)) => column![pink(e.clone()), secondary(tr("Réessayer"), am(A::LoadModels))]
                .spacing(10)
                .into(),
            _ => Element::from(soft(tr("Chargement des modèles…"))),
        });
    } else if shown.is_empty() {
        body = body.push(soft(tr("Aucun modèle ne correspond.")));
    } else {
        let rows: Vec<Element<Message>> = shown
            .into_iter()
            .map(|m| {
                let selected = m == current;
                let mut r = row![column![
                    text(nautile_net::ai::pretty_model(m))
                        .size(15)
                        .font(if selected { BOLD } else { theme::UI }),
                    text(m.clone()).font(MONO).size(12).style(|t: &iced::Theme| text::Style {
                        color: Some(theme::nt(t).muted),
                    }),
                ]
                .spacing(2)
                .width(Fill)]
                .align_y(Alignment::Center);
                if selected {
                    r = r.push(kit::icon('\u{E5CA}', 22.0).style(|t: &iced::Theme| text::Style {
                        color: Some(theme::nt(t).accent),
                    }));
                }
                button(r)
                    .width(Fill)
                    .padding([10, 14])
                    .style(theme::card_pick(selected))
                    .on_press(am(A::UseModel(m.clone())))
                    .into()
            })
            .collect();
        body = body.push(kit::card(rows));
    }
    kit::overlay(
        kit::dialog(
            kit::dialog_head(
                tr("Choisir un modèle"),
                Some(k.label()),
                Some(Message::OpenModal(Some(crate::app::Modal::AiProvider(k)))),
                Message::OpenModal(None),
            ),
            body,
            None,
            640.0,
            max_height,
        ),
        None,
    )
}

/// A system prompt of the user's: its name and instructions.
pub(super) fn ai_prompt_modal(app: &App, max_height: f32) -> Element<'_, Message> {
    use crate::assistant::AiMsg as A;
    let st = &app.assistant;
    let existing = matches!(st.prompt_edit, Some(Some(_)));
    let body = column![
        kit::float_field(
            tr("Nom"),
            text_input(&tr("Pédagogue, Sécurité, Docker…"), &st.prompt_name).on_input(|v| am(A::PromptName(v)))
        ),
        field(
            tr("Consignes"),
            text_editor(&st.prompt_text)
                .on_action(|a| am(A::PromptText(a)))
                .font(MONO)
                .size(13)
                .height(260)
                .padding(12)
                .style(|t: &iced::Theme, s| {
                    let n = theme::nt(t);
                    let focused = matches!(s, text_editor::Status::Focused { .. });
                    text_editor::Style {
                        background: n.bg.into(),
                        border: iced::Border {
                            color: if focused { n.accent } else { n.border },
                            width: 1.0,
                            radius: 10.0.into(),
                        },
                        placeholder: n.placeholder,
                        value: n.text,
                        selection: theme::alpha(n.accent, 0.35),
                    }
                })
        ),
        row![secondary(tr("Repartir du prompt par défaut"), am(A::PromptFromDefault))],
        soft(tr(
            "L'assistant doit garder la commande dans un bloc ```bash``` : c'est elle que « Insérer » tape dans le terminal."
        )),
    ]
    .spacing(14);
    let mut foot = row![].spacing(10).align_y(Alignment::Center);
    if existing {
        foot = foot.push(
            button(
                text(if st.prompt_confirm_delete {
                    tr("Confirmer la suppression")
                } else {
                    tr("Supprimer")
                })
                .size(14),
            )
            .padding([9, 14])
            .style(theme::danger)
            .on_press(am(A::PromptDelete)),
        );
    }
    foot = foot
        .push(Space::new().width(Fill))
        .push(secondary(tr("Annuler"), Message::OpenModal(None)))
        .push(primary(
            tr("Enregistrer"),
            (!st.prompt_name.trim().is_empty() && !st.prompt_text.text().trim().is_empty()).then_some(am(A::PromptSave)),
        ));
    kit::overlay(
        kit::dialog(
            kit::dialog_head(
                if existing { tr("Modifier le prompt") } else { tr("Nouveau prompt") },
                None,
                None,
                Message::OpenModal(None),
            ),
            body,
            Some(foot.into()),
            680.0,
            max_height,
        ),
        None,
    )
}

// --- Backups and synchronisation (as Android's BackupSettingsScreen) ---

/// How a card stands: its border follows (green when on, pink on error, amber when something is to do).
#[derive(Clone, Copy, PartialEq)]
enum CardTone {
    Plain,
    Good,
    Bad,
    Warn,
}

fn toned_card(tone: CardTone) -> impl Fn(&iced::Theme) -> container::Style {
    move |t| {
        let n = theme::nt(t);
        let border = match tone {
            CardTone::Plain => n.border,
            CardTone::Good => theme::alpha(n.green, 0.45),
            CardTone::Bad => theme::alpha(n.pink, 0.55),
            CardTone::Warn => theme::alpha(n.accent, 0.45),
        };
        container::Style {
            background: Some(n.surface.into()),
            border: iced::Border {
                color: border,
                width: 1.0,
                radius: 16.0.into(),
            },
            ..Default::default()
        }
    }
}

/// The head of a card: its tile, its title with an (i), its state, and its switch.
fn switch_head<'a>(
    c: char,
    lit: bool,
    title: String,
    info: Message,
    subtitle: Element<'a, Message>,
    on: bool,
    toggle: impl Fn(bool) -> Message + 'a,
) -> Element<'a, Message> {
    let tile = container(kit::icon(c, 22.0))
        .center(Length::Fixed(42.0))
        .style(move |t: &iced::Theme| {
            let n = theme::nt(t);
            theme::soft_tile(if lit { n.green } else { n.muted }, 12.0)(t)
        });
    row![
        tile,
        column![
            row![
                text(title).size(16).font(BOLD),
                button(kit::icon(I_INFO, 18.0).style(|t: &iced::Theme| text::Style {
                    color: Some(theme::nt(t).muted),
                }))
                .padding(4)
                .style(theme::ghost)
                .on_press(info),
            ]
            .spacing(6)
            .align_y(Alignment::Center),
            subtitle,
        ]
        .spacing(2)
        .width(Fill),
        iced::widget::toggler(on).on_toggle(toggle).size(26).style(theme::toggler),
    ]
    .spacing(14)
    .align_y(Alignment::Center)
    .into()
}

pub(super) fn backup_settings(app: &App) -> Column<'_, Message> {
    column![sync_card(app), lan_card(app), backup_card(app)]
}

fn sync_card(app: &App) -> Element<'_, Message> {
    use crate::backup::BackupMsg as B;
    let s = &app.settings;
    let st = &app.backup;
    let now = nautile_core::model::now_ms();
    let on = s.sync_enabled;
    let error = s.sync_error.clone().filter(|_| on);
    let subtitle: Element<Message> = if !on {
        soft(tr("Vos hôtes, clés et snippets sur tous vos appareils")).into()
    } else if st.syncing {
        soft(tr("Synchronisation…")).into()
    } else if st.waiting_vpn {
        text(tr("En attente du VPN"))
            .size(13)
            .style(|t: &iced::Theme| text::Style {
                color: Some(theme::nt(t).accent),
            })
            .into()
    } else if let Some(at) = st.retry_at.filter(|at| *at > now) {
        let hm = chrono::DateTime::from_timestamp_millis(at)
            .map(|d| d.with_timezone(&chrono::Local).format("%H:%M").to_string())
            .unwrap_or_default();
        soft(trf("Serveur injoignable, nouvel essai à %1$s", &[&hm])).into()
    } else if let Some(e) = &error {
        pink(e.clone()).size(13).into()
    } else {
        let devices = s.devices.len().max(1);
        let when = s
            .last_sync
            .map(|l| kit::relative_time(l, now))
            .unwrap_or_else(|| tr("Pas encore synchronisé"));
        soft(trf("%1$s · %2$s appareil(s)", &[&when, &devices])).into()
    };
    let head = switch_head(
        I_SYNC,
        on,
        tr("Synchroniser mes appareils"),
        Message::OpenModal(Some(crate::app::Modal::SyncInfo)),
        subtitle,
        on,
        |v| bm(B::SyncEnabled(v)),
    );
    let mut body = column![head].spacing(16);
    if on {
        if s.targets.is_empty() {
            body = body.push(
                row![
                    pink(tr("Ajoutez d'abord un emplacement de sauvegarde.")).width(Fill),
                    secondary(tr("Ajouter un emplacement"), Message::OpenModal(Some(crate::app::Modal::AddPlace))),
                ]
                .spacing(10)
                .align_y(Alignment::Center),
            );
        } else {
            // The devices sharing the file.
            let me = s.device_id.clone().unwrap_or_default();
            let mut chips = row![].spacing(8);
            for (id, name, _) in &s.devices {
                let this = *id == me;
                chips = chips.push(
                    container(
                        row![
                            kit::icon(if this { I_COMPUTER } else { I_PHONE }, 16.0),
                            text(if this {
                                trf("%1$s (cet ordinateur)", &[name])
                            } else {
                                name.clone()
                            })
                            .size(13)
                        ]
                        .spacing(6)
                        .align_y(Alignment::Center),
                    )
                    .padding([6, 12])
                    .style(move |t: &iced::Theme| {
                        let n = theme::nt(t);
                        theme::chip(if this { n.accent } else { n.text_soft })(t)
                    }),
                );
            }
            if !s.devices.is_empty() {
                body = body.push(chips.wrap());
            }
            let choices: Vec<TargetChoice> = s.targets.iter().map(|t| TargetChoice(t.id.clone(), t.dest.describe())).collect();
            let current = choices.iter().find(|t| Some(&t.0) == s.sync_target.as_ref()).cloned();
            body = body.push(kit::float_box(
                tr("Fichier partagé"),
                pick_list(choices, current, |t: TargetChoice| bm(B::SyncTarget(t.0)))
                    .width(Fill)
                    .padding([12, 14])
                    .text_size(14)
                    .style(theme::select),
            ));
            body = body.push(
                row![
                    field(
                        tr("Fréquence"),
                        segmented(
                            vec![
                                (5, "5 min".into()),
                                (15, "15 min".into()),
                                (30, "30 min".into()),
                                (60, "1 h".into())
                            ],
                            s.sync_minutes(),
                            |m| bm(B::SyncMinutes(m)),
                        )
                    )
                    .width(Fill),
                    field(
                        tr("Réseau"),
                        segmented(
                            vec![(false, tr("Tous")), (true, tr("Seulement via le VPN"))],
                            s.sync_vpn_only,
                            |v| bm(B::VpnOnly(v)),
                        )
                    )
                    .width(Fill),
                ]
                .spacing(14),
            );
            if s.sync_vpn_only {
                let up = crate::backup::vpn_up();
                body = body.push(
                    row![
                        kit::icon(I_VPN_LOCK, 18.0).style(move |t: &iced::Theme| text::Style {
                            color: Some(if up { theme::nt(t).green } else { theme::nt(t).accent }),
                        }),
                        soft(if up {
                            tr("VPN actif : la synchronisation passe par lui.")
                        } else {
                            tr("Aucun VPN actif : la synchronisation attend qu'il se connecte (WireGuard, Tailscale, OpenVPN…).")
                        }),
                    ]
                    .spacing(8)
                    .align_y(Alignment::Center),
                );
            }
            if !app.has_backup_password() {
                body = body.push(
                    row![
                        pink(tr("Mot de passe de sauvegarde non enregistré")).width(Fill),
                        secondary(tr("Choisir"), Message::OpenModal(Some(crate::app::Modal::Password))),
                    ]
                    .spacing(10)
                    .align_y(Alignment::Center),
                );
            }
            body = body.push(row![secondary(tr("Synchroniser maintenant"), bm(B::SyncNow))]);
        }
    }
    let tone = if error.is_some() {
        CardTone::Bad
    } else if on {
        CardTone::Good
    } else {
        CardTone::Plain
    };
    container(body).padding(18).width(Fill).style(toned_card(tone)).into()
}

fn lan_card(app: &App) -> Element<'_, Message> {
    use crate::backup::BackupMsg as B;
    let s = &app.settings;
    let st = &app.backup;
    let on = s.lan_enabled;
    let subtitle = if !on {
        tr("Directement entre vos appareils, sur le même réseau")
    } else if s.lan_peers.is_empty() {
        tr("Aucun appareil associé")
    } else {
        trf("%1$s appareil(s) associé(s)", &[&s.lan_peers.len()])
    };
    let head = switch_head(
        I_DEVICES,
        on && !s.lan_peers.is_empty(),
        tr("Sur le Wi-Fi, sans cloud"),
        Message::OpenModal(Some(crate::app::Modal::LanInfo)),
        soft(subtitle).into(),
        on,
        |v| bm(B::LanEnabled(v)),
    );
    let mut body = column![head].spacing(14);
    if on {
        let now = nautile_core::model::now_ms();
        if !s.lan_peers.is_empty() {
            let rows: Vec<Element<Message>> = s
                .lan_peers
                .iter()
                .map(|p| {
                    let near = st.lan_found.iter().any(|f| f.id == p.id);
                    let state = if p.last > 0 {
                        trf("Synchro %1$s", &[&kit::relative_time(p.last, now)])
                    } else {
                        tr("jamais synchronisé")
                    };
                    row![
                        kit::icon_tile(I_PHONE, theme::nt(&app.theme()).blue),
                        column![
                            text(p.name.clone()).size(15).font(BOLD),
                            soft(if near { trf("à portée · %1$s", &[&state]) } else { state }),
                        ]
                        .spacing(2)
                        .width(Fill),
                        icon_button(I_CLOSE, bm(B::LanForget(p.id.clone()))),
                    ]
                    .spacing(12)
                    .padding([10, 14])
                    .align_y(Alignment::Center)
                    .into()
                })
                .collect();
            body = body.push(kit::card(rows));
        }
        let mut actions = row![secondary(tr("Associer"), Message::OpenModal(Some(crate::app::Modal::LanPair)))].spacing(10);
        if !s.lan_peers.is_empty() {
            actions = actions.push(secondary(tr("Synchroniser"), bm(B::LanSyncNow)));
        }
        body = body.push(actions);
        if let Some(m) = &st.lan_status {
            body = body.push(soft(m.clone()));
        }
    }
    let tone = if on && !s.lan_peers.is_empty() {
        CardTone::Good
    } else {
        CardTone::Plain
    };
    container(body).padding(18).width(Fill).style(toned_card(tone)).into()
}

fn backup_card(app: &App) -> Element<'_, Message> {
    use crate::backup::BackupMsg as B;
    let s = &app.settings;
    let st = &app.backup;
    let now = nautile_core::model::now_ms();
    let last = last_saved(app);
    let fresh = last.is_some_and(|l| now - l < 30 * 24 * 3600 * 1000);
    let failed = s.targets.iter().find_map(|t| t.error.clone());
    let (title, detail) = match last {
        Some(l) => (
            trf("Dernière sauvegarde %1$s", &[&kit::relative_time(l, now)]),
            if fresh {
                tr("À jour")
            } else {
                tr("Plus de 30 jours : pensez à sauvegarder")
            },
        ),
        None => (
            tr("Aucune sauvegarde exportée"),
            tr("Une copie chiffrée, à garder ailleurs que sur cet ordinateur"),
        ),
    };
    let status = row![
        container(kit::icon(if fresh { I_CLOUD_DONE } else { I_CLOUD_UPLOAD }, 22.0))
            .center(Length::Fixed(42.0))
            .style(move |t: &iced::Theme| {
                let n = theme::nt(t);
                theme::soft_tile(if fresh { n.green } else { n.accent }, 12.0)(t)
            }),
        column![text(title).size(16).font(BOLD), soft(detail)].spacing(2).width(Fill),
    ]
    .spacing(14)
    .align_y(Alignment::Center);
    let mut body = column![status].spacing(14);

    if !app.has_backup_password() {
        body = body.push(
            container(
                row![
                    kit::icon(I_VPN_KEY, 20.0),
                    soft(tr(
                        "Choisissez d'abord le mot de passe des sauvegardes (le même sur tous vos appareils)."
                    ))
                    .width(Fill),
                    primary(tr("Choisir"), Some(Message::OpenModal(Some(crate::app::Modal::Password)))),
                ]
                .spacing(12)
                .align_y(Alignment::Center),
            )
            .padding([10, 14])
            .style(|t: &iced::Theme| theme::soft_tile(theme::nt(t).accent, 12.0)(t)),
        );
    }

    // The places.
    body = body.push(label(tr("Emplacements")));
    let sync_target = s.sync_target.clone().filter(|_| s.sync_enabled);
    let mut rows: Vec<Element<Message>> = s
        .targets
        .iter()
        .map(|t| {
            let syncing = sync_target.as_ref() == Some(&t.id);
            let running = st.running.contains(&t.id) || (syncing && st.syncing);
            let when = t.last.map(|l| kit::relative_time(l, now)).unwrap_or_else(|| tr("jamais"));
            let state: Element<Message> = if running {
                soft(tr("En cours…")).into()
            } else if let Some(e) = &t.error {
                pink(trf("Échec : %1$s", &[e])).size(13).into()
            } else if syncing {
                soft(trf("Synchronisé · %1$s", &[&when])).into()
            } else if t.auto {
                let hm = format!("{:02}:{:02}", t.time / 60, t.time % 60);
                soft(format!("{} · {} · {}", t.frequency.label(), hm, when)).into()
            } else {
                soft(format!("{} · {}", tr("Manuelle"), when)).into()
            };
            let mut name = row![text(place_kind(&t.dest)).size(15).font(BOLD)]
                .spacing(8)
                .align_y(Alignment::Center);
            if syncing {
                name = name.push(
                    container(text(tr("Synchro")).size(11).font(BOLD))
                        .padding([2, 8])
                        .style(|t: &iced::Theme| theme::chip(theme::nt(t).green)(t)),
                );
            }
            button(
                row![
                    kit::icon_tile(place_icon(&t.dest), theme::nt(&app.theme()).text_soft),
                    column![name, text(t.dest.describe()).font(MONO).size(12), state]
                        .spacing(3)
                        .width(Fill),
                    kit::icon(I_GEAR, 22.0),
                ]
                .spacing(14)
                .align_y(Alignment::Center),
            )
            .width(Fill)
            .padding([12, 14])
            .style(theme::card_pick(false))
            .on_press(bm(B::Open(Some(t.id.clone()))))
            .into()
        })
        .collect();
    rows.push(
        button(
            row![icon(I_ADD).size(22), text(tr("Ajouter un emplacement")).size(15).font(BOLD)]
                .spacing(12)
                .align_y(Alignment::Center),
        )
        .width(Fill)
        .padding([14, 16])
        .style(|t: &iced::Theme, st| {
            let mut b = theme::card_pick(false)(t, st);
            b.text_color = theme::nt(t).accent;
            b
        })
        .on_press(Message::OpenModal(Some(crate::app::Modal::AddPlace)))
        .into(),
    );
    body = body.push(kit::card(rows));

    let mut actions = row![].spacing(10).align_y(Alignment::Center);
    if !s.targets.is_empty() {
        actions = actions.push(primary(
            if s.targets.len() > 1 {
                tr("Sauvegarder partout")
            } else {
                tr("Sauvegarder")
            },
            app.has_backup_password().then_some(bm(B::NowAll)),
        ));
    }
    actions = actions.push(secondary(tr("Restaurer…"), Message::ImportPick));
    body = body.push(actions);
    body = body.push(
        button(text(tr("Exporter aussi une copie dans un fichier…")).size(14))
            .padding([4, 0])
            .style(|t: &iced::Theme, _| button::Style {
                text_color: theme::nt(t).accent,
                ..Default::default()
            })
            .on_press(Message::OpenModal(Some(crate::app::Modal::Export))),
    );

    // Copies kept on this computer.
    let versions = crate::backup::versions(app);
    if !versions.is_empty() {
        body = body.push(label(tr("Versions précédentes")));
        let rows: Vec<Element<Message>> = versions
            .into_iter()
            .map(|(path, at, size)| {
                row![
                    kit::icon(I_HISTORY, 20.0).style(|t: &iced::Theme| text::Style {
                        color: Some(theme::nt(t).muted),
                    }),
                    column![text(when(at)).font(MONO).size(13), soft(nautile_net::sftp::human_size(size))]
                        .spacing(2)
                        .width(Fill),
                    secondary(tr("Restaurer"), bm(B::RestoreVersion(path))),
                ]
                .spacing(12)
                .padding([10, 14])
                .align_y(Alignment::Center)
                .into()
            })
            .collect();
        body = body.push(kit::card(rows));
    }
    body = body.push(soft(tr(
        "Fichier chiffré (Argon2id + AES-256-GCM) : sans le mot de passe, il est illisible. Il se restaure sur le téléphone comme ici.",
    )));
    if app.has_backup_password() {
        body = body.push(
            button(text(tr("Changer le mot de passe de sauvegarde")).size(14))
                .padding([4, 0])
                .style(|t: &iced::Theme, _| button::Style {
                    text_color: theme::nt(t).accent,
                    ..Default::default()
                })
                .on_press(Message::OpenModal(Some(crate::app::Modal::Password))),
        );
    }
    let tone = if failed.is_some() {
        CardTone::Bad
    } else if !fresh {
        CardTone::Warn
    } else {
        CardTone::Plain
    };
    column![
        container(kit::section_label(tr("Sauvegarde"))).padding([0, 4]),
        container(body).padding(18).width(Fill).style(toned_card(tone)),
    ]
    .spacing(10)
    .into()
}

/// A list of explanations with bullets, in a pop-up.
fn bullets_modal<'a>(title: String, lines: Vec<String>, max_height: f32) -> Element<'a, Message> {
    let body = column(lines.into_iter().map(|l| {
        row![
            text("•").size(15).style(|t: &iced::Theme| text::Style {
                color: Some(theme::nt(t).accent),
            }),
            text(l).size(14).width(Fill),
        ]
        .spacing(10)
        .into()
    }))
    .spacing(12);
    kit::overlay(
        kit::dialog(
            kit::dialog_head(title, None, None, Message::OpenModal(None)),
            body,
            Some(row![Space::new().width(Fill), primary(tr("Compris"), Some(Message::OpenModal(None)))].into()),
            520.0,
            max_height,
        ),
        Some(Message::OpenModal(None)),
    )
}

pub(super) fn sync_info_modal<'a>(max_height: f32) -> Element<'a, Message> {
    bullets_modal(
        tr("La synchronisation"),
        vec![
            tr("Hôtes, clés, identités, snippets, tunnels et hôtes connus sont partagés entre vos appareils."),
            tr("Sur chaque appareil : le même emplacement et le même mot de passe. Un dossier Google Drive, OneDrive ou Syncthing convient aussi."),
            tr("La modification la plus récente l'emporte, suppressions comprises."),
            tr("Le fichier synchronisé sert aussi de sauvegarde ; une copie par jour reste sur cet ordinateur."),
            tr("Les clés API et les réglages d'affichage restent propres à chaque appareil."),
        ],
        max_height,
    )
}

pub(super) fn lan_info_modal<'a>(max_height: f32) -> Element<'a, Message> {
    bullets_modal(
        tr("Sur le Wi-Fi, sans cloud"),
        vec![
            tr("Vos appareils échangent directement quand ils sont sur le même réseau : rien ne passe par Internet ni par un cloud."),
            tr("Associez-les une fois avec un code à 6 chiffres ; ensuite ils se retrouvent tout seuls, à l'ouverture de l'app et après chaque modification."),
            tr("La liaison est chiffrée (X25519, AES-256-GCM) et seuls les appareils associés sont acceptés."),
            tr("Elle se combine avec la synchro par fichier : la modification la plus récente l'emporte partout."),
        ],
        max_height,
    )
}

/// « Associer un appareil »: show a code here, or type the one shown on the other device.
pub(super) fn lan_pair_modal(app: &App, max_height: f32) -> Element<'_, Message> {
    use crate::backup::BackupMsg as B;
    let st = &app.backup;
    let n = theme::nt(&app.theme());
    let close = Message::OpenModal(None);
    let (title, back, body): (String, Option<Message>, Element<Message>) = if let Some(code) = &st.lan_code {
        (
            tr("Afficher un code"),
            Some(bm(B::LanCancelCode)),
            column![
                soft(tr("Tapez ce code sur l'autre appareil, dans « Associer » › « Saisir un code ».")).size(14),
                container(
                    text(format!("{} {}", &code[..3], &code[3..]))
                        .font(MONO)
                        .size(44)
                        .style(|t: &iced::Theme| text::Style {
                            color: Some(theme::nt(t).accent),
                        })
                )
                .padding(18)
                .center_x(Fill),
                soft(tr("Il change à chaque association et ne sert qu'une fois.")),
            ]
            .spacing(12)
            .into(),
        )
    } else if st.lan_join {
        let paired: Vec<&str> = app.settings.lan_peers.iter().map(|p| p.id.as_str()).collect();
        let others: Vec<_> = st.lan_found.iter().filter(|f| !paired.contains(&f.id.as_str())).collect();
        let mut c = column![soft(tr("Les appareils à proximité qui affichent un code :")).size(14)].spacing(12);
        if others.is_empty() {
            c = c.push(soft(tr(
                "Recherche… Ouvrez Nautile sur l'autre appareil et choisissez « Afficher un code ».",
            )));
        }
        let rows: Vec<Element<Message>> = others
            .into_iter()
            .map(|f| {
                let pairing = st.lan_pairing.as_ref().filter(|(p, _)| p.id == f.id);
                match pairing {
                    Some((_, code)) => row![
                        kit::icon_tile(I_PHONE, n.blue),
                        text(f.name.clone()).size(15).font(BOLD).width(Fill),
                        container(
                            text_input(&tr("Code à 6 chiffres"), code)
                                .on_input(|v| bm(B::LanCode(v)))
                                .on_submit(bm(B::LanPair))
                                .font(MONO)
                                .padding([9, 12])
                                .size(15)
                                .style(theme::input)
                        )
                        .width(150),
                        primary(tr("Associer"), (code.len() == 6 && !st.lan_busy).then_some(bm(B::LanPair))),
                    ]
                    .spacing(12)
                    .padding([10, 14])
                    .align_y(Alignment::Center)
                    .into(),
                    None => button(
                        row![
                            kit::icon_tile(I_PHONE, n.blue),
                            text(f.name.clone()).size(15).font(BOLD).width(Fill),
                            icon(I_CHEVRON_RIGHT).size(22),
                        ]
                        .spacing(12)
                        .align_y(Alignment::Center),
                    )
                    .width(Fill)
                    .padding([10, 14])
                    .style(theme::card_pick(false))
                    .on_press(bm(B::LanPairWith(f.clone())))
                    .into(),
                }
            })
            .collect();
        if !rows.is_empty() {
            c = c.push(kit::card(rows));
        }
        if let Some(m) = &st.lan_status {
            c = c.push(soft(m.clone()));
        }
        (tr("Saisir un code"), Some(bm(B::LanJoin(false))), c.into())
    } else {
        (
            tr("Associer un appareil"),
            None,
            column![
                kit::kind_row(
                    I_PHONE,
                    n.accent,
                    tr("Afficher un code"),
                    tr("Cet ordinateur montre un code à taper sur l'autre appareil"),
                    bm(B::LanShowCode)
                ),
                kit::kind_row(
                    I_KEYBOARD,
                    n.blue,
                    tr("Saisir un code"),
                    tr("L'autre appareil montre le code, vous le tapez ici"),
                    bm(B::LanJoin(true))
                ),
            ]
            .spacing(2)
            .into(),
        )
    };
    kit::overlay(
        kit::dialog(kit::dialog_head(title, None, back, close), body, None, 560.0, max_height),
        None,
    )
}

/// « Ajouter un emplacement »: its kind, then its form.
pub(super) fn add_place_modal(app: &App, max_height: f32) -> Element<'_, Message> {
    use crate::backup::{BackupMsg as B, PlaceField as F, PlaceKind};
    let n = theme::nt(&app.theme());
    let close = Message::Backup(B::CancelPlace);
    let Some(p) = &app.backup.place else {
        let body = column![
            kit::kind_row(
                I_FOLDER,
                n.accent,
                tr("Dossier"),
                tr("Un dossier de cet ordinateur : Google Drive, OneDrive, Nextcloud ou Syncthing pour ordinateur, clé USB…"),
                bm(B::NewPlace(PlaceKind::Folder))
            ),
            kit::kind_row(
                I_HOSTS,
                n.blue,
                "Nextcloud".into(),
                tr("WebDAV, auto-hébergé (Nextcloud, ownCloud, Apache…)"),
                bm(B::NewPlace(PlaceKind::WebDav))
            ),
            kit::kind_row(
                I_INVENTORY,
                n.green,
                tr("Stockage S3"),
                tr("Backblaze B2, Cloudflare R2, AWS, MinIO, RustFS…"),
                bm(B::NewPlace(PlaceKind::S3))
            ),
        ]
        .spacing(2);
        return kit::overlay(
            kit::dialog(
                kit::dialog_head(
                    tr("Ajouter un emplacement"),
                    Some(tr("Où garder la sauvegarde ?")),
                    None,
                    Message::OpenModal(None),
                ),
                body,
                None,
                560.0,
                max_height,
            ),
            Some(Message::OpenModal(None)),
        );
    };
    let field_of = |name: String, ph: &str, v: &str, f: fn(String) -> F| {
        kit::float_field(name, text_input(ph, v).on_input(move |x| bm(B::Place(f(x)))))
    };
    let mut form = column![].spacing(14);
    let title = match p.kind {
        PlaceKind::Folder => {
            form = form.push(
                row![
                    field_of(tr("Dossier"), "/home/moi/Nextcloud/Nautile", &p.path, F::Path),
                    secondary(tr("Choisir…"), bm(B::PickFolder)),
                ]
                .spacing(10)
                .align_y(Alignment::End),
            );
            form = form.push(soft(tr(
                "Le dossier d'un client de synchronisation (Google Drive, OneDrive, Nextcloud, Syncthing) emporte la sauvegarde ailleurs.",
            )));
            tr("Dossier")
        }
        PlaceKind::WebDav => {
            form = form
                .push(field_of(
                    tr("Adresse WebDAV"),
                    "https://cloud.example.org/remote.php/dav/files/moi/Nautile/",
                    &p.url,
                    F::Url,
                ))
                .push(
                    row![
                        field_of(tr("Utilisateur"), "moi", &p.username, F::Username),
                        kit::float_field(
                            tr("Mot de passe"),
                            text_input(&tr("Mot de passe d'application"), &p.secret)
                                .on_input(|x| bm(B::Place(F::Secret(x))))
                                .secure(true)
                        ),
                    ]
                    .spacing(10),
                )
                .push(soft(tr("Nextcloud : Paramètres › Sécurité › mot de passe d'application.")));
            "Nextcloud / WebDAV".into()
        }
        PlaceKind::S3 => {
            let preset = |name: &str, endpoint: &str, region: &str| -> Element<Message> {
                button(text(name.to_owned()).size(13))
                    .padding([6, 12])
                    .style(theme::count_chip(p.endpoint == endpoint, None))
                    .on_press(Message::Batch(vec![
                        bm(B::Place(F::Endpoint(endpoint.to_owned()))),
                        bm(B::Place(F::Region(region.to_owned()))),
                    ]))
                    .into()
            };
            form = form.push(
                row![
                    preset("Backblaze B2", "s3.eu-central-003.backblazeb2.com", "eu-central-003"),
                    preset("Cloudflare R2", "ID-DU-COMPTE.r2.cloudflarestorage.com", "auto"),
                    preset("AWS", "s3.eu-west-3.amazonaws.com", "eu-west-3"),
                    preset("MinIO", "http://192.168.1.10:9000", "us-east-1"),
                ]
                .spacing(8)
                .wrap(),
            );
            form = form
                .push(
                    row![
                        field_of(tr("Adresse"), "s3.eu-central-003.backblazeb2.com", &p.endpoint, F::Endpoint),
                        container(field_of(tr("Région"), "us-east-1", &p.region, F::Region)).width(170),
                    ]
                    .spacing(10),
                )
                .push(
                    row![
                        field_of("Bucket".into(), "nautile", &p.bucket, F::Bucket),
                        field_of(tr("Chemin du fichier"), "nautile.backup", &p.key, F::Key),
                    ]
                    .spacing(10),
                )
                .push(
                    row![
                        field_of(tr("Clé d'accès (ID)"), "", &p.access_key, F::AccessKey),
                        kit::float_field(
                            tr("Clé secrète"),
                            text_input("", &p.secret).on_input(|x| bm(B::Place(F::Secret(x)))).secure(true)
                        ),
                    ]
                    .spacing(10),
                );
            tr("Stockage S3")
        }
    };
    if let Some(e) = &p.error {
        form = form.push(pink(e.clone()));
    }
    let foot = row![
        Space::new().width(Fill),
        secondary(tr("Annuler"), close.clone()),
        primary(
            if p.busy {
                tr("Vérification…")
            } else {
                tr("Vérifier et ajouter")
            },
            (!p.busy && p.dest().is_some()).then_some(bm(B::AddPlace))
        ),
    ]
    .spacing(10);
    kit::overlay(
        kit::dialog(
            kit::dialog_head(title, None, Some(close.clone()), Message::OpenModal(None)),
            form,
            Some(foot.into()),
            640.0,
            max_height,
        ),
        None,
    )
}

/// The settings of one place: automatic backups and their schedule, sync, removal.
pub(super) fn target_modal(app: &App, max_height: f32) -> Element<'_, Message> {
    use crate::backup::{BackupMsg as B, Frequency};
    let close = bm(B::Open(None));
    let Some(t) = app
        .backup
        .open
        .as_ref()
        .and_then(|id| app.settings.targets.iter().find(|t| &t.id == id))
    else {
        return Space::new().into();
    };
    let id = t.id.clone();
    let now = nautile_core::model::now_ms();
    let syncing = app.settings.sync_enabled && app.settings.sync_target.as_ref() == Some(&t.id);
    let mut body = column![].spacing(16);
    if syncing {
        body = body.push(soft(tr(
            "C'est le fichier de synchronisation : il est mis à jour à chaque synchronisation, une copie par jour reste sur cet ordinateur.",
        )));
    } else {
        let summary = if t.auto {
            let next = crate::backup::next_run(t.frequency, t.time, t.weekday, t.last, now);
            trf("%1$s · prochaine : %2$s", &[&t.frequency.label(), &when(next)])
        } else {
            tr("Seulement quand vous le demandez")
        };
        let idv = id.clone();
        body = body.push(kit::card(vec![kit::toggle_row(
            None,
            tr("Sauvegarde automatique"),
            summary,
            t.auto,
            move |v| bm(B::Auto(idv.clone(), v)),
        )]));
        if t.auto {
            let idf = id.clone();
            body = body.push(field(
                tr("Fréquence"),
                segmented(
                    Frequency::ALL
                        .iter()
                        .map(|f| {
                            let short = match f {
                                Frequency::Hours6 => "6 h".to_owned(),
                                Frequency::Hours12 => "12 h".to_owned(),
                                Frequency::Day => tr("Jour"),
                                Frequency::Week => tr("Semaine"),
                                Frequency::Month => tr("Mois"),
                            };
                            (*f, short)
                        })
                        .collect(),
                    t.frequency,
                    move |f| bm(B::Frequency(idf.clone(), f)),
                ),
            ));
            if t.frequency.at_time() {
                let idt = id.clone();
                let mut r = row![container(kit::float_field(
                    tr("Heure"),
                    text_input("03:00", &format!("{:02}:{:02}", t.time / 60, t.time % 60))
                        .on_input(move |v| bm(B::Time(idt.clone(), v)))
                        .font(MONO)
                ))
                .width(140)]
                .spacing(14)
                .align_y(Alignment::End);
                if t.frequency == Frequency::Week {
                    let days = [tr("L"), tr("Ma"), tr("Me"), tr("J"), tr("V"), tr("S"), tr("D")];
                    let mut d = row![].spacing(6);
                    for (i, name) in days.into_iter().enumerate() {
                        let day = i as u32 + 1;
                        let selected = t.weekday == day;
                        d = d.push(
                            button(text(name).size(13).font(BOLD).center().width(Fill))
                                .width(40)
                                .height(40)
                                .padding(0)
                                .style(move |th: &iced::Theme, s| {
                                    let mut b = theme::segment(selected)(th, s);
                                    b.border.radius = 20.0.into();
                                    if !selected {
                                        b.border.width = 1.0;
                                        b.border.color = theme::nt(th).border;
                                    }
                                    b
                                })
                                .on_press(bm(B::Weekday(id.clone(), day))),
                        );
                    }
                    r = r.push(column![label(tr("Jour")), d].spacing(6));
                }
                body = body.push(r);
            }
        }
    }
    let mut more: Vec<Element<Message>> = Vec::new();
    if !syncing {
        more.push(
            button(
                row![kit::icon(I_SYNC, 20.0), text(tr("Utiliser pour la synchronisation")).size(15)]
                    .spacing(12)
                    .align_y(Alignment::Center),
            )
            .width(Fill)
            .padding([14, 16])
            .style(theme::card_pick(false))
            .on_press(Message::Batch(vec![bm(B::SyncTarget(id.clone())), bm(B::SyncEnabled(true))]))
            .into(),
        );
    }
    more.push(
        button(
            row![kit::icon(I_TRASH, 20.0), text(tr("Retirer cet emplacement")).size(15)]
                .spacing(12)
                .align_y(Alignment::Center),
        )
        .width(Fill)
        .padding([14, 16])
        .style(|t: &iced::Theme, st| {
            let mut b = theme::card_pick(false)(t, st);
            b.text_color = theme::nt(t).pink;
            b
        })
        .on_press(bm(B::Remove(id.clone())))
        .into(),
    );
    body = body.push(kit::card(more));
    let foot = row![
        Space::new().width(Fill),
        primary(
            if syncing {
                tr("Synchroniser maintenant")
            } else {
                tr("Sauvegarder ici maintenant")
            },
            Some(if syncing { bm(B::SyncNow) } else { bm(B::Now(id.clone())) })
        ),
    ];
    let head = row![
        kit::icon_tile(place_icon(&t.dest), theme::nt(&app.theme()).accent),
        column![
            text(place_kind(&t.dest)).size(20).font(BOLD),
            text(t.dest.describe()).font(MONO).size(12).style(|th: &iced::Theme| text::Style {
                color: Some(theme::nt(th).muted),
            }),
        ]
        .spacing(2)
        .width(Fill),
        button(kit::icon('\u{E5CD}', 22.0))
            .padding(6)
            .style(theme::ghost)
            .on_press(close.clone()),
    ]
    .spacing(14)
    .align_y(Alignment::Center);
    kit::overlay(
        kit::dialog(
            container(head)
                .padding(Padding {
                    top: 18.0,
                    right: 14.0,
                    bottom: 10.0,
                    left: 22.0,
                })
                .into(),
            body,
            Some(foot.into()),
            620.0,
            max_height,
        ),
        Some(close),
    )
}

/// The backup password: chosen once, the same on every device.
pub(super) fn password_modal(app: &App, max_height: f32) -> Element<'_, Message> {
    use crate::backup::BackupMsg as B;
    let st = &app.backup;
    let has = app.has_backup_password();
    let ok = st.password.chars().count() >= 8 && st.password == st.password_confirm;
    let body = column![
        soft(if has {
            tr("Le mot de passe de sauvegarde est enregistré sur cet ordinateur : les sauvegardes automatiques et la synchronisation s'en servent.")
        } else {
            tr("Choisissez le mot de passe des sauvegardes (le même que sur vos autres appareils pour la synchronisation).")
        })
        .size(14),
        kit::float_field(
            if has { tr("Nouveau mot de passe") } else { tr("Mot de passe (8 caractères minimum)") },
            text_input("", &st.password).on_input(|v| bm(B::PasswordInput(v))).secure(true)
        ),
        kit::float_field(
            tr("Confirmer"),
            text_input("", &st.password_confirm)
                .on_input(|v| bm(B::PasswordConfirm(v)))
                .on_submit(bm(B::SavePassword))
                .secure(true)
        ),
        label(tr("Sans lui, une sauvegarde ne peut pas être ouverte : notez-le dans votre gestionnaire de mots de passe.")),
    ]
    .spacing(14);
    let foot = row![
        Space::new().width(Fill),
        secondary(tr("Annuler"), Message::OpenModal(None)),
        primary(
            tr("Enregistrer"),
            ok.then_some(Message::Batch(vec![bm(B::SavePassword), Message::OpenModal(None)]))
        ),
    ]
    .spacing(10);
    kit::overlay(
        kit::dialog(
            kit::dialog_head(tr("Mot de passe de sauvegarde"), None, None, Message::OpenModal(None)),
            body,
            Some(foot.into()),
            520.0,
            max_height,
        ),
        None,
    )
}

/// « Exporter une copie dans un fichier » with its own password.
pub(super) fn export_modal(app: &App, max_height: f32) -> Element<'_, Message> {
    let ex = &app.export;
    let ok = ex.password.chars().count() >= 8 && ex.password == ex.confirm && !ex.busy;
    let body = column![
        soft(tr(
            "Un fichier chiffré (Argon2id + AES-256), à rouvrir sur un autre appareil avec ce mot de passe."
        ))
        .size(14),
        kit::float_field(
            tr("Mot de passe (8 caractères minimum)"),
            text_input("", &ex.password).on_input(Message::ExportPassword).secure(true)
        ),
        kit::float_field(
            tr("Confirmer"),
            text_input("", &ex.confirm)
                .on_input(Message::ExportConfirm)
                .on_submit(Message::ExportRun)
                .secure(true)
        ),
    ]
    .spacing(14);
    let foot = row![
        Space::new().width(Fill),
        secondary(tr("Annuler"), Message::OpenModal(None)),
        primary(
            if ex.busy { tr("Chiffrement…") } else { tr("Exporter…") },
            ok.then_some(Message::ExportRun)
        ),
    ]
    .spacing(10);
    kit::overlay(
        kit::dialog(
            kit::dialog_head(tr("Exporter une copie"), None, None, Message::OpenModal(None)),
            body,
            Some(foot.into()),
            520.0,
            max_height,
        ),
        None,
    )
}

/// « Restaurer » a chosen backup file: its password, then everything is merged.
pub(super) fn restore_modal(app: &App, max_height: f32) -> Element<'_, Message> {
    let imp = &app.import;
    let name = imp.file.as_ref().map(|f| f.0.clone()).unwrap_or_default();
    let body = column![
        row![
            kit::icon_tile(I_DOWNLOAD_FILE, theme::nt(&app.theme()).blue),
            text(name).size(15).font(MONO)
        ]
        .spacing(12)
        .align_y(Alignment::Center),
        soft(tr(
            "Un fichier nautile.backup de votre téléphone ou d'un autre ordinateur : tout est fusionné, rien n'est effacé ici."
        )),
        kit::float_field(
            tr("Mot de passe de la sauvegarde"),
            text_input("", &imp.password)
                .on_input(Message::ImportPassword)
                .on_submit(Message::ImportRun)
                .secure(true)
        ),
    ]
    .spacing(14);
    let foot = row![
        Space::new().width(Fill),
        secondary(tr("Annuler"), Message::ImportCancel),
        primary(
            if imp.busy { tr("Déchiffrement…") } else { tr("Restaurer") },
            (!imp.busy && !imp.password.is_empty()).then_some(Message::ImportRun)
        ),
    ]
    .spacing(10);
    kit::overlay(
        kit::dialog(
            kit::dialog_head(tr("Restaurer une sauvegarde"), None, None, Message::ImportCancel),
            body,
            Some(foot.into()),
            540.0,
            max_height,
        ),
        None,
    )
}

/// The window, the notification area, starting with the session and notifications.
pub(super) fn system_settings(app: &App) -> Column<'_, Message> {
    let s = &app.settings;
    let tray_note = if app.tray {
        tr("L'icône de Nautile est dans la zone de notification : un clic rouvre la fenêtre, son menu ouvre un terminal, synchronise, verrouille ou quitte.")
    } else {
        tr("Ce bureau n'a pas de zone de notification (sous GNOME, l'extension AppIndicator l'ajoute) : la fenêtre est réduite au lieu d'être cachée.")
    };
    column![
        column![
            container(kit::section_label(tr("Fenêtre"))).padding([0, 4]),
            kit::card(vec![kit::toggle_row(
                None,
                tr("Fermer réduit dans la zone de notification"),
                tr("Les sessions, tunnels et synchronisations continuent ; « Quitter » dans le menu de l'icône ferme vraiment."),
                s.close_to_tray(),
                Message::CloseToTray
            )]),
            soft(tray_note),
        ]
        .spacing(10),
        column![
            container(kit::section_label(tr("Démarrage"))).padding([0, 4]),
            kit::card(vec![kit::toggle_row(
                None,
                tr("Lancer au démarrage de la session"),
                tr("Nautile démarre réduit dans la zone de notification : la synchronisation et les sauvegardes automatiques tournent."),
                crate::autostart::is_on(),
                Message::Autostart
            )]),
        ]
        .spacing(10),
        update_section(app),
        column![
            container(kit::section_label(tr("Notifications"))).padding([0, 4]),
            kit::card(vec![kit::toggle_row(
                None,
                tr("Notifications du système"),
                tr("Quand la fenêtre est cachée : sauvegarde ou synchronisation échouée, tunnel coupé, mise à jour disponible."),
                s.notifications(),
                Message::Notifications
            )]),
        ]
        .spacing(10),
    ]
}

/// « Nautile vX est disponible »: what is new, and « Télécharger et installer ».
pub(super) fn update_modal(app: &App, max_height: f32) -> Element<'_, Message> {
    use crate::update::UpdateMsg as U;
    let um = Message::Update;
    let st = &app.updates;
    let Some(r) = &st.available else { return Space::new().into() };
    let has_file = nautile_net::update::asset_for(r, crate::update::target(app.paths.portable)).is_some();
    let mut body = column![].spacing(14);
    if st.notes.is_empty() {
        body = body.push(soft(tr("Pas de notes pour cette version.")));
    } else {
        body =
            body.push(iced::widget::markdown::view(&st.notes, md_settings(app)).map(|u| Message::Ai(crate::assistant::AiMsg::OpenLink(u))));
    }
    if !has_file {
        body = body.push(label(tr(
            "Pas de fichier pour cet ordinateur dans cette version : la page de la version s'ouvre dans le navigateur.",
        )));
    }
    if let Some((done, total)) = st.progress {
        let pct = if total > 0 { done as f32 * 100.0 / total as f32 } else { 0.0 };
        body = body.push(
            column![
                iced::widget::progress_bar(0.0..=100.0, pct).girth(8).style(|t: &iced::Theme| {
                    iced::widget::progress_bar::Style {
                        background: theme::nt(t).key_bg.into(),
                        bar: theme::nt(t).accent.into(),
                        border: iced::border::rounded(4),
                    }
                }),
                soft(format!(
                    "{} / {}",
                    nautile_net::sftp::human_size(done),
                    nautile_net::sftp::human_size(total)
                )),
            ]
            .spacing(6),
        );
    }
    match &st.status {
        Some(Ok(s)) if st.progress.is_none() && !s.is_empty() && st.available.is_some() => body = body.push(green(s.clone())),
        Some(Err(e)) => body = body.push(pink(e.clone())),
        _ => {}
    }
    let busy = st.progress.is_some();
    let foot = row![
        button(text(tr("Ignorer cette version")).size(14))
            .padding([9, 4])
            .style(theme::ghost)
            .on_press_maybe((!busy).then_some(um(U::Skip))),
        Space::new().width(Fill),
        secondary(tr("Plus tard"), um(U::Later)),
        primary(
            if busy {
                tr("Téléchargement…")
            } else if has_file {
                tr("Télécharger et installer")
            } else {
                tr("Ouvrir la page")
            },
            (!busy).then_some(um(if has_file { U::Download } else { U::OpenPage }))
        ),
    ]
    .spacing(10)
    .align_y(Alignment::Center);
    kit::overlay(
        kit::dialog(
            kit::dialog_head(
                trf("Nautile %1$s est disponible", &[&r.tag_name]),
                Some(trf("Vous avez la version %1$s", &[&env!("CARGO_PKG_VERSION")])),
                None,
                um(U::Later),
            ),
            body,
            Some(foot.into()),
            620.0,
            max_height,
        ),
        None,
    )
}

/// « Mises à jour »: the version here, the check at start, « Vérifier maintenant ».
fn update_section(app: &App) -> Element<'_, Message> {
    use crate::update::UpdateMsg as U;
    let st = &app.updates;
    let status: Element<Message> = if st.checking {
        soft(tr("Recherche…")).into()
    } else {
        match &st.status {
            Some(Ok(s)) => green(s.clone()).into(),
            Some(Err(e)) => pink(e.clone()).size(13).into(),
            None => soft(trf("Version %1$s", &[&env!("CARGO_PKG_VERSION")])).into(),
        }
    };
    let mut now = row![
        column![text(trf("Nautile %1$s", &[&env!("CARGO_PKG_VERSION")])).size(15).font(BOLD), status]
            .spacing(3)
            .width(Fill),
    ]
    .spacing(10)
    .padding([14, 14])
    .align_y(Alignment::Center);
    if st.available.is_some() {
        now = now.push(primary(tr("Voir"), Some(Message::OpenModal(Some(crate::app::Modal::Update)))));
    }
    now = now.push(secondary(tr("Vérifier maintenant"), Message::Update(U::Check(true))));
    column![
        container(kit::section_label(tr("Mises à jour"))).padding([0, 4]),
        kit::card(vec![
            kit::toggle_row(
                None,
                tr("Vérifier au démarrage"),
                tr("Une fois par jour au plus, sur GitHub (MrDDream/Nautile) ; rien n'est installé sans votre accord."),
                app.settings.update_check(),
                |v| Message::Update(U::AutoCheck(v))
            ),
            now.into(),
        ]),
    ]
    .spacing(10)
    .into()
}

/// RDP and VNC: how the desktop is drawn, its size, the clipboard, full screen.
pub(super) fn remote_settings(app: &App) -> Column<'_, Message> {
    let s = &app.settings;
    column![
        column![
            container(kit::section_label(tr("Affichage"))).padding([0, 4]),
            kit::card(vec![
                kit::toggle_row(
                    None,
                    tr("Taille réelle"),
                    tr("Le bureau n'est jamais agrandi au-delà de sa taille : plus net, avec des marges. Sinon il remplit la fenêtre."),
                    s.remote_no_upscale,
                    Message::RemoteNoUpscale
                ),
                kit::toggle_row(
                    None,
                    tr("Plein écran à l'ouverture"),
                    tr("F11 quitte (et remet) le plein écran."),
                    s.remote_fullscreen,
                    Message::RemoteFullscreen
                ),
            ]),
        ]
        .spacing(10),
        column![
            container(kit::section_label("RDP".into())).padding([0, 4]),
            kit::card(vec![kit::toggle_row(
                None,
                tr("À la taille de la fenêtre"),
                tr("Sans résolution choisie pour l'hôte, le bureau Windows prend la taille de la fenêtre de Nautile (sinon 1600×900)."),
                s.rdp_window_size(),
                Message::RdpWindowSize
            )]),
        ]
        .spacing(10),
        column![
            container(kit::section_label(tr("Presse-papier"))).padding([0, 4]),
            kit::card(vec![kit::toggle_row(
                None,
                tr("Copier ici ce qui est copié là-bas"),
                tr("VNC : le texte copié sur le bureau distant arrive dans le presse-papier de cet ordinateur. Ctrl+Maj+V envoie le vôtre."),
                s.remote_clipboard(),
                Message::RemoteClipboard
            )]),
        ]
        .spacing(10),
    ]
}
