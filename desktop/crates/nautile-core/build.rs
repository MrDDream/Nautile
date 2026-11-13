//! Compiles the Android app's translations (core/i18n/translations/*.tsv, "French<TAB>English" with Kotlin
//! escapes) into a sorted table, so the desktop app shows exactly the same English text.

use std::fmt::Write as _;
use std::path::PathBuf;

/// Kotlin string escapes used in the TSV files: \$ \" \\ \n \t \uXXXX.
fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars().peekable();
    while let Some(c) = it.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match it.next() {
            Some('n') => out.push('\n'),
            Some('t') => out.push('\t'),
            Some('r') => out.push('\r'),
            Some('u') => {
                let hex: String = it.by_ref().take(4).collect();
                match u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32) {
                    Some(ch) => out.push(ch),
                    None => {
                        out.push_str("\\u");
                        out.push_str(&hex);
                    }
                }
            }
            Some(other) => out.push(other), // \$ \" \\ \'
            None => out.push('\\'),
        }
    }
    out
}

fn main() {
    let dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap()).join("../../../core/i18n/translations");
    println!("cargo:rerun-if-changed={}", dir.display());
    let mut files: Vec<_> = std::fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("translations not found in {}: {e}", dir.display()))
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.extension().is_some_and(|x| x == "tsv"))
        .collect();
    files.sort();

    // Later files win, as in scripts/gen-strings.py.
    let mut pairs = std::collections::BTreeMap::new();
    for f in &files {
        println!("cargo:rerun-if-changed={}", f.display());
        for line in std::fs::read_to_string(f).unwrap().lines() {
            if line.trim().is_empty() {
                continue;
            }
            if let Some((fr, en)) = line.split_once('\t') {
                pairs.insert(unescape(fr), unescape(en));
            }
        }
    }

    let mut code = String::from(
        "/// (French, English), sorted by French (generated from core/i18n/translations).\npub(crate) static PAIRS: &[(&str, &str)] = &[\n",
    );
    for (fr, en) in &pairs {
        writeln!(code, "    ({fr:?}, {en:?}),").unwrap();
    }
    code.push_str("];\n");
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("strings.rs");
    std::fs::write(out, code).unwrap();
}
