//! What the app looks like: a sidebar, the page, and the editors in centred pop-ups.

use iced::widget::{button, column, container, row, stack, text, text_input, Column, Space};
use iced::{Alignment, Color, Element, Fill, Length, Padding};
use nautile_core::model::{AuthMethod, Host, Protocol};
use nautile_core::{tr, trf};

use crate::app::{App, AuthChoice, Choice, Editor, HostField, KeysTab, Message, Page, ProtocolChoice, COLORS};
use crate::kit;
use crate::sessions::{Tab, TabState, TunnelState};
use crate::storage::{Appearance, Language};
use crate::term::{themes, widget as term_view};
use crate::theme::{self, argb, BOLD, MONO};
use nautile_core::model::ForwardKind;
use nautile_net::Prompt;

// Nerd Font (Material Design) icons.

mod editor;
mod lists;
mod session;
mod settings;
use editor::*;
use lists::*;
use session::*;
use settings::*;

const I_HOSTS: char = '\u{E875}'; // dns
const I_SNIPPETS: char = '\u{EB8E}'; // terminal
const I_KEYS: char = '\u{E73C}'; // key
const I_SETTINGS: char = '\u{E429}'; // tune
const I_ADD: char = '\u{E145}'; // add
const I_EDIT: char = '\u{E3C9}'; // edit
const I_TERMINAL: char = '\u{EB8E}'; // terminal
const I_DESKTOP: char = '\u{E30C}'; // desktop_windows
const I_COPY: char = '\u{F08A}'; // content_copy
const I_FOLDER: char = '\u{E2C7}'; // folder
const I_CLOSE: char = '\u{E5CD}'; // close
const I_TRASH: char = '\u{E872}'; // delete
const I_TUNNEL: char = '\u{E8D4}'; // swap_horiz
const I_PERSON: char = '\u{E7FD}'; // person
const I_SHIELD: char = '\u{E8E8}'; // verified_user
const I_PLAY: char = '\u{E037}'; // play_arrow
const I_STOP: char = '\u{E047}'; // stop
const I_LOCK: char = '\u{E897}'; // lock
const I_PHONE: char = '\u{E324}'; // phone_android
const I_PALETTE: char = '\u{E40A}'; // palette
const I_CLOUD_SYNC: char = '\u{EB5A}'; // cloud_sync
const I_FINGERPRINT: char = '\u{E90D}'; // fingerprint
const I_BACKUP: char = '\u{E864}'; // backup
const I_SPARKLE: char = '\u{E65F}'; // auto_awesome
const I_STORAGE: char = '\u{E1DB}'; // storage
const I_SCREEN_SHARE: char = '\u{E0E2}'; // screen_share
const I_LAN: char = '\u{EB2F}'; // lan
const I_COMPUTER: char = '\u{E30A}'; // computer
const I_USB: char = '\u{E1E0}'; // usb
const I_UP_ARROW: char = '\u{E5D8}'; // arrow_upward
const I_DOWN_ARROW: char = '\u{E5DB}'; // arrow_downward
const I_TUNE: char = '\u{E429}'; // tune
const I_DESCRIPTION: char = '\u{E873}'; // description
const I_GEAR: char = '\u{E8B8}'; // settings
const I_SYNC: char = '\u{E627}'; // sync
const I_DEVICES: char = '\u{E1B1}'; // devices
const I_INFO: char = '\u{E88E}'; // info
const I_CLOUD_DONE: char = '\u{E2BF}'; // cloud_done
const I_CLOUD_UPLOAD: char = '\u{E2C3}'; // cloud_upload
const I_INVENTORY: char = '\u{E1A1}'; // inventory_2
const I_HISTORY: char = '\u{E889}'; // history
const I_DOWNLOAD_FILE: char = '\u{E2C4}'; // file_download
const I_VPN_KEY: char = '\u{E0DA}'; // vpn_key
const I_VPN_LOCK: char = '\u{E62F}'; // vpn_lock
const I_CHEVRON_RIGHT: char = '\u{E5CC}'; // chevron_right
const I_KEYBOARD: char = '\u{E312}'; // keyboard
const I_FILE_OPEN: char = '\u{EAF3}'; // file_open
const I_MONITOR: char = '\u{EAA2}'; // monitor_heart
const I_EXPAND_LESS: char = '\u{E5CE}'; // expand_less
const I_FOLDER_MOVE: char = '\u{E675}'; // drive_file_move
const I_CHECK: char = '\u{E5CA}'; // check
const I_SEARCH: char = '\u{E8B6}'; // search
const I_PLUS: char = '\u{E145}'; // add

