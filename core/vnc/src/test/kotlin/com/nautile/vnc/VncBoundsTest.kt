package com.nautile.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** A malicious server must not make the client allocate huge buffers or write outside the screen. */
class VncBoundsTest {
    private val clipboard = mutableListOf<String>()
    private val listener = object : VncClient.Listener {
        override fun resized(width: Int, height: Int) {}
        override fun updated(x: Int, y: Int, width: Int, height: Int) {}
        override fun clipboard(text: String) { clipboard += text }
    }

    /** RFB 3.8, no authentication, a [w]×[h] desktop, then [after]. */
    private fun client(w: Int, h: Int, nameLength: Long = 4, after: DataOutputStream.() -> Unit = {}): VncClient {
        val bytes = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeBytes("RFB 003.008\n")
                writeByte(1); writeByte(VncClient.SEC_NONE)
                writeInt(0) // security result
                writeShort(w); writeShort(h); write(ByteArray(16))
                writeInt(nameLength.toInt()); if (nameLength == 4L) writeBytes("test")
                after()
                flush()
            }
        }.toByteArray()
        return VncClient(ByteArrayInputStream(bytes), ByteArrayOutputStream(), listener)
    }

    private fun failure(block: () -> Unit): Throwable? = runCatching(block).exceptionOrNull()

    @Test fun sizeLimits() {
        assertTrue(VncClient.fits(1920, 1080))
        assertTrue(VncClient.fits(3840, 2160))
        assertFalse(VncClient.fits(0, 10))
        assertFalse(VncClient.fits(65535, 65535)) // would overflow Int in w*h
        assertFalse(VncClient.fits(8192, 8192))
    }

    @Test fun hugeDesktopIsRefused() {
        val e = failure { client(65535, 65535).connect { null } }
        assertTrue(e.toString(), e is VncException)
    }

    @Test fun hugeNameDoesNotAllocate() {
        // Length 0xFFFFFFF0 announced, stream ends: an EOF, not an OutOfMemoryError.
        val e = failure { client(32, 16, nameLength = 0xFFFFFFF0L).connect { null } }
        assertTrue(e.toString(), e is java.io.EOFException)
    }

    private fun DataOutputStream.update(x: Int, y: Int, w: Int, h: Int, encoding: Int, body: DataOutputStream.() -> Unit = {}) {
        writeByte(0); writeByte(0); writeShort(1)
        writeShort(x); writeShort(y); writeShort(w); writeShort(h); writeInt(encoding)
        body()
    }

    @Test fun rectangleOutsideTheScreenIsRefused() {
        val c = client(32, 16) { update(30, 0, 4, 1, VncClient.ENC_RAW) { write(ByteArray(16)) } }
        c.connect { null }
        val e = failure { c.run() }
        assertTrue(e.toString(), e is VncException)
    }

    @Test fun copyRectSourceOutsideTheScreenIsRefused() {
        val c = client(32, 16) { update(0, 0, 8, 8, VncClient.ENC_COPYRECT) { writeShort(30); writeShort(12) } }
        c.connect { null }
        assertTrue(failure { c.run() } is VncException)
    }

    @Test fun hugeZrleLengthIsStreamed() {
        val c = client(32, 16) { update(0, 0, 4, 4, VncClient.ENC_ZRLE) { writeInt(-1); write(ByteArray(10)) } }
        c.connect { null }
        val e = failure { c.run() }
        assertFalse(e.toString(), e is OutOfMemoryError)
    }

    @Test fun hugeCursorIsRefused() {
        val c = client(32, 16) { update(0, 0, 60000, 60000, VncClient.ENC_CURSOR) }
        c.connect { null }
        assertTrue(failure { c.run() } is VncException)
    }

    @Test fun longClipboardIsCutAndTheStreamStaysInSync() {
        val text = "a".repeat(VncClient.MAX_CLIPBOARD_BYTES + 10)
        val c = client(32, 16) {
            writeByte(3); write(ByteArray(3)); writeInt(text.length); writeBytes(text)
            writeByte(3); write(ByteArray(3)); writeInt(2); writeBytes("ok")
        }
        c.connect { null }
        runCatching { c.run() }
        assertEquals(2, clipboard.size)
        assertEquals(VncClient.MAX_CLIPBOARD_BYTES, clipboard[0].length)
        assertEquals("ok", clipboard[1])
    }

    /**
     * A 64×(64·[tiles]) ZRLE payload of palette-RLE tiles: [singles] pixels spelt one byte each
     * (colour 0x302010), the rest of each tile as one run (colour 0x112233). The single pixels set
     * the payload length to the byte.
     */
    private fun paletteTiles(tiles: Int, singles: Int) = ByteArrayOutputStream().apply {
        var left = singles
        repeat(tiles) {
            write(130); write(0x10); write(0x20); write(0x30); write(0x33); write(0x22); write(0x11)
            val one = minOf(left, 4095); left -= one
            repeat(one) { write(0) }
            write(0x81)
            var run = 4096 - one - 1
            while (run >= 255) { write(255); run -= 255 }
            write(run)
        }
    }.toByteArray()

    /** A deflate stored block (not final) holding [data]; empty, it is the sync-flush marker. */
    private fun stored(data: ByteArray = ByteArray(0)) = ByteArrayOutputStream().apply {
        write(0); write(data.size and 0xFF); write(data.size shr 8)
        val inv = data.size.inv() and 0xFFFF
        write(inv and 0xFF); write(inv shr 8); write(data)
    }.toByteArray()

    @Test fun zrleTailBeyondTheReadChunksStillReachesTheInflater() {
        // A rectangle whose last compressed bytes (part of the sync-flush marker) fall past the
        // 64 KiB chunk that completes its pixels: the client must still feed them to the shared
        // zlib stream, or the next rectangle fails with "invalid stored block lengths".
        val tiles = 20
        // zlib header (2) + stored block (5 + payload) + marker (5): 65538 bytes, the marker's last
        // two bytes past the first 64 KiB.
        val payloadSize = 65536 + 2 - 2 - 5 - 5
        val payload = (60000..80000).asSequence().map { paletteTiles(tiles, it) }.first { it.size == payloadSize }
        val first = byteArrayOf(0x78, 0x01) + stored(payload) + stored()
        val second = stored(byteArrayOf(1, 0x66, 0x55, 0x44)) + stored() // one solid tile
        assertEquals(65538, first.size)
        val c = client(64, 64 * tiles) {
            writeByte(0); writeByte(0); writeShort(2)
            writeShort(0); writeShort(0); writeShort(64); writeShort(64 * tiles); writeInt(VncClient.ENC_ZRLE)
            writeInt(first.size); write(first)
            writeShort(0); writeShort(0); writeShort(4); writeShort(4); writeInt(VncClient.ENC_ZRLE)
            writeInt(second.size); write(second)
        }
        c.connect { null }
        val e = failure { c.run() }
        assertTrue(e.toString(), e is java.io.EOFException)
        val fb = c.framebuffer
        assertEquals(0xFF445566.toInt(), fb[0])
        assertEquals(0xFF112233.toInt(), fb[fb.size - 1])
    }
}
