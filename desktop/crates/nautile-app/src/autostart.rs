//! « Lancer au démarrage »: Nautile starts with the session, hidden in the notification area (`--minimized`).

use std::path::PathBuf;

/// The program to start: the AppImage when run from one, else this executable.
fn program() -> Option<PathBuf> {
    std::env::var_os("APPIMAGE")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute() && p.is_file())
        .or_else(|| std::env::current_exe().ok())
}

/// [path] as one quoted argument of a Desktop Entry's `Exec` key: the quoting rule (`"`, `` ` ``, `$`, `\`),
/// then the string rule (`\` doubled again), and `%` doubled (field codes).
#[cfg(any(test, all(unix, not(target_os = "macos"))))]
fn desktop_exec_arg(path: &str) -> String {
    let mut quoted = String::from("\"");
    for c in path.chars() {
        match c {
            '"' | '`' | '$' => {
                quoted.push_str("\\\\");
                quoted.push(c);
            }
            '\\' => quoted.push_str("\\\\\\\\"),
            '%' => quoted.push_str("%%"),
            '\n' => quoted.push_str("\\n"),
            c => quoted.push(c),
        }
    }
    quoted.push('"');
    quoted
}

/// [s] as XML text (the macOS launch agent).
#[cfg(any(test, target_os = "macos"))]
fn xml_text(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
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
            "[Desktop Entry]\nType=Application\nName=Nautile\nExec={} --minimized\nIcon=nautile\nX-GNOME-Autostart-enabled=true\n",
            desktop_exec_arg(&exe.to_string_lossy())
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
    use winreg::{RegKey, enums::HKEY_CURRENT_USER};
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
    use winreg::{RegKey, enums::HKEY_CURRENT_USER};
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
            xml_text(&exe.to_string_lossy())
        ),
    )
}

#[cfg(target_os = "macos")]
pub fn is_on() -> bool {
    agent().is_some_and(|f| f.exists())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths_are_escaped() {
        assert_eq!(desktop_exec_arg("/opt/Nautile/nautile"), "\"/opt/Nautile/nautile\"");
        assert_eq!(desktop_exec_arg("/home/a b/100%/x"), "\"/home/a b/100%%/x\"");
        assert_eq!(desktop_exec_arg("/t/$(id)`x`\"q"), r#""/t/\\$(id)\\`x\\`\\"q""#);
        assert_eq!(desktop_exec_arg(r"/t/a\b"), r#""/t/a\\\\b""#);
        assert_eq!(xml_text("/Apps/A&B <1>.app"), "/Apps/A&amp;B &lt;1&gt;.app");
    }
}
