//! Portable mode: the master password that opens the database key (`db.key.enc`), asked before anything is
//! read, and changed in « Données ». The Argon2id derivation (about a second) runs off the interface thread.

use std::time::{Duration, Instant};

use iced::Task;
use nautile_core::{tr, trf};

use crate::app::{App, Message};
use crate::storage::{self, Vault};

/// The start screen of the portable mode.
#[derive(Debug, Clone)]
pub struct VaultForm {
    pub mode: Vault,
    pub password: String,
    pub confirm: String,
    pub error: Option<String>,
    /// The derivation is running.
    pub busy: bool,
    /// Wrong passwords since the start (in memory: the tries are never limited, only slowed).
    pub failures: u32,
    pub until: Option<Instant>,
}

impl VaultForm {
    pub fn new(mode: Vault) -> VaultForm {
        VaultForm {
            mode,
            password: String::new(),
            confirm: String::new(),
            error: None,
            busy: false,
            failures: 0,
            until: None,
        }
    }
}

/// « Changer le mot de passe maître », in the settings.
#[derive(Debug, Clone, Default)]
pub struct MasterForm {
    pub current: String,
    pub new: String,
    pub confirm: String,
    pub error: Option<String>,
    pub busy: bool,
}

#[derive(Debug, Clone)]
pub enum VaultMsg {
    Password(String),
    Confirm(String),
    Submit,
    /// The database key, or why not.
    Opened(Result<[u8; 32], String>),
    ChangeCurrent(String),
    ChangeNew(String),
    ChangeConfirm(String),
    ChangeSave,
    Changed(Result<(), String>),
}

/// What a new master password must be.
fn check_new(password: &str, confirm: &str) -> Result<(), String> {
    if password.chars().count() < nautile_core::lock::MASTER_MIN {
        return Err(trf(
            "Le mot de passe maître doit faire au moins %1$s caractères",
            &[&nautile_core::lock::MASTER_MIN],
        ));
    }
    if password != confirm {
        return Err(tr("Les deux saisies ne correspondent pas"));
    }
    Ok(())
}

impl App {
    pub fn vault_msg(&mut self, m: VaultMsg) -> Task<Message> {
        match m {
            VaultMsg::Password(v) => {
                if let Some(f) = &mut self.vault {
                    f.password = v;
                    f.error = None;
                }
            }
            VaultMsg::Confirm(v) => {
                if let Some(f) = &mut self.vault {
                    f.confirm = v;
                    f.error = None;
                }
            }
            VaultMsg::Submit => return self.vault_submit(),
            VaultMsg::Opened(r) => return self.vault_opened(r),
            VaultMsg::ChangeCurrent(v) => self.master_form.current = v,
            VaultMsg::ChangeNew(v) => self.master_form.new = v,
            VaultMsg::ChangeConfirm(v) => self.master_form.confirm = v,
            VaultMsg::ChangeSave => {
                let f = &mut self.master_form;
                if f.busy || !self.paths.portable || self.db.is_none() {
                    return Task::none();
                }
                if let Err(e) = check_new(&f.new, &f.confirm) {
                    f.error = Some(e);
                    return Task::none();
                }
                f.busy = true;
                f.error = None;
                let (paths, current, new) = (self.paths.clone(), f.current.clone(), f.new.clone());
                return Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || storage::change_master(&paths, &current, &new))
                            .await
                            .map_err(|e| e.to_string())
                            .and_then(|r| r)
                    },
                    |r| Message::Vault(VaultMsg::Changed(r)),
                );
            }
            VaultMsg::Changed(r) => {
                self.master_form.busy = false;
                match r {
                    Ok(()) => {
                        self.master_form = MasterForm::default();
                        return self.say(tr("Mot de passe maître changé"));
                    }
                    Err(e) => self.master_form.error = Some(e),
                }
            }
        }
        Task::none()
    }

    fn vault_submit(&mut self) -> Task<Message> {
        let Some(f) = &mut self.vault else { return Task::none() };
        if f.busy {
            return Task::none();
        }
        if let Some(left) = f.until.and_then(|u| u.checked_duration_since(Instant::now())) {
            f.error = Some(trf("Patientez %1$s s avant de réessayer", &[&left.as_secs().max(1)]));
            return Task::none();
        }
        let create = matches!(f.mode, Vault::Create { .. });
        if create {
            if let Err(e) = check_new(&f.password, &f.confirm) {
                f.error = Some(e);
                return Task::none();
            }
        } else if f.password.is_empty() {
            return Task::none();
        }
        f.busy = true;
        f.error = None;
        let (paths, password) = (self.paths.clone(), f.password.clone());
        Task::perform(
            async move {
                tokio::task::spawn_blocking(move || {
                    if create {
                        storage::create_master(&paths, &password)
                    } else {
                        storage::open_master(&paths, &password)
                    }
                })
                .await
                .map_err(|e| e.to_string())
                .and_then(|r| r)
            },
            |r| Message::Vault(VaultMsg::Opened(r)),
        )
    }

    fn vault_opened(&mut self, r: Result<[u8; 32], String>) -> Task<Message> {
        let Some(f) = &mut self.vault else { return Task::none() };
        f.busy = false;
        let key = match r {
            Ok(k) => k,
            Err(e) => {
                f.password.clear();
                if matches!(f.mode, Vault::Open) {
                    f.failures += 1;
                    let wait = nautile_core::lock::master_wait_ms(f.failures);
                    f.until = (wait > 0).then(|| Instant::now() + Duration::from_millis(wait));
                }
                f.error = Some(e);
                return iced::widget::operation::focus("vault");
            }
        };
        self.vault = None;
        // Session logs and AI conversations are sealed with keys derived from it.
        crate::sealed::set_database_key(&key);
        crate::sealed::seal_old_files(&self.paths.data);
        match nautile_core::db::Db::open(&self.paths.db(), &key) {
            Ok(db) => {
                self.db = Some(db);
                self.reload();
                // The master password was just typed: the app's own lock is not asked again now.
                self.lock_boot(false);
                self.refresh_view_cache();
                Task::batch([self.backup_boot(), self.install_pending()])
            }
            Err(e) => {
                self.fatal = Some(e.to_string());
                Task::none()
            }
        }
    }
}
