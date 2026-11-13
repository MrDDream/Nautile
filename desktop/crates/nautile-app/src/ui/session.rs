//! A session: its tab bar, terminal or remote desktop, files, assistant, and the dialogs over it.

use super::*;
#[allow(unused_imports)]
use iced::widget::{button, column, container, image, pick_list, row, scrollable, stack, text, text_editor, text_input, Column, Space};

/// The tabs: the hosts page, then one per session.
pub(super) fn tab_bar(app: &App) -> Element<'_, Message> {
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
    let shows_terminal = app
        .active_tab
        .and_then(|id| app.tab(id))
        .is_some_and(|t| t.files.is_none() && t.remote.is_none());
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
pub(super) fn session<'a>(app: &'a App, t: &'a Tab) -> Element<'a, Message> {
    let id = t.id;
    if let Some(f) = &t.files {
        return files_view(app, t, f);
    }
    if let Some(r) = &t.remote {
        return remote_desktop(app, t, r);
    }
    if let Some(m) = &t.monitor {
        return monitor_view(app, t, m);
    }
    let focused = app.prompts.is_empty() && app.editor.is_none();
    let term: Element<Message> = term_view::view(
        &t.term,
        app.settings.terminal_family().font,
        app.settings.terminal_font(),
        focused,
        move |a| Message::Term(id, a),
    )
    .into();
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
        TabState::Open => {
            // Commands typed before on this host, offered as you type.
            let (typed, list) = app.suggestions(id);
            if list.is_empty() {
                with_panel(term.into())
            } else {
                let rows = column(list.into_iter().map(|c| {
                    let rest = c[typed.len()..].to_owned();
                    button(
                        row![
                            text(typed.clone()).font(MONO).size(13),
                            text(rest.clone()).font(MONO).size(13).style(|th: &iced::Theme| text::Style {
                                color: Some(theme::nt(th).muted),
                            }),
                        ]
                        .spacing(0),
                    )
                    .width(Fill)
                    .padding([5, 10])
                    .style(theme::card_pick(false))
                    .on_press(Message::Complete(id, rest))
                    .into()
                }))
                .spacing(2);
                let card = container(column![rows, label(tr("→ complète avec la première · clic pour choisir"))].spacing(6))
                    .padding(8)
                    .max_width(560)
                    .style(theme::dialog);
                with_panel(stack![term, container(card).align_bottom(Fill).align_right(Fill).padding(16)].into())
            }
        }
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
pub(super) fn prompt_dialog<'a>(app: &'a App, p: &'a Prompt) -> Element<'a, Message> {
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
    kit::overlay(container(body).padding(24).max_width(540).style(theme::dialog), None)
}

/// The values of a snippet's `${VARIABLES}` before it runs.
pub(super) fn snippet_dialog<'a>(app: &'a App, id: i64, vars: &'a [(String, String)]) -> Element<'a, Message> {
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
    kit::overlay(
        container(c).padding(24).max_width(540).style(theme::dialog),
        Some(Message::SnippetRunCancel),
    )
}

/// The app is locked: the code, nothing else.
pub(super) fn lock_screen(app: &App) -> Element<'_, Message> {
    let pin = app.settings.lock_method == Some(nautile_core::lock::LockMethod::Pin);
    let mut c = column![
        container(icon(I_LOCK).size(30))
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

pub(super) const I_UP: char = '\u{E5D8}'; // arrow_upward
pub(super) const I_REFRESH: char = '\u{E5D5}'; // refresh
pub(super) const I_HOME: char = '\u{E88A}'; // home
pub(super) const I_FOLDER_PLUS: char = '\u{E2CC}'; // create_new_folder
pub(super) const I_FILE: char = '\u{E24D}'; // insert_drive_file
pub(super) const I_DOWNLOAD: char = '\u{F090}'; // download
pub(super) const I_UPLOAD: char = '\u{F09B}'; // upload
pub(super) const I_RENAME: char = '\u{E9A2}'; // drive_file_rename_outline

/// The SFTP tab: this computer | the server, then the transfers.
pub(super) fn files_view<'a>(app: &'a App, t: &'a Tab, f: &'a crate::files::Files) -> Element<'a, Message> {
    let id = t.id;
    if let Some(ed) = &f.editor {
        return remote_editor(id, ed);
    }
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

pub(super) fn small_button<'a>(c: char, m: Message) -> Element<'a, Message> {
    button(icon(c).size(15)).padding([4, 6]).style(theme::ghost).on_press(m).into()
}

/// One side of the file browser.
pub(super) fn files_pane<'a>(app: &'a App, t: &'a Tab, f: &'a crate::files::Files, remote: bool) -> Element<'a, Message> {
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
            if remote && !e.dir && f.remote_ready() {
                r = r.push(small_button(I_EDIT, fm(FilesMsg::EditFile(e.name.clone()))));
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

pub(super) fn edit_row<'a>(id: u64, value: &'a str, placeholder: String, action: String) -> Element<'a, Message> {
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

