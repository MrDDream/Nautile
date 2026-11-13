// No console window behind the app on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use nautile_app::app::App;
use nautile_app::storage::{Paths, Settings};
use nautile_app::{theme, ui};

fn main() -> iced::Result {
    // The window as it was left; started with the session (« --minimized »): hidden in the notification area.
    let saved = Settings::load(&Paths::detect()).window;
    let minimized = std::env::args().any(|a| a == "--minimized");
    let size = saved
        .filter(|w| w.width >= 640.0 && w.height >= 400.0)
        .map(|w| iced::Size::new(w.width, w.height))
        .unwrap_or(iced::Size::new(1280.0, 800.0));
    let position = match saved.and_then(|w| Some(iced::Point::new(w.x?, w.y?))) {
        Some(p) if p.x > -100.0 && p.y > -100.0 => iced::window::Position::Specific(p),
        _ => iced::window::Position::Centered,
    };
    iced::application(App::boot, App::update, ui::view)
        .title(App::title)
        .theme(App::theme)
        .subscription(App::subscription)
        .font(theme::FONT_UI)
        .font(theme::FONT_MONO)
        .font(theme::FONT_ICONS)
        .default_font(theme::UI)
        .window(iced::window::Settings {
            size,
            position,
            visible: !minimized,
            min_size: Some(iced::Size::new(760.0, 480.0)),
            exit_on_close_request: false,
            icon: icon(),
            // The Linux desktop file is matched by this name.
            platform_specific: iced::window::settings::PlatformSpecific {
                #[cfg(target_os = "linux")]
                application_id: "nautile".into(),
                ..Default::default()
            },
            ..Default::default()
        })
        .run()
}

/// The window's icon, from the same picture as the packages'.
fn icon() -> Option<iced::window::Icon> {
    let decoder = png::Decoder::new(std::io::Cursor::new(include_bytes!("../../../packaging/nautile.png")));
    let mut reader = decoder.read_info().ok()?;
    let mut buf = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf).ok()?;
    if info.color_type != png::ColorType::Rgba {
        return None;
    }
    buf.truncate(info.buffer_size());
    iced::window::icon::from_rgba(buf, info.width, info.height).ok()
}
