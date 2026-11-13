//! The Android app's building blocks (`Components.kt`, `PageHeader.kt`, `OsLogo.kt`, `SettingsComponents.kt`),
//! so that both apps look alike.

use iced::widget::{
    button, column, container, image, mouse_area, opaque, row, scrollable, stack, text, text_input, toggler, Column, Space,
};
use iced::{Alignment, Color, Element, Fill, Length, Padding};
use nautile_core::{tr, trf};

use crate::app::Message;
use crate::theme::{self, alpha, nt, BOLD, ICONS, MONO};

fn color(hex: u32) -> Color {
    Color::from_rgb8((hex >> 16) as u8, (hex >> 8) as u8, hex as u8)
}

pub fn icon<'a>(c: char, size: f32) -> iced::widget::Text<'a> {
    text(c.to_string()).font(ICONS).size(size).line_height(1.0)
}

fn muted_style(t: &iced::Theme) -> text::Style {
    text::Style { color: Some(nt(t).muted) }
}

/// Title of a page: optional back arrow, the Nautile mark, the title and actions on the right.
pub fn page_header<'a>(title: String, back: Option<Message>, actions: Vec<Element<'a, Message>>) -> Element<'a, Message> {
    let mut r = row![].spacing(12).align_y(Alignment::Center);
    let has_back = back.is_some();
    if let Some(m) = back {
        r = r.push(button(icon('\u{E5C4}', 24.0)).padding(6).style(theme::ghost).on_press(m));
    } else {
        r = r.push(image(theme::mark()).height(30));
    }
    r = r.push(text(title).size(26).font(BOLD).width(Fill));
    for a in actions {
        r = r.push(a);
    }
    container(r)
        .padding(Padding {
            top: if has_back { 14.0 } else { 22.0 },
            right: 0.0,
            bottom: 4.0,
            left: 0.0,
        })
        .into()
}

/// The rounded search field with its magnifier (and a clear button when filled).
pub fn search<'a>(value: &str, placeholder: String, on: impl Fn(String) -> Message + 'a, clear: Message) -> Element<'a, Message> {
    let mut r = row![
        icon('\u{E8B6}', 22.0).style(muted_style),
        text_input(&placeholder, value)
            .on_input(on)
            .size(15)
            .padding([12, 0])
            .style(theme::bare_input)
    ]
    .spacing(12)
    .align_y(Alignment::Center);
    if !value.is_empty() {
        r = r.push(button(icon('\u{E5CD}', 18.0)).padding(4).style(theme::ghost).on_press(clear));
    }
    container(r).padding([2, 16]).width(Fill).style(theme::field_box(false)).into()
}

/// A square bordered icon button beside the search field.
pub fn square<'a>(c: char, on: Message) -> Element<'a, Message> {
    button(container(icon(c, 22.0)).center(Length::Fixed(26.0)))
        .padding(14)
        .style(theme::square)
        .on_press(on)
        .into()
}

/// A filter chip with its count; [tint]: a group's colour (a dot is shown), else the accent.
pub fn count_chip<'a>(label: String, count: usize, selected: bool, tint: Option<Color>, on: Message) -> Element<'a, Message> {
    let mut r = row![].spacing(8).align_y(Alignment::Center);
    if let Some(c) = tint {
        r = r.push(dot(c, 10.0));
    }
    r = r.push(text(label).size(14).font(BOLD));
    let badge_tint = tint;
    r = r.push(
        container(text(count.to_string()).size(12).font(BOLD))
            .padding([2, 7])
            .style(move |t: &iced::Theme| {
                let n = nt(t);
                let c = badge_tint.unwrap_or(n.accent);
                container::Style {
                    background: Some(if selected { alpha(c, 0.28) } else { n.bg }.into()),
                    text_color: Some(if selected { n.text } else { n.muted }),
                    border: iced::border::rounded(11),
                    ..Default::default()
                }
            }),
    );
    button(r)
        .padding(Padding {
            top: 8.0,
            right: 8.0,
            bottom: 8.0,
            left: 12.0,
        })
        .style(theme::count_chip(selected, tint))
        .on_press(on)
        .into()
}

pub fn dot<'a>(c: Color, size: f32) -> Element<'a, Message> {
    container(Space::new().width(size).height(size))
        .style(move |_| container::Style {
            background: Some(c.into()),
            border: iced::border::rounded(size / 2.0),
            ..Default::default()
        })
        .into()
}

