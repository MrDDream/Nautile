//! RDP through IronRDP: TLS (the server certificate trusted on first use, like Android's `rdp-x509` known
//! hosts), NLA when the server asks for it, the desktop decoded into the shared framebuffer, mouse and keyboard
//! as fast-path input.

use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use ironrdp_connector::sspi;
use ironrdp_connector::{ClientConnector, Config, Credentials, DesktopSize};
use ironrdp_graphics::image_processing::PixelFormat;
use ironrdp_input::{Database, MouseButton, MousePosition, Operation, Scancode, WheelRotations};
use ironrdp_pdu::gcc::{ConnectionType, KeyboardType};
use ironrdp_pdu::rdp::capability_sets::{MajorPlatformType, RailSupportLevel};
use ironrdp_pdu::rdp::client_info::{PerformanceFlags, TimezoneInfo};
use ironrdp_session::image::DecodedImage;
use ironrdp_session::{ActiveStage, ActiveStageBuilder, ActiveStageOutput};
use nautile_core::{tr, trf};
use nautile_net::session::Reply;
use nautile_net::{HostKeyInfo, KnownKey, Prompt};
use sha2::Digest;
use tokio::sync::mpsc::UnboundedSender;

use crate::{Framebuffer, Remote, RemoteEvent, RemoteInput, SharedFrame};

/// keyType of RDP server certificates in the known hosts (same as Android).
pub const CERT_TYPE: &str = "rdp-x509";

#[derive(Debug, Clone, Default)]
pub struct RdpParams {
    pub host: String,
    pub port: u16,
    pub label: String,
    pub username: String,
    pub password: Option<String>,
    pub domain: Option<String>,
    /// Width × height; the server may lower it.
    pub size: (u16, u16),
    /// Windows' scale in percent (100 = none).
    pub scale: Option<u32>,
    /// Certificates trusted earlier for host:port.
    pub known: Vec<KnownKey>,
}

/// SHA-256 of the certificate as `AA:BB:…` (Android's format).
pub fn fingerprint(der: &[u8]) -> String {
    sha2::Sha256::digest(der)
        .iter()
        .map(|b| format!("{b:02X}"))
        .collect::<Vec<_>>()
        .join(":")
}

/// Turns the connector's errors into words (Android's `RdpManager.describe`).
fn describe(e: &str) -> String {
    let l = e.to_lowercase();
    if l.contains("logon") || l.contains("authentication") || l.contains("credssp") || l.contains("access denied") {
        tr("Identifiants refusés par le serveur")
    } else if l.contains("password") && (l.contains("expired") || l.contains("must change")) {
        tr("Mot de passe expiré : changez-le depuis Windows")
    } else if l.contains("refused") || l.contains("unreachable") || l.contains("timed out") {
        tr("Serveur injoignable (port fermé ou bureau à distance désactivé)")
    } else if l.contains("tls") || l.contains("certificate") {
        tr("Échec de la négociation TLS")
    } else {
        e.to_owned()
    }
}

/// No Kerberos: NLA uses NTLM with the username and password.
struct NoNetwork;

impl sspi::network_client::NetworkClient for NoNetwork {
    fn send(&self, _: &sspi::generator::NetworkRequest) -> sspi::Result<Vec<u8>> {
        Err(sspi::Error::new(
            sspi::ErrorKind::NoAuthenticatingAuthority,
            "Kerberos is not supported",
        ))
    }
}

/// Accepts any certificate during the handshake: it is checked right after against the known hosts.
mod accept_all {
    use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
    use rustls::{pki_types, DigitallySignedStruct, Error, SignatureScheme};

    #[derive(Debug)]
    pub struct AcceptAll;

