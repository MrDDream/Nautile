//! The mobile app's colours (app/.../ui/theme/Theme.kt, « Nt ») and the widget styles built on them.

use iced::widget::{button, container, pick_list, text_input};
use iced::{border, Background, Border, Color, Font, Shadow, Theme, Vector};

pub const UI: Font = Font::with_name("Inter");
pub const MONO: Font = Font::with_name("JetBrainsMonoNL Nerd Font Mono");
pub const BOLD: Font = Font {
    weight: iced::font::Weight::Semibold,
    ..UI
};

/// Google's Material Icons, outlined: the same icons as the Android app (Compose `Icons.Outlined`).
pub const ICONS: Font = Font::with_name("Material Icons Outlined");

pub const FONT_UI: &[u8] = include_bytes!("../assets/fonts/Inter.ttf");
pub const FONT_ICONS: &[u8] = include_bytes!("../assets/fonts/MaterialIconsOutlined.otf");

/// The Nautile mark (spiral and cursor) shown before page titles, as on Android.
pub fn mark() -> iced::widget::image::Handle {
    static MARK: std::sync::OnceLock<iced::widget::image::Handle> = std::sync::OnceLock::new();
    MARK.get_or_init(|| {
        let decoder = png::Decoder::new(std::io::Cursor::new(include_bytes!("../assets/mark.png")));
        let decoded = decoder.read_info().ok().and_then(|mut r| {
            let mut buf = vec![0; r.output_buffer_size()];
            let info = r.next_frame(&mut buf).ok()?;
            buf.truncate(info.buffer_size());
            Some((info.width, info.height, buf))
        });
        match decoded {
            Some((w, h, rgba)) => iced::widget::image::Handle::from_rgba(w, h, rgba),
            None => iced::widget::image::Handle::from_rgba(1, 1, vec![0u8; 4]),
        }
    })
    .clone()
}
pub const FONT_MONO: &[u8] = include_bytes!("../assets/fonts/JetBrainsMonoNerd.ttf");

const fn rgb(hex: u32) -> Color {
    Color::from_rgb8(((hex >> 16) & 0xff) as u8, ((hex >> 8) & 0xff) as u8, (hex & 0xff) as u8)
}

/// Every colour of the interface.
#[derive(Debug, Clone, Copy)]
pub struct Nt {
    pub dark: bool,
    pub bg: Color,
    pub bar: Color,
    pub surface: Color,
    pub border: Color,
    pub key_bg: Color,
    pub text: Color,
    pub text_soft: Color,
    pub muted: Color,
    pub placeholder: Color,
    pub accent: Color,
    pub on_accent: Color,
    pub green: Color,
    pub blue: Color,
    pub pink: Color,
}

pub const DARK: Nt = Nt {
    dark: true,
    bg: rgb(0x0B0E12),
    bar: rgb(0x10141A),
    surface: rgb(0x141920),
    border: rgb(0x252E39),
    key_bg: rgb(0x1B222B),
    text: rgb(0xE8EDF2),
    text_soft: rgb(0xC5CED7),
    muted: rgb(0x94A1AE),
    placeholder: rgb(0x6B7784),
    accent: rgb(0xFFB547),
    on_accent: rgb(0x1A1205),
    green: rgb(0x5BD69A),
    blue: rgb(0x7AA2F7),
    pink: rgb(0xF38BA8),
};

pub const LIGHT: Nt = Nt {
    dark: false,
    bg: rgb(0xF6F4EF),
    bar: rgb(0xFFFFFF),
    surface: rgb(0xFFFFFF),
    border: rgb(0xE2DED4),
    key_bg: rgb(0xEFEBE3),
    text: rgb(0x1A1F26),
    text_soft: rgb(0x3F4852),
    muted: rgb(0x5A636E),
    placeholder: rgb(0x6A7480),
    accent: rgb(0x9A5B00),
    on_accent: rgb(0xFFFFFF),
    green: rgb(0x1F7A4A),
    blue: rgb(0x2E4FA3),
    pink: rgb(0xA3254A),
};

/// The palette of the active theme.
pub fn nt(theme: &Theme) -> Nt {
    if theme.extended_palette().is_dark {
        DARK
    } else {
        LIGHT
    }
}

