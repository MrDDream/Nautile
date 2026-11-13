//! Nautile's core for the desktop app: the same records, backup file and synchronisation as the Android app,
//! so a phone and a computer share one backup or sync file.

pub mod backup;
pub mod db;
pub mod i18n;
pub mod lan;
pub mod model;
pub mod sync;

pub use i18n::{tr, trf};