pub(super) const I_SPARKLES: char = '\u{E65F}'; // auto_awesome

pub(super) fn md_settings(app: &App) -> iced::widget::markdown::Settings {
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
    s.h1_size = 18.into();
    s.h2_size = 16.into();
    s.h3_size = 15.into();
    s.code_size = 13.into();
    s
}

/// The assistant beside the session.
pub(super) fn assistant_panel(app: &App) -> Element<'_, Message> {
    use crate::assistant::AiMsg as A;
    let st = &app.assistant;
    let kind = app.ai_provider();
    let model = app.ai_config(kind).model;
    let head = row![
        icon(I_SPARKLES).size(16),
        text(tr("Assistant")).font(BOLD).size(15),
        provider_switch(app, kind, &model),
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

/// A VNC or RDP tab: the desktop, and what is happening when it is not shown.
pub(super) fn remote_desktop<'a>(app: &'a App, t: &'a Tab, r: &'a crate::sessions::RemoteTab) -> Element<'a, Message> {
    let id = t.id;
    let focused = app.prompts.is_empty() && app.editor.is_none();
    let screen: Element<Message> = crate::remote_view::view(r.image.as_ref(), r.size, r.cursor.as_ref(), focused, move |a| {
        Message::RemoteAction(id, a)
    })
    .no_upscale(app.settings.remote_no_upscale)
    .into();
    let banner = |body: Element<'a, Message>| -> Element<'a, Message> {
        container(container(body).padding([14, 18]).max_width(560).style(theme::card))
            .center_x(Fill)
            .align_bottom(Fill)
            .padding(24)
            .into()
    };
    match &t.state {
        TabState::Open => screen,
        TabState::Connecting(step) => stack![
            screen,
            banner(
                row![
                    icon(I_DESKTOP),
                    text(if step.is_empty() {
                        trf("Connexion à %1$s…", &[&t.name])
                    } else {
                        step.clone()
                    })
                    .size(14),
                    Space::new().width(Fill),
                    secondary(tr("Annuler"), Message::CloseTab(id))
                ]
                .spacing(12)
                .align_y(Alignment::Center)
                .into()
            )
        ]
        .into(),
        TabState::Closed(reason) => stack![
            screen,
            banner(
                row![
                    text(reason.clone().unwrap_or_else(|| tr("Session terminée"))).size(14),
                    Space::new().width(Fill),
                    primary(tr("Reconnecter"), Some(Message::Reconnect(id))),
                    secondary(tr("Fermer"), Message::CloseTab(id)),
                ]
                .spacing(8)
                .align_y(Alignment::Center)
                .into()
            )
        ]
        .into(),
    }
}

/// The provider in use, as a chip; a menu of the ready ones when several are on.
fn provider_switch<'a>(app: &App, kind: nautile_net::ai::ProviderKind, model: &str) -> Element<'a, Message> {
    use crate::assistant::AiMsg as A;
    let ready = app.ai_ready_providers();
    if ready.len() < 2 {
        return container(text(format!("{} · {}", kind.short_label(), nautile_net::ai::pretty_model(model))).size(11))
            .padding([2, 8])
            .style(|t: &iced::Theme| theme::chip(theme::nt(t).blue)(t))
            .into();
    }
    let choices: Vec<ProviderChoice> = ready.into_iter().map(ProviderChoice).collect();
    pick_list(choices, Some(ProviderChoice(kind)), |c: ProviderChoice| am(A::Use(c.0)))
        .text_size(12)
        .padding([3, 8])
        .style(theme::select)
        .into()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct ProviderChoice(nautile_net::ai::ProviderKind);

impl std::fmt::Display for ProviderChoice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0.short_label())
    }
}

