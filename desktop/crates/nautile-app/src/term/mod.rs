//! The terminal of a session: alacritty's emulator fed with the connection's bytes, drawn by [widget].

pub mod fonts;
pub mod import;
pub mod keys;
pub mod themes;
pub mod widget;

use std::sync::{Arc, Mutex};

use alacritty_terminal::event::{Event as TermEvent, EventListener, WindowSize};
use alacritty_terminal::grid::{Dimensions, Scroll};
use alacritty_terminal::index::{Column, Line, Point, Side};
use alacritty_terminal::selection::{Selection, SelectionType};
use alacritty_terminal::term::test::TermSize;
use alacritty_terminal::term::{Config, Term, TermMode};
use alacritty_terminal::vte::ansi::{CursorShape, CursorStyle, Processor, Rgb, StdSyncHandler};

/// What the emulator asks of the app.
#[derive(Clone, Default)]
pub struct Listener(Arc<Mutex<Vec<TermEvent>>>);

impl EventListener for Listener {
    fn send_event(&self, event: TermEvent) {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).push(event);
    }
}

/// What the app must do after feeding bytes.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Effects {
    /// Answers to send back to the server (cursor position, device attributes…).
    pub reply: Vec<u8>,
    pub title: Option<String>,
    /// OSC 52: text a program put in the clipboard.
    pub clipboard: Option<String>,
    pub bell: bool,
}

pub struct Terminal {
    pub term: Term<Listener>,
    parser: Processor<StdSyncHandler>,
    listener: Listener,
    /// The colours used to answer colour queries (OSC 10/11/4).
    pub palette: themes::TermTheme,
}

/// Lines kept above the screen (as on Android).
pub const SCROLLBACK: usize = 5000;

fn config(cursor: CursorShape) -> Config {
    Config {
        scrolling_history: SCROLLBACK,
        default_cursor_style: CursorStyle {
            shape: cursor,
            blinking: false,
        },
        ..Config::default()
    }
}

/// The cursor setting (`block`, `bar`, `underline`, as Android's Bloc / Barre / Souligné).
pub fn cursor_shape(id: &str) -> CursorShape {
    match id {
        "bar" => CursorShape::Beam,
        "underline" => CursorShape::Underline,
        _ => CursorShape::Block,
    }
}

impl Terminal {
    pub fn new(cols: u16, rows: u16, palette: themes::TermTheme, cursor: CursorShape) -> Terminal {
        let listener = Listener::default();
        let size = TermSize::new(cols.max(2) as usize, rows.max(1) as usize);
        Terminal {
            term: Term::new(config(cursor), &size, listener.clone()),
            parser: Processor::new(),
            listener,
            palette,
        }
    }

    /// The cursor's shape when programs do not ask for another one.
    pub fn set_cursor(&mut self, cursor: CursorShape) {
        self.term.set_options(config(cursor));
    }

    pub fn cols(&self) -> u16 {
        self.term.columns() as u16
    }

    pub fn rows(&self) -> u16 {
        self.term.screen_lines() as u16
    }

    pub fn mode(&self) -> TermMode {
        *self.term.mode()
    }

    /// Feeds bytes from the connection.
    pub fn feed(&mut self, bytes: &[u8]) -> Effects {
        self.parser.advance(&mut self.term, bytes);
        self.effects()
    }

    fn effects(&mut self) -> Effects {
        let events = std::mem::take(&mut *self.listener.0.lock().unwrap_or_else(|e| e.into_inner()));
        let mut fx = Effects::default();
        for e in events {
            match e {
                TermEvent::PtyWrite(s) => fx.reply.extend_from_slice(s.as_bytes()),
                TermEvent::Title(t) => fx.title = Some(t),
                TermEvent::ResetTitle => fx.title = Some(String::new()),
                TermEvent::ClipboardStore(_, text) => fx.clipboard = Some(text),
                TermEvent::Bell => fx.bell = true,
                TermEvent::ColorRequest(index, format) => {
                    let c = match index {
                        256 => self.palette.foreground,
                        257 => self.palette.background,
                        258 => self.palette.cursor,
                        i => self.term.colors()[i]
                            .map(rgb_color)
                            .unwrap_or_else(|| widget::indexed(&self.palette, i as u8)),
                    };
                    let [r, g, b, _] = c.into_rgba8();
                    fx.reply.extend_from_slice(format(Rgb { r, g, b }).as_bytes());
                }
                TermEvent::TextAreaSizeRequest(format) => {
                    let size = WindowSize {
                        num_lines: self.rows(),
                        num_cols: self.cols(),
                        cell_width: 8,
                        cell_height: 16,
                    };
                    fx.reply.extend_from_slice(format(size).as_bytes());
                }
                _ => {}
            }
        }
        fx
    }

