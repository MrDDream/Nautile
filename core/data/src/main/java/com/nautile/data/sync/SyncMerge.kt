package com.nautile.data.sync

import androidx.room.withTransaction
import com.nautile.data.backup.BackupData
import com.nautile.data.db.NautileDatabase
import com.nautile.data.db.SyncSchema
import com.nautile.data.model.Host

/**
 * Merges another device's data into this database, record by record:
 * - records are matched by sync id, or by a natural key (alias, group name, key fingerprint…) the first time two
 *   devices meet, in which case both adopt the smaller sync id;
 * - the most recent change wins (`updatedAt`, ties broken by sync id so every device picks the same);
 * - a deletion wins over changes made before it, and a change made after a deletion brings the record back.
 * Local-only fields (last connection, detected OS, run count) are never taken from the other side.
 */
class SyncMerge(private val db: NautileDatabase) {

    data class Stats(var added: Int = 0, var updated: Int = 0, var deleted: Int = 0) {
        val changed: Boolean get() = added + updated + deleted > 0
        override fun toString() = "+$added ~$updated -$deleted"
    }

    private fun remoteWins(rAt: Long, rId: String, lAt: Long, lId: String) = rAt > lAt || (rAt == lAt && rId < lId)

    /** updatedAt to write for a winning remote record: never equal to the local one, or the trigger would stamp « now ». */
    private fun stamp(remoteAt: Long, localAt: Long) = if (remoteAt == localAt) remoteAt + 1 else remoteAt

