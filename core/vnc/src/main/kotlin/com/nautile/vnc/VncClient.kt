package com.nautile.vnc

import com.nautile.i18n.tr
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class VncException(message: String, val authFailed: Boolean = false) : Exception(message)

/**
 * A VNC (RFB 3.3 / 3.7 / 3.8) client: handshake with « None » or VNC password authentication,
 * a 32-bit true-colour framebuffer kept up to date with the Raw, CopyRect, Hextile and ZRLE
 * encodings, desktop resizes and the server's cursor, plus keyboard, pointer and clipboard.
 *
 * [connect] does the handshake; [run] then reads updates until the connection ends (call it on
 * its own thread). Input methods may be called from any thread.
 */
class VncClient(input: InputStream, output: OutputStream, private val listener: Listener) {

    interface Listener {
        /** The framebuffer was (re)created at this size. */
        fun resized(width: Int, height: Int)
        /** This part of [framebuffer] changed. */
        fun updated(x: Int, y: Int, width: Int, height: Int)
        /** The server's cursor shape (ARGB, transparent outside its mask); null hides it. */
        fun cursor(pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int) {}
        fun clipboard(text: String) {}
        fun bell() {}
    }

    private val inp = DataInputStream(input.buffered(64 * 1024))
    private val out = DataOutputStream(output.buffered(4096))
    private val zlib = Inflater()

    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var name = ""; private set

    /** Pixels as 0xAARRGGBB, row by row, [width]×[height]. */
    @Volatile var framebuffer = IntArray(0); private set

    @Volatile private var closed = false

    /** RFB handshake. [password] is asked only when the server wants one. */
    fun connect(password: () -> String?) {
        val version = String(ByteArray(12).also { inp.readFully(it) }, Charsets.US_ASCII)
        val m = Regex("RFB (\\d{3})\\.(\\d{3})").find(version) ?: throw VncException(tr("Ce n'est pas un serveur VNC"))
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toInt()
        // 3.3 and 3.7 as such; 3.8 and anything newer (Apple's 3.889…) as 3.8.
        val v = when {
            major > 3 || minor >= 8 -> 8
            minor >= 7 -> 7
            else -> 3
        }
        write { writeBytes("RFB 003.00$v\n") }

        val type: Int
        if (v == 3) {
            type = inp.readInt()
            if (type == 0) throw VncException(reason())
        } else {
            val n = inp.readUnsignedByte()
            if (n == 0) throw VncException(reason())
            val types = (0 until n).map { inp.readUnsignedByte() }
            type = when {
                SEC_VNC in types -> SEC_VNC
                SEC_NONE in types -> SEC_NONE
                else -> throw VncException(tr("Méthode d'authentification VNC non prise en charge (%1\$s)", types.joinToString()))
            }
            write { writeByte(type) }
        }
        when (type) {
            SEC_NONE -> if (v == 8) result(v)
            SEC_VNC -> {
                val challenge = ByteArray(16).also { inp.readFully(it) }
                val pw = password() ?: throw VncException(tr("Mot de passe VNC requis"), authFailed = true)
                write { write(vncResponse(pw, challenge)) }
                result(v)
            }
            else -> throw VncException(tr("Méthode d'authentification VNC non prise en charge (%1\$s)", type))
        }

        write { writeByte(1) } // ClientInit: shared desktop
        val w = inp.readUnsignedShort()
        val h = inp.readUnsignedShort()
        inp.skipBytes(16) // the server's pixel format: ours is set right after
        name = String(ByteArray(inp.readInt()).also { inp.readFully(it) }, Charsets.UTF_8)
        resize(w, h)

        write {
            // SetPixelFormat: 32 bpp, depth 24, little endian, true colour, 0x00RRGGBB.
            writeByte(0); write(ByteArray(3))
            writeByte(32); writeByte(24); writeByte(0); writeByte(1)
            writeShort(255); writeShort(255); writeShort(255)
            writeByte(16); writeByte(8); writeByte(0); write(ByteArray(3))
            // SetEncodings
            writeByte(2); writeByte(0); writeShort(ENCODINGS.size)
            ENCODINGS.forEach { writeInt(it) }
        }
        requestUpdate(incremental = false)
    }

