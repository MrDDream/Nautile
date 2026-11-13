//! Shows a remote desktop scaled to fit, and sends it the mouse and the keyboard.

use std::collections::HashMap;

use iced::advanced::clipboard::Kind;
use iced::advanced::image::{self, Image};
use iced::advanced::layout::{self, Layout};
use iced::advanced::renderer::{self, Quad};
use iced::advanced::widget::{tree, Tree, Widget};
use iced::advanced::{Clipboard, Shell};
use iced::keyboard::{self, key::Named, Key};
use iced::mouse::{self, ScrollDelta};
use iced::{Color, Element, Event, Length, Point, Rectangle, Size};
use nautile_remote::keysyms as ks;
use nautile_remote::RemoteInput;

/// What the user did on the remote desktop.
#[derive(Debug, Clone)]
pub enum RemoteAction {
    Input(RemoteInput),
    /// Ctrl+Shift+V: this computer's clipboard goes to the remote one.
    Paste(String),
}

pub struct RemoteView<'a, Message> {
    image: Option<&'a image::Handle>,
    size: (u32, u32),
    cursor: Option<&'a (image::Handle, u32, u32, u32, u32)>,
    focused: bool,
    /// Never larger than the desktop's real size.
    no_upscale: bool,
    on_action: Box<dyn Fn(RemoteAction) -> Message + 'a>,
}

pub fn view<'a, Message>(
    image: Option<&'a image::Handle>,
    size: (u32, u32),
    cursor: Option<&'a (image::Handle, u32, u32, u32, u32)>,
    focused: bool,
    on_action: impl Fn(RemoteAction) -> Message + 'a,
) -> RemoteView<'a, Message> {
    RemoteView {
        image,
        size,
        cursor,
        focused,
        no_upscale: false,
        on_action: Box::new(on_action),
    }
}

#[derive(Default)]
struct State {
    buttons: u8,
    last: (u16, u16),
    /// Keys held, by physical key, with the keysym sent for them.
    held: HashMap<String, (u32, Option<u16>)>,
    scroll_rest: f32,
    inside: bool,
}

impl<'a, Message> RemoteView<'a, Message> {
    /// Never drawn larger than the desktop's real size (sharper, with margins).
    pub fn no_upscale(mut self, v: bool) -> Self {
        self.no_upscale = v;
        self
    }

    /// Where the desktop is drawn: scaled to fit, centred.
    fn dest(&self, bounds: Rectangle) -> (Rectangle, f32) {
        let (w, h) = (self.size.0.max(1) as f32, self.size.1.max(1) as f32);
        let mut scale = (bounds.width / w).min(bounds.height / h);
        if self.no_upscale {
            scale = scale.min(1.0);
        }
        let (dw, dh) = (w * scale, h * scale);
        (
            Rectangle::new(
                Point::new(bounds.x + (bounds.width - dw) / 2.0, bounds.y + (bounds.height - dh) / 2.0),
                Size::new(dw, dh),
            ),
            scale,
        )
    }

    fn remote_point(&self, bounds: Rectangle, p: Point) -> Option<(u16, u16)> {
        let (d, scale) = self.dest(bounds);
        if !d.contains(p) || scale <= 0.0 {
            return None;
        }
        let x = ((p.x - d.x) / scale).clamp(0.0, self.size.0.saturating_sub(1) as f32);
        let y = ((p.y - d.y) / scale).clamp(0.0, self.size.1.saturating_sub(1) as f32);
        Some((x as u16, y as u16))
    }
}

