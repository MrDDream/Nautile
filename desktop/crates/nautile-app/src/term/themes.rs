//! The terminal colour schemes of the Android app (`TerminalThemes.kt`), same ids so the choice syncs.

use iced::Color;

#[derive(Debug, Clone, PartialEq)]
pub struct TermTheme {
    pub id: &'static str,
    /// French name, translated with `tr`.
    pub name: &'static str,
    pub background: Color,
    pub foreground: Color,
    pub cursor: Color,
    pub ansi: [Color; 16],
}

const fn rgb(c: u32) -> Color {
    Color::from_rgb8(((c >> 16) & 0xff) as u8, ((c >> 8) & 0xff) as u8, (c & 0xff) as u8)
}

fn lighten(c: Color, f: f32) -> Color {
    let l = |x: f32| x + (1.0 - x) * f;
    Color::from_rgb(l(c.r), l(c.g), l(c.b))
}

/// A theme from the design's swatches; bright colours are derived (as on Android).
#[allow(clippy::too_many_arguments)]
fn design(
    id: &'static str,
    name: &'static str,
    bg: u32,
    fg: u32,
    red: u32,
    green: u32,
    yellow: u32,
    blue: u32,
    magenta: u32,
    cyan: u32,
    cursor: u32,
    black: u32,
    white: u32,
) -> TermTheme {
    let normal = [black, red, green, yellow, blue, magenta, cyan, white].map(rgb);
    let mut ansi = [Color::BLACK; 16];
    for i in 0..8 {
        ansi[i] = normal[i];
        // Android truncates to integers; close enough.
        ansi[i + 8] = lighten(normal[i], 0.25);
    }
    TermTheme {
        id,
        name,
        background: rgb(bg),
        foreground: rgb(fg),
        cursor: rgb(cursor),
        ansi,
    }
}

fn classic(id: &'static str, name: &'static str, bg: u32, fg: u32, cursor: u32, ansi: [u32; 16]) -> TermTheme {
    TermTheme {
        id,
        name,
        background: rgb(bg),
        foreground: rgb(fg),
        cursor: rgb(cursor),
        ansi: ansi.map(rgb),
    }
}

/// The design's six themes first, then the classics.
/// The themes imported by the user (`data/themes`), after the built-in ones.
static CUSTOM: std::sync::RwLock<Vec<TermTheme>> = std::sync::RwLock::new(Vec::new());

pub fn set_custom(list: Vec<TermTheme>) {
    if let Ok(mut c) = CUSTOM.write() {
        *c = list;
    }
}

pub fn is_custom(id: &str) -> bool {
    id.starts_with("custom-")
}

pub fn all() -> Vec<TermTheme> {
    let mut v = builtin();
    if let Ok(c) = CUSTOM.read() {
        v.extend(c.iter().cloned());
    }
    v
}

fn builtin() -> Vec<TermTheme> {
    vec![
        design(
            "nautile",
            "Nautile Nuit",
            0x07090C,
            0xD6DDE4,
            0xFF7A6B,
            0x5BD69A,
            0xFFB547,
            0x7AA2F7,
            0xC792EA,
            0x7FDBCA,
            0xFFB547,
            0x1B222B,
            0xC5CED7,
        ),
        design(
            "abysse", "Abysse", 0x0A1424, 0xCFE0F5, 0xFF8A8A, 0x4FD1C5, 0xF5C26B, 0x6CB6FF, 0xC4A7FF, 0x5EE6E0, 0x6CB6FF, 0x152238,
            0xCFE0F5,
        ),
        design(
            "phosphore",
            "Phosphore",
            0x030A05,
            0x8EF5A8,
            0xFF6B6B,
            0x5BFF8A,
            0xD4FF6B,
            0x3FD8B0,
            0x9BE89F,
            0x6BFFD8,
            0x5BFF8A,
            0x0D1F12,
            0x8EF5A8,
        ),
        design(
            "corail", "Corail", 0x1A0F0E, 0xF3DED8, 0xFF7A6B, 0x9BD68A, 0xFFC46B, 0x8FB4F0, 0xE59BC4, 0x7FD4C8, 0xFF9C7A, 0x2A1A18,
            0xF3DED8,
        ),
        design(
            "nacre", "Nacre", 0x1C1A22, 0xE8E2F0, 0xF38BA8, 0x9EE6B8, 0xF7D58B, 0xB39DFF, 0xF3A6C8, 0x94E2D5, 0xF3C4DA, 0x2A2732, 0xE8E2F0,
        ),
        classic(
            "papier",
            "Papier",
            0xF6F4EF,
            0x2A2F36,
            0x1A1F26,
            [
                0x2A2F36, 0xB4322A, 0x1F7A4A, 0x8A5200, 0x2E4FA3, 0x8E3A8A, 0x1F6F7A, 0xD8D3C8, 0x5A636E, 0xD0453B, 0x2A9A5E, 0xB06B00,
                0x3F64C4, 0xAA4EA5, 0x2A8A96, 0xFFFFFF,
            ],
        ),
        classic(
            "dracula",
            "Dracula",
            0x282A36,
            0xF8F8F2,
            0xF8F8F2,
            [
                0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2, 0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5,
                0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF,
            ],
        ),
        classic(
            "solarized-dark",
            "Solarized sombre",
            0x002B36,
            0x839496,
            0x93A1A1,
            [
                0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5, 0x002B36, 0xCB4B16, 0x586E75, 0x657B83,
                0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
            ],
        ),
        classic(
            "gruvbox",
            "Gruvbox",
            0x282828,
            0xEBDBB2,
            0xEBDBB2,
            [
                0x282828, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286, 0x689D6A, 0xA89984, 0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F,
                0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2,
            ],
        ),
        classic(
            "nord",
            "Nord",
            0x2E3440,
            0xD8DEE9,
            0xD8DEE9,
            [
                0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0, 0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B,
                0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4,
            ],
        ),
    ]
}

pub fn by_id(id: &str) -> TermTheme {
    let mut all = all();
    let i = all.iter().position(|t| t.id == id).unwrap_or(0);
    all.swap_remove(i)
}
