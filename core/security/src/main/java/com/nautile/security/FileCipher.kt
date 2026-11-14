package com.nautile.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the app's own files (AES-256-GCM) with a random key that [key] keeps (in the app: [SecretStore],
 * itself sealed by the Android Keystore). Files written before encryption stay readable as they are.
 */
class FileCipher(
    /** Whether a null [key] is gone for good (its secret cannot be decrypted any more), not just unreachable now. */
    private val keyLost: () -> Boolean = { false },
    private val key: () -> ByteArray?,
) {
    private val random = SecureRandom()

    /** What reading a file gave. */
    sealed interface Opened {
        class Plain(val bytes: ByteArray) : Opened
        /** The key cannot be read now (the Keystore failing for a moment): try again later, never overwrite the file. */
        data object Unavailable : Opened
        /** Sealed with another key, damaged, or its key lost for good: no later try will read it. */
        data object Unreadable : Opened
    }

    fun seal(plain: ByteArray): ByteArray {
        val k = key() ?: throw IllegalStateException("file key unavailable")
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv)) }
        cipher.updateAAD(MAGIC)
        return MAGIC + iv + cipher.doFinal(plain)
    }

    /** The plain bytes: decrypted when sealed, as they are when written before encryption; null when they cannot be read. */
    fun open(blob: ByteArray): ByteArray? = (read(blob) as? Opened.Plain)?.bytes

    /** Like [open], telling a key unreachable for now from a file that will never be read. */
    fun read(blob: ByteArray): Opened {
        if (!isSealed(blob)) return Opened.Plain(blob)
        val k = key() ?: return if (keyLost()) Opened.Unreadable else Opened.Unavailable
        return decrypt(k, blob)?.let { Opened.Plain(it) } ?: Opened.Unreadable
    }

    private fun decrypt(k: ByteArray, blob: ByteArray): ByteArray? {
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, blob, MAGIC.size, IV_LEN))
            }
            cipher.updateAAD(MAGIC)
            cipher.doFinal(blob, MAGIC.size + IV_LEN, blob.size - MAGIC.size - IV_LEN)
        }.getOrNull()
    }

    companion object {
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), 'C'.code.toByte(), 1)
        private const val IV_LEN = 12
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun isSealed(blob: ByteArray) = blob.size > MAGIC.size + IV_LEN && MAGIC.indices.all { blob[it] == MAGIC[it] }

        /**
         * A cipher whose key is created on first use and kept in [secrets] under [name]. A key kept but unreadable
         * for now is never replaced (the files sealed with it would be lost); one whose decryption failed for good
         * ([SecretStore.Read.Unreadable], or not a key) is moved aside as `<name>.lost-<stamp>`, still sealed,
         * and a new one is made: otherwise nothing could ever be saved again. The files sealed with the old one
         * then read as [Opened.Unreadable].
         */
        fun of(secrets: SecretStore, name: String = "file_key", stamp: () -> String = ::lostStamp): FileCipher {
            var cached: ByteArray? = null
            /** The last try found the key unreadable for good (not merely unreachable). */
            var lost = false
            // commit: files sealed with a key that a crash kept from the disk could never be read.
            fun create() = ByteArray(32).also { random(it); secrets.put(name, java.util.Base64.getEncoder().encodeToString(it), sync = true) }
            fun replace(): ByteArray? {
                lost = true
                if (!secrets.moveAside(name, "$name.lost-${stamp()}")) return null
                return create()
            }
            val key = {
                synchronized(secrets) {
                    cached ?: runCatching {
                        lost = false
                        if (!secrets.contains(name)) create() else when (val r = secrets.read(name)) {
                            is SecretStore.Read.Value ->
                                runCatching { java.util.Base64.getDecoder().decode(r.value) }.getOrNull()?.takeIf { it.size == 32 } ?: replace()
                            SecretStore.Read.Unreadable -> replace()
                            else -> null
                        }
                    }.getOrNull()?.also { cached = it; lost = false }
                }
            }
            return FileCipher(keyLost = { synchronized(secrets) { lost } }, key = key)
        }

        private fun lostStamp(): String =
            java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", java.util.Locale.ROOT).format(java.util.Date())

        private fun random(b: ByteArray) = SecureRandom().nextBytes(b)
    }
}
