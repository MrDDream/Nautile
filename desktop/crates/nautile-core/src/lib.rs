//! Nautile's core for the desktop app: the same records, backup file and synchronisation as the Android app,
//! so a phone and a computer share one backup or sync file.

pub mod backup;
pub mod i18n;
pub mod model;

pub use i18n::{tr, trf};
