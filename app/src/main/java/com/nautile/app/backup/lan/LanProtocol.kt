package com.nautile.app.backup.lan

import com.nautile.i18n.tr
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Nautile's device-to-device protocol on the local network, version 2, byte for byte the desktop app's
 * (`nautile_core::lan`, whose documentation details the messages). `lp(x)`: [x] prefixed with its length as a
 * u64 little-endian; ids: the devices' ids.
 *
 * Every connection opens with `NLAN` · 2 · mode (P pair, S sync) · u8 length · initiator id; the responder
 * answers `NLAN` · its version · a [Status] (anything but OK closes the connection).
 *
 * - Pairing, SPAKE2 ([Spake2], RFC 9382) with A = initiator, B = responder: I → R `pA`; R → I `pB` · `cB`;
 *   I checks `cB` before answering `cA`. `i2r ‖ r2i ‖ secret = HKDF(∅, Ke, "nautile-lan-v2 pair", 96)`:
 *   [Paired.secret] is kept for that one device.
 * - Synchronisation, with that secret: I → R X25519 key; R → I its key · `HMAC(kR, th)`; I checks it, then
 *   `HMAC(kI, th)`. `th = SHA-256("nautile-lan-v2 sync" · lp(idI) · lp(idR) · pubI · pubR)`,
 *   `i2r ‖ r2i ‖ kI ‖ kR = HKDF(secret, shared, th, 128)`.
 * - Then length-prefixed AES-256-GCM frames, nonces counting up per direction.
 */
object LanProtocol {
    const val VERSION = 2
    const val MODE_PAIR = 'P'.code
    const val MODE_SYNC = 'S'.code
    private val MAGIC = "NLAN".toByteArray()
    const val MAX_FRAME = 64 * 1024 * 1024
    /** The largest small message (a hello). */
    const val MAX_HELLO = 64 * 1024
    const val MAX_ID = 64
    private val random = SecureRandom()

    /** The responder's answer to an opening. */
    object Status {
        const val OK = 0
        const val VERSION = 1
        const val NO_CODE = 2
        const val UNKNOWN = 3
        const val BUSY = 4
    }

    /** Why the other device refused, or could not be trusted. */
    open class LanException(message: String) : IOException(message)

    /** It does not know the code or the pairing secret. */
    class AuthFailed : LanException(tr("Code ou appairage refusé par l'autre appareil"))
    class PeerTooOld : LanException(tr("Mettez à jour Nautile sur l'autre appareil"))
    class PeerTooNew : LanException(tr("Mettez à jour Nautile sur cet appareil"))
    class NoCode : LanException(tr("Aucun code valide sur l'autre appareil : affichez-en un nouveau"))
    /** It does not know this device: never paired, or forgotten. */
    class Unknown : LanException(tr("L'autre appareil ne reconnaît pas celui-ci : associez-les à nouveau"))
    class Busy : LanException(tr("L'autre appareil est occupé, réessayez dans un instant"))
    /** It hung up before answering: unreachable, overloaded or giving up (an older Nautile is told by its mDNS version). */
    class Unreachable : LanException(tr("L'autre appareil est injoignable ou occupé, réessayez"))

    private fun invalid() = IOException(tr("Message réseau invalide"))

    /** An authenticated, encrypted channel. */
    class Channel internal constructor(input: InputStream, output: OutputStream, private val sendKey: ByteArray, private val receiveKey: ByteArray) {
        private val inp = DataInputStream(input)
        private val out = DataOutputStream(output)
        private var sent = 0L
        private var received = 0L

