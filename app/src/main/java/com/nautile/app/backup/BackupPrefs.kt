package com.nautile.app.backup

import android.content.Context
import com.nautile.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Where backups go. */
sealed interface BackupDestination {
    /** A document chosen with Android's picker: phone storage, Google Drive, OneDrive… */
    data class Document(val uri: String, val label: String) : BackupDestination
    /** A WebDAV URL (Nextcloud, ownCloud…); a URL ending with / is a folder. */
    data class WebDav(val url: String, val username: String) : BackupDestination
    /** An S3 bucket (AWS, Backblaze B2, Cloudflare R2, MinIO…); the secret key stays in the [SecretStore]. */
    data class S3(val endpoint: String, val region: String, val bucket: String, val key: String, val accessKey: String) : BackupDestination
    /** A folder picked with Android's picker (one Syncthing or the Nextcloud app keeps in step with a computer). */
    data class Folder(val treeUri: String, val label: String) : BackupDestination
}

/** How often the automatic backup runs; the daily and longer ones run at a chosen time. */
enum class BackupFrequency(val hours: Long) {
    HOURS_6(6), HOURS_12(12), DAY(24), WEEK(24 * 7), MONTH(24 * 30);

    val atTime: Boolean get() = hours >= 24
}

/** Which networks the backup and the synchronisation may use. */
enum class BackupNetwork { WIFI, ANY }

/** One place backups go, with its own schedule and status. */
data class BackupTarget(
    val id: String,
    val dest: BackupDestination,
    val auto: Boolean = false,
    val frequency: BackupFrequency = BackupFrequency.WEEK,
    /** Minutes after midnight for the daily, weekly and monthly backups. */
    val time: Int = 3 * 60,
    /** ISO day of week (1 = Monday) for the weekly backup. */
    val weekday: Int = 7,
    val network: BackupNetwork = BackupNetwork.WIFI,
    /** Only while charging (automatic backups never run on a low battery anyway). */
    val charging: Boolean = false,
    val last: Long? = null,
    /** Why the last backup here failed, cleared by the next success. */
    val error: String? = null,
)