/// An uppercase section label, muted.
pub fn section_label<'a>(s: String) -> iced::widget::Text<'a> {
    text(s.to_uppercase()).size(12).font(BOLD).style(muted_style)
}

/// A group's heading in a list: its dot, its name, and on the right how many items it holds.
pub fn section_head<'a>(tint: Option<Color>, name: String, right: String, action: Option<Element<'a, Message>>) -> Element<'a, Message> {
    let mut r = row![].spacing(10).align_y(Alignment::Center);
    if let Some(c) = tint {
        r = r.push(dot(c, 10.0));
    }
    r = r.push(section_label(name)).push(Space::new().width(Fill));
    r = r.push(text(right).size(13).style(muted_style));
    if let Some(a) = action {
        r = r.push(a);
    }
    container(r).padding([0, 2]).into()
}

/// Rounded bordered surface holding rows, separated by thin lines (Android's `NtCard`).
pub fn card<'a>(rows: Vec<Element<'a, Message>>) -> Element<'a, Message> {
    let mut c = Column::new();
    for (i, r) in rows.into_iter().enumerate() {
        if i > 0 {
            c = c.push(container(Space::new().height(1)).width(Fill).style(theme::divider));
        }
        c = c.push(r);
    }
    container(c).width(Fill).clip(true).style(theme::card).into()
}

/// The coloured square behind an icon (settings rows).
pub fn icon_tile<'a>(c: char, tint: Color) -> Element<'a, Message> {
    container(icon(c, 20.0))
        .center(Length::Fixed(36.0))
        .style(theme::soft_tile(tint, 10.0))
        .into()
}

/// A row that opens a sub-page, with its summary (Android's `NavRow`).
pub fn nav_row<'a>(
    c: char,
    tint: Color,
    title: String,
    detail: String,
    trailing: Option<Element<'a, Message>>,
    on: Message,
) -> Element<'a, Message> {
    let mut r = row![
        icon_tile(c, tint),
        column![text(title).size(15).font(BOLD), text(detail).size(12).style(muted_style)]
            .spacing(2)
            .width(Fill),
    ]
    .spacing(12)
    .align_y(Alignment::Center);
    if let Some(t) = trailing {
        r = r.push(t);
    }
    r = r.push(icon('\u{E5CC}', 22.0).style(muted_style));
    button(r).width(Fill).padding([12, 14]).style(theme::card_row).on_press(on).into()
}

/// A title, its explanation and a switch (Android's `ToggleRow`).
pub fn toggle_row<'a>(
    lead: Option<Element<'a, Message>>,
    title: String,
    detail: String,
    value: bool,
    on: impl Fn(bool) -> Message + 'a,
) -> Element<'a, Message> {
    let mut r = row![].spacing(12).align_y(Alignment::Center);
    if let Some(l) = lead {
        r = r.push(l);
    }
    r = r.push(
        column![text(title).size(15).font(BOLD), text(detail).size(13).style(muted_style)]
            .spacing(3)
            .width(Fill),
    );
    r = r.push(toggler(value).on_toggle(on).size(26).style(theme::toggler));
    container(r).padding([14, 14]).width(Fill).into()
}

/// The round « + » of a list, bottom right.
pub fn fab<'a>(on: Message) -> Element<'a, Message> {
    container(
        button(container(icon('\u{E145}', 30.0)).center(Length::Fixed(36.0)))
            .padding(12)
            .style(theme::fab)
            .on_press(on),
    )
    .align_right(Fill)
    .align_bottom(Fill)
    .padding(24)
    .into()
}

/// An outlined field whose label sits on its border (Android's `NtTextField`).
pub fn float_field<'a>(label: String, input: text_input::TextInput<'a, Message>) -> iced::widget::Container<'a, Message> {
    let field = column![Space::new().height(9), input.padding([13, 14]).size(15).style(theme::input)];
    let tag = container(text(label).size(12).style(|t: &iced::Theme| text::Style {
        color: Some(nt(t).text_soft),
    }))
    .padding([0, 5])
    .style(|t: &iced::Theme| container::Style {
        background: Some(nt(t).surface.into()),
        ..Default::default()
    });
    container(stack![field, row![Space::new().width(10), tag]]).width(Fill)
}

