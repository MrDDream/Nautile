package com.nautile.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** How the app is unlocked. */
enum class LockMethod { BIOMETRIC, PIN, PASSWORD }

/** The app's own PIN or password: only a salted PBKDF2 hash is kept, never the code itself. */
object LockSecret {
    private const val ITERATIONS = 60_000
    private const val PREFIX = "pbkdf2-sha256"

    /** "pbkdf2-sha256:iterations:salt:hash", in Base64. */
    fun hash(secret: String, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val b64 = Base64.getEncoder()
        return "$PREFIX:$iterations:${b64.encodeToString(salt)}:${b64.encodeToString(derive(secret, salt, iterations))}"
    }

    fun verify(secret: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val b64 = Base64.getDecoder()
        val expected = runCatching { b64.decode(parts[3]) }.getOrNull() ?: return false
        val salt = runCatching { b64.decode(parts[2]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(secret, salt, iterations), expected)
    }

    private fun derive(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** After 5 wrong tries: wait 30 s, then twice as long each time, up to 15 min. */
    fun lockoutMs(failures: Int): Long = if (failures < FREE_TRIES) 0L
    else (30_000L shl (failures - FREE_TRIES).coerceAtMost(5)).coerceAtMost(15 * 60_000L)

    const val FREE_TRIES = 5
    const val PIN_MIN = 4
    const val PIN_MAX = 8
    const val PASSWORD_MIN = 6

    fun validPin(pin: String) = pin.length in PIN_MIN..PIN_MAX && pin.all { it in '0'..'9' }
    fun validPassword(pw: String) = pw.length >= PASSWORD_MIN
}
