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
            icon: I_TERMINAL,
            tint: n.blue,
            title: tr("Terminal"),
            detail: format!("JetBrains Mono {} · {}", s.terminal_font(), tr(term.name)),
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
        P::Ai => (tr("Assistant IA"), column![settings_card(tr("Fournisseur"), ai_settings(app))]),
        P::Backup => (tr("Sauvegarde et synchro"), backup_settings(app)),
        P::Security => (tr("Sécurité"), column![settings_card(tr("Verrouillage"), lock_settings(app))]),
        P::Data => (tr("Données"), data_settings(app)),
    };
    let head = container(kit::page_header(name, Some(Message::SettingsPage(None)), vec![])).padding(Padding {
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

pub(super) fn terminal_settings(app: &App) -> Column<'_, Message> {
    let s = &app.settings;
    let current = themes::by_id(s.terminal_theme());
    let swatches = row(themes::all().into_iter().map(|t| {
        let selected = t.id == current.id;
        let (bg, fg, accent) = (t.background, t.foreground, t.ansi[2]);
        let id = t.id.to_owned();
        button(
            column![
                text("$ ls").font(MONO).size(12).color(fg),
                text("~/docker").font(MONO).size(12).color(accent),
            ]
            .spacing(2),
        )
        .padding([8, 10])
        .width(86)
        .style(move |th: &iced::Theme, _| button::Style {
            background: Some(bg.into()),
            border: iced::Border {
                color: if selected { theme::nt(th).accent } else { theme::nt(th).border },
                width: if selected { 2.0 } else { 1.0 },
                radius: 10.0.into(),
            },
            ..Default::default()
        })
        .on_press(Message::TermTheme(id))
        .into()
    }))
    .spacing(8)
    .wrap();
    let size = s.terminal_font();
    column![
        settings_card(trf("Couleurs · %1$s", &[&tr(current.name)]), swatches),
        settings_card(
            tr("Police"),
            row![
                column![
                    text("JetBrains Mono").size(15).font(BOLD),
                    soft(trf("Taille du texte · %1$spx", &[&size])),
                ]
                .spacing(3)
                .width(Fill),
                secondary("A−".into(), Message::TermFont(size - 1.0)),
                secondary("A+".into(), Message::TermFont(size + 1.0)),
            ]
            .spacing(8)
            .align_y(Alignment::Center)
        ),
        settings_card(
            tr("Raccourcis"),
            soft(tr("Ctrl+Maj+C / Ctrl+Maj+V pour copier-coller, clic droit pour copier la sélection ou coller. Ctrl+Maj+T ouvre un terminal local, Ctrl+Tab change d'onglet.")).size(14)
        ),
    ]
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

pub(super) fn backup_settings(app: &App) -> Column<'_, Message> {
    let imp = &app.import;
    let mut restore = column![soft(tr(
        "Un fichier nautile.backup de votre téléphone ou d'un autre ordinateur : tout est fusionné, rien n'est effacé ici."
    ))]
    .spacing(12);
    match &imp.file {
        None => restore = restore.push(row![secondary(tr("Choisir le fichier…"), Message::ImportPick)]),
        Some((name, _)) => {
            restore = restore
                .push(
                    row![icon(I_FOLDER), text(name.clone()).size(14)]
                        .spacing(8)
                        .align_y(Alignment::Center),
                )
                .push(
                    row![
                        kit::float_field(
                            tr("Mot de passe de la sauvegarde"),
                            text_input("", &imp.password)
                                .on_input(Message::ImportPassword)
                                .on_submit(Message::ImportRun)
                                .secure(true)
                        ),
                        primary(
                            if imp.busy { tr("Déchiffrement…") } else { tr("Restaurer") },
                            (!imp.busy && !imp.password.is_empty()).then_some(Message::ImportRun)
                        ),
                    ]
                    .spacing(10)
                    .align_y(Alignment::End),
                );
        }
    }

    let ex = &app.export;
    let ok = ex.password.chars().count() >= 8 && ex.password == ex.confirm && !ex.busy;
    let export = column![
        soft(tr(
            "Un fichier chiffré (Argon2id + AES-256), à rouvrir sur un autre appareil avec ce mot de passe."
        )),
        row![
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
            primary(
                if ex.busy { tr("Chiffrement…") } else { tr("Exporter…") },
                ok.then_some(Message::ExportRun)
            ),
        ]
        .spacing(10)
        .align_y(Alignment::End),
    ]
    .spacing(12);

    column![
        settings_card(tr("Synchronisation"), sync_settings(app)),
        settings_card(tr("Synchronisation Wi-Fi"), lan_settings(app)),
        settings_card(tr("Sauvegarde"), backup_places(app)),
        settings_card(tr("Restaurer"), restore),
        settings_card(tr("Exporter"), export),
    ]
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
        _ => I_CLOUD,
    }
}

