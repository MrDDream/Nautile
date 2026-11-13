//! What the app looks like: a sidebar, the page, and an editing panel on the right.

use iced::widget::{button, column, container, pick_list, row, scrollable, stack, text, text_editor, text_input, Column, Space};
use iced::{Alignment, Color, Element, Fill, Length, Padding};
use nautile_core::model::{AuthMethod, Host, Protocol};
use nautile_core::{tr, trf};

use crate::app::{App, AuthChoice, Choice, Editor, HostField, Message, Page, ProtocolChoice, COLORS};
use crate::storage::{Appearance, Language};
use crate::theme::{self, argb, BOLD, MONO};

// Nerd Font (Material Design) icons.
const I_HOSTS: char = '\u{F048B}';
const I_SNIPPETS: char = '\u{F0169}';
const I_KEYS: char = '\u{F0306}';
const I_SETTINGS: char = '\u{F0493}';
const I_ADD: char = '\u{F0415}';
const I_EDIT: char = '\u{F03EB}';
const I_SEARCH: char = '\u{F0349}';
const I_TERMINAL: char = '\u{F018D}';
const I_DESKTOP: char = '\u{F0379}';
const I_COPY: char = '\u{F018F}';
const I_FOLDER: char = '\u{F024B}';
const I_CLOSE: char = '\u{F0156}';
const I_TRASH: char = '\u{F0A7A}';

pub fn icon<'a>(c: char) -> iced::widget::Text<'a> {
    text(c.to_string()).font(MONO).size(17)
}

fn soft<'a>(s: impl text::IntoFragment<'a>) -> iced::widget::Text<'a> {
    text(s).size(13).style(|t: &iced::Theme| text::Style {
        color: Some(theme::nt(t).muted),
    })
}

fn label<'a>(s: impl text::IntoFragment<'a>) -> iced::widget::Text<'a> {
    text(s).size(12).style(|t: &iced::Theme| text::Style {
        color: Some(theme::nt(t).muted),
    })
}

fn title<'a>(s: impl text::IntoFragment<'a>) -> iced::widget::Text<'a> {
    text(s).size(22).font(BOLD)
}

fn field<'a>(name: String, input: impl Into<Element<'a, Message>>) -> Column<'a, Message> {
    column![label(name), input.into()].spacing(6)
}

fn input<'a>(placeholder: &str, value: &str, on: impl Fn(String) -> Message + 'a) -> text_input::TextInput<'a, Message> {
    text_input(placeholder, value)
        .on_input(on)
        .padding([9, 12])
        .size(14)
        .style(theme::input)
}

fn primary<'a>(label: String, on: Option<Message>) -> button::Button<'a, Message> {
    button(text(label).font(BOLD).size(14))
        .padding([9, 16])
        .style(theme::primary)
        .on_press_maybe(on)
}

fn secondary<'a>(label: String, on: Message) -> button::Button<'a, Message> {
    button(text(label).size(14)).padding([9, 16]).style(theme::secondary).on_press(on)
}

fn icon_button<'a>(c: char, on: Message) -> button::Button<'a, Message> {
    button(icon(c).size(16)).padding([6, 8]).style(theme::ghost).on_press(on)
}

fn count<'a>(n: usize) -> Element<'a, Message> {
    container(text(n.to_string()).size(11).font(BOLD))
        .padding([2, 8])
        .style(|t: &iced::Theme| {
            let c = theme::nt(t).muted;
            theme::chip(c)(t)
        })
        .into()
}

fn color_dot<'a>(c: i64, size: f32) -> Element<'a, Message> {
    let col = argb(c);
    container(Space::new().width(size).height(size))
        .style(move |_| container::Style {
            background: Some(col.into()),
            border: iced::border::rounded(size / 2.0),
            ..Default::default()
        })
        .into()
}

fn color_picker<'a>(current: i64, on: impl Fn(i64) -> Message + 'a) -> Element<'a, Message> {
    row(COLORS.iter().map(|&c| {
        let selected = c == current;
        let col = argb(c);
        button(Space::new().width(22).height(22))
            .style(move |t: &iced::Theme, _| button::Style {
                background: Some(col.into()),
                border: iced::Border {
                    color: if selected { theme::nt(t).text } else { Color::TRANSPARENT },
                    width: 2.0,
                    radius: 12.0.into(),
                },
                ..Default::default()
            })
            .padding(0)
            .on_press(on(c))
            .into()
    }))
    .spacing(10)
    .into()
}

