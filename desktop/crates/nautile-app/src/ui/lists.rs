//! The lists: hosts, snippets, keychain and tunnels.

use super::*;
#[allow(unused_imports)]
use iced::widget::{button, column, container, image, pick_list, row, scrollable, stack, text, text_editor, text_input, Column, Space};

pub(super) fn matches(h: &Host, q: &str) -> bool {
    let q = q.trim().to_lowercase();
    q.is_empty()
        || [&h.alias, &h.hostname, &h.username, &h.tags]
            .iter()
            .any(|s| s.to_lowercase().contains(&q))
}

pub(super) fn host_count(n: usize) -> String {
    if n == 1 {
        tr("1 hôte")
    } else {
        trf("%1$s hôtes", &[&n])
    }
}

pub(super) fn host_row<'a>(app: &'a App, h: &'a Host) -> Element<'a, Message> {
    let selected = matches!(&app.editor, Some(Editor::Host(f)) if f.original.as_ref().map(|o| o.id) == Some(h.id));
    let address = match h.protocol {
        Protocol::Local => tr("cet ordinateur"),
        _ => h.address(),
    };
    let mut name = row![text(&h.alias).font(BOLD).size(16)].spacing(8).align_y(Alignment::Center);
    if h.protocol != Protocol::Ssh {
        name = name.push(
            container(text(crate::app::protocol_label(h.protocol)).size(11).font(BOLD))
                .padding([2, 7])
                .style(|t: &iced::Theme| theme::chip(theme::nt(t).text_soft)(t)),
        );
    }
    let mut info = column![name, soft(address).font(MONO).size(13)].spacing(4).width(Fill);
    if !h.tags.is_empty() {
        let col = argb(h.color);
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
    let mut r = row![kit::host_avatar(h.os_id.as_deref(), &h.alias, argb(h.color), 48.0), info]
        .spacing(16)
        .align_y(Alignment::Center);
    if let Some(at) = h.last_connected_at {
        r = r.push(soft(kit::relative_time(at, nautile_core::model::now_ms())).size(13));
    }
    if matches!(h.protocol, Protocol::Ssh | Protocol::Mosh) {
        r = r.push(
            button(icon(I_MONITOR).size(22))
                .padding(8)
                .style(theme::ghost)
                .on_press(Message::OpenMonitor(h.id)),
        );
        r = r.push(
            button(icon(I_FOLDER).size(24))
                .padding(8)
                .style(theme::ghost)
                .on_press(Message::OpenFiles(h.id)),
        );
    }
    r = r.push(
        button(icon(I_EDIT).size(20))
            .padding(8)
            .style(theme::ghost)
            .on_press(Message::EditHost(h.id)),
    );
    button(r)
        .width(Fill)
        .padding([14, 16])
        .style(move |t: &iced::Theme, st| {
            let mut b = theme::card_row(t, st);
            if selected {
                b.background = Some(theme::alpha(theme::nt(t).accent, 0.10).into());
            }
            b
        })
        .on_press(Message::Connect(h.id))
        .into()
}

pub(super) fn section<'a>(
    app: &'a App,
    name: String,
    color: Option<i64>,
    n: usize,
    edit: Option<Message>,
    items: Vec<&'a Host>,
) -> Element<'a, Message> {
    let action: Option<Element<'a, Message>> =
        edit.map(|m| button(icon(I_EDIT).size(16)).padding(4).style(theme::ghost).on_press(m).into());
    let mut c = column![kit::section_head(color.map(argb), name, host_count(n), action)].spacing(10);
    if !items.is_empty() {
        c = c.push(kit::card(items.into_iter().map(|h| host_row(app, h)).collect()));
    }
    c.into()
}

