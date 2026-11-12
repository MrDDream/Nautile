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

    /** Renames and recolours [group]; false when another group already has that name. */
    suspend fun updateGroup(group: HostGroup): Boolean {
        val clash = dao.findGroup(group.name)
        if (clash != null && clash.id != group.id) return false
        dao.updateGroup(group)
        return true
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
        dao.findGroup(name)?.id ?: dao.insertGroup(HostGroup(name = name, sortOrder = (dao.allGroups().maxOfOrNull { it.sortOrder } ?: -1) + 1)).let { id ->
            if (id > 0) id else dao.findGroup(name)!!.id
        }

    /**
     * Saves imported hosts. Existing aliases are skipped; jump hosts are linked once all
     * hosts exist. Returns how many hosts were added.
     */
    suspend fun import(items: List<ImportedHost>, defaultGroup: String? = null): Int = db.withTransaction {
        var added = 0
        val ids = mutableMapOf<String, Long>()
        for (item in items) {
            val existing = dao.findByAlias(item.alias)
            if (existing != null) { ids[item.alias] = existing.id; continue }
            val group = (item.group ?: defaultGroup)?.let { groupId(it) }
            ids[item.alias] = dao.upsertHost(
                Host(
                    alias = item.alias,
                    hostname = item.hostname,
                    port = item.port,
                    username = item.username,
                    authMethod = if (item.identityFile != null) AuthMethod.KEY else AuthMethod.PASSWORD,
                    identity = item.identityFile,
                    groupId = group,
                    tags = item.tags.joinToString(","),
                ),
            )
            added++
        }
        for (item in items) {
            val jump = item.proxyJump?.substringAfter('@')?.substringBefore(':') ?: continue
            val hostId = ids[item.alias] ?: continue
            val jumpId = ids[jump] ?: dao.findByAlias(jump)?.id ?: continue
            dao.getHost(hostId)?.takeIf { it.jumpHostId == null }?.let { dao.upsertHost(it.copy(jumpHostId = jumpId)) }
        }
        added
    }
}
