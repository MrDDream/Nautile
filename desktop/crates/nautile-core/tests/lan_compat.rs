//! Replays the Wi-Fi handshakes recorded by the Android app (desktop/tests/fixtures/lan-*.json, made by
//! app's LanFixturesTest, which checks them on its side too): with the same random values, the Rust side must
//! send exactly the same bytes, and derive the same pairing secret.

use nautile_core::lan::{self, LanError};
use serde_json::Value;
use std::io::{Cursor, Read, Write};
use std::path::PathBuf;

/// Reads from a recording, keeps what is written.
struct Replay {
    input: Cursor<Vec<u8>>,
    output: Vec<u8>,
}

impl Read for Replay {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        self.input.read(buf)
    }
}

impl Write for Replay {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.output.write(buf)
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

fn load(name: &str) -> Value {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../tests/fixtures")
        .join(format!("lan-{name}.json"));
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn bytes(v: &Value, key: &str) -> Vec<u8> {
    hex::decode(v[key].as_str().unwrap()).unwrap()
}

fn key(v: &Value, k: &str) -> [u8; 32] {
    bytes(v, k).try_into().unwrap()
}

fn text<'a>(v: &'a Value, k: &str) -> &'a str {
    v[k].as_str().unwrap()
}

fn texts(v: &Value, k: &str) -> Vec<String> {
    v[k].as_array().unwrap().iter().map(|s| s.as_str().unwrap().to_owned()).collect()
}

fn replay(name: &str) {
    let v = load(name);
    let mode = text(&v, "mode").as_bytes()[0];
    let (id_i, id_r, code) = (text(&v, "initiator_id"), text(&v, "responder_id"), text(&v, "code"));
    let secret = bytes(&v, "secret_hex");
    let i2r = bytes(&v, "initiator_to_responder_hex");
    let r2i = bytes(&v, "responder_to_initiator_hex");

    // As the initiator, against what the Android responder sent.
    let io = Replay {
        input: Cursor::new(r2i.clone()),
        output: Vec::new(),
    };
    let mut ch = if mode == lan::MODE_PAIR {
        let p = lan::pair_connect_with(io, id_i, id_r, code, key(&v, "initiator_random_hex")).unwrap();
        assert_eq!(hex::encode(p.secret), hex::encode(&secret), "pairing secret ({name})");
        p.channel
    } else {
        lan::sync_connect_with(io, id_i, id_r, &secret, key(&v, "initiator_random_hex")).unwrap()
    };
    for t in texts(&v, "initiator_sends") {
        ch.send_text(&t).unwrap();
    }
    for t in texts(&v, "responder_sends") {
        assert_eq!(ch.receive_text().unwrap(), t);
    }
    assert_eq!(hex::encode(ch.into_inner().output), hex::encode(&i2r), "initiator bytes ({name})");

    // As the responder, against what the Android initiator sent.
    let mut io = Replay {
        input: Cursor::new(i2r),
        output: Vec::new(),
    };
    let hello = lan::read_hello(&mut io).unwrap();
    assert_eq!(hello, lan::Hello { mode, id: id_i.into() });
    let mut ch = if mode == lan::MODE_PAIR {
        let p = lan::pair_accept_with(io, &hello, id_r, code, key(&v, "responder_random_hex")).unwrap();
        assert_eq!(hex::encode(p.secret), hex::encode(&secret), "pairing secret ({name})");
        p.channel
    } else {
        lan::sync_accept_with(io, &hello, id_r, &secret, key(&v, "responder_random_hex")).unwrap()
    };
    for t in texts(&v, "initiator_sends") {
        assert_eq!(ch.receive_text().unwrap(), t);
    }
    for t in texts(&v, "responder_sends") {
        ch.send_text(&t).unwrap();
    }
    assert_eq!(hex::encode(ch.into_inner().output), hex::encode(&r2i), "responder bytes ({name})");
}

#[test]
fn pairing_matches_android() {
    replay("pair");
}

#[test]
fn sync_matches_android() {
    replay("sync");
}

#[test]
fn wrong_code_is_refused_before_anything_is_sent() {
    let v = load("pair");
    let io = Replay {
        input: Cursor::new(bytes(&v, "responder_to_initiator_hex")),
        output: Vec::new(),
    };
    let e = lan::pair_connect_with(
        io,
        text(&v, "initiator_id"),
        text(&v, "responder_id"),
        "000000",
        key(&v, "initiator_random_hex"),
    )
    .err()
    .unwrap();
    assert_eq!(LanError::of(&e), Some(LanError::AuthFailed));
}

#[test]
fn another_secret_is_refused() {
    let v = load("sync");
    let io = Replay {
        input: Cursor::new(bytes(&v, "responder_to_initiator_hex")),
        output: Vec::new(),
    };
    let e = lan::sync_connect_with(
        io,
        text(&v, "initiator_id"),
        text(&v, "responder_id"),
        &[0u8; 32],
        key(&v, "initiator_random_hex"),
    )
    .err()
    .unwrap();
    assert_eq!(LanError::of(&e), Some(LanError::AuthFailed));
}
