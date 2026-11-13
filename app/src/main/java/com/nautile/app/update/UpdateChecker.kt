package com.nautile.app.update

import android.content.Context
import android.content.Intent
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
 * the APK of this phone's processor is downloaded, checked against the release's SHA256SUMS.txt and handed
 * to Android's installer. The desktop does the same in `update.rs`.
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

    fun enabled(context: Context) = prefs(context).getBoolean("check", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("check", on).apply()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The check at start: on, and not done in the last 20 hours. */
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

    /** The SHA-256 of [file] in a `sha256sum` listing. */
    fun expectedSha256(sums: String, file: String): String? = sums.lineSequence().firstNotNullOfOrNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size == 2 && parts[1].trimStart('*') == file) parts[0].lowercase() else null
    }

    /** Downloads the APK into the cache, checks it, and returns it. */
    suspend fun download(context: Context, r: Release, asset: Asset, progress: (Long, Long) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val expected = r.assets.firstOrNull { it.name == "SHA256SUMS.txt" }?.let { sums ->
                http.newCall(Request.Builder().url(sums.url).build()).execute().use { expectedSha256(it.body!!.string(), asset.name) }
            }
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
            if (expected != null && expected != got) {
                file.delete()
                throw IOException(tr("Le fichier téléchargé ne correspond pas à la somme de contrôle de la version : il a été supprimé."))
            }
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