/// The keysym of a key that is not a character.
fn named(n: Named, location: keyboard::Location) -> Option<u32> {
    let right = location == keyboard::Location::Right;
    Some(match n {
        Named::Enter => ks::RETURN,
        Named::Backspace => ks::BACKSPACE,
        Named::Tab => ks::TAB,
        Named::Escape => ks::ESCAPE,
        Named::Delete => ks::DELETE,
        Named::Insert => ks::INSERT,
        Named::Home => ks::HOME,
        Named::End => ks::END,
        Named::PageUp => ks::PAGE_UP,
        Named::PageDown => ks::PAGE_DOWN,
        Named::ArrowLeft => ks::LEFT,
        Named::ArrowRight => ks::RIGHT,
        Named::ArrowUp => ks::UP,
        Named::ArrowDown => ks::DOWN,
        Named::Shift => {
            if right {
                ks::SHIFT_R
            } else {
                ks::SHIFT_L
            }
        }
        Named::Control => {
            if right {
                ks::CONTROL_R
            } else {
                ks::CONTROL_L
            }
        }
        Named::Alt => {
            if right {
                ks::ALT_R
            } else {
                ks::ALT_L
            }
        }
        Named::Super | Named::Meta => ks::SUPER_L,
        Named::CapsLock => ks::CAPS_LOCK,
        Named::ContextMenu => ks::MENU,
        Named::PrintScreen => ks::PRINT,
        Named::Space => 0x20,
        Named::F1 => ks::F1,
        Named::F2 => ks::F1 + 1,
        Named::F3 => ks::F1 + 2,
        Named::F4 => ks::F1 + 3,
        Named::F5 => ks::F1 + 4,
        Named::F6 => ks::F1 + 5,
        Named::F7 => ks::F1 + 6,
        Named::F8 => ks::F1 + 7,
        Named::F9 => ks::F1 + 8,
        Named::F10 => ks::F1 + 9,
        Named::F11 => ks::F1 + 10,
        Named::F12 => ks::F1 + 11,
        _ => return None,
    })
}