/// The backup password, the places and their schedules.
pub(super) fn backup_places(app: &App) -> Element<'_, Message> {
    use crate::backup::{BackupMsg as B, Frequency, PlaceField as F, PlaceKind};
    let st = &app.backup;
    let mut c = column![].spacing(12);

    // Password.
    let has = app.has_backup_password();
    c = c.push(soft(if has {
        tr("Le mot de passe de sauvegarde est enregistré sur cet ordinateur : les sauvegardes automatiques et la synchronisation s'en servent.")
    } else {
        tr("Choisissez le mot de passe des sauvegardes (le même que sur vos autres appareils pour la synchronisation).")
    }));
    let ok = st.password.chars().count() >= 8 && st.password == st.password_confirm;
    c = c.push(
        row![
            text_input(
                &if has {
                    tr("Nouveau mot de passe")
                } else {
                    tr("Mot de passe (8 caractères minimum)")
                },
                &st.password
            )
            .on_input(|v| bm(B::PasswordInput(v)))
            .secure(true)
            .padding([9, 12])
            .size(14)
            .style(theme::input),
            text_input(&tr("Confirmer"), &st.password_confirm)
                .on_input(|v| bm(B::PasswordConfirm(v)))
                .on_submit(bm(B::SavePassword))
                .secure(true)
                .padding([9, 12])
                .size(14)
                .style(theme::input),
            primary(tr("Enregistrer"), ok.then_some(bm(B::SavePassword))),
        ]
        .spacing(10),
    );

    // Places.
    c = c.push(label(tr("EMPLACEMENTS")));
    let now = nautile_core::model::now_ms();
    for t in &app.settings.targets {
        let syncing = app.settings.sync_enabled && app.settings.sync_target.as_ref() == Some(&t.id);
        let running = st.running.contains(&t.id) || (syncing && st.syncing);
        let status = if running {
            soft(tr("En cours…"))
        } else if let Some(e) = &t.error {
            pink(e.clone())
        } else if let Some(l) = t.last {
            green(trf("Dernière sauvegarde : %1$s", &[&when(l)]))
        } else {
            soft(tr("Jamais sauvegardé ici"))
        };
        let mut info = column![text(t.dest.describe()).size(14).font(MONO), status].spacing(4);
        if syncing {
            info = info.push(label(tr("Synchronisé avec vos autres appareils")));
        } else if t.auto {
            let next = crate::backup::next_run(t.frequency, t.time, t.weekday, t.last, now);
            info = info.push(label(trf("%1$s · prochaine : %2$s", &[&t.frequency.label(), &when(next)])));
        }
        let open = st.open.as_ref() == Some(&t.id);
        let id = t.id.clone();
        let head = row![
            icon(place_icon(&t.dest)),
            info.width(Fill),
            secondary(tr("Sauvegarder"), bm(B::Now(id.clone()))),
            icon_button(I_SETTINGS, bm(B::Open(if open { None } else { Some(id.clone()) }))),
        ]
        .spacing(12)
        .align_y(Alignment::Center);
        let mut card = column![head].spacing(12);
        if open {
            let mut opts = column![switch(tr("Sauvegarde automatique (tant que Nautile est ouvert)"), t.auto, {
                let id = id.clone();
                move |v| bm(B::Auto(id.clone(), v))
            })]
            .spacing(10);
            if t.auto {
                let idf = id.clone();
                opts = opts.push(
                    pick_list(
                        Frequency::ALL.map(FreqChoice),
                        Some(FreqChoice(t.frequency)),
                        move |f: FreqChoice| bm(B::Frequency(idf.clone(), f.0)),
                    )
                    .padding([7, 12])
                    .style(theme::select),
                );
                if t.frequency.at_time() {
                    let idt = id.clone();
                    let mut r = row![
                        label(tr("À")),
                        text_input("03:00", &format!("{:02}:{:02}", t.time / 60, t.time % 60))
                            .on_input(move |v| bm(B::Time(idt.clone(), v)))
                            .width(90)
                            .padding([7, 10])
                            .size(13)
                            .style(theme::input),
                    ]
                    .spacing(8)
                    .align_y(Alignment::Center);
                    if t.frequency == Frequency::Week {
                        let days = [
                            tr("lundi"),
                            tr("mardi"),
                            tr("mercredi"),
                            tr("jeudi"),
                            tr("vendredi"),
                            tr("samedi"),
                            tr("dimanche"),
                        ];
                        let choices: Vec<DayChoice> = (1..=7).map(|d| DayChoice(d, days[d as usize - 1].clone())).collect();
                        let current = choices.iter().find(|d| d.0 == t.weekday).cloned();
                        let idw = id.clone();
                        r = r.push(
                            pick_list(choices, current, move |d: DayChoice| bm(B::Weekday(idw.clone(), d.0)))
                                .padding([7, 12])
                                .style(theme::select),
                        );
                    }
                    opts = opts.push(r);
                }
            }
            opts = opts.push(
                button(text(tr("Retirer cet emplacement")).size(13))
                    .padding([7, 12])
                    .style(theme::danger)
                    .on_press(bm(B::Remove(id.clone()))),
            );
            card = card.push(opts);
        }
        c = c.push(container(card).padding(12).style(theme::card));
    }
    match &st.place {
        None => {
            c = c.push(
                row![
                    secondary(tr("+ Dossier"), bm(B::NewPlace(PlaceKind::Folder))),
                    secondary("+ WebDAV".into(), bm(B::NewPlace(PlaceKind::WebDav))),
                    secondary("+ S3".into(), bm(B::NewPlace(PlaceKind::S3))),
                ]
                .spacing(8),
            );
            c = c.push(label(tr(
                "Dossier : celui de Google Drive, OneDrive, Nextcloud ou Syncthing sur cet ordinateur convient aussi.",
            )));
        }
        Some(p) => {
            let inp = |ph: &str, v: &str, f: fn(String) -> F| {
                text_input(ph, v)
                    .on_input(move |x| bm(B::Place(f(x))))
                    .padding([8, 12])
                    .size(13)
                    .style(theme::input)
            };
            let mut form = column![].spacing(8);
            match p.kind {
                PlaceKind::Folder => {
                    form = form.push(
                        row![inp(&tr("Dossier"), &p.path, F::Path), secondary(tr("Choisir…"), bm(B::PickFolder))]
                            .spacing(8)
                            .align_y(Alignment::Center),
                    )
                }
                PlaceKind::WebDav => {
                    form = form
                        .push(inp("https://cloud.example.org/remote.php/dav/files/moi/Nautile/", &p.url, F::Url))
                        .push(
                            row![
                                inp(&tr("Utilisateur"), &p.username, F::Username),
                                inp(&tr("Mot de passe"), &p.secret, F::Secret).secure(true)
                            ]
                            .spacing(8),
                        )
                }
                PlaceKind::S3 => {
                    form = form
                        .push(
                            row![
                                inp("s3.eu-west-3.amazonaws.com", &p.endpoint, F::Endpoint),
                                inp("us-east-1", &p.region, F::Region).width(140)
                            ]
                            .spacing(8),
                        )
                        .push(row![inp("bucket", &p.bucket, F::Bucket), inp("nautile.backup", &p.key, F::Key)].spacing(8))
                        .push(
                            row![
                                inp(&tr("Clé d'accès"), &p.access_key, F::AccessKey),
                                inp(&tr("Clé secrète"), &p.secret, F::Secret).secure(true)
                            ]
                            .spacing(8),
                        )
                }
            }
            if let Some(e) = &p.error {
                form = form.push(pink(e.clone()));
            }
            form = form.push(
                row![
                    secondary(tr("Annuler"), bm(B::CancelPlace)),
                    primary(
                        if p.busy {
                            tr("Vérification…")
                        } else {
                            tr("Vérifier et ajouter")
                        },
                        (!p.busy && p.dest().is_some()).then_some(bm(B::AddPlace))
                    ),
                ]
                .spacing(8),
            );
            c = c.push(container(form).padding(12).style(theme::card));
        }
    }

    // Copies kept here.
    let versions = crate::backup::versions(app);
    if !versions.is_empty() {
        c = c.push(label(tr("COPIES SUR CET ORDINATEUR")));
        for (path, at, size) in versions {
            c = c.push(
                row![
                    text(when(at)).font(MONO).size(13),
                    label(nautile_net::sftp::human_size(size)),
                    Space::new().width(Fill),
                    button(text(tr("Restaurer")).size(12))
                        .padding([5, 10])
                        .style(theme::secondary)
                        .on_press(bm(B::RestoreVersion(path))),
                ]
                .spacing(12)
                .align_y(Alignment::Center),
            );
        }
    }
    c.into()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct FreqChoice(crate::backup::Frequency);

