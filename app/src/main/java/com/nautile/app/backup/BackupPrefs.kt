package com.nautile.app.backup

import android.content.Context
import com.nautile.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where automatic and « Sauvegarder maintenant » backups go. */
sealed interface BackupDestination {
    /** A document chosen with Android's picker: phone storage, Google Drive, OneDrive… */
    data class Document(val uri: String, val label: String) : BackupDestination
    /** A WebDAV URL (Nextcloud, ownCloud…); a URL ending with / is a folder. */
    data class WebDav(val url: String, val username: String) : BackupDestination
}

/** How often the automatic backup runs; the daily and longer ones run at a chosen time. */
enum class BackupFrequency(val hours: Long) {
    HOURS_6(6), HOURS_12(12), DAY(24), WEEK(24 * 7), MONTH(24 * 30);

    val atTime: Boolean get() = hours >= 24
}

/** Which networks the backup and the synchronisation may use. */
enum class BackupNetwork { WIFI, ANY }

/** Backup settings and status. The backup password and the WebDAV password stay in the [SecretStore]. */
class BackupPrefs(context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("nautile_backup", Context.MODE_PRIVATE)

    private val _lastExport = MutableStateFlow(prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 })
    /** Epoch millis of the last successful backup, null if never. */
    val lastExport: StateFlow<Long?> = _lastExport.asStateFlow()

    private val _lastError = MutableStateFlow(prefs.getString(KEY_ERROR, null))
    /** Why the last automatic backup failed, cleared by the next success. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _destination = MutableStateFlow(loadDestination())
    val destination: StateFlow<BackupDestination?> = _destination.asStateFlow()

    private val _auto = MutableStateFlow(prefs.getBoolean(KEY_AUTO, false))
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    private val _frequency = MutableStateFlow(
        prefs.getString(KEY_FREQ, null)?.let { f -> BackupFrequency.entries.firstOrNull { it.name == f } } ?: BackupFrequency.WEEK,
    )
    val frequency: StateFlow<BackupFrequency> = _frequency.asStateFlow()

    private val _hasPassword = MutableStateFlow(secrets.get(SECRET_PASSWORD) != null)
    /** A backup password is stored, so backups can run without asking. */
    val hasPassword: StateFlow<Boolean> = _hasPassword.asStateFlow()

    private val _syncEnabled = MutableStateFlow(prefs.getBoolean(KEY_SYNC, false))
    /** Synchronise with the other devices through the destination. */
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

    fun markSynced(devices: List<com.nautile.data.backup.SyncDevice>, at: Long = System.currentTimeMillis()) {
        _lastSync.value = at; _syncError.value = null; _devices.value = devices
        _lastExport.value = at
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.nautile.data.backup.SyncDevice.serializer()), devices,
        )
        prefs.edit().putLong(KEY_LAST_SYNC, at).putLong(KEY_LAST, at).remove(KEY_SYNC_ERROR).remove(KEY_ERROR).putString(KEY_DEVICES, json).apply()
    }

    fun markSyncFailed(reason: String) {
        _syncError.value = reason
        prefs.edit().putString(KEY_SYNC_ERROR, reason).apply()
    }

    private fun loadDevices(): List<com.nautile.data.backup.SyncDevice> = runCatching {
        kotlinx.serialization.json.Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(com.nautile.data.backup.SyncDevice.serializer()),
            prefs.getString(KEY_DEVICES, null) ?: return@runCatching emptyList(),
        )
    }.getOrDefault(emptyList())

    fun markExported(at: Long = System.currentTimeMillis()) {
        _lastExport.value = at
        _lastError.value = null
        prefs.edit().putLong(KEY_LAST, at).remove(KEY_ERROR).apply()
    }

    fun markFailed(reason: String) {
        _lastError.value = reason
        prefs.edit().putString(KEY_ERROR, reason).apply()
    }

    fun setDestination(d: BackupDestination?, webDavPassword: String? = null) {
        _destination.value = d
        val e = prefs.edit().remove(KEY_DOC_URI).remove(KEY_DOC_LABEL).remove(KEY_DAV_URL).remove(KEY_DAV_USER)
        when (d) {
            is BackupDestination.Document -> e.putString(KEY_DOC_URI, d.uri).putString(KEY_DOC_LABEL, d.label)
            is BackupDestination.WebDav -> e.putString(KEY_DAV_URL, d.url).putString(KEY_DAV_USER, d.username)
            null -> {}
        }
        e.apply()
        if (d !is BackupDestination.WebDav) secrets.put(SECRET_DAV, null) else if (webDavPassword != null) secrets.put(SECRET_DAV, webDavPassword)
        if (d == null) { setAuto(false); setSyncEnabled(false) }
    }

    fun webDavPassword(): String? = secrets.get(SECRET_DAV)

    fun setAuto(v: Boolean) { _auto.value = v; prefs.edit().putBoolean(KEY_AUTO, v).apply() }
    fun setFrequency(f: BackupFrequency) { _frequency.value = f; prefs.edit().putString(KEY_FREQ, f.name).apply() }

    private val _time = MutableStateFlow(prefs.getInt(KEY_TIME, 3 * 60))
    /** Minutes after midnight for the daily, weekly and monthly backups. */
    val time: StateFlow<Int> = _time.asStateFlow()
    fun setTime(minutes: Int) { _time.value = minutes; prefs.edit().putInt(KEY_TIME, minutes).apply() }

    private val _weekday = MutableStateFlow(prefs.getInt(KEY_WEEKDAY, 7))
    /** ISO day of week (1 = Monday) for the weekly backup. */
    val weekday: StateFlow<Int> = _weekday.asStateFlow()
    fun setWeekday(d: Int) { _weekday.value = d; prefs.edit().putInt(KEY_WEEKDAY, d).apply() }

    private val _network = MutableStateFlow(
        prefs.getString(KEY_NETWORK, null)?.let { n -> BackupNetwork.entries.firstOrNull { it.name == n } } ?: BackupNetwork.WIFI,
    )
    val network: StateFlow<BackupNetwork> = _network.asStateFlow()
    fun setNetwork(n: BackupNetwork) { _network.value = n; prefs.edit().putString(KEY_NETWORK, n.name).apply() }

    private val _charging = MutableStateFlow(prefs.getBoolean(KEY_CHARGING, false))
    /** Only while the phone is charging. */
    val charging: StateFlow<Boolean> = _charging.asStateFlow()
    fun setCharging(v: Boolean) { _charging.value = v; prefs.edit().putBoolean(KEY_CHARGING, v).apply() }

    private val _batteryOk = MutableStateFlow(prefs.getBoolean(KEY_BATTERY, true))
    /** Not when the battery is low. */
    val batteryOk: StateFlow<Boolean> = _batteryOk.asStateFlow()
    fun setBatteryOk(v: Boolean) { _batteryOk.value = v; prefs.edit().putBoolean(KEY_BATTERY, v).apply() }

    fun password(): String? = secrets.get(SECRET_PASSWORD)
    fun setPassword(p: String?) {
        secrets.put(SECRET_PASSWORD, p)
        _hasPassword.value = !p.isNullOrEmpty()
        if (p.isNullOrEmpty()) setAuto(false)
    }

    private fun loadDestination(): BackupDestination? {
        prefs.getString(KEY_DOC_URI, null)?.let { return BackupDestination.Document(it, prefs.getString(KEY_DOC_LABEL, null).orEmpty()) }
        prefs.getString(KEY_DAV_URL, null)?.let { return BackupDestination.WebDav(it, prefs.getString(KEY_DAV_USER, null).orEmpty()) }
        return null
    }

    companion object {
        private const val KEY_LAST = "last_export"
        private const val KEY_SYNC = "sync_enabled"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_SYNC_ERROR = "sync_error"
        private const val KEY_DEVICES = "sync_devices"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_SYNCED_CHANGE = "synced_change"
        private const val KEY_ERROR = "last_error"
        private const val KEY_AUTO = "auto"
        private const val KEY_FREQ = "frequency"
        private const val KEY_TIME = "time"
        private const val KEY_WEEKDAY = "weekday"
        private const val KEY_NETWORK = "network"
        private const val KEY_CHARGING = "charging"
        private const val KEY_BATTERY = "battery_ok"
        private const val KEY_DOC_URI = "doc_uri"
        private const val KEY_DOC_LABEL = "doc_label"
        private const val KEY_DAV_URL = "dav_url"
        private const val KEY_DAV_USER = "dav_user"
        private const val SECRET_PASSWORD = "backup_password"
        private const val SECRET_DAV = "backup_webdav_password"
        /** After this, Settings suggests backing up again. */
        const val STALE_AFTER_MS = 30L * 24 * 3600 * 1000

        fun isStale(last: Long?, now: Long = System.currentTimeMillis()) = last == null || now - last > STALE_AFTER_MS
    }
}
