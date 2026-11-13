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
const I_CLOUD: char = '\u{E2BD}'; // cloud
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
        (None, None) => {}
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
