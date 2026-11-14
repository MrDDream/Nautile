package com.nautile.app.backup.lan

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/** The listening side of the Wi-Fi synchronisation, against real initiators on this machine (as the desktop's lan_sync tests). */
class LanServerTest {
    private val idI = "11111111-1111-4111-8111-111111111111"
    private val idR = "22222222-2222-4222-8222-222222222222"

    /** What this device's [LanSync] would keep. */
    private inner class Host : LanServer.Host {
        val secrets = ConcurrentHashMap<String, ByteArray>()
        val paired = CopyOnWriteArrayList<String>()
        val merged = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<String>()
        /** Paired devices whose secret cannot be read for now. */
        val unreadable = CopyOnWriteArrayList<String>()
        override fun hello() = PeerHello(idR, "Pixel", "android")
        override fun secret(id: String) = when {
            id in unreadable -> LanServer.Secret.NotNow
            else -> secrets[id]?.let { LanServer.Secret.Known(it) } ?: LanServer.Secret.Unknown
        }
        override fun paired(them: PeerHello, secret: ByteArray) { secrets[them.id] = secret; paired += them.id }
        // Merges first, then answers with its data merged.
        override fun exchange(channel: LanProtocol.Channel, them: PeerHello, live: () -> Boolean) =
            LanExchange.respond(channel, hello(), merge = { merged += it; true }, snapshot = { "data of R + ${merged.last()}" })
        override fun status(message: String) { statuses += message }
    }

    private val host = Host()
    private var server = LanServer({ idR }, host)
    private var port = server.start()

    @After fun stop() = server.stop()

    private fun <T> client(block: (Socket) -> T): T = Socket("127.0.0.1", port).use { it.soTimeout = 5_000; block(it) }

    /** Pairs as the initiator, the whole way; its secret. */
    private fun pair(code: String, id: String = idI): ByteArray = client { s ->
        val p = LanProtocol.pairConnect(s.getInputStream(), s.getOutputStream(), id, idR, code)
        p.channel.sendText(PeerHello(id, "Bureau", "ubuntu").encode())
        PeerHello.parse(p.channel.receiveShortText(), idR)
        p.secret
    }

    /** Synchronises as the initiator; the data received. */
    private fun sync(secret: ByteArray, id: String = idI): String = client { s ->
        val ch = LanProtocol.syncConnect(s.getInputStream(), s.getOutputStream(), id, idR, secret)
        val (them, data) = LanExchange.initiate(ch, PeerHello(id, "Bureau", "ubuntu"), "data of I", idR)
        assertEquals("Pixel", them.name)
        data
    }

    private fun failure(block: () -> Any): Throwable? = runCatching { block() }.exceptionOrNull()

