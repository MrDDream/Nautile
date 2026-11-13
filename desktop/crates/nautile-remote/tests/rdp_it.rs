//! Against xrdp in Docker: `scripts/rdp-it.sh` (skipped without `IT_RDP_PORT`).

use std::time::Duration;

use nautile_remote::rdp::{self, RdpParams};
use nautile_remote::{Prompt, RemoteEvent, RemoteInput};

#[test]
fn a_real_rdp_server() {
    let Some(port) = std::env::var("IT_RDP_PORT").ok().and_then(|p| p.parse().ok()) else {
        eprintln!("IT_RDP_PORT not set: skipped");
        return;
    };
    let user = std::env::var("IT_RDP_USER").unwrap_or_else(|_| "ubuntu".into());
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
    rt.block_on(async {
        let mut r = rdp::spawn(RdpParams {
            host: "127.0.0.1".into(),
            port,
            label: "xrdp".into(),
            username: user.clone(),
            password: Some(user),
            size: (1024, 768),
            ..Default::default()
        });
        let mut trusted = false;
        let mut frames = 0;
        while frames < 3 {
            let e = tokio::time::timeout(Duration::from_secs(60), r.events.recv())
                .await
                .expect("event")
                .expect("open");
            match e {
                RemoteEvent::Prompt(Prompt::HostKey { reply, info, .. }) => {
                    assert_eq!(info.key_type, "rdp-x509");
                    reply.send(true);
                }
                RemoteEvent::Trust(_) => trusted = true,
                RemoteEvent::Frame => frames += 1,
                RemoteEvent::Closed(e) => panic!("closed: {e:?}"),
                _ => {}
            }
        }
        assert!(trusted, "certificate trusted once");
        {
            let f = r.frame.lock().unwrap();
            assert_eq!((f.width, f.height), (1024, 768));
            let distinct: std::collections::HashSet<&[u8]> = f.rgba.chunks(4).step_by(97).collect();
            assert!(distinct.len() > 3, "a desktop is drawn ({} colours)", distinct.len());
        }
        r.input
            .send(RemoteInput::Pointer {
                buttons: 0,
                x: 500,
                y: 400,
            })
            .unwrap();
        r.input.send(RemoteInput::Close).unwrap();
    });
    eprintln!("RDP OK");
}
