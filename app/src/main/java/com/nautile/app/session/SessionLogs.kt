package com.nautile.app.session

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Session recordings: raw terminal output, one file per session, in the app's private storage. */
object SessionLogs {
    fun dir(context: Context) = File(context.filesDir, "logs").apply { mkdirs() }

    fun create(context: Context, alias: String): OutputStream {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        val safe = alias.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        return BufferedOutputStream(FileOutputStream(File(dir(context), "${stamp}_$safe.log")))
    }

    fun list(context: Context): List<File> = dir(context).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    private val ANSI = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\)|[()][0-9A-Za-z]|[@-Z\\-_])""")

    /** The log as readable text: escape sequences removed, carriage returns resolved. */
    fun readable(file: File, maxBytes: Long = 2L * 1024 * 1024): String {
        val bytes = file.inputStream().use { inp ->
            if (file.length() > maxBytes) inp.skip(file.length() - maxBytes)
            inp.readBytes()
        }
        return clean(bytes.decodeToString())
    }

    fun clean(raw: String): String = ANSI.replace(raw, "")
        .replace("\r\n", "\n")
        .lines().joinToString("\n") { line -> line.substringAfterLast('\r') }
        .filter { it == '\n' || it == '\t' || it >= ' ' }
}
