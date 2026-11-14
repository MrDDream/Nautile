//! RDP through IronRDP: TLS (the server certificate trusted on first use, like Android's `rdp-x509` known
//! hosts), NLA when the server asks for it, the desktop decoded into the shared framebuffer, mouse and keyboard
//! as fast-path input.

use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use ironrdp_connector::connection_activation::{ConnectionActivationFactory, ConnectionActivationState};
use ironrdp_connector::sspi;
use ironrdp_connector::{ClientConnector, Config, Credentials, DesktopSize, Sequence};
use ironrdp_core::WriteBuf;
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

#[derive(Clone, Default)]
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

/// Without the password, so that a `{:?}` never prints it.
impl std::fmt::Debug for RdpParams {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RdpParams")
            .field("host", &self.host)
            .field("port", &self.port)
            .field("label", &self.label)
            .field("username", &self.username)
            .field("password", &self.password.as_ref().map(|_| "***"))
            .field("domain", &self.domain)
            .field("size", &self.size)
            .field("scale", &self.scale)
            .field("known", &self.known.len())
            .finish()
    }
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

/// Accepts any certificate during the handshake: it is checked right after against the known hosts. The
/// handshake signatures are still verified with the certificate's key, so a man in the middle cannot replay
/// the real (public) certificate without holding its private key. Only the key is read from the certificate
/// (not webpki's checks of a CA-issued one): self-made certificates of xrdp and appliances may be X.509 v1,
/// carry unknown critical extensions or an RSA key under 2048 bits.
mod accept_all {
    use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
    use rustls::crypto::WebPkiSupportedAlgorithms;
    use rustls::{CertificateError, DigitallySignedStruct, Error, PeerMisbehaved, SignatureScheme, pki_types};
    use x509_cert::der::{Decode, Encode};

    #[derive(Debug)]
    pub struct AcceptAll {
        algorithms: WebPkiSupportedAlgorithms,
    }

    impl AcceptAll {
        pub fn new(provider: &rustls::crypto::CryptoProvider) -> Self {
            Self {
                algorithms: provider.signature_verification_algorithms,
            }
        }
    }

    const RSA_ENCRYPTION: &str = "1.2.840.113549.1.1.1";

    /// Checks [signature] of [message] with the public key of [cert] alone.
    pub fn verify(
        message: &[u8],
        cert: &[u8],
        scheme: SignatureScheme,
        signature: &[u8],
        algorithms: &WebPkiSupportedAlgorithms,
        tls13: bool,
    ) -> Result<HandshakeSignatureValid, Error> {
        // RFC 8446 4.2.3: no RSA PKCS#1, SHA-1 nor older hashes in TLS 1.3.
        let [hash, sign] = u16::from(scheme).to_be_bytes();
        if tls13 && (hash <= 3 || sign == 1) {
            return Err(PeerMisbehaved::SignedHandshakeWithUnadvertisedSigScheme.into());
        }
        let candidates = algorithms
            .mapping
            .iter()
            .find(|(s, _)| *s == scheme)
            .map(|(_, a)| *a)
            .ok_or(Error::PeerMisbehaved(PeerMisbehaved::SignedHandshakeWithUnadvertisedSigScheme))?;
        let bad = |_| Error::InvalidCertificate(CertificateError::BadEncoding);
        let cert = x509_cert::Certificate::from_der(cert).map_err(bad)?;
        let spki = cert.tbs_certificate().subject_public_key_info();
        let key = spki
            .subject_public_key
            .as_bytes()
            .ok_or(Error::InvalidCertificate(CertificateError::BadEncoding))?;
        // The key's AlgorithmIdentifier without its SEQUENCE header, as webpki gives it.
        let rsa = spki.algorithm.oid.to_string() == RSA_ENCRYPTION;
        let mut alg_id = spki.algorithm.oid.to_der().map_err(bad)?;
        match &spki.algorithm.parameters {
            Some(p) => alg_id.extend(p.to_der().map_err(bad)?),
            None if rsa => alg_id.extend([0x05, 0x00]),
            None => {}
        }
        for alg in candidates.iter().filter(|a| a.public_key_alg_id().as_ref() == alg_id.as_slice()) {
            if alg.verify_signature(key, message, signature).is_ok() {
                return Ok(HandshakeSignatureValid::assertion());
            }
        }
        // ring refuses RSA keys under 2048 bits; PKCS#1 v1.5 has a variant from 1024 bits (TLS 1.2 only).
        let legacy: Option<&'static dyn ring::signature::VerificationAlgorithm> = match scheme {
            SignatureScheme::RSA_PKCS1_SHA256 => Some(&ring::signature::RSA_PKCS1_1024_8192_SHA256_FOR_LEGACY_USE_ONLY),
            SignatureScheme::RSA_PKCS1_SHA512 => Some(&ring::signature::RSA_PKCS1_1024_8192_SHA512_FOR_LEGACY_USE_ONLY),
            _ => None,
        };
        if let Some(alg) = legacy.filter(|_| rsa && !tls13)
            && ring::signature::UnparsedPublicKey::new(alg, key).verify(message, signature).is_ok()
        {
            return Ok(HandshakeSignatureValid::assertion());
        }
        Err(Error::InvalidCertificate(CertificateError::BadSignature))
    }

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
            message: &[u8],
            cert: &pki_types::CertificateDer<'_>,
            dss: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, Error> {
            verify(message, cert, dss.scheme, dss.signature(), &self.algorithms, false)
        }

        fn verify_tls13_signature(
            &self,
            message: &[u8],
            cert: &pki_types::CertificateDer<'_>,
            dss: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, Error> {
            verify(message, cert, dss.scheme, dss.signature(), &self.algorithms, true)
        }

        fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
            self.algorithms.supported_schemes()
        }
    }
}

