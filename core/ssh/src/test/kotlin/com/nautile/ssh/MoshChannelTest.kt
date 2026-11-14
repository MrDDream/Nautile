package com.nautile.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class MoshChannelTest {
    private val key = ByteArray(16) { it.toByte() }
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    private fun server() = DatagramSocket(0, loopback).apply { soTimeout = 100; receiveBufferSize = 4 shl 20 }

    /** Nonces (first 8 bytes) of the packets received until [done] (the client resends forever). */
    private fun drain(server: DatagramSocket, done: () -> Boolean, onPacket: (ByteArray, DatagramPacket) -> Unit = { _, _ -> }): List<Long> {
        val nonces = mutableListOf<Long>()
        val buf = ByteArray(4096)
        while (!done()) {
            val p = DatagramPacket(buf, buf.size)
            try { server.receive(p) } catch (_: SocketTimeoutException) { continue }
            nonces += ByteBuffer.wrap(buf, 0, 8).long
            onPacket(buf.copyOf(p.length), p)
        }
        return nonces
    }

    @Test fun nonceNeverRepeatsUnderConcurrentSends() {
        val server = server()
        val mosh = MoshChannel(loopback, server.localPort, key, 80, 24)
        val nonces = mutableListOf<Long>()
        val stopAt = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        val reader = thread { nonces += drain(server, done = { System.currentTimeMillis() > stopAt.get() }) }
        // Typing from several threads while the tick thread also sends.
        (1..8).map { t -> thread { repeat(150) { mosh.output.write(byteArrayOf(('a' + t).code.toByte())) } } }.forEach { it.join() }
        stopAt.set(System.currentTimeMillis() + 500)
        reader.join()
        mosh.close()
        server.close()
        assertTrue(nonces.size > 100)
        assertEquals(nonces.size, nonces.toSet().size)
        assertTrue(nonces.all { it >= 0 }) // direction bit clear: client to server
    }

    @Test fun acknowledgedEventsAreDropped() {
        val server = server()
        val mosh = MoshChannel(loopback, server.localPort, key, 80, 24)
        repeat(10) { mosh.output.write(byteArrayOf('x'.code.toByte())) }
        assertEquals(11, mosh.pendingEvents) // the initial resize + 10 keystrokes
        var clientAddress: DatagramPacket? = null
        val until = System.currentTimeMillis() + 300
        drain(server, done = { System.currentTimeMillis() > until }) { _, p -> clientAddress = DatagramPacket(ByteArray(0), 0, p.address, p.port) }

        // The server acknowledges state 11 (everything sent so far).
        val inst = MoshChannel.Proto.instruction(oldNum = 0, newNum = 1, ackNum = 11, throwawayNum = 0, diff = ByteArray(0))
        val payload = MoshChannel.zlib(inst)
        val plain = ByteBuffer.allocate(4 + 10 + payload.size).putShort(0).putShort(-1)
            .putLong(0).putShort(0x8000.toShort()).put(payload).array()
        val packet = MoshChannel.Crypto(key).seal(1, plain, toClient = true)
        val from = clientAddress!!
        server.send(DatagramPacket(packet, packet.size, from.address, from.port))

        val deadline = System.currentTimeMillis() + 3_000
        while (mosh.pendingEvents != 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(0, mosh.pendingEvents)
        mosh.close()
        server.close()
    }
}