/// A gauge of the monitor: its name, value, detail, bar and, when given, its small curve.
fn gauge<'a>(
    name: String,
    value: String,
    detail: String,
    percent: f32,
    history: Option<&std::collections::VecDeque<f32>>,
) -> Element<'a, Message> {
    let tone = move |t: &iced::Theme| {
        let n = theme::nt(t);
        if percent >= 90.0 {
            n.pink
        } else if percent >= 70.0 {
            n.accent
        } else {
            n.green
        }
    };
    let mut c = column![
        kit::section_label(name),
        text(value)
            .size(30)
            .font(BOLD)
            .style(move |t: &iced::Theme| text::Style { color: Some(tone(t)) }),
        soft(detail),
        iced::widget::progress_bar(0.0..=100.0, percent)
            .girth(8)
            .style(move |t: &iced::Theme| {
                iced::widget::progress_bar::Style {
                    background: theme::nt(t).key_bg.into(),
                    bar: tone(t).into(),
                    border: iced::border::rounded(4),
                }
            }),
    ]
    .spacing(8);
    if let Some(h) = history {
        // The last two minutes, one bar per reading.
        let bars = row(h.iter().map(|v| {
            let v = *v;
            container(Space::new().width(Fill).height(Length::Fixed((v / 100.0 * 44.0).max(2.0))))
                .style(move |t: &iced::Theme| container::Style {
                    background: Some(theme::alpha(theme::nt(t).blue, 0.75).into()),
                    border: iced::border::rounded(2),
                    ..Default::default()
                })
                .into()
        }))
        .spacing(2)
        .height(46)
        .align_y(Alignment::End);
        c = c.push(bars);
    }
    container(c).padding(18).width(Fill).style(theme::card).into()
}

fn uptime(s: u64) -> String {
    let (d, h, m) = (s / 86_400, s % 86_400 / 3600, s % 3600 / 60);
    if d > 0 {
        trf("%1$s j %2$s h", &[&d, &h])
    } else if h > 0 {
        trf("%1$s h %2$s min", &[&h, &m])
    } else {
        trf("%1$s min", &[&m])
    }
}

fn gb(kb: u64) -> String {
    let s = if kb >= 1024 * 1024 {
        format!("{:.1} Go", kb as f64 / 1024.0 / 1024.0)
    } else {
        format!("{:.0} Mo", kb as f64 / 1024.0)
    };
    decimal(s)
}

/// A number written the French way (« 3,2 ») unless in English.
fn decimal(s: String) -> String {
    if nautile_core::i18n::is_english() {
        s.replace(" Go", " GB").replace(" Mo", " MB")
    } else {
        s.replace('.', ",")
    }
}

