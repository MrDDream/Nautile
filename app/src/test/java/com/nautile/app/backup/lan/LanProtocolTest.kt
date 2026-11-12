package com.nautile.app.backup.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LanProtocolTest {
    private fun exchange(serverAuth: ByteArray, clientAuth: ByteArray): Pair<Result<String>, Result<String>> {
        val server = ServerSocket(0)
        var serverResult: Result<String> = Result.failure(IllegalStateException())
        val t = thread {
            serverResult = runCatching {
                server.accept().use { s ->
                    val mode = LanProtocol.readMode(s.getInputStream())
                    assertEquals(LanProtocol.MODE_PAIR, mode)
                    val ch = LanProtocol.accept(s.getInputStream(), s.getOutputStream(), mode, serverAuth)
                    val got = ch.receiveText()
                    ch.sendText("pong:$got")
                    got
                }
            }
        }
        val clientResult = runCatching {
            Socket("127.0.0.1", server.localPort).use { s ->
                val ch = LanProtocol.connect(s.getInputStream(), s.getOutputStream(), LanProtocol.MODE_PAIR, clientAuth)
                ch.sendText("ping")
                ch.sendText("x".repeat(100_000)).let { } // a large frame goes through too
                ch.receiveText()
            }
        }
        t.join(); server.close()
        return clientResult to serverResult
    }

    @Test fun sameCodeConnects() {
        val (client, server) = exchange("123456".toByteArray(), "123456".toByteArray())
        assertEquals("pong:ping", client.getOrThrow())
        assertEquals("ping", server.getOrThrow())
    }

    @Test fun wrongCodeIsRefusedOnBothSides() {
        val (client, server) = exchange("123456".toByteArray(), "654321".toByteArray())
        assertTrue(client.isFailure)
        assertTrue(server.exceptionOrNull() is LanProtocol.AuthFailed)
    }
}
