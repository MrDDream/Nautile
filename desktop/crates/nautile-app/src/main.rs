// No console window behind the app on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use nautile_app::app::App;
use nautile_app::{theme, ui};

fn main() -> iced::Result {
    iced::application(App::boot, App::update, ui::view)
        .title(App::title)
        .theme(App::theme)
        .subscription(App::subscription)
        .font(theme::FONT_UI)
        .font(theme::FONT_MONO)
        .font(theme::FONT_ICONS)
        .default_font(theme::UI)
        .window(iced::window::Settings {
            size: iced::Size::new(1280.0, 800.0),
            icon: icon(),
            // The Linux desktop file is matched by this name.
            platform_specific: iced::window::settings::PlatformSpecific {
                #[cfg(target_os = "linux")]
                application_id: "nautile".into(),
                ..Default::default()
            },
            ..Default::default()
        })
        .centered()
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
