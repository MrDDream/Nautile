package com.nautile.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.nautile.data.backup.BackupData
import com.nautile.data.backup.SyncDevice
import com.nautile.data.db.NautileDatabase
import com.nautile.data.db.SyncSchema
import androidx.sqlite.db.SimpleSQLiteQuery
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.Identity
import com.nautile.data.model.PortForward
import com.nautile.data.model.Snippet
import com.nautile.data.model.SshKey
import com.nautile.data.model.Synced
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/**
 * Merges another device's data into this database, record by record:
 * - records are matched by sync id, or by a natural key (alias, group name, key fingerprint…) the first time two
 *   devices meet, in which case both adopt the smaller sync id. A local row the other device already knows under its
 *   own sync id is never matched by natural key: it is another record (renamed there, while a new one took its name);
 * - the most recent change wins (`updatedAt`; ties broken by sync id, then by the content's hash). The hash depends on
 *   the columns a version synchronises, so a tie kept here is moved one past: the other device then takes it by its
 *   date, whatever its own version would pick. Only one side of an exchange may do so (`moveTies`), or two versions
 *   picking differently would move theirs past each other's forever: the one merging first (the Wi-Fi responder, which
 *   answers with its data merged), not the one merging what comes back;
 * - stamps from the other device are believed up to a year ahead of this clock ([SyncSchema.stampCap]): later ones,
 *   tombstones included, are brought back to that (only past [SyncSchema.MAX_STAMP] with a clock known to be wrong);
 * - a deletion wins over changes made before it, and a change made after a deletion brings the record back;
 * - two different groups wanting the same name: the smaller sync id keeps it, the other becomes « name (2) ».
 * Local-only fields (last connection, detected OS, run count) are never taken from the other side.
 *
 * The synchronisation triggers stand aside during a merge (`sync_ctl.merging`): every updatedAt and tombstone written
 * here is the other device's, or set explicitly (a group renamed « name (2) » is a new edit).
 */