/// The whole window.
pub fn view(app: &App) -> Element<'_, Message> {
    if let Some(e) = &app.fatal {
        return container(
            column![
                title(tr("Impossible d'ouvrir les données de Nautile")),
                soft(e.clone()),
                soft(app.paths.data.display().to_string()).font(MONO),
            ]
            .spacing(12)
            .max_width(560),
        )
        .center(Fill)
        .style(theme::page)
        .into();
    }

    let page: Element<Message> = match app.page {
        Page::Hosts => hosts(app),
        Page::Snippets => snippets(app),
        Page::Keys => keys(app),
        Page::Settings => settings(app),
    };
    let mut main = row![sidebar(app), container(page).width(Fill).height(Fill).style(theme::page)];
    if let Some(e) = &app.editor {
        main = main.push(container(editor(app, e)).width(400).height(Fill).style(theme::panel));
    }
    let mut layers = stack![main];
    if let Some(t) = &app.toast {
        layers = layers.push(
            container(container(text(t.clone()).size(14)).padding([10, 16]).style(theme::toast))
                .align_bottom(Fill)
                .center_x(Fill)
                .padding(24),
        );
    }
    layers.into()
}

fn sidebar(app: &App) -> Element<'_, Message> {
    let item = |c: char, name: String, p: Page, n: Option<usize>| {
        let active = app.page == p;
        let mut r = row![icon(c), text(name).size(14)].spacing(12).align_y(Alignment::Center);
        if let Some(n) = n {
            r = r.push(Space::new().width(Fill)).push(text(n.to_string()).size(12));
        }
        button(r)
            .width(Fill)
            .padding([10, 12])
            .style(theme::nav(active))
            .on_press(Message::Nav(p))
    };
    let brand = row![
        container(icon('\u{F1A44}').size(18))
            .padding([4, 6])
            .style(|t: &iced::Theme| theme::tile(theme::nt(t).accent)(t)),
        text("Nautile").font(BOLD).size(19),
    ]
    .spacing(10)
    .align_y(Alignment::Center);
    let place = if app.paths.portable {
        tr("Mode portable")
    } else {
        tr("Données chiffrées sur cet ordinateur")
    };
    container(
        column![
            brand,
            Space::new().height(18),
            item(I_HOSTS, tr("Hôtes"), Page::Hosts, Some(app.hosts.len())),
            item(I_SNIPPETS, tr("Snippets"), Page::Snippets, Some(app.snippets.len())),
            item(I_KEYS, tr("Clés"), Page::Keys, Some(app.keys.len())),
            Space::new().height(Fill),
            item(I_SETTINGS, tr("Réglages"), Page::Settings, None),
            label(place).size(11),
        ]
        .spacing(4),
    )
    .width(230)
    .height(Fill)
    .padding(Padding {
        top: 20.0,
        right: 14.0,
        bottom: 16.0,
        left: 14.0,
    })
    .style(theme::sidebar)
    .into()
}

fn header<'a>(
    name: String,
    n: Option<usize>,
    search: Option<(&'a str, String)>,
    actions: Vec<Element<'a, Message>>,
) -> Element<'a, Message> {
    let mut r = row![title(name)].spacing(10).align_y(Alignment::Center);
    if let Some(n) = n {
        r = r.push(count(n));
    }
    r = r.push(Space::new().width(Fill));
    if let Some((value, placeholder)) = search {
        // Shrinks before the buttons do when the editing panel is open.
        r = r.push(
            container(
                text_input(&placeholder, value)
                    .on_input(Message::Search)
                    .icon(text_input::Icon {
                        font: MONO,
                        code_point: I_SEARCH,
                        size: Some(15.into()),
                        spacing: 8.0,
                        side: text_input::Side::Left,
                    })
                    .padding([8, 12])
                    .size(14)
                    .style(theme::input),
            )
            .max_width(300)
            .width(Fill),
        );
    }
    for a in actions {
        r = r.push(a);
    }
    container(r)
        .padding(Padding {
            top: 22.0,
            right: 28.0,
            bottom: 14.0,
            left: 28.0,
        })
        .into()
}

fn matches(h: &Host, q: &str) -> bool {
    let q = q.trim().to_lowercase();
    q.is_empty()
        || [&h.alias, &h.hostname, &h.username, &h.tags]
            .iter()
            .any(|s| s.to_lowercase().contains(&q))
}

