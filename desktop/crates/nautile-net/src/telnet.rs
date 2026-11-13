//! Plain Telnet (RFC 854) with terminal type, echo, suppress-go-ahead and window size (NAWS) negotiation,
//! like Android's `TelnetChannel.kt`.

use nautile_core::trf;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::session::{channels, Event, Input, Session, RUNTIME};

const IAC: u8 = 255;
const DONT: u8 = 254;
const DO: u8 = 253;
const WONT: u8 = 252;
const WILL: u8 = 251;
const SB: u8 = 250;
const SE: u8 = 240;
const ECHO: u8 = 1;
const SGA: u8 = 3;
const TTYPE: u8 = 24;
const NAWS: u8 = 31;
const TERM: &str = "xterm-256color";

/// The protocol state of a connection.
#[derive(Default)]
pub struct Telnet {
    state: State,
    sub: Vec<u8>,
    naws: bool,
    cols: u16,
    rows: u16,
}

#[derive(Default, Clone, Copy)]
enum State {
    #[default]
    Data,
    Iac,
    Verb(u8),
    Sub,
    SubIac,
}

fn naws(cols: u16, rows: u16) -> Vec<u8> {
    let mut v = vec![IAC, SB, NAWS];
    for b in [cols.to_be_bytes(), rows.to_be_bytes()].concat() {
        v.push(b);
        if b == IAC {
            v.push(IAC);
        }
    }
    v.extend_from_slice(&[IAC, SE]);
    v
}

impl Telnet {
    pub fn new(cols: u16, rows: u16) -> Telnet {
        Telnet {
            cols,
            rows,
            ..Default::default()
        }
    }

    /// What the client announces when it connects.
    pub fn hello(&self) -> Vec<u8> {
        vec![IAC, DO, SGA, IAC, DO, ECHO, IAC, WILL, TTYPE, IAC, WILL, NAWS]
    }

    /// Splits what the server sent into terminal bytes and answers to send back.
    pub fn receive(&mut self, input: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let (mut data, mut reply) = (Vec::new(), Vec::new());
        for &b in input {
            self.state = match self.state {
                State::Data if b == IAC => State::Iac,
                State::Data => {
                    data.push(b);
                    State::Data
                }
                State::Iac => match b {
                    IAC => {
                        data.push(IAC);
                        State::Data
                    }
                    DO | DONT | WILL | WONT => State::Verb(b),
                    SB => {
                        self.sub.clear();
                        State::Sub
                    }
                    _ => State::Data,
                },
                State::Verb(verb) => {
                    match (verb, b) {
                        (DO, NAWS) => {
                            if !self.naws {
                                self.naws = true;
                            }
                            reply.extend(naws(self.cols, self.rows));
                        }
                        (DO, TTYPE) => {}
                        (DO, opt) => reply.extend([IAC, WONT, opt]),
                        (DONT, opt) => {
                            if opt == NAWS {
                                self.naws = false;
                            }
                            reply.extend([IAC, WONT, opt]);
                        }
                        (WILL, ECHO | SGA) => {}
                        (WILL, opt) => reply.extend([IAC, DONT, opt]),
                        (WONT, _) => {}
                        _ => {}
                    }
                    State::Data
                }
                State::Sub if b == IAC => State::SubIac,
                State::Sub => {
                    self.sub.push(b);
                    State::Sub
                }
                State::SubIac if b == SE => {
                    // TTYPE SEND → TTYPE IS xterm-256color.
                    if self.sub.first() == Some(&TTYPE) && self.sub.get(1) == Some(&1) {
                        reply.extend([IAC, SB, TTYPE, 0]);
                        reply.extend_from_slice(TERM.as_bytes());
                        reply.extend([IAC, SE]);
                    }
                    State::Data
                }
                State::SubIac => {
                    self.sub.push(b);
                    State::Sub
                }
            };
        }
        (data, reply)
    }

    /// Escapes the user's bytes (IAC doubled, a lone CR followed by NUL).
    pub fn send(data: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(data.len());
        for (i, &b) in data.iter().enumerate() {
            out.push(b);
            if b == IAC {
                out.push(IAC);
            } else if b == b'\r' && data.get(i + 1) != Some(&b'\n') {
                out.push(0);
            }
        }
        out
    }

    pub fn resize(&mut self, cols: u16, rows: u16) -> Option<Vec<u8>> {
        self.cols = cols;
        self.rows = rows;
        self.naws.then(|| naws(cols, rows))
    }
}

pub fn spawn(host: String, port: u16, cols: u16, rows: u16) -> Session {
    let (session, events, mut input) = channels();
    RUNTIME.spawn(async move {
        events.status(trf("Connexion à %1$s…", &[&host]));
        let mut sock = match crate::proxy::connect(&host, port, None).await {
            Ok(s) => s,
            Err(e) => return events.send(Event::Closed(Some(e.to_string()))),
        };
        let mut t = Telnet::new(cols, rows);
        if sock.write_all(&t.hello()).await.is_err() {
            return events.send(Event::Closed(None));
        }
        events.send(Event::Connected);
        let mut buf = vec![0u8; 32 * 1024];
        let reason = loop {
            tokio::select! {
                n = sock.read(&mut buf) => match n {
                    Ok(0) => break None,
                    Ok(n) => {
                        let (data, reply) = t.receive(&buf[..n]);
                        events.output(&data);
                        if !reply.is_empty() && sock.write_all(&reply).await.is_err() {
                            break None;
                        }
                    }
                    Err(e) => break Some(e.to_string()),
                },
                i = input.recv() => match i {
                    Some(Input::Data(d)) => {
                        if sock.write_all(&Telnet::send(&d)).await.is_err() {
                            break None;
                        }
                    }
                    Some(Input::Resize { cols, rows }) => {
                        if let Some(m) = t.resize(cols, rows) {
                            let _ = sock.write_all(&m).await;
                        }
                    }
                    Some(Input::Close) | None => break None,
                },
            }
        };
        events.send(Event::Closed(reason));
    });
    session
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn options_are_negotiated() {
        let mut t = Telnet::new(80, 24);
        let (data, reply) = t.receive(&[b'h', IAC, DO, NAWS, b'i', IAC, IAC, IAC, DO, 39, IAC, SB, TTYPE, 1, IAC, SE]);
        assert_eq!(data, [b'h', b'i', IAC]);
        let mut want = naws(80, 24);
        want.extend([IAC, WONT, 39, IAC, SB, TTYPE, 0]);
        want.extend_from_slice(b"xterm-256color");
        want.extend([IAC, SE]);
        assert_eq!(reply, want);
        assert_eq!(t.resize(100, 40), Some(naws(100, 40)));
        assert_eq!(Telnet::send(b"a\r\xff"), b"a\r\0\xff\xff");
    }
}
