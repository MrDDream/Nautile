//! X11 keysyms, as VNC sends keys (Android's `Keysyms.kt`).

pub const BACKSPACE: u32 = 0xFF08;
pub const TAB: u32 = 0xFF09;
pub const RETURN: u32 = 0xFF0D;
pub const ESCAPE: u32 = 0xFF1B;
pub const DELETE: u32 = 0xFFFF;
pub const HOME: u32 = 0xFF50;
pub const LEFT: u32 = 0xFF51;
pub const UP: u32 = 0xFF52;
pub const RIGHT: u32 = 0xFF53;
pub const DOWN: u32 = 0xFF54;
pub const PAGE_UP: u32 = 0xFF55;
pub const PAGE_DOWN: u32 = 0xFF56;
pub const END: u32 = 0xFF57;
pub const INSERT: u32 = 0xFF63;
pub const MENU: u32 = 0xFF67;
pub const F1: u32 = 0xFFBE;
pub const SHIFT_L: u32 = 0xFFE1;
pub const SHIFT_R: u32 = 0xFFE2;
pub const CONTROL_L: u32 = 0xFFE3;
pub const CONTROL_R: u32 = 0xFFE4;
pub const CAPS_LOCK: u32 = 0xFFE5;
pub const ALT_L: u32 = 0xFFE9;
pub const ALT_R: u32 = 0xFFEA;
pub const SUPER_L: u32 = 0xFFEB;
pub const PRINT: u32 = 0xFF61;

/// A character: Latin-1 as itself, the rest as a Unicode keysym.
pub fn of(ch: char) -> u32 {
    match ch {
        '\n' | '\r' => RETURN,
        '\t' => TAB,
        '\u{8}' => BACKSPACE,
        c if (0x20..=0x7E).contains(&(c as u32)) || (0xA0..=0xFF).contains(&(c as u32)) => c as u32,
        c => 0x0100_0000 | c as u32,
    }
}