    impl ServerCertVerifier for AcceptAll {
        fn verify_server_cert(
            &self,
            _: &pki_types::CertificateDer<'_>,
            _: &[pki_types::CertificateDer<'_>],
            _: &pki_types::ServerName<'_>,
            _: &[u8],
            _: pki_types::UnixTime,
        ) -> Result<ServerCertVerified, Error> {
            Ok(ServerCertVerified::assertion())
        }

        fn verify_tls12_signature(
            &self,
            _: &[u8],
            _: &pki_types::CertificateDer<'_>,
            _: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, Error> {
            Ok(HandshakeSignatureValid::assertion())
        }

        fn verify_tls13_signature(
            &self,
            _: &[u8],
            _: &pki_types::CertificateDer<'_>,
            _: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, Error> {
            Ok(HandshakeSignatureValid::assertion())
        }

        fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
            rustls::crypto::ring::default_provider()
                .signature_verification_algorithms
                .supported_schemes()
        }
    }
}

type Tls = rustls::StreamOwned<rustls::ClientConnection, TcpStream>;

fn tls_upgrade(stream: TcpStream, server: &str) -> Result<(Tls, Vec<u8>, Vec<u8>, String), String> {
    let mut config = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(accept_all::AcceptAll))
        .with_no_client_auth();
    // CredSSP needs the TLS session as it is.
    config.resumption = rustls::client::Resumption::disabled();
    let name = rustls::pki_types::ServerName::try_from(server.to_owned()).map_err(|e| e.to_string())?;
    let conn = rustls::ClientConnection::new(Arc::new(config), name).map_err(|e| e.to_string())?;
    let mut tls = rustls::StreamOwned::new(conn, stream);
    tls.flush().map_err(|e| e.to_string())?;
    let der = tls
        .conn
        .peer_certificates()
        .and_then(|c| c.first())
        .map(|c| c.as_ref().to_vec())
        .ok_or_else(|| tr("Échec de la négociation TLS"))?;
    use x509_cert::der::Decode;
    let cert = x509_cert::Certificate::from_der(&der).map_err(|e| e.to_string())?;
    let key = cert
        .tbs_certificate
        .subject_public_key_info
        .subject_public_key
        .as_bytes()
        .ok_or_else(|| tr("Échec de la négociation TLS"))?
        .to_vec();
    let subject = cert.tbs_certificate.subject.to_string();
    Ok((tls, key, der, subject))
}

fn config(p: &RdpParams, password: String) -> Config {
    Config {
        desktop_size: DesktopSize {
            width: p.size.0.max(640),
            height: p.size.1.max(480),
        },
        monitor_layout: None,
        desktop_scale_factor: p.scale.unwrap_or(100),
        desktop_physical_size: None,
        desktop_orientation: None,
        device_scale_factor: None,
        monitor_extended_layout: None,
        relative_mouse_input: false,
        enable_tls: true,
        enable_credssp: true,
        enable_standard_rdp_security: false,
        credentials: Credentials::UsernamePassword {
            username: p.username.clone(),
            password,
        },
        domain: p.domain.clone().filter(|d| !d.is_empty()),
        client_build: 0,
        client_name: "Nautile".into(),
        keyboard_type: KeyboardType::IBM_ENHANCED,
        keyboard_subtype: 0,
        keyboard_functional_keys_count: 12,
        keyboard_layout: 0,
        connection_type: ConnectionType::Lan,
        ime_file_name: String::new(),
        bitmap: None,
        dig_product_id: String::new(),
        client_dir: "C:\\Windows\\System32\\mstscax.dll".into(),
        alternate_shell: String::new(),
        work_dir: String::new(),
        remote_application_mode: false,
        rail_support_level: RailSupportLevel::empty(),
        platform: if cfg!(windows) {
            MajorPlatformType::WINDOWS
        } else if cfg!(target_os = "macos") {
            MajorPlatformType::MACINTOSH
        } else {
            MajorPlatformType::UNIX
        },
        hardware_id: None,
        request_data: None,
        autologon: false,
        enable_audio_playback: false,
        enable_audio_capture: false,
        performance_flags: PerformanceFlags::default(),
        license_cache: None,
        timezone_info: TimezoneInfo::default(),
        compression_type: None,
        enable_server_pointer: true,
        pointer_software_rendering: true,
        multitransport_flags: None,
        support_dyn_vc_gfx_protocol: false,
    }
}

pub fn spawn(p: RdpParams) -> Remote {
    let (ev_tx, ev_rx) = tokio::sync::mpsc::unbounded_channel();
    let (in_tx, in_rx) = std::sync::mpsc::channel::<RemoteInput>();
    let frame: SharedFrame = Arc::new(Mutex::new(Framebuffer::default()));
    let f = frame.clone();
    std::thread::Builder::new()
        .name("rdp".into())
        .spawn(move || {
            let reason = run(p, f, &ev_tx, in_rx).err();
            let _ = ev_tx.send(RemoteEvent::Closed(reason));
        })
        .ok();
    Remote {
        input: in_tx,
        events: ev_rx,
        frame,
    }
}

fn ask<T>(events: &UnboundedSender<RemoteEvent>, make: impl FnOnce(Reply<T>) -> Prompt) -> Option<T> {
    let (reply, rx) = Reply::new();
    events.send(RemoteEvent::Prompt(make(reply))).ok()?;
    rx.blocking_recv().ok()
}

fn run(
    p: RdpParams,
    frame: SharedFrame,
    events: &UnboundedSender<RemoteEvent>,
    input: std::sync::mpsc::Receiver<RemoteInput>,
) -> Result<(), String> {
    let _ = events.send(RemoteEvent::Status(trf("Connexion à %1$s…", &[&p.label])));
    let password = match p.password.clone().filter(|x| !x.is_empty()) {
        Some(pw) => pw,
        None => ask(events, |reply| Prompt::Secret {
            label: p.label.clone(),
            title: tr("Mot de passe"),
            detail: format!("{}@{}", p.username, p.host),
            reply,
        })
        .flatten()
        .ok_or_else(|| tr("Connexion annulée"))?,
    };
    let addr: SocketAddr = (p.host.as_str(), p.port)
        .to_socket_addrs()
        .map_err(|e| e.to_string())?
        .next()
        .ok_or_else(|| trf("Adresse introuvable : %1$s", &[&p.host]))?;
    let tcp = TcpStream::connect_timeout(&addr, Duration::from_secs(15)).map_err(|e| describe(&e.to_string()))?;
    tcp.set_read_timeout(Some(Duration::from_secs(20))).map_err(|e| e.to_string())?;
    let _ = tcp.set_nodelay(true);
    let raw = tcp.try_clone().map_err(|e| e.to_string())?;
    let client_addr = tcp.local_addr().map_err(|e| e.to_string())?;

    let mut framed = ironrdp_blocking::Framed::new(tcp);
    let mut connector = ClientConnector::new(config(&p, password), client_addr);
    let should_upgrade = ironrdp_blocking::connect_begin(&mut framed, &mut connector).map_err(|e| describe(&e.to_string()))?;
    let (tls, server_key, der, subject) = tls_upgrade(framed.into_inner_no_leftover(), &p.host)?;

    // The certificate: trusted on first use, the user warned when it changed.
    let fp = fingerprint(&der);
    let previous = p.known.iter().find(|k| k.key_type == CERT_TYPE).cloned();
    if previous.as_ref().map(|k| k.fingerprint.as_str()) != Some(fp.as_str()) {
        let info = HostKeyInfo {
            host: p.host.clone(),
            port: p.port,
            key_type: CERT_TYPE.into(),
            public_key: subject,
            fingerprint: fp,
        };
        let ok = ask(events, |reply| Prompt::HostKey {
            label: p.label.clone(),
            info: info.clone(),
            previous: previous.clone(),
            reply,
        })
        .unwrap_or(false);
        if !ok {
            return Err(tr("Connexion annulée"));
        }
        let _ = events.send(RemoteEvent::Trust(info));
    }

    let upgraded = ironrdp_blocking::mark_as_upgraded(should_upgrade, &mut connector);
    let mut framed = ironrdp_blocking::Framed::new(tls);
    let outcome = ironrdp_blocking::connect_finalize(
        upgraded,
        connector,
        &mut framed,
        &mut NoNetwork,
        p.host.clone().into(),
        server_key,
        None,
    )
    .map_err(|e| describe(&format!("{e:#}")))?;
    let result = outcome.into_connected().map_err(|e| describe(&e.to_string()))?;

    let (w, h) = (result.desktop_size.width, result.desktop_size.height);
    let mut image = DecodedImage::new(PixelFormat::RgbA32, w, h);
    {
        let mut f = frame.lock().unwrap_or_else(|e| e.into_inner());
        *f = Framebuffer {
            width: w as u32,
            height: h as u32,
            rgba: vec![0; w as usize * h as usize * 4],
        };
    }
    let mut stage: ActiveStage = ActiveStageBuilder {
        static_channels: result.static_channels,
        user_channel_id: result.user_channel_id,
        io_channel_id: result.io_channel_id,
        message_channel_id: result.message_channel_id,
        share_id: result.share_id,
        compression_type: result.compression_type,
        enable_server_pointer: result.enable_server_pointer,
        pointer_software_rendering: result.pointer_software_rendering,
    }
    .build();
    let _ = events.send(RemoteEvent::Ready(p.host.clone()));
    // Short reads from now on, so that input goes out between frames.
    raw.set_read_timeout(Some(Duration::from_millis(15))).map_err(|e| e.to_string())?;

    let mut db = Database::new();
    let mut buttons = 0u8;
    let mut dirty = false;
    let mut last_frame = Instant::now();
    loop {
        // Input first.
        let mut ops = Vec::new();
        loop {
            match input.try_recv() {
                Ok(RemoteInput::Close) | Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    if let Ok(out) = stage.graceful_shutdown() {
                        let _ = write_outputs(&mut framed, out, &mut dirty);
                    }
                    return Ok(());
                }
                Ok(i) => ops.extend(operations(i, &mut buttons)),
                Err(std::sync::mpsc::TryRecvError::Empty) => break,
            }
        }
        if !ops.is_empty() {
            let fast = db.apply(ops);
            let out = stage.process_fastpath_input(&mut image, &fast).map_err(|e| e.to_string())?;
            if write_outputs(&mut framed, out, &mut dirty)? {
                return Ok(());
            }
        }
        match framed.read_pdu() {
            Ok((action, payload)) => {
                let out = stage
                    .process(&mut image, action, &payload, None)
                    .map_err(|e| describe(&e.to_string()))?;
                if write_outputs(&mut framed, out, &mut dirty)? {
                    return Ok(());
                }
            }
            Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(()),
            Err(e) => return Err(describe(&e.to_string())),
        }
        // The picture goes out at most ~40 times a second.
        if dirty && last_frame.elapsed() >= Duration::from_millis(25) {
            dirty = false;
            last_frame = Instant::now();
            let mut f = frame.lock().unwrap_or_else(|e| e.into_inner());
            f.width = image.width() as u32;
            f.height = image.height() as u32;
            f.rgba.clear();
            f.rgba.extend_from_slice(image.data());
            drop(f);
            if events.send(RemoteEvent::Frame).is_err() {
                return Ok(());
            }
        }
    }
}

