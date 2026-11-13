//! The terminal fonts of the Android app (`TerminalFonts.kt`, same ids): Nerd Font Mono variants, so that
//! Powerline prompts (Powerlevel10k, Starship) draw right. Only JetBrains Mono is loaded at start; the others
//! when chosen or previewed.

use iced::Font;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TermFont {
    pub id: &'static str,
    pub name: &'static str,
    pub font: Font,
    /// `None`: always there (the bundled default, or the system's monospace).
    pub bytes: Option<&'static [u8]>,
    /// Has the Powerline and icon glyphs.
    pub nerd: bool,
}

pub const JETBRAINS: TermFont = TermFont {
    id: "jetbrains",
    name: "JetBrains Mono",
    font: crate::theme::MONO,
    bytes: None,
    nerd: true,
};

pub const ALL: [TermFont; 6] = [
    JETBRAINS,
    TermFont {
        id: "fira",
        name: "Fira Code",
        font: Font::with_name("FiraCode Nerd Font Mono"),
        bytes: Some(include_bytes!("../../assets/fonts/FiraCodeNerd.ttf")),
        nerd: true,
    },
    TermFont {
        id: "hack",
        name: "Hack",
        font: Font::with_name("Hack Nerd Font Mono"),
        bytes: Some(include_bytes!("../../assets/fonts/HackNerd.ttf")),
        nerd: true,
    },
    TermFont {
        id: "source",
        name: "Source Code Pro",
        font: Font::with_name("SauceCodePro Nerd Font Mono"),
        bytes: Some(include_bytes!("../../assets/fonts/SauceCodeProNerd.ttf")),
        nerd: true,
    },
    TermFont {
        id: "cascadia",
        name: "Cascadia Code",
        font: Font::with_name("CaskaydiaCove Nerd Font Mono"),
        bytes: Some(include_bytes!("../../assets/fonts/CaskaydiaCoveNerd.ttf")),
        nerd: true,
    },
    TermFont {
        id: "system",
        name: "Monospace du système",
        font: Font::MONOSPACE,
        bytes: None,
        nerd: false,
    },
];

pub fn by_id(id: &str) -> TermFont {
    ALL.into_iter().find(|f| f.id == id).unwrap_or(JETBRAINS)
}
