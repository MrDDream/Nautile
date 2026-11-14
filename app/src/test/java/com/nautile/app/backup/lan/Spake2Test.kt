package com.nautile.app.backup.lan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/** The same checks as the desktop's `nautile_core::lan::tests`. */
class Spake2Test {
    private fun h(s: String) = Spake2.hex(s)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** RFC 9382 appendix A: M and N come from SHA-256 iterated over a seed, the first candidate on the curve. */
    @Test fun mAndNAreTheRfcPoints() {
        for ((seed, expected) in listOf("M" to Spake2.M, "N" to Spake2.N)) {
            val start = "1.2.840.10045.3.1.7 point generation seed ($seed)".toByteArray()
            var found: ByteArray? = null
            var hash = start
            for (i in 1 until 1000) {
                hash = MessageDigest.getInstance("SHA-256").digest(hash)
                val cand = (hash + MessageDigest.getInstance("SHA-256").digest(hash)).copyOf(33)
                cand[0] = ((cand[0].toInt() and 1) or 2).toByte()
                val ok = runCatching { org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256r1").curve.decodePoint(cand) }.isSuccess
                if (ok) { found = cand; break }
            }
            assertEquals(hex(expected.getEncoded(true)), hex(found!!))
        }
    }

    /** RFC 9382 appendix B, the first P-256 vector (A = "server", B = "client"). */
    @Test fun rfc9382TestVector() {
        val w = Spake2.scalar(h("2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f"))!!
        val x = Spake2.scalar(h("43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729"))!!
        val y = Spake2.scalar(h("dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be"))!!
        val pA = Spake2.encode(Spake2.message(x, w, Spake2.M))
        val pB = Spake2.encode(Spake2.message(y, w, Spake2.N))
        assertEquals("04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c", hex(pA))
        assertEquals("0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7", hex(pB))
        val kA = Spake2.encode(Spake2.shared(x, w, Spake2.N, Spake2.decode(pB)!!)!!)
        val kB = Spake2.encode(Spake2.shared(y, w, Spake2.M, Spake2.decode(pA)!!)!!)
        assertArrayEquals(kA, kB)
        assertEquals("0412af7e89717850671913e6b469ace67bd90a4df8ce45c2af19010175e37eed69f75897996d539356e2fa6a406d528501f907e04d97515fbe83db277b715d3325", hex(kA))
        val tt = Spake2.transcript("server".toByteArray(), "client".toByteArray(), pA, pB, kA, w)
        assertEquals(
            "06000000000000007365727665720600000000000000636c69656e74410000000000000004a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c41000000000000000406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b741000000000000000412af7e89717850671913e6b469ace67bd90a4df8ce45c2af19010175e37eed69f75897996d539356e2fa6a406d528501f907e04d97515fbe83db277b715d332520000000000000002ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f",
            hex(tt),
        )
        val keys = Spake2.Keys(tt)
        assertEquals("0e0672dc86f8e45565d338b0540abe69", hex(keys.ke))
        assertEquals("15bdf72e2b35b5c9e5663168e960a91b", hex(keys.ka))
        assertEquals("00c12546835755c86d8c0db7851ae86f", hex(keys.kcA))
        assertEquals("a9fa3406c3b781b93d804485430ca27a", hex(keys.kcB))
        assertEquals("58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0", hex(keys.confirmA(tt)))
        assertEquals("d3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b", hex(keys.confirmB(tt)))
    }

    /** RFC 5869, test case 1. */
    @Test fun hkdfMatchesRfc5869() {
        val okm = Spake2.hkdf(h("000102030405060708090a0b0c"), ByteArray(22) { 0x0b }, h("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex(okm))
    }

    @Test fun badPointsAreRefused() {
        // The identity, a compressed point, a point off the curve, a coordinate past p.
        assertNull(Spake2.decode(byteArrayOf(0)))
        assertNull(Spake2.decode(Spake2.M.getEncoded(true)))
        val off = Spake2.encode(Spake2.M).also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertNull(Spake2.decode(off))
        assertNull(Spake2.decode(ByteArray(65) { if (it == 0) 4 else -1 }))
        assertNotNull(Spake2.decode(Spake2.encode(Spake2.M)))
        // A peer point that cancels out: K would be the identity.
        val w = Spake2.password("123456")
        assertNull(Spake2.shared(BigInteger.valueOf(7), w, Spake2.M, Spake2.M.multiply(w).normalize()))
        // Zero and n are not scalars.
        assertNull(Spake2.scalar(ByteArray(32)))
        assertNull(Spake2.scalar(h("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")))
    }

    /** The same bytes on the desktop (password_scalar_is_stable). */
    @Test fun passwordScalarIsStable() {
        assertEquals("7d687220bd43d3231991ab50ddd566c0a137de3832c9c0854f502d0b5baab37d", hex(Spake2.scalarBytes(Spake2.password("482916"))))
    }
}
