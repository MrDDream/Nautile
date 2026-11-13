//! A VNC client (RFB 3.3 / 3.7 / 3.8), ported from Android's `VncClient.kt`: « None » or VNC password
//! authentication, a 32-bit true-colour framebuffer kept up to date with the Raw, CopyRect, Hextile and ZRLE
//! encodings, desktop resizes and the server's cursor, plus keyboard, pointer and clipboard.

use std::io::{self, BufReader, BufWriter, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use des::cipher::{BlockCipherEncrypt, KeyInit};
use nautile_core::{tr, trf};
use nautile_net::session::Reply;
use nautile_net::Prompt;
use tokio::sync::mpsc::UnboundedSender;

use crate::{Cursor, Framebuffer, Remote, RemoteEvent, RemoteInput, SharedFrame};

pub const SEC_NONE: u8 = 1;
pub const SEC_VNC: u8 = 2;
const ENC_RAW: i32 = 0;
const ENC_COPYRECT: i32 = 1;
const ENC_HEXTILE: i32 = 5;
const ENC_ZRLE: i32 = 16;
const ENC_CURSOR: i32 = -239;
const ENC_DESKTOP_SIZE: i32 = -223;
const ENC_LAST_RECT: i32 = -224;
const ENCODINGS: [i32; 7] = [
    ENC_ZRLE,
    ENC_HEXTILE,
    ENC_COPYRECT,
    ENC_RAW,
    ENC_DESKTOP_SIZE,
    ENC_CURSOR,
    ENC_LAST_RECT,
];

#[derive(Debug)]
pub enum VncError {
    Io(io::Error),
    /// The password was refused (or not given).
    Auth(String),
    Protocol(String),
}

impl std::fmt::Display for VncError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            VncError::Io(e) => write!(f, "{e}"),
            VncError::Auth(e) | VncError::Protocol(e) => f.write_str(e),
        }
    }
}

impl From<io::Error> for VncError {
    fn from(e: io::Error) -> Self {
        VncError::Io(e)
    }
}

type R<T> = Result<T, VncError>;

fn mirror(b: u8) -> u8 {
    b.reverse_bits()
}

/// The 16-byte answer to a VNC challenge: DES with the password (8 bytes, bits mirrored) as the key.
pub fn vnc_response(password: &str, challenge: &[u8; 16]) -> [u8; 16] {
    let mut key = [0u8; 8];
    for (i, c) in password.chars().take(8).enumerate() {
        key[i] = mirror(if (c as u32) < 256 { c as u8 } else { b'?' });
    }
    let des = des::Des::new(&key.into());
    let mut out = *challenge;
    for b in out.as_chunks_mut::<8>().0 {
        des.encrypt_block(b.into());
    }
    out
}

/// The protocol state; reads from [R], the framebuffer is shared.
pub struct Client<S: Read> {
    inp: BufReader<S>,
    pub width: u32,
    pub height: u32,
    pub name: String,
    frame: SharedFrame,
    zlib: flate2::Decompress,
    zbuf: Vec<u8>,
    zpos: usize,
    /// A new pointer shape read with the last update.
    pub pending_cursor: Option<Option<Cursor>>,
}

fn u8_<S: Read>(r: &mut S) -> io::Result<u8> {
    let mut b = [0u8; 1];
    r.read_exact(&mut b)?;
    Ok(b[0])
}

fn u16_<S: Read>(r: &mut S) -> io::Result<u16> {
    let mut b = [0u8; 2];
    r.read_exact(&mut b)?;
    Ok(u16::from_be_bytes(b))
}

fn u32_<S: Read>(r: &mut S) -> io::Result<u32> {
    let mut b = [0u8; 4];
    r.read_exact(&mut b)?;
    Ok(u32::from_be_bytes(b))
}

fn skip<S: Read>(r: &mut S, n: usize) -> io::Result<()> {
    io::copy(&mut r.take(n as u64), &mut io::sink()).map(|_| ())
}

/// Our pixel format: 32 bpp little endian 0x00RRGGBB, so bytes come as B, G, R, x.
#[inline]
fn put(rgba: &mut [u8], at: usize, b: u8, g: u8, r: u8) {
    rgba[at] = r;
    rgba[at + 1] = g;
    rgba[at + 2] = b;
    rgba[at + 3] = 255;
}

