package com.nautile.app.session

import com.nautile.data.db.ToolsDao
import com.nautile.data.model.ForwardKind
import com.nautile.data.model.PortForward
import com.nautile.i18n.tr
import com.nautile.ssh.ActiveForward
import com.nautile.ssh.ForwardSpec
import com.nautile.ssh.ForwardType
import com.nautile.ssh.SshConnection
import com.nautile.ssh.startForward
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

sealed interface TunnelState {
    data object Starting : TunnelState
    data class Active(val port: Int) : TunnelState
    data class Failed(val reason: String) : TunnelState
}

/** Runs port forwarding rules, each on its own SSH connection, until stopped or disconnected. */
class TunnelManager(
    private val sessions: SessionManager,
    private val dao: ToolsDao,
    private val scope: CoroutineScope,
) {
    private class Running(val connection: SshConnection, val forward: ActiveForward)

    private val running = ConcurrentHashMap<Long, Running>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val _states = MutableStateFlow<Map<Long, TunnelState>>(emptyMap())
    val states: StateFlow<Map<Long, TunnelState>> = _states.asStateFlow()

    fun isOn(id: Long) = _states.value[id].let { it is TunnelState.Active || it is TunnelState.Starting }

    fun start(rule: PortForward) {
        // A start that a stop (and maybe a new start) overtook must leave no trace: everything it
        // records is checked against its own job.
        lateinit var job: Job
        fun current() = jobs[rule.id] === job
        fun report(state: TunnelState) = synchronized(this) { if (current()) set(rule.id, state) }
        synchronized(this) {
            if (isOn(rule.id)) return
            set(rule.id, TunnelState.Starting)
            job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                var mine: Running? = null
                try {
                    val plan = sessions.plan(rule.hostId)
                    val conn = sessions.connect(plan)
                    val fwd = try {
                        if (!isActive || !current()) error("stopped")
                        conn.startForward(spec(rule))
                    } catch (e: Exception) {
                        conn.close(); throw e
                    }
                    val r = Running(conn, fwd)
                    // stop() during the connection: it found nothing to close, so close it here.
                    val registered = synchronized(this@TunnelManager) {
                        (isActive && current()).also { ok ->
                            if (ok) { running[rule.id] = r; mine = r; set(rule.id, TunnelState.Active(fwd.boundPort)) }
                        }
                    }
                    if (!registered) { runCatching { fwd.close() }; runCatching { conn.close() }; return@launch }
                    // Watch the connection; report when it drops.
                    while (isActive && conn.isConnected) delay(2_000)
                    if (running.remove(rule.id, r)) {
                        runCatching { fwd.close() }; runCatching { conn.close() }
                        report(TunnelState.Failed(tr("Connexion perdue")))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mine?.let { running.remove(rule.id, it) }
                    if (isActive) report(TunnelState.Failed(ChannelBackend.describe(e)))
                } finally {
                    jobs.remove(rule.id, job)
                }
            }
            jobs[rule.id] = job
        }
        job.start()
    }

    fun stop(id: Long) {
        val r = synchronized(this) {
            jobs.remove(id)?.cancel()
            _states.update { it - id }
            running.remove(id)
        }
        r?.let { Thread { runCatching { it.forward.close() }; runCatching { it.connection.close() } }.start() }
    }

    /** Starts the host's auto-start rules (called when a terminal session to it connects). */
    suspend fun startAuto(hostId: Long) {
        dao.autoForwards(hostId).forEach(::start)
    }

    private fun set(id: Long, state: TunnelState) = _states.update { it + (id to state) }

    companion object {
        fun spec(rule: PortForward) = ForwardSpec(
            type = when (rule.kind) {
                ForwardKind.LOCAL -> ForwardType.LOCAL
                ForwardKind.REMOTE -> ForwardType.REMOTE
                ForwardKind.DYNAMIC -> ForwardType.DYNAMIC
            },
            bindHost = rule.bindHost, bindPort = rule.bindPort, destHost = rule.destHost, destPort = rule.destPort,
        )
    }
}
