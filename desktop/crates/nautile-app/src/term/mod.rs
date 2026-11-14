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
use alacritty_terminal::term::cell::Flags;
use alacritty_terminal::term::test::TermSize;
use alacritty_terminal::term::{Config, Term, TermMode};
use alacritty_terminal::vte::ansi::{Color as AnsiColor, CursorShape, CursorStyle, NamedColor, Processor, Rgb, StdSyncHandler};

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

    /// What inserting [text] (a command the assistant proposed) types: it is never run before the user
    /// presses Enter. Bracketed when the program asked for it (the shell then keeps the line ends as text);
    /// otherwise on one line, since each line end would be an Enter.
    pub fn insert(&self, text: &str) -> Vec<u8> {
        let clean: String = text
            .replace("\r\n", "\n")
            .chars()
            .filter(|c| !c.is_control() || matches!(c, '\n' | '\t'))
            .collect();
        let clean = clean.trim_end();
        if self.mode().contains(TermMode::BRACKETED_PASTE) {
            self.paste(clean)
        } else {
            one_line(clean).into_bytes()
        }
    }

    /// The cursor's line after the prompt (`mrd@x:~$ docker ps` → `docker ps`), cut at the cursor, read from
    /// the screen as the shell echoes it (so passwords, never echoed, never show); `None` in full-screen
    /// programs (vim, htop) or without a recognisable prompt. Gives what is before the cursor, everything
    /// after it, and the part after it that was typed (up to the suggestion fish, zsh-autosuggestions or
    /// PSReadLine show in grey).
    fn prompt_line(&self) -> Option<(String, String, String)> {
        if self.mode().contains(TermMode::ALT_SCREEN) {
            return None;
        }
        let cursor = self.term.grid().cursor.point;
        let row = &self.term.grid()[cursor.line];
        let cols = self.term.columns();
        let at_cursor = cursor.column.0.min(cols);
        let shown = |c: &usize| !row[Column(*c)].flags.contains(Flags::WIDE_CHAR_SPACER);
        let text = |cells: std::ops::Range<usize>| -> String { cells.filter(shown).map(|c| row[Column(c)].c).collect() };
        let line = text(0..at_cursor);
        let at = ["$ ", "# ", "> ", "% ", "❯ ", "➜ "]
            .iter()
            .filter_map(|p| line.rfind(p).map(|i| i + p.len()))
            .max()?;
        // The colours of what was typed before the cursor.
        let cells: Vec<usize> = (0..at_cursor).filter(shown).collect();
        let typed: Vec<_> = cells[line[..at].chars().count().min(cells.len())..]
            .iter()
            .map(|c| &row[Column(*c)])
            .filter(|cell| cell.c != ' ')
            .map(|cell| cell.fg)
            .collect();
        let suggested = |c: usize| {
            let cell = &row[Column(c)];
            cell.flags.contains(Flags::DIM) || (!typed.contains(&cell.fg) && grey(cell.fg))
        };
        let typed_end = (at_cursor..cols).find(|c| suggested(*c)).unwrap_or(cols);
        // What follows the cursor, up to a wide gap (zsh's right prompt).
        let cut = |s: String| s.split("   ").next().unwrap_or_default().trim_end().to_owned();
        Some((line[at..].to_owned(), cut(text(at_cursor..cols)), cut(text(at_cursor..typed_end))))
    }

    /// What is typed before the cursor, when nothing is shown after it (else → moves the cursor or takes the
    /// shell's own suggestion, it does not complete).
    pub fn typed_command(&self) -> Option<String> {
        let (before, after, _) = self.prompt_line()?;
        after.is_empty().then_some(before)
    }

    /// The whole command on the cursor's line, wherever the cursor is in it (for the history), without the
    /// shell's grey suggestion.
    pub fn command_line(&self) -> Option<String> {
        let (before, _, after) = self.prompt_line()?;
        Some(format!("{before}{after}").trim().to_owned())
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

/// Lines of a shell command joined into one: `\\` continuations and lines ending in `|`, `&&`, `do`, `then`…
/// go on, the others are separated by `;`.
fn one_line(text: &str) -> String {
    let mut out = String::new();
    for line in text.lines().map(str::trim).filter(|l| !l.is_empty()) {
        if out.ends_with('\\') {
            out.pop();
            out.truncate(out.trim_end().len());
            out.push(' ');
        } else if !out.is_empty() {
            let last = out.rsplit(' ').next().unwrap_or_default();
            let goes_on = out.ends_with(['|', '&', ';', '{', '(']) || matches!(last, "then" | "do" | "else" | "in");
            out.push_str(if goes_on { " " } else { "; " });
        }
        out.push_str(line);
    }
    out.replace('\t', " ")
}

/// The greys shells show their suggestions in (fish's `brblack`, zsh-autosuggestions' `fg=8`, PSReadLine's
/// 256-colour greys).
fn grey(c: AnsiColor) -> bool {
    match c {
        AnsiColor::Named(n) => matches!(n, NamedColor::BrightBlack | NamedColor::DimForeground | NamedColor::DimWhite),
        AnsiColor::Indexed(i) => i == 8 || (232..=248).contains(&i),
        AnsiColor::Spec(Rgb { r, g, b }) => {
            let (hi, lo) = (r.max(g).max(b), r.min(g).min(b));
            hi - lo <= 16 && (0x40..=0xa0).contains(&hi)
        }
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
    fn inserted_commands_never_run() {
        let t = Terminal::new(40, 10, themes::by_id("nautile"), CursorShape::Block);
        let one = |s: &str| String::from_utf8(t.insert(s)).unwrap();
        assert_eq!(one("ls -la\n"), "ls -la");
        assert_eq!(one("apt update\r\napt upgrade -y\n"), "apt update; apt upgrade -y");
        assert_eq!(one("docker run \\\n  -it alpine"), "docker run -it alpine");
        assert_eq!(one("for f in *; do\n  echo $f\ndone"), "for f in *; do echo $f; done");
        assert_eq!(one("ps aux |\n grep ssh &&\n echo ok"), "ps aux | grep ssh && echo ok");
        assert_eq!(one("echo a\x1b[201~\rrm -rf x\x07\tb"), "echo a[201~rm -rf x b");
        for s in ["a\nb", "x\r\ny\n", "a\rb"] {
            assert!(!t.insert(s).iter().any(|b| matches!(b, b'\r' | b'\n')), "{s:?}");
        }
        let mut t = t;
        t.feed(b"\x1b[?2004h");
        assert_eq!(t.insert("a\nb\n"), b"\x1b[200~a\nb\x1b[201~");
    }

    #[test]
    fn suggestions_only_at_the_end_of_the_line() {
        let mut t = Terminal::new(40, 10, themes::by_id("nautile"), CursorShape::Block);
        t.feed(b"mrd@x:~$ git commit -m x");
        assert_eq!(t.typed_command().as_deref(), Some("git commit -m x"));
        // The cursor brought back after `gi`.
        t.feed(b"\x1b[13D");
        assert_eq!(t.typed_command(), None);
        assert_eq!(t.command_line().as_deref(), Some("git commit -m x"));
        // zsh's right prompt is not part of the command.
        let mut t = Terminal::new(60, 10, themes::by_id("nautile"), CursorShape::Block);
        t.feed(b"% ls\x1b[s\x1b[50G~/src\x1b[u");
        assert_eq!(t.typed_command().as_deref(), Some("ls"));
        assert_eq!(t.command_line().as_deref(), Some("ls"));
    }

    #[test]
    fn the_shells_grey_suggestion_is_not_part_of_the_command() {
        // fish (brblack), zsh-autosuggestions (fg=8), PSReadLine (dim, or a 256-colour grey).
        for grey in ["\x1b[90m", "\x1b[38;5;8m", "\x1b[2m", "\x1b[38;5;238m"] {
            let mut t = Terminal::new(60, 10, themes::by_id("nautile"), CursorShape::Block);
            t.feed(format!("mrd@x:~$ rm{grey} -rf build/\x1b[0m\x1b[11D").as_bytes());
            assert_eq!(t.command_line().as_deref(), Some("rm"), "{grey:?}");
            assert_eq!(t.typed_command(), None, "→ takes the shell's own suggestion");
        }
        // Coloured by fish's highlighting, the cursor brought back: all of it was typed.
        let mut t = Terminal::new(60, 10, themes::by_id("nautile"), CursorShape::Block);
        t.feed(b"mrd@x:~$ \x1b[34mrm\x1b[36m -rf build/\x1b[0m\x1b[11D");
        assert_eq!(t.command_line().as_deref(), Some("rm -rf build/"));
        // Typed in grey and in another colour after the cursor.
        let mut t = Terminal::new(60, 10, themes::by_id("nautile"), CursorShape::Block);
        t.feed(b"mrd@x:~$ \x1b[90mls\x1b[0m -la\x1b[4D");
        assert_eq!(t.command_line().as_deref(), Some("ls -la"));
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
