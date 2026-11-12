package com.nautile.app.backup.lan

import com.nautile.i18n.tr

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import com.nautile.app.NautileApp
import com.nautile.data.backup.BackupData
import com.nautile.data.sync.SyncMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Synchronisation straight between devices on the same network, no cloud: each announces itself
 * (mDNS « _nautile._tcp »), pairs once with a 6-digit code, then merges with its paired devices
 * found nearby, as the file synchronisation does (latest change wins). See [LanProtocol].
 */
class LanSync(private val app: NautileApp, private val scope: CoroutineScope) {
    /** A paired device. */
    data class Peer(val id: String, val name: String, val lastSync: Long)

    /** A device announcing itself on the network now. */
    data class Found(val id: String, val name: String, val host: InetAddress, val port: Int)

    private val prefs = app.getSharedPreferences("nautile_lan", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val nsd by lazy { app.getSystemService(NsdManager::class.java) }
    val deviceId: String get() = app.backupPrefs.deviceId
    private val deviceName: String get() = Build.MODEL.ifBlank { "Android" }

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _peers = MutableStateFlow(loadPeers())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    /** Nautile devices seen on the network (paired or not), this one excepted. */
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private val _code = MutableStateFlow<String?>(null)
    /** The code this device shows while waiting to be paired. */
    val code: StateFlow<String?> = _code.asStateFlow()
    private var codeAttempts = 0

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var pending: Job? = null

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start() else stop()
    }

    // ── Server and announcement ───────────────────────────────────────────

