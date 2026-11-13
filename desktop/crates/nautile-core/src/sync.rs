//! Merges another device's data into this database, record by record — a port of the Android app's
//! core/data/.../sync/SyncMerge.kt, so both sides reach the same state:
//! - records are matched by sync id, or by a natural key (alias, group name, key fingerprint…) the first time two
//!   devices meet, in which case both adopt the smaller sync id;
//! - the most recent change wins (`updatedAt`, ties broken by sync id so every device picks the same);
//! - a deletion wins over changes made before it, and a change made after a deletion brings the record back.
//!
//! Local-only fields (last connection, detected OS, run count) are never taken from the other side.

use std::collections::{HashMap, HashSet};

use crate::db::{Db, Result, Store};
use crate::model::{
    known_host_tombstone_id, now_ms, BackupData, Host, HostGroup, Identity, KnownHost, PortForward, Snippet, SshKey, SyncDevice,
};

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Stats {
    pub added: usize,
    pub updated: usize,
    pub deleted: usize,
}

impl Stats {
    pub fn changed(&self) -> bool {
        self.added + self.updated + self.deleted > 0
    }
}

fn remote_wins(r_at: i64, r_id: &str, l_at: i64, l_id: &str) -> bool {
    r_at > l_at || (r_at == l_at && r_id < l_id)
}

/// updatedAt to write for a winning remote record: never equal to the local one, or the trigger would stamp « now ».
fn stamp(remote_at: i64, local_at: i64) -> i64 {
    if remote_at == local_at {
        remote_at + 1
    } else {
        remote_at
    }
}

fn canonical(a: &str, b: &str) -> String {
    a.min(b).to_owned()
}

/// Hosts without a jump host first, then those whose jump host is already placed; cycles last.
pub fn order_by_jump(hosts: &[Host]) -> Vec<Host> {
    let ids: HashSet<i64> = hosts.iter().map(|h| h.id).collect();
    let mut done: Vec<Host> = Vec::new();
    let mut placed: HashSet<i64> = HashSet::new();
    let mut pending: Vec<Host> = hosts.to_vec();
    for _ in 0..=hosts.len() {
        let (ready, rest): (Vec<Host>, Vec<Host>) = pending
            .into_iter()
            .partition(|h| h.jump_host_id.is_none_or(|j| !ids.contains(&j) || placed.contains(&j)));
        for h in ready {
            placed.insert(h.id);
            done.push(h);
        }
        pending = rest;
        if pending.is_empty() {
            return done;
        }
    }
    done.extend(pending);
    done
}

/// Host equality ignoring the fields that differ between devices without being edits.
fn same_host(a: &Host, b: &Host) -> bool {
    let strip = |h: &Host| Host {
        updated_at: 0,
        sync_id: String::new(),
        last_connected_at: None,
        os_id: None,
        created_at: 0,
        ..h.clone()
    };
    strip(a) == strip(b)
}

/// Pulls `remote` into `db`, in one transaction.
pub fn merge(db: &mut Db, remote: &BackupData) -> Result<Stats> {
    db.transaction(|s| merge_in(s, remote))
}

