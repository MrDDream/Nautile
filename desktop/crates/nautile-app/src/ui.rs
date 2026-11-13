//! What the app looks like: a sidebar, the page, and an editing panel on the right.

use iced::widget::{button, column, container, pick_list, row, scrollable, stack, text, text_editor, text_input, Column, Space};
use iced::{Alignment, Color, Element, Fill, Length, Padding};
use nautile_core::model::{AuthMethod, Host, Protocol};
use nautile_core::{tr, trf};

use crate::app::{App, AuthChoice, Choice, Editor, HostField, KeysTab, Message, Page, ProtocolChoice, COLORS};
use crate::sessions::{Tab, TabState, TunnelState};
use crate::storage::{Appearance, Language};
use crate::term::{themes, widget as term_view};
use crate::theme::{self, argb, BOLD, MONO};
use nautile_core::model::ForwardKind;
use nautile_net::Prompt;

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
const I_TUNNEL: char = '\u{F0318}';
const I_PERSON: char = '\u{F0004}';
const I_SHIELD: char = '\u{F0565}';
const I_PLAY: char = '\u{F040A}';
const I_STOP: char = '\u{F04DB}';
const I_SEND: char = '\u{F048A}';
const I_PLUS: char = '\u{F0415}';

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

    if app.locked {
        return lock_screen(app);
    }
    let active = app.active_tab.and_then(|id| app.tab(id));
    let page: Element<Message> = match active {
        Some(t) => session(app, t),
        None => match app.page {
            Page::Hosts => hosts(app),
            Page::Snippets => snippets(app),
            Page::Keys => keys(app),
            Page::Tunnels => tunnels(app),
            Page::Settings => settings(app),
        },
    };
    let mut center = Column::new();
    if !app.tabs.is_empty() {
        center = center.push(tab_bar(app));
    }
    center = center.push(container(page).width(Fill).height(Fill).style(theme::page));
    let mut main = row![sidebar(app), center];
    if let Some(e) = &app.editor {
        main = main.push(container(editor(app, e)).width(400).height(Fill).style(theme::panel));
    }
    let mut layers = stack![main];
    if let Some(p) = app.prompts.first() {
        layers = layers.push(prompt_dialog(app, &p.prompt));
    } else if let Some((id, vars)) = &app.snippet_run {
        layers = layers.push(snippet_dialog(app, *id, vars));
    }
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
        let active = app.page == p && app.active_tab.is_none();
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
            item(I_TUNNEL, tr("Tunnels"), Page::Tunnels, Some(app.forwards.len())),
            Space::new().height(Fill),
            item(I_SETTINGS, tr("Réglages"), Page::Settings, None),
            {
                let lock: Element<Message> = if app.settings.locks() {
                    button(
                        row![icon('\u{F033E}').size(15), text(tr("Verrouiller")).size(13)]
                            .spacing(12)
                            .align_y(Alignment::Center),
                    )
                    .width(Fill)
                    .padding([8, 12])
                    .style(theme::nav(false))
                    .on_press(Message::LockNow)
                    .into()
                } else {
                    Space::new().into()
                };
                lock
            },
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
    button({
        let mut r = row![tile, info, Space::new().width(Fill), proto]
            .spacing(14)
            .align_y(Alignment::Center);
        if matches!(h.protocol, Protocol::Ssh | Protocol::Mosh) {
            r = r.push(icon_button(I_FOLDER, Message::OpenFiles(h.id)));
        }
        r.push(icon_button(I_EDIT, Message::EditHost(h.id)))
    })
    .width(Fill)
    .padding([10, 12])
    .style(theme::row_item(selected))
    .on_press(Message::Connect(h.id))
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
                button(
                    row![icon(I_TERMINAL).size(15), text(tr("Terminal local")).size(14)]
                        .spacing(8)
                        .align_y(Alignment::Center)
                )
                .padding([9, 14])
                .style(theme::secondary)
                .on_press(Message::OpenLocal)
                .into(),
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
        let mut body = row![info, Space::new().width(Fill)].spacing(10).align_y(Alignment::Center);
        if app.target_tab().is_some() {
            body = body.push(icon_button(I_SEND, Message::RunSnippet(s.id)));
        }
        body = body.push(icon_button(I_COPY, Message::CopySnippet(s.id)));
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

fn key_list(app: &App) -> Column<'_, Message> {
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
            icon_button(I_COPY, Message::CopyPublicKey(k.id)),
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
            tr("Générez une clé ou importez-en une (OpenSSH, PEM, PuTTY .ppk). Celles du téléphone arrivent avec sa sauvegarde."),
        ));
    }
    list
}

fn keys(app: &App) -> Element<'_, Message> {
    let tabs = segmented(
        vec![
            (KeysTab::Keys, tr("Clés")),
            (KeysTab::Identities, tr("Identités")),
            (KeysTab::KnownHosts, tr("Hôtes connus")),
        ],
        app.keys_tab,
        Message::KeysTab,
    );
    let (n, body, actions): (usize, Column<Message>, Vec<Element<Message>>) = match app.keys_tab {
        KeysTab::Keys => (
            app.keys.len(),
            key_list(app),
            vec![
                secondary(tr("Importer"), Message::NewKey(false)).into(),
                primary(tr("Générer"), Some(Message::NewKey(true))).into(),
            ],
        ),
        KeysTab::Identities => (
            app.identities.len(),
            identity_list(app),
            vec![primary(tr("Nouvelle identité"), Some(Message::NewIdentity)).into()],
        ),
        KeysTab::KnownHosts => (app.known_hosts.len(), known_list(app), vec![]),
    };
    column![
        header(tr("Trousseau"), Some(n), None, actions),
        container(container(tabs).max_width(520)).padding(Padding {
            top: 0.0,
            right: 28.0,
            bottom: 10.0,
            left: 28.0
        }),
        scrollable(body).height(Fill)
    ]
    .into()
}

fn pad() -> Padding {
    Padding {
        top: 4.0,
        right: 28.0,
        bottom: 28.0,
        left: 28.0,
    }
}

