package com.nautile.app.backup.lan

import org.junit.Assert.assertEquals
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
 * Real handshakes of the Wi-Fi protocol with fixed random values, which the desktop app (Rust) replays byte for
 * byte (desktop/crates/nautile-core/tests/lan_compat.rs). Checked against desktop/tests/fixtures/lan-*.json;
 * rewritten with `./gradlew :app:testDebugUnitTest --tests '*LanFixturesTest*' -PnautileFixtures`.
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

    private val idI = "11111111-1111-4111-8111-111111111111"
    private val idR = "22222222-2222-4222-8222-222222222222"

    /** One handshake then a few messages; the document describing it. */
    private fun record(mode: Int, code: String, secret: ByteArray): String {
        val randI = ByteArray(32) { (it + 1).toByte() }
        val randR = ByteArray(32) { (0x80 + it).toByte() }
        val i2rWire = ByteArrayOutputStream()
        val r2iWire = ByteArrayOutputStream()
        val toR = PipedOutputStream(); val rIn: InputStream = PipedInputStream(toR, 1 shl 20)
        val toI = PipedOutputStream(); val iIn: InputStream = PipedInputStream(toI, 1 shl 20)
        val fromI = "{\"id\":\"$idI\",\"name\":\"Pixel 9\",\"platform\":\"android\"}"
        val fromR = "{\"id\":\"$idR\",\"name\":\"PC bureau\",\"note\":\"é ✓\"}"
        var receivedByR = ""
        var secretR = ByteArray(0)
        val responder = thread {
            val out = Tee(toI, r2iWire)
            val hello = LanProtocol.readHello(rIn, out)
            check(hello == LanProtocol.Hello(mode, idI))
            val ch = if (mode == LanProtocol.MODE_PAIR) {
                LanProtocol.pairAccept(rIn, out, hello, idR, code, Fixed(randR)).also { secretR = it.secret }.channel
            } else LanProtocol.syncAccept(rIn, out, hello, idR, secret, Fixed(randR))
            receivedByR = ch.receiveText()
            ch.sendText(fromR)
            ch.sendText("")
        }
        val out = Tee(toR, i2rWire)
        var secretI = ByteArray(0)
        val ch = if (mode == LanProtocol.MODE_PAIR) {
            LanProtocol.pairConnect(iIn, out, idI, idR, code, Fixed(randI)).also { secretI = it.secret }.channel
        } else LanProtocol.syncConnect(iIn, out, idI, idR, secret, Fixed(randI))
        ch.sendText(fromI)
        val receivedByI = ch.receiveText()
        check(ch.receiveText().isEmpty())
        responder.join()
        check(receivedByR == fromI && receivedByI == fromR && secretI.contentEquals(secretR))

        val fields = listOf(
            "mode" to q(String(Character.toChars(mode))),
            "initiator_id" to q(idI),
            "responder_id" to q(idR),
            "code" to q(code),
            "secret_hex" to q(hex(if (mode == LanProtocol.MODE_PAIR) secretI else secret)),
            "initiator_random_hex" to q(hex(randI)),
            "responder_random_hex" to q(hex(randR)),
            "initiator_to_responder_hex" to q(hex(i2rWire.toByteArray())),
            "responder_to_initiator_hex" to q(hex(r2iWire.toByteArray())),
            "initiator_sends" to "[" + q(fromI) + "]",
            "responder_sends" to "[" + q(fromR) + ", " + q("") + "]",
        )
        return fields.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "  " + q(k) + ": " + v }
    }

    private fun compare(name: String, doc: String) {
        if (dir.isNotEmpty()) {
            File(dir).mkdirs()
            File(dir, "lan-$name.json").writeText(doc)
        } else {
            val file = File(System.getProperty("user.dir"), "../desktop/tests/fixtures/lan-$name.json")
            assertEquals("lan-$name.json, the desktop's replay", file.readText(), doc)
        }
    }

    @Test fun pairingMatchesTheDesktopsRecording() = compare("pair", record(LanProtocol.MODE_PAIR, "482916", ByteArray(0)))

    @Test fun syncMatchesTheDesktopsRecording() = compare("sync", record(LanProtocol.MODE_SYNC, "", ByteArray(32) { (0x40 + it).toByte() }))
}