/// Sends the frames to write; true when the server ended the session.
fn write_outputs<S: Read + Write>(
    framed: &mut ironrdp_blocking::Framed<S>,
    out: Vec<ActiveStageOutput>,
    dirty: &mut bool,
) -> Result<bool, String> {
    for o in out {
        match o {
            ActiveStageOutput::ResponseFrame(f) => framed.write_all(&f).map_err(|e| e.to_string())?,
            ActiveStageOutput::GraphicsUpdate(_) => *dirty = true,
            ActiveStageOutput::Terminate(_) => return Ok(true),
            _ => {}
        }
    }
    Ok(false)
}

/// The VNC-style input (buttons mask, keysyms) as RDP operations.
fn operations(i: RemoteInput, buttons: &mut u8) -> Vec<Operation> {
    let mut ops = Vec::new();
    match i {
        RemoteInput::Pointer { buttons: b, x, y } => {
            ops.push(Operation::MouseMove(MousePosition { x, y }));
            for (bit, button) in [(1, MouseButton::Left), (2, MouseButton::Middle), (4, MouseButton::Right)] {
                let (was, now) = (*buttons & bit != 0, b & bit != 0);
                if now && !was {
                    ops.push(Operation::MouseButtonPressed(button));
                } else if was && !now {
                    ops.push(Operation::MouseButtonReleased(button));
                }
            }
            // Wheel: a press of « button 4/5 » is one notch.
            if b & 8 != 0 && *buttons & 8 == 0 {
                ops.push(Operation::WheelRotations(WheelRotations {
                    is_vertical: true,
                    rotation_units: 120,
                }));
            }
            if b & 16 != 0 && *buttons & 16 == 0 {
                ops.push(Operation::WheelRotations(WheelRotations {
                    is_vertical: true,
                    rotation_units: -120,
                }));
            }
            *buttons = b;
        }
        RemoteInput::Key { keysym, scancode, down } => match (
            scancode,
            char::from_u32(if keysym >= 0x0100_0000 { keysym - 0x0100_0000 } else { keysym }),
        ) {
            (Some(code), _) => ops.push(if down {
                Operation::KeyPressed(Scancode::from_u16(code))
            } else {
                Operation::KeyReleased(Scancode::from_u16(code))
            }),
            (None, Some(c)) if !(0xFF00..0x0100_0000).contains(&keysym) => ops.push(if down {
                Operation::UnicodeKeyPressed(c)
            } else {
                Operation::UnicodeKeyReleased(c)
            }),
            _ => {}
        },
        RemoteInput::Clipboard(_) | RemoteInput::Close => {}
    }
    ops
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fingerprints_look_like_android_ones() {
        let fp = fingerprint(b"cert");
        assert_eq!(fp.len(), 32 * 3 - 1);
        assert!(fp.chars().all(|c| c == ':' || c.is_ascii_hexdigit() && !c.is_ascii_lowercase()));
    }

    #[test]
    fn mouse_buttons_become_presses_and_releases() {
        let mut b = 0;
        let ops = operations(RemoteInput::Pointer { buttons: 1, x: 5, y: 6 }, &mut b);
        assert!(matches!(
            ops[..],
            [Operation::MouseMove(_), Operation::MouseButtonPressed(MouseButton::Left)]
        ));
        let ops = operations(RemoteInput::Pointer { buttons: 0, x: 5, y: 6 }, &mut b);
        assert!(matches!(
            ops[..],
            [Operation::MouseMove(_), Operation::MouseButtonReleased(MouseButton::Left)]
        ));
        let ops = operations(RemoteInput::Pointer { buttons: 8, x: 5, y: 6 }, &mut b);
        assert!(matches!(
            ops[..],
            [
                Operation::MouseMove(_),
                Operation::WheelRotations(WheelRotations { rotation_units: 120, .. })
            ]
        ));
        let ops = operations(
            RemoteInput::Key {
                keysym: 'é' as u32,
                scancode: None,
                down: true,
            },
            &mut b,
        );
        assert!(matches!(ops[..], [Operation::UnicodeKeyPressed('é')]));
    }
}