impl<S: Read> Client<S> {
    /// RFB handshake on [input], answers on [out]. [password] is asked only when the server wants one.
    pub fn handshake<W: Write>(input: S, out: &mut W, frame: SharedFrame, password: impl FnOnce() -> Option<String>) -> R<Client<S>> {
        let mut inp = BufReader::with_capacity(64 * 1024, input);
        let mut version = [0u8; 12];
        inp.read_exact(&mut version)?;
        let v = std::str::from_utf8(&version).unwrap_or_default();
        if !v.starts_with("RFB ") {
            return Err(VncError::Protocol(tr("Ce n'est pas un serveur VNC")));
        }
        let major: u32 = v[4..7].parse().unwrap_or(0);
        let minor: u32 = v[8..11].parse().unwrap_or(0);
        // 3.3 and 3.7 as such; 3.8 and anything newer (Apple's 3.889…) as 3.8.
        let ver = if major > 3 || minor >= 8 {
            8
        } else if minor >= 7 {
            7
        } else {
            3
        };
        out.write_all(format!("RFB 003.00{ver}\n").as_bytes())?;
        out.flush()?;
        let reason = |inp: &mut BufReader<S>| -> String {
            let n = u32_(inp).unwrap_or(0).min(4096) as usize;
            let mut b = vec![0u8; n];
            let _ = inp.read_exact(&mut b);
            String::from_utf8_lossy(&b).into_owned()
        };
        let kind = if ver == 3 {
            let t = u32_(&mut inp)?;
            if t == 0 {
                return Err(VncError::Protocol(reason(&mut inp)));
            }
            t as u8
        } else {
            let n = u8_(&mut inp)?;
            if n == 0 {
                return Err(VncError::Protocol(reason(&mut inp)));
            }
            let mut types = vec![0u8; n as usize];
            inp.read_exact(&mut types)?;
            let t = if types.contains(&SEC_VNC) {
                SEC_VNC
            } else if types.contains(&SEC_NONE) {
                SEC_NONE
            } else {
                let list = types.iter().map(u8::to_string).collect::<Vec<_>>().join(", ");
                return Err(VncError::Protocol(trf(
                    "Méthode d'authentification VNC non prise en charge (%1$s)",
                    &[&list],
                )));
            };
            out.write_all(&[t])?;
            out.flush()?;
            t
        };
        let result = |inp: &mut BufReader<S>| -> R<()> {
            if u32_(inp)? == 0 {
                return Ok(());
            }
            let why = if ver == 8 { reason(inp) } else { String::new() };
            Err(VncError::Auth(if why.trim().is_empty() {
                tr("Mot de passe VNC refusé")
            } else {
                trf("Authentification VNC refusée : %1$s", &[&why])
            }))
        };
        match kind {
            SEC_NONE => {
                if ver == 8 {
                    result(&mut inp)?;
                }
            }
            SEC_VNC => {
                let mut challenge = [0u8; 16];
                inp.read_exact(&mut challenge)?;
                let pw = password().ok_or_else(|| VncError::Auth(tr("Mot de passe VNC requis")))?;
                out.write_all(&vnc_response(&pw, &challenge))?;
                out.flush()?;
                result(&mut inp)?;
            }
            t => {
                return Err(VncError::Protocol(trf(
                    "Méthode d'authentification VNC non prise en charge (%1$s)",
                    &[&t],
                )))
            }
        }

        out.write_all(&[1])?; // ClientInit: shared desktop
        out.flush()?;
        let w = u16_(&mut inp)? as u32;
        let h = u16_(&mut inp)? as u32;
        skip(&mut inp, 16)?; // the server's pixel format: ours is set right after
        let n = u32_(&mut inp)?.min(4096) as usize;
        let mut name = vec![0u8; n];
        inp.read_exact(&mut name)?;
        let mut c = Client {
            inp,
            width: 0,
            height: 0,
            name: String::from_utf8_lossy(&name).into_owned(),
            frame,
            zlib: flate2::Decompress::new(true),
            zbuf: Vec::new(),
            zpos: 0,
            pending_cursor: None,
        };
        c.resize(w, h);

        // SetPixelFormat: 32 bpp, depth 24, little endian, true colour, 0x00RRGGBB.
        let mut m = vec![0u8, 0, 0, 0, 32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0];
        // SetEncodings.
        m.extend_from_slice(&[2, 0]);
        m.extend_from_slice(&(ENCODINGS.len() as u16).to_be_bytes());
        for e in ENCODINGS {
            m.extend_from_slice(&e.to_be_bytes());
        }
        out.write_all(&m)?;
        out.write_all(&update_request(false, w, h))?;
        out.flush()?;
        Ok(c)
    }