fn merge_in(s: Store<'_>, remote: &BackupData) -> Result<Stats> {
    let mut st = Stats::default();

    // 1. Deletions made on the other device.
    let before: HashMap<String, i64> = s.tombstones()?.into_iter().map(|t| (t.sync_id, t.deleted_at)).collect();
    for t in &remote.tombstones {
        let n = s.delete_synced(&t.kind, &t.sync_id, t.deleted_at)?;
        st.deleted += n;
        // Keep the original deletion time (the trigger wrote « now »).
        let mine = before.get(&t.sync_id);
        if n > 0 || mine.is_none_or(|at| *at < t.deleted_at) {
            s.put_tombstone(t)?;
        }
    }
    let tombs: HashMap<String, i64> = s.tombstones()?.into_iter().map(|t| (t.sync_id, t.deleted_at)).collect();
    let deleted = |id: &str, at: i64| tombs.get(id).is_some_and(|d| *d >= at);
    let revived = |id: &str| -> Result<()> {
        if tombs.contains_key(id) {
            s.drop_tombstone(id)?;
        }
        Ok(())
    };

    // 2. Groups.
    let mut group_ids: HashMap<i64, i64> = HashMap::new();
    for g in &remote.groups {
        if deleted(&g.sync_id, g.updated_at) {
            continue;
        }
        let local = match s.by_sync_id::<HostGroup>(&g.sync_id)? {
            Some(l) => Some(l),
            None => s.group_by_name(&g.name)?,
        };
        let Some(local) = local else {
            if let Some(id) = s.insert_or_ignore(&HostGroup { id: 0, ..g.clone() })? {
                group_ids.insert(g.id, id);
                st.added += 1;
                revived(&g.sync_id)?;
            }
            continue;
        };
        group_ids.insert(g.id, local.id);
        let can = canonical(&local.sync_id, &g.sync_id);
        if remote_wins(g.updated_at, &g.sync_id, local.updated_at, &local.sync_id)
            && (local.name != g.name || local.color != g.color || local.sort_order != g.sort_order)
        {
            let updated = HostGroup {
                name: g.name.clone(),
                color: g.color,
                sort_order: g.sort_order,
                sync_id: can,
                updated_at: stamp(g.updated_at, local.updated_at),
                ..local
            };
            // A rename onto an existing name fails on the unique index: skipped, as on Android.
            if s.update(&updated).is_ok() {
                st.updated += 1;
            }
        } else if local.sync_id != can {
            s.set_sync_id("host_group", local.id, &can)?;
        }
    }
    let local_group = |remote_id: Option<i64>| -> Result<Option<i64>> {
        let Some(rid) = remote_id else { return Ok(None) };
        if let Some(id) = group_ids.get(&rid) {
            return Ok(Some(*id));
        }
        match remote.groups.iter().find(|g| g.id == rid) {
            Some(g) => Ok(s.group_by_name(&g.name)?.map(|l| l.id)),
            None => Ok(None),
        }
    };

    // 3. Keys.
    let mut key_ids: HashMap<i64, i64> = HashMap::new();
    for k in &remote.keys {
        if deleted(&k.sync_id, k.updated_at) {
            continue;
        }
        let local = match s.by_sync_id::<SshKey>(&k.sync_id)? {
            Some(l) => Some(l),
            None => s.key_by_fingerprint(&k.fingerprint)?,
        };
        let Some(local) = local else {
            key_ids.insert(k.id, s.insert(&SshKey { id: 0, ..k.clone() })?);
            st.added += 1;
            revived(&k.sync_id)?;
            continue;
        };
        key_ids.insert(k.id, local.id);
        let can = canonical(&local.sync_id, &k.sync_id);
        let candidate = SshKey {
            id: local.id,
            created_at: local.created_at,
            sync_id: can.clone(),
            updated_at: stamp(k.updated_at, local.updated_at),
            ..k.clone()
        };
        let strip = |x: &SshKey| SshKey {
            updated_at: 0,
            sync_id: String::new(),
            ..x.clone()
        };
        if remote_wins(k.updated_at, &k.sync_id, local.updated_at, &local.sync_id) && strip(&candidate) != strip(&local) {
            if s.update(&candidate).is_ok() {
                st.updated += 1;
            }
        } else if local.sync_id != can {
            s.set_sync_id("ssh_key", local.id, &can)?;
        }
    }

    // 4. Identities.
    let mut identity_ids: HashMap<i64, i64> = HashMap::new();
    let identities: Vec<Identity> = s.all()?;
    for i in &remote.identities {
        if deleted(&i.sync_id, i.updated_at) {
            continue;
        }
        let local = match s.by_sync_id::<Identity>(&i.sync_id)? {
            Some(l) => Some(l),
            None => identities.iter().find(|l| l.name == i.name && l.username == i.username).cloned(),
        };
        let key_id = i.key_id.and_then(|k| key_ids.get(&k).copied());
        let Some(local) = local else {
            identity_ids.insert(
                i.id,
                s.upsert(&Identity {
                    id: 0,
                    key_id,
                    ..i.clone()
                })?,
            );
            st.added += 1;
            revived(&i.sync_id)?;
            continue;
        };
        identity_ids.insert(i.id, local.id);
        let can = canonical(&local.sync_id, &i.sync_id);
        let candidate = Identity {
            id: local.id,
            key_id,
            sync_id: can.clone(),
            updated_at: stamp(i.updated_at, local.updated_at),
            ..i.clone()
        };
        let strip = |x: &Identity| Identity {
            updated_at: 0,
            sync_id: String::new(),
            ..x.clone()
        };
        if remote_wins(i.updated_at, &i.sync_id, local.updated_at, &local.sync_id) && strip(&candidate) != strip(&local) {
            s.upsert(&candidate)?;
            st.updated += 1;
        } else if local.sync_id != can {
            s.set_sync_id("identity", local.id, &can)?;
        }
    }

    // 5. Hosts, jump targets before the hosts that use them.
    let mut host_ids: HashMap<i64, i64> = HashMap::new();
    let remote_hosts: HashMap<i64, &Host> = remote.hosts.iter().map(|h| (h.id, h)).collect();
    for h in order_by_jump(&remote.hosts) {
        if deleted(&h.sync_id, h.updated_at) {
            continue;
        }
        let local = match s.by_sync_id::<Host>(&h.sync_id)? {
            Some(l) => Some(l),
            None => s.host_by_alias(&h.alias)?,
        };
        let jump = match h.jump_host_id {
            None => None,
            Some(j) => match host_ids.get(&j) {
                Some(id) => Some(*id),
                None => match remote_hosts.get(&j) {
                    Some(rj) => s.by_sync_id::<Host>(&rj.sync_id)?.map(|l| l.id),
                    None => None,
                },
            },
        };
        let mapped = Host {
            group_id: local_group(h.group_id)?,
            key_id: h.key_id.and_then(|k| key_ids.get(&k).copied()),
            identity_id: h.identity_id.and_then(|i| identity_ids.get(&i).copied()),
            jump_host_id: jump,
            ..h.clone()
        };
        let Some(local) = local else {
            host_ids.insert(
                h.id,
                s.upsert(&Host {
                    id: 0,
                    last_connected_at: None,
                    os_id: None,
                    ..mapped
                })?,
            );
            st.added += 1;
            revived(&h.sync_id)?;
            continue;
        };
        host_ids.insert(h.id, local.id);
        let can = canonical(&local.sync_id, &h.sync_id);
        let candidate = Host {
            id: local.id,
            last_connected_at: local.last_connected_at,
            os_id: local.os_id.clone(),
            created_at: local.created_at,
            sync_id: can.clone(),
            updated_at: stamp(h.updated_at, local.updated_at),
            ..mapped
        };
        let wins = remote_wins(h.updated_at, &h.sync_id, local.updated_at, &local.sync_id);
        if wins && !same_host(&candidate, &local) {
            s.upsert(&candidate)?;
            st.updated += 1;
        } else if local.sync_id != can {
            // Same content: only the shared id may need to converge.
            s.set_sync_id("host", local.id, &can)?;
        }
    }

    // 6. Port forwards (need their host).
    let forwards: Vec<PortForward> = s.all()?;
    for f in &remote.forwards {
        if deleted(&f.sync_id, f.updated_at) {
            continue;
        }
        let Some(&host_id) = host_ids.get(&f.host_id) else { continue };
        let local = match s.by_sync_id::<PortForward>(&f.sync_id)? {
            Some(l) => Some(l),
            None => forwards.iter().find(|l| l.name == f.name && l.host_id == host_id).cloned(),
        };
        let Some(local) = local else {
            s.upsert(&PortForward {
                id: 0,
                host_id,
                ..f.clone()
            })?;
            st.added += 1;
            revived(&f.sync_id)?;
            continue;
        };
        let can = canonical(&local.sync_id, &f.sync_id);
        let candidate = PortForward {
            id: local.id,
            host_id,
            sync_id: can.clone(),
            updated_at: stamp(f.updated_at, local.updated_at),
            ..f.clone()
        };
        let strip = |x: &PortForward| PortForward {
            updated_at: 0,
            sync_id: String::new(),
            ..x.clone()
        };
        if remote_wins(f.updated_at, &f.sync_id, local.updated_at, &local.sync_id) && strip(&candidate) != strip(&local) {
            s.upsert(&candidate)?;
            st.updated += 1;
        } else if local.sync_id != can {
            s.set_sync_id("port_forward", local.id, &can)?;
        }
    }

    // 7. Snippets.
    let snippets: Vec<Snippet> = s.all()?;
    for sn in &remote.snippets {
        if deleted(&sn.sync_id, sn.updated_at) {
            continue;
        }
        let local = match s.by_sync_id::<Snippet>(&sn.sync_id)? {
            Some(l) => Some(l),
            None => snippets.iter().find(|l| l.name == sn.name && l.command == sn.command).cloned(),
        };
        let Some(local) = local else {
            s.upsert(&Snippet {
                id: 0,
                run_count: 0,
                ..sn.clone()
            })?;
            st.added += 1;
            revived(&sn.sync_id)?;
            continue;
        };
        let can = canonical(&local.sync_id, &sn.sync_id);
        let candidate = Snippet {
            id: local.id,
            run_count: local.run_count,
            created_at: local.created_at,
            sync_id: can.clone(),
            updated_at: stamp(sn.updated_at, local.updated_at),
            ..sn.clone()
        };
        let strip = |x: &Snippet| Snippet {
            updated_at: 0,
            sync_id: String::new(),
            ..x.clone()
        };
        if remote_wins(sn.updated_at, &sn.sync_id, local.updated_at, &local.sync_id) && strip(&candidate) != strip(&local) {
            s.upsert(&candidate)?;
            st.updated += 1;
        } else if local.sync_id != can {
            s.set_sync_id("snippet", local.id, &can)?;
        }
    }

    // 8. Trusted host keys: the newest trust wins.
    let local_known: HashMap<(String, i32, String), KnownHost> = s
        .all::<KnownHost>()?
        .into_iter()
        .map(|k| ((k.host.clone(), k.port, k.key_type.clone()), k))
        .collect();
    for k in &remote.known_hosts {
        if deleted(&known_host_tombstone_id(&k.host, k.port, &k.key_type), k.added_at) {
            continue;
        }
        match local_known.get(&(k.host.clone(), k.port, k.key_type.clone())) {
            None => {
                s.insert_or_ignore(&KnownHost { id: 0, ..k.clone() })?;
                st.added += 1;
            }
            Some(mine) if k.added_at > mine.added_at && (k.fingerprint != mine.fingerprint || k.public_key != mine.public_key) => {
                s.replace_known_host(&KnownHost { id: 0, ..k.clone() })?;
                st.updated += 1;
            }
            Some(_) => {}
        }
    }

    // Tombstones older than 90 days are forgotten: every device has seen them by then.
    s.prune_tombstones(now_ms() - 90 * 24 * 3600 * 1000)?;
    Ok(st)
}

