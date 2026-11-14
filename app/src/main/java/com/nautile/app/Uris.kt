package com.nautile.app

import android.content.ContentResolver
import android.net.Uri

/** Whole content of [uri] as UTF-8 text; null when the provider gives no stream. Blocking. */
fun ContentResolver.readText(uri: Uri): String? = openInputStream(uri)?.use { it.readBytes().decodeToString() }

/**
 * Content of [uri] as UTF-8 text, reading at most [limit] bytes; null when the provider gives no stream.
 * @throws IllegalArgumentException when the file is larger than [limit]. Blocking.
 */
fun ContentResolver.readText(uri: Uri, limit: Int): String? = openInputStream(uri)?.use {
    val bytes = it.readNBytesCompat(limit + 1)
    require(bytes.size <= limit) { "file larger than $limit bytes" }
    bytes.decodeToString()
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
