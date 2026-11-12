package com.nautile.app.session

import com.nautile.data.db.KnownHostDao
import com.nautile.data.model.KnownHost
import com.nautile.ssh.HostKeyInfo
import com.nautile.ssh.KnownHostStore
import com.nautile.ssh.KnownKey

/** known_hosts in the encrypted database. Called from SSH threads, never the main thread. */
class RoomKnownHostStore(private val dao: KnownHostDao) : KnownHostStore {
    override fun find(host: String, port: Int): List<KnownKey> =
        dao.findBlocking(host, port).map { KnownKey(it.keyType, it.publicKey, it.fingerprint) }

    override fun trust(info: HostKeyInfo, replace: Boolean) {
        val row = KnownHost(host = info.host, port = info.port, keyType = info.keyType, publicKey = info.publicKey, fingerprint = info.fingerprint)
        if (replace) dao.replaceBlocking(row) else dao.upsertBlocking(row)
    }
}
