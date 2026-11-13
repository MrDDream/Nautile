//! Draws a [Terminal] and turns the keyboard and the mouse into [Action]s.
//!
//! The text is drawn in runs of cells that share a style, each placed at its cell: the grid never drifts even
//! when a glyph comes from a fallback font.

use std::time::{Duration, Instant};

use alacritty_terminal::index::Side;
use alacritty_terminal::selection::SelectionType;
use alacritty_terminal::term::cell::Flags;
use alacritty_terminal::term::TermMode;
use alacritty_terminal::vte::ansi::{Color as AnsiColor, CursorShape, NamedColor};
use iced::advanced::clipboard::Kind;
use iced::advanced::layout::{self, Layout};
use iced::advanced::renderer::{self, Quad};
use iced::advanced::text::{self, Paragraph, Text};
use iced::advanced::widget::{tree, Tree, Widget};
use iced::advanced::{Clipboard, Shell};
use iced::alignment::Vertical;
use iced::keyboard::{self, key::Named, Key};
use iced::mouse::{self, ScrollDelta};
use iced::widget::text::{LineHeight, Shaping, Wrapping};
use iced::{Border, Color, Element, Event, Font, Length, Pixels, Point, Rectangle, Size};

use super::themes::TermTheme;
use super::Terminal;

/// Inner margin around the grid.
pub const PADDING: f32 = 8.0;

/// What the user did on the terminal.
#[derive(Debug, Clone, PartialEq)]
pub enum Action {
    /// Bytes for the connection (keys, paste, mouse reports).
    Input(Vec<u8>),
    /// The visible grid changed size.
    Resize(u16, u16),
    /// Positive: towards the history.
    Scroll(i32),
    SelectStart(SelectionType, usize, usize, Side),
    SelectUpdate(usize, usize, Side),
    ClearSelection,
}

pub struct TermView<'a, Message> {
    term: &'a Terminal,
    font: Font,
    size: f32,
    focused: bool,
    on_action: Box<dyn Fn(Action) -> Message + 'a>,
}

pub fn view<'a, Message>(
    term: &'a Terminal,
    font: Font,
    size: f32,
    focused: bool,
    on_action: impl Fn(Action) -> Message + 'a,
) -> TermView<'a, Message> {
    TermView {
        term,
        font,
        size,
        focused,
        on_action: Box::new(on_action),
    }
}

#[derive(Debug, Default)]
struct State {
    /// Measured size of a cell for the current font size.
    cell: Size,
    measured_for: Option<(f32, Font)>,
    /// The last size asked with [Action::Resize].
    reported: (u16, u16),
    selecting: bool,
    last_cell: (usize, usize),
    clicks: u32,
    last_click: Option<Instant>,
    /// Pixel scrolling (touchpads) not yet worth a line.
    scroll_rest: f32,
    modifiers: keyboard::Modifiers,
}

/// The 256 colours: the theme's 16, the 6×6×6 cube, then the greys.
pub fn indexed(theme: &TermTheme, i: u8) -> Color {
    match i {
        0..=15 => theme.ansi[i as usize],
        16..=231 => {
            let i = i - 16;
            let level = |v: u8| if v == 0 { 0 } else { 55 + v * 40 };
            Color::from_rgb8(level(i / 36), level((i / 6) % 6), level(i % 6))
        }
        _ => {
            let v = 8 + (i - 232) * 10;
            Color::from_rgb8(v, v, v)
        }
    }
}

fn dim(c: Color) -> Color {
    Color::from_rgb(c.r * 0.66, c.g * 0.66, c.b * 0.66)
}

impl<'a, Message> TermView<'a, Message> {
    fn color(&self, c: AnsiColor) -> Color {
        let t = &self.term.palette;
        let colors = self.term.term.colors();
        match c {
            AnsiColor::Spec(rgb) => Color::from_rgb8(rgb.r, rgb.g, rgb.b),
            AnsiColor::Indexed(i) => colors[i as usize]
                .map(|c| Color::from_rgb8(c.r, c.g, c.b))
                .unwrap_or_else(|| indexed(t, i)),
            AnsiColor::Named(n) => {
                if let Some(c) = colors[n] {
                    return Color::from_rgb8(c.r, c.g, c.b);
                }
                let i = n as usize;
                match n {
                    NamedColor::Foreground | NamedColor::BrightForeground => t.foreground,
                    NamedColor::Background => t.background,
                    NamedColor::Cursor => t.cursor,
                    NamedColor::DimForeground => dim(t.foreground),
                    _ if i < 16 => t.ansi[i],
                    // Dim black … dim white.
                    _ => dim(t.ansi[(i - NamedColor::DimBlack as usize) % 8]),
                }
            }
        }
    }

