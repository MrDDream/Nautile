//! Against a real S3 (RustFS) and WebDAV server in Docker: `scripts/store-it.sh` (skipped without the variables).

use nautile_net::store::{Dest, Remote, S3Client, StoreError};

fn check(dest: Dest, secret: &str) {
    let s = dest.open(secret);
    s.probe().unwrap();
    let empty = s.read().unwrap();
    assert_eq!(empty.text, None);
    s.write(b"first", Some(&empty)).unwrap();
    // Another device wrote in between: a write based on the old state must fail.
    assert_eq!(s.write(b"stale", Some(&empty)), Err(StoreError::Conflict));
    let now = s.read().unwrap();
    assert_eq!(now.text.as_deref(), Some("first"));
    if now.etag.as_deref().is_some_and(|e| !e.starts_with("W/")) {
        let old = Remote {
            text: Some("x".into()),
            etag: Some("\"nope\"".into()),
        };
        assert_eq!(s.write(b"stale", Some(&old)), Err(StoreError::Conflict));
    }
    s.rotate();
    s.write(b"second", Some(&s.read().unwrap())).unwrap();
    assert_eq!(s.read().unwrap().text.as_deref(), Some("second"));
}

#[test]
fn s3_and_webdav() {
    if let Ok(port) = std::env::var("S3_PORT") {
        let endpoint = format!("http://127.0.0.1:{port}");
        S3Client::new(&endpoint, "us-east-1", "nautile", "nautile", "nautile-secret")
            .create_bucket()
            .unwrap();
        let key = format!("it/{}.backup", std::process::id());
        check(
            Dest::S3 {
                endpoint,
                region: "us-east-1".into(),
                bucket: "nautile".into(),
                key,
                access_key: "nautile".into(),
            },
            "nautile-secret",
        );
        eprintln!("S3 OK");
    }
    if let Ok(url) = std::env::var("WEBDAV_URL") {
        check(
            Dest::WebDav {
                url,
                username: "nautile".into(),
            },
            "nautile",
        );
        eprintln!("WebDAV OK");
    }
}