impl<'a, Message, Theme, Renderer> Widget<Message, Theme, Renderer> for RemoteView<'a, Message>
where
    Renderer: image::Renderer<Handle = image::Handle>,
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

    fn layout(&mut self, _tree: &mut Tree, _renderer: &Renderer, limits: &layout::Limits) -> layout::Node {
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
        let st = tree.state.downcast_mut::<State>();
        let bounds = layout.bounds();
        let send = |shell: &mut Shell<'_, Message>, i: RemoteInput| shell.publish((self.on_action)(RemoteAction::Input(i)));
        match event {
            Event::Mouse(mouse::Event::CursorMoved { position }) => {
                let p = self.remote_point(bounds, *position);
                st.inside = p.is_some();
                if let Some((x, y)) = p {
                    if (x, y) != st.last {
                        st.last = (x, y);
                        send(shell, RemoteInput::Pointer { buttons: st.buttons, x, y });
                    }
                }
            }
            Event::Mouse(mouse::Event::ButtonPressed(b) | mouse::Event::ButtonReleased(b)) => {
                let bit = match b {
                    mouse::Button::Left => 1,
                    mouse::Button::Middle => 2,
                    mouse::Button::Right => 4,
                    _ => return,
                };
                let pressed = matches!(event, Event::Mouse(mouse::Event::ButtonPressed(_)));
                if pressed && cursor.position_in(bounds).is_none() {
                    return;
                }
                if pressed {
                    st.buttons |= bit;
                } else if st.buttons & bit != 0 {
                    st.buttons &= !bit;
                } else {
                    return;
                }
                let (x, y) = st.last;
                send(shell, RemoteInput::Pointer { buttons: st.buttons, x, y });
                shell.capture_event();
            }
            Event::Mouse(mouse::Event::WheelScrolled { delta }) => {
                if cursor.position_in(bounds).is_none() {
                    return;
                }
                let steps = match delta {
                    ScrollDelta::Lines { y, .. } => y.round() as i32,
                    ScrollDelta::Pixels { y, .. } => {
                        st.scroll_rest += y / 40.0;
                        let s = st.scroll_rest.trunc();
                        st.scroll_rest -= s;
                        s as i32
                    }
                };
                let bit = if steps > 0 { 8 } else { 16 };
                let (x, y) = st.last;
                for _ in 0..steps.unsigned_abs().min(10) {
                    send(
                        shell,
                        RemoteInput::Pointer {
                            buttons: st.buttons | bit,
                            x,
                            y,
                        },
                    );
                    send(shell, RemoteInput::Pointer { buttons: st.buttons, x, y });
                }
                shell.capture_event();
            }
            Event::Keyboard(keyboard::Event::KeyPressed {
                key,
                physical_key,
                location,
                modifiers,
                text,
                ..
            }) if self.focused => {
                let (ctrl, shift) = (modifiers.control(), modifiers.shift());
                // Left to the window: tabs and the app's own shortcuts.
                if ctrl
                    && (matches!(key, Key::Named(Named::Tab)) || (shift && matches!(key.as_ref(), Key::Character("t" | "T" | "w" | "W"))))
                {
                    return;
                }
                if ctrl && shift && matches!(key.as_ref(), Key::Character("v" | "V")) {
                    if let Some(t) = clipboard.read(Kind::Standard) {
                        shell.publish((self.on_action)(RemoteAction::Paste(t)));
                    }
                    shell.capture_event();
                    return;
                }
                let keysym = match key {
                    Key::Named(n) => named(*n, *location),
                    // With Ctrl or Alt held, the key itself (Ctrl+C sends c); else the character typed.
                    Key::Character(c) if ctrl || modifiers.alt() => c.chars().next().map(|c| ks::of(c.to_ascii_lowercase())),
                    Key::Character(c) => text
                        .as_deref()
                        .and_then(|t| t.chars().next())
                        .or_else(|| c.chars().next())
                        .map(ks::of),
                    Key::Unidentified => text.as_deref().and_then(|t| t.chars().next()).map(ks::of),
                };
                let code = scancode(physical_key);
                if let Some(k) = keysym.or(code.map(|_| 0)) {
                    st.held.insert(format!("{physical_key:?}"), (k, code));
                    send(
                        shell,
                        RemoteInput::Key {
                            keysym: k,
                            scancode: code,
                            down: true,
                        },
                    );
                    shell.capture_event();
                }
            }
            Event::Keyboard(keyboard::Event::KeyReleased { physical_key, .. }) => {
                if let Some((k, code)) = st.held.remove(&format!("{physical_key:?}")) {
                    send(
                        shell,
                        RemoteInput::Key {
                            keysym: k,
                            scancode: code,
                            down: false,
                        },
                    );
                    shell.capture_event();
                }
            }
            _ => {}
        }
    }

    fn mouse_interaction(
        &self,
        tree: &Tree,
        layout: Layout<'_>,
        cursor: mouse::Cursor,
        _viewport: &Rectangle,
        _renderer: &Renderer,
    ) -> mouse::Interaction {
        let st = tree.state.downcast_ref::<State>();
        match cursor.position() {
            // The server draws its own pointer: the local one is hidden over the desktop.
            Some(p) if self.cursor.is_some() && self.remote_point(layout.bounds(), p).is_some() && st.inside => mouse::Interaction::Hidden,
            Some(p) if layout.bounds().contains(p) => mouse::Interaction::Crosshair,
            _ => mouse::Interaction::None,
        }
    }

    fn draw(
        &self,
        tree: &Tree,
        renderer: &mut Renderer,
        _theme: &Theme,
        _style: &renderer::Style,
        layout: Layout<'_>,
        cursor: mouse::Cursor,
        _viewport: &Rectangle,
    ) {
        let bounds = layout.bounds();
        renderer.fill_quad(Quad { bounds, ..Quad::default() }, Color::BLACK);
        let Some(handle) = self.image else { return };
        let (d, scale) = self.dest(bounds);
        renderer.draw_image(Image::new(handle.clone()).filter_method(image::FilterMethod::Linear), d, bounds);
        let st = tree.state.downcast_ref::<State>();
        if let (Some((h, w, hh, hx, hy)), Some(p)) = (self.cursor, cursor.position()) {
            if st.inside && d.contains(p) {
                let r = Rectangle::new(
                    Point::new(p.x - *hx as f32 * scale, p.y - *hy as f32 * scale),
                    Size::new(*w as f32 * scale, *hh as f32 * scale),
                );
                renderer.draw_image(Image::new(h.clone()), r, bounds);
            }
        }
    }
}

impl<'a, Message: 'a, Theme, Renderer> From<RemoteView<'a, Message>> for Element<'a, Message, Theme, Renderer>
where
    Renderer: image::Renderer<Handle = image::Handle> + 'a,
{
    fn from(v: RemoteView<'a, Message>) -> Self {
        Element::new(v)
    }
}