fn identity_list(app: &App) -> Column<'_, Message> {
    let mut list = Column::new().spacing(8).padding(pad());
    for i in &app.identities {
        let selected = matches!(&app.editor, Some(Editor::Identity(f)) if f.original.as_ref().map(|o| o.id) == Some(i.id));
        let how = match (i.key_id.and_then(|k| app.keys.iter().find(|x| x.id == k)), i.password.is_some()) {
            (Some(k), _) => trf("clé %1$s", &[&k.name]),
            (None, true) => tr("mot de passe"),
            (None, false) => tr("mot de passe demandé"),
        };
        let used = app.hosts.iter().filter(|h| h.identity_id == Some(i.id)).count();
        let body = row![
            container(icon(I_PERSON).size(18))
                .padding([7, 9])
                .style(|t: &iced::Theme| theme::tile(theme::nt(t).blue)(t)),
            column![
                text(&i.name).font(BOLD).size(15),
                soft(format!("{} · {} · {}", i.username, how, trf("%1$s hôte(s)", &[&used])))
            ]
            .spacing(3),
        ]
        .spacing(14)
        .align_y(Alignment::Center);
        list = list.push(
            button(body)
                .width(Fill)
                .padding([10, 14])
                .style(theme::row_item(selected))
                .on_press(Message::EditIdentity(i.id)),
        );
    }
    if app.identities.is_empty() {
        list = list.push(empty(
            I_PERSON,
            tr("Aucune identité"),
            tr("Un utilisateur et son mot de passe ou sa clé, à réutiliser sur plusieurs hôtes."),
        ));
    }
    list
}

fn known_list(app: &App) -> Column<'_, Message> {
    let mut list = Column::new().spacing(8).padding(pad());
    for k in &app.known_hosts {
        let body = row![
            container(icon(I_SHIELD).size(18))
                .padding([7, 9])
                .style(|t: &iced::Theme| theme::tile(theme::nt(t).green)(t)),
            column![
                text(if k.port == 22 {
                    k.host.clone()
                } else {
                    format!("{}:{}", k.host, k.port)
                })
                .font(BOLD)
                .size(15),
                soft(k.key_type.clone()),
                label(k.fingerprint.clone()).font(MONO),
            ]
            .spacing(3),
            Space::new().width(Fill),
            icon_button(I_TRASH, Message::DeleteKnownHost(k.id)),
        ]
        .spacing(14)
        .align_y(Alignment::Center);
        list = list.push(container(body).padding([10, 14]).style(theme::card));
    }
    if app.known_hosts.is_empty() {
        list = list.push(empty(
            I_SHIELD,
            tr("Aucun hôte connu"),
            tr("La clé de chaque serveur est retenue à la première connexion, puis vérifiée à chaque fois."),
        ));
    }
    list
}

fn kind_label(k: ForwardKind) -> String {
    match k {
        ForwardKind::Local => tr("Locale"),
        ForwardKind::Remote => tr("Distante"),
        ForwardKind::Dynamic => "SOCKS5".into(),
    }
}