impl std::fmt::Display for FreqChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0.label())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct DayChoice(u32, String);

impl std::fmt::Display for DayChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.1)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct TargetChoice(String, String);

impl std::fmt::Display for TargetChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.1)
    }
}

/// Synchronisation through a shared file, and straight over Wi-Fi.
pub(super) fn sync_settings(app: &App) -> Element<'_, Message> {
    use crate::backup::BackupMsg as B;
    let s = &app.settings;
    let st = &app.backup;
    let mut c = column![
        soft(tr("Vos appareils partagent un même fichier chiffré : chacun y fusionne ses modifications (la plus récente l'emporte, suppressions comprises).")),
        switch(tr("Synchroniser mes appareils"), s.sync_enabled, |v| bm(B::SyncEnabled(v))),
    ]
    .spacing(12);
    if s.sync_enabled {
        let choices: Vec<TargetChoice> = s.targets.iter().map(|t| TargetChoice(t.id.clone(), t.dest.describe())).collect();
        if choices.is_empty() {
            c = c.push(pink(tr("Ajoutez d'abord un emplacement de sauvegarde.")));
        } else {
            let current = choices.iter().find(|t| Some(&t.0) == s.sync_target.as_ref()).cloned();
            c = c.push(field(
                tr("Fichier partagé"),
                pick_list(choices, current, |t: TargetChoice| bm(B::SyncTarget(t.0)))
                    .width(Fill)
                    .padding([8, 12])
                    .style(theme::select),
            ));
            c = c.push(field(
                tr("Fréquence"),
                segmented(
                    vec![
                        (5, "5 min".into()),
                        (15, "15 min".into()),
                        (30, "30 min".into()),
                        (60, "1 h".into()),
                    ],
                    s.sync_minutes(),
                    |m| bm(B::SyncMinutes(m)),
                ),
            ));
        }
        if !app.has_backup_password() {
            c = c.push(pink(tr("Mot de passe de sauvegarde non enregistré")));
        }
        let status = if st.syncing {
            soft(tr("Synchronisation…"))
        } else if let Some(e) = &s.sync_error {
            pink(e.clone())
        } else if let Some(l) = s.last_sync {
            green(trf("Dernière synchronisation : %1$s", &[&when(l)]))
        } else {
            soft(tr("Pas encore synchronisé"))
        };
        c = c.push(
            row![status.width(Fill), secondary(tr("Synchroniser maintenant"), bm(B::SyncNow))]
                .spacing(12)
                .align_y(Alignment::Center),
        );
        let me = s.device_id.clone().unwrap_or_default();
        for (id, name, seen) in &s.devices {
            let this = *id == me;
            c = c.push(
                row![
                    icon(if this { I_DESKTOP } else { I_PHONE }).size(15),
                    text(if this {
                        trf("%1$s (cet ordinateur)", &[name])
                    } else {
                        name.clone()
                    })
                    .size(13)
                    .width(Fill),
                    label(when(*seen)).font(MONO),
                ]
                .spacing(10)
                .align_y(Alignment::Center),
            );
        }
    }
    c.into()
}

