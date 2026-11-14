//! Keys → the bytes a terminal sends (xterm sequences).

use std::sync::atomic::{AtomicBool, Ordering};

use iced::keyboard::key::{Code, Named, Physical};
use iced::keyboard::{Key, Modifiers};

/// « Option = Méta »: Alt (Option) sends ESC before the key. Off on macOS, where Option types characters
/// (« @ », « | », « ~ » on many layouts); on everywhere else.
static OPTION_META: AtomicBool = AtomicBool::new(!cfg!(target_os = "macos"));

pub fn option_meta() -> bool {
    OPTION_META.load(Ordering::Relaxed)
}

pub fn set_option_meta(on: bool) {
    OPTION_META.store(on, Ordering::Relaxed);
}

/// The character of a physical key on a US keyboard: what Ctrl+key means on a Cyrillic, Greek… layout
/// (Ctrl+C is still the key where C is on a US keyboard, as in xterm).
pub fn latin(p: &Physical) -> Option<char> {
    let Physical::Code(c) = p else { return None };
    let letters = [
        Code::KeyA,
        Code::KeyB,
        Code::KeyC,
        Code::KeyD,
        Code::KeyE,
        Code::KeyF,
        Code::KeyG,
        Code::KeyH,
        Code::KeyI,
        Code::KeyJ,
        Code::KeyK,
        Code::KeyL,
        Code::KeyM,
        Code::KeyN,
        Code::KeyO,
        Code::KeyP,
        Code::KeyQ,
        Code::KeyR,
        Code::KeyS,
        Code::KeyT,
        Code::KeyU,
        Code::KeyV,
        Code::KeyW,
        Code::KeyX,
        Code::KeyY,
        Code::KeyZ,
    ];
    if let Some(i) = letters.iter().position(|k| k == c) {
        return Some((b'a' + i as u8) as char);
    }
    Some(match c {
        Code::BracketLeft => '[',
        Code::BracketRight => ']',
        Code::Backslash => '\\',
        Code::Minus => '-',
        Code::Slash => '/',
        Code::Digit2 => '2',
        Code::Digit3 => '3',
        Code::Digit4 => '4',
        Code::Digit5 => '5',
        Code::Digit6 => '6',
        Code::Digit7 => '7',
        Code::Digit8 => '8',
        _ => return None,
    })
}