pub fn theme(dark: bool) -> Theme {
    let n = if dark { DARK } else { LIGHT };
    Theme::custom(
        if dark { "Nautile sombre" } else { "Nautile clair" }.to_owned(),
        iced::theme::Palette {
            background: n.bg,
            text: n.text,
            primary: n.accent,
            success: n.green,
            warning: n.accent,
            danger: n.pink,
        },
    )
}

/// A host's colour (ARGB, as stored by Android).
pub fn argb(c: i64) -> Color {
    let c = c as u32;
    Color::from_rgba8(
        ((c >> 16) & 0xff) as u8,
        ((c >> 8) & 0xff) as u8,
        (c & 0xff) as u8,
        ((c >> 24) & 0xff) as f32 / 255.0,
    )
}

pub fn alpha(c: Color, a: f32) -> Color {
    Color { a, ..c }
}

// --- Containers ---

pub fn sidebar(t: &Theme) -> container::Style {
    let n = nt(t);
    container::Style {
        background: Some(n.bar.into()),
        border: Border {
            color: n.border,
            width: 1.0,
            radius: 0.0.into(),
        },
        ..Default::default()
    }
}

pub fn page(t: &Theme) -> container::Style {
    container::Style {
        background: Some(nt(t).bg.into()),
        text_color: Some(nt(t).text),
        ..Default::default()
    }
}

pub fn card(t: &Theme) -> container::Style {
    let n = nt(t);
    container::Style {
        background: Some(n.surface.into()),
        border: Border {
            color: n.border,
            width: 1.0,
            radius: 16.0.into(),
        },
        ..Default::default()
    }
}

/// A pop-up's card: the surface, rounded, with a shadow.
pub fn dialog(t: &Theme) -> container::Style {
    let n = nt(t);
    container::Style {
        background: Some(n.surface.into()),
        border: Border {
            color: n.border,
            width: 1.0,
            radius: 20.0.into(),
        },
        shadow: iced::Shadow {
            color: Color { a: 0.35, ..Color::BLACK },
            offset: iced::Vector::new(0.0, 12.0),
            blur_radius: 40.0,
        },
        ..Default::default()
    }
}

/// A divider line inside a card, between two rows.
pub fn divider(t: &Theme) -> container::Style {
    container::Style {
        background: Some(nt(t).border.into()),
        ..Default::default()
    }
}

/// A coloured square behind an icon (settings rows, host avatars).
pub fn soft_tile(color: Color, radius: f32) -> impl Fn(&Theme) -> container::Style {
    move |_| container::Style {
        background: Some(alpha(color, 0.15).into()),
        text_color: Some(color),
        border: border::rounded(radius),
        ..Default::default()
    }
}

/// The darker block a snippet's command sits in.
pub fn code_block(t: &Theme) -> container::Style {
    let n = nt(t);
    container::Style {
        background: Some(n.bg.into()),
        text_color: Some(n.text_soft),
        border: border::rounded(12),
        ..Default::default()
    }
}

/// The panel around a segmented control.
pub fn segmented_box(t: &Theme) -> container::Style {
    let n = nt(t);
    container::Style {
        background: Some(n.surface.into()),
        border: Border {
            color: n.border,
            width: 1.0,
            radius: 14.0.into(),
        },
        ..Default::default()
    }
}

pub fn panel(t: &Theme) -> container::Style {
    let n = nt(t);
    container::Style {
        background: Some(n.surface.into()),
        border: Border {
            color: n.border,
            width: 1.0,
            radius: 0.0.into(),
        },
        shadow: Shadow {
            color: alpha(Color::BLACK, 0.25),
            offset: Vector::new(-4.0, 0.0),
            blur_radius: 18.0,
        },
        ..Default::default()
    }
}

pub fn chip(color: Color) -> impl Fn(&Theme) -> container::Style {
    move |_| container::Style {
        background: Some(alpha(color, 0.14).into()),
        text_color: Some(color),
        border: border::rounded(8),
        ..Default::default()
    }
}

pub fn tile(color: Color) -> impl Fn(&Theme) -> container::Style {
    move |_| container::Style {
        background: Some(alpha(color, 0.14).into()),
        text_color: Some(color),
        border: border::rounded(10),
        ..Default::default()
    }
}