    suspend fun merge(remote: BackupData): Stats = db.withTransaction {
        val st = Stats()
        val sync = db.syncDao()
        val hostDao = db.hostDao()
        val keyDao = db.keyDao()
        val tools = db.toolsDao()
        val known = db.knownHostDao()

        // 1. Deletions made on the other device.
        val before = sync.tombstones().associateBy { it.syncId }
        for (t in remote.tombstones) {
            val n = when (t.kind) {
                "host_group" -> sync.deleteGroup(t.syncId, t.deletedAt)
                "host" -> sync.deleteHost(t.syncId, t.deletedAt)
                "ssh_key" -> sync.deleteKey(t.syncId, t.deletedAt)
                "identity" -> sync.deleteIdentity(t.syncId, t.deletedAt)
                "snippet" -> sync.deleteSnippet(t.syncId, t.deletedAt)
                "port_forward" -> sync.deleteForward(t.syncId, t.deletedAt)
                "known_host" -> sync.deleteKnownHost(t.syncId, t.deletedAt)
                else -> 0
            }
            st.deleted += n
            // Keep the original deletion time (the trigger wrote « now »).
            val mine = before[t.syncId]
            if (n > 0 || mine == null || mine.deletedAt < t.deletedAt) sync.putTombstone(t)
        }
        val tombs = sync.tombstones().associateBy { it.syncId }
        fun deleted(syncId: String, updatedAt: Long) = tombs[syncId]?.let { it.deletedAt >= updatedAt } ?: false
        suspend fun revived(syncId: String) { if (syncId in tombs) sync.dropTombstone(syncId) }

        // 2. Groups.
        val groupIds = HashMap<Long, Long>()
        for (g in remote.groups) {
            if (deleted(g.syncId, g.updatedAt)) continue
            val local = sync.group(g.syncId) ?: hostDao.findGroup(g.name)
            if (local == null) {
                val id = hostDao.insertGroup(g.copy(id = 0))
                if (id > 0) { groupIds[g.id] = id; st.added++; revived(g.syncId) }
                continue
            }
            groupIds[g.id] = local.id
            val canonical = minOf(local.syncId, g.syncId)
            if (remoteWins(g.updatedAt, g.syncId, local.updatedAt, local.syncId) &&
                (local.name != g.name || local.color != g.color || local.sortOrder != g.sortOrder)
            ) {
                runCatching {
                    hostDao.updateGroup(local.copy(name = g.name, color = g.color, sortOrder = g.sortOrder, syncId = canonical, updatedAt = stamp(g.updatedAt, local.updatedAt)))
                }.onSuccess { st.updated++ }
            } else if (local.syncId != canonical) sync.setGroupSyncId(local.id, canonical)
        }
        suspend fun localGroup(remoteId: Long?): Long? = remoteId?.let { groupIds[it] ?: remote.groups.firstOrNull { g -> g.id == it }?.let { g -> hostDao.findGroup(g.name)?.id } }

        // 3. Keys.
        val keyIds = HashMap<Long, Long>()
        for (k in remote.keys) {
            if (deleted(k.syncId, k.updatedAt)) continue
            val local = sync.key(k.syncId) ?: keyDao.keyByFingerprint(k.fingerprint)
            if (local == null) {
                keyIds[k.id] = keyDao.insertKey(k.copy(id = 0)); st.added++; revived(k.syncId)
                continue
            }
            keyIds[k.id] = local.id
            val canonical = minOf(local.syncId, k.syncId)
            val candidate = k.copy(id = local.id, createdAt = local.createdAt, syncId = canonical, updatedAt = stamp(k.updatedAt, local.updatedAt))
            if (remoteWins(k.updatedAt, k.syncId, local.updatedAt, local.syncId) && candidate.copy(updatedAt = 0, syncId = "") != local.copy(updatedAt = 0, syncId = "")) {
                runCatching { keyDao.updateKey(candidate) }.onSuccess { st.updated++ }
            } else if (local.syncId != canonical) sync.setKeySyncId(local.id, canonical)
        }

        // 4. Identities.
        val identityIds = HashMap<Long, Long>()
        val identities = keyDao.allIdentities()
        for (i in remote.identities) {
            if (deleted(i.syncId, i.updatedAt)) continue
            val local = sync.identity(i.syncId) ?: identities.firstOrNull { it.name == i.name && it.username == i.username }
            val keyId = i.keyId?.let(keyIds::get)
            if (local == null) {
                identityIds[i.id] = keyDao.upsertIdentity(i.copy(id = 0, keyId = keyId)); st.added++; revived(i.syncId)
                continue
            }
            identityIds[i.id] = local.id
            val canonical = minOf(local.syncId, i.syncId)
            val candidate = i.copy(id = local.id, keyId = keyId, syncId = canonical, updatedAt = stamp(i.updatedAt, local.updatedAt))
            if (remoteWins(i.updatedAt, i.syncId, local.updatedAt, local.syncId) && candidate.copy(updatedAt = 0, syncId = "") != local.copy(updatedAt = 0, syncId = "")) {
                keyDao.upsertIdentity(candidate); st.updated++
            } else if (local.syncId != canonical) sync.setIdentitySyncId(local.id, canonical)
        }

        // 5. Hosts, jump targets before the hosts that use them.
        val hostIds = HashMap<Long, Long>()
        val remoteHosts = remote.hosts.associateBy { it.id }
        val ordered = orderByJump(remote.hosts)
        for (h in ordered) {
            if (deleted(h.syncId, h.updatedAt)) continue
            val local = sync.host(h.syncId) ?: hostDao.findByAlias(h.alias)
            val jump = h.jumpHostId?.let { j -> hostIds[j] ?: remoteHosts[j]?.let { rj -> sync.host(rj.syncId)?.id } }
            val mapped = h.copy(
                groupId = localGroup(h.groupId),
                keyId = h.keyId?.let(keyIds::get),
                identityId = h.identityId?.let(identityIds::get),
                jumpHostId = jump,
            )
            if (local == null) {
                hostIds[h.id] = hostDao.upsertHost(mapped.copy(id = 0, lastConnectedAt = null, osId = null)); st.added++; revived(h.syncId)
                continue
            }
            hostIds[h.id] = local.id
            val canonical = minOf(local.syncId, h.syncId)
            val candidate = mapped.copy(
                id = local.id, lastConnectedAt = local.lastConnectedAt, osId = local.osId, createdAt = local.createdAt,
                syncId = canonical, updatedAt = stamp(h.updatedAt, local.updatedAt),
            )
            if (remoteWins(h.updatedAt, h.syncId, local.updatedAt, local.syncId) && candidate.sameContent(local)) {
                // Same content: only the shared id may need to converge.
                if (local.syncId != canonical) sync.setHostSyncId(local.id, canonical)
            } else if (remoteWins(h.updatedAt, h.syncId, local.updatedAt, local.syncId)) {
                hostDao.upsertHost(candidate); st.updated++
            } else if (local.syncId != canonical) sync.setHostSyncId(local.id, canonical)
        }

        // 6. Port forwards (need their host).
        val forwards = tools.allForwards()
        for (f in remote.forwards) {
            if (deleted(f.syncId, f.updatedAt)) continue
            val hostId = hostIds[f.hostId] ?: continue
            val local = sync.forward(f.syncId) ?: forwards.firstOrNull { it.name == f.name && it.hostId == hostId }
            if (local == null) {
                tools.upsertForward(f.copy(id = 0, hostId = hostId)); st.added++; revived(f.syncId)
                continue
            }
            val canonical = minOf(local.syncId, f.syncId)
            val candidate = f.copy(id = local.id, hostId = hostId, syncId = canonical, updatedAt = stamp(f.updatedAt, local.updatedAt))
            if (remoteWins(f.updatedAt, f.syncId, local.updatedAt, local.syncId) && candidate.copy(updatedAt = 0, syncId = "") != local.copy(updatedAt = 0, syncId = "")) {
                tools.upsertForward(candidate); st.updated++
            } else if (local.syncId != canonical) sync.setForwardSyncId(local.id, canonical)
        }

        // 7. Snippets.
        val snippets = tools.allSnippets()
        for (s in remote.snippets) {
            if (deleted(s.syncId, s.updatedAt)) continue
            val local = sync.snippet(s.syncId) ?: snippets.firstOrNull { it.name == s.name && it.command == s.command }
            if (local == null) {
                tools.upsertSnippet(s.copy(id = 0, runCount = 0)); st.added++; revived(s.syncId)
                continue
            }
            val canonical = minOf(local.syncId, s.syncId)
            val candidate = s.copy(id = local.id, runCount = local.runCount, createdAt = local.createdAt, syncId = canonical, updatedAt = stamp(s.updatedAt, local.updatedAt))
            if (remoteWins(s.updatedAt, s.syncId, local.updatedAt, local.syncId) && candidate.copy(updatedAt = 0, syncId = "") != local.copy(updatedAt = 0, syncId = "")) {
                tools.upsertSnippet(candidate); st.updated++
            } else if (local.syncId != canonical) sync.setSnippetSyncId(local.id, canonical)
        }

        // 8. Trusted host keys: the newest trust wins.
        val localKnown = known.all().associateBy { Triple(it.host, it.port, it.keyType) }
        for (k in remote.knownHosts) {
            val tid = SyncSchema.knownHostId(k.host, k.port, k.keyType)
            if (deleted(tid, k.addedAt)) continue
            val mine = localKnown[Triple(k.host, k.port, k.keyType)]
            if (mine == null) { known.upsertBlocking(k.copy(id = 0)); st.added++ }
            else if (k.addedAt > mine.addedAt && (k.fingerprint != mine.fingerprint || k.publicKey != mine.publicKey)) {
                known.replaceBlocking(k.copy(id = 0)); st.updated++
            }
        }

        // Tombstones older than 90 days are forgotten: every device has seen them by then.
        sync.pruneTombstones(System.currentTimeMillis() - 90L * 24 * 3600 * 1000)
        st
    }

