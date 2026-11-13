package com.nautile.app.backup

import com.nautile.i18n.tr

import android.net.Uri
import android.os.Build
import androidx.room.InvalidationTracker
import com.nautile.app.NautileApp
import com.nautile.data.backup.BackupCodec
import com.nautile.data.backup.SyncDevice
import com.nautile.data.sync.SyncMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps several devices in step through one encrypted file (the backup destination):
 * download, merge (latest change wins, deletions included), upload. On WebDAV the upload is conditional
 * (ETag), so two devices writing at once retry instead of overwriting each other.
 */
class SyncManager(private val app: NautileApp, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val http by lazy { OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build() }
    private val prefs get() = app.backupPrefs
    private var pending: Job? = null
    /** Servers unreachable in a row: retried silently (a VPN not up yet, a server asleep). */
    private var netFailures = 0
    private val _waitingVpn = MutableStateFlow(false)
    /** « Seulement via le VPN » and no VPN is up: the synchronisation waits for it. */
    val waitingVpn: StateFlow<Boolean> = _waitingVpn.asStateFlow()


    /** Downloads, merges and uploads. [password] overrides the stored one (first synchronisation). */
    suspend fun sync(password: String? = null): Result<SyncMerge.Stats> = mutex.withLock {
        _running.value = true
        try {
            val pw = password ?: prefs.password() ?: error(tr("Mot de passe de sauvegarde non enregistré"))
            val dest = prefs.syncTarget ?: error(tr("Aucun emplacement de synchronisation choisi"))
            if (prefs.network.value == BackupNetwork.VPN && !AutoBackup.isLocal(dest.dest) && !AutoBackup.vpnUp(app)) {
                _waitingVpn.value = true
                return@withLock Result.failure(IOException(tr("En attente du VPN")))
            }
            _waitingVpn.value = false
            val merge = SyncMerge(app.database)
            var attempt = 0
            while (true) {
                try {
                    val dirtyBefore = merge.lastChange() > prefs.syncedChange
                    val store = BackupStores.of(app, dest, prefs)
                    val remote = withContext(Dispatchers.IO) { store.read() }
                    val data = remote.text?.let { withContext(Dispatchers.Default) { BackupCodec.import(it, pw) } }
                    val stats = data?.let { merge.merge(it) } ?: SyncMerge.Stats()
                    val now = System.currentTimeMillis()
                    val me = SyncDevice(prefs.deviceId, Build.MODEL.ifBlank { "Android" }, now)
                    val others = data?.devices.orEmpty().filter { it.id != me.id && now - it.lastSeen < 180L * 24 * 3600 * 1000 }
                    val myLastSeen = data?.devices?.firstOrNull { it.id == me.id }?.lastSeen ?: 0L
                    val devices = (others + me).sortedByDescending { it.lastSeen }
                    if (data == null || dirtyBefore || stats.changed || now - myLastSeen > 12 * 3600_000L) {
                        val text = withContext(Dispatchers.Default) { BackupCodec.export(merge.snapshot(devices), pw) }
                        withContext(Dispatchers.IO) {
                            store.write(text.toByteArray(), remote)
                            // The synchronised file is also the backup: keep a daily copy on the phone.
                            BackupVersions.addIfOlder(app, text.toByteArray(), 20 * 3600_000L)
                        }
                    }
                    prefs.syncedChange = merge.lastChange()
                    prefs.markSynced(devices, now)
                    netFailures = 0
                    return@withLock Result.success(stats)
                } catch (e: ConflictException) {
                    // Another device wrote in between: start again from its version.
                    if (++attempt >= 3) throw IOException(tr("Conflit d'écriture répété avec un autre appareil"))
                    delay(500L * attempt)
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } catch (e: Exception) {
            if (unreachable(e) && netFailures < 3) {
                // Again in 1, 2, then 5 minutes, without showing an error.
                val wait = longArrayOf(60_000, 120_000, 300_000)[netFailures++]
                requestSoon(wait)
                return@withLock Result.failure(IOException(e.message ?: e.javaClass.simpleName, e))
            }
            val reason = when (e) {
                is com.nautile.data.backup.WrongBackupPasswordException -> tr("Le mot de passe ne déchiffre pas le fichier de synchronisation")
                else -> e.message ?: e.javaClass.simpleName
            }
            prefs.markSyncFailed(reason)
            Result.failure(IOException(reason, e))
        } finally {
            _running.value = false
        }
    }

    private fun unreachable(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
        it is java.net.UnknownHostException || it is java.net.ConnectException ||
            it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException
    }

    /** Synchronises as soon as a VPN comes up, when the synchronisation waits for one. */
    fun watchVpn() {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (prefs.network.value == BackupNetwork.VPN) {
                    netFailures = 0
                    requestSoon(2_000)
                }
            }
        })
    }

    /** Whether the destination already holds a file (another device set it up). */
    suspend fun remoteExists(): Boolean = withContext(Dispatchers.IO) {
        val dest = prefs.syncTarget ?: return@withContext false
        runCatching { BackupStores.of(app, dest, prefs).read().text != null }.getOrDefault(false)
    }

    /** Checks [password] against the existing remote file. */
    suspend fun checkPassword(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = prefs.syncTarget ?: error(tr("Aucun emplacement de synchronisation choisi"))
            val text = BackupStores.of(app, dest, prefs).read().text ?: return@runCatching
            withContext(Dispatchers.Default) { BackupCodec.import(text, password) }
            Unit
        }
    }

    /** Synchronises soon (debounced), when enabled. */
    fun requestSoon(delayMs: Long = 8_000) {
        if (!prefs.syncEnabled.value) return
        pending?.cancel()
        pending = scope.launch { delay(delayMs); sync() }
    }

    /** Local edits to synced tables schedule a synchronisation. */
    fun watchLocalChanges() {
        app.database.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
            arrayOf("host", "host_group", "ssh_key", "identity", "snippet", "port_forward", "known_host", "sync_tombstone"),
        ) {
            override fun onInvalidated(tables: Set<String>) {
                // Writes made by the merge itself do not ask for another round.
                if (!_running.value) requestSoon()
                app.lanSync.requestSoon()
            }
        })
    }
}