type Tls = rustls::StreamOwned<rustls::ClientConnection, TcpStream>;

fn tls_upgrade(stream: TcpStream, server: &str) -> Result<(Tls, Vec<u8>, Vec<u8>, String), String> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let verifier = Arc::new(accept_all::AcceptAll::new(&provider));
    let mut config = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .dangerous()
        .with_custom_certificate_verifier(verifier)
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
        .tbs_certificate()
        .subject_public_key_info()
        .subject_public_key
        .as_bytes()
        .ok_or_else(|| tr("Échec de la négociation TLS"))?
        .to_vec();
    let subject = cert.tbs_certificate().subject().to_string();
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

    let mut image = new_image(&frame, result.desktop_size);
    let activation = result.activation_factory;
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
    // What changed since the last frame went out (only that is copied).
    let mut dirty: Option<Rect> = None;
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
                // No clipboard channel: the text is typed (Ctrl+Maj+V).
                Ok(RemoteInput::Clipboard(text)) => ops.extend(typing(&text, |k| db.is_key_pressed(k))),
                Ok(i) => ops.extend(operations(i, &mut buttons)),
                Err(std::sync::mpsc::TryRecvError::Empty) => break,
            }
        }
        if !ops.is_empty() {
            let fast = db.apply(ops);
            let out = stage.process_fastpath_input(&mut image, &fast).map_err(|e| e.to_string())?;
            if write_outputs(&mut framed, out, &mut dirty)? == After::End {
                return Ok(());
            }
        }
        match framed.read_pdu() {
            Ok((action, payload)) => {
                let out = stage
                    .process(&mut image, action, &payload, None)
                    .map_err(|e| describe(&e.to_string()))?;
                match write_outputs(&mut framed, out, &mut dirty)? {
                    After::End => return Ok(()),
                    After::Reactivate => {
                        // Waits for the whole sequence (not the short reads of the session).
                        raw.set_read_timeout(Some(Duration::from_secs(20))).map_err(|e| e.to_string())?;
                        let size = reactivate(&mut framed, &mut stage, &activation).map_err(|e| {
                            trf(
                                "Le serveur a réinitialisé l'affichage (changement de résolution…) et la session n'a pas pu reprendre : %1$s",
                                &[&e],
                            )
                        })?;
                        raw.set_read_timeout(Some(Duration::from_millis(15))).map_err(|e| e.to_string())?;
                        image = new_image(&frame, size);
                        db = Database::new();
                        buttons = 0;
                        dirty = None;
                        let _ = events.send(RemoteEvent::Frame);
                    }
                    After::Go => {}
                }
            }
            Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(()),
            Err(e) => return Err(describe(&e.to_string())),
        }
        // The picture goes out at most ~40 times a second.
        if last_frame.elapsed() >= Duration::from_millis(25)
            && let Some(r) = dirty.take()
        {
            last_frame = Instant::now();
            let mut f = frame.lock().unwrap_or_else(|e| e.into_inner());
            copy_rect(image.data(), &mut f.rgba, image.width() as usize, image.height() as usize, r);
            drop(f);
            if events.send(RemoteEvent::Frame).is_err() {
                return Ok(());
            }
        }
    }
}

/// A picture of [size], and the shared framebuffer made the same size.
fn new_image(frame: &SharedFrame, size: DesktopSize) -> DecodedImage {
    let (w, h) = (size.width, size.height);
    let mut f = frame.lock().unwrap_or_else(|e| e.into_inner());
    *f = Framebuffer {
        width: w as u32,
        height: h as u32,
        rgba: vec![0; w as usize * h as usize * 4],
        // Kept between frames: only the parts that changed are copied into it.
        whole: false,
    };
    DecodedImage::new(PixelFormat::RgbA32, w, h)
}