/// A host's CPU, memory, disk and load, read every few seconds over SSH.
fn monitor_view<'a>(app: &'a App, t: &'a Tab, m: &'a crate::sessions::MonitorState) -> Element<'a, Message> {
    let id = t.id;
    let head = kit::page_header(t.name.clone(), None, vec![]);
    let body: Element<Message> = match (&t.state, &m.last) {
        (TabState::Closed(reason), _) => column![
            soft(reason.clone().unwrap_or_else(|| tr("Session terminée"))).size(14),
            row![primary(tr("Reconnecter"), Some(Message::Reconnect(id)))],
        ]
        .spacing(12)
        .into(),
        (_, _) if m.unsupported => soft(tr("Ce serveur n'est pas un Linux : le moniteur lit /proc, absent ici."))
            .size(14)
            .into(),
        (_, None) => soft(tr("Première mesure…")).size(14).into(),
        (_, Some(s)) => {
            let cpu = m.cpu.unwrap_or(0.0);
            let load_pct = if s.cpus > 0 { s.load[0] * 100.0 / s.cpus as f32 } else { 0.0 };
            column![
                row![
                    gauge(
                        tr("Processeur"),
                        format!("{cpu:.0} %"),
                        trf("%1$s cœur(s)", &[&s.cpus]),
                        cpu,
                        Some(&m.cpu_history)
                    ),
                    gauge(
                        tr("Mémoire"),
                        format!("{:.0} %", s.mem_percent()),
                        format!(
                            "{} / {}",
                            gb(s.mem_total_kb - s.mem_available_kb.min(s.mem_total_kb)),
                            gb(s.mem_total_kb)
                        ),
                        s.mem_percent(),
                        Some(&m.mem_history)
                    ),
                ]
                .spacing(16),
                row![
                    gauge(
                        tr("Disque /"),
                        format!("{:.0} %", s.disk_percent()),
                        format!("{} / {}", gb(s.disk_used_kb), gb(s.disk_size_kb)),
                        s.disk_percent(),
                        None
                    ),
                    gauge(
                        tr("Charge"),
                        decimal(format!("{:.2}", s.load[0])),
                        trf(
                            "5 min : %1$s · 15 min : %2$s · allumé depuis %3$s",
                            &[
                                &decimal(format!("{:.2}", s.load[1])),
                                &decimal(format!("{:.2}", s.load[2])),
                                &uptime(s.uptime_s)
                            ]
                        ),
                        load_pct.min(100.0),
                        None
                    ),
                ]
                .spacing(16),
                soft(trf("Mesuré toutes les %1$s s par SSH.", &[&nautile_net::monitor::EVERY])),
            ]
            .spacing(16)
            .into()
        }
    };
    let _ = app;
    scrollable(container(column![head, body].spacing(18).max_width(1000)).padding(Padding {
        top: 0.0,
        right: 28.0,
        bottom: 28.0,
        left: 28.0,
    }))
    .height(Fill)
    .into()
}

/// A remote text file in the editor: Ctrl+S saves it back, refused when it changed on the server meanwhile.
fn remote_editor(id: u64, ed: &crate::files::RemoteEditor) -> Element<'_, Message> {
    use crate::files::FilesMsg;
    let fm = move |m: FilesMsg| Message::Files(id, m);
    let modified = ed.modified();
    let mut head = row![
        icon(I_EDIT).size(20),
        column![
            text(ed.path.clone()).font(MONO).size(14),
            soft(if ed.saving {
                tr("Enregistrement…")
            } else if modified {
                tr("Modifié · Ctrl+S pour enregistrer")
            } else {
                tr("Enregistré sur le serveur")
            }),
        ]
        .spacing(2)
        .width(Fill),
    ]
    .spacing(12)
    .align_y(Alignment::Center);
    head = head.push(secondary(tr("Fermer"), fm(FilesMsg::EditorClose))).push(primary(
        tr("Enregistrer"),
        (modified && !ed.saving).then_some(fm(FilesMsg::EditorSave(false))),
    ));
    let mut c = column![head].spacing(12);
    if let Some(e) = &ed.error {
        let mut r = row![pink(e.clone()).width(Fill)].spacing(10).align_y(Alignment::Center);
        if ed.conflict {
            r = r.push(
                button(text(tr("Écraser quand même")).size(13))
                    .padding([7, 12])
                    .style(theme::danger)
                    .on_press(fm(FilesMsg::EditorSave(true))),
            );
        }
        c = c.push(r);
    }
    c = c.push(
        text_editor(&ed.content)
            .on_action(move |a| Message::Files(id, FilesMsg::EditorAction(a)))
            .font(MONO)
            .size(14)
            .height(Fill)
            .padding(14)
            .style(|t: &iced::Theme, s| {
                let n = theme::nt(t);
                let focused = matches!(s, text_editor::Status::Focused { .. });
                text_editor::Style {
                    background: n.bg.into(),
                    border: iced::Border {
                        color: if focused { n.accent } else { n.border },
                        width: 1.0,
                        radius: 12.0.into(),
                    },
                    placeholder: n.placeholder,
                    value: n.text,
                    selection: theme::alpha(n.accent, 0.35),
                }
            }),
    );
    container(c).padding(20).height(Fill).into()
}