pub(super) fn hosts(app: &App) -> Element<'_, Message> {
    let shown: Vec<&Host> = app
        .hosts
        .iter()
        .filter(|h| matches(h, &app.search))
        .filter(|h| app.group_filter.is_none_or(|g| h.group_id == Some(g)))
        .collect();
    let mut list = Column::new().spacing(22).padding(Padding {
        top: 4.0,
        right: 28.0,
        bottom: 100.0,
        left: 28.0,
    });

    // « root@192.168.1.2 » typed in the search: connect without saving it (as on Android).
    let quick = nautile_core::quick::parse(&app.search).filter(|_| nautile_core::quick::looks_like_target(&app.search));
    if let Some(t) = &quick {
        let mut chips = row![].spacing(8);
        for p in [Protocol::Ssh, Protocol::Mosh, Protocol::Telnet, Protocol::Rdp, Protocol::Vnc] {
            let main = p == t.protocol;
            chips = chips.push(
                button(text(crate::app::protocol_label(p)).size(13).font(BOLD))
                    .padding([7, 14])
                    .style(if main { theme::primary } else { theme::secondary })
                    .on_press(Message::QuickConnect(p)),
            );
        }
        let card = container(
            row![
                kit::icon_tile(I_TERMINAL, theme::nt(&app.theme()).accent),
                column![
                    text(trf("Se connecter à %1$s", &[&t.label()])).size(15).font(BOLD),
                    soft(tr("Sans l'enregistrer ; il reste dans Connexions › Historique.")),
                ]
                .spacing(2)
                .width(Fill),
                chips,
            ]
            .spacing(14)
            .align_y(Alignment::Center),
        )
        .padding([12, 14])
        .style(theme::card);
        list = list.push(card);
    }

    // The groups as filters, with their number of hosts.
    let mut chips = row![kit::count_chip(
        tr("Tous"),
        app.hosts.len(),
        app.group_filter.is_none(),
        None,
        Message::GroupFilter(None)
    )]
    .spacing(8)
    .align_y(Alignment::Center);
    for g in &app.groups {
        let n = app.hosts.iter().filter(|h| h.group_id == Some(g.id)).count();
        chips = chips.push(kit::count_chip(
            g.name.clone(),
            n,
            app.group_filter == Some(g.id),
            Some(argb(g.color)),
            Message::GroupFilter(Some(g.id)),
        ));
    }
    chips = chips.push(
        button(container(icon(I_TUNE).size(20)).center(Length::Fixed(22.0)))
            .padding(9)
            .style(theme::square)
            .on_press(Message::OpenModal(Some(crate::app::Modal::Groups))),
    );
    list = list.push(chips.wrap());

    for g in &app.groups {
        if app.group_filter.is_some_and(|f| f != g.id) {
            continue;
        }
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
    } else if shown.is_empty() && quick.is_none() {
        list = list.push(soft(trf("Aucun hôte ne correspond à « %1$s ».", &[&app.search])));
    }

    let top = container(
        column![
            kit::page_header(tr("Hôtes"), None, vec![]),
            row![
                kit::search(&app.search, tr("Hôte ou user@ip"), Message::Search, Message::Search(String::new())),
                kit::square(I_TERMINAL, Message::OpenLocal),
            ]
            .spacing(10)
            .align_y(Alignment::Center),
        ]
        .spacing(14),
    )
    .padding(Padding {
        top: 0.0,
        right: 28.0,
        bottom: 14.0,
        left: 28.0,
    });
    stack![column![top, scrollable(list).height(Fill)], kit::fab(Message::NewHost)].into()
}

