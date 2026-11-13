package com.nautile.app.backup.lan

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Records real handshakes of the Wi-Fi protocol with fixed keys, for the desktop app (Rust) to replay:
 * `./gradlew :app:testDebugUnitTest --tests '*LanFixturesTest*' -PnautileFixtures`.
 */
class LanFixturesTest {
    private val dir = System.getProperty("nautile.fixtures").orEmpty()

    /** Hands out fixed bytes instead of random ones. */
    private class Fixed(private val bytes: ByteArray) : SecureRandom() {
        override fun nextBytes(out: ByteArray) { bytes.copyInto(out, 0, 0, out.size) }
    }

    /** Copies what passes through, to record the wire. */
    private class Tee(private val inner: OutputStream, private val copy: ByteArrayOutputStream) : OutputStream() {
        override fun write(b: Int) { inner.write(b); copy.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); copy.write(b, off, len) }
        override fun flush() = inner.flush()
    }

    /** A JSON string (only quotes and backslashes occur here). */
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun record(name: String, mode: Int, auth: ByteArray) {
        val privI = ByteArray(32) { (it + 1).toByte() }
        val privR = ByteArray(32) { (0x80 + it).toByte() }
        val i2rWire = ByteArrayOutputStream()
        val r2iWire = ByteArrayOutputStream()
        val toR = PipedOutputStream(); val rIn: InputStream = PipedInputStream(toR, 1 shl 20)
        val toI = PipedOutputStream(); val iIn: InputStream = PipedInputStream(toI, 1 shl 20)
        val fromI = "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"name\":\"Pixel 9\"}"
        val fromR = "{\"id\":\"22222222-2222-4222-8222-222222222222\",\"name\":\"PC bureau\",\"note\":\"é ✓\"}"
        var receivedByR = ""
        val responder = thread {
            val out = Tee(toI, r2iWire)
            check(LanProtocol.readMode(rIn) == mode)
            val ch = LanProtocol.accept(rIn, out, mode, auth, Fixed(privR))
            receivedByR = ch.receiveText()
            ch.sendText(fromR)
            ch.sendText("")
        }
        val ch = LanProtocol.connect(iIn, Tee(toR, i2rWire), mode, auth, Fixed(privI))
        ch.sendText(fromI)
        val receivedByI = ch.receiveText()
        check(ch.receiveText().isEmpty())
        responder.join()
        check(receivedByR == fromI && receivedByI == fromR)

        val fields = listOf(
            "mode" to q(String(Character.toChars(mode))),
            "auth_hex" to q(hex(auth)),
            "initiator_private_hex" to q(hex(privI)),
            "responder_private_hex" to q(hex(privR)),
            "initiator_to_responder_hex" to q(hex(i2rWire.toByteArray())),
            "responder_to_initiator_hex" to q(hex(r2iWire.toByteArray())),
            "initiator_sends" to "[" + q(fromI) + "]",
            "responder_sends" to "[" + q(fromR) + ", " + q("") + "]",
        )
        val doc = fields.joinToString(",\n", "{\n", "\n}") { (k, v) -> "  " + q(k) + ": " + v }
        File(dir, "lan-$name.json").writeText(doc + "\n")
    }

    @Test fun writeForDesktop() {
        assumeTrue("only with -PnautileFixtures", dir.isNotEmpty())
        File(dir).mkdirs()
        record("pair", LanProtocol.MODE_PAIR, "482916".toByteArray())
        record("sync", LanProtocol.MODE_SYNC, ByteArray(32) { (0x40 + it).toByte() })
    }
}
