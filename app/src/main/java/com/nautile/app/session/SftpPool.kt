package com.nautile.app.session

import com.nautile.i18n.tr

import com.nautile.ssh.SftpSession
import com.nautile.ssh.SshConnection
import com.nautile.ssh.openSftp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shares one SFTP connection per host between the file browser, the editor and transfers. */
class SftpPool(private val sessions: SessionManager) {
    private class Entry(val connection: SshConnection, val sftp: SftpSession, var refs: Int)

    private val entries = mutableMapOf<Long, Entry>()
    private val lock = Mutex()

    suspend fun acquire(hostId: Long): SftpSession = lock.withLock {
        entries[hostId]?.takeIf { it.connection.isConnected }?.let { it.refs++; return it.sftp }
        entries.remove(hostId)?.let { stale -> runCatching { stale.connection.close() } }
        withContext(Dispatchers.IO) {
            val plan = sessions.plan(hostId)
            val conn = sessions.connector(plan.host.alias).connect(plan.chain, proxy = plan.proxy)
            val sftp = try { conn.openSftp() } catch (e: Exception) { conn.close(); throw e }
            entries[hostId] = Entry(conn, sftp, 1)
            sftp
        }
    }

    suspend fun release(hostId: Long) {
        val toClose = lock.withLock {
            val e = entries[hostId] ?: return
            e.refs--
            if (e.refs > 0) return
            entries.remove(hostId)
        }
        withContext(Dispatchers.IO) { runCatching { toClose?.sftp?.close() }; runCatching { toClose?.connection?.close() } }
    }

    /** Runs [command] on the host over the pooled connection and returns its output. */
    suspend fun exec(hostId: Long, command: String): String {
        acquire(hostId)
        try {
            val conn = lock.withLock { entries[hostId]?.connection } ?: error(tr("Connexion perdue"))
            return withContext(Dispatchers.IO) { conn.exec(command) }
        } finally { release(hostId) }
    }

    /** Runs [block] with the host's SFTP session, releasing it afterwards. */
    suspend fun <T> with(hostId: Long, block: suspend (SftpSession) -> T): T {
        val sftp = acquire(hostId)
        try { return withContext(Dispatchers.IO) { block(sftp) } } finally { release(hostId) }
    }
}
