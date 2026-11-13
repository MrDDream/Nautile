package com.nautile.app.backup

import com.nautile.i18n.tr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nautile.app.NautileApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Writes encrypted backups to the chosen places, now or each on its own schedule. */
object AutoBackup {
    private const val TAG = "auto-backup"
    private const val SYNC_WORK = "sync"
    private const val KEY_TARGET = "target"
    const val FILE_NAME = "nautile.backup"
    private val http by lazy { OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build() }

    /**
     * Backs up to [targetId], or to every place when null; [password] defaults to the stored one.
     * The synchronised place is synchronised instead (merged, not overwritten). Fails if any place failed.
     */
    suspend fun run(app: NautileApp, targetId: String? = null, password: String? = null): Result<Unit> = runCatching {
        val prefs = app.backupPrefs
        val targets = if (targetId == null) prefs.targets.value else listOfNotNull(prefs.target(targetId))
        if (targets.isEmpty()) error(tr("Aucun emplacement de sauvegarde"))
        val syncing = prefs.syncEnabled.value
        val plain = targets.filterNot { syncing && it.id == prefs.syncTargetId.value }
        var failure: Throwable? = null
        if (plain.size < targets.size) app.sync.sync(password).onFailure { failure = it }
        if (plain.isNotEmpty()) {
            val pw = password ?: prefs.password() ?: error(tr("Mot de passe de sauvegarde non enregistré"))
            val bytes = app.backups.export(pw).toByteArray()
            withContext(Dispatchers.IO) { BackupVersions.add(app, bytes) }
            plain.forEach { t ->
                if (t.network == BackupNetwork.VPN && !isLocal(t.dest) && !vpnUp(app)) {
                    failure = failure ?: IOException(tr("En attente du VPN"))
                    return@forEach
                }
                runCatching {
                    withContext(Dispatchers.IO) {
                        val store = BackupStores.of(app, t, prefs)
                        store.rotate()
                        store.write(bytes)
                    }
                }.onSuccess { prefs.markDone(t.id) }
                    .onFailure { e -> prefs.markFailed(t.id, e.message ?: e.javaClass.simpleName); failure = failure ?: e }
            }
        }
        failure?.let { throw it }
    }

    /** Full URL of the backup file: a folder URL (ending with /) gets [FILE_NAME]. */
    fun webDavFileUrl(url: String) = if (url.endsWith("/")) url + FILE_NAME else url

    /** Checks a WebDAV destination by writing a small file next to the backup, then deleting it. */
    suspend fun testWebDav(url: String, username: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { BackupStores.probe(WebDavStore(url, username, password, http)) }
    }

    /** Same for S3. */
    suspend fun testS3(dest: BackupDestination.S3, secret: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { BackupStores.probe(S3Store(S3Client(dest.endpoint, dest.region, dest.bucket, dest.accessKey, secret, http), dest.key)) }
    }

    /** « Téléphone › Syncthing › Nautile » for a folder picked with Android's picker. */
    fun describeFolder(context: Context, tree: Uri): String {
        val provider = when (tree.authority) {
            "com.android.externalstorage.documents" -> tr("Téléphone")
            "org.nextcloud.documents", "com.nextcloud.client.documents" -> "Nextcloud"
            else -> tree.authority.orEmpty()
        }
        val path = runCatching { android.provider.DocumentsContract.getTreeDocumentId(tree).substringAfter(':') }.getOrNull().orEmpty()
        return listOf(provider, path.replace("/", " › ")).filter { it.isNotEmpty() }.joinToString(" › ")
    }

    /** « Google Drive », « OneDrive »… for a document picked with Android's file picker. */
    fun describe(context: Context, uri: Uri): String {
        val provider = when (uri.authority) {
            "com.google.android.apps.docs.storage", "com.google.android.apps.docs.storage.legacy" -> "Google Drive"
            "com.microsoft.skydrive.content.StorageAccessProvider" -> "OneDrive"
            "com.android.externalstorage.documents" -> tr("Téléphone")
            "com.android.providers.downloads.documents" -> tr("Téléchargements")
            "org.nextcloud.documents", "com.nextcloud.client.documents" -> "Nextcloud"
            "com.dropbox.product.android.dbapp.document_provider.documents" -> "Dropbox"
            else -> uri.authority.orEmpty()
        }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return listOfNotNull(provider.ifEmpty { null }, name).joinToString(" › ")
    }

