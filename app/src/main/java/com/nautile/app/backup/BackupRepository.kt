package com.nautile.app.backup

import com.nautile.i18n.tr

import androidx.room.withTransaction
import com.nautile.data.backup.BackupCodec
import com.nautile.data.backup.BackupData
import com.nautile.data.db.NautileDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Export/import of the whole configuration as an encrypted file (see [BackupCodec]). */
class BackupRepository(private val db: NautileDatabase) {

    data class Summary(val hosts: Int, val keys: Int, val identities: Int, val snippets: Int, val forwards: Int, val knownHosts: Int) {
        override fun toString() = listOf(
            hosts to tr("hôte(s)"), keys to tr("clé(s)"), identities to tr("identité(s)"), snippets to tr("snippet(s)"), forwards to tr("tunnel(s)"), knownHosts to tr("clé(s) d'hôte"),
        ).filter { it.first > 0 }.joinToString(", ") { "${it.first} ${it.second}" }.ifEmpty { tr("rien de nouveau") }
    }

    suspend fun export(password: String): String = withContext(Dispatchers.Default) {
        val data = BackupData(
            groups = db.hostDao().allGroups(),
            hosts = db.hostDao().allHosts(),
            keys = db.keyDao().allKeys(),
            identities = db.keyDao().allIdentities(),
            knownHosts = db.knownHostDao().all(),
            snippets = db.toolsDao().allSnippets(),
            forwards = db.toolsDao().allForwards(),
        )
        BackupCodec.export(data, password)
    }

    /** Merges a backup into the current data: nothing existing is overwritten or deleted. */
    suspend fun import(text: String, password: String): Summary {
        val data = withContext(Dispatchers.Default) { BackupCodec.import(text, password) }
        return db.withTransaction {
            val hostDao = db.hostDao(); val keyDao = db.keyDao(); val tools = db.toolsDao()

            val groupIds = data.groups.associate { g ->
                g.id to (hostDao.findGroup(g.name)?.id ?: hostDao.insertGroup(g.copy(id = 0)))
            }
            var newKeys = 0
            val keyIds = data.keys.associate { k ->
                k.id to (keyDao.keyByFingerprint(k.fingerprint)?.id ?: keyDao.insertKey(k.copy(id = 0)).also { newKeys++ })
            }
            val existingIdentities = keyDao.allIdentities()
            var newIdentities = 0
            val identityIds = data.identities.associate { i ->
                i.id to (existingIdentities.firstOrNull { it.name == i.name && it.username == i.username }?.id
                    ?: keyDao.upsertIdentity(i.copy(id = 0, keyId = i.keyId?.let(keyIds::get))).also { newIdentities++ })
            }
            var newHosts = 0
            val hostIds = data.hosts.associate { h ->
                h.id to (hostDao.findByAlias(h.alias)?.id ?: hostDao.upsertHost(
                    h.copy(
                        id = 0, jumpHostId = null,
                        groupId = h.groupId?.let(groupIds::get),
                        keyId = h.keyId?.let(keyIds::get),
                        identityId = h.identityId?.let(identityIds::get),
                    ),
                ).also { newHosts++ })
            }
            // Jump hosts once every host exists.
            data.hosts.filter { it.jumpHostId != null }.forEach { h ->
                val id = hostIds[h.id] ?: return@forEach
                val jump = hostIds[h.jumpHostId] ?: return@forEach
                hostDao.getHost(id)?.takeIf { it.jumpHostId == null }?.let { hostDao.upsertHost(it.copy(jumpHostId = jump)) }
            }
            val knownBefore = db.knownHostDao().all().map { Triple(it.host, it.port, it.keyType) }.toSet()
            var newKnown = 0
            data.knownHosts.filter { Triple(it.host, it.port, it.keyType) !in knownBefore }.forEach {
                db.knownHostDao().upsertBlocking(it.copy(id = 0)); newKnown++
            }
            val snippets = tools.allSnippets().map { it.name to it.command }.toSet()
            var newSnippets = 0
            data.snippets.filter { (it.name to it.command) !in snippets }.forEach { tools.upsertSnippet(it.copy(id = 0)); newSnippets++ }
            val forwards = tools.allForwards().map { it.name to it.hostId }.toSet()
            var newForwards = 0
            data.forwards.forEach { f ->
                val host = hostIds[f.hostId] ?: return@forEach
                if ((f.name to host) !in forwards) { tools.upsertForward(f.copy(id = 0, hostId = host)); newForwards++ }
            }
            Summary(newHosts, newKeys, newIdentities, newSnippets, newForwards, newKnown)
        }
    }
}
