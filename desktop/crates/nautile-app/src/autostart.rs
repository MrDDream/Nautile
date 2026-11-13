//! « Lancer au démarrage »: Nautile starts with the session, hidden in the notification area (`--minimized`).

use std::path::PathBuf;

/// The program to start: the AppImage when run from one, else this executable.
fn program() -> Option<PathBuf> {
    std::env::var_os("APPIMAGE")
        .map(PathBuf::from)
        .or_else(|| std::env::current_exe().ok())
}

#[cfg(all(unix, not(target_os = "macos")))]
fn entry() -> Option<PathBuf> {
    Some(dirs::config_dir()?.join("autostart").join("nautile.desktop"))
}

#[cfg(all(unix, not(target_os = "macos")))]
pub fn set(on: bool) -> std::io::Result<()> {
    let Some(file) = entry() else { return Ok(()) };
    if !on {
        let _ = std::fs::remove_file(&file);
        return Ok(());
    }
    let exe = program().ok_or_else(|| std::io::Error::other("no executable"))?;
    if let Some(dir) = file.parent() {
        std::fs::create_dir_all(dir)?;
    }
    std::fs::write(
        &file,
        format!(
            "[Desktop Entry]\nType=Application\nName=Nautile\nExec=\"{}\" --minimized\nIcon=nautile\nX-GNOME-Autostart-enabled=true\n",
            exe.display()
        ),
    )
}

#[cfg(all(unix, not(target_os = "macos")))]
pub fn is_on() -> bool {
    entry().is_some_and(|f| f.exists())
}

#[cfg(windows)]
const RUN: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";

#[cfg(windows)]
pub fn set(on: bool) -> std::io::Result<()> {
    use winreg::{enums::HKEY_CURRENT_USER, RegKey};
    let (key, _) = RegKey::predef(HKEY_CURRENT_USER).create_subkey(RUN)?;
    if on {
        let exe = program().ok_or_else(|| std::io::Error::other("no executable"))?;
        key.set_value("Nautile", &format!("\"{}\" --minimized", exe.display()))
    } else {
        match key.delete_value("Nautile") {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    }
}

#[cfg(windows)]
pub fn is_on() -> bool {
    use winreg::{enums::HKEY_CURRENT_USER, RegKey};
    RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey(RUN)
        .and_then(|k| k.get_value::<String, _>("Nautile"))
        .is_ok()
}

#[cfg(target_os = "macos")]
fn agent() -> Option<PathBuf> {
    Some(dirs::home_dir()?.join("Library/LaunchAgents/org.nautile.desktop.plist"))
}

#[cfg(target_os = "macos")]
pub fn set(on: bool) -> std::io::Result<()> {
    let Some(file) = agent() else { return Ok(()) };
    if !on {
        let _ = std::fs::remove_file(&file);
        return Ok(());
    }
    let exe = program().ok_or_else(|| std::io::Error::other("no executable"))?;
    if let Some(dir) = file.parent() {
        std::fs::create_dir_all(dir)?;
    }
    std::fs::write(
        &file,
        format!(
            r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>org.nautile.desktop</string>
<key>ProgramArguments</key><array><string>{}</string><string>--minimized</string></array>
<key>RunAtLoad</key><true/>
</dict></plist>
"#,
            exe.display()
        ),
    )
}

#[cfg(target_os = "macos")]
pub fn is_on() -> bool {
    agent().is_some_and(|f| f.exists())
}