fn tunnels(app: &App) -> Element<'_, Message> {
    let mut list = Column::new().spacing(8).padding(pad());
    for x in &app.forwards {
        let selected = matches!(&app.editor, Some(Editor::Forward(f)) if f.original.as_ref().map(|o| o.id) == Some(x.id));
        let via = app
            .hosts
            .iter()
            .find(|h| h.id == x.host_id)
            .map(|h| h.alias.clone())
            .unwrap_or_default();
        let route = match x.kind {
            ForwardKind::Dynamic => format!("{}:{} → SOCKS5", x.bind_host, x.bind_port),
            _ => format!("{}:{} → {}:{}", x.bind_host, x.bind_port, x.dest_host, x.dest_port),
        };
        let mut info = column![
            row![
                text(&x.name).font(BOLD).size(15),
                container(text(kind_label(x.kind)).size(11).font(BOLD))
                    .padding([1, 7])
                    .style(|t: &iced::Theme| theme::chip(theme::nt(t).blue)(t)),
            ]
            .spacing(8)
            .align_y(Alignment::Center),
            soft(route).font(MONO).size(12),
            label(trf("via %1$s", &[&via])),
        ]
        .spacing(3);
        if x.auto_start {
            info = info.push(label(tr("Démarre avec chaque session")));
        }
        let run = app.tunnels.get(&x.id).map(|r| &r.state);
        match run {
            Some(TunnelState::Running) => {
                let hint = App::tunnel_hint(x)
                    .map(|a| trf("Actif sur %1$s", &[&a]))
                    .unwrap_or_else(|| tr("Actif"));
                info = info.push(text(hint).size(12).style(|t: &iced::Theme| text::Style {
                    color: Some(theme::nt(t).green),
                }));
            }
            Some(TunnelState::Starting(step)) => info = info.push(label(if step.is_empty() { tr("Démarrage…") } else { step.clone() })),
            Some(TunnelState::Failed(e)) => {
                info = info.push(text(e.clone()).size(12).style(|t: &iced::Theme| text::Style {
                    color: Some(theme::nt(t).pink),
                }))
            }
            None => {}
        }
        let running = matches!(run, Some(TunnelState::Running | TunnelState::Starting(_)));
        let body = row![
            container(icon(I_TUNNEL).size(18))
                .padding([7, 9])
                .style(|t: &iced::Theme| theme::tile(theme::nt(t).accent)(t)),
            info,
            Space::new().width(Fill),
            button(
                row![
                    icon(if running { I_STOP } else { I_PLAY }).size(15),
                    text(if running { tr("Arrêter") } else { tr("Démarrer") }).size(13)
                ]
                .spacing(6)
                .align_y(Alignment::Center)
            )
            .padding([6, 12])
            .style(if running { theme::danger } else { theme::secondary })
            .on_press(Message::ToggleTunnel(x.id)),
        ]
        .spacing(14)
        .align_y(Alignment::Center);
        list = list.push(
            button(body)
                .width(Fill)
                .padding([10, 14])
                .style(theme::row_item(selected))
                .on_press(Message::EditForward(x.id)),
        );
    }
    if app.forwards.is_empty() {
        list = list.push(empty(
            I_TUNNEL,
            tr("Aucun tunnel"),
            tr("Accédez à un service du serveur depuis cet ordinateur (locale), exposez un service local sur le serveur (distante) ou passez par le serveur comme proxy (SOCKS)."),
        ));
    }
    column![
        header(
            tr("Tunnels"),
            Some(app.forwards.len()),
            None,
            vec![primary(tr("Nouvelle règle"), (!app.hosts.is_empty()).then_some(Message::NewForward)).into()]
        ),
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
    let terminal = settings_card(
        tr("Terminal"),
        column![
            field(trf("Thème · %1$s", &[&tr(current.name)]), swatches),
            field(
                trf("Taille du texte · %1$spx", &[&size]),
                row![
                    secondary("A−".into(), Message::TermFont(size - 1.0)),
                    secondary("A+".into(), Message::TermFont(size + 1.0)),
                ]
                .spacing(8)
            ),
            soft(tr("Ctrl+Maj+C / Ctrl+Maj+V pour copier-coller, clic droit pour copier la sélection ou coller. Ctrl+Maj+T ouvre un terminal local, Ctrl+Tab change d'onglet.")),
        ]
        .spacing(14),
    );

    let lf = &app.lock_form;
    let mut lock_body = column![segmented(
        vec![
            (None, tr("Aucun")),
            (Some(nautile_core::lock::LockMethod::Pin), tr("Code PIN")),
            (Some(nautile_core::lock::LockMethod::Password), tr("Mot de passe")),
        ],
        lf.method,
        Message::LockPick
    )]
    .spacing(12);
    let changing = lf.method != s.lock_method || lf.method.is_some() && !s.locks();
    let secure = |ph: String, v: &str, on: fn(String) -> Message| {
        text_input(&ph, v)
            .on_input(on)
            .on_submit(Message::LockSave)
            .secure(true)
            .padding([9, 12])
            .size(14)
            .style(theme::input)
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
    let lock_card = settings_card(tr("Verrouillage"), lock_body);

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
            backup_places(app),
            Space::new().height(4),
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
        scrollable(
            column![
                look,
                terminal,
                lock_card,
                backup,
                settings_card(tr("Synchronisation"), sync_settings(app)),
                settings_card(tr("Synchronisation Wi-Fi"), lan_settings(app)),
                settings_card(tr("Assistant IA"), ai_settings(app)),
                data
            ]
            .spacing(16)
            .max_width(760)
            .padding(Padding {
                top: 4.0,
                right: 28.0,
                bottom: 28.0,
                left: 28.0
            })
        )
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
        Editor::Key(f) => {
            let mut form = column![field(tr("Nom"), input("laptop", &f.name, Message::KeyName))].spacing(14);
            if f.generate {
                form = form.push(field(
                    tr("Type"),
                    pick_list(nautile_net::keys::KeyAlgorithm::ALL, Some(f.algorithm), Message::KeyAlgorithm)
                        .width(Fill)
                        .padding([8, 12])
                        .style(theme::select),
                ));
            } else {
                form = form.push(field(
                    tr("Clé privée"),
                    column![
                        text_editor(&f.text)
                            .placeholder("-----BEGIN OPENSSH PRIVATE KEY-----")
                            .on_action(Message::KeyText)
                            .font(MONO)
                            .size(12)
                            .height(180)
                            .padding(10),
                        secondary(tr("Choisir un fichier…"), Message::KeyPickFile),
                    ]
                    .spacing(8),
                ));
            }
            form = form.push(field(
                if f.generate {
                    tr("Phrase de passe (recommandée)")
                } else {
                    tr("Phrase de passe")
                },
                text_input(&tr("Aucune"), &f.passphrase)
                    .on_input(Message::KeyPassphrase)
                    .on_submit(Message::SaveKey)
                    .secure(true)
                    .padding([9, 12])
                    .size(14)
                    .style(theme::input),
            ));
            if !f.generate {
                form = form.push(
                    iced::widget::checkbox(f.remember)
                        .label(tr("Mémoriser la phrase de passe"))
                        .on_toggle(Message::KeyRemember)
                        .size(16)
                        .text_size(13),
                );
            }
            if f.busy {
                form = form.push(soft(if f.generate {
                    tr("Génération…")
                } else {
                    tr("Lecture de la clé…")
                }));
            }
            if let Some(e) = &f.error {
                form = form.push(text(e.clone()).size(13).style(|t: &iced::Theme| text::Style {
                    color: Some(theme::nt(t).pink),
                }));
            }
            let ok = !f.name.trim().is_empty() && !f.busy && (f.generate || !f.text.text().trim().is_empty());
            (
                if f.generate {
                    tr("Générer une clé")
                } else {
                    tr("Importer une clé")
                },
                form.into(),
                ok.then_some(Message::SaveKey),
                None,
            )
        }
        Editor::Identity(f) => {
            let keys: Vec<Choice> = std::iter::once(Choice {
                id: None,
                label: tr("Aucune — mot de passe"),
            })
            .chain(app.keys.iter().map(|k| Choice {
                id: Some(k.id),
                label: k.name.clone(),
            }))
            .collect();
            let current = keys.iter().find(|c| c.id == f.key).cloned();
            let form = column![
                field(tr("Nom"), input("admin", &f.name, Message::IdentityName)),
                field(tr("Utilisateur"), input("root", &f.username, Message::IdentityUser)),
                field(
                    tr("Clé SSH"),
                    pick_list(keys, current, Message::IdentityKey)
                        .width(Fill)
                        .padding([8, 12])
                        .style(theme::select)
                ),
                field(
                    tr("Mot de passe"),
                    text_input(&tr("Demandé à la connexion si vide"), &f.password)
                        .on_input(Message::IdentityPassword)
                        .secure(true)
                        .padding([9, 12])
                        .size(14)
                        .style(theme::input)
                ),
            ]
            .spacing(14);
            let heading = if f.original.is_some() {
                f.name.clone()
            } else {
                tr("Nouvelle identité")
            };
            let ok = !f.name.trim().is_empty() && !f.username.trim().is_empty();
            (
                heading,
                form.into(),
                ok.then_some(Message::SaveIdentity),
                f.original.as_ref().map(|_| (f.confirm_delete, Message::DeleteIdentity)),
            )
        }
        Editor::Forward(f) => {
            let hosts: Vec<Choice> = app
                .hosts
                .iter()
                .filter(|h| matches!(h.protocol, Protocol::Ssh | Protocol::Mosh))
                .map(|h| Choice {
                    id: Some(h.id),
                    label: h.alias.clone(),
                })
                .collect();
            let current = hosts.iter().find(|c| c.id == f.host).cloned();
            let explain = match f.kind {
                ForwardKind::Local => tr("Écoute sur cet ordinateur, se connecte à la destination depuis le serveur."),
                ForwardKind::Remote => tr("Écoute sur le serveur, se connecte à la destination depuis cet ordinateur."),
                ForwardKind::Dynamic => tr("Proxy SOCKS5 sur cet ordinateur : les applis qui le configurent sortent par le serveur."),
            };
            let mut form = column![
                field(tr("Nom"), input("Jellyfin", &f.name, Message::ForwardName)),
                segmented(
                    vec![
                        (ForwardKind::Local, tr("Locale")),
                        (ForwardKind::Remote, tr("Distante")),
                        (ForwardKind::Dynamic, "SOCKS".into())
                    ],
                    f.kind,
                    Message::ForwardKind
                ),
                soft(explain),
                field(
                    tr("Via l'hôte"),
                    pick_list(hosts, current, Message::ForwardHost)
                        .placeholder(tr("Choisir…"))
                        .width(Fill)
                        .padding([8, 12])
                        .style(theme::select)
                ),
                row![
                    field(
                        if f.kind == ForwardKind::Remote {
                            tr("Écoute (serveur)")
                        } else {
                            tr("Écoute")
                        },
                        input("127.0.0.1", &f.bind_host, Message::ForwardBindHost).font(MONO)
                    )
                    .width(Length::FillPortion(2)),
                    field(tr("Port"), input("8080", &f.bind_port, Message::ForwardBindPort).font(MONO)).width(Length::FillPortion(1)),
                ]
                .spacing(10),
            ]
            .spacing(14);
            if f.kind != ForwardKind::Dynamic {
                form = form.push(
                    row![
                        field(
                            "Destination".into(),
                            input("127.0.0.1", &f.dest_host, Message::ForwardDestHost).font(MONO)
                        )
                        .width(Length::FillPortion(2)),
                        field(tr("Port"), input("80", &f.dest_port, Message::ForwardDestPort).font(MONO)).width(Length::FillPortion(1)),
                    ]
                    .spacing(10),
                );
            }
            form = form.push(
                iced::widget::checkbox(f.auto_start)
                    .label(tr("Démarrer avec chaque session sur cet hôte"))
                    .on_toggle(Message::ForwardAuto)
                    .size(18)
                    .text_size(14),
            );
            let heading = if f.original.is_some() {
                tr("Modifier la règle")
            } else {
                tr("Nouvelle règle")
            };
            (
                heading,
                form.into(),
                f.valid().then_some(Message::SaveForward),
                f.original.as_ref().map(|_| (f.confirm_delete, Message::DeleteForward)),
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

/// The tabs: the hosts page, then one per session.
fn tab_bar(app: &App) -> Element<'_, Message> {
    let home = app.active_tab.is_none();
    let mut tabs = row![button(
        row![icon(I_HOSTS).size(15), text(tr("Hôtes")).size(13)]
            .spacing(8)
            .align_y(Alignment::Center)
    )
    .padding([7, 12])
    .style(theme::nav(home))
    .on_press(Message::SelectTab(None))]
    .spacing(4)
    .align_y(Alignment::Center);
    for t in &app.tabs {
        let active = app.active_tab == Some(t.id);
        let state = match &t.state {
            TabState::Open => None,
            TabState::Connecting(_) => Some(I_TERMINAL),
            TabState::Closed(_) => Some(I_CLOSE),
        };
        let mut r = row![color_dot(t.color, 8.0), text(t.name.clone()).size(13)]
            .spacing(8)
            .align_y(Alignment::Center);
        if let Some(c) = state {
            r = r.push(label(c.to_string()).font(MONO));
        }
        r = r.push(
            button(icon(I_CLOSE).size(12))
                .padding([2, 4])
                .style(theme::ghost)
                .on_press(Message::CloseTab(t.id)),
        );
        tabs = tabs.push(
            button(r)
                .padding(Padding {
                    top: 5.0,
                    right: 6.0,
                    bottom: 5.0,
                    left: 12.0,
                })
                .style(theme::nav(active))
                .on_press(Message::SelectTab(Some(t.id))),
        );
    }
    tabs = tabs.push(
        button(icon(I_PLUS).size(15))
            .padding([6, 9])
            .style(theme::ghost)
            .on_press(Message::OpenLocal),
    );

    let mut bar = row![container(scrollable(tabs).direction(scrollable::Direction::Horizontal(
        scrollable::Scrollbar::new().width(0).scroller_width(0)
    )))
    .width(Fill)]
    .align_y(Alignment::Center);
    let shows_terminal = app.active_tab.and_then(|id| app.tab(id)).is_some_and(|t| t.files.is_none());
    if shows_terminal {
        bar = bar.push(
            button(
                row![icon(I_SPARKLES).size(15), text(tr("Assistant")).size(13)]
                    .spacing(6)
                    .align_y(Alignment::Center),
            )
            .padding([6, 12])
            .style(theme::nav(app.assistant.open))
            .on_press(Message::Ai(crate::assistant::AiMsg::Toggle)),
        );
    }
    container(bar).width(Fill).padding([6, 10]).style(theme::sidebar).into()
}

/// A session: its terminal, and what is happening when it is not open.
fn session<'a>(app: &'a App, t: &'a Tab) -> Element<'a, Message> {
    let id = t.id;
    if let Some(f) = &t.files {
        return files_view(app, t, f);
    }
    let focused = app.prompts.is_empty() && app.editor.is_none();
    let term: Element<Message> =
        term_view::view(&t.term, MONO, app.settings.terminal_font(), focused, move |a| Message::Term(id, a)).into();
    let bg = t.term.palette.background;
    let term = container(term).style(move |_| container::Style {
        background: Some(bg.into()),
        ..Default::default()
    });
    let banner = |body: Element<'a, Message>| -> Element<'a, Message> {
        container(container(body).padding([14, 18]).max_width(560).style(theme::card))
            .center_x(Fill)
            .align_bottom(Fill)
            .padding(24)
            .into()
    };
    let with_panel = |e: Element<'a, Message>| -> Element<'a, Message> {
        if app.assistant.open {
            row![e, assistant_panel(app)].into()
        } else {
            e
        }
    };
    match &t.state {
        TabState::Open => with_panel(term.into()),
        TabState::Connecting(step) => {
            let step = if step.is_empty() {
                trf("Connexion à %1$s…", &[&t.name])
            } else {
                step.clone()
            };
            stack![
                term,
                banner(
                    row![
                        icon(I_TERMINAL),
                        text(step).size(14),
                        Space::new().width(Fill),
                        secondary(tr("Annuler"), Message::CloseTab(id))
                    ]
                    .spacing(12)
                    .align_y(Alignment::Center)
                    .into()
                )
            ]
            .into()
        }
        TabState::Closed(reason) => {
            let (head, color_pink) = match reason {
                Some(r) => (r.clone(), true),
                None => (tr("Session terminée"), false),
            };
            let mut actions = row![].spacing(8);
            if t.protocol != nautile_core::model::Protocol::Local || t.host_id.is_none() {
                actions = actions.push(primary(tr("Reconnecter"), Some(Message::Reconnect(id))));
            }
            actions = actions.push(secondary(tr("Fermer"), Message::CloseTab(id)));
            stack![
                term,
                banner(
                    row![
                        text(head).size(14).style(move |th: &iced::Theme| text::Style {
                            color: Some(if color_pink { theme::nt(th).pink } else { theme::nt(th).text }),
                        }),
                        Space::new().width(Fill),
                        actions
                    ]
                    .spacing(12)
                    .align_y(Alignment::Center)
                    .into()
                )
            ]
            .into()
        }
    }
}

/// A connection's question: trust a host key, or type a secret.
fn prompt_dialog<'a>(app: &'a App, p: &'a Prompt) -> Element<'a, Message> {
    let body: Element<Message> = match p {
        Prompt::HostKey {
            label: alias,
            info,
            previous,
            ..
        } => {
            let changed = previous.is_some();
            let mut c = column![
                text(if changed {
                    tr("⚠ La clé de l'hôte a changé")
                } else {
                    tr("Nouvel hôte")
                })
                .size(18)
                .font(BOLD)
                .style(move |th: &iced::Theme| text::Style {
                    color: Some(if changed { theme::nt(th).pink } else { theme::nt(th).text }),
                }),
                soft(if changed {
                    trf(
                        "La clé présentée par %1$s:%2$s ne correspond plus à celle enregistrée. ",
                        &[&info.host, &info.port],
                    ) + &tr("Cela peut venir d'une réinstallation du serveur… ou d'une attaque de l'homme du milieu.")
                } else {
                    trf(
                        "Première connexion à %1$s:%2$s (%3$s). Vérifiez l'empreinte avant de faire confiance.",
                        &[&info.host, &info.port, alias],
                    )
                }),
                label(info.key_type.clone()),
                text(info.fingerprint.clone()).font(MONO).size(13),
            ]
            .spacing(10);
            if let Some(prev) = previous {
                c = c
                    .push(label(tr("Ancienne empreinte")))
                    .push(soft(prev.fingerprint.clone()).font(MONO));
            }
            c.push(
                row![
                    Space::new().width(Fill),
                    secondary(tr("Refuser"), Message::PromptAnswer(false)),
                    button(
                        text(if changed { tr("Remplacer la clé") } else { tr("Faire confiance") })
                            .font(BOLD)
                            .size(14)
                    )
                    .padding([9, 16])
                    .style(if changed { theme::danger } else { theme::primary })
                    .on_press(Message::PromptAnswer(true)),
                ]
                .spacing(10),
            )
            .into()
        }
        Prompt::Secret {
            label: alias,
            title: name,
            detail,
            ..
        } => column![
            text(name.clone()).size(18).font(BOLD),
            soft(format!("{alias} · {detail}")),
            text_input("", &app.prompt_input)
                .id("prompt-secret")
                .on_input(Message::PromptInput)
                .on_submit(Message::PromptAnswer(true))
                .secure(true)
                .padding([9, 12])
                .size(14)
                .style(theme::input),
            row![
                Space::new().width(Fill),
                secondary(tr("Annuler"), Message::PromptAnswer(false)),
                primary(tr("Se connecter"), Some(Message::PromptAnswer(true))),
            ]
            .spacing(10),
        ]
        .spacing(12)
        .into(),
    };
    container(container(body).padding(22).max_width(520).style(theme::card))
        .center(Fill)
        .style(|_| container::Style {
            background: Some(Color { a: 0.55, ..Color::BLACK }.into()),
            ..Default::default()
        })
        .into()
}

/// The values of a snippet's `${VARIABLES}` before it runs.
fn snippet_dialog<'a>(app: &'a App, id: i64, vars: &'a [(String, String)]) -> Element<'a, Message> {
    let name = app.snippets.iter().find(|s| s.id == id).map(|s| s.name.clone()).unwrap_or_default();
    let target = app.target_tab().map(|t| t.name.clone()).unwrap_or_default();
    let mut c = column![
        text(trf("Exécuter %1$s", &[&name])).size(18).font(BOLD),
        soft(trf("sur %1$s", &[&target]))
    ]
    .spacing(12);
    for (i, (var, value)) in vars.iter().enumerate() {
        let last = i + 1 == vars.len();
        let mut inp = text_input(var, value)
            .on_input(move |v| Message::SnippetVar(i, v))
            .padding([9, 12])
            .size(14)
            .font(MONO)
            .style(theme::input);
        if last {
            inp = inp.on_submit(Message::SnippetRunGo);
        }
        c = c.push(field(var.clone(), inp));
    }
    c = c.push(
        row![
            Space::new().width(Fill),
            secondary(tr("Annuler"), Message::SnippetRunCancel),
            primary(tr("Exécuter"), Some(Message::SnippetRunGo)),
        ]
        .spacing(10),
    );
    container(container(c).padding(22).max_width(520).style(theme::card))
        .center(Fill)
        .style(|_| container::Style {
            background: Some(Color { a: 0.55, ..Color::BLACK }.into()),
            ..Default::default()
        })
        .into()
}

/// The app is locked: the code, nothing else.
fn lock_screen(app: &App) -> Element<'_, Message> {
    let pin = app.settings.lock_method == Some(nautile_core::lock::LockMethod::Pin);
    let mut c = column![
        container(icon('\u{F033E}').size(30))
            .padding([10, 14])
            .style(|t: &iced::Theme| theme::tile(theme::nt(t).accent)(t)),
        text(tr("Nautile est verrouillé")).size(20).font(BOLD),
        text_input(&if pin { tr("Code PIN") } else { tr("Mot de passe") }, &app.lock_input)
            .id("lock")
            .on_input(Message::LockInput)
            .on_submit(Message::Unlock)
            .secure(true)
            .padding([10, 14])
            .size(16)
            .width(280)
            .style(theme::input),
        primary(tr("Déverrouiller"), Some(Message::Unlock)),
    ]
    .spacing(16)
    .align_x(Alignment::Center);
    if let Some(e) = &app.lock_error {
        c = c.push(text(e.clone()).size(13).style(|t: &iced::Theme| text::Style {
            color: Some(theme::nt(t).pink),
        }));
    }
    if !app.tabs.is_empty() {
        c = c.push(soft(trf("%1$s session(s) restent ouvertes", &[&app.tabs.len()])));
    }
    container(c).center(Fill).style(theme::page).into()
}

const I_UP: char = '\u{F0143}';
const I_REFRESH: char = '\u{F0450}';
const I_HOME: char = '\u{F02DC}';
const I_FOLDER_PLUS: char = '\u{F0257}';
const I_FILE: char = '\u{F0214}';
const I_DOWNLOAD: char = '\u{F01DA}';
const I_UPLOAD: char = '\u{F0552}';
const I_RENAME: char = '\u{F0CB6}';

/// The SFTP tab: this computer | the server, then the transfers.
fn files_view<'a>(app: &'a App, t: &'a Tab, f: &'a crate::files::Files) -> Element<'a, Message> {
    let id = t.id;
    let mut body = column![row![files_pane(app, t, f, false), files_pane(app, t, f, true)]
        .spacing(12)
        .height(Fill)]
    .spacing(10);
    let transfers: Vec<&crate::files::Job> = f.jobs.iter().filter(|j| j.transfer).collect();
    if transfers.is_empty() {
        body = body.push(label(tr(
            "Glissez des fichiers sur la fenêtre pour les envoyer dans le dossier du serveur.",
        )));
    } else {
        let mut list = Column::new().spacing(6);
        for j in transfers.iter().rev().take(5) {
            let ratio = if j.total > 0 { j.done as f32 / j.total as f32 } else { 0.0 };
            let state = match (&j.error, j.finished) {
                (Some(e), _) => e.clone(),
                (None, true) => tr("Terminé"),
                (None, false) => format!(
                    "{} / {}",
                    nautile_net::sftp::human_size(j.done),
                    nautile_net::sftp::human_size(j.total)
                ),
            };
            list = list.push(
                row![
                    text(j.label.clone()).size(13).width(Length::FillPortion(2)),
                    iced::widget::progress_bar(0.0..=1.0, if j.finished { 1.0 } else { ratio })
                        .length(Length::FillPortion(3))
                        .girth(6),
                    label(state).width(Length::FillPortion(2)),
                ]
                .spacing(12)
                .align_y(Alignment::Center),
            );
        }
        body = body.push(
            container(
                column![
                    row![
                        text(tr("Transferts")).font(BOLD).size(13),
                        Space::new().width(Fill),
                        button(text(tr("Effacer les terminés")).size(12))
                            .padding([4, 8])
                            .style(theme::ghost)
                            .on_press(Message::Files(id, crate::files::FilesMsg::ClearJobs))
                    ]
                    .align_y(Alignment::Center),
                    list
                ]
                .spacing(8),
            )
            .padding(12)
            .style(theme::card),
        );
    }
    let mut out = column![container(body).padding(16).height(Fill)];
    if let TabState::Closed(reason) = &t.state {
        out = out.push(
            container(
                row![
                    text(reason.clone().unwrap_or_else(|| tr("Session terminée"))).size(14),
                    Space::new().width(Fill),
                    primary(tr("Reconnecter"), Some(Message::Reconnect(id))),
                    secondary(tr("Fermer"), Message::CloseTab(id)),
                ]
                .spacing(8)
                .align_y(Alignment::Center),
            )
            .padding([10, 16]),
        );
    }
    out.into()
}