pub fn toast(t: &Theme) -> container::Style {
    let n = nt(t);
    container::Style {
        background: Some(n.text.into()),
        text_color: Some(n.bg),
        border: border::rounded(10),
        shadow: Shadow {
            color: alpha(Color::BLACK, 0.3),
            offset: Vector::new(0.0, 4.0),
            blur_radius: 16.0,
        },
        ..Default::default()
    }
}

// --- Buttons ---

fn base(bg: Option<Color>, text: Color, border_color: Color, radius: f32) -> button::Style {
    button::Style {
        background: bg.map(Background::Color),
        text_color: text,
        border: Border {
            color: border_color,
            width: if border_color.a > 0.0 { 1.0 } else { 0.0 },
            radius: radius.into(),
        },
        ..Default::default()
    }
}

/// Main action: filled with the accent.
pub fn primary(t: &Theme, s: button::Status) -> button::Style {
    let n = nt(t);
    let bg = match s {
        button::Status::Hovered => alpha(n.accent, 0.88),
        button::Status::Disabled => alpha(n.accent, 0.35),
        _ => n.accent,
    };
    base(Some(bg), n.on_accent, Color::TRANSPARENT, 14.0)
}

/// The round « + » of the lists (Android's floating action button).
pub fn fab(t: &Theme, s: button::Status) -> button::Style {
    let n = nt(t);
    let mut st = primary(t, s);
    st.border.radius = 30.0.into();
    st.shadow = Shadow {
        color: alpha(Color::BLACK, 0.35),
        offset: Vector::new(0.0, 4.0),
        blur_radius: 14.0,
    };
    st.text_color = n.on_accent;
    st
}

/// A filter chip with its count (Android's `CountChip`); [tint]: a group's colour or the accent.
pub fn count_chip(selected: bool, tint: Option<Color>) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |t, s| {
        let n = nt(t);
        let c = tint.unwrap_or(n.accent);
        let bg = if selected {
            Some(alpha(c, 0.16))
        } else if matches!(s, button::Status::Hovered) {
            Some(n.key_bg)
        } else {
            Some(n.surface)
        };
        base(
            bg,
            if selected { n.text } else { n.text_soft },
            if selected { c } else { n.border },
            12.0,
        )
    }
}

/// A square bordered icon button (beside the search field).
pub fn square(t: &Theme, s: button::Status) -> button::Style {
    let n = nt(t);
    let bg = if matches!(s, button::Status::Hovered) {
        n.key_bg
    } else {
        n.surface
    };
    base(Some(bg), n.text_soft, n.border, 14.0)
}

/// A row of a card that opens something (no own background).
pub fn card_row(t: &Theme, s: button::Status) -> button::Style {
    let n = nt(t);
    let bg = matches!(s, button::Status::Hovered | button::Status::Pressed).then_some(alpha(n.text, 0.04));
    base(bg, n.text, Color::TRANSPARENT, 0.0)
}

/// A row of a card that opens in the side panel: tinted while open.
pub fn card_pick(selected: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |t, s| {
        let n = nt(t);
        let bg = if selected {
            Some(alpha(n.accent, 0.10))
        } else {
            matches!(s, button::Status::Hovered | button::Status::Pressed).then_some(alpha(n.text, 0.04))
        };
        base(bg, n.text, Color::TRANSPARENT, 0.0)
    }
}

/// A light action: the accent, tinted background.
pub fn tonal(t: &Theme, s: button::Status) -> button::Style {
    let n = nt(t);
    let a = if matches!(s, button::Status::Hovered) { 0.26 } else { 0.16 };
    base(Some(alpha(n.accent, a)), n.accent, Color::TRANSPARENT, 10.0)
}

/// Secondary action: outlined.
pub fn secondary(t: &Theme, s: button::Status) -> button::Style {
    let n = nt(t);
    let bg = matches!(s, button::Status::Hovered).then_some(n.key_bg);
    base(bg, n.text, n.border, 14.0)
}

pub fn danger(t: &Theme, s: button::Status) -> button::Style {
    let n = nt(t);
    let bg = matches!(s, button::Status::Hovered).then_some(alpha(n.pink, 0.12));
    base(bg, n.pink, alpha(n.pink, 0.5), 10.0)
}

