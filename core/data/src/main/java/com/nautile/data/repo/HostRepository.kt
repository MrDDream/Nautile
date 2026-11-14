package com.nautile.data.repo

import androidx.room.withTransaction
import com.nautile.data.db.NautileDatabase
import com.nautile.data.importer.ImportedHost
import com.nautile.data.model.AuthMethod
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import kotlinx.coroutines.flow.Flow

class HostRepository(private val db: NautileDatabase) {
    private val dao = db.hostDao()

    val hosts: Flow<List<Host>> = dao.observeHosts()
    val groups: Flow<List<HostGroup>> = dao.observeGroups()

    suspend fun get(id: Long): Host? = dao.getHost(id)
    suspend fun save(host: Host): Long {
        val id = dao.upsertHost(host)
        return if (host.id != 0L) host.id else id
    }
    suspend fun delete(host: Host) = dao.deleteHost(host)
    suspend fun markConnected(id: Long) = dao.markConnected(id, System.currentTimeMillis())
    suspend fun setOs(id: Long, osId: String) = dao.setOs(id, osId)

    /**
     * Renames, recolours and changes the icon of [group] as it is now in the database (its place in the list
     * may have changed since [group] was read); false when another group already has that name.
     */
    suspend fun updateGroup(group: HostGroup): Boolean = db.withTransaction {
        val clash = dao.findGroup(group.name)
        if (clash != null && clash.id != group.id) return@withTransaction false
        val current = dao.getGroup(group.id) ?: return@withTransaction false
        dao.updateGroup(current.copy(name = group.name, color = group.color, icon = group.icon))
        true
    }

    /** Deletes [group]; its hosts become ungrouped. */
    suspend fun deleteGroup(group: HostGroup) = dao.deleteGroup(group)

    /** Moves [group] by [delta] places in the list and renumbers every group. */
    suspend fun moveGroup(group: HostGroup, delta: Int) = db.withTransaction {
        val list = dao.allGroups().sortedWith(compareBy<HostGroup> { it.sortOrder }.thenBy { it.name.lowercase() }).toMutableList()
        val i = list.indexOfFirst { it.id == group.id }
        if (i < 0) return@withTransaction
        val j = (i + delta).coerceIn(0, list.lastIndex)
        list.add(j, list.removeAt(i))
        list.forEachIndexed { index, g -> if (g.sortOrder != index) dao.updateGroup(g.copy(sortOrder = index)) }
    }

    /** Puts [hostId] in the group named [groupName] (created if needed), or in none. */
    suspend fun moveHost(hostId: Long, groupName: String?) =
        dao.setHostGroup(hostId, groupName?.trim()?.takeIf { it.isNotEmpty() }?.let { groupId(it) })

    /** Returns the id of the group named [name], creating it if needed (at the end of the list). */
    suspend fun groupId(name: String): Long =
        dao.findGroup(name)?.id ?: dao.insertGroup(HostGroup(name = name, sortOrder = nextSortOrder())).let { id ->
            if (id > 0) id else dao.findGroup(name)!!.id
        }

    /** Adds [group] at the end of the list; false when a group already has that name. */
    suspend fun createGroup(group: HostGroup): Boolean = db.withTransaction {
        if (dao.findGroup(group.name) != null) return@withTransaction false
        dao.insertGroup(group.copy(id = 0, sortOrder = nextSortOrder())) > 0
    }

    private suspend fun nextSortOrder() = (dao.allGroups().maxOfOrNull { it.sortOrder } ?: -1) + 1

    /** What [import] did: hosts added, and those whose IdentityFile is not in the keychain (they use a password). */
    data class ImportResult(val added: Int, val missingKeys: Int = 0)

    /**
     * Saves imported hosts. Existing aliases are skipped (left untouched); jump hosts are linked once all
     * hosts exist. An IdentityFile is matched by file name with a key of the keychain (the file itself is not
     * readable from here); without a match the host asks for a password.
     */
    suspend fun import(items: List<ImportedHost>): ImportResult = db.withTransaction {
        var added = 0
        var missingKeys = 0
        val keys = db.keyDao().allKeys()
        val ids = mutableMapOf<String, Long>()
        val inserted = mutableSetOf<Long>()
        for (item in items) {
            val existing = dao.findByAlias(item.alias)
            if (existing != null) { ids[item.alias] = existing.id; continue }
            val group = item.group?.let { groupId(it) }
            val key = item.identityFile?.let { path ->
                val file = path.trim('"').substringAfterLast('/').substringAfterLast('\\')
                keys.firstOrNull { it.name.equals(file, ignoreCase = true) || it.name.equals(file.substringBeforeLast('.'), ignoreCase = true) }
            }
            if (item.identityFile != null && key == null) missingKeys++
            val id = dao.upsertHost(
                Host(
                    alias = item.alias,
                    hostname = item.hostname,
                    port = item.port,
                    username = item.username,
                    authMethod = if (key != null) AuthMethod.KEY else AuthMethod.PASSWORD,
                    keyId = key?.id,
                    groupId = group,
                    tags = item.tags.joinToString(","),
                ),
            )
            ids[item.alias] = id
            inserted += id
            added++
        }
        for (item in items) {
            val jump = item.proxyJump?.substringAfter('@')?.substringBefore(':') ?: continue
            // Only the hosts added now: the existing ones are left as they are.
            val hostId = ids[item.alias]?.takeIf { it in inserted } ?: continue
            val jumpId = ids[jump] ?: dao.findByAlias(jump)?.id ?: continue
            if (jumpId == hostId) continue
            dao.getHost(hostId)?.takeIf { it.jumpHostId == null }?.let { dao.upsertHost(it.copy(jumpHostId = jumpId)) }
        }
        ImportResult(added, missingKeys)
    }
}
