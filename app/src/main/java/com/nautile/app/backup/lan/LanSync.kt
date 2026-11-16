package com.nautile.app.backup.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import com.nautile.app.NautileApp
import com.nautile.data.backup.BackupData
import com.nautile.data.sync.SyncMerge
import com.nautile.data.sync.SyncPart
import com.nautile.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Synchronisation straight between devices on the same network, no cloud: each announces itself
 * (mDNS « _nautile._tcp »), pairs once with a 6-digit code, then merges with its paired devices
 * found nearby, as the file synchronisation does (latest change wins). Each paired device has its own
 * secret from its pairing; forgetting one drops it, and a device without one is refused. See [LanProtocol].
 * Safe to use from any thread; the secrets are only read from the Keystore once the synchronisation is on.
 */
class LanSync(private val app: NautileApp, private val scope: CoroutineScope) {
    /**
     * A paired device; [platform]: its system (« android », « windows », « ubuntu »…), unknown for older versions;
     * [linked]: false when it was paired with an older version (no secret of its own): to pair again.
     */
    data class Peer(val id: String, val name: String, val lastSync: Long, val platform: String? = null, val linked: Boolean = true)

    /** A device announcing itself on the network now; [version]: its protocol (the mDNS `v`, 1 when missing). */
    data class Found(val id: String, val name: String, val host: InetAddress, val port: Int, val version: Int = LanProtocol.VERSION)

