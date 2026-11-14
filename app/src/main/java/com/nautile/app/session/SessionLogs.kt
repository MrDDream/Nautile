package com.nautile.app.session

import android.content.Context
import com.nautile.app.NautileApp
import com.nautile.security.FileCipher
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Session recordings: raw terminal output, one file per session, in the app's private storage,
 * encrypted with the app's [FileCipher]. A log is a header then sealed chunks (u32 length + blob),
 * each appended whole: a session cut short loses at most its last few seconds. Logs written before
 * encryption (plain text) stay readable.
 */
object SessionLogs {
    private val HEADER = "NTLS1".toByteArray()
    private const val CHUNK_BYTES = 64 * 1024
    /** Delay between the first unsealed output and its sealing: what a killed process can lose. */
    private const val CHUNK_MS = 3_000L
    private const val MAX_CHUNK = 16 shl 20

    /** The app's cipher, remembered from the last context seen (list() runs before readable()). */
    @Volatile private var cipher: FileCipher? = null

    private fun cipherOf(context: Context): FileCipher? =
        cipher ?: (context.applicationContext as? NautileApp)?.fileCipher?.also { cipher = it }

    fun dir(context: Context) = File(context.filesDir, "logs").apply { mkdirs() }.also { cipherOf(context) }

    fun create(context: Context, alias: String): OutputStream {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        val safe = alias.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        val c = cipherOf(context) ?: error("file cipher unavailable")
        return SealedLog(File(dir(context), "${stamp}_$safe.log"), c)
    }

    fun list(context: Context): List<File> = dir(context).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Seals idle logs' pending output (one thread for all sessions). */
    private val sealer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "session-log-sealer").apply { isDaemon = true } }
    }

    private val ANSI = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\)|[()][0-9A-Za-z]|[@-Z\\-_])""")

    /** The log as readable text: escape sequences removed, carriage returns resolved. */
    fun readable(file: File, maxBytes: Long = 2L * 1024 * 1024, cipher: FileCipher? = this.cipher): String =
        clean(plainTail(file, maxBytes, cipher).decodeToString())

    /** The last [maxBytes] of the log's plain content. */
    internal fun plainTail(file: File, maxBytes: Long, cipher: FileCipher?): ByteArray {
        val sealed = DataInputStream(file.inputStream()).use { inp ->
            val head = ByteArray(HEADER.size)
            runCatching { inp.readFully(head) }.isSuccess && head.contentEquals(HEADER)
        }
        if (!sealed) {
            // Written before encryption: plain text (or a whole blob sealed by the cipher).
            val bytes = file.inputStream().use { inp ->
                if (file.length() > maxBytes) inp.skip(file.length() - maxBytes)
                inp.readBytes()
            }
            return cipher?.open(bytes) ?: bytes
        }
        val out = ByteArrayOutputStream()
        DataInputStream(file.inputStream().buffered()).use { inp ->
            inp.skipBytes(HEADER.size)
            while (true) {
                val len = try { inp.readInt() } catch (_: EOFException) { break }
                if (len <= 0 || len > MAX_CHUNK) break
                val blob = ByteArray(len)
                try { inp.readFully(blob) } catch (_: EOFException) { break } // last chunk cut off
                val plain = cipher?.open(blob) ?: break
                out.write(plain)
                if (out.size() > maxBytes * 2) {
                    val keep = out.toByteArray().let { it.copyOfRange(it.size - maxBytes.toInt(), it.size) }
                    out.reset(); out.write(keep)
                }
            }
        }
        val all = out.toByteArray()
        return if (all.size > maxBytes) all.copyOfRange(all.size - maxBytes.toInt(), all.size) else all
    }

    fun clean(raw: String): String = ANSI.replace(raw, "")
        .replace("\r\n", "\n")
        .lines().joinToString("\n") { line -> line.substringAfterLast('\r') }
        .filter { it == '\n' || it == '\t' || it >= ' ' }

    /**
     * Buffers output and appends it as sealed chunks: every 64 KiB, [delayMs] after the first
     * output not sealed yet (so an idle session is on disk within seconds), and at close.
     */
    internal class SealedLog(file: File, private val cipher: FileCipher, private val delayMs: Long = CHUNK_MS) : OutputStream() {
        private val out = FileOutputStream(file).apply { write(HEADER) }
        private val buffer = ByteArrayOutputStream()
        private var pending: ScheduledFuture<*>? = null
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed || len == 0) return
            buffer.write(b, off, len)
            if (buffer.size() >= CHUNK_BYTES) seal()
            else if (pending == null) pending = sealer.schedule(Runnable { timedSeal() }, delayMs, TimeUnit.MILLISECONDS)
        }

        @Synchronized private fun timedSeal() {
            pending = null
            // A failure has already stopped the recording (see seal); nothing to report here.
            if (!closed) runCatching { seal() }
        }

        @Synchronized override fun flush() { if (!closed) seal() }

        @Synchronized override fun close() {
            if (closed) return
            try { seal() } finally { closed = true; out.close() }
        }

        private fun seal() {
            pending?.cancel(false)
            pending = null
            if (buffer.size() == 0) return
            val data = buffer.toByteArray()
            buffer.reset()
            // No key: stop recording rather than write the session in clear.
            val blob = try { cipher.seal(data) } catch (e: Exception) { closed = true; runCatching { out.close() }; throw e }
            // Length and blob in one write: a chunk is on disk whole or (cut off) ignored by the reader.
            val chunk = ByteBuffer.allocate(4 + blob.size).putInt(blob.size).put(blob).array()
            out.write(chunk)
            out.flush()
        }
    }
}