fn small_button<'a>(c: char, m: Message) -> Element<'a, Message> {
    button(icon(c).size(15)).padding([4, 6]).style(theme::ghost).on_press(m).into()
}

/// One side of the file browser.
fn files_pane<'a>(app: &'a App, t: &'a Tab, f: &'a crate::files::Files, remote: bool) -> Element<'a, Message> {
    use crate::files::{Edit, FilesMsg};
    let id = t.id;
    let fm = move |m: FilesMsg| Message::Files(id, m);
    let entries = if remote { &f.remote } else { &f.local };
    let title_text = if remote {
        app.hosts
            .iter()
            .find(|h| Some(h.id) == t.host_id)
            .map(|h| h.alias.clone())
            .unwrap_or_default()
    } else {
        tr("Cet ordinateur")
    };
    let path: Element<Message> = if remote {
        text_input("/", &f.remote_input)
            .on_input(move |v| fm(FilesMsg::RemoteInput(v)))
            .on_submit(fm(FilesMsg::RemoteGo))
            .font(MONO)
            .size(13)
            .padding([6, 10])
            .style(theme::input)
            .into()
    } else {
        container(text(f.local_path.display().to_string()).font(MONO).size(13))
            .padding([7, 10])
            .width(Fill)
            .style(theme::card)
            .into()
    };
    let mut tools = row![
        small_button(I_UP, fm(FilesMsg::Up(remote))),
        small_button(I_REFRESH, fm(FilesMsg::Refresh(remote)))
    ]
    .spacing(2);
    if !remote {
        tools = tools.push(small_button(I_HOME, fm(FilesMsg::LocalHome)));
    }
    tools = tools.push(small_button(
        I_FOLDER_PLUS,
        fm(FilesMsg::StartEdit(Edit::NewFolder {
            remote,
            name: String::new(),
        })),
    ));
    let head = column![
        row![text(title_text).font(BOLD).size(15), Space::new().width(Fill), tools].align_y(Alignment::Center),
        path
    ]
    .spacing(8);

    let mut list = Column::new().spacing(2);
    match &f.edit {
        Some(Edit::NewFolder { remote: r, name }) if *r == remote => {
            list = list.push(edit_row(id, name, tr("Nom du dossier"), tr("Créer")));
        }
        Some(Edit::Delete { remote: r, name }) if *r == remote => {
            list = list.push(
                container(
                    row![
                        text(trf("Supprimer « %1$s » ?", &[name])).size(13),
                        Space::new().width(Fill),
                        secondary(tr("Annuler"), fm(FilesMsg::EditCancel)),
                        button(text(tr("Supprimer")).size(13).font(BOLD))
                            .padding([8, 14])
                            .style(theme::danger)
                            .on_press(fm(FilesMsg::EditGo)),
                    ]
                    .spacing(8)
                    .align_y(Alignment::Center),
                )
                .padding(8)
                .style(theme::card),
            );
        }
        _ => {}
    }
    if remote && (f.remote_path.is_empty() || matches!(t.state, TabState::Closed(_))) {
        list = list.push(soft(match &t.state {
            TabState::Connecting(s) if !s.is_empty() => s.clone(),
            TabState::Closed(Some(e)) => e.clone(),
            TabState::Closed(None) => tr("Session terminée"),
            _ => trf("Connexion à %1$s…", &[&t.name]),
        }));
    }
    if !remote {
        if let Some(e) = &f.local_error {
            list = list.push(text(e.clone()).size(13).style(|th: &iced::Theme| text::Style {
                color: Some(theme::nt(th).pink),
            }));
        }
    }
    for e in entries {
        if let Some(Edit::Rename { remote: r, old, new }) = &f.edit {
            if *r == remote && *old == e.name {
                list = list.push(edit_row(id, new, e.name.clone(), tr("Renommer")));
                continue;
            }
        }
        let selected = f.selected.as_ref().is_some_and(|(r, n)| *r == remote && *n == e.name);
        let size = if e.dir {
            String::new()
        } else {
            nautile_net::sftp::human_size(e.size)
        };
        let when = e.modified.map(crate::files::date).unwrap_or_default();
        let mut r = row![
            icon(if e.dir { I_FOLDER } else { I_FILE }).size(15),
            text(e.name.clone()).size(13).width(Fill),
            label(size).width(80).align_x(Alignment::End),
            label(when).font(MONO).width(130),
        ]
        .spacing(10)
        .align_y(Alignment::Center);
        if selected {
            if f.remote_ready() {
                r = r.push(small_button(
                    if remote { I_DOWNLOAD } else { I_UPLOAD },
                    fm(FilesMsg::Transfer {
                        remote,
                        name: e.name.clone(),
                    }),
                ));
            }
            r = r
                .push(small_button(
                    I_RENAME,
                    fm(FilesMsg::StartEdit(Edit::Rename {
                        remote,
                        old: e.name.clone(),
                        new: e.name.clone(),
                    })),
                ))
                .push(small_button(
                    I_TRASH,
                    fm(FilesMsg::StartEdit(Edit::Delete {
                        remote,
                        name: e.name.clone(),
                    })),
                ));
        }
        let open = if e.dir && selected {
            FilesMsg::Open {
                remote,
                name: e.name.clone(),
            }
        } else {
            FilesMsg::Select {
                remote,
                name: e.name.clone(),
            }
        };
        list = list.push(
            button(r)
                .width(Fill)
                .padding([5, 8])
                .style(theme::row_item(selected))
                .on_press(fm(open)),
        );
    }
    container(column![head, scrollable(list).height(Fill)].spacing(10))
        .padding(14)
        .width(Fill)
        .height(Fill)
        .style(theme::card)
        .into()
}

