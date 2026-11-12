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

/** Writes an encrypted backup to the chosen destination, now or on a schedule. */
object AutoBackup {
    private const val WORK = "auto-backup"
    const val FILE_NAME = "nautile.backup"
    private val http by lazy { OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build() }

    /** Exports with [password] (default: the stored one) and writes it to the destination. */
    suspend fun run(app: NautileApp, password: String? = null): Result<Unit> = runCatching {
        val prefs = app.backupPrefs
        // With synchronisation on, the file is shared: merge instead of overwriting the other devices' changes.
        if (prefs.syncEnabled.value) { app.sync.sync(password).getOrThrow(); return@runCatching }
        val dest = prefs.destination.value ?: error(tr("Aucune destination choisie"))
        val password = password ?: prefs.password() ?: error(tr("Mot de passe de sauvegarde non enregistré"))
        val bytes = app.backups.export(password).toByteArray()
        withContext(Dispatchers.IO) {
            val store = BackupStores.of(app, dest, prefs)
            store.rotate()
            store.write(bytes)
            BackupVersions.add(app, bytes)
        }
        prefs.markExported()
    }.onFailure { e -> app.backupPrefs.markFailed(e.message ?: e.javaClass.simpleName) }

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

    /** Schedules or cancels the periodic backup to match the settings. */
    fun schedule(app: NautileApp) {
        val prefs = app.backupPrefs
        val wm = WorkManager.getInstance(app)
        val dest = prefs.destination.value
        val sync = prefs.syncEnabled.value
        if ((!prefs.auto.value && !sync) || dest == null || !prefs.hasPassword.value) { wm.cancelUniqueWork(WORK); return }
        val local = isLocal(dest)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                when {
                    local -> NetworkType.NOT_REQUIRED
                    prefs.network.value == BackupNetwork.ANY -> NetworkType.CONNECTED
                    else -> NetworkType.UNMETERED
                },
            )
            // Synchronisation is light and must stay timely: no battery condition for it.
            .setRequiresBatteryNotLow(!sync)
            .setRequiresCharging(!sync && prefs.charging.value)
            .build()
        val now = System.currentTimeMillis()
        val request = if (sync) PeriodicWorkRequestBuilder<Worker>(prefs.syncMinutes.value.toLong().coerceAtLeast(15), TimeUnit.MINUTES).setConstraints(constraints).build()
        else {
            val f = prefs.frequency.value
            val next = nextRun(f, prefs.time.value, prefs.weekday.value, prefs.lastExport.value, now)
            PeriodicWorkRequestBuilder<Worker>(f.hours, TimeUnit.HOURS)
                .setInitialDelay((next - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()
        }
        // Re-created so a new time or frequency takes effect now; the delay keeps the rhythm of the last backup.
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
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
            return AutoBackup.run(app).fold(
                onSuccess = { Result.success() },
                onFailure = { e -> if (e is IOException && runAttemptCount < 3) Result.retry() else Result.failure() },
            )
        }
    }
}