pub(super) fn snippets(app: &App) -> Element<'_, Message> {
    let q = app.search.to_lowercase();
    let mut shown: Vec<&nautile_core::model::Snippet> = app
        .snippets
        .iter()
        .filter(|s| q.is_empty() || s.name.to_lowercase().contains(&q) || s.command.to_lowercase().contains(&q))
        .filter(|s| app.snippet_tag.as_ref().is_none_or(|t| &s.tag == t))
        .collect();
    shown.sort_by(|a, b| {
        b.run_count
            .cmp(&a.run_count)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    let can_run = app.target_tab().is_some();

    let mut tags: Vec<&str> = app.snippets.iter().map(|s| s.tag.as_str()).filter(|t| !t.is_empty()).collect();
    tags.sort_unstable();
    tags.dedup();
    let mut chips = row![kit::count_chip(
        tr("Tous"),
        app.snippets.len(),
        app.snippet_tag.is_none(),
        None,
        Message::SnippetFilter(None)
    )]
    .spacing(8)
    .align_y(Alignment::Center);
    for t in tags {
        let n = app.snippets.iter().filter(|s| s.tag == t).count();
        chips = chips.push(kit::count_chip(
            t.to_owned(),
            n,
            app.snippet_tag.as_deref() == Some(t),
            None,
            Message::SnippetFilter(Some(t.to_owned())),
        ));
    }

    let mut list = Column::new().spacing(12).padding(Padding {
        top: 4.0,
        right: 28.0,
        bottom: 100.0,
        left: 28.0,
    });
    list = list.push(chips.wrap()).push(Space::new().height(4));
    if !shown.is_empty() {
        list = list.push(kit::section_label(tr("Les plus utilisés d'abord")));
    }
    for s in shown {
        let selected = matches!(&app.editor, Some(Editor::Snippet(f)) if f.original.as_ref().map(|o| o.id) == Some(s.id));
        let one_line = s.command.lines().collect::<Vec<_>>().join(" ⏎ ");
        let mut footer = row![label(
            [
                Some(s.tag.clone()).filter(|t| !t.is_empty()),
                Some(if s.run_count > 0 {
                    trf("%1$s exécution%2$s", &[&s.run_count, &if s.run_count > 1 { "s" } else { "" }])
                } else {
                    tr("jamais lancé")
                }),
            ]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>()
            .join(" · ")
        )
        .size(12)]
        .spacing(6)
        .align_y(Alignment::Center);
        for v in s.variables() {
            footer = footer.push(
                container(text(format!("${{{v}}}")).font(MONO).size(11))
                    .padding([2, 6])
                    .style(|t: &iced::Theme| theme::chip(theme::nt(t).accent)(t)),
            );
        }
        let body = column![
            text(&s.name).font(BOLD).size(16),
            container(text(one_line).font(MONO).size(13))
                .padding([8, 12])
                .width(Fill)
                .style(theme::code_block),
            footer,
        ]
        .spacing(8)
        .width(Fill);
        let play = button(container(text('\u{F040A}').font(MONO).size(28).line_height(1.0)).center(Length::Fixed(30.0)))
            .padding(11)
            .style(theme::fab)
            .on_press_maybe(can_run.then_some(Message::RunSnippet(s.id)));
        let copy = button(icon(I_COPY).size(18))
            .padding(8)
            .style(theme::ghost)
            .on_press(Message::CopySnippet(s.id));
        list = list.push(
            button(row![body, copy, play].spacing(12).align_y(Alignment::Center))
                .width(Fill)
                .padding(Padding {
                    top: 14.0,
                    right: 12.0,
                    bottom: 14.0,
                    left: 16.0,
                })
                .style(move |t: &iced::Theme, st| {
                    let n = theme::nt(t);
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
                            width: 1.0,
                            radius: 16.0.into(),
                        },
                        ..Default::default()
                    }
                })
                .on_press(Message::EditSnippet(s.id)),
        );
    }
    if app.snippets.is_empty() {
        list = list.push(empty(
            I_SNIPPETS,
            tr("Aucun snippet"),
            tr("Les variables comme ${SERVICE} sont demandées au lancement."),
        ));
    } else if !can_run {
        list = list.push(label(tr("Ouvrez une session pour exécuter un snippet.")));
    }
    stack![
        column![
            header(tr("Snippets"), None, Some((&app.search, tr("Rechercher un snippet"))), vec![]),
            scrollable(list).height(Fill)
        ],
        kit::fab(Message::NewSnippet)
    ]
    .into()
}