fn protocol_icon(p: Protocol) -> char {
    if p.is_desktop() {
        I_DESKTOP
    } else {
        I_TERMINAL
    }
}

fn host_row<'a>(app: &'a App, h: &'a Host) -> Element<'a, Message> {
    let selected = matches!(&app.editor, Some(Editor::Host(f)) if f.original.as_ref().map(|o| o.id) == Some(h.id));
    let col = argb(h.color);
    let address = match h.protocol {
        Protocol::Local => tr("cet ordinateur"),
        _ => h.address(),
    };
    let mut info = column![text(&h.alias).font(BOLD).size(15), soft(address).font(MONO).size(12)].spacing(3);
    if !h.tags.is_empty() {
        info = info.push(
            row(h.tags.split(',').map(|t| {
                container(text(t.to_owned()).size(11))
                    .padding([1, 7])
                    .style(theme::chip(col))
                    .into()
            }))
            .spacing(6),
        );
    }
    let tile = container(icon(protocol_icon(h.protocol)).size(18))
        .padding([7, 9])
        .style(theme::tile(col));
    let proto = container(text(crate::app::protocol_label(h.protocol)).size(11).font(BOLD))
        .padding([3, 8])
        .style(|t: &iced::Theme| theme::chip(theme::nt(t).text_soft)(t));
    button(
        row![tile, info, Space::new().width(Fill), proto]
            .spacing(14)
            .align_y(Alignment::Center),
    )
    .width(Fill)
    .padding([10, 12])
    .style(theme::row_item(selected))
    .on_press(Message::EditHost(h.id))
    .into()
}

fn section<'a>(
    app: &'a App,
    name: String,
    color: Option<i64>,
    n: usize,
    edit: Option<Message>,
    items: Vec<&'a Host>,
) -> Element<'a, Message> {
    let mut head = row![].spacing(10).align_y(Alignment::Center);
    if let Some(c) = color {
        head = head.push(color_dot(c, 10.0));
    }
    head = head.push(text(name).font(BOLD).size(13)).push(count(n));
    if let Some(m) = edit {
        head = head.push(Space::new().width(Fill)).push(icon_button(I_EDIT, m));
    }
    container(column![head, column(items.into_iter().map(|h| host_row(app, h))).spacing(4)].spacing(8))
        .padding(12)
        .style(theme::card)
        .into()
}

fn hosts(app: &App) -> Element<'_, Message> {
    let shown: Vec<&Host> = app.hosts.iter().filter(|h| matches(h, &app.search)).collect();
    let mut list = Column::new().spacing(18).padding(Padding {
        top: 4.0,
        right: 28.0,
        bottom: 28.0,
        left: 28.0,
    });

    for g in &app.groups {
        let items: Vec<&Host> = shown.iter().copied().filter(|h| h.group_id == Some(g.id)).collect();
        if items.is_empty() && !app.search.is_empty() {
            continue;
        }
        list = list.push(section(
            app,
            g.name.clone(),
            Some(g.color),
            items.len(),
            Some(Message::EditGroup(g.id)),
            items,
        ));
    }
    let loose: Vec<&Host> = shown
        .iter()
        .copied()
        .filter(|h| h.group_id.is_none_or(|g| !app.groups.iter().any(|x| x.id == g)))
        .collect();
    if !loose.is_empty() {
        list = list.push(section(app, tr("Sans groupe"), None, loose.len(), None, loose));
    }
    if app.hosts.is_empty() {
        list = list.push(empty(
            I_HOSTS,
            tr("Aucun hôte pour l'instant"),
            tr("Ajoutez un serveur, ou restaurez la sauvegarde de votre téléphone dans les réglages."),
        ));
    } else if shown.is_empty() {
        list = list.push(soft(trf("Aucun hôte ne correspond à « %1$s ».", &[&app.search])));
    }

    column![
        header(
            tr("Hôtes"),
            Some(app.hosts.len()),
            Some((&app.search, tr("Rechercher"))),
            vec![
                secondary(tr("Nouveau groupe"), Message::NewGroup).into(),
                button(
                    row![icon(I_ADD).size(15), text(tr("Nouvel hôte")).font(BOLD).size(14)]
                        .spacing(6)
                        .align_y(Alignment::Center)
                )
                .padding([9, 14])
                .style(theme::primary)
                .on_press(Message::NewHost)
                .into(),
            ],
        ),
        scrollable(list).height(Fill),
    ]
    .into()
}

