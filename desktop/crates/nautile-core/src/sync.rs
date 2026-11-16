//! Merges another device's data into this database, record by record — a port of the Android app's
//! core/data/.../sync/SyncMerge.kt, so both sides reach the same state:
//! - records are matched by sync id, or by a natural key (alias, group name, key fingerprint…) the first time two
//!   devices meet, in which case both adopt the smaller sync id. A local row the other device already knows under its
//!   own sync id is never matched by natural key: it is another record (renamed there, while a new one took its name);
//! - the most recent change wins (`updatedAt`; ties broken by sync id, then by the content's hash). The hash depends on
//!   the columns a version synchronises, so a tie kept here is moved one past: the other device then takes it by its
//!   date, whatever its own version would pick. Only one side of an exchange may do so ([merge_ties]), or two
//!   versions picking differently would move theirs past each other's forever: the one merging first (the Wi-Fi
//!   responder, which answers with its data merged), not the one merging what comes back;
//! - stamps from the other device are believed up to a year ahead of this clock (db::stamp_cap): later ones,
//!   tombstones included, are brought back to that (only past db::MAX_STAMP with a clock known to be wrong);
//! - a deletion wins over changes made before it, and a change made after a deletion brings the record back;
//! - two different groups wanting the same name: the smaller sync id keeps it, the other becomes « name (2) ».
//!
//! Local-only fields (last connection, detected OS, run count) are never taken from the other side.
//!
//! The synchronisation triggers stand aside during a merge (`sync_ctl.merging`): every updatedAt and tombstone
//! written here is the other device's, or set explicitly (a group renamed « name (2) » is a new edit).

use std::collections::{HashMap, HashSet};

use serde_json::{Map, Value};
use sha2::{Digest, Sha256};

use crate::db::{Db, Record, Result, SYNCED, Store, bump, stamp_cap};
use crate::model::{
    BackupData, Host, HostGroup, Identity, KnownHost, PortForward, Snippet, SshKey, SyncDevice, known_host_tombstone_id, now_ms,
};

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Stats {
    pub added: usize,
    /// Records rewritten, including those whose sync id only converged (a cached copy is then stale too).
    pub updated: usize,
    pub deleted: usize,
}

impl Stats {
    pub fn changed(&self) -> bool {
        self.added + self.updated + self.deleted > 0
    }
}