/// This database for the other devices (the whole state, tombstones included).
pub fn snapshot(db: &Db, devices: Vec<SyncDevice>) -> Result<BackupData> {
    let s = db.store();
    Ok(BackupData {
        groups: s.all()?,
        hosts: s.all()?,
        keys: s.all()?,
        identities: s.all()?,
        known_hosts: s.all()?,
        snippets: s.all()?,
        forwards: s.all()?,
        tombstones: s.tombstones()?,
        devices,
        ..BackupData::default()
    })
}

#[cfg(test)]
mod tests {
    //! The Android SyncMergeTest, on two in-memory « devices » with the real triggers.
    use super::*;

    fn tick() {
        std::thread::sleep(std::time::Duration::from_millis(5));
    }

    fn pull(to: &mut Db, from: &Db) -> Stats {
        merge(to, &snapshot(from, vec![]).unwrap()).unwrap()
    }

    fn round(a: &mut Db, b: &mut Db) {
        pull(a, b);
        pull(b, a);
    }

    fn hosts(db: &Db) -> Vec<Host> {
        let mut h: Vec<Host> = db.store().all().unwrap();
        h.sort_by(|x, y| x.alias.cmp(&y.alias));
        h
    }

    fn two() -> (Db, Db) {
        (Db::in_memory().unwrap(), Db::in_memory().unwrap())
    }