fn empty<'a>(c: char, head: String, body: String) -> Element<'a, Message> {
    container(
        column![icon(c).size(34), text(head).font(BOLD).size(16), soft(body)]
            .spacing(10)
            .align_x(Alignment::Center)
            .max_width(420),
    )
    .center_x(Fill)
    .padding(48)
    .into()
}

fn snippets(app: &App) -> Element<'_, Message> {
    let q = app.search.to_lowercase();
    let shown = app
        .snippets
        .iter()
        .filter(|s| q.is_empty() || s.name.to_lowercase().contains(&q) || s.command.to_lowercase().contains(&q));
    let mut list = Column::new().spacing(8).padding(Padding {
        top: 4.0,
        right: 28.0,
        bottom: 28.0,
        left: 28.0,
    });
    for s in shown {
        let selected = matches!(&app.editor, Some(Editor::Snippet(f)) if f.original.as_ref().map(|o| o.id) == Some(s.id));
        let first = s.command.lines().next().unwrap_or_default().to_owned();
        let more = s.command.lines().count().saturating_sub(1);
        let mut info = column![
            {
                let mut r = row![text(&s.name).font(BOLD).size(15)].spacing(8).align_y(Alignment::Center);
                if !s.tag.is_empty() {
                    r = r.push(
                        container(text(s.tag.clone()).size(11))
                            .padding([1, 7])
                            .style(|t: &iced::Theme| theme::chip(theme::nt(t).blue)(t)),
                    );
                }
                r
            },
            soft(first).font(MONO).size(12),
        ]
        .spacing(4);
        if more > 0 {
            info = info.push(label(trf("+ %1$s ligne(s)", &[&more])));
        }
        let body = row![info, Space::new().width(Fill), icon_button(I_COPY, Message::CopySnippet(s.id))]
            .spacing(10)
            .align_y(Alignment::Center);
        list = list.push(
            button(body)
                .width(Fill)
                .padding([10, 14])
                .style(theme::row_item(selected))
                .on_press(Message::EditSnippet(s.id)),
        );
    }
    if app.snippets.is_empty() {
        list = list.push(empty(
            I_SNIPPETS,
            tr("Aucun snippet"),
            tr("Les variables comme ${SERVICE} sont demandées au lancement."),
        ));
    }
    column![
        header(
            tr("Snippets"),
            Some(app.snippets.len()),
            Some((&app.search, tr("Rechercher un snippet"))),
            vec![primary(tr("Nouveau snippet"), Some(Message::NewSnippet)).into()],
        ),
        scrollable(container(list).style(theme::page)).height(Fill),
    ]
    .into()
}

fn keys(app: &App) -> Element<'_, Message> {
    let mut list = Column::new().spacing(8).padding(Padding {
        top: 4.0,
        right: 28.0,
        bottom: 28.0,
        left: 28.0,
    });
    for k in &app.keys {
        let used = app.hosts.iter().filter(|h| h.key_id == Some(k.id)).count();
        let row = row![
            container(icon(I_KEYS).size(18))
                .padding([7, 9])
                .style(|t: &iced::Theme| theme::tile(theme::nt(t).accent)(t)),
            column![
                text(&k.name).font(BOLD).size(15),
                soft(format!(
                    "{} · {}",
                    k.description,
                    if used == 0 {
                        tr("inutilisée")
                    } else {
                        trf("%1$s hôte(s)", &[&used])
                    }
                )),
                label(k.fingerprint.clone()).font(MONO),
            ]
            .spacing(3),
            Space::new().width(Fill),
            icon_button(I_TRASH, Message::DeleteKey(k.id)),
        ]
        .spacing(14)
        .align_y(Alignment::Center);
        list = list.push(container(row).padding([10, 14]).style(theme::card));
    }
    if app.keys.is_empty() {
        list = list.push(empty(
            I_KEYS,
            tr("Aucune clé"),
            tr("Les clés de votre téléphone arrivent avec sa sauvegarde ; l'import et la génération viennent avec le terminal."),
        ));
    }
    column![
        header(tr("Clés"), Some(app.keys.len()), None, vec![]),
        scrollable(list).height(Fill)
    ]
    .into()
}

