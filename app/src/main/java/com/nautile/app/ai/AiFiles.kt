package com.nautile.app.ai

import com.nautile.i18n.tr
import java.io.InputStream

/** A text file joined to a request (configuration, log, JSON…), cut to a size the models accept. */
data class AiFile(val name: String, val text: String, val size: Long, val cut: Boolean, val tail: Boolean)

object AiFiles {
    /** Characters sent per file: about 16 000 tokens, enough for a config or the end of a log. */
    const val MAX_BYTES = 64 * 1024
    /** Read at most this much: the end of a big log is what matters. */
    private const val READ_LIMIT = 8 * 1024 * 1024

    /**
     * Reads [input] as text. Logs keep their end, other files their beginning, when longer than
     * [MAX_BYTES]. Binary files are refused.
     */
    fun read(name: String, input: InputStream): AiFile {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var keep = ByteArray(0)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (out.size() < READ_LIMIT) out.write(buf, 0, n)
            else { // past the limit only the end is kept, for logs
                keep = (keep + buf.copyOf(n)).let { if (it.size > MAX_BYTES) it.copyOfRange(it.size - MAX_BYTES, it.size) else it }
            }
        }
        val all = out.toByteArray().let { if (keep.isEmpty()) it else it + keep }
        if (all.take(8192).any { it == 0.toByte() }) throw AiException(tr("« %1\$s » semble binaire : seuls les fichiers texte peuvent être joints.", name))
        val tail = isLog(name)
        val cut = all.size > MAX_BYTES
        val slice = when {
            !cut -> all
            tail -> all.copyOfRange(all.size - MAX_BYTES, all.size)
            else -> all.copyOfRange(0, MAX_BYTES)
        }
        // Drop a UTF-8 sequence cut in half at the edge.
        val text = slice.decodeToString().trim('�')
        return AiFile(name, text, total, cut, tail && cut)
    }

    private fun isLog(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".log") || n.contains(".log.") || n.contains("syslog") || n.contains("journal") || n.endsWith(".out") || n.endsWith(".err")
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> tr("%1\$s o", bytes)
        bytes < 1024 * 1024 -> tr("%1\$s Ko", (bytes + 512) / 1024)
        else -> tr("%1\$s Mo", "%.1f".format(java.util.Locale.ROOT, bytes / 1048576.0))
    }
}