    private fun wrong(code: String) = if (code == "111111") "222222" else "111111"

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!ok()) { assertTrue(what, System.currentTimeMillis() < end); Thread.sleep(20) }
    }

    @Test fun pairsOnceThenSynchronises() {
        val code = server.showCode()
        val secret = pair(code)
        assertArrayEquals(secret, host.secrets[idI])
        assertEquals(listOf(idI), host.paired)
        assertNull(server.code.value)
        // The code served once.
        assertTrue(failure { pair(code) } is LanProtocol.NoCode)
        // The answer is taken after the merge.
        assertEquals("data of R + data of I", sync(secret))
        waitFor("merged") { host.merged == listOf("data of I") }
    }

    @Test fun aPairedDeviceWhoseSecretCannotBeReadNowIsToldBusy() {
        val secret = pair(server.showCode())
        host.unreadable += idI
        // Not « unknown »: the other device would ask to pair again.
        assertTrue(failure { sync(secret) } is LanProtocol.Busy)
        host.unreadable.clear()
        assertEquals("data of R + data of I", sync(secret))
        assertTrue(failure { sync(secret, "zzzz") } is LanProtocol.Unknown)
    }

    @Test fun threeTriesThenTheCodeIsDropped() {
        val code = server.showCode()
        repeat(LanServer.MAX_TRIES) { assertTrue(failure { pair(wrong(code)) } is LanProtocol.AuthFailed) }
        waitFor("dropped") { server.code.value == null }
        assertTrue(host.statuses.toString(), host.statuses.any { "Trop d'essais" in it })
        // Even the right code: a new one is needed.
        assertTrue(failure { pair(code) } is LanProtocol.NoCode)
        assertTrue(host.paired.isEmpty())
    }

    @Test fun threeTriesEvenInParallel() {
        val code = server.showCode()
        val barrier = CyclicBarrier(8)
        val results = CopyOnWriteArrayList<Throwable?>()
        (0 until 8).map { i -> thread { barrier.await(); results += failure { pair(wrong(code), "x$i") } } }.forEach { it.join() }
        assertTrue(results.toString(), results.count { it is LanProtocol.AuthFailed } <= LanServer.MAX_TRIES)
        assertTrue(failure { pair(code) } is LanProtocol.NoCode)
    }

    @Test fun connectionsAreCappedAndTheNextOnesToldBusy() {
        val idle = (0 until LanServer.MAX_CONNECTIONS).map { Socket("127.0.0.1", port) }
        Thread.sleep(200)
        assertTrue(failure { sync(ByteArray(32)) } is LanProtocol.Busy)
        val reply = client { it.getInputStream().readBytes() }
        assertArrayEquals("NLAN".toByteArray() + byteArrayOf(2, LanProtocol.Status.BUSY.toByte()), reply)
        idle.forEach { it.close() }
        // Slots free again: a real request is answered.
        waitFor("slots back") { failure { sync(ByteArray(32), "zzzz") } is LanProtocol.Unknown }
    }

    @Test fun aForgottenDeviceIsRefused() {
        val secret = pair(server.showCode())
        assertEquals("data of R + data of I", sync(secret))
        host.secrets.remove(idI)
        assertTrue(failure { sync(secret) } is LanProtocol.Unknown)
        // Someone else claiming to be a paired device, without its secret.
        host.secrets[idI] = secret
        assertTrue(failure { sync(ByteArray(32) { 7 }) } is LanProtocol.AuthFailed)
    }

    @Test fun cancellingOrStoppingDuringTheHandshakePairsNobody() {
        for (stop in listOf(false, true)) {
            val code = server.showCode()
            val failed = client { s ->
                val p = LanProtocol.pairConnect(s.getInputStream(), s.getOutputStream(), idI, idR, code)
                if (stop) server.stop() else server.cancelCode()
                p.channel.sendText(PeerHello(idI, "Bureau", null).encode())
                // No hello back: the initiator keeps nothing either.
                failure { p.channel.receiveShortText() }
            }
            assertTrue(failed.toString(), failed is IOException)
            assertTrue(host.paired.isEmpty() && host.secrets.isEmpty())
            if (stop) {
                assertNull(server.code.value)
                assertTrue(failure { Socket("127.0.0.1", port).close() } is IOException)
            }
        }
    }

    @Test fun aSlowHandshakeIsCutAtItsDeadline() {
        server.stop()
        server = LanServer({ idR }, host, handshakeMs = 1_000)
        port = server.start()
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("NLAN".toByteArray() + byteArrayOf(2, 'P'.code.toByte(), 64))
            // One byte of the id at a time, each well within the per-read wait.
            thread { runCatching { repeat(40) { s.getOutputStream().write('a'.code); Thread.sleep(150) } } }
            val start = System.currentTimeMillis()
            s.soTimeout = 5_000
            val r = runCatching { s.getInputStream().read() }
            assertTrue(r.toString(), r.getOrNull() == -1 || (r.isFailure && r.exceptionOrNull() !is java.net.SocketTimeoutException))
            assertTrue("${System.currentTimeMillis() - start} ms", System.currentTimeMillis() - start < 2_500)
        }
    }
}
