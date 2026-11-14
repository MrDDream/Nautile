package com.nautile.app.session

import com.nautile.app.ai.AiAssistant
import com.nautile.data.model.Host
import com.nautile.i18n.tr
import com.nautile.rdp.RdpCertificate
import com.nautile.rdp.RdpCredentials
import com.nautile.ssh.HostKeyInfo
import com.nautile.ssh.KnownKey
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface SessionState {
    data class Connecting(val step: String) : SessionState
    data object Connected : SessionState
    data class Closed(val reason: String, val failed: Boolean) : SessionState
}

/** What a question is asked for (a terminal tab, a remote desktop): closing it withdraws its questions. */
interface PromptOwner {
    val isClosing: Boolean
}

class ActiveSession internal constructor(
    val id: Long,
    val hostId: Long,
    val alias: String,
    val address: String,
    val protocolLabel: String,
) : PromptOwner {
    internal val _state = MutableStateFlow<SessionState>(SessionState.Connecting(tr("Préparation…")))
    val state: StateFlow<SessionState> get() = _state
    lateinit var terminal: TerminalSession
        internal set
    internal lateinit var backend: ChannelBackend
    /** The unsaved host of a quick connection (hostId 0), to reconnect to it. */
    internal var quickHost: Host? = null
    /** A saved host (files, monitor and history need one). */
    val isSaved: Boolean get() = hostId != 0L
    internal val _typed = MutableStateFlow<String?>("")
    /** Set when the user closes the tab, possibly while it is still connecting. */
    @Volatile internal var closing = false
    override val isClosing: Boolean get() = closing
    /** Follows typed commands for the history; null for sessions without one yet. */
    internal var tracker: LineTracker? = null
    /** The command line being typed, when it can be followed (for history suggestions). */
    val typed: StateFlow<String?> get() = _typed
    /** The assistant request running for this session, kept while its sheet is hidden (app locked). */
    val assistant = AssistantJob()
    internal val _notice = MutableStateFlow<SessionNotice?>(null)
    /** A message for the terminal screen, until it is dismissed. */
    val notice: StateFlow<SessionNotice?> get() = _notice
    fun dismissNotice(shown: SessionNotice) { _notice.compareAndSet(shown, null) }
    /** A program's OSC 52 was already ignored once in this session (the notice is shown only then). */
    internal var remoteClipboardNoticed = false
}

/** A message shown over a terminal session. */
sealed interface SessionNotice {
    /** Typed or pasted text not sent: too large at once ([tooLarge]), or the server has not read the previous input yet. */
    data class InputRefused(val bytes: Int, val tooLarge: Boolean) : SessionNotice
    /** A program asked to set the clipboard (OSC 52) and the setting does not allow it. */
    data object RemoteClipboardBlocked : SessionNotice
}

/** A question waiting for the user; [answer] unblocks the SSH thread. */
sealed class PendingPrompt(val sessionAlias: String) {
    /** What asked (a terminal session…), to withdraw the question when it goes away. */
    @Volatile internal var owner: Any? = null

    /** Answers « no » / « cancel », as if the user had refused. */
    abstract fun cancel()

    class HostKey(alias: String, val info: HostKeyInfo, val previous: KnownKey?) : PendingPrompt(alias) {
        val answer = CompletableDeferred<Boolean>()
        override fun cancel() { answer.complete(false) }
    }
    class Secret(alias: String, val title: String, val label: String) : PendingPrompt(alias) {
        val answer = CompletableDeferred<String?>()
        override fun cancel() { answer.complete(null) }
    }
    /** RDP server certificate not trusted yet, or different from the trusted one ([previous]). */
    class Certificate(alias: String, val address: String, val cert: RdpCertificate, val fingerprint: String, val previous: String?) : PendingPrompt(alias) {
        val answer = CompletableDeferred<Boolean>()
        override fun cancel() { answer.complete(false) }
    }
    /** Windows logon asked by an RDP server. */
    class Login(alias: String, val username: String, val domain: String, val gateway: Boolean) : PendingPrompt(alias) {
        val answer = CompletableDeferred<RdpCredentials?>()
        override fun cancel() { answer.complete(null) }
    }
    /** The server of [sessionAlias] wants to sign with a key outside its own chain, through the forwarded agent. */
    class AgentUse(alias: String, val keyName: String, val fingerprint: String) : PendingPrompt(alias) {
        val answer = CompletableDeferred<Boolean>()
        override fun cancel() { answer.complete(false) }
    }
}

/** The assistant's conversation of this server (one per saved host, or per address for a quick connection). */
val ActiveSession.aiKey: String get() = AiAssistant.conversationKey(hostId, address)
