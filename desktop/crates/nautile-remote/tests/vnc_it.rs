//! Against x11vnc in Docker (Xvfb 800×600 with an xterm): `scripts/vnc-it.sh --desktop`.

use std::time::Duration;

use nautile_remote::{vnc, RemoteEvent, RemoteInput};

#[test]
fn a_real_vnc_server() {
    let Some(port) = std::env::var("IT_VNC_PORT").ok().and_then(|p| p.parse().ok()) else {
        eprintln!("IT_VNC_PORT not set: skipped");
        return;
    };
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
    rt.block_on(async {
        // A wrong password first.
        let mut r = vnc::spawn("127.0.0.1".into(), port, "vnc".into(), Some("wrong".into()));
        loop {
            match tokio::time::timeout(Duration::from_secs(20), r.events.recv())
                .await
                .unwrap()
                .unwrap()
            {
                RemoteEvent::Closed(e) => {
                    assert!(e.is_some_and(|e| e.contains("refus")), "refused");
                    break;
                }
                RemoteEvent::Ready(_) => panic!("accepted a wrong password"),
                _ => {}
            }
        }
        let mut r = vnc::spawn("127.0.0.1".into(), port, "vnc".into(), Some("secret12".into()));
        let mut frames = 0;
        while frames < 2 {
            match tokio::time::timeout(Duration::from_secs(20), r.events.recv())
                .await
                .unwrap()
                .unwrap()
            {
                RemoteEvent::Frame => frames += 1,
                RemoteEvent::Closed(e) => panic!("closed: {e:?}"),
                _ => {}
            }
        }
        {
            let f = r.frame.lock().unwrap();
            assert_eq!((f.width, f.height), (800, 600));
            // The white xterm on the black root window.
            let white = f.rgba.chunks(4).filter(|p| p[0] > 200 && p[1] > 200 && p[2] > 200).count();
            assert!(white > 10_000, "xterm drawn ({white} white pixels)");
        }
        r.input
            .send(RemoteInput::Pointer {
                buttons: 0,
                x: 100,
                y: 100,
            })
            .unwrap();
        for c in "echo ok".chars() {
            let k = nautile_remote::keysyms::of(c);
            r.input
                .send(RemoteInput::Key {
                    keysym: k,
                    scancode: None,
                    down: true,
                })
                .unwrap();
            r.input
                .send(RemoteInput::Key {
                    keysym: k,
                    scancode: None,
                    down: false,
                })
                .unwrap();
        }
        // Typing changes the screen: more frames come.
        let mut more = 0;
        while more < 1 {
            match tokio::time::timeout(Duration::from_secs(20), r.events.recv())
                .await
                .unwrap()
                .unwrap()
            {
                RemoteEvent::Frame => more += 1,
                RemoteEvent::Closed(e) => panic!("closed: {e:?}"),
                _ => {}
            }
        }
        r.input.send(RemoteInput::Close).unwrap();
    });
    eprintln!("VNC OK");
}
