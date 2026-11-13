//! Keys → the bytes a terminal sends (xterm sequences).

use iced::keyboard::key::Named;
use iced::keyboard::{Key, Modifiers};

/// `None` for keys that send nothing (modifiers alone…).
pub fn encode(key: &Key, mods: Modifiers, text: Option<&str>, app_cursor: bool) -> Option<Vec<u8>> {
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
            if ctrl && alt {
                if let Some(t) = printable {
                    return Some(t.as_bytes().to_vec());
                }
            }
            if ctrl {
                let ch = c.chars().next()?.to_ascii_lowercase();
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
        assert_eq!(encode(&ch("a"), none, Some("a"), false).unwrap(), b"a");
        assert_eq!(encode(&ch("c"), Modifiers::CTRL, None, false).unwrap(), [3]);
        assert_eq!(encode(&ch("b"), Modifiers::ALT, Some("b"), false).unwrap(), b"\x1bb");
        assert_eq!(
            encode(&ch("e"), Modifiers::CTRL | Modifiers::ALT, Some("€"), false).unwrap(),
            "€".as_bytes()
        );
        assert_eq!(encode(&Key::Named(Named::ArrowUp), none, None, false).unwrap(), b"\x1b[A");
        assert_eq!(encode(&Key::Named(Named::ArrowUp), none, None, true).unwrap(), b"\x1bOA");
        assert_eq!(
            encode(&Key::Named(Named::ArrowLeft), Modifiers::CTRL, None, true).unwrap(),
            b"\x1b[1;5D"
        );
        assert_eq!(encode(&Key::Named(Named::Delete), none, None, false).unwrap(), b"\x1b[3~");
        assert_eq!(
            encode(&Key::Named(Named::F5), Modifiers::SHIFT, None, false).unwrap(),
            b"\x1b[15;2~"
        );
        assert_eq!(encode(&Key::Named(Named::Tab), Modifiers::SHIFT, None, false).unwrap(), b"\x1b[Z");
        assert_eq!(encode(&Key::Named(Named::Backspace), none, None, false).unwrap(), b"\x7f");
        assert_eq!(encode(&Key::Named(Named::Shift), Modifiers::SHIFT, None, false), None);
    }
}