/// `None` for keys that send nothing (modifiers alone…). [latin] is the US character of the physical key
/// (see [latin]), [meta] whether Alt sends ESC before a character it types ([option_meta]).
pub fn encode(key: &Key, mods: Modifiers, text: Option<&str>, latin: Option<char>, meta: bool, app_cursor: bool) -> Option<Vec<u8>> {
    let (shift, alt, ctrl) = (mods.shift(), mods.alt(), mods.control());
    // xterm's modifier parameter.
    let m = 1 + shift as u8 + 2 * alt as u8 + 4 * ctrl as u8;
    let s = |v: &str| Some(v.as_bytes().to_vec());
    let cursor = |c: char| {
        Some(
            if m > 1 {
                format!("\x1b[1;{m}{c}")
            } else if app_cursor {
                format!("\x1bO{c}")
            } else {
                format!("\x1b[{c}")
            }
            .into_bytes(),
        )
    };
    let tilde = |n: u8| Some(if m > 1 { format!("\x1b[{n};{m}~") } else { format!("\x1b[{n}~") }.into_bytes());
    let ss3 = |c: char| Some(if m > 1 { format!("\x1b[1;{m}{c}") } else { format!("\x1bO{c}") }.into_bytes());
    match key {
        Key::Named(n) => match n {
            Named::Enter => s(if alt { "\x1b\r" } else { "\r" }),
            Named::Backspace => s(if ctrl {
                "\x08"
            } else if alt {
                "\x1b\x7f"
            } else {
                "\x7f"
            }),
            Named::Tab => s(if shift { "\x1b[Z" } else { "\t" }),
            Named::Escape => s("\x1b"),
            Named::Space => s(if ctrl {
                "\0"
            } else if alt {
                "\x1b "
            } else {
                " "
            }),
            Named::ArrowUp => cursor('A'),
            Named::ArrowDown => cursor('B'),
            Named::ArrowRight => cursor('C'),
            Named::ArrowLeft => cursor('D'),
            Named::Home => cursor('H'),
            Named::End => cursor('F'),
            Named::Insert => tilde(2),
            Named::Delete => tilde(3),
            Named::PageUp => tilde(5),
            Named::PageDown => tilde(6),
            Named::F1 => ss3('P'),
            Named::F2 => ss3('Q'),
            Named::F3 => ss3('R'),
            Named::F4 => ss3('S'),
            Named::F5 => tilde(15),
            Named::F6 => tilde(17),
            Named::F7 => tilde(18),
            Named::F8 => tilde(19),
            Named::F9 => tilde(20),
            Named::F10 => tilde(21),
            Named::F11 => tilde(23),
            Named::F12 => tilde(24),
            _ => text.filter(|t| !t.is_empty()).map(|t| t.as_bytes().to_vec()),
        },
        Key::Character(c) => {
            let printable = text.filter(|t| !t.is_empty() && !t.chars().any(char::is_control));
            // AltGr is Ctrl+Alt on Windows: the character it typed wins.
            if ctrl
                && alt
                && let Some(t) = printable
            {
                return Some(t.as_bytes().to_vec());
            }
            if ctrl {
                let ch = c.chars().next()?.to_ascii_lowercase();
                // Another alphabet: the key where the Latin letter is.
                let ch = if ch.is_ascii() { ch } else { latin.unwrap_or(ch) };
                let code = match ch {
                    'a'..='z' => ch as u8 - b'a' + 1,
                    '@' | '2' | ' ' => 0,
                    '[' | '3' => 0x1b,
                    '\\' | '4' => 0x1c,
                    ']' | '5' => 0x1d,
                    '^' | '6' => 0x1e,
                    '_' | '-' | '7' | '/' => 0x1f,
                    '8' | '?' => 0x7f,
                    _ => return printable.map(|t| t.as_bytes().to_vec()),
                };
                return Some(if alt { vec![0x1b, code] } else { vec![code] });
            }
            // Option on macOS: the character it typed, as is.
            if alt
                && !meta
                && let Some(t) = printable
            {
                return Some(t.as_bytes().to_vec());
            }
            let t = printable.map(str::to_owned).unwrap_or_else(|| c.to_string());
            Some(if alt {
                [b"\x1b".as_slice(), t.as_bytes()].concat()
            } else {
                t.into_bytes()
            })
        }
        Key::Unidentified => text.filter(|t| !t.is_empty()).map(|t| t.as_bytes().to_vec()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ch(c: &str) -> Key {
        Key::Character(c.into())
    }

    #[test]
    fn keys_send_the_xterm_sequences() {
        let none = Modifiers::empty();
        assert_eq!(encode(&ch("a"), none, Some("a"), None, true, false).unwrap(), b"a");
        assert_eq!(encode(&ch("c"), Modifiers::CTRL, None, None, true, false).unwrap(), [3]);
        assert_eq!(encode(&ch("b"), Modifiers::ALT, Some("b"), None, true, false).unwrap(), b"\x1bb");
        assert_eq!(
            encode(&ch("e"), Modifiers::CTRL | Modifiers::ALT, Some("€"), None, true, false).unwrap(),
            "€".as_bytes()
        );
        assert_eq!(
            encode(&Key::Named(Named::ArrowUp), none, None, None, true, false).unwrap(),
            b"\x1b[A"
        );
        assert_eq!(
            encode(&Key::Named(Named::ArrowUp), none, None, None, true, true).unwrap(),
            b"\x1bOA"
        );
        assert_eq!(
            encode(&Key::Named(Named::ArrowLeft), Modifiers::CTRL, None, None, true, true).unwrap(),
            b"\x1b[1;5D"
        );
        assert_eq!(
            encode(&Key::Named(Named::Delete), none, None, None, true, false).unwrap(),
            b"\x1b[3~"
        );
        assert_eq!(
            encode(&Key::Named(Named::F5), Modifiers::SHIFT, None, None, true, false).unwrap(),
            b"\x1b[15;2~"
        );
        assert_eq!(
            encode(&Key::Named(Named::Tab), Modifiers::SHIFT, None, None, true, false).unwrap(),
            b"\x1b[Z"
        );
        assert_eq!(
            encode(&Key::Named(Named::Backspace), none, None, None, true, false).unwrap(),
            b"\x7f"
        );
        assert_eq!(encode(&Key::Named(Named::Shift), Modifiers::SHIFT, None, None, true, false), None);
    }

    #[test]
    fn ctrl_letters_follow_the_physical_key_on_other_alphabets() {
        // Ctrl+C on a Russian layout: the key gives « с », the terminal wants ^C.
        let c = latin(&Physical::Code(Code::KeyC));
        assert_eq!(c, Some('c'));
        assert_eq!(encode(&ch("с"), Modifiers::CTRL, Some("с"), c, true, false).unwrap(), [3]);
        assert_eq!(latin(&Physical::Code(Code::BracketLeft)), Some('['));
        assert_eq!(latin(&Physical::Code(Code::F1)), None);
        // A Latin key is left as the layout says (AZERTY: Ctrl+A where Q is on a US keyboard).
        assert_eq!(encode(&ch("a"), Modifiers::CTRL, None, Some('q'), true, false).unwrap(), [1]);
    }

    #[test]
    fn option_types_its_character_unless_it_is_meta() {
        // macOS, French layout: Option+Shift+L is « | ».
        assert_eq!(encode(&ch("|"), Modifiers::ALT, Some("|"), Some('l'), false, false).unwrap(), b"|");
        assert_eq!(
            encode(&ch("b"), Modifiers::ALT, Some("b"), Some('b'), true, false).unwrap(),
            b"\x1bb"
        );
        assert_eq!(option_meta(), !cfg!(target_os = "macos"));
    }
}