    #[test]
    fn triggers_stamp_edits_but_not_local_state() {
        let (a, _) = two();
        let s = a.store();
        let id = s.upsert(&Host::new("nas", "10.0.0.2")).unwrap();
        let h: Host = s.get(id).unwrap().unwrap();
        assert_eq!(h.sync_id.len(), 32);
        assert!(h.updated_at > 1);
        tick();
        a.conn.execute("UPDATE host SET lastConnectedAt = 123 WHERE id = ?1", [id]).unwrap();
        assert_eq!(s.get::<Host>(id).unwrap().unwrap().updated_at, h.updated_at);
        tick();
        s.upsert(&Host {
            alias: "nas2".into(),
            ..s.get::<Host>(id).unwrap().unwrap()
        })
        .unwrap();
        assert!(s.get::<Host>(id).unwrap().unwrap().updated_at > h.updated_at);
        s.delete::<Host>(id).unwrap();
        assert_eq!(s.tombstones().unwrap()[0].sync_id, h.sync_id);
    }

    #[test]
    fn new_hosts_and_edits_travel_both_ways() {
        let (mut a, mut b) = two();
        let g = a
            .store()
            .insert(&HostGroup {
                id: 0,
                name: "Homelab".into(),
                ..serde_json::from_str(r#"{"name":"x"}"#).unwrap()
            })
            .unwrap();
        a.store()
            .upsert(&Host {
                group_id: Some(g),
                ..Host::new("nas", "10.0.0.2")
            })
            .unwrap();
        round(&mut a, &mut b);
        let on_b = hosts(&b).remove(0);
        assert_eq!(on_b.hostname, "10.0.0.2");
        let groups: Vec<HostGroup> = b.store().all().unwrap();
        assert_eq!(groups.iter().find(|x| Some(x.id) == on_b.group_id).unwrap().name, "Homelab");
        tick();
        b.store()
            .upsert(&Host {
                hostname: "10.0.0.3".into(),
                ..on_b
            })
            .unwrap();
        round(&mut a, &mut b);
        assert_eq!(hosts(&a)[0].hostname, "10.0.0.3");
        assert_eq!(hosts(&a)[0].sync_id, hosts(&b)[0].sync_id);
    }

    #[test]
    fn latest_change_wins() {
        let (mut a, mut b) = two();
        a.store().upsert(&Host::new("vps", "1.1.1.1")).unwrap();
        round(&mut a, &mut b);
        b.store()
            .upsert(&Host {
                port: 2222,
                ..hosts(&b).remove(0)
            })
            .unwrap();
        tick();
        a.store()
            .upsert(&Host {
                port: 2200,
                ..hosts(&a).remove(0)
            })
            .unwrap();
        round(&mut a, &mut b);
        assert_eq!(hosts(&a)[0].port, 2200);
        assert_eq!(hosts(&b)[0].port, 2200);
    }

    #[test]
    fn deletions_propagate_and_a_later_edit_revives() {
        let (mut a, mut b) = two();
        let snip: Snippet = serde_json::from_str(r#"{"name":"df","command":"df -h"}"#).unwrap();
        a.store().upsert(&snip).unwrap();
        a.store().upsert(&Host::new("old", "x")).unwrap();
        round(&mut a, &mut b);
        tick();
        a.store().delete::<Host>(hosts(&a)[0].id).unwrap();
        round(&mut a, &mut b);
        assert!(hosts(&b).is_empty());
        // Edited on B after the deletion: comes back everywhere.
        let on_b: Snippet = b.store().all::<Snippet>().unwrap().remove(0);
        a.store().delete::<Snippet>(a.store().all::<Snippet>().unwrap()[0].id).unwrap();
        tick();
        b.store()
            .upsert(&Snippet {
                command: "df -h /".into(),
                ..on_b.clone()
            })
            .unwrap();
        round(&mut a, &mut b);
        assert_eq!(a.store().all::<Snippet>().unwrap()[0].command, "df -h /");
        assert!(a.store().tombstones().unwrap().iter().all(|t| t.sync_id != on_b.sync_id));
    }

    #[test]
    fn same_host_on_both_devices_converges() {
        let (mut a, mut b) = two();
        a.store()
            .upsert(&Host {
                username: "pi".into(),
                ..Host::new("pi", "192.168.1.5")
            })
            .unwrap();
        b.store()
            .upsert(&Host {
                username: "pi".into(),
                ..Host::new("pi", "192.168.1.6")
            })
            .unwrap();
        // Data from before synchronisation existed: same age on both sides.
        a.conn.execute("UPDATE host SET updatedAt = 1", []).unwrap();
        b.conn.execute("UPDATE host SET updatedAt = 1", []).unwrap();
        round(&mut a, &mut b);
        round(&mut a, &mut b);
        assert_eq!(hosts(&a).len(), 1);
        assert_eq!(hosts(&a)[0].hostname, hosts(&b)[0].hostname);
        assert_eq!(hosts(&a)[0].sync_id, hosts(&b)[0].sync_id);
    }

    #[test]
    fn local_state_is_kept_and_jump_hosts_are_linked() {
        let (mut a, mut b) = two();
        let bastion = a.store().upsert(&Host::new("bastion", "b.example")).unwrap();
        a.store()
            .upsert(&Host {
                jump_host_id: Some(bastion),
                ..Host::new("inner", "10.1.0.4")
            })
            .unwrap();
        round(&mut a, &mut b);
        let inner_b = hosts(&b).into_iter().find(|h| h.alias == "inner").unwrap();
        assert_eq!(
            hosts(&b).iter().find(|h| h.alias == "bastion").unwrap().id,
            inner_b.jump_host_id.unwrap()
        );
        b.conn
            .execute("UPDATE host SET lastConnectedAt = 42 WHERE id = ?1", [inner_b.id])
            .unwrap();
        tick();
        let inner_a = hosts(&a).into_iter().find(|h| h.alias == "inner").unwrap();
        a.store()
            .upsert(&Host {
                tags: "prod".into(),
                ..inner_a
            })
            .unwrap();
        round(&mut a, &mut b);
        let after = hosts(&b).into_iter().find(|h| h.alias == "inner").unwrap();
        assert_eq!(after.tags, "prod");
        assert_eq!(after.last_connected_at, Some(42));
        assert_ne!(after.updated_at, 0);
    }

    #[test]
    fn nothing_changes_when_already_in_sync() {
        let (mut a, mut b) = two();
        a.store().upsert(&Host::new("nas", "n")).unwrap();
        round(&mut a, &mut b);
        assert!(!pull(&mut a, &b).changed());
        assert!(!pull(&mut b, &a).changed());
    }

    #[test]
    fn merges_the_android_fixture_and_keeps_everything() {
        let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../tests/fixtures/android-backup.json");
        let remote: BackupData = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let mut db = Db::in_memory().unwrap();
        let st = merge(&mut db, &remote).unwrap();
        assert!(st.added > 10);
        let back = snapshot(&db, vec![]).unwrap();
        assert_eq!(back.hosts.len(), remote.hosts.len());
        let nas = back.hosts.iter().find(|h| h.alias == "nas").unwrap();
        let docker = back.hosts.iter().find(|h| h.alias == "docker-01").unwrap();
        assert_eq!(nas.jump_host_id, Some(docker.id));
        assert_eq!(nas.password.as_deref(), Some("pä$$wörd \"quoted\""));
        let pc = back.hosts.iter().find(|h| h.alias == "pc-bureau").unwrap();
        assert_eq!(pc.rdp_resolution.as_deref(), Some("1920x1080"));
        assert!(pc.rdp_console && !pc.rdp_audio);
        // Merging the same data again changes nothing.
        assert!(!merge(&mut db, &remote).unwrap().changed());
    }
}
