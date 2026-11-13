//! The icon in the notification area (« zone de notification »): open Nautile, a local terminal, synchronise,
//! lock, quit. Linux: StatusNotifierItem over D-Bus (KDE, XFCE, Cinnamon, GNOME with AppIndicator), no GTK;
//! Windows and macOS: `tray-icon`. Its clicks reach the app through [subscription].

use std::sync::{Mutex, OnceLock};

use iced::futures::SinkExt;
use nautile_core::tr;
use tokio::sync::mpsc;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrayEvent {
    Open,
    NewLocal,
    Sync,
    Lock,
    Quit,
}

type Channel = (mpsc::UnboundedSender<TrayEvent>, Mutex<Option<mpsc::UnboundedReceiver<TrayEvent>>>);

fn channel() -> &'static Channel {
    static CH: OnceLock<Channel> = OnceLock::new();
    CH.get_or_init(|| {
        let (tx, rx) = mpsc::unbounded_channel();
        (tx, Mutex::new(Some(rx)))
    })
}

fn send(e: TrayEvent) {
    let _ = channel().0.send(e);
}

/// The clicks on the icon and its menu.
pub fn subscription() -> iced::Subscription<TrayEvent> {
    iced::Subscription::run(|| {
        iced::stream::channel(16, async |mut out: iced::futures::channel::mpsc::Sender<TrayEvent>| {
            let rx = channel().1.lock().ok().and_then(|mut r| r.take());
            let Some(mut rx) = rx else {
                std::future::pending::<()>().await;
                return;
            };
            while let Some(e) = rx.recv().await {
                let _ = out.send(e).await;
            }
        })
    })
}

/// The menu's entries, in this order.
fn entries() -> [(TrayEvent, String); 5] {
    [
        (TrayEvent::Open, tr("Ouvrir Nautile")),
        (TrayEvent::NewLocal, tr("Nouveau terminal local")),
        (TrayEvent::Sync, tr("Synchroniser maintenant")),
        (TrayEvent::Lock, tr("Verrouiller")),
        (TrayEvent::Quit, tr("Quitter")),
    ]
}

/// The app's icon, as RGBA, reduced to [size] pixels.
fn icon_rgba(size: u32) -> (u32, u32, Vec<u8>) {
    let decoder = png::Decoder::new(std::io::Cursor::new(include_bytes!("../../../packaging/nautile.png")));
    let Some((w, h, src)) = decoder.read_info().ok().and_then(|mut r| {
        let mut buf = vec![0; r.output_buffer_size()];
        let info = r.next_frame(&mut buf).ok()?;
        buf.truncate(info.buffer_size());
        Some((info.width, info.height, buf))
    }) else {
        return (1, 1, vec![0; 4]);
    };
    let step = (w / size).max(1);
    let (ow, oh) = (w / step, h / step);
    let mut out = Vec::with_capacity((ow * oh * 4) as usize);
    // Each pixel is the average of its block (premultiplied, so edges stay clean).
    for y in 0..oh {
        for x in 0..ow {
            let mut acc = [0u32; 4];
            for dy in 0..step {
                for dx in 0..step {
                    let i = (((y * step + dy) * w + x * step + dx) * 4) as usize;
                    let a = src[i + 3] as u32;
                    acc[0] += src[i] as u32 * a;
                    acc[1] += src[i + 1] as u32 * a;
                    acc[2] += src[i + 2] as u32 * a;
                    acc[3] += a;
                }
            }
            let n = step * step;
            let a = acc[3];
            let c = |v: u32| v.checked_div(a).unwrap_or(0) as u8;
            out.extend_from_slice(&[c(acc[0]), c(acc[1]), c(acc[2]), (a / n) as u8]);
        }
    }
    (ow, oh, out)
}

#[cfg(target_os = "linux")]
mod imp {
    use super::*;
    use ksni::blocking::TrayMethods;

    struct NautileTray {
        icon: ksni::Icon,
        entries: [(TrayEvent, String); 5],
    }