fn segmented<'a, T: Copy + PartialEq + 'a>(options: Vec<(T, String)>, current: T, on: impl Fn(T) -> Message + 'a) -> Element<'a, Message> {
    container(
        row(options.into_iter().map(|(v, name)| {
            button(text(name).size(13).font(BOLD).center())
                .width(Fill)
                .padding([7, 12])
                .style(theme::segment(v == current))
                .on_press(on(v))
                .into()
        }))
        .spacing(4),
    )
    .padding(4)
    .style(|t: &iced::Theme| {
        let n = theme::nt(t);
        container::Style {
            background: Some(n.bg.into()),
            border: iced::Border {
                color: n.border,
                width: 1.0,
                radius: 12.0.into(),
            },
            ..Default::default()
        }
    })
    .into()
}

fn settings_card<'a>(name: String, body: impl Into<Element<'a, Message>>) -> Element<'a, Message> {
    container(column![text(name).font(BOLD).size(15), body.into()].spacing(14))
        .padding(18)
        .width(Fill)
        .style(theme::card)
        .into()
}

fn settings(app: &App) -> Element<'_, Message> {
    let s = &app.settings;
    let look = settings_card(
        tr("Apparence"),
        column![
            field(
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
            field(
                tr("Thème"),
                segmented(
                    vec![(Appearance::Dark, tr("Sombre")), (Appearance::Light, tr("Clair"))],
                    s.appearance,
                    Message::Appearance
                )
            ),
        ]
        .spacing(14),
    );

    let imp = &app.import;
    let mut restore = column![soft(tr(
        "Un fichier nautile.backup de votre téléphone ou d'un autre ordinateur : tout est fusionné, rien n'est effacé ici."
    ))]
    .spacing(12);
    match &imp.file {
        None => restore = restore.push(secondary(tr("Choisir le fichier…"), Message::ImportPick)),
        Some((name, _)) => {
            restore = restore
                .push(
                    row![icon(I_FOLDER), text(name.clone()).size(14)]
                        .spacing(8)
                        .align_y(Alignment::Center),
                )
                .push(
                    row![
                        text_input(&tr("Mot de passe de la sauvegarde"), &imp.password)
                            .on_input(Message::ImportPassword)
                            .on_submit(Message::ImportRun)
                            .secure(true)
                            .padding([9, 12])
                            .size(14)
                            .style(theme::input),
                        primary(
                            if imp.busy { tr("Déchiffrement…") } else { tr("Restaurer") },
                            (!imp.busy && !imp.password.is_empty()).then_some(Message::ImportRun)
                        ),
                    ]
                    .spacing(10),
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
            text_input(&tr("Mot de passe (8 caractères minimum)"), &ex.password)
                .on_input(Message::ExportPassword)
                .secure(true)
                .padding([9, 12])
                .size(14)
                .style(theme::input),
            text_input(&tr("Confirmer"), &ex.confirm)
                .on_input(Message::ExportConfirm)
                .on_submit(Message::ExportRun)
                .secure(true)
                .padding([9, 12])
                .size(14)
                .style(theme::input),
            primary(
                if ex.busy { tr("Chiffrement…") } else { tr("Exporter…") },
                ok.then_some(Message::ExportRun)
            ),
        ]
        .spacing(10),
    ]
    .spacing(12);

    let backup = settings_card(
        tr("Sauvegarde"),
        column![
            label(tr("RESTAURER")),
            restore,
            Space::new().height(4),
            label(tr("EXPORTER")),
            export
        ]
        .spacing(10),
    );

    let key_place = match s.key_store.as_deref() {
        Some("keyring") => tr("Clé de la base dans le trousseau du système"),
        _ if app.paths.portable => tr("Mode portable : la clé voyage avec le dossier data"),
        _ => tr("Clé de la base dans un fichier réservé à votre compte (pas de trousseau disponible)"),
    };
    let data = settings_card(
        tr("Données"),
        column![
            soft(tr("Base SQLCipher chiffrée, sans compte ni serveur.")),
            soft(key_place),
            label(app.paths.data.display().to_string()).font(MONO),
        ]
        .spacing(8),
    );

    column![
        header(tr("Réglages"), None, None, vec![]),
        scrollable(column![look, backup, data].spacing(16).max_width(760).padding(Padding {
            top: 4.0,
            right: 28.0,
            bottom: 28.0,
            left: 28.0
        }))
        .height(Fill),
    ]
    .into()
}

/// The panel's title, form, save action and delete action (with its « confirm » state).
type Panel<'a> = (String, Element<'a, Message>, Option<Message>, Option<(bool, Message)>);

