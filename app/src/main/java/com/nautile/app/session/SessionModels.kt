package com.nautile.app.session

import com.nautile.i18n.tr

import com.termux.terminal.TerminalSession
import com.nautile.ssh.HostKeyInfo
import com.nautile.ssh.KnownKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface SessionState {
    data class Connecting(val step: String) : SessionState
    data object Connected : SessionState
    data class Closed(val reason: String, val failed: Boolean) : SessionState
}

class ActiveSession internal constructor(
    val id: Long,
    val hostId: Long,
    val alias: String,
    val address: String,
    val protocolLabel: String,
) {
    internal val _state = MutableStateFlow<SessionState>(SessionState.Connecting(tr("Préparation…")))
    val state: StateFlow<SessionState> get() = _state
    lateinit var terminal: TerminalSession
        internal set
    internal lateinit var backend: ChannelBackend
    /** The unsaved host of a quick connection (hostId 0), to reconnect to it. */
    internal var quickHost: com.nautile.data.model.Host? = null
    /** A saved host (files, monitor and history need one). */
    val isSaved: Boolean get() = hostId != 0L
    internal val _typed = MutableStateFlow<String?>("")
    /** The command line being typed, when it can be followed (for history suggestions). */
    val typed: StateFlow<String?> get() = _typed
}

/** A question waiting for the user; [answer] unblocks the SSH thread. */
sealed class PendingPrompt(val sessionAlias: String) {
    class HostKey(alias: String, val info: HostKeyInfo, val previous: KnownKey?) : PendingPrompt(alias) {
        val answer = CompletableDeferred<Boolean>()
    }
    class Secret(alias: String, val title: String, val label: String) : PendingPrompt(alias) {
        val answer = CompletableDeferred<String?>()
    }
    /** RDP server certificate not trusted yet, or different from the trusted one ([previous]). */
    class Certificate(alias: String, val address: String, val cert: com.nautile.rdp.RdpCertificate, val fingerprint: String, val previous: String?) : PendingPrompt(alias) {
        val answer = CompletableDeferred<Boolean>()
    }
    /** Windows logon asked by an RDP server. */
    class Login(alias: String, val username: String, val domain: String, val gateway: Boolean) : PendingPrompt(alias) {
        val answer = CompletableDeferred<com.nautile.rdp.RdpCredentials?>()
    }
}

/** The assistant's conversation of this server (one per saved host, or per address for a quick connection). */
val ActiveSession.aiKey: String get() = com.nautile.app.ai.AiAssistant.conversationKey(hostId, address)