        fun send(data: ByteArray) {
            if (data.size > MAX_FRAME - 16) throw invalid()
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce(sent++))) }
            val sealed = c.doFinal(data)
            out.write(ByteBuffer.allocate(4 + sealed.size).putInt(sealed.size).put(sealed).array()); out.flush()
        }

        /** The next frame, at most [max] bytes once decrypted. */
        fun receive(max: Int = MAX_FRAME): ByteArray {
            val n = inp.readInt()
            if (n < 16 || n > minOf(max, MAX_FRAME - 16) + 16) throw invalid()
            val sealed = ByteArray(n).also { inp.readFully(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, nonce(received))) }
            return runCatching { c.doFinal(sealed) }.getOrElse { throw invalid() }.also { received++ }
        }

        fun sendText(s: String) = send(s.toByteArray())
        fun receiveText() = receive().decodeToString()
        /** A small text message (a hello). */
        fun receiveShortText() = receive(MAX_HELLO).decodeToString()

        private fun nonce(n: Long) = ByteBuffer.allocate(12).putInt(0).putLong(n).array()
    }

    /** What an incoming connection asks for and who it says it is (authenticated only by what follows). */
    data class Hello(val mode: Int, val id: String)

    /** A finished pairing: the channel for the hellos, and the secret kept for that device. */
    class Paired(val channel: Channel, val secret: ByteArray)

    private fun checkId(id: String) {
        val b = id.toByteArray()
        if (b.isEmpty() || b.size > MAX_ID || id.any { it.isISOControl() }) throw invalid()
    }

    private fun opening(mode: Int, me: String, rest: ByteArray): ByteArray {
        checkId(me)
        val id = me.toByteArray()
        return MAGIC + byteArrayOf(VERSION.toByte(), mode.toByte(), id.size.toByte()) + id + rest
    }

    /** Reads the responder's header; anything but OK is the matching [LanException]. */
    private fun readReply(inp: DataInputStream) {
        val head = ByteArray(6)
        // Hanging up without an answer: an older Nautile does (its mDNS version, checked before connecting, says so),
        // but so does one that is overloaded or stopping.
        try { inp.readFully(head) } catch (e: EOFException) { throw Unreachable() } catch (e: SocketException) { throw Unreachable() }
        if (!head.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException(tr("Ce n'est pas un appareil Nautile"))
        val version = head[4].toInt() and 0xff
        val status = head[5].toInt() and 0xff
        if (status == Status.OK && version == VERSION) return
        throw when {
            status == Status.NO_CODE -> NoCode()
            status == Status.UNKNOWN -> Unknown()
            status == Status.BUSY -> Busy()
            version > VERSION -> PeerTooNew()
            else -> PeerTooOld()
        }
    }

    /** Answers an opening with [status] (anything but OK, then the connection is closed). */
    fun refuse(output: OutputStream, status: Int) {
        output.write(MAGIC + byteArrayOf(VERSION.toByte(), status.toByte())); output.flush()
    }

    private fun acceptHeader() = MAGIC + byteArrayOf(VERSION.toByte(), Status.OK.toByte())

    /** Reads an opening. Another version is answered with [Status.VERSION] and thrown as [PeerTooOld] / [PeerTooNew]. */
    fun readHello(input: InputStream, output: OutputStream): Hello {
        val inp = DataInputStream(input)
        val head = ByteArray(6).also { inp.readFully(it) }
        if (!head.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException(tr("Ce n'est pas un appareil Nautile"))
        val version = head[4].toInt() and 0xff
        if (version != VERSION) {
            runCatching { refuse(output, Status.VERSION) }
            throw if (version < VERSION) PeerTooOld() else PeerTooNew()
        }
        val mode = head[5].toInt() and 0xff
        if (mode != MODE_PAIR && mode != MODE_SYNC) throw invalid()
        val id = ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }
        val text = runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(id)).toString() }.getOrElse { throw invalid() }
        checkId(text)
        return Hello(mode, text)
    }

    // ── Pairing ───────────────────────────────────────────────────────────

    private fun paired(input: InputStream, output: OutputStream, ke: ByteArray, initiator: Boolean): Paired {
        val okm = Spake2.hkdf(ByteArray(0), ke, "nautile-lan-v2 pair".toByteArray(), 96)
        val i2r = okm.copyOfRange(0, 32); val r2i = okm.copyOfRange(32, 64)
        val ch = if (initiator) Channel(input, output, i2r, r2i) else Channel(input, output, r2i, i2r)
        return Paired(ch, okm.copyOfRange(64, 96))
    }

    /** Pairs, as the initiator, with device [peer] which shows [code]. [rng] is only replaced by the compatibility tests. */
    fun pairConnect(input: InputStream, output: OutputStream, me: String, peer: String, code: String, rng: SecureRandom = random): Paired {
        val out = DataOutputStream(output); val inp = DataInputStream(input)
        val x = Spake2.scalar(Spake2.randomScalar(rng))!!
        val w = Spake2.password(code.trim())
        val pA = Spake2.encode(Spake2.message(x, w, Spake2.M))
        out.write(opening(MODE_PAIR, me, pA)); out.flush()
        readReply(inp)
        val pB = ByteArray(Spake2.POINT_LEN).also { inp.readFully(it) }
        val cB = ByteArray(32).also { inp.readFully(it) }
        val k = Spake2.shared(x, w, Spake2.N, Spake2.decode(pB) ?: throw invalid()) ?: throw invalid()
        val tt = Spake2.transcript(me.toByteArray(), peer.toByteArray(), pA, pB, Spake2.encode(k), w)
        val keys = Spake2.Keys(tt)
        // Nothing else leaves before the responder proved it knows the code.
        if (!MessageDigest.isEqual(cB, keys.confirmB(tt))) throw AuthFailed()
        out.write(keys.confirmA(tt)); out.flush()
        return paired(input, output, keys.ke, initiator = true)
    }

    /** Pairs, as the responder showing [code], after [readHello]. */
    fun pairAccept(input: InputStream, output: OutputStream, hello: Hello, me: String, code: String, rng: SecureRandom = random): Paired {
        val out = DataOutputStream(output); val inp = DataInputStream(input)
        val y = Spake2.scalar(Spake2.randomScalar(rng))!!
        val pA = ByteArray(Spake2.POINT_LEN).also { inp.readFully(it) }
        val pAPoint = Spake2.decode(pA) ?: throw invalid()
        val w = Spake2.password(code.trim())
        val pB = Spake2.encode(Spake2.message(y, w, Spake2.N))
        val k = Spake2.shared(y, w, Spake2.M, pAPoint) ?: throw invalid()
        val tt = Spake2.transcript(hello.id.toByteArray(), me.toByteArray(), pA, pB, Spake2.encode(k), w)
        val keys = Spake2.Keys(tt)
        out.write(acceptHeader() + pB + keys.confirmB(tt)); out.flush()
        // A wrong code: the initiator hangs up instead of answering.
        val cA = ByteArray(32)
        try { inp.readFully(cA) } catch (e: IOException) { throw AuthFailed() }
        if (!MessageDigest.isEqual(cA, keys.confirmA(tt))) throw AuthFailed()
        return paired(input, output, keys.ke, initiator = false)
    }

    // ── Synchronisation ───────────────────────────────────────────────────

    private class SyncKeys(val i2r: ByteArray, val r2i: ByteArray, val proofI: ByteArray, val proofR: ByteArray)

    private fun syncKeys(priv: ByteArray, peer: ByteArray, idI: String, idR: String, pubI: ByteArray, pubR: ByteArray, secret: ByteArray): SyncKeys {
        if (secret.isEmpty()) throw AuthFailed()
        val shared = ByteArray(X25519.POINT_SIZE)
        if (!X25519.calculateAgreement(priv, 0, peer, 0, shared, 0)) throw IOException(tr("Clé réseau invalide"))
        val t = ByteArrayOutputStream()
        t.write("nautile-lan-v2 sync".toByteArray())
        Spake2.lp(t, idI.toByteArray()); Spake2.lp(t, idR.toByteArray())
        t.write(pubI); t.write(pubR)
        val th = Spake2.sha256(t.toByteArray())
        val okm = Spake2.hkdf(secret, shared, th, 128)
        return SyncKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64), Spake2.hmac(okm.copyOfRange(64, 96), th), Spake2.hmac(okm.copyOfRange(96, 128), th))
    }

    private fun x25519(rng: SecureRandom): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(X25519.SCALAR_SIZE).also { X25519.generatePrivateKey(rng, it) }
        val pub = ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(priv, 0, it, 0) }
        return priv to pub
    }

    /** Opens a synchronisation, as the initiator, with device [peer] and the secret shared with it. */
    fun syncConnect(input: InputStream, output: OutputStream, me: String, peer: String, secret: ByteArray, rng: SecureRandom = random): Channel {
        val out = DataOutputStream(output); val inp = DataInputStream(input)
        val (priv, pubI) = x25519(rng)
        out.write(opening(MODE_SYNC, me, pubI)); out.flush()
        readReply(inp)
        val pubR = ByteArray(32).also { inp.readFully(it) }
        val proof = ByteArray(32).also { inp.readFully(it) }
        val k = syncKeys(priv, pubR, me, peer, pubI, pubR, secret)
        if (!MessageDigest.isEqual(proof, k.proofR)) throw AuthFailed()
        out.write(k.proofI); out.flush()
        return Channel(input, output, k.i2r, k.r2i)
    }

    /** Accepts a synchronisation, after [readHello], with the secret of the device it named. */
    fun syncAccept(input: InputStream, output: OutputStream, hello: Hello, me: String, secret: ByteArray, rng: SecureRandom = random): Channel {
        val out = DataOutputStream(output); val inp = DataInputStream(input)
        val pubI = ByteArray(32).also { inp.readFully(it) }
        val (priv, pubR) = x25519(rng)
        val k = syncKeys(priv, pubI, hello.id, me, pubI, pubR, secret)
        out.write(acceptHeader() + pubR + k.proofR); out.flush()
        val proof = ByteArray(32)
        try { inp.readFully(proof) } catch (e: IOException) { throw AuthFailed() }
        if (!MessageDigest.isEqual(proof, k.proofI)) throw AuthFailed()
        return Channel(input, output, k.r2i, k.i2r)
    }

    /** A 6-digit pairing code. */
    fun newCode(): String = (random.nextInt(900_000) + 100_000).toString()
}