/// « il y a 4 min », « hier », « 2 j »… (Android's `relativeTime`).
pub fn relative_time(at: i64, now: i64) -> String {
    let min = (now - at) / 60_000;
    match min {
        m if m < 1 => tr("à l'instant"),
        m if m < 60 => trf("il y a %1$s min", &[&m]),
        m if m < 60 * 24 => trf("il y a %1$s h", &[&(m / 60)]),
        m if m < 60 * 24 * 2 => tr("hier"),
        m if m < 60 * 24 * 7 => trf("%1$s j", &[&(m / (60 * 24))]),
        m if m < 60 * 24 * 30 => trf("%1$s sem", &[&(m / (60 * 24 * 7))]),
        m => trf("%1$s mois", &[&(m / (60 * 24 * 30))]),
    }
}

/// An OS logo: a Nerd Font glyph (or a short label) in the brand's colour (Android's `OsBrands`).
pub struct OsBrand {
    pub glyph: String,
    pub color: Color,
    pub is_glyph: bool,
}

pub fn os_brand(os_id: Option<&str>) -> Option<OsBrand> {
    let id = os_id?;
    let g = |cp: u32, c: u32| OsBrand {
        glyph: char::from_u32(cp).map(String::from).unwrap_or_default(),
        color: color(c),
        is_glyph: true,
    };
    let t = |label: &str, c: u32| OsBrand {
        glyph: label.into(),
        color: color(c),
        is_glyph: false,
    };
    Some(match id {
        "ubuntu" => g(0xF31B, 0xE95420),
        "kubuntu" => g(0xF333, 0x0079C1),
        "debian" => g(0xF306, 0xD70A53),
        "raspbian" => g(0xF315, 0xC51A4A),
        "fedora" => g(0xF30A, 0x51A2DA),
        "centos" => g(0xF304, 0x9CCD2A),
        "redhat" => g(0xF316, 0xEE0000),
        "rocky" => g(0xF32B, 0x10B981),
        "almalinux" => g(0xF31D, 0xFFC107),
        "arch" => g(0xF303, 0x1793D1),
        "manjaro" => g(0xF312, 0x35BF5C),
        "endeavouros" => g(0xF322, 0x9B6BDF),
        "artix" => g(0xF31F, 0x10A0CC),
        "garuda" => g(0xF337, 0x9F7AEA),
        "alpine" => g(0xF300, 0x4A90C2),
        "opensuse" => g(0xF314, 0x73BA25),
        "mint" => g(0xF30E, 0x86BE43),
        "popos" => g(0xF32A, 0x48B9C7),
        "kali" => g(0xF327, 0x367BF0),
        "parrot" => g(0xF329, 0x05EEFF),
        "nixos" => g(0xF313, 0x7EBAE4),
        "gentoo" => g(0xF30D, 0xB7B2E6),
        "void" => g(0xF32E, 0x5FA36B),
        "elementary" => g(0xF309, 0x64BAFF),
        "zorin" => g(0xF32F, 0x15A6F0),
        "slackware" => g(0xF318, 0x8B9FD8),
        "devuan" => g(0xF307, 0xA693D6),
        "deepin" => g(0xF321, 0x2CA7F8),
        "solus" => g(0xF32D, 0x5294E2),
        "mageia" => g(0xF310, 0x2397D4),
        "coreos" => g(0xF305, 0x53A3DA),
        "qubes" => g(0xF342, 0x63A0FF),
        "tails" => g(0xF343, 0xA772D1),
        "postmarketos" => g(0xF374, 0x009900),
        "macos" => g(0xF302, 0xE8EDF2),
        "freebsd" => g(0xF30C, 0xEB3B45),
        "openbsd" => g(0xF328, 0xF2CA30),
        "illumos" => g(0xF326, 0xFF6347),
        "windows" => g(0xF17A, 0x00A4EF),
        "android" => g(0xF17B, 0x3DDC84),
        "linux" => g(0xF31A, 0xF5C518),
        "proxmox" => t("PVE", 0xE57000),
        "unraid" => t("UR", 0xF15A2C),
        "synology" => t("DSM", 0x4D8FE3),
        "openwrt" => t("WRT", 0x00B5E2),
        "truenas" => t("TN", 0x0095D5),
        "unknown" => return None,
        // Unknown Linux distributions fall back to Tux.
        _ => g(0xF31A, 0xF5C518),
    })
}

/// A host's avatar: its OS logo when known, else the alias initial in the host colour.
pub fn host_avatar<'a>(os_id: Option<&str>, alias: &str, host_color: Color, size: f32) -> Element<'a, Message> {
    let brand = os_brand(os_id);
    let tint = brand.as_ref().map(|b| b.color).unwrap_or(host_color);
    let base = size * 0.375;
    let content: iced::widget::Text<'a> = match brand {
        None => text(alias.chars().next().map(|c| c.to_uppercase().to_string()).unwrap_or_default())
            .size(base)
            .font(BOLD),
        Some(b) if b.is_glyph => text(b.glyph).size(base * 1.45).font(MONO),
        Some(b) => text(b.glyph).size(base * 0.7).font(BOLD),
    };
    container(content.style(move |_| text::Style { color: Some(tint) }))
        .center(Length::Fixed(size))
        .style(theme::soft_tile(tint, size * 0.275))
        .into()
}