    /// (row, column, side) under a position relative to the widget.
    fn cell_at(&self, state: &State, p: Point) -> (usize, usize, Side) {
        let x = (p.x - PADDING).max(0.0);
        let y = (p.y - PADDING).max(0.0);
        let col = (x / state.cell.width) as usize;
        let row = (y / state.cell.height) as usize;
        let side = if x % state.cell.width > state.cell.width / 2.0 {
            Side::Right
        } else {
            Side::Left
        };
        (
            row.min(self.term.rows().saturating_sub(1) as usize),
            col.min(self.term.cols().saturating_sub(1) as usize),
            side,
        )
    }

    /// The grid that fits in [bounds].
    fn grid_size(state: &State, bounds: Size) -> (u16, u16) {
        let cols = ((bounds.width - 2.0 * PADDING) / state.cell.width).floor().max(2.0) as u16;
        let rows = ((bounds.height - 2.0 * PADDING) / state.cell.height).floor().max(1.0) as u16;
        (cols, rows)
    }

    /// A mouse report for the program (`MOUSE_MODE`), or `None` when it did not ask for one.
    fn mouse_report(&self, button: u8, row: usize, col: usize, pressed: bool, mods: keyboard::Modifiers) -> Option<Vec<u8>> {
        let mode = self.term.mode();
        if !mode.intersects(TermMode::MOUSE_MODE) {
            return None;
        }
        let mut b = button;
        if mods.shift() {
            b += 4;
        }
        if mods.alt() {
            b += 8;
        }
        if mods.control() {
            b += 16;
        }
        if mode.contains(TermMode::SGR_MOUSE) {
            return Some(format!("\x1b[<{b};{};{}{}", col + 1, row + 1, if pressed { 'M' } else { 'm' }).into_bytes());
        }
        let code = if pressed { b } else { 3 + (b & !3) };
        if col >= 223 || row >= 223 {
            return None;
        }
        Some(vec![0x1b, b'[', b'M', 32 + code, 33 + col as u8, 33 + row as u8])
    }
}