    private val prefs = app.getSharedPreferences("nautile_lan", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val nsd by lazy { app.getSystemService(NsdManager::class.java) }
    val deviceId: String get() = app.backupPrefs.deviceId
    private val deviceName: String get() = Build.MODEL.ifBlank { "Android" }

    /** Who this device is, sent to the other one (its `platform`: shown as its icon there). */
    private fun hello() = PeerHello(deviceId, deviceName, PLATFORM)
    private fun JSONObject.platform() = optString("platform").takeIf { it.isNotBlank() }

    /** The secret of each paired device. */
    private val secrets = ConcurrentHashMap<String, ByteArray>()

    /**
     * Paired devices whose secret the Keystore could not read at the last try (failing for a moment), and when:
     * still paired, read again later ([secretOf]) rather than taken for devices to pair again.
     */
    private val unreadSecrets = ConcurrentHashMap<String, Long>()

    /**
     * The secret of [id], read again from the Keystore when it failed earlier (not more than every few seconds):
     * [LanServer.Secret.NotNow] meanwhile, a paired device not to be told to pair again.
     */
    private fun secretOf(id: String): LanServer.Secret {
        loadSecrets()
        secrets[id]?.let { return LanServer.Secret.Known(it) }
        val triedAt = unreadSecrets[id] ?: return LanServer.Secret.Unknown
        val now = System.currentTimeMillis()
        if (now - triedAt < SECRET_RETRY_MS || !unreadSecrets.replace(id, triedAt, now)) return LanServer.Secret.NotNow
        return when (val r = readSecret(id)) {
            is SecretRead.Ok -> { secrets[id] = r.secret; unreadSecrets.remove(id); LanServer.Secret.Known(r.secret) }
            SecretRead.NotNow -> LanServer.Secret.NotNow
            SecretRead.None -> {
                unreadSecrets.remove(id)
                _peers.update { list -> list.map { if (it.id == id) it.copy(linked = false) else it } }
                LanServer.Secret.Unknown
            }
        }
    }

    private val secretsLoaded = AtomicBoolean(false)
    private val secretsLock = Any()

    /**
     * Reads the secrets of the paired devices from the Keystore, once, when the synchronisation is first used (never
     * while it is off). A Keystore failing for a moment is not a device to pair again: it stays paired, read again later.
     */
    private fun loadSecrets() {
        if (secretsLoaded.get()) return
        synchronized(secretsLock) {
            if (secretsLoaded.get()) return
            // Protocol v1 shared one secret with every device: gone, each pairs again for its own.
            runCatching { if (app.secrets.contains(SECRET_V1)) app.secrets.put(SECRET_V1, null) }
            val linked = _peers.value.associate { p ->
                p.id to when (val r = readSecret(p.id)) {
                    is SecretRead.Ok -> { secrets.putIfAbsent(p.id, r.secret); true }
                    SecretRead.NotNow -> { if (!secrets.containsKey(p.id)) unreadSecrets.putIfAbsent(p.id, 0L); true }
                    // Unless paired again meanwhile.
                    SecretRead.None -> secrets.containsKey(p.id)
                }
            }
            _peers.update { list -> list.map { p -> linked[p.id]?.let { p.copy(linked = it) } ?: p } }
            if (_peers.value.any { !it.linked }) _status.value = tr("Nouvelle version de la synchro Wi-Fi : associez à nouveau vos appareils")
            secretsLoaded.set(true)
        }
    }

    private sealed interface SecretRead {
        class Ok(val secret: ByteArray) : SecretRead
        /** The Keystore failed for a moment. */
        data object NotNow : SecretRead
        /** Absent or unreadable for good: to pair again. */
        data object None : SecretRead
    }

    private fun readSecret(id: String): SecretRead = when (val r = runCatching { app.secrets.read(secretKey(id)) }.getOrNull()) {
        is com.nautile.security.SecretStore.Read.Value ->
            runCatching { Base64.decode(r.value, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 }?.let { SecretRead.Ok(it) } ?: SecretRead.None
        com.nautile.security.SecretStore.Read.Unavailable, null -> SecretRead.NotNow
        else -> SecretRead.None
    }

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _peers = MutableStateFlow(loadPeers())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    /** Nautile devices seen on the network (paired or not), this one excepted. */
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private val _lastPaired = MutableStateFlow<Pair<String, Long>?>(null)
    /** The device last paired with (either side) and when: the code shown was used. */
    val lastPaired: StateFlow<Pair<String, Long>?> = _lastPaired.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val server = LanServer({ deviceId }, object : LanServer.Host {
        override fun hello() = this@LanSync.hello()
        override fun secret(id: String) = secretOf(id)
        override fun paired(them: PeerHello, secret: ByteArray) {
            // Not saved: no answer, the other device keeps nothing either.
            runCatching { savePeer(them.id, them.name, them.platform, secret) }
                .onFailure { _status.value = tr("Association impossible"); throw it }
            _status.value = tr("%1\$s associé", them.name)
            requestSoon(1_000)
        }
        override fun exchange(channel: LanProtocol.Channel, them: PeerHello, live: () -> Boolean) {
            val merged = runBlocking { this@LanSync.exchange(channel, them.id, initiator = false, live) } ?: return
            touchPeer(them.id, them.name, them.platform)
            if (merged) _status.value = tr("Synchronisé avec %1\$s", them.name)
        }
        override fun status(message: String) { _status.value = message }
    })

    /** The code this device shows while waiting to be paired. */
    val code: StateFlow<String?> = server.code

    /** Guards the server's start and stop, the announcement and the discovery (called from any thread). */
    private val lifecycle = Any()
    @Volatile private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var discovery: NsdManager.DiscoveryListener? = null
    /** The planned round; set from the main thread and from the devices' threads (`lan-sync-peer`). */
    private var pending: Job? = null
    private val pendingLock = Any()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        // The Keystore is read on starting: not on the caller's (main) thread.
        if (on) scope.launch(Dispatchers.IO) { runCatching { start() } } else stop()
    }

    // ── Server and announcement ───────────────────────────────────────────

    /**
     * Listens and announces itself (when enabled); then a first round with the paired devices nearby. Reads the
     * secrets from the Keystore the first time: better off the main thread.
     */
    fun start() {
        if (!_enabled.value) return
        loadSecrets()
        synchronized(lifecycle) { startLocked() }
    }

    private fun startLocked() {
        if (!_enabled.value || server.running) return
        val listening = runCatching { server.start() }.getOrElse { _status.value = it.message; return }
        roundOwed.set(true)
        val info = NsdServiceInfo().apply {
            serviceName = "Nautile $deviceName"
            serviceType = SERVICE
            port = listening
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
        if (_peers.value.any { it.linked }) requestSoon(4_000)
    }

    /** Stops listening and announcing; the planned round and the discovery are dropped, a pairing under way is not kept. */
    fun stop() = synchronized(lifecycle) {
        synchronized(pendingLock) { pending?.cancel(); pending = null }
        discoveryEnd?.cancel(); discoveryEnd = null
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        stopDiscovery()
        server.stop()
        _found.value = emptyList()
    }

    // ── Discovery ─────────────────────────────────────────────────────────

    private val resolving = ConcurrentHashMap<String, Boolean>()

    @Volatile private var discoveryEnd: Job? = null

    /** Looks for other Nautile devices on the network for a minute (mDNS costs a little battery). */
    fun discover(): Unit = synchronized(lifecycle) {
        if (!_enabled.value) return
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

    private fun stopDiscovery() = synchronized(lifecycle) {
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
                if (id == deviceId || id.isEmpty() || id.toByteArray().size > LanProtocol.MAX_ID) return
                val host = i.host ?: return
                val version = i.attributes["v"]?.decodeToString()?.toIntOrNull() ?: 1
                val name = i.serviceName.removePrefix("Nautile ")
                _found.value = _found.value.filterNot { it.id == id } + Found(id, name, host, i.port, version)
            }
        })
    }

