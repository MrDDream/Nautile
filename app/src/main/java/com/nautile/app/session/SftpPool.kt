package com.nautile.app.session

import com.nautile.ssh.SftpSession
import com.nautile.ssh.SshConnection
import com.nautile.ssh.openSftp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shares one SFTP connection per host between the file browser, the editor, the monitor and
 * transfers. Each [acquire] returns a [Lease] released exactly once: its count belongs to the
 * connection it was taken on, so a reconnection never makes an old holder close the new one.
 * Connections are made outside the pool's lock (one at a time per host): a slow host or a
 * question on screen does not block the other hosts.
 */
class SftpPool internal constructor(private val open: suspend (hostId: Long) -> Pair<SshConnection, SftpSession>) {
    constructor(sessions: SessionManager) : this({ hostId ->
        val plan = sessions.plan(hostId)
        val conn = sessions.connect(plan, withAgent = false)
        val sftp = try { conn.openSftp() } catch (e: Exception) { conn.close(); throw e }
        conn to sftp
    })

    internal class Entry(val connection: SshConnection, val sftp: SftpSession) {
        /** Leases taken on this connection, under [lock]. */
        var refs = 0
    }

    /** A hold on a host's connection; give it back with [release]. */
    class Lease internal constructor(val hostId: Long, internal val entry: Entry) {
        internal val released = AtomicBoolean(false)
        val sftp: SftpSession get() = entry.sftp
        val connection: SshConnection get() = entry.connection
    }

    /** A connection being made, shared by everyone asking for that host meanwhile. */
    private class Connecting {
        lateinit var job: Deferred<Unit>
        /** Callers waiting for it, under [lock]; each gets one lease. */
        var waiters = 0
        /** Set under [lock] once connected, with refs = the waiters at that time. */
        var entry: Entry? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = mutableMapOf<Long, Entry>()
    private val pending = mutableMapOf<Long, Connecting>()
    private val lock = Mutex()

    suspend fun acquire(hostId: Long): Lease {
        val c: Connecting
        lock.withLock {
            entries[hostId]?.let { e ->
                if (e.connection.isConnected) { e.refs++; return Lease(hostId, e) }
                // Dropped: forget it; its holders close it when they release it.
                entries.remove(hostId)
                if (e.refs == 0) closeLater(e)
            }
            c = pending.getOrPut(hostId) { Connecting().also { it.job = scope.async { establish(hostId, it) } } }
            c.waiters++
        }
        try {
            c.job.await()
        } catch (e: CancellationException) {
            // This caller gave up: its share of the connection (if made) goes back.
            withContext(NonCancellable) {
                lock.withLock { c.entry?.let { releaseLocked(hostId, it) } ?: run { c.waiters-- } }
            }
            throw e
        }
        return Lease(hostId, c.entry!!)
    }

    private suspend fun establish(hostId: Long, c: Connecting) {
        val opened = runCatching { open(hostId) }
        lock.withLock {
            if (pending[hostId] === c) pending.remove(hostId)
            val (conn, sftp) = opened.getOrThrow()
            val e = Entry(conn, sftp)
            if (c.waiters == 0) { closeLater(e); return }
            e.refs = c.waiters
            entries.put(hostId, e)?.let { old -> if (old.refs == 0) closeLater(old) }
            c.entry = e
        }
    }

    /** Gives [lease] back (later calls do nothing); the connection closes with its last lease. */
    suspend fun release(lease: Lease) {
        if (!lease.released.compareAndSet(false, true)) return
        withContext(NonCancellable) { lock.withLock { releaseLocked(lease.hostId, lease.entry) } }
    }

    private fun releaseLocked(hostId: Long, e: Entry) {
        e.refs--
        if (e.refs > 0) return
        if (entries[hostId] === e) entries.remove(hostId)
        closeLater(e)
    }

    private fun closeLater(e: Entry) {
        scope.launch { runCatching { e.sftp.close() }; runCatching { e.connection.close() } }
    }

    /** Runs [command] on the host over the pooled connection and returns its (bounded) output. */
    suspend fun exec(hostId: Long, command: String): String {
        val lease = acquire(hostId)
        try {
            return withContext(Dispatchers.IO) { lease.connection.exec(command) }
        } finally { release(lease) }
    }

    /** Runs [block] with the host's SFTP session, releasing it afterwards. */
    suspend fun <T> with(hostId: Long, block: suspend (SftpSession) -> T): T {
        val lease = acquire(hostId)
        try { return withContext(Dispatchers.IO) { block(lease.sftp) } } finally { release(lease) }
    }
}
