package com.nautile.app.session

import com.nautile.i18n.tr

import com.nautile.data.db.ToolsDao
import com.nautile.data.model.ForwardKind
import com.nautile.data.model.PortForward
import com.nautile.ssh.ActiveForward
import com.nautile.ssh.ForwardSpec
import com.nautile.ssh.ForwardType
import com.nautile.ssh.SshConnection
import com.nautile.ssh.startForward
import kotlinx.coroutines.CoroutineScope
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
        if (isOn(rule.id)) return
        set(rule.id, TunnelState.Starting)
        jobs[rule.id] = scope.launch(Dispatchers.IO) {
            try {
                val plan = sessions.plan(rule.hostId)
                val conn = sessions.connector(plan.host.alias).connect(plan.chain, proxy = plan.proxy, agentKeys = plan.agentKeys?.let { k -> { k } })
                val fwd = try {
                    conn.startForward(spec(rule))
                } catch (e: Exception) {
                    conn.close(); throw e
                }
                running[rule.id] = Running(conn, fwd)
                set(rule.id, TunnelState.Active(fwd.boundPort))
                // Watch the connection; report when it drops.
                while (isActive && conn.isConnected) delay(2_000)
                if (running.remove(rule.id) != null) {
                    runCatching { fwd.close() }; runCatching { conn.close() }
                    set(rule.id, TunnelState.Failed(tr("Connexion perdue")))
                }
            } catch (e: Exception) {
                set(rule.id, TunnelState.Failed(ChannelBackend.describe(e)))
            }
        }
    }

    fun stop(id: Long) {
        jobs.remove(id)?.cancel()
        running.remove(id)?.let { r -> Thread { runCatching { r.forward.close() }; runCatching { r.connection.close() } }.start() }
        _states.update { it - id }
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