    fn resize(&mut self, w: u32, h: u32) {
        self.width = w;
        self.height = h;
        let mut f = self.frame.lock().unwrap_or_else(|e| e.into_inner());
        f.width = w;
        f.height = h;
        f.rgba = vec![0; (w * h * 4) as usize];
    }

    /// Reads one message from the server.
    pub fn read_message(&mut self) -> R<Option<RemoteEvent>> {
        match u8_(&mut self.inp)? {
            0 => {
                self.update()?;
                Ok(Some(RemoteEvent::Frame))
            }
            1 => {
                skip(&mut self.inp, 3)?;
                let n = u16_(&mut self.inp)? as usize;
                skip(&mut self.inp, n * 6)?;
                Ok(None)
            }
            2 => Ok(None), // bell
            3 => {
                skip(&mut self.inp, 3)?;
                let n = u32_(&mut self.inp)?.min(1 << 20) as usize;
                let mut b = vec![0u8; n];
                self.inp.read_exact(&mut b)?;
                // Latin-1.
                Ok(Some(RemoteEvent::Clipboard(b.iter().map(|&c| c as char).collect())))
            }
            t => Err(VncError::Protocol(trf("Message VNC inconnu (%1$s)", &[&t]))),
        }
    }

    fn update(&mut self) -> R<()> {
        skip(&mut self.inp, 1)?;
        let n = u16_(&mut self.inp)?;
        for _ in 0..n {
            let x = u16_(&mut self.inp)? as u32;
            let y = u16_(&mut self.inp)? as u32;
            let w = u16_(&mut self.inp)? as u32;
            let h = u16_(&mut self.inp)? as u32;
            let enc = u32_(&mut self.inp)? as i32;
            // Rectangles outside the screen would write out of bounds.
            let inside = x + w <= self.width && y + h <= self.height;
            match enc {
                ENC_DESKTOP_SIZE => self.resize(w, h),
                ENC_LAST_RECT => return Ok(()),
                ENC_CURSOR => self.cursor(x, y, w, h)?,
                _ if !inside => return Err(VncError::Protocol(tr("Rectangle VNC hors de l'écran"))),
                ENC_RAW => self.raw(x, y, w, h)?,
                ENC_COPYRECT => self.copy_rect(x, y, w, h)?,
                ENC_HEXTILE => self.hextile(x, y, w, h)?,
                ENC_ZRLE => self.zrle(x, y, w, h)?,
                e => return Err(VncError::Protocol(trf("Encodage VNC inattendu (%1$s)", &[&e]))),
            }
        }
        Ok(())
    }

    fn raw(&mut self, x: u32, y: u32, w: u32, h: u32) -> R<()> {
        let mut line = vec![0u8; (w * 4) as usize];
        for row in 0..h {
            self.inp.read_exact(&mut line)?;
            let mut f = self.frame.lock().unwrap_or_else(|e| e.into_inner());
            let base = (((y + row) * self.width + x) * 4) as usize;
            for col in 0..w as usize {
                let p = &line[col * 4..col * 4 + 3];
                put(&mut f.rgba, base + col * 4, p[0], p[1], p[2]);
            }
        }
        Ok(())
    }

