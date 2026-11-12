package com.nautile.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Against a real x11vnc: scripts/vnc-it.sh. */
class VncClientIT {
    private val port = System.getenv("IT_VNC_PORT")?.toIntOrNull() ?: 25900

    @Test fun connectsDrawsAndTypes() {
        assumeTrue(System.getProperty("nautile.vncIT") == "true")
        val socket = Socket("127.0.0.1", port)
        val full = CountDownLatch(1)
        var covered = 0L
        val client = VncClient(socket.getInputStream(), socket.getOutputStream(), object : VncClient.Listener {
            override fun resized(width: Int, height: Int) {}
            override fun updated(x: Int, y: Int, width: Int, height: Int) {
                covered += width.toLong() * height
                if (covered >= 800L * 600) full.countDown()
            }
        })
        client.connect { "secret12" }
        assertEquals(800, client.width); assertEquals(600, client.height)
        val reader = thread { runCatching { client.run() } }
        assertTrue("whole screen received", full.await(20, TimeUnit.SECONDS))
        val fb = client.framebuffer
        assertTrue("xterm's white background", fb.count { it == 0xFFFFFFFF.toInt() } > 10_000)
        // Click in the xterm, type, move: the server must keep the connection.
        client.pointer(1, 100, 100); client.pointer(0, 100, 100)
        client.type("echo nautile\n")
        client.key(Keysyms.fromVk(0x41)!!, true); client.key(Keysyms.fromVk(0x41)!!, false)
        Thread.sleep(1500)
        assertTrue(reader.isAlive)
        client.close(); socket.close()
        reader.join(5000)
    }

    @Test fun wrongPasswordIsRefused() {
        assumeTrue(System.getProperty("nautile.vncIT") == "true")
        Socket("127.0.0.1", port).use { s ->
            val client = VncClient(s.getInputStream(), s.getOutputStream(), object : VncClient.Listener {
                override fun resized(width: Int, height: Int) {}
                override fun updated(x: Int, y: Int, width: Int, height: Int) {}
            })
            val e = runCatching { client.connect { "nope" } }.exceptionOrNull()
            assertTrue("refused: $e", e is VncException && e.authFailed)
        }
    }
}