/// The PC/AT (set 1) scancode of a physical key, for RDP; `0xE0xx` for the extended ones.
pub fn scancode(p: &iced::keyboard::key::Physical) -> Option<u16> {
    use iced::keyboard::key::{Code as C, Physical};
    let Physical::Code(c) = p else { return None };
    Some(match c {
        C::Escape => 0x01,
        C::Digit1 => 0x02,
        C::Digit2 => 0x03,
        C::Digit3 => 0x04,
        C::Digit4 => 0x05,
        C::Digit5 => 0x06,
        C::Digit6 => 0x07,
        C::Digit7 => 0x08,
        C::Digit8 => 0x09,
        C::Digit9 => 0x0A,
        C::Digit0 => 0x0B,
        C::Minus => 0x0C,
        C::Equal => 0x0D,
        C::Backspace => 0x0E,
        C::Tab => 0x0F,
        C::KeyQ => 0x10,
        C::KeyW => 0x11,
        C::KeyE => 0x12,
        C::KeyR => 0x13,
        C::KeyT => 0x14,
        C::KeyY => 0x15,
        C::KeyU => 0x16,
        C::KeyI => 0x17,
        C::KeyO => 0x18,
        C::KeyP => 0x19,
        C::BracketLeft => 0x1A,
        C::BracketRight => 0x1B,
        C::Enter => 0x1C,
        C::ControlLeft => 0x1D,
        C::KeyA => 0x1E,
        C::KeyS => 0x1F,
        C::KeyD => 0x20,
        C::KeyF => 0x21,
        C::KeyG => 0x22,
        C::KeyH => 0x23,
        C::KeyJ => 0x24,
        C::KeyK => 0x25,
        C::KeyL => 0x26,
        C::Semicolon => 0x27,
        C::Quote => 0x28,
        C::Backquote => 0x29,
        C::ShiftLeft => 0x2A,
        C::Backslash => 0x2B,
        C::KeyZ => 0x2C,
        C::KeyX => 0x2D,
        C::KeyC => 0x2E,
        C::KeyV => 0x2F,
        C::KeyB => 0x30,
        C::KeyN => 0x31,
        C::KeyM => 0x32,
        C::Comma => 0x33,
        C::Period => 0x34,
        C::Slash => 0x35,
        C::ShiftRight => 0x36,
        C::NumpadMultiply => 0x37,
        C::AltLeft => 0x38,
        C::Space => 0x39,
        C::CapsLock => 0x3A,
        C::F1 => 0x3B,
        C::F2 => 0x3C,
        C::F3 => 0x3D,
        C::F4 => 0x3E,
        C::F5 => 0x3F,
        C::F6 => 0x40,
        C::F7 => 0x41,
        C::F8 => 0x42,
        C::F9 => 0x43,
        C::F10 => 0x44,
        C::NumLock => 0x45,
        C::ScrollLock => 0x46,
        C::Numpad7 => 0x47,
        C::Numpad8 => 0x48,
        C::Numpad9 => 0x49,
        C::NumpadSubtract => 0x4A,
        C::Numpad4 => 0x4B,
        C::Numpad5 => 0x4C,
        C::Numpad6 => 0x4D,
        C::NumpadAdd => 0x4E,
        C::Numpad1 => 0x4F,
        C::Numpad2 => 0x50,
        C::Numpad3 => 0x51,
        C::Numpad0 => 0x52,
        C::NumpadDecimal => 0x53,
        C::IntlBackslash => 0x56,
        C::F11 => 0x57,
        C::F12 => 0x58,
        C::NumpadEnter => 0xE01C,
        C::ControlRight => 0xE01D,
        C::NumpadDivide => 0xE035,
        C::PrintScreen => 0xE037,
        C::AltRight => 0xE038,
        C::Home => 0xE047,
        C::ArrowUp => 0xE048,
        C::PageUp => 0xE049,
        C::ArrowLeft => 0xE04B,
        C::ArrowRight => 0xE04D,
        C::End => 0xE04F,
        C::ArrowDown => 0xE050,
        C::PageDown => 0xE051,
        C::Insert => 0xE052,
        C::Delete => 0xE053,
        C::SuperLeft => 0xE05B,
        C::SuperRight => 0xE05C,
        C::ContextMenu => 0xE05D,
        _ => return None,
    })
}