    /** Listens and announces itself (when enabled); then a first round with the paired devices nearby. */
    fun start() {
        if (!_enabled.value || server != null) return
        val socket = runCatching { ServerSocket(0) }.getOrElse { _status.value = it.message; return }
        server = socket
        Thread({ acceptLoop(socket) }, "lan-sync-server").apply { isDaemon = true }.start()
        val info = NsdServiceInfo().apply {
            serviceName = "Nautile $deviceName"
            serviceType = SERVICE
            port = socket.localPort
            setAttribute("id", deviceId)
            setAttribute("v", LanProtocol.VERSION.toString())
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { _status.value = tr("Annonce sur le réseau impossible (%1\$s)", code) }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }.also { runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) } }
        discover()
        if (_peers.value.isNotEmpty()) requestSoon(4_000)
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        stopDiscovery()
        runCatching { server?.close() }; server = null
        _found.value = emptyList()
        _code.value = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            Thread({ client.use { handle(it) } }, "lan-sync-peer").apply { isDaemon = true }.start()
        }
    }

    /** An incoming connection: pairing (with the code shown) or synchronisation (with the pairing secret). */
    private fun handle(socket: Socket) {
        socket.soTimeout = 60_000
        try {
            val mode = LanProtocol.readMode(socket.getInputStream())
            when (mode) {
                LanProtocol.MODE_PAIR -> {
                    val code = _code.value ?: throw IOException("no pairing in progress")
                    val ch = try {
                        LanProtocol.accept(socket.getInputStream(), socket.getOutputStream(), mode, code.toByteArray())
                    } catch (e: LanProtocol.AuthFailed) {
                        if (++codeAttempts >= 3) { _code.value = null; _status.value = tr("Trop d'essais : nouveau code nécessaire") }
                        throw e
                    }
                    val them = JSONObject(ch.receiveText())
                    val secret = secret() ?: LanProtocol.newSecret().also(::saveSecret)
                    ch.sendText(JSONObject().put("id", deviceId).put("name", deviceName).put("secret", b64(secret)).toString())
                    addPeer(them.getString("id"), them.getString("name"))
                    _code.value = null
                    _status.value = tr("%1\$s associé", them.getString("name"))
                    requestSoon(1_000)
                }
                LanProtocol.MODE_SYNC -> {
                    val secret = secret() ?: throw IOException("not paired")
                    val ch = LanProtocol.accept(socket.getInputStream(), socket.getOutputStream(), mode, secret)
                    val them = JSONObject(ch.receiveText())
                    val merged = runBlocking { exchange(ch, them, initiator = false) }
                    touchPeer(them.getString("id"), them.getString("name"))
                    if (merged) _status.value = tr("Synchronisé avec %1\$s", them.getString("name"))
                }
            }
        } catch (_: Exception) {
            // A failed or foreign connection: nothing to keep.
        }
    }

    // ── Discovery ─────────────────────────────────────────────────────────

    private val resolving = ConcurrentHashMap<String, Boolean>()

    private var discoveryEnd: Job? = null

    /** Looks for other Nautile devices on the network for a minute (mDNS costs a little battery). */
    fun discover() {
        discoveryEnd?.cancel()
        discoveryEnd = scope.launch { delay(60_000); stopDiscovery() }
        if (discovery != null) return
        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { discovery = null }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (resolving.putIfAbsent(info.serviceName, true) != null) return
                resolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                resolving.remove(info.serviceName)
                _found.value = _found.value.filterNot { "Nautile ${it.name}" == info.serviceName }
            }
        }.also { runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, it) } }
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        resolving.clear()
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        // Android resolves one service at a time: retry a little later when busy.
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                if (code == NsdManager.FAILURE_ALREADY_ACTIVE) scope.launch { delay(400); resolve(info) } else resolving.remove(info.serviceName)
            }

            override fun onServiceResolved(i: NsdServiceInfo) {
                val id = i.attributes["id"]?.decodeToString() ?: return
                if (id == deviceId) return
                val host = i.host ?: return
                val name = i.serviceName.removePrefix("Nautile ")
                _found.value = _found.value.filterNot { it.id == id } + Found(id, name, host, i.port)
            }
        })
    }

    // ── Pairing ───────────────────────────────────────────────────────────

    /** Shows a code on this device: another one types it to pair. */
    fun showCode(): String {
        start()
        codeAttempts = 0
        return LanProtocol.newCode().also { _code.value = it; _status.value = null }
    }

    fun cancelCode() { _code.value = null }

    /** Pairs with [peer], which shows [code]. */
    suspend fun pair(peer: Found, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(peer.host, peer.port), 8_000); s.soTimeout = 30_000
                val ch = LanProtocol.connect(s.getInputStream(), s.getOutputStream(), LanProtocol.MODE_PAIR, code.trim().toByteArray())
                ch.sendText(JSONObject().put("id", deviceId).put("name", deviceName).toString())
                val them = JSONObject(ch.receiveText())
                saveSecret(Base64.decode(them.getString("secret"), Base64.NO_WRAP))
                addPeer(them.getString("id"), them.getString("name"))
            }
            Unit
        }.onSuccess { _status.value = tr("%1\$s associé", peer.name); requestSoon(500) }
    }

    fun forget(peer: Peer) {
        _peers.value = _peers.value.filterNot { it.id == peer.id }
        storePeers()
        if (_peers.value.isEmpty()) app.secrets.put(SECRET, null)
    }

    // ── Synchronisation ───────────────────────────────────────────────────

    /** Synchronises soon with the paired devices nearby (debounced). */
    fun requestSoon(delayMs: Long = 8_000) {
        if (!_enabled.value || _peers.value.isEmpty()) return
        pending?.cancel()
        pending = scope.launch { delay(delayMs); syncAll() }
    }

    /** One round with every paired device found on the network; the number reached. */
    suspend fun syncAll(): Result<Int> = withContext(Dispatchers.IO) {
        if (_running.value) return@withContext Result.success(0)
        _running.value = true
        try {
            val secret = secret() ?: return@withContext Result.success(0)
            val paired = _peers.value.map { it.id }.toSet()
            var nearby = _found.value.filter { it.id in paired }
            if (nearby.isEmpty()) {
                // Not seen lately: look again for a few seconds.
                withContext(Dispatchers.Main) { discover() }
                repeat(10) { if (nearby.isEmpty()) { delay(500); nearby = _found.value.filter { it.id in paired } } }
            }
            if (nearby.isEmpty()) { _status.value = tr("Aucun appareil associé à portée"); return@withContext Result.success(0) }
            var reached = 0
            nearby.forEach { peer ->
                runCatching {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(peer.host, peer.port), 8_000); s.soTimeout = 60_000
                        val ch = LanProtocol.connect(s.getInputStream(), s.getOutputStream(), LanProtocol.MODE_SYNC, secret)
                        exchange(ch, null, initiator = true)
                        touchPeer(peer.id, peer.name)
                        reached++
                    }
                }
            }
            _status.value = if (reached > 0) tr("Synchronisé avec %1\$s appareil(s)", reached) else tr("Appareils associés injoignables")
            Result.success(reached)
        } finally {
            _running.value = false
        }
    }

    /**
     * Both sides send their data and merge the other's (latest change wins), so both end the same.
     * [them]: the peer's hello when it already arrived (responder). True if anything changed here.
     */
    private suspend fun exchange(ch: LanProtocol.Channel, them: JSONObject?, initiator: Boolean): Boolean {
        val merge = SyncMerge(app.database)
        val hello = JSONObject().put("id", deviceId).put("name", deviceName)
        val mine = json.encodeToString(BackupData.serializer(), merge.snapshot(emptyList()))
        val theirs: String
        if (initiator) {
            ch.sendText(hello.toString()); ch.sendText(mine)
            JSONObject(ch.receiveText()); theirs = ch.receiveText()
        } else {
            them ?: error("hello")
            theirs = ch.receiveText()
            ch.sendText(hello.toString()); ch.sendText(mine)
        }
        val data = json.decodeFromString(BackupData.serializer(), theirs)
        return merge.merge(data).changed
    }

    // ── Storage ───────────────────────────────────────────────────────────

    private fun secret(): ByteArray? = app.secrets.get(SECRET)?.let { Base64.decode(it, Base64.NO_WRAP) }
    private fun saveSecret(s: ByteArray) = app.secrets.put(SECRET, b64(s))
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun addPeer(id: String, name: String) {
        _peers.value = _peers.value.filterNot { it.id == id } + Peer(id, name, 0)
        storePeers()
    }

    private fun touchPeer(id: String, name: String) {
        _peers.value = _peers.value.filterNot { it.id == id } + Peer(id, name, System.currentTimeMillis())
        storePeers()
    }

    private fun loadPeers(): List<Peer> = runCatching {
        val a = JSONArray(prefs.getString(KEY_PEERS, "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Peer(it.getString("id"), it.getString("name"), it.optLong("last")) } }
    }.getOrDefault(emptyList())

    private fun storePeers() {
        val a = JSONArray()
        _peers.value.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("last", it.lastSync)) }
        prefs.edit().putString(KEY_PEERS, a.toString()).apply()
    }

    companion object {
        const val SERVICE = "_nautile._tcp."
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PEERS = "peers"
        private const val SECRET = "lan_pairing_secret"
    }
}