fn edit_row<'a>(id: u64, value: &'a str, placeholder: String, action: String) -> Element<'a, Message> {
    use crate::files::FilesMsg;
    container(
        row![
            text_input(&placeholder, value)
                .id("files-edit")
                .on_input(move |v| Message::Files(id, FilesMsg::EditInput(v)))
                .on_submit(Message::Files(id, FilesMsg::EditGo))
                .size(13)
                .padding([6, 10])
                .style(theme::input),
            secondary(tr("Annuler"), Message::Files(id, FilesMsg::EditCancel)),
            primary(action, Some(Message::Files(id, FilesMsg::EditGo))),
        ]
        .spacing(8)
        .align_y(Alignment::Center),
    )
    .padding(6)
    .into()
}

// --- Backups and synchronisation (settings) ---

fn bm(m: crate::backup::BackupMsg) -> Message {
    Message::Backup(m)
}

fn when(ms: i64) -> String {
    use chrono::TimeZone;
    chrono::Local
        .timestamp_millis_opt(ms)
        .single()
        .map(|t| t.format("%Y-%m-%d %H:%M").to_string())
        .unwrap_or_default()
}

fn pink<'a>(s: String) -> iced::widget::Text<'a> {
    text(s).size(13).style(|t: &iced::Theme| text::Style {
        color: Some(theme::nt(t).pink),
    })
}