impl<'a, Message, Theme, Renderer> Widget<Message, Theme, Renderer> for TermView<'a, Message>
where
    Renderer: text::Renderer<Font = Font>,
{
    fn tag(&self) -> tree::Tag {
        tree::Tag::of::<State>()
    }

    fn state(&self) -> tree::State {
        tree::State::new(State::default())
    }

    fn size(&self) -> Size<Length> {
        Size::new(Length::Fill, Length::Fill)
    }

    fn layout(&mut self, tree: &mut Tree, _renderer: &Renderer, limits: &layout::Limits) -> layout::Node {
        let state = tree.state.downcast_mut::<State>();
        if state.measured_for != Some((self.size, self.font)) {
            // The advance the renderer really uses (hinting may round it).
            let probe = "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM";
            let p = Renderer::Paragraph::with_text(Text {
                content: probe,
                bounds: Size::INFINITE,
                size: Pixels(self.size),
                line_height: LineHeight::Relative(1.0),
                font: self.font,
                align_x: text::Alignment::Left,
                align_y: Vertical::Top,
                shaping: Shaping::Basic,
                wrapping: Wrapping::None,
            });
            let w = p.min_bounds().width / probe.len() as f32;
            state.cell = Size::new(if w > 0.0 { w } else { self.size * 0.6 }, (self.size * 1.3).round());
            state.measured_for = Some((self.size, self.font));
        }
        layout::Node::new(limits.max())
    }

    fn update(
        &mut self,
        tree: &mut Tree,
        event: &Event,
        layout: Layout<'_>,
        cursor: mouse::Cursor,
        _renderer: &Renderer,
        clipboard: &mut dyn Clipboard,
        shell: &mut Shell<'_, Message>,
        _viewport: &Rectangle,
    ) {
        let state = tree.state.downcast_mut::<State>();
        let bounds = layout.bounds();
        if state.cell.width <= 0.0 {
            return;
        }
        // The grid follows the widget's size.
        let wanted = Self::grid_size(state, bounds.size());
        if wanted != state.reported && wanted != (self.term.cols(), self.term.rows()) {
            state.reported = wanted;
            shell.publish((self.on_action)(Action::Resize(wanted.0, wanted.1)));
        } else if wanted == (self.term.cols(), self.term.rows()) {
            state.reported = wanted;
        }

        let publish = |shell: &mut Shell<'_, Message>, a: Action| shell.publish((self.on_action)(a));
        match event {
            Event::Keyboard(keyboard::Event::ModifiersChanged(m)) => state.modifiers = *m,
            Event::Keyboard(keyboard::Event::KeyPressed { key, modifiers, text, .. }) if self.focused => {
                let (ctrl, shift) = (modifiers.control(), modifiers.shift());
                // Left to the window: tabs and the app's own shortcuts.
                let digit =
                    matches!(key.as_ref(), Key::Character(c) if c.len() == 1 && ('1'..='9').contains(&c.chars().next().unwrap_or(' ')));
                if (ctrl
                    && (matches!(key, Key::Named(Named::Tab))
                        || (shift
                            && matches!(
                                key.as_ref(),
                                Key::Character("t" | "T" | "w" | "W" | "a" | "A" | "p" | "P" | "k" | "K")
                            ))
                        || (!shift && digit)))
                    || matches!(key, Key::Named(Named::F11))
                {
                    return;
                }
                if ctrl && shift && matches!(key.as_ref(), Key::Character("c" | "C")) {
                    if let Some(t) = self.term.selection_text() {
                        clipboard.write(Kind::Standard, t);
                    }
                    shell.capture_event();
                    return;
                }
                let paste = (ctrl && shift && matches!(key.as_ref(), Key::Character("v" | "V")))
                    || (shift && matches!(key, Key::Named(Named::Insert)));
                if paste {
                    if let Some(t) = clipboard.read(Kind::Standard) {
                        publish(shell, Action::Input(self.term.paste(&t)));
                    }
                    shell.capture_event();
                    return;
                }
                if shift && !self.term.mode().contains(TermMode::ALT_SCREEN) {
                    let page = self.term.rows() as i32 - 1;
                    match key {
                        Key::Named(Named::PageUp) => return publish(shell, Action::Scroll(page)),
                        Key::Named(Named::PageDown) => return publish(shell, Action::Scroll(-page)),
                        _ => {}
                    }
                }
                let app_cursor = self.term.mode().contains(TermMode::APP_CURSOR);
                if let Some(bytes) = super::keys::encode(key, *modifiers, text.as_deref(), app_cursor) {
                    publish(shell, Action::Input(bytes));
                    shell.capture_event();
                }
            }
            Event::Mouse(mouse::Event::ButtonPressed(button)) => {
                let Some(p) = cursor.position_in(bounds) else { return };
                let (row, col, side) = self.cell_at(state, p);
                let report_code = match button {
                    mouse::Button::Left => Some(0),
                    mouse::Button::Middle => Some(1),
                    mouse::Button::Right => Some(2),
                    _ => None,
                };
                if !state.modifiers.shift() {
                    if let Some(r) = report_code.and_then(|b| self.mouse_report(b, row, col, true, state.modifiers)) {
                        publish(shell, Action::Input(r));
                        shell.capture_event();
                        return;
                    }
                }
                match button {
                    mouse::Button::Left => {
                        let now = Instant::now();
                        let again = state.last_click.is_some_and(|t| now - t < Duration::from_millis(400)) && state.last_cell == (row, col);
                        state.clicks = if again { state.clicks % 3 + 1 } else { 1 };
                        state.last_click = Some(now);
                        state.last_cell = (row, col);
                        let kind = match state.clicks {
                            2 => SelectionType::Semantic,
                            3 => SelectionType::Lines,
                            _ => SelectionType::Simple,
                        };
                        state.selecting = true;
                        publish(shell, Action::SelectStart(kind, row, col, side));
                    }
                    // Like Windows Terminal: copies the selection, else pastes.
                    mouse::Button::Right => match self.term.selection_text() {
                        Some(t) => {
                            clipboard.write(Kind::Standard, t);
                            publish(shell, Action::ClearSelection);
                        }
                        None => {
                            if let Some(t) = clipboard.read(Kind::Standard) {
                                publish(shell, Action::Input(self.term.paste(&t)));
                            }
                        }
                    },
                    mouse::Button::Middle => {
                        if let Some(t) = clipboard.read(Kind::Primary).or_else(|| clipboard.read(Kind::Standard)) {
                            publish(shell, Action::Input(self.term.paste(&t)));
                        }
                    }
                    _ => {}
                }
                shell.capture_event();
            }
            Event::Mouse(mouse::Event::CursorMoved { .. }) if state.selecting => {
                let Some(p) = cursor.land().position() else { return };
                let p = Point::new(p.x - bounds.x, p.y - bounds.y);
                let (row, col, side) = self.cell_at(state, p);
                if (row, col) != state.last_cell || state.clicks == 1 {
                    state.last_cell = (row, col);
                    publish(shell, Action::SelectUpdate(row, col, side));
                }
            }
            Event::Mouse(mouse::Event::ButtonReleased(button)) => {
                if *button == mouse::Button::Left && state.selecting {
                    state.selecting = false;
                    if let Some(t) = self.term.selection_text() {
                        clipboard.write(Kind::Primary, t);
                    }
                } else if let Some(p) = cursor.position_in(bounds) {
                    let (row, col, _) = self.cell_at(state, p);
                    let b = match button {
                        mouse::Button::Left => 0,
                        mouse::Button::Middle => 1,
                        _ => 2,
                    };
                    if let Some(r) = self.mouse_report(b, row, col, false, state.modifiers) {
                        publish(shell, Action::Input(r));
                    }
                }
            }
            Event::Mouse(mouse::Event::WheelScrolled { delta }) => {
                let Some(p) = cursor.position_in(bounds) else { return };
                let lines = match delta {
                    ScrollDelta::Lines { y, .. } => {
                        state.scroll_rest = 0.0;
                        (y * 3.0).round() as i32
                    }
                    ScrollDelta::Pixels { y, .. } => {
                        state.scroll_rest += y / state.cell.height;
                        let l = state.scroll_rest.trunc();
                        state.scroll_rest -= l;
                        l as i32
                    }
                };
                if lines == 0 {
                    return;
                }
                let (row, col, _) = self.cell_at(state, p);
                let mode = self.term.mode();
                if mode.intersects(TermMode::MOUSE_MODE) {
                    let b = if lines > 0 { 64 } else { 65 };
                    let mut out = Vec::new();
                    for _ in 0..lines.unsigned_abs().min(10) {
                        out.extend(self.mouse_report(b, row, col, true, state.modifiers).unwrap_or_default());
                    }
                    publish(shell, Action::Input(out));
                } else if mode.contains(TermMode::ALT_SCREEN | TermMode::ALTERNATE_SCROLL) {
                    // less, man, vim without mouse: the wheel moves by lines.
                    let key: &[u8] = if lines > 0 { b"\x1bOA" } else { b"\x1bOB" };
                    publish(shell, Action::Input(key.repeat(lines.unsigned_abs() as usize)));
                } else {
                    publish(shell, Action::Scroll(lines));
                }
                shell.capture_event();
            }
            _ => {}
        }
    }

    fn mouse_interaction(
        &self,
        _tree: &Tree,
        layout: Layout<'_>,
        cursor: mouse::Cursor,
        _viewport: &Rectangle,
        _renderer: &Renderer,
    ) -> mouse::Interaction {
        if cursor.is_over(layout.bounds()) {
            mouse::Interaction::Text
        } else {
            mouse::Interaction::None
        }
    }

    fn draw(
        &self,
        tree: &Tree,
        renderer: &mut Renderer,
        _theme: &Theme,
        _style: &renderer::Style,
        layout: Layout<'_>,
        _cursor: mouse::Cursor,
        viewport: &Rectangle,
    ) {
        let state = tree.state.downcast_ref::<State>();
        let bounds = layout.bounds();
        let palette = &self.term.palette;
        let quad = |renderer: &mut Renderer, r: Rectangle, c: Color| {
            renderer.fill_quad(
                Quad {
                    bounds: r,
                    ..Quad::default()
                },
                c,
            )
        };
        quad(renderer, bounds, palette.background);
        if state.cell.width <= 0.0 {
            return;
        }
        let (cw, ch) = (state.cell.width, state.cell.height);
        let origin = Point::new(bounds.x + PADDING, bounds.y + PADDING);
        let clip = bounds.intersection(viewport).unwrap_or(bounds);
        // Everything is clipped to the widget (long lines, wide glyphs).
        renderer.start_layer(clip);
        let columns = self.term.cols() as usize;
        let content = self.term.term.renderable_content();
        let offset = content.display_offset as i32;
        let selection = content.selection;
        let selection_bg = Color {
            a: 1.0,
            ..mix(palette.background, palette.cursor, 0.35)
        };
        let show_cursor = content.mode.contains(TermMode::SHOW_CURSOR) && content.cursor.shape != CursorShape::Hidden;
        let cursor_point = content.cursor.point;

        // Runs of text sharing a colour, and background spans.
        struct Run {
            row: usize,
            col: usize,
            text: String,
            color: Color,
            cells: usize,
            simple: bool,
        }
        let mut runs: Vec<Run> = Vec::new();
        let mut spans: Vec<(usize, usize, usize, Color)> = Vec::new();
        let mut decorations: Vec<(Rectangle, Color)> = Vec::new();
        let mut under_cursor: Option<(String, Color, usize)> = None;

        for cell in content.display_iter {
            let flags = cell.flags;
            if flags.intersects(Flags::WIDE_CHAR_SPACER | Flags::LEADING_WIDE_CHAR_SPACER) {
                continue;
            }
            let row = (cell.point.line.0 + offset) as usize;
            let col = cell.point.column.0;
            // After a shrink, alacritty keeps the cut-off cells for a later widening: not shown.
            if col >= columns {
                continue;
            }
            let width = if flags.contains(Flags::WIDE_CHAR) { 2 } else { 1 };
            let (mut fg, mut bg) = (self.color(cell.fg), self.color(cell.bg));
            if flags.contains(Flags::BOLD) {
                // Bold in the normal colours shows the bright ones, as most terminals do.
                if let AnsiColor::Named(n) = cell.fg {
                    if (n as usize) < 8 && self.term.term.colors()[n].is_none() {
                        fg = palette.ansi[n as usize + 8];
                    }
                }
            }
            if flags.contains(Flags::DIM) {
                fg = dim(fg);
            }
            if flags.contains(Flags::INVERSE) {
                std::mem::swap(&mut fg, &mut bg);
            }
            if flags.contains(Flags::HIDDEN) {
                fg = bg;
            }
            if selection.is_some_and(|s| s.contains(cell.point)) {
                bg = selection_bg;
            }
            if bg != palette.background {
                match spans.last_mut() {
                    Some((r, c, w, color)) if *r == row && *c + *w == col && *color == bg => *w += width,
                    _ => spans.push((row, col, width, bg)),
                }
            }
            let x = origin.x + col as f32 * cw;
            let y = origin.y + row as f32 * ch;
            if flags.intersects(Flags::ALL_UNDERLINES) {
                decorations.push((Rectangle::new(Point::new(x, y + ch - 2.0), Size::new(cw * width as f32, 1.0)), fg));
            }
            if flags.contains(Flags::STRIKEOUT) {
                decorations.push((Rectangle::new(Point::new(x, y + ch / 2.0), Size::new(cw * width as f32, 1.0)), fg));
            }
            let mut c = String::from(cell.c);
            if let Some(extra) = cell.zerowidth() {
                c.extend(extra);
            }
            if show_cursor && cell.point == cursor_point {
                under_cursor = Some((c.clone(), bg, width));
            }
            if cell.c == ' ' || cell.c == '\t' || flags.contains(Flags::HIDDEN) {
                continue;
            }
            let simple = width == 1 && cell.c.is_ascii() && c.len() == 1;
            match runs.last_mut() {
                Some(r) if simple && r.simple && r.row == row && r.col + r.cells == col && r.color == fg => {
                    r.text.push(cell.c);
                    r.cells += 1;
                }
                // Spaces inside a run are kept so that it stays one piece.
                Some(r) if simple && r.simple && r.row == row && r.col + r.cells < col && r.color == fg && col - (r.col + r.cells) <= 4 => {
                    for _ in r.col + r.cells..col {
                        r.text.push(' ');
                    }
                    r.text.push(cell.c);
                    r.cells = col - r.col + 1;
                }
                _ => runs.push(Run {
                    row,
                    col,
                    text: c,
                    color: fg,
                    cells: width,
                    simple,
                }),
            }
        }

        for (row, col, w, color) in spans {
            quad(
                renderer,
                Rectangle::new(
                    Point::new(origin.x + col as f32 * cw, origin.y + row as f32 * ch),
                    Size::new(w as f32 * cw, ch),
                ),
                color,
            );
        }

        let text_at = |renderer: &mut Renderer, s: String, x: f32, y: f32, cells: usize, color: Color, simple: bool| {
            renderer.fill_text(
                Text {
                    content: s,
                    bounds: Size::new(cells as f32 * cw + cw, ch),
                    size: Pixels(self.size),
                    line_height: LineHeight::Absolute(Pixels(ch)),
                    font: self.font,
                    align_x: text::Alignment::Left,
                    align_y: Vertical::Top,
                    shaping: if simple { Shaping::Basic } else { Shaping::Advanced },
                    wrapping: Wrapping::None,
                },
                Point::new(x, y),
                color,
                clip,
            );
        };
        for r in runs {
            let (x, y) = (origin.x + r.col as f32 * cw, origin.y + r.row as f32 * ch);
            text_at(renderer, r.text, x, y, r.cells, r.color, r.simple);
        }
        for (rect, color) in decorations {
            quad(renderer, rect, color);
        }

        // The cursor (only when the bottom of the history is shown).
        if show_cursor && offset == 0 {
            let row = cursor_point.line.0 as usize;
            let col = cursor_point.column.0;
            let (x, y) = (origin.x + col as f32 * cw, origin.y + row as f32 * ch);
            let width = under_cursor.as_ref().map_or(1, |u| u.2);
            let cell = Rectangle::new(Point::new(x, y), Size::new(cw * width as f32, ch));
            let color = palette.cursor;
            match (self.focused, content.cursor.shape) {
                (true, CursorShape::Block) => {
                    quad(renderer, cell, color);
                    if let Some((c, bg, w)) = under_cursor.filter(|u| !u.0.trim().is_empty()) {
                        let bg = if bg == color { palette.background } else { bg };
                        text_at(renderer, c, x, y, w, Color { a: 1.0, ..bg }, false);
                    }
                }
                (true, CursorShape::Beam) => quad(renderer, Rectangle::new(cell.position(), Size::new(2.0, ch)), color),
                (true, CursorShape::Underline) => quad(
                    renderer,
                    Rectangle::new(Point::new(x, y + ch - 2.0), Size::new(cell.width, 2.0)),
                    color,
                ),
                _ => renderer.fill_quad(
                    Quad {
                        bounds: cell,
                        border: Border {
                            color,
                            width: 1.0,
                            radius: 0.0.into(),
                        },
                        ..Quad::default()
                    },
                    Color::TRANSPARENT,
                ),
            }
        }
        renderer.end_layer();
    }
}

fn mix(a: Color, b: Color, t: f32) -> Color {
    Color::from_rgb(a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t)
}

impl<'a, Message: 'a, Theme, Renderer> From<TermView<'a, Message>> for Element<'a, Message, Theme, Renderer>
where
    Renderer: text::Renderer<Font = Font> + 'a,
{
    fn from(v: TermView<'a, Message>) -> Self {
        Element::new(v)
    }
}
