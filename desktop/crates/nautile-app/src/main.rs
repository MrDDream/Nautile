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
        .default_font(theme::UI)
        .window_size((1280.0, 800.0))
        .centered()
        .run()
}