fn green<'a>(s: String) -> iced::widget::Text<'a> {
    text(s).size(13).style(|t: &iced::Theme| text::Style {
        color: Some(theme::nt(t).green),
    })
}

fn place_icon(d: &nautile_net::store::Dest) -> char {
    match d {
        nautile_net::store::Dest::Folder { .. } => I_FOLDER,
        _ => '\u{F015F}',
    }
}

/// The backup password, the places and their schedules.
fn backup_places(app: &App) -> Element<'_, Message> {
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
            let mut opts = column![iced::widget::checkbox(t.auto)
                .label(tr("Sauvegarde automatique (tant que Nautile est ouvert)"))
                .on_toggle({
                    let id = id.clone();
                    move |v| bm(B::Auto(id.clone(), v))
                })
                .size(16)
                .text_size(13)]
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
struct FreqChoice(crate::backup::Frequency);

impl std::fmt::Display for FreqChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0.label())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DayChoice(u32, String);

impl std::fmt::Display for DayChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.1)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct TargetChoice(String, String);

impl std::fmt::Display for TargetChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.1)
    }
}

/// Synchronisation through a shared file, and straight over Wi-Fi.
fn sync_settings(app: &App) -> Element<'_, Message> {
    use crate::backup::BackupMsg as B;
    let s = &app.settings;
    let st = &app.backup;
    let mut c = column![
        soft(tr("Vos appareils partagent un même fichier chiffré : chacun y fusionne ses modifications (la plus récente l'emporte, suppressions comprises).")),
        iced::widget::checkbox(s.sync_enabled)
            .label(tr("Synchroniser avec mes autres appareils"))
            .on_toggle(|v| bm(B::SyncEnabled(v)))
            .size(16)
            .text_size(14),
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
                    icon(if this { I_DESKTOP } else { '\u{F011C}' }).size(15),
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

fn lan_settings(app: &App) -> Element<'_, Message> {
    use crate::backup::BackupMsg as B;
    let s = &app.settings;
    let st = &app.backup;
    let mut c = column![
        soft(tr(
            "Sans cloud : vos appareils associés se synchronisent directement quand ils sont sur le même réseau."
        )),
        iced::widget::checkbox(s.lan_enabled)
            .label(tr("Synchronisation Wi-Fi"))
            .on_toggle(|v| bm(B::LanEnabled(v)))
            .size(16)
            .text_size(14),
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

const I_SPARKLES: char = '\u{F0E8D}';

fn am(m: crate::assistant::AiMsg) -> Message {
    Message::Ai(m)
}

fn md_settings(app: &App) -> iced::widget::markdown::Settings {
    let th = app.theme();
    let n = theme::nt(&th);
    let mut style = iced::widget::markdown::Style::from_palette(th.palette());
    style.font = theme::UI;
    style.inline_code_font = MONO;
    style.code_block_font = MONO;
    style.inline_code_color = n.text;
    style.inline_code_highlight = iced::advanced::text::Highlight {
        background: n.key_bg.into(),
        border: iced::border::rounded(4),
    };
    let mut s = iced::widget::markdown::Settings::with_text_size(14, style);
    s.h3_size = 15.into();
    s.code_size = 13.into();
    s
}

/// The assistant beside the session.
fn assistant_panel(app: &App) -> Element<'_, Message> {
    use crate::assistant::AiMsg as A;
    let st = &app.assistant;
    let kind = app.ai_provider();
    let model = app.ai_config(kind).model;
    let head = row![
        icon(I_SPARKLES).size(16),
        text(tr("Assistant")).font(BOLD).size(15),
        container(text(format!("{} · {}", kind.short_label(), nautile_net::ai::pretty_model(&model))).size(11))
            .padding([2, 8])
            .style(|t: &iced::Theme| theme::chip(theme::nt(t).blue)(t)),
        Space::new().width(Fill),
        icon_button(I_TRASH, am(A::Clear)),
        icon_button(I_CLOSE, am(A::Toggle)),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    let mut list = Column::new().spacing(14);
    let turns = app.ai_conversation();
    if turns.is_empty() {
        list = list.push(soft(tr(
            "Décrivez ce que vous voulez faire sur ce serveur, ou demandez d'expliquer ce qui est à l'écran : l'assistant propose une commande et l'explique.",
        )));
    }
    for s in turns {
        let mut req = column![text(s.turn.request.clone()).size(14)].spacing(4);
        let mut joined = Vec::new();
        if s.turn.screen {
            joined.push(tr("écran"));
        }
        joined.extend(s.turn.files.iter().cloned());
        if !joined.is_empty() {
            req = req.push(label(trf("Joint : %1$s", &[&joined.join(", ")])));
        }
        list = list.push(
            container(req)
                .padding([8, 12])
                .width(Fill)
                .style(|t: &iced::Theme| theme::chip(theme::nt(t).accent)(t)),
        );
        if let Some(cmd) = &s.command {
            list = list.push(
                container(
                    column![
                        text(cmd.clone()).font(MONO).size(13),
                        row![
                            Space::new().width(Fill),
                            button(text(tr("Copier")).size(12))
                                .padding([5, 10])
                                .style(theme::secondary)
                                .on_press(am(A::Copy(cmd.clone()))),
                            button(text(tr("Insérer dans le terminal")).size(12).font(BOLD))
                                .padding([5, 10])
                                .style(theme::primary)
                                .on_press(am(A::Insert(cmd.clone()))),
                        ]
                        .spacing(6),
                    ]
                    .spacing(8),
                )
                .padding(10)
                .width(Fill)
                .style(|t: &iced::Theme| container::Style {
                    background: Some(theme::nt(t).bg.into()),
                    border: iced::Border {
                        color: theme::nt(t).border,
                        width: 1.0,
                        radius: 10.0.into(),
                    },
                    ..Default::default()
                }),
            );
        }
        list = list.push(iced::widget::markdown::view(s.explanation.items(), md_settings(app)).map(|u| am(A::OpenLink(u))));
    }
    if st.busy {
        list = list.push(soft(tr("Réflexion…")));
    }
    if let Some(e) = &st.error {
        list = list.push(pink(e.clone()));
    }

    let mut attach = row![
        iced::widget::checkbox(st.screen)
            .label(tr("Joindre l'écran"))
            .on_toggle(|v| am(A::Screen(v)))
            .size(14)
            .text_size(12),
        Space::new().width(Fill),
        button(
            row![icon(I_FILE).size(13), text(tr("Fichier…")).size(12)]
                .spacing(6)
                .align_y(Alignment::Center)
        )
        .padding([4, 8])
        .style(theme::ghost)
        .on_press(am(A::Attach)),
    ]
    .spacing(8)
    .align_y(Alignment::Center);
    if !app.ai_ready() {
        attach = attach.push(Space::new());
    }
    let mut files = row![].spacing(6);
    for (i, f) in st.files.iter().enumerate() {
        files = files.push(
            button(text(format!("{} ✕", f.name)).size(11))
                .padding([2, 8])
                .style(theme::secondary)
                .on_press(am(A::RemoveFile(i))),
        );
    }
    let input = text_input(&tr("Que voulez-vous faire ?"), &st.input)
        .id("ai-input")
        .on_input(|v| am(A::Input(v)))
        .on_submit(am(A::Send))
        .padding([9, 12])
        .size(14)
        .style(theme::input);
    let mut bottom = column![
        attach,
        files,
        row![input, primary(tr("Envoyer"), (!st.busy).then_some(am(A::Send)))].spacing(8)
    ]
    .spacing(8);
    if !app.ai_ready() {
        bottom = bottom.push(pink(tr("Configurez l'assistant dans Réglages › Assistant IA")));
    }
    container(column![head, scrollable(list).height(Fill).anchor_bottom(), bottom].spacing(12))
        .padding(14)
        .width(420)
        .height(Fill)
        .style(theme::panel)
        .into()
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ModelChoice(String);

impl std::fmt::Display for ModelChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}  ·  {}", nautile_net::ai::pretty_model(&self.0), self.0)
    }
}

fn ai_settings(app: &App) -> Element<'_, Message> {
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
        c = c.push(field(
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
