package com.nautile.app.backup

import android.content.Context
import java.io.File

/**
 * The last backups, kept on the phone as well (already encrypted with the backup password), so an
 * older one can be restored even when the destination keeps a single file.
 */
object BackupVersions {
    /** Copies kept, the current one included. */
    const val KEEP = 5

    data class Version(val file: File, val at: Long, val size: Long)

    private fun dir(context: Context) = File(context.filesDir, "backup-versions").apply { mkdirs() }

    fun add(context: Context, bytes: ByteArray, at: Long = System.currentTimeMillis()) {
        File(dir(context), "$at.nautile").writeBytes(bytes)
        list(context).drop(KEEP).forEach { it.file.delete() }
    }

    /** Keeps [bytes] unless a version younger than [minAgeMs] exists (sync writes often: one a day is enough). */
    fun addIfOlder(context: Context, bytes: ByteArray, minAgeMs: Long, now: Long = System.currentTimeMillis()) {
        if (list(context).firstOrNull()?.let { now - it.at < minAgeMs } == true) return
        add(context, bytes, now)
    }

    /** Newest first. */
    fun list(context: Context): List<Version> =
        dir(context).listFiles { f -> f.name.endsWith(".nautile") }.orEmpty()
            .mapNotNull { f -> f.name.removeSuffix(".nautile").toLongOrNull()?.let { Version(f, it, f.length()) } }
            .sortedByDescending { it.at }
}