/// The changed part of the picture, inclusive (left, top, right, bottom).
type Rect = (u16, u16, u16, u16);

fn union(a: Option<Rect>, b: Rect) -> Rect {
    match a {
        None => b,
        Some(a) => (a.0.min(b.0), a.1.min(b.1), a.2.max(b.2), a.3.max(b.3)),
    }
}

/// Copies [r] of the RGBA picture [src] ([width] × [height]) into [dst], of the same size (clipped to it).
fn copy_rect(src: &[u8], dst: &mut [u8], width: usize, height: usize, r: Rect) {
    if width == 0 || height == 0 || src.len() < width * height * 4 || dst.len() != src.len() {
        return;
    }
    let (left, top) = (usize::from(r.0).min(width - 1), usize::from(r.1).min(height - 1));
    let (right, bottom) = (usize::from(r.2).min(width - 1), usize::from(r.3).min(height - 1));
    if right < left || bottom < top {
        return;
    }
    for y in top..=bottom {
        let (a, b) = ((y * width + left) * 4, (y * width + right + 1) * 4);
        dst[a..b].copy_from_slice(&src[a..b]);
    }
}

/// What the session does after the server's frames.
#[derive(Debug, PartialEq, Eq)]
enum After {
    Go,
    End,
    /// The server sent « Deactivate All »: the Deactivation-Reactivation Sequence follows.
    Reactivate,
}

/// Sends the frames to write and notes what changed.
fn write_outputs<S: Read + Write>(
    framed: &mut ironrdp_blocking::Framed<S>,
    out: Vec<ActiveStageOutput>,
    dirty: &mut Option<Rect>,
) -> Result<After, String> {
    for o in out {
        match o {
            ActiveStageOutput::ResponseFrame(f) => framed.write_all(&f).map_err(|e| e.to_string())?,
            ActiveStageOutput::GraphicsUpdate(r) => *dirty = Some(union(*dirty, (r.left, r.top, r.right, r.bottom))),
            ActiveStageOutput::Terminate(_) => return Ok(After::End),
            ActiveStageOutput::DeactivateAll => return Ok(After::Reactivate),
            _ => {}
        }
    }
    Ok(After::Go)
}

/// The Deactivation-Reactivation Sequence ([MS-RDPBCGR] 1.3.1.3): the capabilities are exchanged again (a new
/// resolution, a session moved to the console…), then the session goes on at the size the server gives.
fn reactivate<S: Read + Write>(
    framed: &mut ironrdp_blocking::Framed<S>,
    stage: &mut ActiveStage,
    factory: &ConnectionActivationFactory,
) -> Result<DesktopSize, String> {
    let mut seq = factory.create();
    let mut buf = WriteBuf::new();
    loop {
        match seq.connection_activation_state() {
            ConnectionActivationState::Finalized {
                desktop_size,
                share_id,
                static_channel_chunk_size,
                enable_server_pointer,
                pointer_software_rendering,
                ..
            } => {
                let (io, user) = (seq.io_channel_id(), seq.user_channel_id());
                if !stage.reactivate(
                    io,
                    user,
                    share_id,
                    enable_server_pointer,
                    pointer_software_rendering,
                    static_channel_chunk_size,
                ) {
                    return Err(tr("paramètres refusés"));
                }
                return Ok(desktop_size);
            }
            ConnectionActivationState::Redirected { .. } => return Err(tr("le serveur redirige la session, reconnectez-vous")),
            _ => {}
        }
        buf.clear();
        let pdu = match seq.next_pdu_hint() {
            Some(hint) => Some(framed.read_by_hint(hint).map_err(|e| e.to_string())?),
            None => None,
        };
        let written = match pdu {
            Some(pdu) => seq.step(&pdu, framed.last_read_at(), &mut buf),
            None => seq.step_no_input(&mut buf),
        }
        .map_err(|e| e.to_string())?;
        if let Some(n) = written.size() {
            framed.write_all(&buf[..n]).map_err(|e| e.to_string())?;
        }
    }
}