/** Backup and sync settings and status. The backup password and the destinations' secrets stay in the [SecretStore]. */
class BackupPrefs(context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("nautile_backup", Context.MODE_PRIVATE)

    private val _targets = MutableStateFlow(loadTargets())
    /** Every backup place, in the order they were added. */
    val targets: StateFlow<List<BackupTarget>> = _targets.asStateFlow()

    private val _syncTargetId = MutableStateFlow(prefs.getString(KEY_SYNC_TARGET, null))
    /** The place whose file the synchronisation shares. */
    val syncTargetId: StateFlow<String?> = _syncTargetId.asStateFlow()
    val syncTarget: BackupTarget? get() = _syncTargetId.value?.let(::target)

    fun setSyncTarget(id: String?) {
        _syncTargetId.value = id
        prefs.edit().putString(KEY_SYNC_TARGET, id).apply()
    }

    /** The most recent backup anywhere (or synchronisation), null if never. */
    val lastExport: StateFlow<Long?> get() = _lastExport
    private val _lastExport = MutableStateFlow(prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 })

    private val _hasPassword = MutableStateFlow(secrets.get(SECRET_PASSWORD) != null)
    /** A backup password is stored, so backups can run without asking. */
    val hasPassword: StateFlow<Boolean> = _hasPassword.asStateFlow()

    private val _syncEnabled = MutableStateFlow(prefs.getBoolean(KEY_SYNC, false))
    /** Synchronise with the other devices through [syncTarget]. */
    val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()
    fun setSyncEnabled(v: Boolean) { _syncEnabled.value = v; prefs.edit().putBoolean(KEY_SYNC, v).apply() }

    private val _lastSync = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 })
    val lastSync: StateFlow<Long?> = _lastSync.asStateFlow()

    private val _syncError = MutableStateFlow(prefs.getString(KEY_SYNC_ERROR, null))
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _devices = MutableStateFlow(loadDevices())
    /** Devices seen in the synchronisation file (this one included). */
    val devices: StateFlow<List<com.nautile.data.backup.SyncDevice>> = _devices.asStateFlow()

    /** This installation, as named in the synchronisation file. */
    val deviceId: String = prefs.getString(KEY_DEVICE, null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    /** Latest local change already in the remote file. */
    var syncedChange: Long
        get() = prefs.getLong(KEY_SYNCED_CHANGE, 0L)
        set(v) = prefs.edit().putLong(KEY_SYNCED_CHANGE, v).apply()

    private val _network = MutableStateFlow(
        prefs.getString(KEY_NETWORK, null)?.let { n -> BackupNetwork.entries.firstOrNull { it.name == n } } ?: BackupNetwork.WIFI,
    )
    /** Networks the synchronisation may use. */
    val network: StateFlow<BackupNetwork> = _network.asStateFlow()
    fun setNetwork(n: BackupNetwork) { _network.value = n; prefs.edit().putString(KEY_NETWORK, n.name).apply() }

    private val _syncMinutes = MutableStateFlow(prefs.getInt(KEY_SYNC_MINUTES, 60))
    /** How often synchronisation runs in the background (it also runs after each change). */
    val syncMinutes: StateFlow<Int> = _syncMinutes.asStateFlow()
    fun setSyncMinutes(m: Int) { _syncMinutes.value = m; prefs.edit().putInt(KEY_SYNC_MINUTES, m).apply() }

    // ── Targets ────────────────────────────────────────────────────────────

    fun target(id: String): BackupTarget? = _targets.value.firstOrNull { it.id == id }

    /** Adds a place; [secret] is its WebDAV password or S3 secret key. */
    fun addTarget(dest: BackupDestination, secret: String? = null): BackupTarget {
        val t = BackupTarget(java.util.UUID.randomUUID().toString(), dest)
        if (!secret.isNullOrEmpty()) secrets.put(secretKey(t.id), secret)
        _targets.value = _targets.value + t
        storeTargets()
        return t
    }

    fun updateTarget(t: BackupTarget, secret: String? = null) {
        if (!secret.isNullOrEmpty()) secrets.put(secretKey(t.id), secret)
        _targets.value = _targets.value.map { if (it.id == t.id) t else it }
        storeTargets()
    }

    fun removeTarget(id: String) {
        _targets.value = _targets.value.filterNot { it.id == id }
        storeTargets()
        secrets.put(secretKey(id), null)
        if (_syncTargetId.value == id) { setSyncTarget(null); setSyncEnabled(false) }
    }

    /** The WebDAV password or S3 secret key of a place. */
    fun secret(id: String): String? = secrets.get(secretKey(id))

    fun markDone(id: String, at: Long = System.currentTimeMillis()) {
        target(id)?.let { updateTarget(it.copy(last = at, error = null)) }
        markExported(at)
    }

    fun markFailed(id: String, reason: String) {
        target(id)?.let { updateTarget(it.copy(error = reason)) }
    }

    /** A backup was written (a file export too). */
    fun markExported(at: Long = System.currentTimeMillis()) {
        _lastExport.value = at
        prefs.edit().putLong(KEY_LAST, at).apply()
    }

    fun markSynced(devices: List<com.nautile.data.backup.SyncDevice>, at: Long = System.currentTimeMillis()) {
        _lastSync.value = at; _syncError.value = null; _devices.value = devices
        _lastExport.value = at
        syncTarget?.let { updateTarget(it.copy(last = at, error = null)) }
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.nautile.data.backup.SyncDevice.serializer()), devices,
        )
        prefs.edit().putLong(KEY_LAST_SYNC, at).putLong(KEY_LAST, at).remove(KEY_SYNC_ERROR).putString(KEY_DEVICES, json).apply()
    }

    fun markSyncFailed(reason: String) {
        _syncError.value = reason
        prefs.edit().putString(KEY_SYNC_ERROR, reason).apply()
    }

    fun password(): String? = secrets.get(SECRET_PASSWORD)
    fun setPassword(p: String?) {
        secrets.put(SECRET_PASSWORD, p)
        _hasPassword.value = !p.isNullOrEmpty()
        if (p.isNullOrEmpty()) _targets.value.filter { it.auto }.forEach { updateTarget(it.copy(auto = false)) }
    }

    private fun loadDevices(): List<com.nautile.data.backup.SyncDevice> = runCatching {
        kotlinx.serialization.json.Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(com.nautile.data.backup.SyncDevice.serializer()),
            prefs.getString(KEY_DEVICES, null) ?: return@runCatching emptyList(),
        )
    }.getOrDefault(emptyList())

    // ── Storage ────────────────────────────────────────────────────────────

    private fun secretKey(id: String) = "backup_target_secret_$id"

    private fun loadTargets(): List<BackupTarget> {
        prefs.getString(KEY_TARGETS, null)?.let { s ->
            return runCatching { JSONArray(s).let { a -> (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        }
        // Before several places: the single destination and its schedule become the first place.
        val old = legacyDestination() ?: return emptyList<BackupTarget>().also { prefs.edit().putString(KEY_TARGETS, "[]").apply() }
        val t = BackupTarget(
            id = "t1", dest = old,
            auto = prefs.getBoolean("auto", false),
            frequency = prefs.getString("frequency", null)?.let { f -> BackupFrequency.entries.firstOrNull { it.name == f } } ?: BackupFrequency.WEEK,
            time = prefs.getInt("time", 3 * 60), weekday = prefs.getInt("weekday", 7),
            network = prefs.getString(KEY_NETWORK, null)?.let { n -> BackupNetwork.entries.firstOrNull { it.name == n } } ?: BackupNetwork.WIFI,
            charging = prefs.getBoolean("charging", false),
            last = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }, error = prefs.getString("last_error", null),
        )
        (secrets.get("backup_webdav_password") ?: secrets.get("backup_s3_secret"))?.let { secrets.put(secretKey(t.id), it) }
        prefs.edit().putString(KEY_TARGETS, JSONArray().put(toJson(t)).toString()).putString(KEY_SYNC_TARGET, t.id).apply()
        return listOf(t)
    }

    private fun storeTargets() {
        val a = JSONArray()
        _targets.value.forEach { a.put(toJson(it)) }
        prefs.edit().putString(KEY_TARGETS, a.toString()).apply()
    }

    private fun legacyDestination(): BackupDestination? {
        prefs.getString("doc_uri", null)?.let { return BackupDestination.Document(it, prefs.getString("doc_label", null).orEmpty()) }
        prefs.getString("dav_url", null)?.let { return BackupDestination.WebDav(it, prefs.getString("dav_user", null).orEmpty()) }
        prefs.getString("s3_bucket", null)?.let { bucket ->
            return BackupDestination.S3(
                prefs.getString("s3_endpoint", null).orEmpty(), prefs.getString("s3_region", null) ?: "us-east-1", bucket,
                prefs.getString("s3_key", null) ?: AutoBackup.FILE_NAME, prefs.getString("s3_access", null).orEmpty(),
            )
        }
        prefs.getString("folder_uri", null)?.let { return BackupDestination.Folder(it, prefs.getString("folder_label", null).orEmpty()) }
        return null
    }

    companion object {
        private const val KEY_TARGETS = "targets"
        private const val KEY_SYNC_TARGET = "sync_target"
        private const val KEY_LAST = "last_export"
        private const val KEY_SYNC = "sync_enabled"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_SYNC_ERROR = "sync_error"
        private const val KEY_DEVICES = "sync_devices"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_SYNCED_CHANGE = "synced_change"
        private const val KEY_NETWORK = "network"
        private const val KEY_SYNC_MINUTES = "sync_minutes"
        private const val SECRET_PASSWORD = "backup_password"
        val SYNC_CHOICES = listOf(15, 60, 360, 1440)
        /** After this, Settings suggests backing up again. */
        const val STALE_AFTER_MS = 30L * 24 * 3600 * 1000

        fun isStale(last: Long?, now: Long = System.currentTimeMillis()) = last == null || now - last > STALE_AFTER_MS

        internal fun toJson(t: BackupTarget): JSONObject = JSONObject().apply {
            put("id", t.id); put("auto", t.auto); put("frequency", t.frequency.name); put("time", t.time); put("weekday", t.weekday)
            put("network", t.network.name); put("charging", t.charging); t.last?.let { put("last", it) }; t.error?.let { put("error", it) }
            when (val d = t.dest) {
                is BackupDestination.Document -> { put("type", "document"); put("uri", d.uri); put("label", d.label) }
                is BackupDestination.WebDav -> { put("type", "webdav"); put("url", d.url); put("user", d.username) }
                is BackupDestination.S3 -> { put("type", "s3"); put("endpoint", d.endpoint); put("region", d.region); put("bucket", d.bucket); put("key", d.key); put("access", d.accessKey) }
                is BackupDestination.Folder -> { put("type", "folder"); put("uri", d.treeUri); put("label", d.label) }
            }
        }

        internal fun fromJson(o: JSONObject): BackupTarget? {
            val dest = when (o.optString("type")) {
                "document" -> BackupDestination.Document(o.getString("uri"), o.optString("label"))
                "webdav" -> BackupDestination.WebDav(o.getString("url"), o.optString("user"))
                "s3" -> BackupDestination.S3(o.optString("endpoint"), o.optString("region", "us-east-1"), o.getString("bucket"), o.optString("key", AutoBackup.FILE_NAME), o.optString("access"))
                "folder" -> BackupDestination.Folder(o.getString("uri"), o.optString("label"))
                else -> return null
            }
            return BackupTarget(
                id = o.getString("id"), dest = dest, auto = o.optBoolean("auto"),
                frequency = BackupFrequency.entries.firstOrNull { it.name == o.optString("frequency") } ?: BackupFrequency.WEEK,
                time = o.optInt("time", 3 * 60), weekday = o.optInt("weekday", 7),
                network = BackupNetwork.entries.firstOrNull { it.name == o.optString("network") } ?: BackupNetwork.WIFI,
                charging = o.optBoolean("charging"),
                last = if (o.has("last")) o.getLong("last") else null, error = if (o.has("error")) o.getString("error") else null,
            )
        }
    }
}
