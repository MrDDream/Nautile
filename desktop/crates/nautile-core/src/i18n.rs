//! The app is written in French, like the Android one: `tr("Connexions")` gives the English text when the
//! interface is in English. Placeholders are Android's: `%1$s`, `%2$s`…

use std::fmt::Display;
use std::sync::atomic::{AtomicBool, Ordering};

include!(concat!(env!("OUT_DIR"), "/strings.rs"));

static ENGLISH: AtomicBool = AtomicBool::new(false);

/// Switches the interface language (French when false).
pub fn set_english(english: bool) {
    ENGLISH.store(english, Ordering::Relaxed);
}

pub fn is_english() -> bool {
    ENGLISH.load(Ordering::Relaxed)
}

/// The text in the interface language; unknown texts stay in French.
pub fn tr(french: &str) -> String {
    lookup(french).to_owned()
}

/// [tr] with `%1$s`-style placeholders filled in.
pub fn trf(french: &str, args: &[&dyn Display]) -> String {
    let mut s = lookup(french).to_owned();
    for (i, a) in args.iter().enumerate() {
        s = s.replace(&format!("%{}$s", i + 1), &a.to_string());
    }
    s
}

fn lookup(french: &str) -> &str {
    if !is_english() {
        return french;
    }
    match PAIRS.binary_search_by(|(fr, _)| (*fr).cmp(french)) {
        Ok(i) => PAIRS[i].1,
        Err(_) => french,
    }
}

/// Number of translated texts (for tests and diagnostics).
pub fn count() -> usize {
    PAIRS.len()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn translates_like_android() {
        set_english(true);
        assert!(count() > 1000);
        assert_eq!(tr("Connexions"), "Connections");
        assert_eq!(trf("%1$s (importée)", &[&"laptop"]), "laptop (imported)");
        assert_eq!(tr("texte absent du catalogue"), "texte absent du catalogue");
        set_english(false);
        assert_eq!(tr("Connexions"), "Connexions");
    }
}
