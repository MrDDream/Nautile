package com.nautile.app.session

import com.nautile.i18n.tr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.nautile.app.keys.KeyRepository
import com.nautile.data.db.KnownHostDao
import com.nautile.data.model.ProxyKind
import com.nautile.ssh.ProxyConfig
import com.nautile.ssh.ProxyType
import com.nautile.data.model.AuthMethod
import com.nautile.data.model.Host
import com.nautile.data.model.Protocol
import com.nautile.data.repo.HostRepository
import com.nautile.ssh.HostKeyInfo
import com.nautile.ssh.KnownKey
import com.nautile.ssh.SshAuth
import com.nautile.ssh.SshConnector
import com.nautile.ssh.SshPrompts
import com.nautile.ssh.SshTarget
import com.nautile.ssh.TerminalChannel
import com.nautile.ssh.TelnetChannel
import com.nautile.ssh.PortKnocker
import com.nautile.ssh.MoshChannel
import com.nautile.ssh.OsDetector
import com.nautile.app.serial.SerialChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Owns every open terminal session; outlives screens, kept alive by [SessionService]. */
class SessionManager(
    private val context: Context,
    private val hosts: HostRepository,
    private val keys: KeyRepository,
    private val toolsDao: com.nautile.data.db.ToolsDao,
    private val knownHosts: KnownHostDao,
    val prefs: TerminalPrefs,
    private val scope: CoroutineScope,
) {
    private val ids = AtomicLong()
    private val _sessions = MutableStateFlow<List<ActiveSession>>(emptyList())
    val sessions: StateFlow<List<ActiveSession>> = _sessions.asStateFlow()

    private val _prompt = MutableStateFlow<PendingPrompt?>(null)
    val prompt: StateFlow<PendingPrompt?> = _prompt.asStateFlow()
    private val promptLock = Mutex()

    /** The view currently showing a session, to refresh it on output. */
    /** Views currently showing sessions (two in split screen), refreshed on output. */
    val views = java.util.concurrent.CopyOnWriteArraySet<TerminalView>()

    private val knownHostStore = RoomKnownHostStore(knownHosts)

    /** Port forwarding rules running on their own connections. */
    val tunnels: TunnelManager = TunnelManager(this, toolsDao, scope)
    val sftp: SftpPool = SftpPool(this)
    val transfers: TransferManager = TransferManager(context, sftp, scope)
    /** Remote desktops (RDP), shown by their own screen. */
    val rdp: com.nautile.app.rdp.RdpManager = com.nautile.app.rdp.RdpManager(context, this, hosts, keys, knownHosts, scope)

    init {
        TerminalThemes.apply(prefs.theme.value)
        prefs.cursorStyle.onEach {
            _sessions.value.forEach { s -> s.terminal.emulator?.setCursorStyle() }
            views.forEach { it.invalidate() }
        }.launchIn(scope)
        prefs.theme.onEach { theme ->
            TerminalThemes.apply(theme)
            _sessions.value.forEach { it.terminal.emulator?.mColors?.reset() }
            views.forEach { it.invalidate() }
        }.launchIn(scope)
        val activity = kotlinx.coroutines.flow.combine(_sessions, rdp.list, tunnels.states, transfers.transfers) { s, r, t, x ->
            listOf(s.size, t.count { it.value is TunnelState.Active }, x.count { it.state == Transfer.State.RUNNING }, r.size)
        }
        activity.map { it.sum() > 0 }.distinctUntilChanged().onEach { active ->
            val intent = Intent(context, SessionService::class.java)
            if (active) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
        }.launchIn(scope)
        activity.onEach { (s, t, x, r) -> SessionService.update(context, s, t, x, r) }.launchIn(scope)
    }

    /** Everything needed to reach a host: hops, proxy of the first hop, agent keys. */
    class ConnectionPlan(val host: Host, val firstHop: Host, val chain: List<SshTarget>, val proxy: ProxyConfig?, val agentKeys: List<com.nautile.ssh.AgentKey>?)

    suspend fun plan(hostId: Long): ConnectionPlan = plan(hosts.get(hostId) ?: error(tr("Hôte introuvable")))

    /** Same for a host that may not be saved (quick connection). */
    suspend fun plan(host: Host): ConnectionPlan {
        val (chain, firstHop) = buildChain(host)
        val proxy = firstHop.proxyType?.let { kind ->
            val ph = firstHop.proxyHost ?: error(tr("Proxy de %1\$s : adresse manquante", firstHop.alias))
            ProxyConfig(
                if (kind == ProxyKind.HTTP) ProxyType.HTTP else ProxyType.SOCKS5,
                ph, firstHop.proxyPort ?: if (kind == ProxyKind.HTTP) 8080 else 1080,
                firstHop.proxyUsername, firstHop.proxyPassword,
            )
        }
        return ConnectionPlan(host, firstHop, chain, proxy, if (host.agentForwarding) keys.agentKeys() else null)
    }

    /** A connector whose questions (host key, password) go through the app's dialogs. */
    fun connector(alias: String) = SshConnector(knownHostStore, Prompts(alias))

    fun find(id: Long): ActiveSession? = _sessions.value.firstOrNull { it.id == id }

    /** Opens a new session to [hostId]; connection starts once a view gives it a size. */
    suspend fun open(hostId: Long, extraCommand: String? = null): Result<ActiveSession> =
        hosts.get(hostId)?.let { open(it, extraCommand) } ?: Result.failure(IllegalStateException(tr("Hôte introuvable")))

    /** Opens a session to [host], saved or not (a quick connection has id 0). */
    suspend fun open(host: Host, extraCommand: String? = null): Result<ActiveSession> = runCatching {
        if (host.protocol == Protocol.RDP) error(tr("%1\$s est un bureau à distance, pas un terminal", host.alias))
        val active = ActiveSession(ids.incrementAndGet(), host.id, host.alias, host.address, protocolLabel(host))
        if (host.id == 0L) active.quickHost = host
        val startup = listOfNotNull(host.startupCommand, extraCommand).joinToString("\n").ifEmpty { null }

        if (host.protocol == Protocol.LOCAL) {
            active.terminal = localShell()
            active.terminal.mSessionName = host.alias
            track(active)
            active._state.value = SessionState.Connected
            _sessions.value = _sessions.value + active
            scope.launch { hosts.markConnected(host.id); if (host.osId != "android" && host.id != 0L) hosts.setOs(host.id, "android") }
            logConnection(host)
            // The shell starts once the view gives it a size; type the startup command after that.
            if (startup != null) scope.launch {
                repeat(50) { if (active.terminal.pid > 0) return@repeat; kotlinx.coroutines.delay(100) }
                kotlinx.coroutines.delay(300)
                run(active, startup)
            }
            return@runCatching active
        }

        val open: (Int, Int, (String) -> Unit) -> TerminalChannel = when (host.protocol) {
            Protocol.TELNET -> { c, r, step ->
                knock(host, step)
                step(tr("Connexion Telnet à %1\$s:%2\$s…", host.hostname, host.port))
                TelnetChannel.connect(host.hostname, host.port, c, r)
            }
            Protocol.SERIAL -> { c, r, step -> SerialChannel.open(context, host.serialBaud ?: 115_200, step) }
            else -> {
                val plan = plan(host)
                val connector = connector(host.alias)
                val opener: (Int, Int, (String) -> Unit) -> TerminalChannel = { c, r, step ->
                    knock(plan.firstHop, step)
                    plan.proxy?.let { step(tr("Proxy %1\$s %2\$s:%3\$s", it.type, it.host, it.port)) }
                    val conn = connector.connect(plan.chain, step, plan.proxy, plan.agentKeys?.let { k -> { k } })
                    val detectOs = {
                        runCatching { OsDetector.parse(conn.exec(OsDetector.COMMAND)) }.getOrNull()
                            ?.takeIf { it != "unknown" && it != host.osId }
                            ?.takeIf { host.id != 0L }
                            ?.let { os -> scope.launch { hosts.setOs(host.id, os) } }
                    }
                    try {
                        if (host.protocol == Protocol.MOSH) {
                            detectOs()
                            step(tr("Démarrage de mosh-server…"))
                            MoshChannel.start(conn, host.hostname, c, r).also { conn.close() }
                        } else {
                            val sh = conn.openShell(c, r)
                            Thread({ detectOs() }, "os-detect").apply { isDaemon = true }.start()
                            object : TerminalChannel by sh {
                                override fun close() { runCatching { sh.close() }; conn.close() }
                            }
                        }
                    } catch (e: Exception) {
                        conn.close(); throw e
                    }
                }
                opener
            }
        }
        active.backend = ChannelBackend(
            session = active,
            open = open,
            startupCommand = startup,
            notice = null,
            onConnected = { logConnection(host); scope.launch { hosts.markConnected(host.id); tunnels.startAuto(host.id) } },
            log = if (prefs.logSessions.value) SessionLogs.create(context, host.alias) else null,
        )
        active.terminal = TerminalSession(active.backend, TRANSCRIPT_ROWS, client)
        active.terminal.mSessionName = host.alias
        track(active)
        _sessions.value = _sessions.value + active
        active
    }

    /** A new session to the same host as [session] (saved or quick). */
    suspend fun reopen(session: ActiveSession): Result<ActiveSession> =
        session.quickHost?.let { open(it) } ?: open(session.hostId)

    /** Adds [host] to « Connexions › Historique ». */
    fun logConnection(host: Host) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            toolsDao.insertConnection(
                com.nautile.data.model.ConnectionLog(
                    hostId = host.id.takeIf { it != 0L }, alias = host.alias, protocol = host.protocol,
                    username = host.username, hostname = host.hostname, port = host.port,
                ),
            )
            toolsDao.trimConnections()
        }
    }

    /** Feeds typed input to a [LineTracker] that records echoed commands for autocompletion. */
    private fun track(active: ActiveSession) {
        val tracker = LineTracker(
            screenLine = {
                active.terminal.emulator?.let { e -> e.screen.getSelectedText(0, e.cursorRow, e.mColumns, e.cursorRow) }
            },
            onCommit = { cmd ->
                // Quick connections (id 0) are not saved hosts: their commands have nowhere to go.
                if (prefs.history.value && active.hostId != 0L) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    toolsDao.insertHistory(active.hostId, cmd, now)
                    toolsDao.bumpHistory(active.hostId, cmd, now)
                }
            },
        )
        active.terminal.mInputListener = TerminalSession.InputListener { data, off, len ->
            tracker.onInput(String(data, off, len, Charsets.UTF_8))
            active._typed.value = tracker.current
        }
    }

    private fun knock(host: Host, step: (String) -> Unit) {
        val seq = host.knockSequence?.takeIf { it.isNotBlank() } ?: return
        step(tr("Port knocking sur %1\$s (%2\$s)…", host.hostname, seq))
        PortKnocker.knock(host.hostname, PortKnocker.parse(seq))
    }

    /** A shell on the phone itself, through the Termux PTY helper. */
    private fun localShell(): TerminalSession {
        val home = context.filesDir.absolutePath
        val env = (System.getenv() + mapOf(
            "TERM" to "xterm-256color",
            "HOME" to home,
            "TMPDIR" to context.cacheDir.absolutePath,
            "LANG" to "en_US.UTF-8",
        )).map { (k, v) -> "$k=$v" }.toTypedArray()
        return TerminalSession("/system/bin/sh", home, arrayOf("sh"), env, TRANSCRIPT_ROWS, client)
    }

    /** Password to type at a sudo prompt: the host's, else its identity's. */
    suspend fun sudoPassword(session: ActiveSession): String? {
        val host = hosts.get(session.hostId) ?: return null
        host.password?.takeIf { it.isNotEmpty() }?.let { return it }
        if (host.authMethod == AuthMethod.IDENTITY) return host.identityId?.let { keys.identity(it) }?.password
        return null
    }

    suspend fun rememberSudoPassword(session: ActiveSession, password: String) {
        val host = hosts.get(session.hostId) ?: return
        hosts.save(host.copy(password = password))
    }

    /** Sends [command] (possibly multi-line) to [session] as if typed, each line followed by Enter. */
    fun run(session: ActiveSession, command: String) {
        if (!session.terminal.isRunning) return
        val bytes = (command.trimEnd('\n').replace("\r\n", "\n").replace('\n', '\r') + "\r").toByteArray()
        session.terminal.write(bytes, 0, bytes.size)
    }

    /** Types [password] followed by Enter in [session]. */
    fun typePassword(session: ActiveSession, password: String) {
        if (!session.terminal.isRunning) return
        val bytes = (password + "\r").toByteArray()
        session.terminal.write(bytes, 0, bytes.size)
    }

    /** Ends the session if needed and removes its tab. */
    fun close(session: ActiveSession) {
        if (session.terminal.isRunning) session.terminal.finishIfRunning()
        _sessions.value = _sessions.value - session
    }

    /** Returns the hops (outermost jump host first) and the host of the first hop. */
    private suspend fun buildChain(host: Host): Pair<List<SshTarget>, Host> {
        val chain = ArrayDeque<SshTarget>()
        val seen = mutableSetOf<Long>()
        var current: Host? = host
        var first = host
        while (current != null) {
            if (!seen.add(current.id) || seen.size > MAX_HOPS) error(tr("Boucle ou chaîne de rebond trop longue"))
            chain.addFirst(target(current))
            first = current
            current = current.jumpHostId?.let { hosts.get(it) }
        }
        return chain.toList() to first
    }

    private suspend fun target(h: Host): SshTarget {
        var username = h.username
        val auth = when (h.authMethod) {
            AuthMethod.KEY -> keyAuth(h.alias, h.keyId)
                ?: h.identity?.takeIf { it.contains("PRIVATE KEY") }?.let { SshAuth.PrivateKey(it) }
                ?: error(tr("%1\$s : aucune clé choisie", h.alias))
            AuthMethod.IDENTITY -> {
                val id = h.identityId?.let { keys.identity(it) } ?: error(tr("%1\$s : identité introuvable", h.alias))
                username = id.username
                keyAuth(h.alias, id.keyId) ?: SshAuth.Password(id.password)
            }
            AuthMethod.PASSWORD, AuthMethod.AGENT -> SshAuth.Password(h.password)
        }
        return SshTarget(h.hostname, h.port, username.ifBlank { "root" }, auth, label = h.alias)
    }

    private suspend fun keyAuth(alias: String, keyId: Long?): SshAuth.PrivateKey? {
        keyId ?: return null
        val key = keys.key(keyId) ?: error(tr("%1\$s : la clé a été supprimée du trousseau", alias))
        return SshAuth.PrivateKey(key.privateKey, key.savedPassphrase)
    }

    private fun protocolLabel(h: Host) = when (h.protocol) {
        Protocol.SERIAL -> tr("série %1\$s bauds", h.serialBaud ?: 115_200)
        Protocol.LOCAL -> tr("local")
        Protocol.RDP -> "rdp"
        else -> h.protocol.name.lowercase()
    }

    /** Shows [prompt] (one at a time) and blocks the calling connection thread until answered. */
    suspend fun <T> prompt(prompt: PendingPrompt, await: suspend () -> T): T = promptLock.withLock {
        _prompt.value = prompt
        try { await() } finally { _prompt.value = null }
    }

    private fun <T> ask(prompt: PendingPrompt, await: suspend () -> T): T = runBlocking {
        promptLock.withLock {
            _prompt.value = prompt
            try { await() } finally { _prompt.value = null }
        }
    }

    private inner class Prompts(private val alias: String) : SshPrompts {
        override fun confirmHostKey(info: HostKeyInfo, previous: KnownKey?): Boolean {
            val p = PendingPrompt.HostKey(alias, info, previous)
            return ask(p) { p.answer.await() }
        }

        override fun password(target: SshTarget): String? {
            val p = PendingPrompt.Secret(alias, tr("Mot de passe"), "${target.username}@${target.host}")
            return ask(p) { p.answer.await() }
        }

        override fun passphrase(target: SshTarget): String? {
            val p = PendingPrompt.Secret(alias, tr("Phrase de passe de la clé"), target.label)
            return ask(p) { p.answer.await() }
        }
    }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            views.forEach { if (it.currentSession === changedSession) it.onScreenUpdated() }
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            _sessions.value.firstOrNull { it.terminal === finishedSession }?.let { s ->
                if (s.state.value !is SessionState.Closed) s._state.value = SessionState.Closed(tr("Shell terminé (code %1\$s)", finishedSession.exitStatus), false)
            }
            views.forEach { if (it.currentSession === finishedSession) it.onScreenUpdated() }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Nautile", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
            val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            session?.emulator?.paste(text)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) { views.forEach { it.invalidate() } }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = prefs.cursorStyle.value
        override fun logError(tag: String?, message: String?) { android.util.Log.e(tag, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { android.util.Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(tag, "", e) }
    }

    private companion object {
        const val TRANSCRIPT_ROWS = 5000
        const val MAX_HOPS = 6
    }
}