pub fn icon<'a>(c: char) -> iced::widget::Text<'a> {
    text(c.to_string()).font(theme::ICONS).size(20).line_height(1.0)
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
            Page::Connections => connections(app),
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
    let main = row![sidebar(app), sidebar_edge(), center];
    let mut layers = stack![main];
    let max_h = (app.window.height - 48.0).max(320.0);
    match (&app.modal, &app.editor) {
        (Some(crate::app::Modal::Groups), e) => layers = layers.push(groups_modal(app, e.as_ref(), max_h)),
        (_, Some(e)) => layers = layers.push(kit::overlay(editor(app, e, max_h), None)),
        (Some(crate::app::Modal::NewHost), None) => layers = layers.push(new_host_modal(app, max_h)),
        (Some(crate::app::Modal::AiProvider(k)), None) => layers = layers.push(ai_provider_modal(app, *k, max_h)),
        (Some(crate::app::Modal::AiModels(k)), None) => layers = layers.push(ai_models_modal(app, *k, max_h)),
        (Some(crate::app::Modal::AiPrompt), None) => layers = layers.push(ai_prompt_modal(app, max_h)),
        (Some(crate::app::Modal::SyncInfo), None) => layers = layers.push(sync_info_modal(max_h)),
        (Some(crate::app::Modal::LanInfo), None) => layers = layers.push(lan_info_modal(max_h)),
        (Some(crate::app::Modal::LanPair), None) => layers = layers.push(lan_pair_modal(app, max_h)),
        (Some(crate::app::Modal::AddPlace), None) => layers = layers.push(add_place_modal(app, max_h)),
        (Some(crate::app::Modal::Target), None) => layers = layers.push(target_modal(app, max_h)),
        (Some(crate::app::Modal::Password), None) => layers = layers.push(password_modal(app, max_h)),
        (Some(crate::app::Modal::Export), None) => layers = layers.push(export_modal(app, max_h)),
        (Some(crate::app::Modal::Update), None) => layers = layers.push(update_modal(app, max_h)),
        (Some(crate::app::Modal::ImportHosts), None) => layers = layers.push(import_hosts_modal(app, max_h)),
        (None, None) => {}
    }
    if app.import.file.is_some() {
        layers = layers.push(restore_modal(app, max_h));
    }
    if let Some((id, at)) = app.menu {
        layers = layers.push(host_menu(app, id, at));
    }
    if let Some(p) = &app.palette {
        layers = layers.push(palette(app, p));
    }
    if matches!(app.drag, Some(crate::app::Drag::Host(_))) {
        layers = layers.push(
            container(
                container(text(tr("Déposez l'hôte sur un groupe pour l'y ranger")).size(14))
                    .padding([10, 16])
                    .style(theme::toast),
            )
            .align_bottom(Fill)
            .center_x(Fill)
            .padding(24),
        );
    }
    if let Some(id) = app.closing {
        layers = layers.push(close_dialog(app, id));
    }
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
    let compact = app.settings.sidebar_compact;
    let tip = |content: Element<'static, Message>, name: String| -> Element<'static, Message> {
        if compact {
            iced::widget::tooltip(
                content,
                container(text(name).size(13)).padding([6, 10]).style(theme::toast),
                iced::widget::tooltip::Position::Right,
            )
            .gap(8)
            .into()
        } else {
            content
        }
    };
    let item = |c: char, name: String, p: Page, n: Option<usize>| -> Element<'static, Message> {
        let active = app.page == p && app.active_tab.is_none();
        let mut r = row![icon(c).size(22)].spacing(14).align_y(Alignment::Center);
        if !compact {
            r = r.push(text(name.clone()).size(15).font(if active { BOLD } else { theme::UI }));
            if let Some(n) = n {
                r = r.push(Space::new().width(Fill)).push(text(n.to_string()).size(12));
            }
        }
        let b = button(r)
            .width(Fill)
            .padding(if compact { [11, 13] } else { [11, 14] })
            .style(theme::nav(active))
            .on_press(Message::Nav(p));
        tip(b.into(), name)
    };
    let mut brand = row![iced::widget::image(theme::mark()).height(28)]
        .spacing(12)
        .align_y(Alignment::Center);
    if !compact {
        brand = brand.push(text("Nautile").font(BOLD).size(21));
    }
    let place = if app.paths.portable {
        tr("Mode portable")
    } else {
        tr("Données chiffrées sur cet ordinateur")
    };
    let mut c = column![
        container(brand).padding([0, if compact { 10 } else { 6 }]),
        Space::new().height(20),
        item(I_HOSTS, tr("Hôtes"), Page::Hosts, Some(app.hosts.len())),
        item(
            I_HISTORY,
            tr("Connexions"),
            Page::Connections,
            (!app.tabs.is_empty()).then_some(app.tabs.len())
        ),
        item(I_SNIPPETS, tr("Snippets"), Page::Snippets, Some(app.snippets.len())),
        item(I_KEYS, tr("Trousseau"), Page::Keys, Some(app.keys.len())),
        item(I_TUNNEL, tr("Tunnels"), Page::Tunnels, Some(app.forwards.len())),
        Space::new().height(Fill),
    ]
    .spacing(4);
    if app.settings.locks() {
        let mut r = row![icon(I_LOCK).size(22)].spacing(14).align_y(Alignment::Center);
        if !compact {
            r = r.push(text(tr("Verrouiller")).size(15));
        }
        c = c.push(tip(
            button(r)
                .width(Fill)
                .padding(if compact { [11, 13] } else { [11, 14] })
                .style(theme::nav(false))
                .on_press(Message::LockNow)
                .into(),
            tr("Verrouiller"),
        ));
    }
    c = c.push(item(I_SETTINGS, tr("Réglages"), Page::Settings, None));
    if !compact {
        c = c.push(container(label(place).size(11)).padding([6, 14]));
    }
    container(c)
        .width(if compact {
            72.0
        } else {
            app.settings.sidebar_width.unwrap_or(232.0)
        })
        .height(Fill)
        .padding(Padding {
            top: 22.0,
            right: if compact { 10.0 } else { 12.0 },
            bottom: 14.0,
            left: if compact { 10.0 } else { 12.0 },
        })
        .style(theme::sidebar)
        .into()
}

