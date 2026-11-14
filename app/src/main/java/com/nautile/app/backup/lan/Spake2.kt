package com.nautile.app.backup.lan

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.util.BigIntegers
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SPAKE2 over P-256 (RFC 9382, suite SPAKE2-P256-SHA256-HKDF-HMAC, cofactor 1), the same as the desktop app
 * (`nautile_core::lan::spake2`).
 */
object Spake2 {
    private val params = CustomNamedCurves.getByName("secp256r1")
    private val curve = params.curve
    val G: ECPoint = params.g
    val n: BigInteger = params.n

    /**
     * RFC 9382's M and N for P-256: the first valid points from SHA-256 iterated over
     * "1.2.840.10045.3.1.7 point generation seed (M)" / "(N)" (the RFC's appendix script); checked in the tests.
     */
    val M: ECPoint = curve.decodePoint(hex("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))
    val N: ECPoint = curve.decodePoint(hex("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))

    /** A point on the wire: SEC1 uncompressed. */
    const val POINT_LEN = 65

    /** `w` from the 6-digit code: SHA-256("nautile-pair-v2" · code) reduced mod n. */
    fun password(code: String): BigInteger = BigInteger(1, sha256("nautile-pair-v2".toByteArray() + code.toByteArray())).mod(n)

    /** A scalar in [1, n-1] from its 32 big-endian bytes, or null. */
    fun scalar(bytes: ByteArray): BigInteger? = BigInteger(1, bytes).takeIf { bytes.size == 32 && it.signum() > 0 && it < n }

    fun scalarBytes(s: BigInteger): ByteArray = BigIntegers.asUnsignedByteArray(32, s)

    /** 32 random bytes that make a valid scalar. */
    fun randomScalar(rng: SecureRandom): ByteArray {
        while (true) {
            val b = ByteArray(32).also(rng::nextBytes)
            if (scalar(b) != null) return b
        }
    }

    fun encode(p: ECPoint): ByteArray = p.normalize().getEncoded(false)

    /** A point from the other side: uncompressed, on the curve, not the identity; null otherwise. */
    fun decode(bytes: ByteArray): ECPoint? {
        if (bytes.size != POINT_LEN || bytes[0].toInt() != 4) return null
        return runCatching { curve.decodePoint(bytes) }.getOrNull()?.takeIf { !it.isInfinity && it.isValid }
    }

    /** The message to send: `x·G + w·base` (`base` = M for A, N for B). */
    fun message(x: BigInteger, w: BigInteger, base: ECPoint): ECPoint = G.multiply(x).add(base.multiply(w)).normalize()

    /** The shared point `x·(peer − w·base)` (`base` = N for A, M for B); null for the identity. */
    fun shared(x: BigInteger, w: BigInteger, base: ECPoint, peer: ECPoint): ECPoint? =
        peer.subtract(base.multiply(w)).multiply(x).normalize().takeIf { !it.isInfinity }

    fun transcript(a: ByteArray, b: ByteArray, pA: ByteArray, pB: ByteArray, k: ByteArray, w: BigInteger): ByteArray {
        val out = ByteArrayOutputStream()
        listOf(a, b, pA, pB, k, scalarBytes(w)).forEach { lp(out, it) }
        return out.toByteArray()
    }

    /** The keys from a transcript. */
    class Keys(tt: ByteArray) {
        private val h = sha256(tt)
        val ke: ByteArray = h.copyOfRange(0, 16)
        val ka: ByteArray = h.copyOfRange(16, 32)
        private val kc = hkdf(ByteArray(0), ka, "ConfirmationKeys".toByteArray(), 32)
        val kcA: ByteArray = kc.copyOfRange(0, 16)
        val kcB: ByteArray = kc.copyOfRange(16, 32)

        fun confirmA(tt: ByteArray) = hmac(kcA, tt)
        fun confirmB(tt: ByteArray) = hmac(kcB, tt)
    }

    // ── Helpers shared with LanProtocol ───────────────────────────────────

    /** [x] prefixed with its length, a u64 little-endian (RFC 9382). */
    internal fun lp(out: ByteArrayOutputStream, x: ByteArray) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(x.size.toLong()).array())
        out.write(x)
    }

    internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** HKDF-SHA256 (RFC 5869); an empty [salt] is the default one (32 zero bytes). */
    internal fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
