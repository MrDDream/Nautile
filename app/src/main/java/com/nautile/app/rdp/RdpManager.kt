package com.nautile.app.rdp

import com.nautile.i18n.tr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.nautile.app.keys.KeyRepository
import com.nautile.app.session.ChannelBackend
import com.nautile.app.session.PendingPrompt
import com.nautile.app.session.SessionManager
import com.nautile.app.session.SessionState
import com.nautile.data.db.KnownHostDao
import com.nautile.data.model.AuthMethod
import com.nautile.data.model.Host
import com.nautile.data.model.KnownHost
import com.nautile.data.model.ProxyKind
import com.nautile.data.repo.HostRepository
import com.nautile.rdp.RdpArgs
import com.nautile.rdp.RdpCertificate
import com.nautile.rdp.RdpConfig
import com.nautile.rdp.RdpConnection
import com.nautile.rdp.RdpCredentials
import com.nautile.rdp.RdpPointer
import com.nautile.rdp.RdpSecurity
import com.nautile.ssh.ForwardSpec
import com.nautile.ssh.ForwardType
import com.nautile.ssh.PortKnocker
import com.nautile.ssh.SshConnection
import com.nautile.ssh.startForward
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** What a view showing an [RdpSession] is told; called on FreeRDP's thread. */
interface RdpDisplay {
    fun resized(width: Int, height: Int)
    fun frame(x: Int, y: Int, width: Int, height: Int)
    fun pointer(pointer: RdpPointer?)
}

/** An open (or opening) remote desktop. Connects once a view gives it a size, see [start]. */
class RdpSession internal constructor(
    val id: Long,
    /** The host as opened (id 0 for a quick connection, never saved). */
    internal val host: Host,
    private val manager: RdpManager,
) {
    val hostId: Long get() = host.id
    val alias: String get() = host.alias
    val address: String get() = host.address
    internal val _state = MutableStateFlow<SessionState>(SessionState.Connecting(tr("Préparation…")))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @Volatile internal var connection: RdpConnection? = null
    @Volatile internal var tunnel: Closeable? = null
    @Volatile var display: RdpDisplay? = null
    /** Last remote pointer image; null hides it. */
    @Volatile var pointer: RdpPointer? = null
        internal set
    /** Follows the view's size with Display Control (no fixed resolution chosen). */
    @Volatile internal var autoSize = true
    private val started = AtomicBoolean(false)
    /** The connection was started (by the first stable size of its view). */
    val isStarted: Boolean get() = started.get()
    /** Size of the view showing the session. */
    @Volatile private var viewSize: Pair<Int, Int>? = null
    @Volatile internal var sizeJob: kotlinx.coroutines.Job? = null

    val bitmap get() = connection?.bitmap
    val input: RdpConnection? get() = connection?.takeIf { _state.value == SessionState.Connected }

    /** Starts connecting with a desktop sized for a [width]×[height] px view (first call only). */
    fun start(width: Int, height: Int, density: Float) {
        viewSize = width to height
        if (started.compareAndSet(false, true)) manager.connect(this, width, height, density)
    }

    /** The view changed size (rotation, split screen, full screen): follow it when the resolution is automatic. */
    fun viewResized(width: Int, height: Int) {
        if (viewSize == width to height) return
        viewSize = width to height
        manager.followView(this)
    }

    /**
     * Asks for a desktop the size of the view when it does not have it yet; true if a request was sent.
     * The server ignores requests until its Display Control channel is up, hence the retries in [RdpManager.followView].
     */
    internal fun syncSize(): Boolean {
        if (!autoSize) return false
        val (vw, vh) = viewSize ?: return false
        val c = input ?: return false
        val (tw, th) = RdpArgs.desktopSize(vw, vh)
        val b = bitmap
        if (b != null && b.width == tw && b.height == th) return false
        c.resize(vw, vh)
        return true
    }
}

