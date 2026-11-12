package com.nautile.app

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves an anonymised stack trace of an uncaught exception, shown on the next launch. Nothing
 * leaves the device unless the user opens the prefilled GitHub issue and submits it.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "nautile_crash"
    private const val KEY_PROMPT = "prompt"
    const val ISSUES = "https://github.com/MrDDream/Nautile/issues/new"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(context, thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Offer the report after a crash (Settings › Sécurité). */
    fun promptEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PROMPT, true)
    fun setPromptEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PROMPT, on).apply()

    fun pending(context: Context): String? =
        if (!promptEnabled(context)) { clear(context); null } else File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    /** A new GitHub issue with [report] filled in; the user reads it and submits it themself. */
    fun issueUrl(report: String): Uri {
        val first = report.lineSequence().drop(4).firstOrNull { it.isNotBlank() }?.substringBefore(':')?.substringAfterLast('.').orEmpty()
        val body = buildString {
            appendLine("**Rapport de plantage anonymisé / Anonymised crash report**")
            appendLine()
            appendLine("```")
            appendLine(report.take(5500))
            appendLine("```")
            appendLine()
            appendLine("_Que faisiez-vous juste avant ? / What were you doing just before? (optional)_")
        }
        return Uri.parse(ISSUES).buildUpon()
            .appendQueryParameter("title", "Crash: $first".take(120))
            .appendQueryParameter("labels", "crash")
            .appendQueryParameter("body", body)
            .build()
    }

    private fun report(context: Context, thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return buildString {
            appendLine("Nautile $version — ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name.replace(Regex("\\d+"), "#")}")
            appendLine()
            append(anonymize(trace))
        }
    }

    /**
     * Keeps the exception types and the code locations, masks what could name the user or their
     * machines: messages' quoted parts, addresses, user@host, e-mails, paths, secrets.
     */
    internal fun anonymize(trace: String): String = trace.lineSequence().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("at ") || t.startsWith("...")) return@joinToString line
        // « java.net.UnknownHostException: Unable to resolve host "nas.lan" »: keep the type, scrub the message.
        val lead = listOf("Caused by: ", "Suppressed: ").firstOrNull { t.startsWith(it) }?.let { line.indexOf(it) + it.length } ?: 0
        val cut = line.indexOf(": ", lead)
        if (cut < 0) return@joinToString line
        line.substring(0, cut + 2) + scrub(line.substring(cut + 2))
    }

    private fun scrub(message: String): String = message
        .replace(Regex("\"[^\"]*\"|'[^']*'"), "\"…\"")
        .replace(Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)*"), "<user@host>")
        .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b"), "<ip>")
        .replace(Regex("\\b(?:[0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F:]*\\b"), "<ip>")
        .replace(Regex("(?:/[\\w.@-]+){2,}/?"), "<path>")
        .replace(Regex("(?i)\\b(password|passwd|pwd|token|secret|key)\\s*[=:]\\s*\\S+"), "$1=<…>")
        .replace(Regex("\\b[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.(?:lan|local|home|internal|com|net|org|fr|io|dev|eu|de|uk|be|ch|ca)\\b"), "<host>")
}