pub(super) fn lan_settings(app: &App) -> Element<'_, Message> {
    use crate::backup::BackupMsg as B;
    let s = &app.settings;
    let st = &app.backup;
    let mut c = column![
        soft(tr(
            "Sans cloud : vos appareils associés se synchronisent directement quand ils sont sur le même réseau."
        )),
        switch(tr("Sur le Wi-Fi, sans cloud"), s.lan_enabled, |v| bm(B::LanEnabled(v))),
    ]
    .spacing(12);
    if !s.lan_enabled {
        return c.into();
    }
    if let Some(code) = &st.lan_code {
        c = c.push(
            container(
                column![
                    label(tr("Tapez ce code sur l'autre appareil")),
                    text(format!("{} {}", &code[..3], &code[3..])).font(MONO).size(34),
                    secondary(tr("Annuler"), bm(B::LanCancelCode)),
                ]
                .spacing(8)
                .align_x(Alignment::Center),
            )
            .padding(16)
            .center_x(Fill)
            .style(theme::card),
        );
    } else {
        c = c.push(row![secondary(tr("Associer : afficher un code"), bm(B::LanShowCode))]);
    }
    let paired: Vec<&str> = s.lan_peers.iter().map(|p| p.id.as_str()).collect();
    let others: Vec<_> = st.lan_found.iter().filter(|f| !paired.contains(&f.id.as_str())).collect();
    if !others.is_empty() {
        c = c.push(label(tr("À PROXIMITÉ")));
        for f in others {
            let pairing = st.lan_pairing.as_ref().filter(|(p, _)| p.id == f.id);
            let r: Element<Message> = match pairing {
                Some((_, code)) => row![
                    text(f.name.clone()).size(13).width(Fill),
                    text_input(&tr("Code affiché"), code)
                        .on_input(|v| bm(B::LanCode(v)))
                        .on_submit(bm(B::LanPair))
                        .font(MONO)
                        .width(120)
                        .padding([7, 10])
                        .size(14)
                        .style(theme::input),
                    primary(tr("Associer"), (code.len() == 6 && !st.lan_busy).then_some(bm(B::LanPair))),
                ]
                .spacing(10)
                .align_y(Alignment::Center)
                .into(),
                None => row![
                    text(f.name.clone()).size(13).width(Fill),
                    secondary(tr("Associer…"), bm(B::LanPairWith(f.clone()))),
                ]
                .spacing(10)
                .align_y(Alignment::Center)
                .into(),
            };
            c = c.push(r);
        }
    }
    if !s.lan_peers.is_empty() {
        c = c.push(label(tr("APPAREILS ASSOCIÉS")));
        for p in &s.lan_peers {
            let near = st.lan_found.iter().any(|f| f.id == p.id);
            let state = if p.last > 0 {
                trf("synchronisé %1$s", &[&when(p.last)])
            } else {
                tr("jamais synchronisé")
            };
            c = c.push(
                row![
                    text(p.name.clone()).size(13),
                    label(if near { trf("à portée · %1$s", &[&state]) } else { state }),
                    Space::new().width(Fill),
                    icon_button(I_TRASH, bm(B::LanForget(p.id.clone()))),
                ]
                .spacing(10)
                .align_y(Alignment::Center),
            );
        }
        c = c.push(row![secondary(tr("Synchroniser maintenant"), bm(B::LanSyncNow))]);
    }
    if let Some(m) = &st.lan_status {
        c = c.push(soft(m.clone()));
    }
    c.into()
}