/// Any control (a drop-down…) with its label on the border, like [float_field].
pub fn float_box<'a>(label: String, control: impl Into<Element<'a, Message>>) -> iced::widget::Container<'a, Message> {
    let tag = container(text(label).size(12).style(|t: &iced::Theme| text::Style {
        color: Some(nt(t).text_soft),
    }))
    .padding([0, 5])
    .style(|t: &iced::Theme| container::Style {
        background: Some(nt(t).surface.into()),
        ..Default::default()
    });
    container(stack![
        column![Space::new().height(9), control.into()],
        row![Space::new().width(10), tag]
    ])
    .width(Fill)
}

/// A pop-up over the window: the rest is dimmed and does not take clicks;
/// a click outside closes it when [dismiss] is given.
pub fn overlay<'a>(card: impl Into<Element<'a, Message>>, dismiss: Option<Message>) -> Element<'a, Message> {
    let back = container(opaque(card)).center(Fill).padding(24).style(|_| container::Style {
        background: Some(Color { a: 0.55, ..Color::BLACK }.into()),
        ..Default::default()
    });
    let area = mouse_area(back);
    opaque(match dismiss {
        Some(m) => area.on_press(m),
        None => area,
    })
}

/// The top of a pop-up: an optional back arrow, its title and subtitle, and the close button.
pub fn dialog_head<'a>(title: String, subtitle: Option<String>, back: Option<Message>, close: Message) -> Element<'a, Message> {
    let mut r = row![].spacing(10).align_y(Alignment::Center);
    if let Some(m) = back {
        r = r.push(button(icon('\u{E5C4}', 22.0)).padding(6).style(theme::ghost).on_press(m));
    }
    let mut t = column![text(title).size(20).font(BOLD)].spacing(2).width(Fill);
    if let Some(s) = subtitle {
        t = t.push(text(s).size(13).style(muted_style));
    }
    r = r
        .push(t)
        .push(button(icon('\u{E5CD}', 22.0)).padding(6).style(theme::ghost).on_press(close));
    container(r)
        .padding(Padding {
            top: 16.0,
            right: 14.0,
            bottom: 10.0,
            left: 22.0,
        })
        .into()
}

/// A pop-up's card: its head, its body (scrolled when long) and its buttons at the bottom.
pub fn dialog<'a>(
    head: Element<'a, Message>,
    body: impl Into<Element<'a, Message>>,
    foot: Option<Element<'a, Message>>,
    width: f32,
    max_height: f32,
) -> Element<'a, Message> {
    // The body scrolls within what the head and the buttons leave.
    let reserved = if foot.is_some() { 170.0 } else { 90.0 };
    let mut c = column![
        head,
        container(
            scrollable(container(body).padding(Padding {
                top: 6.0,
                right: 22.0,
                bottom: 18.0,
                left: 22.0,
            }))
            .height(Length::Shrink)
        )
        .max_height((max_height - reserved).max(120.0)),
    ];
    if let Some(f) = foot {
        c = c
            .push(container(Space::new().height(1)).width(Fill).style(theme::divider))
            .push(container(f).padding([14, 22]));
    }
    container(c)
        .width(Length::Fixed(width))
        .max_height(max_height)
        .clip(true)
        .style(theme::dialog)
        .into()
}

/// A choice in a list of kinds (« Nouvel hôte »): its tile, title and explanation.
pub fn kind_row<'a>(c: char, tint: Color, title: String, detail: String, on: Message) -> Element<'a, Message> {
    button(
        row![
            container(icon(c, 22.0))
                .center(Length::Fixed(42.0))
                .style(theme::soft_tile(tint, 12.0)),
            column![text(title).size(15).font(BOLD), text(detail).size(13).style(muted_style)]
                .spacing(2)
                .width(Fill),
            icon('\u{E5CC}', 22.0).style(muted_style),
        ]
        .spacing(14)
        .align_y(Alignment::Center),
    )
    .width(Fill)
    .padding([10, 12])
    .style(theme::card_pick(false))
    .on_press(on)
    .into()
}
