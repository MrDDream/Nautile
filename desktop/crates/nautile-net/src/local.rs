//! A shell on this computer: PowerShell on Windows, `$SHELL` elsewhere.

use std::io::{Read, Write};

use nautile_core::trf;
use portable_pty::{native_pty_system, CommandBuilder, PtySize};

use crate::session::{channels, Event, Input, Session};

/// The shells offered for a local terminal, the default first.
pub fn shells() -> Vec<String> {
    #[cfg(windows)]
    {
        let mut v = Vec::new();
        if which("pwsh.exe") {
            v.push("pwsh.exe".to_owned());
        }
        v.push("powershell.exe".to_owned());
        v.push("cmd.exe".to_owned());
        if which("wsl.exe") {
            v.push("wsl.exe".to_owned());
        }
        v
    }
    #[cfg(not(windows))]
    {
        let mut v = vec![std::env::var("SHELL")
            .ok()
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "/bin/sh".into())];
        for s in ["/bin/bash", "/usr/bin/zsh", "/usr/bin/fish", "/bin/sh"] {
            if std::path::Path::new(s).exists() && !v.iter().any(|x| x == s) {
                v.push(s.into());
            }
        }
        v
    }
}

#[cfg(windows)]
fn which(exe: &str) -> bool {
    std::env::var_os("PATH").is_some_and(|p| std::env::split_paths(&p).any(|d| d.join(exe).is_file()))
}

/// Starts [shell] (the default one when `None`) in a pseudo-terminal of `cols`×`rows`.
pub fn spawn(shell: Option<String>, cols: u16, rows: u16) -> Session {
    let (session, events, mut input) = channels();
    let shell = shell.unwrap_or_else(|| shells().remove(0));
    let started = (|| -> Result<_, String> {
        let pair = native_pty_system()
            .openpty(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .map_err(|e| e.to_string())?;
        let mut cmd = CommandBuilder::new(&shell);
        cmd.env("TERM", "xterm-256color");
        cmd.env("COLORTERM", "truecolor");
        if let Some(home) = std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" }) {
            cmd.cwd(home);
        }
        let child = pair.slave.spawn_command(cmd).map_err(|e| e.to_string())?;
        drop(pair.slave);
        let reader = pair.master.try_clone_reader().map_err(|e| e.to_string())?;
        let writer = pair.master.take_writer().map_err(|e| e.to_string())?;
        Ok((pair.master, child, reader, writer))
    })();
    let (master, mut child, mut reader, mut writer) = match started {
        Ok(v) => v,
        Err(e) => {
            events.send(Event::Closed(Some(trf("Impossible de lancer %1$s : %2$s", &[&shell, &e]))));
            return session;
        }
    };
    events.send(Event::Connected);

    let mut killer = child.clone_killer();
    let out = events.clone();
    std::thread::Builder::new()
        .name("pty-read".into())
        .spawn(move || {
            let mut buf = vec![0u8; 64 * 1024];
            loop {
                match reader.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => out.output(&buf[..n]),
                }
            }
            let reason = match child.wait() {
                Ok(s) if s.success() => None,
                Ok(s) => Some(trf("Shell terminé (code %1$s)", &[&s.exit_code()])),
                Err(e) => Some(e.to_string()),
            };
            out.send(Event::Closed(reason));
        })
        .ok();
    std::thread::Builder::new()
        .name("pty-write".into())
        .spawn(move || {
            while let Some(i) = input.blocking_recv() {
                match i {
                    Input::Data(d) => {
                        if writer.write_all(&d).and_then(|_| writer.flush()).is_err() {
                            break;
                        }
                    }
                    Input::Resize { cols, rows } => {
                        let _ = master.resize(PtySize {
                            rows,
                            cols,
                            pixel_width: 0,
                            pixel_height: 0,
                        });
                    }
                    Input::Close => break,
                }
            }
            let _ = killer.kill();
            drop(master);
        })
        .ok();
    session
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn runs_a_command_in_a_shell() {
        let mut s = spawn(Some("/bin/sh".into()), 80, 24);
        s.input.send(Input::Data(b"echo nautile-$((40+2)); exit 3\n".to_vec())).unwrap();
        let mut out = Vec::new();
        let mut closed = None;
        while let Some(e) = s.events.blocking_recv() {
            match e {
                Event::Output(d) => out.extend(d),
                Event::Closed(r) => {
                    closed = Some(r);
                    break;
                }
                _ => {}
            }
        }
        assert!(String::from_utf8_lossy(&out).contains("nautile-42"));
        assert!(closed.unwrap().unwrap().contains('3'));
    }
}