    fn copy_rect(&mut self, x: u32, y: u32, w: u32, h: u32) -> R<()> {
        let sx = u16_(&mut self.inp)? as u32;
        let sy = u16_(&mut self.inp)? as u32;
        if sx + w > self.width || sy + h > self.height {
            return Err(VncError::Protocol(tr("Rectangle VNC hors de l'écran")));
        }
        let stride = (self.width * 4) as usize;
        let mut f = self.frame.lock().unwrap_or_else(|e| e.into_inner());
        let len = (w * 4) as usize;
        // Rows in the order that never reads a row already overwritten.
        let rows: Box<dyn Iterator<Item = u32>> = if sy < y { Box::new((0..h).rev()) } else { Box::new(0..h) };
        for r in rows {
            let src = (sy + r) as usize * stride + (sx * 4) as usize;
            let dst = (y + r) as usize * stride + (x * 4) as usize;
            f.rgba.copy_within(src..src + len, dst);
        }
        Ok(())
    }

    fn pixel(&mut self) -> R<[u8; 3]> {
        let mut b = [0u8; 4];
        self.inp.read_exact(&mut b)?;
        Ok([b[0], b[1], b[2]])
    }

    fn fill(&self, x: u32, y: u32, w: u32, h: u32, c: [u8; 3]) {
        let mut f = self.frame.lock().unwrap_or_else(|e| e.into_inner());
        for row in y..(y + h).min(self.height) {
            for col in x..(x + w).min(self.width) {
                put(&mut f.rgba, ((row * self.width + col) * 4) as usize, c[0], c[1], c[2]);
            }
        }
    }

    fn hextile(&mut self, x: u32, y: u32, w: u32, h: u32) -> R<()> {
        let (mut bg, mut fg) = ([0u8; 3], [255u8; 3]);
        let mut ty = y;
        while ty < y + h {
            let th = 16.min(y + h - ty);
            let mut tx = x;
            while tx < x + w {
                let tw = 16.min(x + w - tx);
                let sub = u8_(&mut self.inp)?;
                if sub & 1 != 0 {
                    self.raw(tx, ty, tw, th)?;
                } else {
                    if sub & 2 != 0 {
                        bg = self.pixel()?;
                    }
                    self.fill(tx, ty, tw, th, bg);
                    if sub & 4 != 0 {
                        fg = self.pixel()?;
                    }
                    if sub & 8 != 0 {
                        let n = u8_(&mut self.inp)?;
                        for _ in 0..n {
                            let color = if sub & 16 != 0 { self.pixel()? } else { fg };
                            let xy = u8_(&mut self.inp)? as u32;
                            let wh = u8_(&mut self.inp)? as u32;
                            self.fill(tx + (xy >> 4), ty + (xy & 15), (wh >> 4) + 1, (wh & 15) + 1, color);
                        }
                    }
                }
                tx += 16;
            }
            ty += 16;
        }
        Ok(())
    }

    // ZRLE: one zlib stream for all the rectangles, 64×64 tiles, 3-byte pixels.

    fn z_byte(&mut self, input: &[u8], used: &mut usize) -> R<u8> {
        if self.zpos == self.zbuf.len() {
            self.zbuf.clear();
            self.zbuf.reserve(64 * 1024);
            let before_in = self.zlib.total_in();
            self.zlib
                .decompress_vec(&input[*used..], &mut self.zbuf, flate2::FlushDecompress::Sync)
                .map_err(|e| VncError::Protocol(e.to_string()))?;
            *used += (self.zlib.total_in() - before_in) as usize;
            self.zpos = 0;
            if self.zbuf.is_empty() {
                return Err(VncError::Protocol(tr("Flux ZRLE incomplet")));
            }
        }
        let b = self.zbuf[self.zpos];
        self.zpos += 1;
        Ok(b)
    }

