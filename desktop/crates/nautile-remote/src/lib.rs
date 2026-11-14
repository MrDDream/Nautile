//! Remote desktops. Each connection runs on its own threads and shares its framebuffer with the interface;
//! events (frames, questions, the end) go out through a channel, input comes in through another.

pub mod keysyms;
pub mod rdp;
pub mod vnc;

use std::sync::{Arc, Mutex};

pub use nautile_net::Prompt;

/// The remote screen as RGBA, row by row.
#[derive(Debug, Default, Clone)]
pub struct Framebuffer {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
    /// The connection writes the whole picture before each [RemoteEvent::Frame], so the interface may take it
    /// instead of copying it. False when it is updated piece by piece (VNC, RDP: only what changed is written
    /// into it): it must stay in place.
    pub whole: bool,
}

impl Framebuffer {
    /// The picture for the renderer (width, height, RGBA), `None` while it is not complete: taken when
    /// [Framebuffer::whole], copied otherwise.
    pub fn picture(&mut self) -> Option<(u32, u32, Vec<u8>)> {
        if self.width == 0 || self.height == 0 || self.rgba.len() != self.width as usize * self.height as usize * 4 {
            return None;
        }
        let rgba = if self.whole {
            std::mem::take(&mut self.rgba)
        } else {
            self.rgba.clone()
        };
        Some((self.width, self.height, rgba))
    }
}

pub type SharedFrame = Arc<Mutex<Framebuffer>>;

/// The server's pointer shape (RGBA) and its hot spot.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Cursor {
    pub width: u32,
    pub height: u32,
    pub hot_x: u32,
    pub hot_y: u32,
    pub rgba: Vec<u8>,
}

#[derive(Debug, Clone)]
pub enum RemoteEvent {
    Status(String),
    /// Connected: the desktop's name.
    Ready(String),
    /// The framebuffer changed (or changed size).
    Frame,
    /// `None`: hidden (the local pointer is shown).
    Cursor(Option<Cursor>),
    Clipboard(String),
    Prompt(Prompt),
    /// A server certificate the user accepted, to save in the known hosts.
    Trust(nautile_net::HostKeyInfo),
    Closed(Option<String>),
}

#[derive(Debug, Clone)]
pub enum RemoteInput {
    /// Buttons: bit 0 left, 1 middle, 2 right, 3 wheel up, 4 wheel down.
    Pointer {
        buttons: u8,
        x: u16,
        y: u16,
    },
    /// An X11 keysym (see [keysyms]) for VNC; RDP prefers the PC scancode when the key has one.
    Key {
        keysym: u32,
        scancode: Option<u16>,
        down: bool,
    },
    Clipboard(String),
    Close,
}

/// The interface's side of a remote desktop.
pub struct Remote {
    pub input: std::sync::mpsc::Sender<RemoteInput>,
    pub events: tokio::sync::mpsc::UnboundedReceiver<RemoteEvent>,
    pub frame: SharedFrame,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_whole_picture_is_taken_a_partial_one_copied() {
        let mut f = Framebuffer {
            width: 2,
            height: 1,
            rgba: vec![1; 8],
            whole: false,
        };
        assert_eq!(f.picture(), Some((2, 1, vec![1; 8])));
        assert_eq!(f.rgba.len(), 8, "kept for the next updates");
        f.whole = true;
        assert_eq!(f.picture(), Some((2, 1, vec![1; 8])));
        assert!(f.rgba.is_empty());
        assert_eq!(f.picture(), None, "nothing until the next frame");
    }
}