/// Text-only button (icons, links).
pub fn ghost(t: &Theme, s: button::Status) -> button::Style {
    let n = nt(t);
    let bg = matches!(s, button::Status::Hovered | button::Status::Pressed).then_some(n.key_bg);
    base(bg, n.text_soft, Color::TRANSPARENT, 8.0)
}

/// A row of a list (a host, a snippet…).
pub fn row_item(selected: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |t, s| {
        let n = nt(t);
        let bg = if selected {
            Some(alpha(n.accent, 0.12))
        } else if matches!(s, button::Status::Hovered) {
            Some(n.key_bg)
        } else {
            None
        };
        base(bg, n.text, if selected { alpha(n.accent, 0.5) } else { Color::TRANSPARENT }, 10.0)
    }
}

/// An entry of the sidebar.
pub fn nav(active: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |t, s| {
        let n = nt(t);
        let bg = if active {
            Some(alpha(n.accent, 0.14))
        } else if matches!(s, button::Status::Hovered) {
            Some(n.key_bg)
        } else {
            None
        };
        base(bg, if active { n.accent } else { n.text_soft }, Color::TRANSPARENT, 10.0)
    }
}

/// One choice of a segmented control.
pub fn segment(active: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |t, s| {
        let n = nt(t);
        let bg = if active {
            Some(n.accent)
        } else if matches!(s, button::Status::Hovered) {
            Some(n.key_bg)
        } else {
            None
        };
        base(bg, if active { n.on_accent } else { n.text_soft }, Color::TRANSPARENT, 10.0)
    }
}

// --- Inputs ---

pub fn input(t: &Theme, s: text_input::Status) -> text_input::Style {
    let n = nt(t);
    let focused = matches!(s, text_input::Status::Focused { .. });
    text_input::Style {
        background: Background::Color(n.surface),
        border: Border {
            color: if focused { n.accent } else { n.border },
            width: 1.0,
            radius: 12.0.into(),
        },
        icon: n.muted,
        placeholder: n.placeholder,
        value: n.text,
        selection: alpha(n.accent, 0.35),
    }
}

pub fn select(t: &Theme, s: pick_list::Status) -> pick_list::Style {
    let n = nt(t);
    let hovered = matches!(s, pick_list::Status::Hovered | pick_list::Status::Opened { .. });
    pick_list::Style {
        text_color: n.text,
        placeholder_color: n.placeholder,
        handle_color: n.muted,
        background: Background::Color(n.surface),
        border: Border {
            color: if hovered { n.accent } else { n.border },
            width: 1.0,
            radius: 12.0.into(),
        },
    }
}

/// Inputs without their own border, inside a bordered box (the search field).
pub fn bare_input(t: &Theme, _s: text_input::Status) -> text_input::Style {
    let n = nt(t);
    text_input::Style {
        background: Background::Color(Color::TRANSPARENT),
        border: Border::default(),
        icon: n.muted,
        placeholder: n.placeholder,
        value: n.text,
        selection: alpha(n.accent, 0.35),
    }
}

/// The bordered box of a field (search, floating-label fields), accent when focused.
pub fn field_box(focused: bool) -> impl Fn(&Theme) -> container::Style {
    move |t| {
        let n = nt(t);
        container::Style {
            background: Some(n.surface.into()),
            border: Border {
                color: if focused { n.accent } else { n.border },
                width: 1.0,
                radius: 14.0.into(),
            },
            ..Default::default()
        }
    }
}

/// Switches in the accent colour (Android's `Switch`).
pub fn toggler(t: &Theme, s: iced::widget::toggler::Status) -> iced::widget::toggler::Style {
    let n = nt(t);
    let on = matches!(
        s,
        iced::widget::toggler::Status::Active { is_toggled: true } | iced::widget::toggler::Status::Hovered { is_toggled: true }
    );
    iced::widget::toggler::Style {
        background: (if on { n.accent } else { n.key_bg }).into(),
        background_border_width: 1.0,
        background_border_color: if on { n.accent } else { n.border },
        foreground: (if on { n.on_accent } else { n.muted }).into(),
        foreground_border_width: 0.0,
        foreground_border_color: Color::TRANSPARENT,
        text_color: None,
        border_radius: None,
        padding_ratio: 0.1,
    }
}