/// The sidebar's edge: drag it to change its width, double-click to fold it to its icons.
fn sidebar_edge<'a>() -> Element<'a, Message> {
    iced::widget::mouse_area(container(Space::new().width(5).height(Fill)))
        .on_press(Message::SidebarGrab)
        .on_double_click(Message::SidebarToggle)
        .interaction(iced::mouse::Interaction::ResizingHorizontally)
        .into()
}

/// A page's top: the mark and its title, actions on the right, then its search field.
fn header<'a>(
    name: String,
    _n: Option<usize>,
    search: Option<(&'a str, String)>,
    actions: Vec<Element<'a, Message>>,
) -> Element<'a, Message> {
    let mut c = column![kit::page_header(name, None, actions)].spacing(14);
    if let Some((value, placeholder)) = search {
        c = c.push(kit::search(value, placeholder, Message::Search, Message::Search(String::new())));
    }
    container(c)
        .padding(Padding {
            top: 0.0,
            right: 28.0,
            bottom: 14.0,
            left: 28.0,
        })
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

fn pad() -> Padding {
    Padding {
        top: 4.0,
        right: 28.0,
        bottom: 28.0,
        left: 28.0,
    }
}

fn segmented<'a, T: Copy + PartialEq + 'a>(options: Vec<(T, String)>, current: T, on: impl Fn(T) -> Message + 'a) -> Element<'a, Message> {
    container(
        row(options.into_iter().map(|(v, name)| {
            button(text(name).size(14).font(BOLD).center().width(Fill).height(Fill))
                .width(Fill)
                .height(40)
                .padding([0, 12])
                .style(theme::segment(v == current))
                .on_press(on(v))
                .into()
        }))
        .spacing(4),
    )
    .padding(4)
    .style(theme::segmented_box)
    .into()
}

/// A title and its switch (Android's `Switch` rows).
fn switch<'a>(name: String, value: bool, on: impl Fn(bool) -> Message + 'a) -> Element<'a, Message> {
    row![
        text(name).size(14).width(Fill),
        iced::widget::toggler(value).on_toggle(on).size(22).style(theme::toggler)
    ]
    .spacing(12)
    .align_y(Alignment::Center)
    .into()
}

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