/** Owns every remote desktop session; outlives screens like [SessionManager]. */
class RdpManager(
    private val context: Context,
    private val sessions: SessionManager,
    private val hosts: HostRepository,
    private val keys: KeyRepository,
    private val knownHosts: KnownHostDao,
    private val scope: CoroutineScope,
) {
    val prefs = RdpPrefs(context)
    private val ids = AtomicLong()
    private val _list = MutableStateFlow<List<RdpSession>>(emptyList())
    val list: StateFlow<List<RdpSession>> = _list.asStateFlow()

    fun find(id: Long): RdpSession? = _list.value.firstOrNull { it.id == id }

    /** Creates the session; the connection starts when its view is laid out. */
    suspend fun open(hostId: Long): Result<RdpSession> =
        hosts.get(hostId)?.let { open(it) } ?: Result.failure(IllegalStateException(tr("Hôte introuvable")))

    /** Same for a host that may not be saved (quick connection). */
    fun open(host: Host): Result<RdpSession> = runCatching {
        RdpConnection.unavailableReason()?.let { error(tr("Bureau à distance indisponible sur cet appareil : %1\$s", it)) }
        RdpSession(ids.incrementAndGet(), host, this).also { _list.value = _list.value + it }
    }

    /** Resizes the desktop to the view, retrying while the server is not ready for it. */
    internal fun followView(session: RdpSession) {
        session.sizeJob?.cancel()
        session.sizeJob = scope.launch {
            repeat(8) {
                if (!session.syncSize()) return@launch
                kotlinx.coroutines.delay(1_500)
            }
        }
    }

    fun close(session: RdpSession) {
        _list.value = _list.value - session
        session.display = null
        scope.launch(Dispatchers.IO) { release(session) }
    }

    /** Tries again after a failure or a disconnection, on a fresh session in the same place. */
    suspend fun reconnect(session: RdpSession): Result<RdpSession> =
        // Open first: the screen leaves when its session disappears from the list.
        (if (session.hostId != 0L) open(session.hostId) else open(session.host)).also { close(session) }

    private fun release(session: RdpSession) {
        session.connection?.let { runCatching { it.close() } }
        session.connection = null
        session.tunnel?.let { runCatching { it.close() } }
        session.tunnel = null
    }

    internal fun connect(session: RdpSession, viewWidth: Int, viewHeight: Int, density: Float) {
        scope.launch(Dispatchers.IO) {
            try {
                val host = if (session.hostId == 0L) session.host else hosts.get(session.hostId) ?: error(tr("Hôte introuvable"))
                fun step(s: String) { session._state.value = SessionState.Connecting(s) }

                host.knockSequence?.takeIf { it.isNotBlank() }?.let { seq ->
                    step(tr("Port knocking sur %1\$s (%2\$s)…", host.hostname, seq))
                    PortKnocker.knock(host.hostname, PortKnocker.parse(seq))
                }
                var target = host.hostname to host.port
                var proxy: String? = null
                val jumpId = host.jumpHostId
                if (jumpId != null) {
                    val plan = sessions.plan(jumpId)
                    val ssh: SshConnection = sessions.connector(plan.host.alias).connect(plan.chain, ::step, plan.proxy, plan.agentKeys?.let { k -> { k } })
                    val fwd = try {
                        ssh.startForward(ForwardSpec(ForwardType.LOCAL, "127.0.0.1", 0, host.hostname, host.port))
                    } catch (e: Exception) {
                        ssh.close(); throw e
                    }
                    session.tunnel = Closeable { runCatching { fwd.close() }; ssh.close() }
                    step(tr("Tunnel SSH via %1\$s vers %2\$s:%3\$s", plan.host.alias, host.hostname, host.port))
                    target = "127.0.0.1" to fwd.boundPort
                } else host.proxyType?.let { kind ->
                    val ph = host.proxyHost ?: error(tr("Proxy de %1\$s : adresse manquante", host.alias))
                    val http = kind == ProxyKind.HTTP
                    proxy = RdpArgs.proxyUrl(if (http) "http" else "socks5", ph, host.proxyPort ?: if (http) 8080 else 1080, host.proxyUsername, host.proxyPassword)
                }

                val (username, password) = credentials(host)
                val fixed = host.rdpResolution?.let(::parseResolution)
                session.autoSize = fixed == null
                val (w, h) = fixed ?: (viewWidth to viewHeight)
                val config = RdpConfig(
                    host = target.first, port = target.second,
                    username = username, domain = host.rdpDomain.orEmpty(), password = password,
                    width = w, height = h,
                    scaleDesktop = host.rdpScale ?: if (fixed == null) autoScale(density) else null,
                    security = host.rdpSecurity?.let { runCatching { RdpSecurity.valueOf(it) }.getOrNull() } ?: RdpSecurity.AUTO,
                    audio = host.rdpAudio,
                    console = host.rdpConsole,
                    clientName = android.os.Build.MODEL.filter { it.isLetterOrDigit() || it == '-' }.take(15).ifEmpty { "Nautile" },
                    proxy = proxy,
                    quality = prefs.quality.value,
                    clipboard = prefs.clipboard.value,
                )
                step(tr("Connexion RDP à %1\$s:%2\$s…", host.hostname, host.port))
                val conn = RdpConnection(context, Events(session, host))
                session.connection = conn
                if (!_list.value.contains(session)) { release(session); return@launch }
                if (!conn.start(config)) error(tr("Paramètres de connexion refusés par FreeRDP"))
            } catch (e: Exception) {
                release(session)
                session._state.value = SessionState.Closed(ChannelBackend.describe(e), failed = true)
            }
        }
    }

    private suspend fun credentials(host: Host): Pair<String, String?> =
        if (host.authMethod == AuthMethod.IDENTITY) {
            val id = host.identityId?.let { keys.identity(it) } ?: error(tr("%1\$s : identité introuvable", host.alias))
            id.username to id.password
        } else host.username to host.password

    private inner class Events(private val session: RdpSession, private val host: Host) : RdpConnection.Events {
        override fun connected() {
            session._state.value = SessionState.Connected
            followView(session)
            sessions.logConnection(host)
            if (host.id != 0L) scope.launch {
                hosts.markConnected(host.id)
                if (host.osId == null) hosts.setOs(host.id, "windows")
            }
        }

        override fun closed(error: String?, failed: Boolean) {
            val reason = when {
                !failed -> tr("Session terminée")
                error.isNullOrBlank() || error.contains("SUCCESS", true) -> tr("Connexion impossible")
                else -> describe(error)
            }
            if (session._state.value !is SessionState.Closed) session._state.value = SessionState.Closed(reason, failed)
            // The session thread is ending: free the instance from another thread.
            scope.launch(Dispatchers.IO) { release(session) }
        }

        override fun reconnecting() { session._state.value = SessionState.Connecting(tr("Reconnexion…")) }
        override fun resized(width: Int, height: Int) { session.display?.resized(width, height) }
        override fun frame(x: Int, y: Int, width: Int, height: Int) { session.display?.frame(x, y, width, height) }
        override fun pointer(pointer: RdpPointer?) { session.pointer = pointer; session.display?.pointer(pointer) }

        override fun credentials(current: RdpCredentials, gateway: Boolean): RdpCredentials? {
            val p = PendingPrompt.Login(host.alias, current.username, current.domain, gateway)
            return runBlocking { sessions.prompt(p) { p.answer.await() } }
        }

        override fun certificate(cert: RdpCertificate): Boolean {
            val fp = fingerprint(cert)
            val known = knownHosts.findBlocking(host.hostname, host.port).firstOrNull { it.keyType == CERT_TYPE }
            if (known?.fingerprint == fp) return true
            val p = PendingPrompt.Certificate(host.alias, "${host.hostname}:${host.port}", cert, fp, known?.fingerprint)
            val ok = runBlocking { sessions.prompt(p) { p.answer.await() } }
            if (ok) knownHosts.replaceBlocking(KnownHost(host = host.hostname, port = host.port, keyType = CERT_TYPE, publicKey = cert.subject, fingerprint = fp))
            return ok
        }

        override fun remoteClipboard(text: String) {
            if (!prefs.clipboard.value) return
            val cm = context.getSystemService(ClipboardManager::class.java)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { cm.setPrimaryClip(ClipData.newPlainText(host.alias, text)) }
            }
        }
    }

    companion object {
        /** keyType of RDP server certificates in the known hosts table. */
        const val CERT_TYPE = "rdp-x509"

        fun parseResolution(s: String): Pair<Int, Int>? =
            Regex("""(\d{3,4})\s*[x×]\s*(\d{3,4})""").matchEntire(s.trim())?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() }

        /** Windows scale making text about as big as on a laptop: ~60 % of the Android density. */
        fun autoScale(density: Float): Int = ((density * 60f / 5f).toInt() * 5).coerceIn(100, 300)

        /** SHA-256 as `AA:BB:…`, from FreeRDP's hex fingerprint or from the PEM certificate. */
        fun fingerprint(cert: RdpCertificate): String {
            if (cert.isPem) {
                val der = runCatching {
                    java.security.cert.CertificateFactory.getInstance("X.509")
                        .generateCertificate(cert.fingerprint.byteInputStream()).encoded
                }.getOrElse { cert.fingerprint.toByteArray() }
                return MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
            }
            return cert.fingerprint.substringAfter("sha256:").uppercase()
        }

        /** FreeRDP error names into words. */
        fun describe(error: String): String = when {
            error.contains("LOGON_FAILURE", true) || error.contains("AUTHENTICATION_FAILED", true) -> tr("Identifiants refusés par le serveur")
            error.contains("PASSWORD_EXPIRED", true) || error.contains("PASSWORD_MUST_CHANGE", true) -> tr("Mot de passe expiré : changez-le depuis Windows")
            error.contains("ACCOUNT_DISABLED", true) -> tr("Compte désactivé")
            error.contains("ACCOUNT_RESTRICTION", true) -> tr("Compte non autorisé à se connecter à distance")
            error.contains("DNS_NAME_NOT_FOUND", true) || error.contains("DNS_ERROR", true) -> tr("Adresse introuvable")
            error.contains("CONNECT_FAILED", true) || error.contains("CONNECT_TRANSPORT_FAILED", true) -> tr("Serveur injoignable (port fermé ou bureau à distance désactivé)")
            error.contains("TLS_CONNECT_FAILED", true) -> tr("Échec de la négociation TLS")
            error.contains("SECURITY_NEGO", true) -> tr("Aucun mode de sécurité commun : essayez un autre réglage de sécurité")
            error.contains("CANCELLED", true) -> tr("Connexion annulée")
            error.contains("INSUFFICIENT_PRIVILEGES", true) -> tr("Droits insuffisants pour ouvrir une session")
            error.contains("SERVER_DENIED", true) -> tr("Connexion refusée par le serveur")
            else -> error
        }
    }
}