    // ── Pairing ───────────────────────────────────────────────────────────

    /** Shows a code on this device: another one types it to pair. */
    fun showCode(): String {
        start()
        return server.showCode().also { _status.value = null }
    }

    fun cancelCode() = server.cancelCode()

    /** Pairs with [peer], which shows [code]. */
    suspend fun pair(peer: Found, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!_enabled.value) return@withContext Result.failure(IOException(tr("Association impossible")))
        runCatching {
            if (peer.version < LanProtocol.VERSION) throw LanProtocol.PeerTooOld()
            LanServer.connect(peer.host, peer.port) { s, _ ->
                val paired = LanProtocol.pairConnect(s.getInputStream(), s.getOutputStream(), deviceId, peer.id, code.trim())
                paired.channel.sendText(hello().encode())
                // Kept only once the other side answered: it hangs up instead when its code was cancelled meanwhile.
                val them = PeerHello.parse(paired.channel.receiveShortText(), peer.id)
                if (!_enabled.value) throw IOException(tr("Association impossible"))
                savePeer(peer.id, them.name, them.platform, paired.secret)
            }
        }.onSuccess { _status.value = tr("%1\$s associé", peer.name); requestSoon(500) }
    }

    /** Forgets [peer]: its secret goes, it is refused from now on; the other devices keep theirs. */
    fun forget(peer: Peer) {
        secrets.remove(peer.id)
        unreadSecrets.remove(peer.id)
        app.secrets.put(secretKey(peer.id), null)
        _peers.update { list -> list.filterNot { it.id == peer.id } }
        storePeers()
    }

    // ── Synchronisation ───────────────────────────────────────────────────

    /**
     * SHA-256 of this device's data as last exchanged (sent, or just merged): a round asked for by a change
     * ([requestSoon]) is skipped while the data is still that. The writes of a merge, this device's or an incoming
     * one, then ask for nothing, or two devices would keep answering each other.
     */
    private val exchanged = AtomicReference<String?>(null)

    /** A round is owed whatever the data (just started, or a device just paired). */
    private val roundOwed = AtomicBoolean(false)

    private fun fingerprint(data: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(data.toByteArray()), Base64.NO_WRAP)

    /** This device's data, as sent to the others. */
    private suspend fun snapshotOf(merge: SyncMerge) =
        json.encodeToString(BackupData.serializer(), SyncPart.without(merge.snapshot(emptyList()), app.backupPrefs.syncSkip.value))

    /** Whether a round is owed, or the data changed since the last exchange. */
    private suspend fun roundWanted(): Boolean = roundOwed.get() || withContext(Dispatchers.IO) {
        val last = exchanged.get() ?: return@withContext true
        runCatching { fingerprint(snapshotOf(SyncMerge(app.database))) != last }.getOrDefault(true)
    }

    /**
     * Synchronises soon with the paired devices nearby (debounced), unless nothing changed here since the last
     * exchange (the writes of a merge do not count: see [exchanged]).
     */
    fun requestSoon(delayMs: Long = 8_000) {
        if (!_enabled.value || _peers.value.none { it.linked }) return
        synchronized(pendingLock) {
            pending?.cancel()
            pending = scope.launch { delay(delayMs); if (roundWanted()) syncAll() }
        }
    }

    /** One round with every paired device found on the network; the number reached. */
    suspend fun syncAll(): Result<Int> = withContext(Dispatchers.IO) {
        if (!_enabled.value || _running.value) return@withContext Result.success(0)
        _running.value = true
        roundOwed.set(false)
        try {
            val paired = _peers.value.filter { it.linked && secretOf(it.id) is LanServer.Secret.Known }.map { it.id }.toSet()
            if (paired.isEmpty()) return@withContext Result.success(0)
            var nearby = _found.value.filter { it.id in paired }
            if (nearby.isEmpty()) {
                // Not seen lately: look again for a few seconds.
                withContext(Dispatchers.Main) { discover() }
                repeat(10) { if (nearby.isEmpty()) { delay(500); nearby = _found.value.filter { it.id in paired } } }
            }
            if (nearby.isEmpty()) { _status.value = tr("Aucun appareil associé à portée"); return@withContext Result.success(0) }
            var reached = 0
            var refused: String? = null
            nearby.forEach { peer ->
                runCatching {
                    if (peer.version < LanProtocol.VERSION) throw LanProtocol.PeerTooOld()
                    val secret = (secretOf(peer.id) as? LanServer.Secret.Known)?.bytes ?: throw IOException("not paired")
                    LanServer.connect(peer.host, peer.port) { s, deadline ->
                        val ch = LanProtocol.syncConnect(s.getInputStream(), s.getOutputStream(), deviceId, peer.id, secret)
                        deadline.arm(LanServer.TRANSFER_DEADLINE)
                        s.soTimeout = LanServer.DATA_IDLE
                        exchange(ch, peer.id, initiator = true) { true } ?: throw IOException("forgotten")
                    }
                    reached++
                }.onFailure { e -> if (e is LanProtocol.LanException) refused = tr("%1\$s : %2\$s", peer.name, e.message.orEmpty()) }
            }
            _status.value = when {
                reached == 0 -> refused ?: tr("Appareils associés injoignables")
                reached == 1 -> tr("Synchronisé avec 1 appareil")
                else -> tr("Synchronisé avec %1\$s appareils", reached)
            }
            Result.success(reached)
        } finally {
            _running.value = false
        }
    }

    /**
     * Both sides send their data and merge the other's (latest change wins), so both end the same ([LanExchange]: the
     * responder merges first and answers with its data merged). [peer]: the device authenticated by the handshake.
     * Whether anything changed here, or null when nothing was merged (stopped or forgotten meanwhile).
     */
    private suspend fun exchange(ch: LanProtocol.Channel, peer: String, initiator: Boolean, live: () -> Boolean): Boolean? {
        val merge = SyncMerge(app.database)
        fun allowed() = _enabled.value && live() && secrets.containsKey(peer)
        // The themes left out here are neither taken nor sent.
        fun decode(text: String) = SyncPart.without(json.decodeFromString(BackupData.serializer(), text), app.backupPrefs.syncSkip.value)
        if (initiator) {
            val mine = snapshotOf(merge)
            val (them, theirs) = LanExchange.initiate(ch, hello(), mine, peer)
            if (!allowed()) return null
            // The responder's data already holds this device's: a tie still there is its choice, not moved past again.
            val changed = merge.merge(decode(theirs), moveTies = false).changed
            exchanged.set(fingerprint(if (changed) snapshotOf(merge) else mine))
            touchPeer(peer, them.name, them.platform)
            return changed
        }
        var changed: Boolean? = null
        LanExchange.respond(
            ch, hello(),
            merge = { theirs -> allowed().also { ok -> if (ok) changed = merge.merge(decode(theirs)).changed } },
            snapshot = { snapshotOf(merge).also { exchanged.set(fingerprint(it)) } },
        )
        return changed
    }

    // ── Storage ───────────────────────────────────────────────────────────

    private fun secretKey(id: String) = "$SECRET_PREFIX$id"
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    /**
     * Paired with [id]: keeps its own secret and lists it (again, when it was to pair again). The Keystore first:
     * when it fails (thrown), the device is neither accepted nor listed.
     */
    private fun savePeer(id: String, name: String, platform: String?, secret: ByteArray) {
        app.secrets.put(secretKey(id), b64(secret))
        secrets[id] = secret
        unreadSecrets.remove(id)
        roundOwed.set(true)
        _peers.update { list -> list.filterNot { it.id == id } + Peer(id, name, 0, platform) }
        storePeers()
        _lastPaired.value = id to System.currentTimeMillis()
    }

    /** Synced with now; keeps its place in the list (and its platform when this one does not tell). Only a paired device. */
    private fun touchPeer(id: String, name: String, platform: String?) {
        val now = System.currentTimeMillis()
        if (_peers.value.none { it.id == id }) return
        _peers.update { list -> list.map { if (it.id == id) it.copy(name = name, lastSync = now, platform = platform ?: it.platform) else it } }
        storePeers()
    }

    /** The paired devices listed here; whether each still has its secret is known once read ([loadSecrets]). */
    private fun loadPeers(): List<Peer> = runCatching {
        val a = JSONArray(prefs.getString(KEY_PEERS, "[]"))
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Peer(it.getString("id"), it.getString("name"), it.optLong("last"), it.platform()) }
        }
    }.getOrDefault(emptyList())

    /** Written from several threads: one at a time, always the latest list. */
    @Synchronized
    private fun storePeers() {
        val a = JSONArray()
        _peers.value.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("last", it.lastSync).put("platform", it.platform)) }
        prefs.edit().putString(KEY_PEERS, a.toString()).apply()
    }

    companion object {
        const val SERVICE = "_nautile._tcp."
        const val PLATFORM = "android"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PEERS = "peers"
        /** The single secret of protocol v1, dropped: each device now has its own. */
        private const val SECRET_V1 = "lan_pairing_secret"
        private const val SECRET_PREFIX = "lan_peer_secret_"
        /** How often a secret the Keystore could not read is tried again. */
        private const val SECRET_RETRY_MS = 10_000L
    }
}
