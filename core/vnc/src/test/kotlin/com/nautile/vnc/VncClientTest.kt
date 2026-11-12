package com.nautile.vnc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.zip.Deflater
import kotlin.concurrent.thread

class VncClientTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun vncAuthMatchesOpenSsl() {
        // openssl enc -des-ecb -nopad -K <mirrored "secret12"> on 00112233…eeff
        assertArrayEquals(hex("817402416b165d7ade9c46b5faa42370"), VncClient.vncResponse("secret12", hex("00112233445566778899aabbccddeeff")))
    }

    @Test fun keysyms() {
        assertEquals(0x61, Keysyms.fromVk(0x41))
        assertEquals(0xFF0D, Keysyms.fromVk(0x0D))
        assertEquals(0xFFBE, Keysyms.fromVk(0x70))
        assertEquals('é'.code, Keysyms.of('é'))
        assertEquals(0x01000000 or '€'.code, Keysyms.of('€'))
    }

    private fun DataOutputStream.px(argb: Int) { writeByte(argb and 0xFF); writeByte((argb shr 8) and 0xFF); writeByte((argb shr 16) and 0xFF); writeByte(0) }

    @Test fun handshakeAndEncodings() {
        val toClient = PipedOutputStream(); val clientIn = PipedInputStream(toClient, 1 shl 20)
        val toServer = PipedOutputStream(); val serverIn = PipedInputStream(toServer, 1 shl 20)
        val red = 0xFFFF0000.toInt(); val green = 0xFF00FF00.toInt(); val blue = 0xFF0000FF.toInt(); val white = 0xFFFFFFFF.toInt()
        var auth = ByteArray(0)

        val server = thread {
            val o = DataOutputStream(toClient); val i = DataInputStream(serverIn)
            o.writeBytes("RFB 003.008\n"); o.flush()
            assertEquals("RFB 003.008\n", String(ByteArray(12).also { i.readFully(it) }))
            o.writeByte(1); o.writeByte(2); o.flush()
            assertEquals(2, i.readUnsignedByte())
            o.write(hex("00112233445566778899aabbccddeeff")); o.flush()
            auth = ByteArray(16).also { i.readFully(it) }
            o.writeInt(0); o.flush()
            assertEquals(1, i.readUnsignedByte()) // shared
            o.writeShort(32); o.writeShort(16); o.write(ByteArray(16)); o.writeInt(4); o.writeBytes("test"); o.flush()
            i.skipBytes(20) // SetPixelFormat
            i.skipBytes(4 + 7 * 4) // SetEncodings
            i.skipBytes(10) // first update request

            o.writeByte(0); o.writeByte(0); o.writeShort(4)
            // Raw 2×1 at (0,0)
            o.writeShort(0); o.writeShort(0); o.writeShort(2); o.writeShort(1); o.writeInt(0); o.px(red); o.px(green)
            // Hextile 16×16 at (16,0): background blue, one white 2×2 subrect at (1,1)
            o.writeShort(16); o.writeShort(0); o.writeShort(16); o.writeShort(16); o.writeInt(5)
            o.writeByte(2 or 4 or 8); o.px(blue); o.px(white); o.writeByte(1); o.writeByte((1 shl 4) or 1); o.writeByte((1 shl 4) or 1)
            // ZRLE 4×2 at (0,8): palette RLE red×5 then green×3
            val z = ByteArrayOutputStream().apply {
                write(130); write(0); write(0); write(0xFF); write(0); write(0xFF); write(0) // palette: red, green (B,G,R)
                write(128 or 0); write(3) // red, run 1+3 = 4… then one more
                write(0) // red ×1
                write(128 or 1); write(2) // green ×3
            }.toByteArray()
            val d = Deflater().apply { setInput(z); }
            val buf = ByteArray(256); val n = d.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
            o.writeShort(0); o.writeShort(8); o.writeShort(4); o.writeShort(2); o.writeInt(16); o.writeInt(n); o.write(buf, 0, n)
            // CopyRect: (0,0) 2×1 copied to (4,4)
            o.writeShort(4); o.writeShort(4); o.writeShort(2); o.writeShort(1); o.writeInt(1); o.writeShort(0); o.writeShort(0)
            o.flush()
            i.skipBytes(10) // incremental request
            o.close()
        }

        var updates = 0
        val client = VncClient(clientIn, toServer, object : VncClient.Listener {
            override fun resized(width: Int, height: Int) {}
            override fun updated(x: Int, y: Int, width: Int, height: Int) { updates++ }
        })
        client.connect { "secret12" }
        assertEquals("test", client.name)
        assertEquals(32, client.width)
        runCatching { client.run() }
        server.join()

        assertArrayEquals(hex("817402416b165d7ade9c46b5faa42370"), auth)
        val fb = client.framebuffer; fun at(x: Int, y: Int) = fb[y * 32 + x]
        assertEquals(red, at(0, 0)); assertEquals(green, at(1, 0))
        assertEquals(blue, at(16, 0)); assertEquals(white, at(17, 1)); assertEquals(white, at(18, 2)); assertEquals(blue, at(19, 3))
        assertEquals(red, at(0, 8)); assertEquals(red, at(0, 9)); assertEquals(green, at(1, 9)); assertEquals(green, at(3, 9))
        assertEquals(red, at(4, 4)); assertEquals(green, at(5, 4))
        assertTrue(updates >= 4)
    }
}