class SyncMerge internal constructor(
    private val db: NautileDatabase,
    /** The columns hashed to break a tie: SyncSchema.TABLES, another list only to test another version's choice. */
    private val hashColumns: Map<String, List<String>>,
) {
    constructor(db: NautileDatabase) : this(db, SyncSchema.TABLES)

    class Stats {
        var added: Int = 0
        /** Records rewritten, including those whose sync id only converged (a cached copy is then stale too). */
        var updated: Int = 0
        var deleted: Int = 0
        val changed: Boolean get() = added + updated + deleted > 0
        override fun toString() = "+$added ~$updated -$deleted"
    }

    /** Which version of a record found on both sides is kept. */
    private enum class Keep { REMOTE, LOCAL, LOCAL_BY_HASH }

    /**
     * Which version is kept when they [differ]: the latest change, then the smaller sync id, then (one record changed on
     * two devices with the same updatedAt, e.g. both moved one past a stamp from the future) the smaller content hash
     * ([remoteHashSmaller], computed only then).
     */
    private inline fun decide(differ: Boolean, rAt: Long, rId: String, lAt: Long, lId: String, remoteHashSmaller: () -> Boolean) = when {
        !differ -> Keep.LOCAL
        rAt != lAt -> if (rAt > lAt) Keep.REMOTE else Keep.LOCAL
        rId != lId -> if (rId < lId) Keep.REMOTE else Keep.LOCAL
        remoteHashSmaller() -> Keep.REMOTE
        else -> Keep.LOCAL_BY_HASH
    }

    /** updatedAt of a group renamed « name (2) » here: a new edit, after both versions, so it reaches the other devices. */
    private fun renamedAt(at: Long) = maxOf(System.currentTimeMillis(), SyncSchema.bump(at))

    /** [contentHash] of [record] (a row of [table]), its references looked up here. */
    private fun <T> recordHash(table: String, serializer: KSerializer<T>, record: T): String {
        val fields = canonicalJson.encodeToJsonElement(serializer, record).jsonObject
        val refs = SyncSchema.REFS[table].orEmpty().mapNotNull { (column, target) ->
            val id = (fields[column] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            db.query(SimpleSQLiteQuery("SELECT syncId FROM $target WHERE id = ?", arrayOf(id))).use { c ->
                if (c.moveToFirst()) column to c.getString(0) else null
            }
        }.toMap()
        return contentHash(table, fields, refs, hashColumns[table].orEmpty())
    }

    /**
     * The local row of a remote record: the one with its sync id, else the one with the same natural key if the other
     * device does not know that row ([remoteIds]) and no other remote record took it in this merge ([claimed]).
     */
    private inline fun <T : Synced> findLocal(bySyncId: T?, remoteIds: Set<String>?, claimed: MutableSet<Long>, byNaturalKey: () -> T?): T? {
        val local = bySyncId ?: byNaturalKey()?.takeIf { (remoteIds == null || it.syncId !in remoteIds) && it.id !in claimed }
        local?.let { claimed += it.id }
        return local
    }

    /**
     * Applies remote [r] to its [local] row: writes [adopt] (the remote content on the local row, with the given sync id and
     * updatedAt) when the remote change wins and the content differs; otherwise only lets the shared sync id converge,
     * or moves a tie kept by its hash one past ([moveTies]).
     */
    private suspend inline fun <T : Synced> reconcile(
        r: T, local: T, st: Stats, moveTies: Boolean,
        table: String, serializer: KSerializer<T>,
        adopt: (syncId: String, updatedAt: Long) -> T,
        same: (T, T) -> Boolean,
        write: (T) -> Boolean,
        setSyncId: (Long, String) -> Unit,
        kept: () -> Unit = {},
    ) {
        val canonical = minOf(local.syncId, r.syncId)
        val candidate = adopt(canonical, r.updatedAt)
        val keep = decide(!same(candidate, local), r.updatedAt, r.syncId, local.updatedAt, local.syncId) {
            recordHash(table, serializer, candidate) < recordHash(table, serializer, local)
        }
        if (keep == Keep.REMOTE) {
            if (write(candidate)) st.updated++
        } else {
            kept()
            if (keep == Keep.LOCAL_BY_HASH && moveTies) {
                // A tie kept by its hash (same sync id): moved one past, so the other device takes it by its date,
                // whatever columns its own version hashes.
                restamp(table, local.id, SyncSchema.bump(local.updatedAt)); st.updated++
            } else if (local.syncId != canonical) {
                setSyncId(local.id, canonical); st.updated++
            }
        }
    }

    /** Sets updatedAt of row [id] of [table] (a column the triggers do not watch). */
    private fun restamp(table: String, id: Long, updatedAt: Long) {
        db.openHelper.writableDatabase.execSQL("UPDATE $table SET updatedAt = ? WHERE id = ?", arrayOf<Any>(updatedAt, id))
    }

    /** Runs [block], false (logged) when a constraint refuses the write; the merge goes on. */
    private suspend fun tryWrite(what: String, block: suspend () -> Unit): Boolean = try {
        block(); true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("SyncMerge", "$what not merged: ${e.message}")
        false
    }

    /**
     * Pulls [remote] into this database, in one transaction, the synchronisation triggers put aside meanwhile.
     * [moveTies]: false when [remote] already merged this device's data (the Wi-Fi initiator, merging the responder's
     * answer): a tie still there is the other version's choice, kept by its date there; moving this one past would
     * only start them over (sync.rs merge_ties).
     */
    suspend fun merge(remote: BackupData, moveTies: Boolean = true): Stats = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        // Local stamps from too far ahead first: they would win over every incoming change.
        val now = System.currentTimeMillis()
        SyncSchema.stampRepairs(now).forEach(sql::execSQL)
        sql.execSQL(SyncSchema.MERGING_ON)
        val st = try {
            mergeIn(bounded(remote, SyncSchema.stampCap(now)), moveTies)
        } catch (e: Throwable) {
            // Also undone by the rollback.
            runCatching { sql.execSQL(SyncSchema.MERGING_OFF) }
            throw e
        }
        // Must succeed before the commit.
        sql.execSQL(SyncSchema.MERGING_OFF)
        st
    }

    /**
     * [remote] with its stamps believed up to [cap]: a later updatedAt / addedAt / deletedAt is brought back to it (one
     * from a clock far ahead, or past what an integer holds, would win over every later edit).
     */
    private fun bounded(remote: BackupData, cap: Long) = remote.copy(
        groups = remote.groups.map { if (it.updatedAt > cap) it.copy(updatedAt = cap) else it },
        hosts = remote.hosts.map { if (it.updatedAt > cap) it.copy(updatedAt = cap) else it },
        keys = remote.keys.map { if (it.updatedAt > cap) it.copy(updatedAt = cap) else it },
        identities = remote.identities.map { if (it.updatedAt > cap) it.copy(updatedAt = cap) else it },
        knownHosts = remote.knownHosts.map { if (it.addedAt > cap) it.copy(addedAt = cap) else it },
        snippets = remote.snippets.map { if (it.updatedAt > cap) it.copy(updatedAt = cap) else it },
        forwards = remote.forwards.map { if (it.updatedAt > cap) it.copy(updatedAt = cap) else it },
        tombstones = remote.tombstones.map { if (it.deletedAt > cap) it.copy(deletedAt = cap) else it },
    )

    private suspend fun mergeIn(remote: BackupData, moveTies: Boolean): Stats {
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
            // Keep the original deletion time.
            val mine = before[t.syncId]
            if (n > 0 || mine == null || mine.deletedAt < t.deletedAt) sync.putTombstone(t)
        }
        val tombs = sync.tombstones().associateBy { it.syncId }
        fun deleted(syncId: String, updatedAt: Long) = tombs[syncId]?.let { it.deletedAt >= updatedAt } ?: false
        suspend fun revived(syncId: String) { if (syncId in tombs) sync.dropTombstone(syncId) }

        // Every sync id the other device knows, alive or deleted.
        val remoteIds = HashSet<String>().apply {
            remote.groups.mapTo(this) { it.syncId }; remote.hosts.mapTo(this) { it.syncId }; remote.keys.mapTo(this) { it.syncId }
            remote.identities.mapTo(this) { it.syncId }; remote.snippets.mapTo(this) { it.syncId }; remote.forwards.mapTo(this) { it.syncId }
            remote.tombstones.mapTo(this) { it.syncId }
        }

        // 2. Groups. Names are unique: records known by sync id go first, so a rename frees its old name before a new
        // group takes it.
        suspend fun freeName(name: String): String {
            var n = 2
            while (hostDao.findGroup("$name ($n)") != null) n++
            return "$name ($n)"
        }
        /** The name group [syncId] (row [selfId]) may take: [name], unless a group with a smaller sync id holds it. */
        suspend fun claimName(name: String, syncId: String, selfId: Long?): String {
            val other = hostDao.findGroup(name)?.takeIf { it.id != selfId } ?: return name
            if (syncId < other.syncId) {
                // A new edit, which then reaches the other devices.
                hostDao.updateGroup(other.copy(name = freeName(name), updatedAt = renamedAt(other.updatedAt)))
                return name
            }
            return freeName(name)
        }
        val groupIds = HashMap<Long, Long>()
        val claimedGroups = HashSet<Long>()
        val knownGroups = remote.groups.filter { sync.group(it.syncId) != null }.mapTo(HashSet()) { it.syncId }
        for (g in remote.groups.sortedBy { it.syncId !in knownGroups }) {
            if (deleted(g.syncId, g.updatedAt)) continue
            val local = findLocal(sync.group(g.syncId), remoteIds, claimedGroups) { hostDao.findGroup(g.name) }
            if (local == null) {
                val name = claimName(g.name, g.syncId, null)
                // Renamed here: a new edit, so the new name goes back to the other device.
                val id = hostDao.insertGroup(g.copy(id = 0, name = name, updatedAt = if (name == g.name) g.updatedAt else renamedAt(g.updatedAt)))
                if (id > 0) { groupIds[g.id] = id; claimedGroups += id; st.added++; revived(g.syncId) }
                continue
            }
            groupIds[g.id] = local.id
            reconcile(
                g, local, st, moveTies, "host_group", HostGroup.serializer(),
                adopt = { id, at -> local.copy(name = g.name, color = g.color, sortOrder = g.sortOrder, icon = g.icon, syncId = id, updatedAt = at) },
                same = { a, b -> a.name == b.name && a.color == b.color && a.sortOrder == b.sortOrder && a.icon == b.icon },
                write = { c ->
                    val name = claimName(c.name, c.syncId, local.id)
                    // Renamed here: a new edit, so the new name goes back.
                    val renamed = if (name == c.name) c else c.copy(name = name, updatedAt = renamedAt(maxOf(g.updatedAt, local.updatedAt)))
                    tryWrite("group ${g.syncId}") { hostDao.updateGroup(renamed) }
                },
                setSyncId = { id, s -> sync.setGroupSyncId(id, s) },
            )
        }
        val remoteGroups = remote.groups.associateBy { it.id }
        suspend fun localGroup(remoteId: Long?): Long? =
            remoteId?.let { groupIds[it] ?: remoteGroups[it]?.let { g -> sync.group(g.syncId)?.id } }

        // 3. Keys. The fingerprint is the key itself: the same key on both sides is one record whatever its sync ids
        // (the unique index allows no other choice).
        val keyIds = HashMap<Long, Long>()
        val claimedKeys = HashSet<Long>()
        for (k in remote.keys) {
            if (deleted(k.syncId, k.updatedAt)) continue
            val local = findLocal(sync.key(k.syncId), null, claimedKeys) { keyDao.keyByFingerprint(k.fingerprint) }
            if (local == null) {
                val id = keyDao.insertKey(k.copy(id = 0))
                keyIds[k.id] = id; claimedKeys += id; st.added++; revived(k.syncId)
                continue
            }
            keyIds[k.id] = local.id
            reconcile(
                k, local, st, moveTies, "ssh_key", SshKey.serializer(),
                adopt = { id, at -> k.copy(id = local.id, createdAt = local.createdAt, syncId = id, updatedAt = at) },
                same = { a, b -> a.copy(updatedAt = 0, syncId = "") == b.copy(updatedAt = 0, syncId = "") },
                write = { c -> tryWrite("key ${k.syncId}") { keyDao.updateKey(c) } },
                setSyncId = { id, s -> sync.setKeySyncId(id, s) },
            )
        }

        // 4. Identities. From here, natural keys are looked up in a list read once: a row written meanwhile is claimed,
        // never looked up again.
        val identityIds = HashMap<Long, Long>()
        val claimedIdentities = HashSet<Long>()
        val identities = keyDao.allIdentities().groupBy { it.name to it.username }
        for (i in remote.identities) {
            if (deleted(i.syncId, i.updatedAt)) continue
            val local = findLocal(sync.identity(i.syncId), remoteIds, claimedIdentities) {
                identities[i.name to i.username]?.firstOrNull { it.syncId !in remoteIds && it.id !in claimedIdentities }
            }
            val keyId = i.keyId?.let(keyIds::get)
            if (local == null) {
                val id = keyDao.upsertIdentity(i.copy(id = 0, keyId = keyId))
                identityIds[i.id] = id; claimedIdentities += id; st.added++; revived(i.syncId)
                continue
            }
            identityIds[i.id] = local.id
            reconcile(
                i, local, st, moveTies, "identity", Identity.serializer(),
                adopt = { id, at -> i.copy(id = local.id, keyId = keyId, syncId = id, updatedAt = at) },
                same = { a, b -> a.copy(updatedAt = 0, syncId = "") == b.copy(updatedAt = 0, syncId = "") },
                write = { c -> keyDao.upsertIdentity(c); true },
                setSyncId = { id, s -> sync.setIdentitySyncId(id, s) },
            )
        }

        // 5. Hosts, jump targets before the hosts that use them.
        val hostIds = HashMap<Long, Long>()
        val claimedHosts = HashSet<Long>()
        val hostsByAlias = hostDao.allHosts().groupBy { it.alias }
        val remoteHosts = remote.hosts.associateBy { it.id }
        for (h in orderByJump(remote.hosts)) {
            if (deleted(h.syncId, h.updatedAt)) continue
            val local = findLocal(sync.host(h.syncId), remoteIds, claimedHosts) {
                hostsByAlias[h.alias]?.firstOrNull { it.syncId !in remoteIds && it.id !in claimedHosts }
            }
            val jump = h.jumpHostId?.let { j -> hostIds[j] ?: remoteHosts[j]?.let { rj -> sync.host(rj.syncId)?.id } }
            val mapped = h.copy(
                groupId = localGroup(h.groupId),
                keyId = h.keyId?.let(keyIds::get),
                identityId = h.identityId?.let(identityIds::get),
                jumpHostId = jump,
            )
            if (local == null) {
                val id = hostDao.upsertHost(mapped.copy(id = 0, lastConnectedAt = null))
                hostIds[h.id] = id; claimedHosts += id; st.added++; revived(h.syncId)
                continue
            }
            hostIds[h.id] = local.id
            reconcile(
                h, local, st, moveTies, "host", Host.serializer(),
                adopt = { id, at ->
                    mapped.copy(
                        // The system found by one device serves them all; each keeps its own once found.
                        id = local.id, lastConnectedAt = local.lastConnectedAt, osId = local.osId ?: h.osId, createdAt = local.createdAt,
                        syncId = id, updatedAt = at,
                    )
                },
                same = { a, b -> a.sameContent(b) },
                write = { c -> hostDao.upsertHost(c); true },
                setSyncId = { id, s -> sync.setHostSyncId(id, s) },
                // Not an edit: only the logo learnt from the other device.
                kept = { if (local.osId == null && h.osId != null) hostDao.setOs(local.id, h.osId) },
            )
        }

        // 6. Port forwards (need their host).
        val claimedForwards = HashSet<Long>()
        val forwards = tools.allForwards().groupBy { it.name to it.hostId }
        for (f in remote.forwards) {
            if (deleted(f.syncId, f.updatedAt)) continue
            val hostId = hostIds[f.hostId] ?: continue
            val local = findLocal(sync.forward(f.syncId), remoteIds, claimedForwards) {
                forwards[f.name to hostId]?.firstOrNull { it.syncId !in remoteIds && it.id !in claimedForwards }
            }
            if (local == null) {
                claimedForwards += tools.upsertForward(f.copy(id = 0, hostId = hostId)); st.added++; revived(f.syncId)
                continue
            }
            reconcile(
                f, local, st, moveTies, "port_forward", PortForward.serializer(),
                adopt = { id, at -> f.copy(id = local.id, hostId = hostId, syncId = id, updatedAt = at) },
                same = { a, b -> a.copy(updatedAt = 0, syncId = "") == b.copy(updatedAt = 0, syncId = "") },
                write = { c -> tools.upsertForward(c); true },
                setSyncId = { id, s -> sync.setForwardSyncId(id, s) },
            )
        }

        // 7. Snippets.
        val claimedSnippets = HashSet<Long>()
        val snippets = tools.allSnippets().groupBy { it.name to it.command }
        for (s in remote.snippets) {
            if (deleted(s.syncId, s.updatedAt)) continue
            val local = findLocal(sync.snippet(s.syncId), remoteIds, claimedSnippets) {
                snippets[s.name to s.command]?.firstOrNull { it.syncId !in remoteIds && it.id !in claimedSnippets }
            }
            if (local == null) {
                claimedSnippets += tools.upsertSnippet(s.copy(id = 0, runCount = 0)); st.added++; revived(s.syncId)
                continue
            }
            reconcile(
                s, local, st, moveTies, "snippet", Snippet.serializer(),
                adopt = { id, at -> s.copy(id = local.id, runCount = local.runCount, createdAt = local.createdAt, syncId = id, updatedAt = at) },
                same = { a, b -> a.copy(updatedAt = 0, syncId = "") == b.copy(updatedAt = 0, syncId = "") },
                write = { c -> tools.upsertSnippet(c); true },
                setSyncId = { id, sid -> sync.setSnippetSyncId(id, sid) },
            )
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

        // Tombstones older than 90 days are forgotten: every device has seen them by then. (Those dated more than a
        // year ahead were brought back before the merge, with SyncSchema.stampRepairs.)
        sync.pruneTombstones(System.currentTimeMillis() - 90L * 24 * 3600 * 1000)
        return st
    }

    /** Snapshot of this database for the other devices. */
    suspend fun snapshot(devices: List<SyncDevice>): BackupData = BackupData(
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
        /** Every field written, so that a default value is part of the content like any other. */
        private val canonicalJson = Json { encodeDefaults = true }

        /**
         * The canonical content of a record of [table]: its synchronised columns ([columns], SyncSchema.TABLES; in that order),
         * each written `name=value\n` with value `N` (null or missing), `I<decimal>` (number; booleans as 1 or 0) or
         * `T<UTF-8 length>:<text>`; a reference (SyncSchema.REFS) is the referenced record's sync id ([refs], by column;
         * missing: null), row ids differing between devices. Hashed with SHA-256, lower-case hex. sync.rs content_hash,
         * byte for byte.
         */
        fun contentHash(
            table: String,
            fields: JsonObject,
            refs: Map<String, String>,
            columns: List<String> = SyncSchema.TABLES[table].orEmpty(),
        ): String {
            val refColumns = SyncSchema.REFS[table].orEmpty()
            val out = StringBuilder()
            fun text(v: String) { out.append('T').append(v.toByteArray(Charsets.UTF_8).size).append(':').append(v) }
            for (c in columns) {
                out.append(c).append('=')
                if (c in refColumns) {
                    refs[c]?.let(::text) ?: out.append('N')
                } else {
                    when (val v = fields[c]) {
                        null, JsonNull -> out.append('N')
                        is JsonPrimitive -> when {
                            v.isString -> text(v.content)
                            v.booleanOrNull != null -> out.append(if (v.booleanOrNull == true) "I1" else "I0")
                            else -> out.append('I').append(v.content)
                        }
                        else -> text(v.toString())
                    }
                }
                out.append('\n')
            }
            return MessageDigest.getInstance("SHA-256").digest(out.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

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