    impl ksni::Tray for NautileTray {
        fn id(&self) -> String {
            "nautile".into()
        }

        fn title(&self) -> String {
            "Nautile".into()
        }

        fn icon_pixmap(&self) -> Vec<ksni::Icon> {
            vec![ksni::Icon {
                width: self.icon.width,
                height: self.icon.height,
                data: self.icon.data.clone(),
            }]
        }

        fn activate(&mut self, _x: i32, _y: i32) {
            send(TrayEvent::Open);
        }

        fn menu(&self) -> Vec<ksni::MenuItem<Self>> {
            use ksni::menu::StandardItem;
            let mut items: Vec<ksni::MenuItem<Self>> = Vec::new();
            for (i, (e, label)) in self.entries.iter().enumerate() {
                if i == 3 || i == 4 {
                    items.push(ksni::MenuItem::Separator);
                }
                let e = *e;
                items.push(
                    StandardItem {
                        label: label.clone(),
                        activate: Box::new(move |_: &mut Self| send(e)),
                        ..Default::default()
                    }
                    .into(),
                );
            }
            items
        }
    }

    pub fn start() -> bool {
        let (w, h, rgba) = icon_rgba(64);
        // ARGB32, network byte order.
        let data = rgba.as_chunks::<4>().0.iter().flat_map(|p| [p[3], p[0], p[1], p[2]]).collect();
        let tray = NautileTray {
            icon: ksni::Icon {
                width: w as i32,
                height: h as i32,
                data,
            },
            entries: entries(),
        };
        match tray.spawn() {
            Ok(handle) => {
                // Kept for the whole run.
                std::mem::forget(handle);
                true
            }
            Err(_) => false,
        }
    }
}

#[cfg(any(windows, target_os = "macos"))]
mod imp {
    use super::*;
    use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
    use tray_icon::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};

    fn id(e: TrayEvent) -> &'static str {
        match e {
            TrayEvent::Open => "open",
            TrayEvent::NewLocal => "local",
            TrayEvent::Sync => "sync",
            TrayEvent::Lock => "lock",
            TrayEvent::Quit => "quit",
        }
    }

    pub fn start() -> bool {
        let menu = Menu::new();
        for (i, (e, label)) in entries().into_iter().enumerate() {
            if i == 3 || i == 4 {
                let _ = menu.append(&PredefinedMenuItem::separator());
            }
            let _ = menu.append(&MenuItem::with_id(id(e), label, true, None));
        }
        MenuEvent::set_event_handler(Some(|m: MenuEvent| {
            let e = [
                TrayEvent::Open,
                TrayEvent::NewLocal,
                TrayEvent::Sync,
                TrayEvent::Lock,
                TrayEvent::Quit,
            ]
            .into_iter()
            .find(|e| m.id.0 == id(*e));
            if let Some(e) = e {
                send(e);
            }
        }));
        TrayIconEvent::set_event_handler(Some(|e: TrayIconEvent| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = e
            {
                send(TrayEvent::Open);
            }
        }));
        let (w, h, rgba) = icon_rgba(64);
        let Ok(icon) = tray_icon::Icon::from_rgba(rgba, w, h) else {
            return false;
        };
        match TrayIconBuilder::new()
            .with_menu(Box::new(menu))
            .with_icon(icon)
            .with_tooltip("Nautile")
            .build()
        {
            Ok(tray) => {
                // Kept for the whole run (it is not Send, and dropping it removes the icon).
                Box::leak(Box::new(tray));
                true
            }
            Err(_) => false,
        }
    }
}

#[cfg(not(any(target_os = "linux", windows, target_os = "macos")))]
mod imp {
    pub fn start() -> bool {
        false
    }
}

/// Puts the icon in the notification area; false when the desktop has none.
pub fn start() -> bool {
    imp::start()
}

/// A system notification (used while the window is hidden or in the background).
pub fn notify(title: String, body: String) {
    std::thread::spawn(move || {
        let _ = notify_rust::Notification::new()
            .appname("Nautile")
            .summary(&title)
            .body(&body)
            .show();
    });
}
