package com.nautile.data.sync

import com.nautile.data.backup.BackupData
import com.nautile.data.model.Protocol

/**
 * What a device may leave out of the synchronisation, by theme (desktop: sync.rs SyncPart). Records of a theme left
 * out are neither taken from the others nor sent to them, and what the others wrote of it stays in the shared file
 * ([carry]).
 *
 * The themes follow the references between records: a host points to its keys, identity, group and jump host, so
 * they travel together; a tunnel points to its host, so tunnels are left out whenever hosts are ([effective]).
 */
enum class SyncPart(internal val kinds: List<String>) {
    /** Hosts, groups, SSH keys and identities. */
    HOSTS(listOf("host_group", "host", "ssh_key", "identity")),
    SNIPPETS(listOf("snippet")),
    TUNNELS(listOf("port_forward")),
    /** The trusted host keys. */
    KNOWN_HOSTS(listOf("known_host"));

    companion object {
        /** The themes really left out: [skip], plus the tunnels when the hosts are. */
        fun effective(skip: Set<SyncPart>): Set<SyncPart> = if (HOSTS in skip) skip + TUNNELS else skip

        /**
         * A local shell or a serial port only exists on the device it was made on: never taken from another device nor
         * sent to one, whatever the themes (and a deletion from elsewhere never removes one: SyncDao.deleteHost).
         */
        fun deviceOnly(p: Protocol) = p == Protocol.LOCAL || p == Protocol.SERIAL

        private fun shared(data: BackupData) =
            if (data.hosts.none { deviceOnly(it.protocol) }) data else data.copy(hosts = data.hosts.filterNot { deviceOnly(it.protocol) })

        /** [data] without the themes in [skip] (records and deletions): what is taken from, or sent to, another device. */
        fun without(data: BackupData, skip: Set<SyncPart>): BackupData = take(shared(data), null, skip)

        /** The themes in [skip] of what is written to the shared file taken from the file as it was ([from]). */
        fun carry(data: BackupData, from: BackupData, skip: Set<SyncPart>): BackupData = take(shared(data), from, skip)

        private fun take(to: BackupData, from: BackupData?, skip: Set<SyncPart>): BackupData {
            val parts = effective(skip)
            if (parts.isEmpty()) return to
            val kinds = parts.flatMap { it.kinds }.toSet()
            var d = to
            if (HOSTS in parts) d = d.copy(
                groups = from?.groups.orEmpty(), hosts = from?.hosts.orEmpty(),
                keys = from?.keys.orEmpty(), identities = from?.identities.orEmpty(),
            )
            if (SNIPPETS in parts) d = d.copy(snippets = from?.snippets.orEmpty())
            if (TUNNELS in parts) d = d.copy(forwards = from?.forwards.orEmpty())
            if (KNOWN_HOSTS in parts) d = d.copy(knownHosts = from?.knownHosts.orEmpty())
            return d.copy(
                tombstones = d.tombstones.filter { it.kind !in kinds } + from?.tombstones.orEmpty().filter { it.kind in kinds },
            )
        }
    }
}
