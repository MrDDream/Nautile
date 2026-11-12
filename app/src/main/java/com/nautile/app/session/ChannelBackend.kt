package com.nautile.app.session

import com.nautile.i18n.tr

import com.termux.terminal.TerminalSession
import com.nautile.ssh.TerminalChannel
import com.nautile.ssh.SshCancelledException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Feeds a Termux [TerminalSession] from an SSH shell channel. */
class ChannelBackend(
    private val session: ActiveSession,
    /** Opens the channel (blocking, off the main thread); reports progress through the callback. */
    private val open: (columns: Int, rows: Int, onStep: (String) -> Unit) -> TerminalChannel,
    private val startupCommand: String?,
    private val notice: String?,
    private val onConnected: () -> Unit,
    /** Receives a copy of everything the server sends (session log), closed at the end. */
    private val log: java.io.OutputStream? = null,
) : TerminalSession.Backend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var shell: TerminalChannel? = null
    @Volatile private var columns = 80
    @Volatile private var rows = 24
    @Volatile private var closedByUser = false
    @Volatile private var finished = false

    private val terminal: TerminalSession get() = session.terminal

    override fun start(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        scope.launch { run() }
    }

    private fun run() {
        try {
            notice?.let { print("\u001b[33m$it\u001b[0m\r\n") }
            val sh = open(columns, rows) { step ->
                session._state.value = SessionState.Connecting(step)
                print("\u001b[90m$step\u001b[0m\r\n")
            }
            shell = sh
            if (closedByUser) { finish(tr("Session fermée"), failed = false); return }
            session._state.value = SessionState.Connected
            onConnected()
            startupCommand?.let { cmd -> sh.output.write((cmd.replace("\n", "\r") + "\r").toByteArray()); sh.output.flush() }
            val buffer = ByteArray(8192)
            val input = sh.input
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    terminal.feed(buffer, 0, n)
                    log?.let { runCatching { it.write(buffer, 0, n) } }
                }
            }
            finish(if (closedByUser) tr("Session fermée") else tr("Connexion fermée"), failed = false)
        } catch (e: Exception) {
            if (closedByUser) finish(tr("Session fermée"), failed = false)
            else finish(describe(e), failed = true)
        }
    }

    private fun print(text: String) {
        val bytes = text.toByteArray()
        terminal.feed(bytes, 0, bytes.size)
    }

    private fun finish(reason: String, failed: Boolean) {
        if (finished) return
        finished = true
        session._state.value = SessionState.Closed(reason, failed)
        terminal.remoteExited(if (failed) tr("Erreur : %1\$s", reason) else reason)
        release()
    }

    private fun release() {
        runCatching { log?.close() }
        runCatching { shell?.close() }
        shell = null
        scope.cancel()
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        val out = shell?.output ?: return
        out.write(data, offset, count)
        out.flush()
    }

    override fun resize(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        val sh = shell ?: return
        scope.launch { sh.resize(columns, rows) }
    }

    override fun close() {
        closedByUser = true
        val sh = shell
        if (sh == null) {
            // Still connecting: the connection is dropped as soon as it opens.
            finish(tr("Session fermée"), failed = false)
            return
        }
        // Closing the channel ends the read loop, which reports the end.
        Thread { runCatching { sh.close() } }.start()
    }

    companion object {
        fun describe(e: Throwable): String = when {
            e is SshCancelledException -> e.message ?: tr("Connexion annulée")
            e is UnknownHostException -> tr("Hôte introuvable (%1\$s)", e.message)
            e is ConnectException -> tr("Connexion refusée")
            e is NoRouteToHostException -> tr("Hôte injoignable")
            e is SocketTimeoutException -> tr("Délai de connexion dépassé")
            e is UserAuthException -> e.message?.takeIf { it.isNotBlank() && !it.startsWith("Exhausted") } ?: tr("Authentification refusée")
            e is TransportException && e.message?.contains("verify", ignoreCase = true) == true -> tr("Clé d'hôte refusée")
            e.cause?.let { it is SshCancelledException } == true -> e.cause!!.message ?: tr("Connexion annulée")
            e is IOException -> e.message ?: tr("Erreur réseau")
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