    pub fn resize(&mut self, cols: u16, rows: u16) {
        self.term.resize(TermSize::new(cols.max(2) as usize, rows.max(1) as usize));
    }

    /// Positive: towards the history.
    pub fn scroll(&mut self, lines: i32) {
        self.term.scroll_display(Scroll::Delta(lines));
    }

    pub fn scroll_to_bottom(&mut self) {
        self.term.scroll_display(Scroll::Bottom);
    }

    /// A point of the screen (`row` from the top of what is shown).
    pub fn point(&self, row: usize, col: usize) -> Point {
        let offset = self.term.grid().display_offset() as i32;
        Point::new(
            Line(row.min(self.rows() as usize - 1) as i32 - offset),
            Column(col.min(self.cols() as usize - 1)),
        )
    }

    pub fn select_start(&mut self, kind: SelectionType, row: usize, col: usize, side: Side) {
        let p = self.point(row, col);
        self.term.selection = Some(Selection::new(kind, p, side));
    }

    pub fn select_update(&mut self, row: usize, col: usize, side: Side) {
        let p = self.point(row, col);
        if let Some(s) = self.term.selection.as_mut() {
            s.update(p, side);
        }
    }

    pub fn clear_selection(&mut self) {
        self.term.selection = None;
    }

    pub fn selection_text(&self) -> Option<String> {
        self.term.selection_to_string().filter(|s| !s.is_empty())
    }

    /// What pasting [text] sends: bracketed when the program asked for it.
    pub fn paste(&self, text: &str) -> Vec<u8> {
        if self.mode().contains(TermMode::BRACKETED_PASTE) {
            format!("\x1b[200~{}\x1b[201~", text.replace('\x1b', "")).into_bytes()
        } else {
            text.replace("\r\n", "\r").replace('\n', "\r").into_bytes()
        }
    }

    /// What is typed after the prompt on the cursor's line (`mrd@x:~$ docker ps` → `docker ps`), read from
    /// the screen as the shell echoes it (so passwords, never echoed, never show); `None` in full-screen
    /// programs (vim, htop) or without a recognisable prompt.
    pub fn typed_command(&self) -> Option<String> {
        if self.mode().contains(TermMode::ALT_SCREEN) {
            return None;
        }
        let cursor = self.term.grid().cursor.point;
        let row = &self.term.grid()[cursor.line];
        let line: String = (0..cursor.column.0.min(self.term.columns())).map(|c| row[Column(c)].c).collect();
        let at = ["$ ", "# ", "> ", "% ", "❯ ", "➜ "]
            .iter()
            .filter_map(|p| line.rfind(p).map(|i| i + p.len()))
            .max()?;
        Some(line[at..].trim_end().to_owned())
    }

    /// The whole text shown (for tests and the « copy all » action).
    pub fn screen_text(&self) -> String {
        let mut out = String::new();
        let mut line = None;
        for cell in self.term.grid().display_iter() {
            if line.is_some_and(|l| l != cell.point.line) {
                out.truncate(out.trim_end_matches(' ').len());
                out.push('\n');
            }
            line = Some(cell.point.line);
            out.push(cell.c);
        }
        out.truncate(out.trim_end().len());
        out
    }
}

fn rgb_color(c: Rgb) -> iced::Color {
    iced::Color::from_rgb8(c.r, c.g, c.b)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bytes_become_text_and_answers() {
        let mut t = Terminal::new(40, 10, themes::by_id("nautile"), CursorShape::Block);
        let fx = t.feed(b"\x1b]0;mrd@docker-01\x07hello \x1b[1;32mworld\x1b[0m\r\n\x1b[6n");
        assert_eq!(fx.title.as_deref(), Some("mrd@docker-01"));
        assert_eq!(fx.reply, b"\x1b[2;1R", "cursor position report");
        assert!(t.screen_text().starts_with("hello world"));
        t.resize(20, 5);
        assert_eq!((t.cols(), t.rows()), (20, 5));
        assert_eq!(t.paste("a\nb"), b"a\rb");
        t.feed(b"\x1b[?2004h");
        assert_eq!(t.paste("x"), b"\x1b[200~x\x1b[201~");
    }

    #[test]
    fn selection_gives_the_text() {
        let mut t = Terminal::new(40, 10, themes::by_id("nautile"), CursorShape::Block);
        t.feed(b"first line\r\nsecond line");
        t.select_start(SelectionType::Simple, 1, 0, Side::Left);
        t.select_update(1, 5, Side::Right);
        assert_eq!(t.selection_text().as_deref(), Some("second"));
        t.select_start(SelectionType::Semantic, 0, 1, Side::Left);
        t.select_update(0, 1, Side::Right);
        assert_eq!(t.selection_text().as_deref(), Some("first"));
    }
}