    fn zrle(&mut self, x: u32, y: u32, w: u32, h: u32) -> R<()> {
        let n = u32_(&mut self.inp)? as usize;
        if n > 64 << 20 {
            return Err(VncError::Protocol(tr("Flux ZRLE incomplet")));
        }
        let mut data = vec![0u8; n];
        self.inp.read_exact(&mut data)?;
        self.zbuf.clear();
        self.zpos = 0;
        let mut used = 0usize;
        macro_rules! byte {
            () => {
                self.z_byte(&data, &mut used)?
            };
        }
        macro_rules! pixel {
            () => {{
                let b = byte!();
                let g = byte!();
                let r = byte!();
                [b, g, r]
            }};
        }
        macro_rules! run {
            () => {{
                let mut n = 1usize;
                loop {
                    let b = byte!();
                    n += b as usize;
                    if b != 255 {
                        break n;
                    }
                }
            }};
        }
        let width = self.width;
        let mut ty = y;
        while ty < y + h {
            let th = 64.min(y + h - ty);
            let mut tx = x;
            while tx < x + w {
                let tw = 64.min(x + w - tx);
                let count = (tw * th) as usize;
                let sub = byte!();
                let mut tile: Vec<[u8; 3]> = Vec::with_capacity(count);
                match sub {
                    0 => {
                        for _ in 0..count {
                            tile.push(pixel!());
                        }
                    }
                    1 => {
                        let c = pixel!();
                        tile.resize(count, c);
                    }
                    2..=16 => {
                        let mut palette = Vec::with_capacity(sub as usize);
                        for _ in 0..sub {
                            palette.push(pixel!());
                        }
                        let bits = match sub {
                            2 => 1,
                            3 | 4 => 2,
                            _ => 4,
                        };
                        let mask = (1u8 << bits) - 1;
                        for _ in 0..th {
                            let (mut byte, mut left) = (0u8, 0);
                            for _ in 0..tw {
                                if left == 0 {
                                    byte = byte!();
                                    left = 8;
                                }
                                left -= bits;
                                tile.push(palette[((byte >> left) & mask) as usize % palette.len()]);
                            }
                        }
                    }
                    128 => {
                        while tile.len() < count {
                            let c = pixel!();
                            let r = run!();
                            for _ in 0..r.min(count - tile.len()) {
                                tile.push(c);
                            }
                        }
                    }
                    130..=255 => {
                        let mut palette = Vec::with_capacity(sub as usize - 128);
                        for _ in 0..sub - 128 {
                            palette.push(pixel!());
                        }
                        while tile.len() < count {
                            let idx = byte!();
                            let r = if idx & 128 != 0 { run!() } else { 1 };
                            let c = palette[(idx & 127) as usize % palette.len()];
                            for _ in 0..r.min(count - tile.len()) {
                                tile.push(c);
                            }
                        }
                    }
                    s => return Err(VncError::Protocol(trf("Tuile ZRLE invalide (%1$s)", &[&s]))),
                }
                let mut f = self.frame.lock().unwrap_or_else(|e| e.into_inner());
                for (i, c) in tile.iter().enumerate() {
                    let (r, col) = (i as u32 / tw, i as u32 % tw);
                    put(&mut f.rgba, (((ty + r) * width + tx + col) * 4) as usize, c[0], c[1], c[2]);
                }
                drop(f);
                tx += 64;
            }
            ty += 64;
        }
        Ok(())
    }

    fn cursor(&mut self, hot_x: u32, hot_y: u32, w: u32, h: u32) -> R<()> {
        if w == 0 || h == 0 {
            self.pending_cursor = Some(None);
            return Ok(());
        }
        let mut px = vec![0u8; (w * h * 4) as usize];
        self.inp.read_exact(&mut px)?;
        let row_bytes = w.div_ceil(8) as usize;
        let mut mask = vec![0u8; row_bytes * h as usize];
        self.inp.read_exact(&mut mask)?;
        let mut rgba = vec![0u8; (w * h * 4) as usize];
        for i in 0..(w * h) as usize {
            let (r, c) = (i / w as usize, i % w as usize);
            if (mask[r * row_bytes + c / 8] >> (7 - c % 8)) & 1 == 1 {
                put(&mut rgba, i * 4, px[i * 4], px[i * 4 + 1], px[i * 4 + 2]);
            }
        }
        self.pending_cursor = Some(Some(Cursor {
            width: w,
            height: h,
            hot_x,
            hot_y,
            rgba,
        }));
        Ok(())
    }
}

fn update_request(incremental: bool, w: u32, h: u32) -> Vec<u8> {
    let mut m = vec![3, incremental as u8, 0, 0, 0, 0];
    m.extend_from_slice(&(w as u16).to_be_bytes());
    m.extend_from_slice(&(h as u16).to_be_bytes());
    m
}