// --- AI assistant ---

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct ModelChoice(String);

impl std::fmt::Display for ModelChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}  ·  {}", nautile_net::ai::pretty_model(&self.0), self.0)
    }
}

pub(super) fn ai_settings(app: &App) -> Element<'_, Message> {
    use crate::assistant::AiMsg as A;
    use nautile_net::ai::ProviderKind;
    let st = &app.assistant;
    let kind = app.ai_provider();
    let saved = app.ai_config(kind);
    let mut c = column![
        soft(tr("Dans une session, l'assistant (Ctrl+Maj+A) transforme une demande en commande et explique ce qui est à l'écran. Rien n'est envoyé sans votre demande.")),
        field(
            tr("Fournisseur"),
            pick_list(ProviderKind::ALL, Some(kind), |k| am(A::Provider(k)))
                .width(Fill)
                .padding([8, 12])
                .style(theme::select)
        ),
    ]
    .spacing(12);
    if kind.needs_key() {
        let has = saved.api_key.is_some();
        let mut r = row![text_input(
            &if has {
                tr("Clé enregistrée — en saisir une autre pour la remplacer")
            } else {
                kind.key_hint().to_owned()
            },
            &st.key_input
        )
        .on_input(|v| am(A::Key(v)))
        .secure(true)
        .padding([9, 12])
        .size(14)
        .style(theme::input)]
        .spacing(8);
        if has {
            r = r.push(secondary(tr("Oublier"), am(A::ForgetKey)));
        }
        c = c.push(field(tr("Clé API"), r));
    }
    if kind.editable_url() {
        c = c.push(kit::float_field(
            tr("Adresse"),
            text_input(kind.default_url().unwrap_or_default(), &st.url_input)
                .on_input(|v| am(A::Url(v)))
                .padding([9, 12])
                .size(14)
                .font(MONO)
                .style(theme::input),
        ));
    }
    let mut model_row = row![text_input(kind.default_model(), &st.model_input)
        .on_input(|v| am(A::Model(v)))
        .padding([9, 12])
        .size(14)
        .font(MONO)
        .style(theme::input)]
    .spacing(8);
    model_row = model_row.push(secondary(tr("Charger la liste"), am(A::LoadModels)));
    c = c.push(field(tr("Modèle"), model_row));
    if !st.models.is_empty() {
        let choices: Vec<ModelChoice> = st.models.iter().cloned().map(ModelChoice).collect();
        let current = choices.iter().find(|m| m.0 == st.model_input).cloned();
        c = c.push(
            pick_list(choices, current, |m: ModelChoice| am(A::PickModel(m.0)))
                .width(Fill)
                .padding([8, 12])
                .style(theme::select),
        );
    }
    c = c.push(
        row![
            primary(tr("Enregistrer"), Some(am(A::Save))),
            secondary(if st.testing { tr("Test…") } else { tr("Tester") }, am(A::Test)),
        ]
        .spacing(8),
    );
    match &st.status {
        Some(Ok(s)) => c = c.push(green(s.clone())),
        Some(Err(e)) => c = c.push(pink(e.clone())),
        None => {}
    }
    c.into()
}
