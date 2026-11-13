//! Nautile's connections. Each one runs on [session::RUNTIME] and talks to the interface through a
//! [session::Session]: input and resizes in, terminal bytes, questions and status out.

pub mod keys;
pub mod local;
pub mod mosh;
mod pem;
pub mod proxy;
pub mod session;
pub mod sftp;
mod socks;
pub mod ssh;
pub mod telnet;

pub use session::{Event, HostKeyInfo, Input, KnownKey, Prompt, Session};