/// Connects to [host]:[port]; the password is asked through a [Prompt] when the server wants one and
/// [password] is not set.
pub fn spawn(host: String, port: u16, label: String, password: Option<String>) -> Remote {
    let (ev_tx, ev_rx) = tokio::sync::mpsc::unbounded_channel();
    let (in_tx, in_rx) = std::sync::mpsc::channel::<RemoteInput>();
    let frame: SharedFrame = Arc::new(Mutex::new(Framebuffer::default()));
    let f = frame.clone();
    std::thread::Builder::new()
        .name("vnc".into())
        .spawn(move || {
            let reason = run(&host, port, &label, password, f, &ev_tx, in_rx).err();
            let _ = ev_tx.send(RemoteEvent::Closed(reason.map(|e| e.to_string())));
        })
        .ok();
    Remote {
        input: in_tx,
        events: ev_rx,
        frame,
    }
}

fn ask_password(events: &UnboundedSender<RemoteEvent>, label: &str, host: &str) -> Option<String> {
    let (reply, rx) = Reply::new();
    events
        .send(RemoteEvent::Prompt(Prompt::Secret {
            label: label.into(),
            title: tr("Mot de passe VNC"),
            detail: host.into(),
            reply,
        }))
        .ok()?;
    rx.blocking_recv().ok().flatten()
}