    /** Snapshot of this database for the other devices. */
    suspend fun snapshot(devices: List<com.nautile.data.backup.SyncDevice>): BackupData = BackupData(
        groups = db.hostDao().allGroups(),
        hosts = db.hostDao().allHosts(),
        keys = db.keyDao().allKeys(),
        identities = db.keyDao().allIdentities(),
        knownHosts = db.knownHostDao().all(),
        snippets = db.toolsDao().allSnippets(),
        forwards = db.toolsDao().allForwards(),
        tombstones = db.syncDao().tombstones(),
        devices = devices,
    )

    suspend fun lastChange(): Long = db.syncDao().lastChange() ?: 0L

    companion object {
        /** Hosts without a jump host first, then those whose jump host is already placed. */
        fun orderByJump(hosts: List<Host>): List<Host> {
            val byId = hosts.associateBy { it.id }
            val done = LinkedHashMap<Long, Host>()
            var pending = hosts
            repeat(hosts.size + 1) {
                val (ready, rest) = pending.partition { h -> h.jumpHostId == null || h.jumpHostId !in byId || h.jumpHostId in done }
                ready.forEach { done[it.id] = it }
                pending = rest
                if (pending.isEmpty()) return done.values.toList()
            }
            return done.values.toList() + pending // cycles: whatever is left
        }

        private fun Host.sameContent(other: Host) =
            copy(updatedAt = 0, syncId = "", lastConnectedAt = null, osId = null, createdAt = 0) ==
                other.copy(updatedAt = 0, syncId = "", lastConnectedAt = null, osId = null, createdAt = 0)
    }
}