fn editor<'a>(app: &'a App, e: &'a Editor) -> Element<'a, Message> {
    let (heading, body, save, delete): Panel<'a> = match e {
        Editor::Host(f) => {
            let none = || Choice {
                id: None,
                label: tr("Aucun"),
            };
            let groups: Vec<Choice> = std::iter::once(Choice {
                id: None,
                label: tr("Sans groupe"),
            })
            .chain(app.groups.iter().map(|g| Choice {
                id: Some(g.id),
                label: g.name.clone(),
            }))
            .collect();
            let jumps: Vec<Choice> = std::iter::once(Choice {
                id: None,
                label: tr("Aucun — connexion directe"),
            })
            .chain(
                app.hosts
                    .iter()
                    .filter(|h| Some(h.id) != f.original.as_ref().map(|o| o.id) && matches!(h.protocol, Protocol::Ssh))
                    .map(|h| Choice {
                        id: Some(h.id),
                        label: h.alias.clone(),
                    }),
            )
            .collect();
            let keys: Vec<Choice> = std::iter::once(none())
                .chain(app.keys.iter().map(|k| Choice {
                    id: Some(k.id),
                    label: k.name.clone(),
                }))
                .collect();
            let ids: Vec<Choice> = std::iter::once(none())
                .chain(app.identities.iter().map(|i| Choice {
                    id: Some(i.id),
                    label: format!("{} ({})", i.name, i.username),
                }))
                .collect();
            let pick = |list: &[Choice], id: Option<i64>| list.iter().find(|c| c.id == id).cloned();

            let network = f.protocol != Protocol::Local;
            let mut form = column![
                field(
                    tr("Type"),
                    pick_list(&ProtocolChoice::ALL[..], Some(ProtocolChoice(f.protocol)), |p| Message::Host(
                        HostField::Protocol(p)
                    ))
                    .width(Fill)
                    .padding([8, 12])
                    .style(theme::select)
                ),
                field(tr("Nom"), input("docker-01", &f.alias, |v| Message::Host(HostField::Alias(v)))),
            ]
            .spacing(14);
            if network {
                form = form.push(
                    row![
                        field(
                            tr("Adresse ou nom"),
                            input("10.0.0.20", &f.hostname, |v| Message::Host(HostField::Hostname(v)))
                        )
                        .width(Length::FillPortion(3)),
                        field(tr("Port"), input("22", &f.port, |v| Message::Host(HostField::Port(v)))).width(Length::FillPortion(1)),
                    ]
                    .spacing(10),
                );
                if f.protocol != Protocol::Vnc {
                    form = form.push(field(
                        tr("Utilisateur"),
                        input("root", &f.username, |v| Message::Host(HostField::Username(v))),
                    ));
                }
                form = form.push(field(
                    tr("Authentification"),
                    pick_list(&AuthChoice::ALL[..], Some(AuthChoice(f.auth)), |a| {
                        Message::Host(HostField::Auth(a))
                    })
                    .width(Fill)
                    .padding([8, 12])
                    .style(theme::select),
                ));
                match f.auth {
                    AuthMethod::Key => {
                        form = form.push(field(
                            tr("Clé SSH"),
                            pick_list(keys.clone(), pick(&keys, f.key), |c| Message::Host(HostField::Key(c)))
                                .width(Fill)
                                .padding([8, 12])
                                .style(theme::select),
                        ))
                    }
                    AuthMethod::Identity => {
                        form = form.push(field(
                            tr("Identité"),
                            pick_list(ids.clone(), pick(&ids, f.identity), |c| Message::Host(HostField::Identity(c)))
                                .width(Fill)
                                .padding([8, 12])
                                .style(theme::select),
                        ))
                    }
                    _ => {
                        form = form.push(field(
                            tr("Mot de passe"),
                            text_input(&tr("Demandé à la connexion si vide"), &f.password)
                                .on_input(|v| Message::Host(HostField::Password(v)))
                                .secure(true)
                                .padding([9, 12])
                                .size(14)
                                .style(theme::input),
                        ))
                    }
                }
                if matches!(f.protocol, Protocol::Ssh | Protocol::Mosh | Protocol::Rdp | Protocol::Vnc) {
                    form = form.push(field(
                        tr("Rebond SSH"),
                        pick_list(jumps.clone(), pick(&jumps, f.jump), |c| Message::Host(HostField::Jump(c)))
                            .width(Fill)
                            .padding([8, 12])
                            .style(theme::select),
                    ));
                }
            }
            form = form
                .push(field(
                    tr("Groupe"),
                    pick_list(groups.clone(), pick(&groups, f.group), |c| Message::Host(HostField::Group(c)))
                        .width(Fill)
                        .padding([8, 12])
                        .style(theme::select),
                ))
                .push(field(tr("Couleur"), color_picker(f.color, |c| Message::Host(HostField::Color(c)))))
                .push(field(
                    tr("Tags (séparés par des virgules)"),
                    input("prod, docker", &f.tags, |v| Message::Host(HostField::Tags(v))),
                ));
            if !f.protocol.is_desktop() {
                form = form.push(field(
                    tr("Commande au démarrage"),
                    input("htop", &f.startup, |v| Message::Host(HostField::Startup(v))).font(MONO),
                ));
            }
            let heading = if f.original.is_some() {
                f.alias.clone()
            } else {
                tr("Nouvel hôte")
            };
            (
                heading,
                form.into(),
                f.valid().then_some(Message::SaveHost),
                f.original.as_ref().map(|_| (f.confirm_delete, Message::DeleteHost)),
            )
        }
        Editor::Group(f) => {
            let form = column![
                field(
                    tr("Nom"),
                    input("Homelab", &f.name, Message::GroupName).on_submit(Message::SaveGroup)
                ),
                field(tr("Couleur"), color_picker(f.color, Message::GroupColor)),
            ]
            .spacing(14);
            let heading = if f.original.is_some() {
                tr("Modifier le groupe")
            } else {
                tr("Nouveau groupe")
            };
            let hint: Element<Message> = if f.original.is_some() {
                soft(tr("Supprimer le groupe garde ses hôtes, sans groupe.")).into()
            } else {
                Space::new().into()
            };
            (
                heading,
                column![form, hint].spacing(14).into(),
                (!f.name.trim().is_empty()).then_some(Message::SaveGroup),
                f.original.as_ref().map(|_| (f.confirm_delete, Message::DeleteGroup)),
            )
        }
        Editor::Snippet(f) => {
            let form = column![
                field(tr("Nom"), input(&tr("Espace disque"), &f.name, Message::SnippetName)),
                field(
                    tr("Commande"),
                    text_editor(&f.command)
                        .on_action(Message::SnippetCommand)
                        .font(MONO)
                        .size(13)
                        .height(160)
                        .padding(10)
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
                soft(tr(
                    "Astuce : ${NOM} devient une valeur demandée à chaque exécution. Plusieurs lignes = plusieurs commandes."
                )),
                field(tr("Tag"), input("Docker", &f.tag, Message::SnippetTag)),
            ]
            .spacing(14);
            let heading = if f.original.is_some() {
                f.name.clone()
            } else {
                tr("Nouveau snippet")
            };
            let ok = !f.name.trim().is_empty() && !f.command.text().trim().is_empty();
            (
                heading,
                form.into(),
                ok.then_some(Message::SaveSnippet),
                f.original.as_ref().map(|_| (f.confirm_delete, Message::DeleteSnippet)),
            )
        }
    };

    let mut actions = row![].spacing(10).align_y(Alignment::Center);
    if let Some((confirm, m)) = delete {
        actions = actions.push(
            button(text(if confirm { tr("Confirmer la suppression") } else { tr("Supprimer") }).size(14))
                .padding([9, 14])
                .style(theme::danger)
                .on_press(m),
        );
    }
    actions = actions
        .push(Space::new().width(Fill))
        .push(secondary(tr("Annuler"), Message::CloseEditor))
        .push(primary(tr("Enregistrer"), save));

    column![
        row![
            text(heading).font(BOLD).size(18),
            Space::new().width(Fill),
            icon_button(I_CLOSE, Message::CloseEditor)
        ]
        .align_y(Alignment::Center)
        .padding([18, 20]),
        scrollable(container(body).padding(Padding {
            top: 0.0,
            right: 20.0,
            bottom: 20.0,
            left: 20.0
        }))
        .height(Fill),
        container(actions).padding([14, 20]),
    ]
    .into()
}