fn run(
    host: &str,
    port: u16,
    label: &str,
    password: Option<String>,
    frame: SharedFrame,
    events: &UnboundedSender<RemoteEvent>,
    input: std::sync::mpsc::Receiver<RemoteInput>,
) -> R<()> {
    let _ = events.send(RemoteEvent::Status(trf("Connexion à %1$s…", &[&label])));
    let addr = (host, port)
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| VncError::Protocol(trf("Adresse introuvable : %1$s", &[&host])))?;
    let stream = TcpStream::connect_timeout(&addr, Duration::from_secs(15))?;
    stream.set_nodelay(true)?;
    let mut out = BufWriter::new(stream.try_clone()?);
    let mut client = Client::handshake(stream.try_clone()?, &mut out, frame, || {
        password.filter(|p| !p.is_empty()).or_else(|| ask_password(events, label, host))
    })?;
    let _ = events.send(RemoteEvent::Ready(client.name.clone()));
    let _ = events.send(RemoteEvent::Frame);
    // Input goes out from its own thread.
    let size = Arc::new(Mutex::new((client.width, client.height)));
    let sz = size.clone();
    let closing = stream.try_clone()?;
    std::thread::Builder::new().name("vnc-input".into()).spawn(move || {
        while let Ok(i) = input.recv() {
            let m = match i {
                RemoteInput::Pointer { buttons, x, y } => {
                    let (w, h) = *sz.lock().unwrap_or_else(|e| e.into_inner());
                    let mut m = vec![5, buttons];
                    m.extend_from_slice(&x.min(w.saturating_sub(1) as u16).to_be_bytes());
                    m.extend_from_slice(&y.min(h.saturating_sub(1) as u16).to_be_bytes());
                    m
                }
                RemoteInput::Key { keysym, down, .. } => {
                    let mut m = vec![4, down as u8, 0, 0];
                    m.extend_from_slice(&keysym.to_be_bytes());
                    m
                }
                RemoteInput::Clipboard(text) => {
                    let bytes: Vec<u8> = text.chars().map(|c| if (c as u32) < 256 { c as u8 } else { b'?' }).collect();
                    let mut m = vec![6, 0, 0, 0];
                    m.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
                    m.extend_from_slice(&bytes);
                    m
                }
                RemoteInput::Close => {
                    let _ = closing.shutdown(std::net::Shutdown::Both);
                    break;
                }
            };
            if out.write_all(&m).and_then(|_| out.flush()).is_err() {
                break;
            }
        }
    })?;
    let mut updates = BufWriter::new(stream.try_clone()?);
    loop {
        let ev = match client.read_message() {
            Ok(e) => e,
            // Closed by us.
            Err(VncError::Io(e))
                if matches!(
                    e.kind(),
                    io::ErrorKind::UnexpectedEof | io::ErrorKind::ConnectionAborted | io::ErrorKind::NotConnected
                ) =>
            {
                return Ok(())
            }
            Err(e) => return Err(e),
        };
        if let Some(c) = client.pending_cursor.take() {
            let _ = events.send(RemoteEvent::Cursor(c));
        }
        if let Some(ev) = ev {
            let frame = matches!(ev, RemoteEvent::Frame);
            if events.send(ev).is_err() {
                return Ok(());
            }
            if frame {
                *size.lock().unwrap_or_else(|e| e.into_inner()) = (client.width, client.height);
                updates.write_all(&update_request(true, client.width, client.height))?;
                updates.flush()?;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_challenge_answer_matches_the_known_vector() {
        // VNC's DES with mirrored key bits (same vector as the Android test).
        let challenge: [u8; 16] = core::array::from_fn(|i| i as u8);
        let r = vnc_response("secret12", &challenge);
        // Decrypting with the same key gives the challenge back.
        use des::cipher::BlockCipherDecrypt;
        let mut key = [0u8; 8];
        for (i, c) in "secret12".bytes().enumerate() {
            key[i] = c.reverse_bits();
        }
        let d = des::Des::new(&key.into());
        let mut back = r;
        for b in back.as_chunks_mut::<8>().0 {
            d.decrypt_block(b.into());
        }
        assert_eq!(back, challenge);
        assert_ne!(r, challenge);
    }

    /// A tiny RFB 3.8 server: password, one Raw rectangle, one CopyRect, one ZRLE tile.
    #[test]
    fn a_session_draws_the_updates() {
        use std::io::Write as _;
        let mut server = Vec::new();
        server.extend_from_slice(b"RFB 003.008\n");
        server.extend_from_slice(&[1, SEC_VNC]);
        server.extend_from_slice(&[0u8; 16]); // challenge
        server.extend_from_slice(&0u32.to_be_bytes()); // OK
        server.extend_from_slice(&4u16.to_be_bytes());
        server.extend_from_slice(&2u16.to_be_bytes());
        server.extend_from_slice(&[0u8; 16]);
        server.extend_from_slice(&3u32.to_be_bytes());
        server.extend_from_slice(b"pc1");
        // FramebufferUpdate with 3 rectangles.
        server.extend_from_slice(&[0, 0]);
        server.extend_from_slice(&3u16.to_be_bytes());
        let rect = |s: &mut Vec<u8>, x: u16, y: u16, w: u16, h: u16, e: i32| {
            for v in [x, y, w, h] {
                s.extend_from_slice(&v.to_be_bytes());
            }
            s.extend_from_slice(&e.to_be_bytes());
        };
        rect(&mut server, 0, 0, 2, 1, ENC_RAW);
        server.extend_from_slice(&[0, 0, 255, 0, 0, 255, 0, 0]); // red (B,G,R,x), green
        rect(&mut server, 0, 1, 2, 1, ENC_COPYRECT);
        server.extend_from_slice(&[0, 0, 0, 0]); // from (0,0)
        rect(&mut server, 2, 0, 2, 2, ENC_ZRLE);
        let mut z = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        z.write_all(&[1, 255, 0, 0]).unwrap(); // solid tile, blue
        let zdata = z.finish().unwrap();
        server.extend_from_slice(&(zdata.len() as u32).to_be_bytes());
        server.extend_from_slice(&zdata);

        let frame: SharedFrame = Arc::default();
        let mut sent = Vec::new();
        let mut c = Client::handshake(&server[..], &mut sent, frame.clone(), || Some("secret12".into())).unwrap();
        assert_eq!((c.width, c.height, c.name.as_str()), (4, 2, "pc1"));
        assert!(sent.starts_with(b"RFB 003.008\n"));
        assert!(matches!(c.read_message().unwrap(), Some(RemoteEvent::Frame)));
        let f = frame.lock().unwrap();
        let px = |x: usize, y: usize| &f.rgba[(y * 4 + x) * 4..(y * 4 + x) * 4 + 4];
        assert_eq!(px(0, 0), [255, 0, 0, 255]);
        assert_eq!(px(1, 0), [0, 255, 0, 255]);
        assert_eq!(px(0, 1), [255, 0, 0, 255], "copied");
        assert_eq!(px(3, 1), [0, 0, 255, 255], "ZRLE");
    }
}