fn am(m: crate::assistant::AiMsg) -> Message {
    Message::Ai(m)
}

/// « Fermer la session ? » for a connected one.
fn close_dialog(app: &App, id: u64) -> Element<'_, Message> {
    let name = app.tab(id).map(|t| t.name.clone()).unwrap_or_default();
    let body = column![
        text(trf("Fermer « %1$s » ?", &[&name])).size(20).font(BOLD),
        soft(tr("La session est connectée : ce qui tourne au premier plan sera arrêté.")).size(14),
        row![
            Space::new().width(Fill),
            secondary(tr("Annuler"), Message::CloseAnswer(false)),
            button(text(tr("Fermer")).size(14).font(BOLD))
                .padding([9, 18])
                .style(theme::danger)
                .on_press(Message::CloseAnswer(true)),
        ]
        .spacing(10)
        .align_y(Alignment::Center),
    ]
    .spacing(14);
    kit::overlay(
        container(body).padding(24).max_width(480).style(theme::dialog),
        Some(Message::CloseAnswer(false)),
    )
}

/// The menu of a host, where it was right-clicked.
fn host_menu(app: &App, id: i64, at: iced::Point) -> Element<'_, Message> {
    let Some(h) = app.hosts.iter().find(|h| h.id == id) else {
        return Space::new().into();
    };
    let ssh = matches!(h.protocol, nautile_core::model::Protocol::Ssh | nautile_core::model::Protocol::Mosh);
    let item = |c: char, name: String, on: Message| -> Element<'_, Message> {
        button(row![icon(c).size(18), text(name).size(14)].spacing(12).align_y(Alignment::Center))
            .width(Fill)
            .padding([8, 12])
            .style(theme::card_pick(false))
            .on_press(on)
            .into()
    };
    let mut c = column![
        container(text(h.alias.clone()).size(13).font(BOLD)).padding(Padding {
            top: 6.0,
            right: 12.0,
            bottom: 4.0,
            left: 12.0,
        }),
        item(I_TERMINAL, tr("Connecter"), Message::Connect(id)),
    ]
    .spacing(0);
    if ssh {
        c = c.push(item(I_FOLDER, tr("Fichiers SFTP"), Message::OpenFiles(id))).push(item(
            I_MONITOR,
            tr("Moniteur"),
            Message::OpenMonitor(id),
        ));
    }
    c = c
        .push(container(Space::new().height(1)).width(Fill).style(theme::divider))
        .push(item(
            I_EDIT,
            tr("Modifier"),
            Message::Batch(vec![Message::CloseMenu, Message::EditHost(id)]),
        ))
        .push(item(I_COPY, tr("Dupliquer"), Message::DuplicateHost(id)))
        .push(item(
            if app.menu_move { I_EXPAND_LESS } else { I_FOLDER_MOVE },
            tr("Déplacer vers…"),
            Message::MenuMove,
        ));
    if app.menu_move {
        let mut groups = column![].spacing(0);
        for g in &app.groups {
            let here = h.group_id == Some(g.id);
            groups = groups.push(
                button(
                    row![
                        kit::dot(argb(g.color), 9.0),
                        text(g.name.clone()).size(13).width(Fill),
                        icon(if here { I_CHECK } else { ' ' }).size(16)
                    ]
                    .spacing(10)
                    .align_y(Alignment::Center),
                )
                .width(Fill)
                .padding(Padding {
                    top: 6.0,
                    right: 12.0,
                    bottom: 6.0,
                    left: 34.0,
                })
                .style(theme::card_pick(here))
                .on_press(Message::MoveHost(id, Some(g.id))),
            );
        }
        groups = groups.push(
            button(text(tr("Sans groupe")).size(13))
                .width(Fill)
                .padding(Padding {
                    top: 6.0,
                    right: 12.0,
                    bottom: 6.0,
                    left: 34.0,
                })
                .style(theme::card_pick(h.group_id.is_none()))
                .on_press(Message::MoveHost(id, None)),
        );
        c = c.push(groups);
    }
    c = c.push(container(Space::new().height(1)).width(Fill).style(theme::divider)).push(
        button(
            row![
                icon(I_TRASH).size(18),
                text(if app.menu_confirm {
                    tr("Confirmer la suppression")
                } else {
                    tr("Supprimer")
                })
                .size(14)
            ]
            .spacing(12)
            .align_y(Alignment::Center),
        )
        .width(Fill)
        .padding([8, 12])
        .style(|t: &iced::Theme, s| {
            let mut b = theme::card_pick(false)(t, s);
            b.text_color = theme::nt(t).pink;
            b
        })
        .on_press(Message::DeleteHostNow(id)),
    );
    // Kept inside the window.
    let items = if ssh { 7.0 } else { 5.0 };
    let groups = if app.menu_move { (app.groups.len() + 1) as f32 * 34.0 } else { 0.0 };
    let (w, height) = (250.0, 50.0 + items * 38.0 + groups);
    let x = at.x.min(app.window.width - w - 12.0).max(8.0);
    let y = at.y.min(app.window.height - height - 12.0).max(8.0);
    let card = container(c).width(w).padding(6).style(theme::dialog);
    iced::widget::mouse_area(
        container(iced::widget::opaque(card))
            .padding(Padding {
                top: y,
                left: x,
                ..Padding::ZERO
            })
            .width(Fill)
            .height(Fill),
    )
    .on_press(Message::CloseMenu)
    .on_right_press(Message::CloseMenu)
    .into()
}

