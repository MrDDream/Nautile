package com.nautile.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nautile.app.BuildConfig
import com.nautile.app.NautileApp
import com.nautile.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * « Mises à jour »: the latest release on GitHub (MrDDream/Nautile), checked at start once a day at most;
 * the APK of this phone's processor is downloaded, checked against the release's SHA256SUMS.txt (required)
 * and against the installed app's signing key, then handed to Android's installer. The desktop does the same in `update.rs`.
 */
object UpdateChecker {
    const val REPO = "MrDDream/Nautile"
    private const val PREFS = "nautile_updates"
    private val http by lazy { OkHttpClient.Builder().callTimeout(30, TimeUnit.MINUTES).build() }

    data class Asset(val name: String, val url: String, val size: Long)
    data class Release(val tag: String, val notes: String, val prerelease: Boolean, val page: String, val assets: List<Asset>) {
        val version get() = tag.removePrefix("v")
    }

    private val _available = MutableStateFlow<Release?>(null)
    /** A newer release to offer (the pop-up shows it). */
    val available: StateFlow<Release?> = _available.asStateFlow()

    /**
     * The user's answer to « Vérifier automatiquement les mises à jour ? », asked once after unlocking (new
     * installs and those updated from 0.4.0 alike), then changed in Settings › À propos; null before: until
     * then nothing is checked by itself.
     */
    fun consent(context: Context): Boolean? = prefs(context).let { p -> if (p.contains(KEY_CONSENT)) p.getBoolean(KEY_CONSENT, false) else null }
    fun needsConsent(context: Context) = consent(context) == null
    fun enabled(context: Context) = consent(context) == true
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_CONSENT, on).apply()
    /** Replaces the switch « check » of 0.4.0 and before (on by default, never asked): everyone is asked once. */
    private const val KEY_CONSENT = "auto_check_consent"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The check at start: agreed to, and not done in the last 20 hours. */
    fun checkAtStart(app: NautileApp) {
        val p = prefs(app)
        if (!enabled(app) || System.currentTimeMillis() - p.getLong("last", 0) < 20 * 3600_000L) return
        app.appScope.launch {
            val r = latest().getOrNull() ?: return@launch
            p.edit().putLong("last", System.currentTimeMillis()).apply()
            if (!r.prerelease && isNewer(r.version, BuildConfig.VERSION_NAME) && p.getString("skipped", null) != r.version) {
                _available.value = r
            }
        }
    }

    /** « Vérifier maintenant »: the newer release, or null when up to date. */
    suspend fun checkNow(context: Context): Result<Release?> = latest().map { r ->
        prefs(context).edit().putLong("last", System.currentTimeMillis()).apply()
        r.takeIf { !it.prerelease && isNewer(it.version, BuildConfig.VERSION_NAME) }?.also { _available.value = it }
    }

    fun later() { _available.value = null }

    fun skip(context: Context) {
        _available.value?.let { prefs(context).edit().putString("skipped", it.version).apply() }
        _available.value = null
    }

    fun show(r: Release) { _available.value = r }

    suspend fun latest(): Result<Release> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Nautile/${BuildConfig.VERSION_NAME}")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code != 200) throw IOException("GitHub : HTTP ${resp.code}")
                val o = JSONObject(resp.body!!.string())
                val assets = o.optJSONArray("assets")
                Release(
                    tag = o.getString("tag_name"),
                    notes = o.optString("body"),
                    prerelease = o.optBoolean("prerelease"),
                    page = o.optString("html_url"),
                    assets = (0 until (assets?.length() ?: 0)).map { i ->
                        val a = assets!!.getJSONObject(i)
                        Asset(a.getString("name"), a.getString("browser_download_url"), a.optLong("size"))
                    },
                )
            }
        }
    }

    /** Whether [candidate] is newer than [current] (numbers compared one by one, « -beta » ignored). */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toLongOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The APK for this phone's processor (as named by release.yml), else the universal one. */
    fun apkFor(r: Release, abis: List<String> = Build.SUPPORTED_ABIS.toList()): Asset? =
        abis.firstNotNullOfOrNull { abi -> r.assets.firstOrNull { it.name == "Nautile-${r.tag}-$abi.apk" } }
            ?: r.assets.firstOrNull { it.name == "Nautile-${r.tag}.apk" }

    /** Names the release's checksum list may have. */
    private val SUMS_NAMES = listOf("SHA256SUMS.txt", "SHA256SUMS")

    /** The SHA-256 of [file] in a `sha256sum` listing. */
    fun expectedSha256(sums: String, file: String): String? = sums.lineSequence().firstNotNullOfOrNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size == 2 && parts[1].trimStart('*') == file) parts[0].lowercase() else null
    }

    /**
     * The checksum [file] must have; fails closed: no list ([sums] null), or a list that does not name the file,
     * refuses the update.
     */
    fun requireSha256(sums: String?, file: String): String =
        sums?.let { expectedSha256(it, file) }?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: throw IOException(tr("Somme de contrôle introuvable pour %1\$s : mise à jour refusée.", file))

    /** The release's checksum list, null when it has none or it cannot be fetched. */
    private fun fetchSums(r: Release): String? {
        val asset = SUMS_NAMES.firstNotNullOfOrNull { n -> r.assets.firstOrNull { it.name == n } } ?: return null
        return http.newCall(Request.Builder().url(asset.url).build()).execute().use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null || body.contentLength() > MAX_SUMS_BYTES) null
            else body.source().let { src -> src.request(MAX_SUMS_BYTES); src.buffer.readUtf8(minOf(src.buffer.size, MAX_SUMS_BYTES)) }
        }
    }

    private const val MAX_SUMS_BYTES = 1L shl 20

    /**
     * Whether an APK signed by [archiveCurrent] (with the rotation history [archiveHistory]) may replace an app
     * signed by [installed]: the same signers, or a rotated key whose history holds the installed one.
     */
    fun signersMatch(installed: Set<String>, archiveCurrent: Set<String>, archiveHistory: Set<String>): Boolean =
        installed.isNotEmpty() && archiveCurrent.isNotEmpty() &&
            (installed == archiveCurrent || (installed.size == 1 && installed.single() in archiveHistory))

    private class Signers(val current: Set<String>, val history: Set<String>)

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): Signers? {
        info ?: return null
        fun digest(sigs: Array<Signature>?) = sigs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.let { si ->
            val current = digest(si.apkContentsSigners)
            val history = if (si.hasMultipleSigners()) emptySet() else digest(si.signingCertificateHistory)
            if (current.isNotEmpty()) return Signers(current, history)
        }
        return digest(info.signatures).takeIf { it.isNotEmpty() }?.let { Signers(it, emptySet()) }
    }

    /** Refuses an APK that is not this app, or not signed with the installed app's key. */
    private fun checkSigner(context: Context, apk: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION") // GET_SIGNATURES: only below API 28, and as the archive fallback below.
        val legacy = PackageManager.GET_SIGNATURES
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else legacy
        val installed = signers(pm.packageInfo(context.packageName, flags))
        val info = pm.archiveInfo(apk.path, flags)
            // Some versions only fill the archive's signatures with the older flag.
            ?.takeIf { signers(it) != null } ?: pm.archiveInfo(apk.path, legacy)
        val archive = signers(info)
        if (info?.packageName != context.packageName || installed == null || archive == null ||
            !signersMatch(installed.current, archive.current, archive.history)
        ) {
            apk.delete()
            throw IOException(tr("La mise à jour n'est pas signée par la même clé que l'application installée : elle a été supprimée."))
        }
    }

    /** Downloads the APK into the cache, checks it (checksum, then signer), and returns it. */
    suspend fun download(context: Context, r: Release, asset: Asset, progress: (Long, Long) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // Checked before downloading: without a checksum the APK is never installed.
            val expected = requireSha256(fetchSums(r), asset.name)
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, asset.name)
            val digest = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(asset.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: asset.size
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (done - shown > 256 * 1024 || done == total) { shown = done; progress(done, total) }
                        }
                    }
                }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected != got) {
                file.delete()
                throw IOException(tr("Le fichier téléchargé ne correspond pas à la somme de contrôle de la version : il a été supprimé."))
            }
            checkSigner(context, file)
            file
        }
    }

    /**
     * Hands the APK to Android's installer; first asks to allow installing from Nautile when needed
     * (« Installer des applis inconnues »). Returns false when that permission screen was opened instead.
     */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}

private fun PackageManager.packageInfo(name: String, flags: Int): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags.toLong()))
    else @Suppress("DEPRECATION") getPackageInfo(name, flags)

private fun PackageManager.archiveInfo(path: String, flags: Int): PackageInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
    else @Suppress("DEPRECATION") getPackageArchiveInfo(path, flags)
