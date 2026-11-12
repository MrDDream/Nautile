package com.nautile.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class TelnetAndKnockTest {
    @Test fun parsesKnockSequences() {
        assertEquals(
            listOf(PortKnocker.Knock(7000, false), PortKnocker.Knock(8000, true), PortKnocker.Knock(9000, false)),
            PortKnocker.parse("7000, u:8000;t:9000"),
        )
        assertTrue(runCatching { PortKnocker.parse("7000,abc") }.isFailure)
    }

    @Test fun knocksTcpPortsInOrder() {
        val hits = CopyOnWriteArrayList<Int>()
        val servers = listOf(ServerSocket(0, 5, InetAddress.getLoopbackAddress()), ServerSocket(0, 5, InetAddress.getLoopbackAddress()))
        servers.forEach { s -> Thread { runCatching { s.accept().close(); hits += s.localPort } }.apply { isDaemon = true }.start() }
        PortKnocker.knock("127.0.0.1", servers.map { PortKnocker.Knock(it.localPort, false) }, delayMs = 50)
        Thread.sleep(200)
        assertEquals(servers.map { it.localPort }, hits.toList())
        servers.forEach { it.close() }
    }

    @Test fun telnetExchangesTextAndNegotiatesTerminalType() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val received = StringBuilder()
            val t = Thread {
                server.accept().use { s ->
                    val out = s.getOutputStream(); val inp = s.getInputStream()
                    // IAC DO TERMINAL-TYPE, then a banner.
                    out.write(byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 24)); out.write("login: ".toByteArray()); out.flush()
                    val deadline = System.currentTimeMillis() + 3000
                    val buf = ByteArray(256)
                    s.soTimeout = 300
                    while (System.currentTimeMillis() < deadline && !received.contains("admin")) {
                        val n = runCatching { inp.read(buf) }.getOrDefault(0)
                        if (n > 0) received.append(String(buf, 0, n, Charsets.ISO_8859_1))
                    }
                }
            }.apply { start() }
            TelnetChannel.connect("127.0.0.1", server.localPort, 100, 30).use { ch ->
                val banner = ByteArray(7)
                var got = 0
                while (got < 7) got += ch.input.read(banner, got, 7 - got)
                assertEquals("login: ", String(banner))
                ch.output.write("admin\r\n".toByteArray()); ch.output.flush()
                t.join(4000)
            }
            assertTrue("client answered WILL TERMINAL-TYPE", received.contains("ÿû\u0018"))
            assertTrue(received.contains("admin"))
        }
    }
}