    private fun result(version: Int) {
        if (inp.readInt() == 0) return
        val why = if (version == 8) runCatching { reason() }.getOrNull() else null
        throw VncException(why?.takeIf { it.isNotBlank() }?.let { tr("Authentification VNC refusée : %1\$s", it) } ?: tr("Mot de passe VNC refusé"), authFailed = true)
    }

    private fun reason(): String = String(ByteArray(inp.readInt().coerceIn(0, 4096)).also { inp.readFully(it) }, Charsets.UTF_8)

    /** Reads the server's messages until the connection closes. */
    fun run() {
        try {
            while (!closed) {
                when (val type = inp.readUnsignedByte()) {
                    0 -> { update(); requestUpdate(incremental = true) }
                    1 -> { inp.skipBytes(3); val n = inp.readUnsignedShort(); inp.skipBytes(n * 6) } // colour map: unused in true colour
                    2 -> listener.bell()
                    3 -> {
                        inp.skipBytes(3)
                        val n = inp.readInt().coerceIn(0, 1 shl 20)
                        listener.clipboard(String(ByteArray(n).also { inp.readFully(it) }, Charsets.ISO_8859_1))
                    }
                    else -> throw VncException(tr("Message VNC inconnu (%1\$s)", type))
                }
            }
        } catch (e: EOFException) {
            if (!closed) throw e
        }
    }

    fun close() {
        closed = true
        runCatching { inp.close() }
        runCatching { out.close() }
        zlib.end()
    }

    // ── Input ──────────────────────────────────────────────────────────────

    /** [buttons]: bit 0 left, 1 middle, 2 right, 3 wheel up, 4 wheel down. */
    fun pointer(buttons: Int, x: Int, y: Int) = write {
        writeByte(5); writeByte(buttons)
        writeShort(x.coerceIn(0, (width - 1).coerceAtLeast(0))); writeShort(y.coerceIn(0, (height - 1).coerceAtLeast(0)))
    }

    /** An X11 keysym, see [Keysyms]. */
    fun key(keysym: Int, down: Boolean) = write { writeByte(4); writeByte(if (down) 1 else 0); writeShort(0); writeInt(keysym) }

    /** Types [text], one keysym per character. */
    fun type(text: String) = text.forEach { ch ->
        val k = Keysyms.of(ch)
        key(k, true); key(k, false)
    }

    /** Sends the clipboard (VNC carries Latin-1 only). */
    fun clipboard(text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        write { writeByte(6); write(ByteArray(3)); writeInt(bytes.size); write(bytes) }
    }

    private fun requestUpdate(incremental: Boolean) = write {
        writeByte(3); writeByte(if (incremental) 1 else 0)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
    }

    private inline fun write(block: DataOutputStream.() -> Unit) {
        if (closed) return
        synchronized(out) { out.block(); out.flush() }
    }

    // ── Updates ────────────────────────────────────────────────────────────

    private fun resize(w: Int, h: Int) {
        width = w; height = h
        framebuffer = IntArray(w * h)
        listener.resized(w, h)
    }

    private fun update() {
        inp.skipBytes(1)
        val n = inp.readUnsignedShort()
        repeat(n) {
            val x = inp.readUnsignedShort(); val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort(); val h = inp.readUnsignedShort()
            when (val enc = inp.readInt()) {
                ENC_RAW -> { raw(x, y, w, h); listener.updated(x, y, w, h) }
                ENC_COPYRECT -> { copyRect(x, y, w, h); listener.updated(x, y, w, h) }
                ENC_HEXTILE -> { hextile(x, y, w, h); listener.updated(x, y, w, h) }
                ENC_ZRLE -> { zrle(x, y, w, h); listener.updated(x, y, w, h) }
                ENC_DESKTOP_SIZE -> resize(w, h)
                ENC_CURSOR -> cursor(x, y, w, h)
                ENC_LAST_RECT -> return
                else -> throw VncException(tr("Encodage VNC inattendu (%1\$s)", enc))
            }
        }
    }

