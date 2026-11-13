package com.nautile.app.backup.lan

import com.nautile.i18n.tr
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Nautile's device-to-device protocol on the local network (version 1), simple enough for a
 * desktop client to speak:
 *
 * 1. The initiator sends `NLAN` · version · mode (P pair, S sync) · its X25519 public key; the
 *    responder answers with its public key.
 * 2. Both derive, from the X25519 secret and the transcript, one AES-256-GCM key per direction
 *    and a proof key mixing in the shared secret ([auth]: the 6-digit code when pairing, the
 *    pairing secret afterwards). Each proves it knows it (HMAC of the transcript and its role):
 *    an eavesdropper learns nothing, an intruder gets one guess per connection.
 * 3. Then length-prefixed AES-GCM frames, nonces counting up per direction.
 */
object LanProtocol {
    const val VERSION = 1
    const val MODE_PAIR = 'P'.code
    const val MODE_SYNC = 'S'.code
    private val MAGIC = "NLAN".toByteArray()
    private const val MAX_FRAME = 64 * 1024 * 1024
    private val random = SecureRandom()

    class AuthFailed : IOException(tr("Code ou appairage refusé par l'autre appareil"))

    /** An authenticated, encrypted channel. */
    class Channel internal constructor(input: InputStream, output: OutputStream, private val sendKey: ByteArray, private val receiveKey: ByteArray) {
        private val inp = DataInputStream(input)
        private val out = DataOutputStream(output)
        private var sent = 0L
        private var received = 0L

        fun send(data: ByteArray) {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce(sent++))) }
            val sealed = c.doFinal(data)
            out.writeInt(sealed.size); out.write(sealed); out.flush()
        }

        fun receive(): ByteArray {
            val n = inp.readInt()
            if (n !in 16..MAX_FRAME) throw IOException(tr("Message réseau invalide"))
            val sealed = ByteArray(n).also { inp.readFully(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, nonce(received++))) }
            return c.doFinal(sealed)
        }

        fun sendText(s: String) = send(s.toByteArray())
        fun receiveText() = receive().decodeToString()

        private fun nonce(n: Long) = ByteBuffer.allocate(12).putInt(0).putLong(n).array()
    }

    /** Initiator side: [mode] and the secret both sides must share. [rng] is only replaced by the compatibility tests. */
    fun connect(input: InputStream, output: OutputStream, mode: Int, auth: ByteArray, rng: SecureRandom = random): Channel {
        val out = DataOutputStream(output); val inp = DataInputStream(input)
        val priv = ByteArray(X25519.SCALAR_SIZE).also { X25519.generatePrivateKey(rng, it) }
        val pub = ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(priv, 0, it, 0) }
        out.write(MAGIC); out.writeByte(VERSION); out.writeByte(mode); out.write(pub); out.flush()
        val peer = ByteArray(X25519.POINT_SIZE).also { inp.readFully(it) }
        val k = keys(priv, pub, peer, mode, auth, initiator = true)
        out.write(k.proof("I")); out.flush()
        val theirs = ByteArray(32).also { inp.readFully(it) }
        if (!MessageDigest.isEqual(theirs, k.proof("R"))) throw AuthFailed()
        return Channel(input, output, k.i2r, k.r2i)
    }

    /** What an incoming connection asks for, read before choosing the secret to check. */
    fun readMode(input: InputStream): Int {
        val inp = DataInputStream(input)
        val magic = ByteArray(4).also { inp.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException(tr("Ce n'est pas un appareil Nautile"))
        val version = inp.readUnsignedByte()
        if (version != VERSION) throw IOException(tr("Version de Nautile incompatible sur l'autre appareil"))
        return inp.readUnsignedByte()
    }

    /** Responder side, after [readMode]. */
    fun accept(input: InputStream, output: OutputStream, mode: Int, auth: ByteArray, rng: SecureRandom = random): Channel {
        val out = DataOutputStream(output); val inp = DataInputStream(input)
        val peer = ByteArray(X25519.POINT_SIZE).also { inp.readFully(it) }
        val priv = ByteArray(X25519.SCALAR_SIZE).also { X25519.generatePrivateKey(rng, it) }
        val pub = ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(priv, 0, it, 0) }
        out.write(pub); out.flush()
        val k = keys(priv, peer, pub, mode, auth, initiator = false)
        val theirs = ByteArray(32).also { inp.readFully(it) }
        if (!MessageDigest.isEqual(theirs, k.proof("I"))) throw AuthFailed()
        out.write(k.proof("R")); out.flush()
        return Channel(input, output, k.r2i, k.i2r)
    }

    private class Keys(val i2r: ByteArray, val r2i: ByteArray, private val proofKey: ByteArray, private val transcript: ByteArray) {
        fun proof(role: String) = hmac(proofKey, transcript + role.toByteArray())
    }

    /** [pubI]/[pubR]: the initiator's and responder's public keys, whichever side computes. */
    private fun keys(priv: ByteArray, pubI: ByteArray, pubR: ByteArray, mode: Int, auth: ByteArray, initiator: Boolean): Keys {
        val peer = if (initiator) pubR else pubI
        val shared = ByteArray(X25519.POINT_SIZE)
        if (!X25519.calculateAgreement(priv, 0, peer, 0, shared, 0)) throw IOException(tr("Clé réseau invalide"))
        val transcript = "nautile-lan-v1".toByteArray() + byteArrayOf(mode.toByte()) + pubI + pubR
        val prk = hmac(transcript, shared)
        return Keys(
            i2r = hmac(prk, "i2r".toByteArray()),
            r2i = hmac(prk, "r2i".toByteArray()),
            proofKey = hmac(prk, "proof".toByteArray() + auth),
            transcript = transcript,
        )
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** A 6-digit pairing code. */
    fun newCode(): String = (random.nextInt(900_000) + 100_000).toString()

    fun newSecret(): ByteArray = ByteArray(32).also(random::nextBytes)
}
