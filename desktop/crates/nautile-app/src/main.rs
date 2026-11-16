// No console window behind the app on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use nautile_app::app::App;
use nautile_app::storage::{Paths, Settings};
use nautile_app::{theme, ui};

fn main() -> iced::Result {
    // The window as it was left; started with the session (« --minimized »): hidden in the notification area.
    let settings = Settings::load(&Paths::detect());
    let saved = settings.window;
    // The renderer, chosen before the window opens: the CPU one, or the graphics card first when « Accélération
    // graphique » is on (the CPU one if it cannot start). An ICED_BACKEND set by hand wins.
    if std::env::var_os("ICED_BACKEND").is_none() {
        let backend = if settings.gpu { "wgpu,tiny-skia" } else { "tiny-skia" };
        // SAFETY: the very start of main, before any other thread exists.
        unsafe { std::env::set_var("ICED_BACKEND", backend) };
    }
    let minimized = std::env::args().any(|a| a == "--minimized");
    let size = saved
        .filter(|w| w.width >= 640.0 && w.height >= 400.0)
        .map(|w| iced::Size::new(w.width, w.height))
        .unwrap_or(iced::Size::new(1280.0, 800.0));
    let position = match saved.and_then(|w| Some(iced::Point::new(w.x?, w.y?))) {
        Some(p) if p.x > -100.0 && p.y > -100.0 => iced::window::Position::Specific(p),
        _ => iced::window::Position::Centered,
    };
    // Several windows: the main one, opened here, and the tabs detached into their own.
    let main = nautile_app::app::window_settings(size, position, !minimized);
    let main = iced::window::Settings {
        min_size: Some(iced::Size::new(760.0, 480.0)),
        ..main
    };
    iced::daemon(
        move || {
            let (mut app, task) = App::boot();
            let (id, open) = iced::window::open(main.clone());
            app.main_window = Some(id);
            app.focused_window = Some(id);
            (app, iced::Task::batch([open.discard(), task]))
        },
        App::update,
        ui::view_window,
    )
    .title(App::window_title)
    .theme(|app: &App, _| app.theme())
    .subscription(App::subscription)
    .font(theme::FONT_UI)
    .font(theme::FONT_MONO)
    .font(theme::FONT_ICONS)
    .default_font(theme::UI)
    .run()
}