    private fun pixel(b: ByteArray, at: Int): Int =
        0xFF000000.toInt() or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at].toInt() and 0xFF)

    private fun readPixel(): Int {
        val b0 = inp.readUnsignedByte(); val b1 = inp.readUnsignedByte(); val b2 = inp.readUnsignedByte(); inp.readUnsignedByte()
        return 0xFF000000.toInt() or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun fill(x: Int, y: Int, w: Int, h: Int, color: Int) {
        val fb = framebuffer; val stride = width
        for (row in y until (y + h).coerceAtMost(height)) {
            val start = row * stride + x
            java.util.Arrays.fill(fb, start, start + w.coerceAtMost(stride - x), color)
        }
    }

    private fun raw(x: Int, y: Int, w: Int, h: Int) {
        val line = ByteArray(w * 4)
        val fb = framebuffer
        for (row in 0 until h) {
            inp.readFully(line)
            val base = (y + row) * width + x
            for (col in 0 until w) fb[base + col] = pixel(line, col * 4)
        }
    }

    private fun copyRect(x: Int, y: Int, w: Int, h: Int) {
        val sx = inp.readUnsignedShort(); val sy = inp.readUnsignedShort()
        val fb = framebuffer; val stride = width
        // Rows in the order that never reads a row already overwritten.
        val rows = if (sy < y) (h - 1 downTo 0) else (0 until h)
        for (r in rows) System.arraycopy(fb, (sy + r) * stride + sx, fb, (y + r) * stride + x, w)
    }

    private fun hextile(x: Int, y: Int, w: Int, h: Int) {
        var bg = 0xFF000000.toInt(); var fg = 0xFFFFFFFF.toInt()
        var ty = y
        while (ty < y + h) {
            val th = minOf(16, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tw = minOf(16, x + w - tx)
                val sub = inp.readUnsignedByte()
                if (sub and 1 != 0) raw(tx, ty, tw, th)
                else {
                    if (sub and 2 != 0) bg = readPixel()
                    fill(tx, ty, tw, th, bg)
                    if (sub and 4 != 0) fg = readPixel()
                    if (sub and 8 != 0) {
                        val n = inp.readUnsignedByte()
                        repeat(n) {
                            val color = if (sub and 16 != 0) readPixel() else fg
                            val xy = inp.readUnsignedByte(); val wh = inp.readUnsignedByte()
                            fill(tx + (xy shr 4), ty + (xy and 15), (wh shr 4) + 1, (wh and 15) + 1, color)
                        }
                    }
                }
                tx += 16
            }
            ty += 16
        }
    }

    // ZRLE: zlib stream shared by all rectangles, 64×64 tiles, 3-byte pixels.
    private var zbuf = ByteArray(0)
    private var zpos = 0
    private var zlen = 0

    private fun zByte(): Int {
        if (zpos == zlen) {
            if (zbuf.isEmpty()) zbuf = ByteArray(64 * 1024)
            zlen = zlib.inflate(zbuf); zpos = 0
            if (zlen == 0) throw VncException(tr("Flux ZRLE incomplet"))
        }
        return zbuf[zpos++].toInt() and 0xFF
    }

    private fun zPixel(): Int {
        val b0 = zByte(); val b1 = zByte(); val b2 = zByte()
        return 0xFF000000.toInt() or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun zRun(): Int {
        var n = 1
        while (true) { val b = zByte(); n += b; if (b != 255) return n }
    }

    private fun zrle(x: Int, y: Int, w: Int, h: Int) {
        val compressed = ByteArray(inp.readInt()).also { inp.readFully(it) }
        zlib.setInput(compressed)
        zpos = 0; zlen = 0
        val fb = framebuffer; val stride = width
        var ty = y
        while (ty < y + h) {
            val th = minOf(64, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tw = minOf(64, x + w - tx)
                val sub = zByte()
                when {
                    sub == 0 -> for (r in 0 until th) for (c in 0 until tw) fb[(ty + r) * stride + tx + c] = zPixel()
                    sub == 1 -> fill(tx, ty, tw, th, zPixel())
                    sub in 2..16 -> {
                        val palette = IntArray(sub) { zPixel() }
                        val bits = when { sub == 2 -> 1; sub <= 4 -> 2; else -> 4 }
                        val mask = (1 shl bits) - 1
                        for (r in 0 until th) {
                            var byte = 0; var left = 0
                            for (c in 0 until tw) {
                                if (left == 0) { byte = zByte(); left = 8 }
                                left -= bits
                                fb[(ty + r) * stride + tx + c] = palette[(byte shr left) and mask]
                            }
                        }
                    }
                    sub == 128 -> {
                        var i = 0; val n = tw * th
                        while (i < n) {
                            val color = zPixel(); val run = zRun()
                            repeat(run) { if (i < n) { fb[(ty + i / tw) * stride + tx + i % tw] = color; i++ } }
                        }
                    }
                    sub >= 130 -> {
                        val palette = IntArray(sub - 128) { zPixel() }
                        var i = 0; val n = tw * th
                        while (i < n) {
                            val idx = zByte()
                            val run = if (idx and 128 != 0) zRun() else 1
                            val color = palette[idx and 127]
                            repeat(run) { if (i < n) { fb[(ty + i / tw) * stride + tx + i % tw] = color; i++ } }
                        }
                    }
                    else -> throw VncException(tr("Tuile ZRLE invalide (%1\$s)", sub))
                }
                tx += 64
            }
            ty += 64
        }
    }

    private fun cursor(hotX: Int, hotY: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) { listener.cursor(null, 0, 0, 0, 0); return }
        val px = ByteArray(w * h * 4).also { inp.readFully(it) }
        val rowBytes = (w + 7) / 8
        val mask = ByteArray(rowBytes * h).also { inp.readFully(it) }
        val pixels = IntArray(w * h) { i ->
            val r = i / w; val c = i % w
            val visible = (mask[r * rowBytes + c / 8].toInt() shr (7 - c % 8)) and 1 == 1
            if (visible) pixel(px, i * 4) else 0
        }
        listener.cursor(pixels, w, h, hotX, hotY)
    }

    companion object {
        const val SEC_NONE = 1
        const val SEC_VNC = 2
        const val ENC_RAW = 0
        const val ENC_COPYRECT = 1
        const val ENC_HEXTILE = 5
        const val ENC_ZRLE = 16
        const val ENC_CURSOR = -239
        const val ENC_DESKTOP_SIZE = -223
        const val ENC_LAST_RECT = -224
        private val ENCODINGS = intArrayOf(ENC_ZRLE, ENC_HEXTILE, ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE, ENC_CURSOR, ENC_LAST_RECT)

        /** The 16-byte answer to a VNC challenge: DES with the password (8 bytes, bits mirrored) as key. */
        fun vncResponse(password: String, challenge: ByteArray): ByteArray {
            val key = ByteArray(8)
            password.toByteArray(Charsets.ISO_8859_1).take(8).forEachIndexed { i, b -> key[i] = mirror(b) }
            val des = Cipher.getInstance("DES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES")) }
            return des.doFinal(challenge)
        }

        private fun mirror(b: Byte): Byte {
            var v = b.toInt() and 0xFF; var r = 0
            repeat(8) { r = (r shl 1) or (v and 1); v = v shr 1 }
            return r.toByte()
        }
    }
}