pub(super) fn key_list(app: &App) -> Column<'_, Message> {
    let mut list = Column::new().spacing(8).padding(Padding {
        top: 4.0,
        right: 28.0,
        bottom: 28.0,
        left: 28.0,
    });
    let mut rows: Vec<Element<Message>> = vec![];
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
        rows.push(container(row).padding([12, 14]).into());
    }
    if !rows.is_empty() {
        list = list.push(kit::card(rows));
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

pub(super) fn keys(app: &App) -> Element<'_, Message> {
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

pub(super) fn identity_list(app: &App) -> Column<'_, Message> {
    let mut list = Column::new().spacing(8).padding(pad());
    let mut rows: Vec<Element<Message>> = vec![];
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
        rows.push(
            button(body)
                .width(Fill)
                .padding([12, 14])
                .style(theme::card_pick(selected))
                .on_press(Message::EditIdentity(i.id))
                .into(),
        );
    }
    if !rows.is_empty() {
        list = list.push(kit::card(rows));
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

pub(super) fn known_list(app: &App) -> Column<'_, Message> {
    let mut list = Column::new().spacing(8).padding(pad());
    let mut rows: Vec<Element<Message>> = vec![];
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
        rows.push(container(body).padding([12, 14]).into());
    }
    if !rows.is_empty() {
        list = list.push(kit::card(rows));
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

pub(super) fn kind_label(k: ForwardKind) -> String {
    match k {
        ForwardKind::Local => tr("Locale"),
        ForwardKind::Remote => tr("Distante"),
        ForwardKind::Dynamic => "SOCKS5".into(),
    }
}

pub(super) fn tunnels(app: &App) -> Element<'_, Message> {
    let mut list = Column::new().spacing(8).padding(pad());
    let mut rows: Vec<Element<Message>> = vec![];
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
        rows.push(
            button(body)
                .width(Fill)
                .padding([12, 14])
                .style(theme::card_pick(selected))
                .on_press(Message::EditForward(x.id))
                .into(),
        );
    }
    if !rows.is_empty() {
        list = list.push(kit::card(rows));
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

/// « Connexions »: the sessions open now (with their last lines), then every connection made, by day.
pub(super) fn connections(app: &App) -> Element<'_, Message> {
    let n = theme::nt(&app.theme());
    let mut list = Column::new().spacing(22).padding(Padding {
        top: 4.0,
        right: 28.0,
        bottom: 28.0,
        left: 28.0,
    });
    if !app.tabs.is_empty() {
        let cards: Vec<Element<Message>> = app
            .tabs
            .iter()
            .map(|t| {
                let preview: Element<Message> = if t.remote.is_some() {
                    soft(tr("Bureau à distance")).into()
                } else if t.files.is_some() {
                    soft(tr("Fichiers SFTP")).into()
                } else {
                    let screen = t.term.screen_text();
                    let lines: Vec<&str> = screen.lines().filter(|l| !l.trim().is_empty()).collect();
                    let tail = lines[lines.len().saturating_sub(3)..].join("\n");
                    let theme = app.term_theme();
                    let (bg, fg) = (theme.background, theme.foreground);
                    container(
                        text(if tail.is_empty() { "…".to_owned() } else { tail })
                            .font(MONO)
                            .size(12)
                            .color(fg),
                    )
                    .padding([8, 10])
                    .width(Fill)
                    .style(move |_| container::Style {
                        background: Some(bg.into()),
                        border: iced::border::rounded(8),
                        ..Default::default()
                    })
                    .into()
                };
                let state = match &t.state {
                    TabState::Open => tr("Connecté"),
                    TabState::Connecting(_) => tr("Connexion…"),
                    TabState::Closed(Some(_)) => tr("Coupé"),
                    TabState::Closed(None) => tr("Fermé"),
                };
                let os = t.host_id.and_then(|h| app.host_by(h)).and_then(|h| h.os_id.clone());
                button(
                    column![
                        row![
                            kit::host_avatar(os.as_deref(), &t.name, argb(t.color), 36.0),
                            column![text(t.name.clone()).size(15).font(BOLD), soft(state)]
                                .spacing(2)
                                .width(Fill),
                            icon_button(I_CLOSE, Message::CloseTab(t.id)),
                        ]
                        .spacing(12)
                        .align_y(Alignment::Center),
                        preview,
                    ]
                    .spacing(10),
                )
                .width(Fill)
                .padding(14)
                .style(theme::card_pick(false))
                .on_press(Message::SelectTab(Some(t.id)))
                .into()
            })
            .collect();
        list = list.push(
            column![
                kit::section_head(Some(n.green), tr("Ouvertes"), trf("%1$s session(s)", &[&app.tabs.len()]), None),
                kit::card(cards),
            ]
            .spacing(10),
        );
    }

    let log = app.db.as_ref().map(|db| db.connection_log(300)).unwrap_or_default();
    if log.is_empty() && app.tabs.is_empty() {
        list = list.push(empty(
            I_HISTORY,
            tr("Aucune connexion pour l'instant"),
            tr("Les sessions ouvertes et l'historique des connexions apparaissent ici, pour y revenir en un clic."),
        ));
    }
    // By day: « Aujourd'hui », « Hier », then the date.
    let today = chrono::Local::now().date_naive();
    let mut day: Option<chrono::NaiveDate> = None;
    let mut rows: Vec<Element<Message>> = Vec::new();
    let flush = |list: Column<'static, Message>,
                 day: Option<chrono::NaiveDate>,
                 rows: &mut Vec<Element<'static, Message>>|
     -> Column<'static, Message> {
        let Some(d) = day else { return list };
        if rows.is_empty() {
            return list;
        }
        let name = if d == today {
            tr("Aujourd'hui")
        } else if Some(d) == today.pred_opt() {
            tr("Hier")
        } else {
            d.format("%d/%m/%Y").to_string()
        };
        list.push(column![kit::section_head(None, name, String::new(), None), kit::card(std::mem::take(rows))].spacing(10))
    };
    for e in log {
        let at = chrono::DateTime::from_timestamp_millis(e.at).map(|d| d.with_timezone(&chrono::Local));
        let d = at.map(|a| a.date_naive());
        if d != day {
            list = flush(list, day, &mut rows);
            day = d;
        }
        let saved = e.host_id.and_then(|h| app.hosts.iter().find(|x| x.id == h));
        let addr = {
            let mut s = String::new();
            if !e.username.is_empty() {
                s.push_str(&e.username);
                s.push('@');
            }
            s.push_str(&e.hostname);
            s.push_str(&format!(":{}", e.port));
            s
        };
        let mut r = row![
            kit::host_avatar(
                saved.and_then(|h| h.os_id.as_deref()),
                &e.alias,
                saved.map(|h| argb(h.color)).unwrap_or(n.muted),
                36.0
            ),
            column![
                row![
                    text(saved.map(|h| h.alias.clone()).unwrap_or(e.alias.clone())).size(15).font(BOLD),
                    container(text(e.protocol.clone()).size(11).font(BOLD))
                        .padding([1, 7])
                        .style(|t: &iced::Theme| theme::chip(theme::nt(t).blue)(t)),
                ]
                .spacing(8)
                .align_y(Alignment::Center),
                text(addr).font(MONO).size(12).style(|t: &iced::Theme| text::Style {
                    color: Some(theme::nt(t).muted),
                }),
            ]
            .spacing(2)
            .width(Fill),
            soft(at.map(|a| a.format("%H:%M").to_string()).unwrap_or_default()),
        ]
        .spacing(12)
        .align_y(Alignment::Center);
        if saved.is_none() {
            r = r.push(
                button(text(tr("Enregistrer")).size(13))
                    .padding([6, 12])
                    .style(theme::secondary)
                    .on_press(Message::SaveLogged(e.id)),
            );
        }
        r = r.push(icon_button(I_CLOSE, Message::ForgetLogged(e.id)));
        rows.push(
            button(r)
                .width(Fill)
                .padding([10, 14])
                .style(theme::card_pick(false))
                .on_press(Message::Reconnect2(e.id))
                .into(),
        );
    }
    list = flush(list, day, &mut rows);
    column![header(tr("Connexions"), None, None, vec![]), scrollable(list).height(Fill)].into()
}
