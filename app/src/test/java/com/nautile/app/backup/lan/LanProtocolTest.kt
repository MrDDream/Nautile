package com.nautile.app.backup.lan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** The protocol alone, both sides on this machine (the desktop's lan_sync tests cover the same cases). */
class LanProtocolTest {
    private val idI = "11111111-1111-4111-8111-111111111111"
    private val idR = "22222222-2222-4222-8222-222222222222"

    /** Runs [responder] on one accepted connection and [initiator] against it; both results. */
    private fun <A, B> run(responder: (Socket) -> A, initiator: (Socket) -> B): Pair<Result<B>, Result<A>> {
        val server = ServerSocket(0)
        var r: Result<A> = Result.failure(IllegalStateException())
        val t = thread { r = runCatching { server.accept().use { s -> s.soTimeout = 5_000; responder(s) } } }
        val i = runCatching { Socket("127.0.0.1", server.localPort).use { s -> s.soTimeout = 5_000; initiator(s) } }
        t.join(); server.close()
        return i to r
    }

    private fun pair(serverCode: String, clientCode: String) = run(
        { s ->
            val hello = LanProtocol.readHello(s.getInputStream(), s.getOutputStream())
            assertEquals(LanProtocol.Hello(LanProtocol.MODE_PAIR, idI), hello)
            val p = LanProtocol.pairAccept(s.getInputStream(), s.getOutputStream(), hello, idR, serverCode)
            val got = p.channel.receiveShortText()
            p.channel.sendText("pong:$got")
            p.secret
        },
        { s ->
            val p = LanProtocol.pairConnect(s.getInputStream(), s.getOutputStream(), idI, idR, clientCode)
            p.channel.sendText("ping")
            p.channel.receiveShortText() to p.secret
        },
    )

    @Test fun sameCodePairsWithOneSecret() {
        val (client, server) = pair("123456", "123456")
        val (reply, secret) = client.getOrThrow()
        assertEquals("pong:ping", reply)
        assertArrayEquals(secret, server.getOrThrow())
        // Another pairing, another secret.
        val (again, _) = pair("123456", "123456")
        assertTrue(!again.getOrThrow().second.contentEquals(secret))
    }

    @Test fun wrongCodeIsRefusedOnBothSides() {
        val (client, server) = pair("123456", "654321")
        assertTrue(client.exceptionOrNull() is LanProtocol.AuthFailed)
        assertTrue(server.exceptionOrNull() is LanProtocol.AuthFailed)
    }

    private fun sync(serverSecret: ByteArray?, clientSecret: ByteArray) = run(
        { s ->
            val hello = LanProtocol.readHello(s.getInputStream(), s.getOutputStream())
            if (serverSecret == null) { LanProtocol.refuse(s.getOutputStream(), LanProtocol.Status.UNKNOWN); s.shutdownOutput(); s.getInputStream().readBytes(); "" }
            else {
                val ch = LanProtocol.syncAccept(s.getInputStream(), s.getOutputStream(), hello, idR, serverSecret)
                ch.receiveText().also { ch.sendText("x".repeat(100_000)) } // a large frame goes through too
            }
        },
        { s ->
            val ch = LanProtocol.syncConnect(s.getInputStream(), s.getOutputStream(), idI, idR, clientSecret)
            ch.sendText("data")
            ch.receiveText().length
        },
    )

    @Test fun syncWithTheSecretOfThePairing() {
        val secret = ByteArray(32) { it.toByte() }
        val (client, server) = sync(secret, secret)
        assertEquals(100_000, client.getOrThrow())
        assertEquals("data", server.getOrThrow())
    }

    @Test fun anotherSecretIsRefused() {
        val (client, server) = sync(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        assertTrue(client.exceptionOrNull() is LanProtocol.AuthFailed)
        assertTrue(server.exceptionOrNull() is LanProtocol.AuthFailed)
    }

    @Test fun unknownDeviceIsToldSo() {
        val (client, _) = sync(null, ByteArray(32))
        assertTrue(client.exceptionOrNull().toString(), client.exceptionOrNull() is LanProtocol.Unknown)
    }

    @Test fun noCodeIsToldSo() {
        val (client, _) = run(
            { s -> LanProtocol.readHello(s.getInputStream(), s.getOutputStream()); LanProtocol.refuse(s.getOutputStream(), LanProtocol.Status.NO_CODE); s.shutdownOutput(); s.getInputStream().readBytes() },
            { s -> LanProtocol.pairConnect(s.getInputStream(), s.getOutputStream(), idI, idR, "123456") },
        )
        assertTrue(client.exceptionOrNull() is LanProtocol.NoCode)
    }

    @Test fun olderVersionsAreAnsweredAndReported() {
        // A version 1 initiator: told « version refused », the responder knows it is an old one.
        val (reply, server) = run(
            { s -> LanProtocol.readHello(s.getInputStream(), s.getOutputStream()) },
            { s -> s.getOutputStream().write("NLAN".toByteArray() + byteArrayOf(1, 'S'.code.toByte()) + ByteArray(32)); s.getInputStream().readNBytes(6) },
        )
        assertArrayEquals("NLAN".toByteArray() + byteArrayOf(2, 1), reply.getOrThrow())
        assertTrue(server.exceptionOrNull() is LanProtocol.PeerTooOld)
        // A responder hanging up without an answer is not taken for an old one (its mDNS version tells that).
        val (client, _) = run(
            { s -> s.getInputStream().readNBytes(6) },
            { s -> LanProtocol.syncConnect(s.getInputStream(), s.getOutputStream(), idI, idR, ByteArray(32)) },
        )
        assertTrue(client.exceptionOrNull().toString(), client.exceptionOrNull() is LanProtocol.Unreachable)
    }
}