    private fun network(dest: BackupDestination, network: BackupNetwork) = when {
        isLocal(dest) -> NetworkType.NOT_REQUIRED
        // WorkManager has no « VPN » condition: any network, then the VPN is checked before writing.
        network == BackupNetwork.ANY || network == BackupNetwork.VPN -> NetworkType.CONNECTED
        else -> NetworkType.UNMETERED
    }

    /** Whether a VPN network is up (WireGuard, Tailscale, OpenVPN… all show as TRANSPORT_VPN). */
    fun vpnUp(context: Context): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** Schedules each place's automatic backup and the synchronisation, cancels the rest. */
    fun schedule(app: NautileApp) {
        val prefs = app.backupPrefs
        val wm = WorkManager.getInstance(app)
        val now = System.currentTimeMillis()
        val sync = prefs.syncEnabled.value
        val syncTarget = prefs.syncTarget.takeIf { sync && prefs.hasPassword.value }

        // Synchronisation: light and timely, no battery condition.
        if (syncTarget == null) wm.cancelUniqueWork(SYNC_WORK)
        else wm.enqueueUniquePeriodicWork(
            SYNC_WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<Worker>(prefs.syncMinutes.value.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
                .setInputData(androidx.work.workDataOf(KEY_TARGET to syncTarget.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network(syncTarget.dest, prefs.network.value)).build())
                .build(),
        )

        // Each place on its own schedule (the synchronised one is kept up to date by the synchronisation).
        val wanted = prefs.targets.value.filter { it.auto && prefs.hasPassword.value && it.id != syncTarget?.id }
        val wantedNames = wanted.map { "$TAG-${it.id}" }.toSet()
        runCatching {
            wm.getWorkInfosByTag(TAG).get().flatMap { it.tags }.filter { it.startsWith("$TAG-") && it !in wantedNames }.forEach { wm.cancelUniqueWork(it) }
        }
        wm.cancelUniqueWork(TAG) // the single schedule of earlier versions
        wanted.forEach { t ->
            val next = nextRun(t.frequency, t.time, t.weekday, t.last, now)
            val request = PeriodicWorkRequestBuilder<Worker>(t.frequency.hours, TimeUnit.HOURS)
                .setInitialDelay((next - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(androidx.work.workDataOf(KEY_TARGET to t.id))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(network(t.dest, t.network))
                        .setRequiresBatteryNotLow(true).setRequiresCharging(t.charging).build(),
                )
                .addTag(TAG).addTag("$TAG-${t.id}")
                .build()
            // Re-created so a new time or frequency takes effect now; the delay keeps the rhythm of the last backup.
            wm.enqueueUniquePeriodicWork("$TAG-${t.id}", ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        }
    }

    /**
     * When the next automatic backup is due: one period after the last one, at the chosen time (and
     * weekday) for the daily and longer ones. Conditions (network, battery) may delay it further.
     */
    fun nextRun(f: BackupFrequency, minutes: Int, weekday: Int, last: Long?, now: Long = System.currentTimeMillis()): Long {
        val period = f.hours * 3_600_000L
        // The next slot at the chosen time, unless the last backup is too recent for it: half a
        // period (a monthly one: all but two days), so « every Sunday » means the coming Sunday.
        val slack = if (f == BackupFrequency.MONTH) 2 * 24 * 3_600_000L else period / 2
        val earliest = maxOf(now, (last ?: 0L) + period - slack)
        if (!f.atTime) return maxOf(now + 15 * 60_000L, (last ?: now) + period)
        val zone = java.time.ZoneId.systemDefault()
        var day = java.time.Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate()
        repeat(40) {
            val at = day.atTime(minutes / 60, minutes % 60).atZone(zone).toInstant().toEpochMilli()
            if (at >= earliest && (f != BackupFrequency.WEEK || day.dayOfWeek.value == weekday)) return at
            day = day.plusDays(1)
        }
        return earliest
    }

    /** A file on the phone itself: no network needed. */
    fun isLocal(dest: BackupDestination) = when (dest) {
        is BackupDestination.Document -> Uri.parse(dest.uri).authority in LOCAL_AUTHORITIES
        is BackupDestination.Folder -> Uri.parse(dest.treeUri).authority in LOCAL_AUTHORITIES
        else -> false
    }

    private val LOCAL_AUTHORITIES = setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents")

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as NautileApp
            val id = inputData.getString(KEY_TARGET) ?: return Result.failure()
            if (app.backupPrefs.target(id) == null) return Result.failure()
            return AutoBackup.run(app, id).fold(
                onSuccess = { Result.success() },
                onFailure = { e -> if (e is IOException && runAttemptCount < 3) Result.retry() else Result.failure() },
            )
        }
    }
}