/// « Ctrl+K »: the search and its results, chosen with the arrows and Enter.
fn palette<'a>(app: &'a App, p: &'a crate::palette::PaletteState) -> Element<'a, Message> {
    let results = crate::palette::results(app, &p.query);
    let input = row![
        icon(I_SEARCH).size(22),
        text_input(&tr("Hôte, snippet, réglage, action…"), &p.query)
            .id("palette")
            .on_input(Message::PaletteQuery)
            .on_submit(Message::PaletteEnter)
            .size(17)
            .padding([14, 0])
            .style(theme::bare_input),
        container(text("Échap").size(11).font(MONO))
            .padding([3, 7])
            .style(|t: &iced::Theme| container::Style {
                background: Some(theme::nt(t).key_bg.into()),
                border: iced::border::rounded(6),
                ..Default::default()
            }),
    ]
    .spacing(12)
    .padding([0, 18])
    .align_y(Alignment::Center);
    let mut list = column![].spacing(2).padding(8);
    if results.is_empty() {
        list = list.push(container(soft(tr("Rien ne correspond."))).padding(12));
    }
    for (i, it) in results.into_iter().enumerate() {
        let selected = i == p.selected;
        let mut r = row![
            icon(it.icon).size(18),
            column![text(it.title).size(14).font(BOLD), soft(it.detail).size(12)]
                .spacing(1)
                .width(Fill),
        ]
        .spacing(12)
        .align_y(Alignment::Center);
        if let Some(k) = it.keys {
            r = r.push(
                text(k.replace("Maj", &tr("Maj")))
                    .font(MONO)
                    .size(11)
                    .style(|t: &iced::Theme| text::Style {
                        color: Some(theme::nt(t).muted),
                    }),
            );
        }
        list = list.push(
            button(r)
                .width(Fill)
                .padding([8, 12])
                .style(move |t: &iced::Theme, s| {
                    let mut b = theme::card_pick(selected)(t, s);
                    b.border.radius = 10.0.into();
                    b
                })
                .on_press(Message::PaletteRun(i)),
        );
    }
    let card = container(column![
        input,
        container(Space::new().height(1)).width(Fill).style(theme::divider),
        list,
        container(label(tr("↑ ↓ pour choisir · Entrée pour lancer"))).padding(Padding {
            top: 0.0,
            right: 18.0,
            bottom: 12.0,
            left: 18.0,
        }),
    ])
    .width(640)
    .style(theme::dialog);
    iced::widget::mouse_area(
        container(iced::widget::opaque(card))
            .center_x(Fill)
            .padding(Padding {
                top: 80.0,
                ..Padding::ZERO
            })
            .height(Fill)
            .style(|_| container::Style {
                background: Some(Color { a: 0.45, ..Color::BLACK }.into()),
                ..Default::default()
            }),
    )
    .on_press(Message::Palette(false))
    .into()
}