/// [text] typed as Unicode key events, Ctrl and Shift (held for the shortcut) released first.
fn typing(text: &str, held: impl Fn(Scancode) -> bool) -> Vec<Operation> {
    // Left/right Ctrl, left/right Shift.
    let mut ops: Vec<Operation> = [0x1D, 0xE01D, 0x2A, 0x36]
        .into_iter()
        .map(Scancode::from_u16)
        .filter(|k| held(*k))
        .map(Operation::KeyReleased)
        .collect();
    // A pasted novel is not typed out whole.
    for c in text.chars().filter(|c| *c != '\r').take(20_000) {
        match c {
            '\n' | '\t' => {
                let key = Scancode::from_u16(if c == '\n' { 0x1C } else { 0x0F });
                ops.extend([Operation::KeyPressed(key), Operation::KeyReleased(key)]);
            }
            c if c.is_control() => {}
            c => ops.extend([Operation::UnicodeKeyPressed(c), Operation::UnicodeKeyReleased(c)]),
        }
    }
    ops
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
    fn handshake_signatures_are_checked_with_the_key_of_any_certificate() {
        use rustls::SignatureScheme as S;
        let algorithms = rustls::crypto::ring::default_provider().signature_verification_algorithms;
        let message = b"TLS handshake transcript";
        // Made with OpenSSL: X.509 v1 (ECDSA P-256, RSA 1024), and v3 with an unknown critical extension.
        let cases: [(&[u8], &[u8], S, bool); 4] = [
            (
                include_bytes!("../tests/fixtures/v1-ec.der"),
                include_bytes!("../tests/fixtures/v1-ec.sig"),
                S::ECDSA_NISTP256_SHA256,
                true,
            ),
            (
                include_bytes!("../tests/fixtures/v1-ec.der"),
                include_bytes!("../tests/fixtures/v1-ec.sig"),
                S::ECDSA_NISTP256_SHA256,
                false,
            ),
            (
                include_bytes!("../tests/fixtures/v1-rsa1024.der"),
                include_bytes!("../tests/fixtures/v1-rsa1024.sig"),
                S::RSA_PKCS1_SHA256,
                false,
            ),
            (
                include_bytes!("../tests/fixtures/critical-extension.der"),
                include_bytes!("../tests/fixtures/critical-extension.sig"),
                S::RSA_PSS_SHA256,
                true,
            ),
        ];
        for (cert, sig, scheme, tls13) in cases {
            assert!(
                accept_all::verify(message, cert, scheme, sig, &algorithms, tls13).is_ok(),
                "{scheme:?}"
            );
            // Another message, or an altered signature: refused.
            assert!(accept_all::verify(b"forged transcript", cert, scheme, sig, &algorithms, tls13).is_err());
            let mut altered = sig.to_vec();
            altered[sig.len() / 2] ^= 1;
            assert!(accept_all::verify(message, cert, scheme, &altered, &algorithms, tls13).is_err());
        }
        // An RSA key cannot sign as ECDSA, nor TLS 1.3 take PKCS#1.
        let rsa: &[u8] = include_bytes!("../tests/fixtures/v1-rsa1024.der");
        let sig: &[u8] = include_bytes!("../tests/fixtures/v1-rsa1024.sig");
        assert!(accept_all::verify(message, rsa, S::ECDSA_NISTP256_SHA256, sig, &algorithms, false).is_err());
        assert!(accept_all::verify(message, rsa, S::RSA_PKCS1_SHA256, sig, &algorithms, true).is_err());
    }

    #[test]
    fn fingerprints_look_like_android_ones() {
        let fp = fingerprint(b"cert");
        assert_eq!(fp.len(), 32 * 3 - 1);
        assert!(fp.chars().all(|c| c == ':' || c.is_ascii_hexdigit() && !c.is_ascii_lowercase()));
    }

    #[test]
    fn only_the_changed_part_is_copied() {
        let (w, h) = (4, 3);
        let src: Vec<u8> = (0..w * h * 4).map(|i| i as u8).collect();
        let mut dst = vec![0u8; w * h * 4];
        let r = union(Some((1, 1, 1, 1)), (2, 1, 2, 2));
        assert_eq!(r, (1, 1, 2, 2));
        copy_rect(&src, &mut dst, w, h, r);
        for y in 0..h {
            for x in 0..w {
                let i = (y * w + x) * 4;
                let inside = (1..=2).contains(&x) && (1..=2).contains(&y);
                assert_eq!(dst[i..i + 4] == src[i..i + 4], inside, "{x},{y}");
            }
        }
        // Outside the picture (a size just changed): clipped, no panic.
        copy_rect(&src, &mut dst, w, h, (2, 0, 400, 300));
        assert_eq!(dst[(w * h - 1) * 4..], src[(w * h - 1) * 4..]);
    }

    #[test]
    fn pasted_text_is_typed_without_the_shortcut_keys() {
        let ctrl = Scancode::from_u16(0x1D);
        let ops = typing("é\r\nb", |k| k == ctrl);
        assert!(matches!(
            ops[..],
            [
                Operation::KeyReleased(k),
                Operation::UnicodeKeyPressed('é'),
                Operation::UnicodeKeyReleased('é'),
                Operation::KeyPressed(enter),
                Operation::KeyReleased(_),
                Operation::UnicodeKeyPressed('b'),
                Operation::UnicodeKeyReleased('b'),
            ] if k == ctrl && enter == Scancode::from_u16(0x1C)
        ));
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