/// Synced tables and their columns (db::SYNCED; another list only to test another version's choice).
type Columns<'a> = &'a [(&'a str, &'a [&'a str])];

/// Which version of a record found on both sides is kept.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Keep {
    Remote,
    Local,
    LocalByHash,
}

/// Which version is kept when they `differ`: the latest change, then the smaller sync id, then (one record changed on
/// two devices with the same updatedAt, e.g. both moved one past a stamp from the future) the smaller content hash
/// ([tie], computed only then).
fn decide(differ: bool, r_at: i64, r_id: &str, l_at: i64, l_id: &str, tie: impl FnOnce() -> Result<bool>) -> Result<Keep> {
    Ok(if !differ {
        Keep::Local
    } else if r_at != l_at {
        if r_at > l_at { Keep::Remote } else { Keep::Local }
    } else if r_id != l_id {
        if r_id < l_id { Keep::Remote } else { Keep::Local }
    } else if tie()? {
        Keep::Remote
    } else {
        Keep::LocalByHash
    })
}

/// The tie-break of [decide]: whether `candidate` (the remote content on the local row) hashes before `local`.
fn tie<R: Record>(s: &Store<'_>, columns: Columns<'_>, candidate: &R, local: &R) -> Result<bool> {
    Ok(record_hash_with(s, columns, candidate)? < record_hash_with(s, columns, local)?)
}

/// The local version of a record was kept (row `id` of `table`, `sync_id`, `at`): its sync id converges to `can`; a
/// tie it won by its hash is moved one past (`m.move_ties`), so the other device takes it by its date whatever
/// columns its own version hashes (same sync id then).
#[allow(clippy::too_many_arguments)]
fn keep_local(
    s: &Store<'_>,
    m: &Merging<'_>,
    st: &mut Stats,
    keep: Keep,
    table: &str,
    id: i64,
    sync_id: &str,
    at: i64,
    can: &str,
) -> Result<()> {
    if keep == Keep::LocalByHash && m.move_ties {
        s.restamp(table, id, bump(at))?;
        st.updated += 1;
    } else if sync_id != can {
        s.set_sync_id(table, id, can)?;
        st.updated += 1;
    }
    Ok(())
}

/// updatedAt of a group renamed « name (2) » here: a new edit, after both versions, so it reaches the other devices.
fn renamed_at(at: i64) -> i64 {
    now_ms().max(bump(at))
}

/// `remote` with its stamps believed up to `cap`: a later updatedAt / addedAt / deletedAt is brought back to it (one
/// from a clock far ahead, or past what an integer holds, would win over every later edit).
fn bounded(remote: &BackupData, cap: i64) -> BackupData {
    let mut r = remote.clone();
    r.groups.iter_mut().for_each(|x| x.updated_at = x.updated_at.min(cap));
    r.hosts.iter_mut().for_each(|x| x.updated_at = x.updated_at.min(cap));
    r.keys.iter_mut().for_each(|x| x.updated_at = x.updated_at.min(cap));
    r.identities.iter_mut().for_each(|x| x.updated_at = x.updated_at.min(cap));
    r.known_hosts.iter_mut().for_each(|x| x.added_at = x.added_at.min(cap));
    r.snippets.iter_mut().for_each(|x| x.updated_at = x.updated_at.min(cap));
    r.forwards.iter_mut().for_each(|x| x.updated_at = x.updated_at.min(cap));
    r.tombstones.iter_mut().for_each(|t| t.deleted_at = t.deleted_at.min(cap));
    r
}

/// The canonical content of a record of `table`: its synchronised columns (db::SYNCED, in that order), each written
/// `name=value\n` with value `N` (null or missing), `I<decimal>` (number; booleans as 1 or 0) or
/// `T<UTF-8 length>:<text>`; a reference (db::REFS) is the referenced record's sync id ([refs], by column; missing:
/// null), row ids differing between devices. Hashed with SHA-256, lower-case hex. SyncMerge.contentHash, byte for byte.
pub fn content_hash(table: &str, fields: &Map<String, Value>, refs: &HashMap<String, String>) -> String {
    content_hash_with(SYNCED, table, fields, refs)
}

/// [content_hash] over the columns of `table` in `columns`.
fn content_hash_with(columns: Columns<'_>, table: &str, fields: &Map<String, Value>, refs: &HashMap<String, String>) -> String {
    let columns = columns.iter().find(|(t, _)| *t == table).map(|(_, c)| *c).unwrap_or(&[]);
    let ref_columns = crate::db::refs(table);
    let mut out = String::new();
    for c in columns {
        out.push_str(c);
        out.push('=');
        let text = |out: &mut String, s: &str| {
            out.push('T');
            out.push_str(&s.len().to_string());
            out.push(':');
            out.push_str(s);
        };
        if ref_columns.iter().any(|(rc, _)| rc == c) {
            match refs.get(*c) {
                Some(id) => text(&mut out, id),
                None => out.push('N'),
            }
        } else {
            match fields.get(*c) {
                None | Some(Value::Null) => out.push('N'),
                Some(Value::Bool(b)) => out.push_str(if *b { "I1" } else { "I0" }),
                Some(Value::Number(n)) => {
                    out.push('I');
                    out.push_str(&n.as_i64().map(|i| i.to_string()).unwrap_or_else(|| n.to_string()));
                }
                Some(Value::String(v)) => text(&mut out, v),
                Some(other) => text(&mut out, &other.to_string()),
            }
        }
        out.push('\n');
    }
    Sha256::digest(out.as_bytes()).iter().map(|b| format!("{b:02x}")).collect()
}

/// [content_hash] of `r`, its references looked up here.
pub fn record_hash<R: Record>(s: &Store<'_>, r: &R) -> Result<String> {
    record_hash_with(s, SYNCED, r)
}

/// [record_hash] over the columns of its table in `columns`.
fn record_hash_with<R: Record>(s: &Store<'_>, columns: Columns<'_>, r: &R) -> Result<String> {
    let Value::Object(fields) = serde_json::to_value(r).expect("records serialise") else {
        unreachable!()
    };
    let mut refs = HashMap::new();
    for (column, table) in crate::db::refs(R::TABLE) {
        if let Some(id) = fields.get(*column).and_then(Value::as_i64)
            && let Some(sync_id) = s.sync_id_of(table, id)?
        {
            refs.insert((*column).to_owned(), sync_id);
        }
    }
    Ok(content_hash_with(columns, R::TABLE, &fields, &refs))
}

/// The local row of a remote record: [by_sync_id] (the row with its sync id), else [by_natural_key] if the other
/// device does not know that row ([remote_ids], `None`: no such check) and no other remote record took it in this
/// merge ([claimed]). The row found is claimed.
fn find_local<T: crate::db::Record>(
    by_sync_id: Option<T>,
    by_natural_key: Option<T>,
    remote_ids: Option<&HashSet<String>>,
    claimed: &mut HashSet<i64>,
    sync_id: impl Fn(&T) -> &str,
) -> Option<T> {
    let local = by_sync_id
        .or_else(|| by_natural_key.filter(|l| remote_ids.is_none_or(|ids| !ids.contains(sync_id(l))) && !claimed.contains(&l.id())));
    if let Some(l) = &local {
        claimed.insert(l.id());
    }
    local
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

/// How a merge decides ties.
struct Merging<'a> {
    /// The columns hashed to break a tie: db::SYNCED, another list only to test another version's choice.
    columns: Columns<'a>,
    /// Whether a tie kept by its hash is moved one past ([merge_ties]).
    move_ties: bool,
}

/// Pulls `remote` into `db`, in one transaction, the synchronisation triggers put aside meanwhile.
pub fn merge(db: &mut Db, remote: &BackupData) -> Result<Stats> {
    merge_ties(db, remote, true)
}

/// [merge]; `move_ties`: false when `remote` already merged this device's data (the Wi-Fi initiator, merging the
/// responder's answer): a tie still there is the other version's choice, kept by its date there; moving this one
/// past would only start them over (SyncMerge.merge moveTies).
pub fn merge_ties(db: &mut Db, remote: &BackupData, move_ties: bool) -> Result<Stats> {
    merge_with(
        db,
        remote,
        &Merging {
            columns: SYNCED,
            move_ties,
        },
    )
}

/// [merge], ties decided as `m` says.
fn merge_with(db: &mut Db, remote: &BackupData, m: &Merging<'_>) -> Result<Stats> {
    db.transaction(|s| {
        // Local stamps from too far ahead first: they would win over every incoming change.
        let now = now_ms();
        s.repair_stamps(now)?;
        s.set_merging(true)?;
        let out = merge_in(s, &bounded(remote, stamp_cap(now)), m);
        // Also undone by the rollback on an error; reset anyway, and it must succeed before a commit.
        let reset = s.set_merging(false);
        let st = out?;
        reset?;
        Ok(st)
    })
}

fn merge_in(s: Store<'_>, remote: &BackupData, m: &Merging<'_>) -> Result<Stats> {
    let columns = m.columns;
    let mut st = Stats::default();

    // 1. Deletions made on the other device.
    let before: HashMap<String, i64> = s.tombstones()?.into_iter().map(|t| (t.sync_id, t.deleted_at)).collect();
    for t in &remote.tombstones {
        let n = s.delete_synced(&t.kind, &t.sync_id, t.deleted_at)?;
        st.deleted += n;
        // Keep the original deletion time.
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

    // Every sync id the other device knows, alive or deleted: a local row it knows under its own sync id is never
    // matched by natural key (it is another record, renamed there while a new one took its name).
    let remote_ids: HashSet<String> = remote
        .groups
        .iter()
        .map(|x| &x.sync_id)
        .chain(remote.hosts.iter().map(|x| &x.sync_id))
        .chain(remote.keys.iter().map(|x| &x.sync_id))
        .chain(remote.identities.iter().map(|x| &x.sync_id))
        .chain(remote.snippets.iter().map(|x| &x.sync_id))
        .chain(remote.forwards.iter().map(|x| &x.sync_id))
        .chain(remote.tombstones.iter().map(|x| &x.sync_id))
        .cloned()
        .collect();

    // 2. Groups. Names are unique: records known by sync id go first, so a rename frees its old name before a new
    // group takes it. Two different groups wanting one name: the smaller sync id keeps it, the other becomes « name (2) ».
    let free_name = |name: &str| -> Result<String> {
        let mut n = 2;
        while s.group_by_name(&format!("{name} ({n})"))?.is_some() {
            n += 1;
        }
        Ok(format!("{name} ({n})"))
    };
    // The name group [sync_id] (row [self_id]) may take: [name], unless a group with a smaller sync id holds it.
    let claim_name = |name: &str, sync_id: &str, self_id: Option<i64>| -> Result<String> {
        let Some(other) = s.group_by_name(name)?.filter(|o| Some(o.id) != self_id) else {
            return Ok(name.to_owned());
        };
        if sync_id < other.sync_id.as_str() {
            // A new edit, which then reaches the other devices.
            s.update(&HostGroup {
                name: free_name(name)?,
                updated_at: renamed_at(other.updated_at),
                ..other
            })?;
            return Ok(name.to_owned());
        }
        free_name(name)
    };
    let mut group_ids: HashMap<i64, i64> = HashMap::new();
    let mut claimed_groups: HashSet<i64> = HashSet::new();
    let mut ordered: Vec<(bool, &HostGroup)> = Vec::new();
    for g in &remote.groups {
        ordered.push((s.by_sync_id::<HostGroup>(&g.sync_id)?.is_none(), g));
    }
    ordered.sort_by_key(|(unknown, _)| *unknown);
    for (_, g) in ordered {
        if deleted(&g.sync_id, g.updated_at) {
            continue;
        }
        let by_id = s.by_sync_id::<HostGroup>(&g.sync_id)?;
        let by_name = if by_id.is_none() { s.group_by_name(&g.name)? } else { None };
        let Some(local) = find_local(by_id, by_name, Some(&remote_ids), &mut claimed_groups, |l| &l.sync_id) else {
            let name = claim_name(&g.name, &g.sync_id, None)?;
            // Renamed here: a new edit, so the new name goes back to the other device.
            let updated_at = if name == g.name { g.updated_at } else { renamed_at(g.updated_at) };
            if let Some(id) = s.insert_or_ignore(&HostGroup {
                id: 0,
                name,
                updated_at,
                ..g.clone()
            })? {
                group_ids.insert(g.id, id);
                claimed_groups.insert(id);
                st.added += 1;
                revived(&g.sync_id)?;
            }
            continue;
        };
        group_ids.insert(g.id, local.id);
        let can = canonical(&local.sync_id, &g.sync_id);
        let updated = HostGroup {
            name: g.name.clone(),
            color: g.color,
            sort_order: g.sort_order,
            icon: g.icon.clone(),
            sync_id: can.clone(),
            updated_at: g.updated_at,
            ..local.clone()
        };
        let differs = local.name != g.name || local.color != g.color || local.sort_order != g.sort_order || local.icon != g.icon;
        let keep = decide(differs, g.updated_at, &g.sync_id, local.updated_at, &local.sync_id, || {
            tie(&s, columns, &updated, &local)
        })?;
        if keep == Keep::Remote {
            let name = claim_name(&updated.name, &updated.sync_id, Some(local.id))?;
            let updated = if name == updated.name {
                updated
            } else {
                // Renamed here: a new edit, so the new name goes back.
                HostGroup {
                    name,
                    updated_at: renamed_at(g.updated_at.max(local.updated_at)),
                    ..updated
                }
            };
            // A constraint refusing the write skips this record, as on Android.
            if s.update(&updated).is_ok() {
                st.updated += 1;
            }
        } else {
            keep_local(&s, m, &mut st, keep, "host_group", local.id, &local.sync_id, local.updated_at, &can)?;
        }
    }
    // Only by sync id: a group with the same name may be another record.
    let local_group = |remote_id: Option<i64>| -> Result<Option<i64>> {
        let Some(rid) = remote_id else { return Ok(None) };
        if let Some(id) = group_ids.get(&rid) {
            return Ok(Some(*id));
        }
        match remote.groups.iter().find(|g| g.id == rid) {
            Some(g) => Ok(s.by_sync_id::<HostGroup>(&g.sync_id)?.map(|l| l.id)),
            None => Ok(None),
        }
    };

    // 3. Keys. The fingerprint is the key itself: the same key on both sides is one record whatever its sync ids
    // (the unique index allows no other choice).
    let mut key_ids: HashMap<i64, i64> = HashMap::new();
    let mut claimed_keys: HashSet<i64> = HashSet::new();
    for k in &remote.keys {
        if deleted(&k.sync_id, k.updated_at) {
            continue;
        }
        let by_id = s.by_sync_id::<SshKey>(&k.sync_id)?;
        let by_print = if by_id.is_none() {
            s.key_by_fingerprint(&k.fingerprint)?
        } else {
            None
        };
        let Some(local) = find_local(by_id, by_print, None, &mut claimed_keys, |l| &l.sync_id) else {
            let id = s.insert(&SshKey { id: 0, ..k.clone() })?;
            key_ids.insert(k.id, id);
            claimed_keys.insert(id);
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
            updated_at: k.updated_at,
            ..k.clone()
        };
        let strip = |x: &SshKey| SshKey {
            updated_at: 0,
            sync_id: String::new(),
            ..x.clone()
        };
        let keep = decide(
            strip(&candidate) != strip(&local),
            k.updated_at,
            &k.sync_id,
            local.updated_at,
            &local.sync_id,
            || tie(&s, columns, &candidate, &local),
        )?;
        if keep == Keep::Remote {
            if s.update(&candidate).is_ok() {
                st.updated += 1;
            }
        } else {
            keep_local(&s, m, &mut st, keep, "ssh_key", local.id, &local.sync_id, local.updated_at, &can)?;
        }
    }

    // 4. Identities. From here, natural keys are looked up in a list read once: a row written meanwhile is claimed,
    // never looked up again.
    let mut identity_ids: HashMap<i64, i64> = HashMap::new();
    let mut claimed_identities: HashSet<i64> = HashSet::new();
    let identities: Vec<Identity> = s.all()?;
    for i in &remote.identities {
        if deleted(&i.sync_id, i.updated_at) {
            continue;
        }
        let by_id = s.by_sync_id::<Identity>(&i.sync_id)?;
        let by_name = identities
            .iter()
            .find(|l| {
                l.name == i.name && l.username == i.username && !remote_ids.contains(&l.sync_id) && !claimed_identities.contains(&l.id)
            })
            .cloned();
        let key_id = i.key_id.and_then(|k| key_ids.get(&k).copied());
        let Some(local) = find_local(by_id, by_name, Some(&remote_ids), &mut claimed_identities, |l| &l.sync_id) else {
            let id = s.upsert(&Identity {
                id: 0,
                key_id,
                ..i.clone()
            })?;
            identity_ids.insert(i.id, id);
            claimed_identities.insert(id);
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
            updated_at: i.updated_at,
            ..i.clone()
        };
        let strip = |x: &Identity| Identity {
            updated_at: 0,
            sync_id: String::new(),
            ..x.clone()
        };
        let keep = decide(
            strip(&candidate) != strip(&local),
            i.updated_at,
            &i.sync_id,
            local.updated_at,
            &local.sync_id,
            || tie(&s, columns, &candidate, &local),
        )?;
        if keep == Keep::Remote {
            s.upsert(&candidate)?;
            st.updated += 1;
        } else {
            keep_local(&s, m, &mut st, keep, "identity", local.id, &local.sync_id, local.updated_at, &can)?;
        }
    }

    // 5. Hosts, jump targets before the hosts that use them.
    let mut host_ids: HashMap<i64, i64> = HashMap::new();
    let mut claimed_hosts: HashSet<i64> = HashSet::new();
    let local_hosts: Vec<Host> = s.all()?;
    let remote_hosts: HashMap<i64, &Host> = remote.hosts.iter().map(|h| (h.id, h)).collect();
    for h in order_by_jump(&remote.hosts) {
        if deleted(&h.sync_id, h.updated_at) {
            continue;
        }
        let by_id = s.by_sync_id::<Host>(&h.sync_id)?;
        let by_alias = local_hosts
            .iter()
            .find(|l| l.alias == h.alias && !remote_ids.contains(&l.sync_id) && !claimed_hosts.contains(&l.id))
            .cloned();
        let local = find_local(by_id, by_alias, Some(&remote_ids), &mut claimed_hosts, |l| &l.sync_id);
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
            let id = s.upsert(&Host {
                id: 0,
                last_connected_at: None,
                ..mapped
            })?;
            host_ids.insert(h.id, id);
            claimed_hosts.insert(id);
            st.added += 1;
            revived(&h.sync_id)?;
            continue;
        };
        host_ids.insert(h.id, local.id);
        let can = canonical(&local.sync_id, &h.sync_id);
        let candidate = Host {
            id: local.id,
            last_connected_at: local.last_connected_at,
            // The system found by one device serves them all; each keeps its own once found.
            os_id: local.os_id.clone().or_else(|| h.os_id.clone()),
            created_at: local.created_at,
            sync_id: can.clone(),
            updated_at: h.updated_at,
            ..mapped
        };
        let keep = decide(
            !same_host(&candidate, &local),
            h.updated_at,
            &h.sync_id,
            local.updated_at,
            &local.sync_id,
            || tie(&s, columns, &candidate, &local),
        )?;
        if keep == Keep::Remote {
            s.upsert(&candidate)?;
            st.updated += 1;
        } else {
            if let (None, Some(os)) = (&local.os_id, &h.os_id) {
                // Not an edit: only the logo learnt from the other device (no new updatedAt).
                s.set_os(local.id, os)?;
            }
            // Only the shared id may need to converge, or a tie kept by its hash be moved one past.
            keep_local(&s, m, &mut st, keep, "host", local.id, &local.sync_id, local.updated_at, &can)?;
        }
    }

    // 6. Port forwards (need their host).
    let mut claimed_forwards: HashSet<i64> = HashSet::new();
    let forwards: Vec<PortForward> = s.all()?;
    for f in &remote.forwards {
        if deleted(&f.sync_id, f.updated_at) {
            continue;
        }
        let Some(&host_id) = host_ids.get(&f.host_id) else { continue };
        let by_id = s.by_sync_id::<PortForward>(&f.sync_id)?;
        let by_name = forwards
            .iter()
            .find(|l| l.name == f.name && l.host_id == host_id && !remote_ids.contains(&l.sync_id) && !claimed_forwards.contains(&l.id))
            .cloned();
        let Some(local) = find_local(by_id, by_name, Some(&remote_ids), &mut claimed_forwards, |l| &l.sync_id) else {
            let id = s.upsert(&PortForward {
                id: 0,
                host_id,
                ..f.clone()
            })?;
            claimed_forwards.insert(id);
            st.added += 1;
            revived(&f.sync_id)?;
            continue;
        };
        let can = canonical(&local.sync_id, &f.sync_id);
        let candidate = PortForward {
            id: local.id,
            host_id,
            sync_id: can.clone(),
            updated_at: f.updated_at,
            ..f.clone()
        };
        let strip = |x: &PortForward| PortForward {
            updated_at: 0,
            sync_id: String::new(),
            ..x.clone()
        };
        let keep = decide(
            strip(&candidate) != strip(&local),
            f.updated_at,
            &f.sync_id,
            local.updated_at,
            &local.sync_id,
            || tie(&s, columns, &candidate, &local),
        )?;
        if keep == Keep::Remote {
            s.upsert(&candidate)?;
            st.updated += 1;
        } else {
            keep_local(
                &s,
                m,
                &mut st,
                keep,
                "port_forward",
                local.id,
                &local.sync_id,
                local.updated_at,
                &can,
            )?;
        }
    }

    // 7. Snippets.
    let mut claimed_snippets: HashSet<i64> = HashSet::new();
    let snippets: Vec<Snippet> = s.all()?;
    for sn in &remote.snippets {
        if deleted(&sn.sync_id, sn.updated_at) {
            continue;
        }
        let by_id = s.by_sync_id::<Snippet>(&sn.sync_id)?;
        let by_text = snippets
            .iter()
            .find(|l| l.name == sn.name && l.command == sn.command && !remote_ids.contains(&l.sync_id) && !claimed_snippets.contains(&l.id))
            .cloned();
        let Some(local) = find_local(by_id, by_text, Some(&remote_ids), &mut claimed_snippets, |l| &l.sync_id) else {
            let id = s.upsert(&Snippet {
                id: 0,
                run_count: 0,
                ..sn.clone()
            })?;
            claimed_snippets.insert(id);
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
            updated_at: sn.updated_at,
            ..sn.clone()
        };
        let strip = |x: &Snippet| Snippet {
            updated_at: 0,
            sync_id: String::new(),
            ..x.clone()
        };
        let keep = decide(
            strip(&candidate) != strip(&local),
            sn.updated_at,
            &sn.sync_id,
            local.updated_at,
            &local.sync_id,
            || tie(&s, columns, &candidate, &local),
        )?;
        if keep == Keep::Remote {
            s.upsert(&candidate)?;
            st.updated += 1;
        } else {
            keep_local(&s, m, &mut st, keep, "snippet", local.id, &local.sync_id, local.updated_at, &can)?;
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

    // Tombstones older than 90 days are forgotten: every device has seen them by then. (Those dated more than a year
    // ahead were brought back before the merge, with Store::repair_stamps.)
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
    fn a_deletion_from_elsewhere_never_removes_a_local_shell() {
        // A copy made by an older version, which still synced local shells: same sync id on both devices.
        let (mut a, mut b) = two();
        let mut shell = Host::new("shell", "");
        shell.protocol = crate::model::Protocol::Local;
        a.store().upsert(&shell).unwrap();
        round(&mut a, &mut b);
        assert_eq!(hosts(&b).len(), 1);
        tick();
        a.store().delete::<Host>(hosts(&a)[0].id).unwrap();
        round(&mut a, &mut b);
        assert_eq!(hosts(&b).len(), 1, "B's own local shell stays");
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
    fn a_group_icon_is_an_edit_that_travels() {
        let (mut a, mut b) = two();
        let id = a
            .store()
            .insert(&serde_json::from_str::<HostGroup>(r#"{"name":"Homelab"}"#).unwrap())
            .unwrap();
        round(&mut a, &mut b);
        tick();
        a.conn.execute("UPDATE host_group SET icon = 'server' WHERE id = ?1", [id]).unwrap();
        round(&mut a, &mut b);
        let groups: Vec<HostGroup> = b.store().all().unwrap();
        assert_eq!(groups[0].icon.as_deref(), Some("server"));
    }

    #[test]
    fn a_detected_system_reaches_the_other_device_without_replacing_its_own() {
        let (mut a, mut b) = two();
        a.store().upsert(&Host::new("web", "10.0.0.8")).unwrap();
        a.store().upsert(&Host::new("db", "10.0.0.9")).unwrap();
        round(&mut a, &mut b);
        let web_b = hosts(&b).into_iter().find(|h| h.alias == "web").unwrap();
        b.conn.execute("UPDATE host SET osId = 'debian' WHERE id = ?1", [web_b.id]).unwrap();
        let db_a = hosts(&a).into_iter().find(|h| h.alias == "db").unwrap();
        a.conn.execute("UPDATE host SET osId = 'alpine' WHERE id = ?1", [db_a.id]).unwrap();
        let db_b = hosts(&b).into_iter().find(|h| h.alias == "db").unwrap();
        b.conn.execute("UPDATE host SET osId = 'ubuntu' WHERE id = ?1", [db_b.id]).unwrap();
        round(&mut a, &mut b);
        let os = |d: &Db, alias: &str| hosts(d).into_iter().find(|h| h.alias == alias).unwrap().os_id;
        assert_eq!(os(&a, "web").as_deref(), Some("debian"));
        assert_eq!(os(&a, "db").as_deref(), Some("alpine"));
        assert_eq!(os(&b, "db").as_deref(), Some("ubuntu"));
    }

    #[test]
    fn an_edit_saved_from_a_stale_copy_is_stamped_and_travels() {
        let (mut a, mut b) = two();
        a.store().upsert(&Host::new("web", "1.1.1.1")).unwrap();
        round(&mut a, &mut b);
        // The editor opens on A, then a synchronisation brings B's newer edit before it is saved.
        let stale = hosts(&a).remove(0);
        tick();
        b.store()
            .upsert(&Host {
                port: 2222,
                ..hosts(&b).remove(0)
            })
            .unwrap();
        round(&mut a, &mut b);
        assert_eq!(hosts(&a)[0].port, 2222);
        assert_eq!(hosts(&a)[0].updated_at, hosts(&b)[0].updated_at, "the merge keeps the remote stamp");
        tick();
        a.store()
            .upsert(&Host {
                hostname: "2.2.2.2".into(),
                ..stale
            })
            .unwrap();
        assert!(hosts(&a)[0].updated_at > hosts(&b)[0].updated_at);
        round(&mut a, &mut b);
        assert_eq!(hosts(&b)[0].hostname, "2.2.2.2");
        assert_eq!(hosts(&a)[0].updated_at, hosts(&b)[0].updated_at);
    }

    #[test]
    fn merged_changes_keep_their_time() {
        let (mut a, mut b) = two();
        a.store()
            .insert(&HostGroup {
                id: 0,
                name: "Lab".into(),
                ..serde_json::from_str(r#"{"name":"x"}"#).unwrap()
            })
            .unwrap();
        a.store()
            .upsert(&serde_json::from_str::<Snippet>(r#"{"name":"up","command":"uptime"}"#).unwrap())
            .unwrap();
        round(&mut a, &mut b);
        tick();
        let g: HostGroup = a.store().all().unwrap().remove(0);
        a.store().update(&HostGroup { color: 1, ..g }).unwrap();
        let sn: Snippet = a.store().all().unwrap().remove(0);
        a.store()
            .upsert(&Snippet {
                command: "uptime -p".into(),
                ..sn
            })
            .unwrap();
        round(&mut a, &mut b);
        let at = |d: &Db| {
            let g: Vec<HostGroup> = d.store().all().unwrap();
            let sn: Vec<Snippet> = d.store().all().unwrap();
            (g[0].updated_at, sn[0].updated_at)
        };
        assert_eq!(at(&a), at(&b));
        assert!(!pull(&mut a, &b).changed());
    }

    #[test]
    fn renamed_then_recreated_stays_two_records() {
        let (mut a, mut b) = two();
        let bastion = a.store().upsert(&Host::new("bastion", "b")).unwrap();
        a.store()
            .upsert(&Host {
                jump_host_id: Some(bastion),
                ..Host::new("web", "10.0.0.1")
            })
            .unwrap();
        round(&mut a, &mut b);
        // Renamed, and a new host takes its old name (without a jump host, so it is merged first).
        tick();
        let web = hosts(&a).into_iter().find(|h| h.alias == "web").unwrap();
        a.store()
            .upsert(&Host {
                alias: "web-old".into(),
                ..web
            })
            .unwrap();
        a.store().upsert(&Host::new("web", "10.0.0.2")).unwrap();
        round(&mut a, &mut b);
        round(&mut a, &mut b);
        for d in [&a, &b] {
            let hs = hosts(d);
            assert_eq!(
                hs.iter().map(|h| h.alias.as_str()).collect::<Vec<_>>(),
                ["bastion", "web", "web-old"]
            );
            assert_eq!(hs.iter().find(|h| h.alias == "web").unwrap().hostname, "10.0.0.2");
            assert_eq!(hs.iter().find(|h| h.alias == "web-old").unwrap().hostname, "10.0.0.1");
        }
        let ids = |d: &Db| hosts(d).into_iter().map(|h| h.sync_id).collect::<Vec<_>>();
        assert_eq!(ids(&a), ids(&b));
    }

    #[test]
    fn group_renamed_onto_another_groups_name_keeps_both() {
        let group = |name: &str| HostGroup {
            id: 0,
            name: name.into(),
            ..serde_json::from_str(r#"{"name":"x"}"#).unwrap()
        };
        let (mut a, mut b) = two();
        let dev = a.store().insert(&group("Dev")).unwrap();
        a.store()
            .upsert(&Host {
                group_id: Some(dev),
                ..Host::new("d1", "d")
            })
            .unwrap();
        round(&mut a, &mut b);
        let prod = b.store().insert(&group("Prod")).unwrap();
        b.store()
            .upsert(&Host {
                group_id: Some(prod),
                ..Host::new("p1", "p")
            })
            .unwrap();
        let dev_id = a.store().all::<HostGroup>().unwrap().remove(0).sync_id;
        let prod_id = b.store().get::<HostGroup>(prod).unwrap().unwrap().sync_id;
        tick();
        let g = a.store().all::<HostGroup>().unwrap().remove(0);
        a.store().update(&HostGroup { name: "Prod".into(), ..g }).unwrap();
        round(&mut a, &mut b);
        round(&mut a, &mut b);
        let groups = |d: &Db| {
            d.store()
                .all::<HostGroup>()
                .unwrap()
                .into_iter()
                .map(|g| (g.name, g.sync_id))
                .collect::<std::collections::BTreeMap<_, _>>()
        };
        assert_eq!(groups(&a).keys().map(String::as_str).collect::<Vec<_>>(), ["Prod", "Prod (2)"]);
        assert_eq!(groups(&a), groups(&b));
        // The smaller sync id keeps the name; every host stays in its group.
        assert_eq!(groups(&a)["Prod"], dev_id.clone().min(prod_id.clone()));
        for d in [&a, &b] {
            let by_id: HashMap<i64, String> = d
                .store()
                .all::<HostGroup>()
                .unwrap()
                .into_iter()
                .map(|g| (g.id, g.sync_id))
                .collect();
            let group_of = |alias: &str| by_id[&hosts(d).into_iter().find(|h| h.alias == alias).unwrap().group_id.unwrap()].clone();
            assert_eq!(group_of("d1"), dev_id);
            assert_eq!(group_of("p1"), prod_id);
        }
    }

    #[test]
    fn an_edit_moves_forward_even_after_a_clock_running_ahead() {
        let (a, _) = two();
        let s = a.store();
        let ahead = now_ms() + 3_600_000;
        let id = s
            .upsert(&Host {
                updated_at: ahead,
                ..Host::new("nas", "n")
            })
            .unwrap();
        assert_eq!(s.get::<Host>(id).unwrap().unwrap().updated_at, ahead);
        s.upsert(&Host {
            port: 2200,
            ..s.get::<Host>(id).unwrap().unwrap()
        })
        .unwrap();
        assert_eq!(s.get::<Host>(id).unwrap().unwrap().updated_at, ahead + 1);
        // A write carrying a newer updatedAt (what the merge does) keeps it.
        s.upsert(&Host {
            port: 2201,
            updated_at: ahead + 10,
            ..s.get::<Host>(id).unwrap().unwrap()
        })
        .unwrap();
        assert_eq!(s.get::<Host>(id).unwrap().unwrap().updated_at, ahead + 10);
    }

    #[test]
    fn learning_the_system_is_not_an_edit() {
        let (mut a, mut b) = two();
        a.store().upsert(&Host::new("web", "10.0.0.8")).unwrap();
        round(&mut a, &mut b);
        let before = hosts(&b).remove(0);
        a.store().set_os(hosts(&a)[0].id, "debian").unwrap();
        tick();
        pull(&mut b, &a);
        let after = hosts(&b).remove(0);
        assert_eq!(after.os_id.as_deref(), Some("debian"));
        assert_eq!(after.updated_at, before.updated_at);
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

    fn group(name: &str) -> HostGroup {
        serde_json::from_value(serde_json::json!({ "name": name })).unwrap()
    }

    #[test]
    fn a_remote_deletion_does_not_date_the_records_that_pointed_to_it() {
        let (mut a, mut b) = two();
        let prod = a.store().insert(&group("Prod")).unwrap();
        a.store()
            .upsert(&Host {
                group_id: Some(prod),
                ..Host::new("web", "10.0.0.1")
            })
            .unwrap();
        round(&mut a, &mut b);
        // B deletes the group (its own host leaves it: a local edit), then changes the port.
        tick();
        let prod_b = b.store().all::<HostGroup>().unwrap().remove(0).id;
        b.store().delete::<HostGroup>(prod_b).unwrap();
        tick();
        b.store()
            .upsert(&Host {
                port: 2222,
                ..hosts(&b).remove(0)
            })
            .unwrap();
        tick();
        round(&mut a, &mut b);
        for d in [&a, &b] {
            let h = hosts(d).remove(0);
            assert_eq!((h.port, h.group_id), (2222, None));
            assert!(d.store().all::<HostGroup>().unwrap().is_empty());
        }
        assert_eq!(hosts(&a)[0].updated_at, hosts(&b)[0].updated_at);
    }

    #[test]
    fn same_time_edits_of_one_record_converge() {
        let (mut a, mut b) = two();
        let ahead = now_ms() + 3_600_000;
        a.store()
            .upsert(&Host {
                updated_at: ahead,
                ..Host::new("web", "10.0.0.1")
            })
            .unwrap();
        round(&mut a, &mut b);
        // Both edits are stamped one past the stamp from the future: same updatedAt, same sync id.
        a.store()
            .upsert(&Host {
                port: 2200,
                ..hosts(&a).remove(0)
            })
            .unwrap();
        b.store()
            .upsert(&Host {
                hostname: "10.0.0.2".into(),
                ..hosts(&b).remove(0)
            })
            .unwrap();
        assert_eq!(hosts(&a)[0].updated_at, ahead + 1);
        assert_eq!(hosts(&b)[0].updated_at, ahead + 1);
        round(&mut a, &mut b);
        let (ha, hb) = (hosts(&a).remove(0), hosts(&b).remove(0));
        assert_eq!((ha.port, &ha.hostname), (hb.port, &hb.hostname));
        assert!(
            (ha.port, ha.hostname.as_str()) == (2200, "10.0.0.1") || (ha.port, ha.hostname.as_str()) == (22, "10.0.0.2"),
            "one of the two edits wins, whole"
        );
        assert_eq!(ha.updated_at, hb.updated_at);
        assert!(!pull(&mut a, &b).changed() && !pull(&mut b, &a).changed());
    }

    #[test]
    fn content_hash_is_the_android_one() {
        // Same record and expected value as SyncMergeTest.contentHashIsTheDesktopOne.
        let h = Host {
            port: 2222,
            username: "root".into(),
            password: Some("pä$$ \"q\"".into()),
            group_id: Some(7),
            key_id: Some(3),
            tags: "prod,web".into(),
            rdp_audio: false,
            ..Host::new("café ☕", "10.0.0.1")
        };
        let Value::Object(fields) = serde_json::to_value(&h).unwrap() else {
            unreachable!()
        };
        let refs = HashMap::from([("groupId".to_owned(), "g-1".to_owned())]);
        assert_eq!(
            content_hash("host", &fields, &refs),
            "5a8e550a672b7a693f41d592e4ceeaf66491c13219081a684d152d8ef36d45db"
        );
    }

    #[test]
    fn a_converged_sync_id_counts_as_a_change() {
        let (mut a, mut b) = two();
        a.store().upsert(&Host::new("pi", "192.168.1.5")).unwrap();
        b.store().upsert(&Host::new("pi", "192.168.1.5")).unwrap();
        a.conn.execute("UPDATE host SET updatedAt = 1", []).unwrap();
        b.conn.execute("UPDATE host SET updatedAt = 1", []).unwrap();
        let (ia, ib) = (hosts(&a)[0].sync_id.clone(), hosts(&b)[0].sync_id.clone());
        // The side with the larger sync id adopts the other one: the app must reload its copy.
        let (bigger, smaller) = if ia > ib { (&mut a, &b) } else { (&mut b, &a) };
        assert!(pull(bigger, smaller).changed());
        assert_eq!(hosts(bigger)[0].sync_id, ia.clone().min(ib));
    }

    #[test]
    fn triggers_are_back_after_a_merge() {
        let (mut a, mut b) = two();
        a.store().upsert(&Host::new("nas", "n")).unwrap();
        round(&mut a, &mut b);
        let merging: i64 = b.conn.query_row("SELECT merging FROM sync_ctl", [], |r| r.get(0)).unwrap();
        assert_eq!(merging, 0);
        let before = hosts(&b).remove(0);
        tick();
        b.store().upsert(&Host { port: 2, ..before.clone() }).unwrap();
        assert!(hosts(&b)[0].updated_at > before.updated_at);
        b.store().delete::<Host>(before.id).unwrap();
        assert_eq!(b.store().tombstones().unwrap().len(), 1);
    }

    /// The SQL type of `column` in every row of `table`.
    fn types(db: &Db, table: &str, column: &str) -> Vec<String> {
        let mut st = db.conn.prepare(&format!("SELECT typeof({column}) FROM {table}")).unwrap();
        st.query_map([], |r| r.get(0)).unwrap().map(|r| r.unwrap()).collect()
    }

    #[test]
    fn stamps_never_go_past_the_cap() {
        use crate::db::{FUTURE_LIMIT_MS, MAX_STAMP};
        let (a, _) = two();
        let s = a.store();
        let id = s
            .upsert(&Host {
                updated_at: i64::MAX,
                ..Host::new("x", "h")
            })
            .unwrap();
        assert_eq!(s.get::<Host>(id).unwrap().unwrap().updated_at, MAX_STAMP);
        // Written before stamps were bounded: one past it no longer turns into a REAL.
        a.conn.execute(&format!("UPDATE host SET updatedAt = {}", i64::MAX), []).unwrap();
        s.upsert(&Host {
            port: 2,
            ..s.get::<Host>(id).unwrap().unwrap()
        })
        .unwrap();
        assert_eq!(s.get::<Host>(id).unwrap().unwrap().updated_at, MAX_STAMP);
        a.conn.execute(&format!("UPDATE host SET updatedAt = {}", i64::MAX), []).unwrap();
        s.delete::<Host>(id).unwrap();
        assert_eq!(s.tombstones().unwrap()[0].deleted_at, MAX_STAMP);
        assert_eq!(types(&a, "sync_tombstone", "deletedAt"), ["integer"]);
        // A REAL left by an older version is read, then brought back when the database opens.
        s.upsert(&Host::new("y", "h")).unwrap();
        a.conn.execute("UPDATE host SET updatedAt = 1e19", []).unwrap();
        a.conn.execute("UPDATE sync_tombstone SET deletedAt = 1e19", []).unwrap();
        assert_eq!(types(&a, "host", "updatedAt"), ["real"]);
        assert_eq!(hosts(&a)[0].updated_at, MAX_STAMP);
        assert_eq!(s.tombstones().unwrap()[0].deleted_at, MAX_STAMP);
        assert!(snapshot(&a, vec![]).is_ok() && s.last_change().unwrap() == MAX_STAMP);
        let a = a.reopened();
        assert_eq!(types(&a, "host", "updatedAt"), ["integer"]);
        assert!(hosts(&a)[0].updated_at <= now_ms() + FUTURE_LIMIT_MS);
        // The tombstone is brought back too, not dropped.
        assert!(a.store().tombstones().unwrap()[0].deleted_at <= now_ms() + FUTURE_LIMIT_MS);
        assert_eq!(types(&a, "sync_tombstone", "deletedAt"), ["integer"]);
    }

    #[test]
    fn a_clock_before_the_floor_brings_nothing_back() {
        use crate::db::{CLOCK_FLOOR_MS, FUTURE_LIMIT_MS, MAX_STAMP};
        assert_eq!(stamp_cap(0), MAX_STAMP);
        assert_eq!(stamp_cap(CLOCK_FLOOR_MS - 1), MAX_STAMP);
        assert_eq!(stamp_cap(CLOCK_FLOOR_MS), CLOCK_FLOOR_MS + FUTURE_LIMIT_MS);
        // Stamped by clocks that were right, two years after this clock (started at 1970, before the network time).
        let (a, _) = two();
        let s = a.store();
        let later = now_ms() + 2 * FUTURE_LIMIT_MS;
        s.upsert(&Host {
            updated_at: later,
            ..Host::new("nas", "n")
        })
        .unwrap();
        let mut df = serde_json::from_str::<Snippet>(r#"{"name":"df","command":"df -h"}"#).unwrap();
        df.updated_at = later;
        let df = s.upsert(&df).unwrap();
        s.delete::<Snippet>(df).unwrap();
        s.upsert(&Host::new("real", "r")).unwrap();
        a.conn.execute("UPDATE host SET updatedAt = 1e19 WHERE alias = 'real'", []).unwrap();
        s.repair_stamps(0).unwrap();
        // Nothing judged by that clock; only what no integer holds is brought back.
        assert_eq!(hosts(&a).iter().map(|h| h.updated_at).collect::<Vec<_>>(), [later, MAX_STAMP]);
        assert_eq!(types(&a, "host", "updatedAt"), ["integer", "integer"]);
        assert_eq!(s.tombstones().unwrap()[0].deleted_at, later + 1);
        // With a clock past the floor, both come back to a year ahead of it, the tombstone kept.
        let now = CLOCK_FLOOR_MS;
        s.repair_stamps(now).unwrap();
        let cap = now + FUTURE_LIMIT_MS;
        assert_eq!(hosts(&a).iter().map(|h| h.updated_at).collect::<Vec<_>>(), [cap, cap]);
        assert_eq!(s.tombstones().unwrap()[0].deleted_at, cap);
    }

    #[test]
    fn stamps_from_far_ahead_are_believed_up_to_a_year() {
        use crate::db::FUTURE_LIMIT_MS;
        let (mut a, mut b) = two();
        a.store().upsert(&Host::new("nas", "n")).unwrap();
        a.store()
            .upsert(&serde_json::from_str::<Snippet>(r#"{"name":"df","command":"df -h"}"#).unwrap())
            .unwrap();
        round(&mut a, &mut b);
        tick();
        a.store()
            .upsert(&Host {
                hostname: "n2".into(),
                ..hosts(&a).remove(0)
            })
            .unwrap();
        a.conn.execute(&format!("UPDATE host SET updatedAt = {}", i64::MAX), []).unwrap();
        a.conn.execute(&format!("UPDATE snippet SET updatedAt = {}", i64::MAX), []).unwrap();
        a.store().delete::<Snippet>(a.store().all::<Snippet>().unwrap()[0].id).unwrap();
        let before = now_ms();
        pull(&mut b, &a);
        let after = now_ms();
        let h = hosts(&b).remove(0);
        assert_eq!(h.hostname, "n2");
        assert!((before + FUTURE_LIMIT_MS..=after + FUTURE_LIMIT_MS).contains(&h.updated_at));
        // A deletion dated that far ahead is believed up to a year too: it still applies.
        assert!(b.store().all::<Snippet>().unwrap().is_empty());
        let t = b.store().tombstones().unwrap()[0].deleted_at;
        assert!((before + FUTURE_LIMIT_MS..=after + FUTURE_LIMIT_MS).contains(&t));
        // Its own stamps are brought back too before it merges.
        pull(&mut a, &b);
        assert!(hosts(&a)[0].updated_at <= now_ms() + FUTURE_LIMIT_MS);
        assert!(a.store().tombstones().unwrap()[0].deleted_at <= now_ms() + FUTURE_LIMIT_MS);
        assert!(a.store().all::<Snippet>().unwrap().is_empty());
        assert!(!pull(&mut a, &b).changed() && !pull(&mut b, &a).changed());
    }

    #[test]
    fn a_key_trusted_again_outlives_a_tombstone_dated_ahead() {
        // Trusted on a device whose clock runs ahead.
        let (mut a, mut b) = two();
        let ahead = now_ms() + 3_600_000;
        let key = KnownHost {
            id: 0,
            host: "n".into(),
            port: 22,
            key_type: "ssh-ed25519".into(),
            public_key: "AAAA".into(),
            fingerprint: "SHA256:x".into(),
            added_at: ahead,
            extra: Map::new(),
        };
        a.store().insert(&key).unwrap();
        round(&mut a, &mut b);
        // Removed on B: the tombstone is dated after that trust, so ahead too, and both devices keep it.
        b.conn.execute("DELETE FROM known_host WHERE host = 'n'", []).unwrap();
        round(&mut a, &mut b);
        assert!(a.store().all::<KnownHost>().unwrap().is_empty());
        assert_eq!(a.store().tombstones().unwrap()[0].deleted_at, ahead + 1);
        // The new key is trusted on A with this clock: dated after the tombstone, which B still holds.
        a.store()
            .insert(&KnownHost {
                public_key: "BBBB".into(),
                fingerprint: "SHA256:y".into(),
                added_at: now_ms(),
                ..key
            })
            .unwrap();
        assert_eq!(a.store().all::<KnownHost>().unwrap()[0].added_at, ahead + 2);
        round(&mut a, &mut b);
        round(&mut a, &mut b);
        for d in [&a, &b] {
            let k = d.store().all::<KnownHost>().unwrap().remove(0);
            assert_eq!((k.fingerprint.as_str(), k.added_at), ("SHA256:y", ahead + 2));
            // The old tombstone may still travel (for 90 days), dated before the new trust.
            assert!(d.store().tombstones().unwrap().iter().all(|t| t.deleted_at < k.added_at));
        }
    }

    /// One host on `x` and `y`, both stamped one past a stamp from the future, differing by `port` (or, with
    /// `extra_y`, by a field this version does not know on `y`); the version of `x` keeps its own too (`y`'s port
    /// hashes after). The stamp both have.
    fn tie(x: &mut Db, y: &mut Db, extra_y: Option<Value>) -> i64 {
        let ahead = now_ms() + 3_600_000;
        x.store()
            .upsert(&Host {
                updated_at: ahead,
                ..Host::new("web", "10.0.0.1")
            })
            .unwrap();
        round(x, y);
        let hash = |h: &Host| {
            let Value::Object(fields) = serde_json::to_value(h).unwrap() else {
                unreachable!()
            };
            content_hash("host", &fields, &HashMap::new())
        };
        let mine = Host {
            port: 2200,
            ..hosts(x).remove(0)
        };
        let theirs = (2201..2600)
            .find(|p| hash(&Host { port: *p, ..mine.clone() }) > hash(&mine))
            .unwrap();
        x.store().upsert(&mine).unwrap();
        let mut on_y = Host {
            port: if extra_y.is_some() { 2200 } else { theirs },
            ..hosts(y).remove(0)
        };
        if let Some(v) = extra_y {
            on_y.extra.insert("future".into(), v);
        }
        y.store().upsert(&on_y).unwrap();
        assert_eq!(hosts(x)[0].updated_at, ahead + 1);
        assert_eq!(hosts(y)[0].updated_at, ahead + 1);
        ahead + 1
    }

    /// The columns hashed by a version that does not hash `port`: for it, two versions differing by their port are a
    /// tie it keeps.
    fn older_columns() -> Vec<(&'static str, &'static [&'static str])> {
        static HOST: std::sync::OnceLock<Vec<&'static str>> = std::sync::OnceLock::new();
        let host = HOST.get_or_init(|| SYNCED[1].1.iter().copied().filter(|c| *c != "port").collect());
        assert_eq!(SYNCED[1].0, "host");
        let mut older: Vec<(&str, &[&str])> = SYNCED.to_vec();
        older[1].1 = host;
        older
    }

    /// Two versions of one host stamped alike (one past a stamp from the future), differing by `port`; the merges of
    /// B hash `older`.
    fn tied_ports(older: Columns<'_>, extra_b: Option<Value>) -> (Db, Db, i64) {
        let (mut a, mut b) = two();
        let ahead = tie(&mut a, &mut b, extra_b) - 1;
        let older = Merging {
            columns: older,
            move_ties: true,
        };
        for _ in 0..2 {
            pull(&mut a, &b);
            merge_with(&mut b, &snapshot(&a, vec![]).unwrap(), &older).unwrap();
        }
        assert!(!pull(&mut a, &b).changed());
        assert!(!merge_with(&mut b, &snapshot(&a, vec![]).unwrap(), &older).unwrap().changed());
        (a, b, ahead)
    }

    /// A Wi-Fi exchange where each side took its snapshot before merging the other's (an older responder): only the
    /// responder moves a tie past, so the next exchange settles it, whichever version decides on which side.
    #[test]
    fn a_tie_merged_both_ways_at_once_converges() {
        let older = older_columns();
        for older_initiates in [false, true] {
            let (mut i, mut r) = two();
            tie(&mut i, &mut r, None);
            let (on_i, on_r) = if older_initiates {
                (&older[..], SYNCED)
            } else {
                (SYNCED, &older[..])
            };
            let initiator = Merging {
                columns: on_i,
                move_ties: false,
            };
            let responder = Merging {
                columns: on_r,
                move_ties: true,
            };
            let mut exchanges = 0;
            loop {
                let (from_i, from_r) = (snapshot(&i, vec![]).unwrap(), snapshot(&r, vec![]).unwrap());
                let a = merge_with(&mut i, &from_r, &initiator).unwrap().changed();
                let b = merge_with(&mut r, &from_i, &responder).unwrap().changed();
                if !a && !b {
                    break;
                }
                exchanges += 1;
                assert!(exchanges < 4, "no convergence");
            }
            assert_eq!(hosts(&i)[0].port, hosts(&r)[0].port);
            assert_eq!(hosts(&i)[0].updated_at, hosts(&r)[0].updated_at);
        }
    }

    /// The Wi-Fi exchange: the responder merges first, then answers with its data merged; one exchange settles a tie.
    #[test]
    fn a_tie_merged_by_the_responder_first_converges_in_one_exchange() {
        let older = older_columns();
        let responder = Merging {
            columns: &older,
            move_ties: true,
        };
        let (mut a, mut b) = two();
        let at = tie(&mut a, &mut b, None);
        assert!(merge_with(&mut b, &snapshot(&a, vec![]).unwrap(), &responder).unwrap().changed());
        assert!(merge_ties(&mut a, &snapshot(&b, vec![]).unwrap(), false).unwrap().changed());
        assert_eq!(hosts(&a)[0].port, hosts(&b)[0].port);
        assert_eq!(hosts(&a)[0].updated_at, at + 1);
        assert_eq!(hosts(&b)[0].updated_at, at + 1);
        assert!(!merge_with(&mut b, &snapshot(&a, vec![]).unwrap(), &responder).unwrap().changed());
        assert!(!merge_ties(&mut a, &snapshot(&b, vec![]).unwrap(), false).unwrap().changed());
    }

    #[test]
    fn a_tie_decided_differently_by_each_version_converges() {
        // B runs a version that does not hash `port`.
        let (a, b, ahead) = tied_ports(&older_columns(), None);
        for d in [&a, &b] {
            assert_eq!(hosts(d)[0].port, 2200);
            assert_eq!(hosts(d)[0].updated_at, ahead + 2);
        }
    }

    #[test]
    fn a_tie_on_a_field_this_version_does_not_know_converges() {
        // A field of a newer version, kept in `extra` here: compared, but not hashed by this version.
        let (a, b, ahead) = tied_ports(SYNCED, Some(Value::from(2)));
        assert_eq!(hosts(&a)[0].extra.get("future"), hosts(&b)[0].extra.get("future"));
        assert_eq!(hosts(&a)[0].updated_at, ahead + 2);
        assert_eq!(hosts(&b)[0].updated_at, ahead + 2);
    }

    #[test]
    fn deleting_a_record_stamped_in_the_future_applies_everywhere() {
        let (mut a, mut b) = two();
        a.store()
            .upsert(&Host {
                updated_at: now_ms() + 3_600_000,
                ..Host::new("nas", "n")
            })
            .unwrap();
        round(&mut a, &mut b);
        a.store().delete::<Host>(hosts(&a)[0].id).unwrap();
        round(&mut a, &mut b);
        assert!(hosts(&a).is_empty() && hosts(&b).is_empty());
    }
}

/// What a device may leave out of the synchronisation, by theme. Records of a theme left out are neither taken from
/// the others nor sent to them, and what the others wrote of it stays in the shared file ([carry]).
///
/// The themes follow the references between records: a host points to its keys, identity, group and jump host, so
/// they travel together; a tunnel points to its host, so tunnels are left out whenever hosts are ([effective]).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub enum SyncPart {
    /// Hosts, groups, SSH keys and identities.
    Hosts,
    Snippets,
    Tunnels,
    /// The trusted host keys (known hosts).
    KnownHosts,
    /// The AI assistant's API keys: taken in only on demand (Settings::sync_ai_keys), being secrets.
    AiKeys,
}

impl SyncPart {
    pub const ALL: [SyncPart; 5] = [
        SyncPart::Hosts,
        SyncPart::Snippets,
        SyncPart::Tunnels,
        SyncPart::KnownHosts,
        SyncPart::AiKeys,
    ];

    /// The tombstone kinds (table names) of the theme.
    fn kinds(self) -> &'static [&'static str] {
        match self {
            SyncPart::Hosts => &["host_group", "host", "ssh_key", "identity"],
            SyncPart::Snippets => &["snippet"],
            SyncPart::Tunnels => &["port_forward"],
            SyncPart::KnownHosts => &["known_host"],
            SyncPart::AiKeys => &[],
        }
    }
}

/// The themes really left out: [skip], plus the tunnels when the hosts are.
pub fn effective(skip: &[SyncPart]) -> Vec<SyncPart> {
    let mut v: Vec<SyncPart> = SyncPart::ALL.into_iter().filter(|p| skip.contains(p)).collect();
    if v.contains(&SyncPart::Hosts) && !v.contains(&SyncPart::Tunnels) {
        v.push(SyncPart::Tunnels);
    }
    v
}

fn take_parts(to: &mut BackupData, from: Option<&BackupData>, skip: &[SyncPart]) {
    let skip = effective(skip);
    let kinds: Vec<&str> = skip.iter().flat_map(|p| p.kinds().iter().copied()).collect();
    for p in &skip {
        match p {
            SyncPart::Hosts => {
                to.groups = from.map(|f| f.groups.clone()).unwrap_or_default();
                to.hosts = from.map(|f| f.hosts.clone()).unwrap_or_default();
                to.keys = from.map(|f| f.keys.clone()).unwrap_or_default();
                to.identities = from.map(|f| f.identities.clone()).unwrap_or_default();
            }
            SyncPart::Snippets => to.snippets = from.map(|f| f.snippets.clone()).unwrap_or_default(),
            SyncPart::Tunnels => to.forwards = from.map(|f| f.forwards.clone()).unwrap_or_default(),
            SyncPart::KnownHosts => to.known_hosts = from.map(|f| f.known_hosts.clone()).unwrap_or_default(),
            SyncPart::AiKeys => to.ai_keys = from.map(|f| f.ai_keys.clone()).unwrap_or_default(),
        }
    }
    to.tombstones.retain(|t| !kinds.contains(&t.kind.as_str()));
    if let Some(f) = from {
        to.tombstones
            .extend(f.tombstones.iter().filter(|t| kinds.contains(&t.kind.as_str())).cloned());
    }
}

/// A local shell or a serial port only exists on the device it was made on: never taken from another device nor sent
/// to one, whatever the themes (and a deletion from elsewhere never removes one: Store::delete_synced).
pub fn device_only(p: crate::model::Protocol) -> bool {
    matches!(p, crate::model::Protocol::Local | crate::model::Protocol::Serial)
}

/// [data] without the themes in [skip] (records and deletions): what is taken from, or sent to, another device.
pub fn without(data: &mut BackupData, skip: &[SyncPart]) {
    data.hosts.retain(|h| !device_only(h.protocol));
    take_parts(data, None, skip);
}

/// The themes in [skip] of what is written to the shared file taken from the file as it was ([from]): this device
/// leaves them as the others wrote them.
pub fn carry(data: &mut BackupData, from: &BackupData, skip: &[SyncPart]) {
    data.hosts.retain(|h| !device_only(h.protocol));
    take_parts(data, Some(from), skip);
}

#[cfg(test)]
mod parts_tests {
    use super::*;
    use crate::model::{Host, Snippet, SyncTombstone};

    fn data(alias: &str, snippet: &str) -> BackupData {
        let mut d = BackupData::default();
        d.hosts.push(Host::new(alias, "10.0.0.2"));
        d.snippets
            .push(serde_json::from_value::<Snippet>(serde_json::json!({ "name": snippet, "command": "ls" })).unwrap());
        for kind in ["host", "snippet", "port_forward"] {
            d.tombstones.push(SyncTombstone {
                sync_id: format!("{alias}-{kind}"),
                kind: kind.into(),
                deleted_at: 1,
                extra: Default::default(),
            });
        }
        d
    }

    #[test]
    fn a_theme_left_out_is_neither_taken_nor_sent() {
        let mut d = data("nas", "logs");
        without(&mut d, &[SyncPart::Snippets]);
        assert!(d.snippets.is_empty());
        assert_eq!(d.hosts.len(), 1);
        let kinds: Vec<&str> = d.tombstones.iter().map(|t| t.kind.as_str()).collect();
        assert_eq!(kinds, ["host", "port_forward"]);
    }

    #[test]
    fn local_shells_and_serial_ports_stay_on_their_device() {
        use crate::model::Protocol;
        let mut d = data("nas", "logs");
        for (alias, p) in [("shell", Protocol::Local), ("router", Protocol::Serial)] {
            let mut h = Host::new(alias, "");
            h.protocol = p;
            d.hosts.push(h);
        }
        let mut sent = d.clone();
        without(&mut sent, &[]);
        assert_eq!(sent.hosts.iter().map(|h| h.alias.as_str()).collect::<Vec<_>>(), ["nas"]);
        carry(&mut d, &data("pc", "x"), &[]);
        assert_eq!(d.hosts.iter().map(|h| h.alias.as_str()).collect::<Vec<_>>(), ["nas"]);
    }

    #[test]
    fn leaving_hosts_out_leaves_their_tunnels_out() {
        assert_eq!(effective(&[SyncPart::Hosts]), [SyncPart::Hosts, SyncPart::Tunnels]);
        let mut d = data("nas", "logs");
        without(&mut d, &[SyncPart::Hosts]);
        assert!(d.hosts.is_empty());
        let kinds: Vec<&str> = d.tombstones.iter().map(|t| t.kind.as_str()).collect();
        assert_eq!(kinds, ["snippet"]);
    }

    #[test]
    fn the_shared_file_keeps_what_the_others_wrote_of_a_theme_left_out() {
        let mut mine = data("pc", "mine");
        let file = data("nas", "theirs");
        carry(&mut mine, &file, &[SyncPart::Snippets]);
        assert_eq!(mine.hosts[0].alias, "pc");
        assert_eq!(mine.snippets[0].name, "theirs");
        let snippet_stones: Vec<&str> = mine
            .tombstones
            .iter()
            .filter(|t| t.kind == "snippet")
            .map(|t| t.sync_id.as_str())
            .collect();
        assert_eq!(snippet_stones, ["nas-snippet"]);
        // Nothing left out: nothing changes.
        let before = data("pc", "mine");
        let mut same = before.clone();
        carry(&mut same, &file, &[]);
        assert_eq!(same.hosts, before.hosts);
        assert_eq!(same.snippets, before.snippets);
        assert_eq!(same.tombstones.len(), before.tombstones.len());
    }
}
