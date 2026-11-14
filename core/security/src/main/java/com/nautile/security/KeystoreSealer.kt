package com.nautile.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The wrapped value can never be decrypted again: its key is gone, broken, or another one (data copied from another device). */
class KeyLostException(cause: Throwable? = null) : Exception("keystore key lost", cause)

/** The Keystore failed for another reason (busy, crashed, locked…): try again later, nothing is lost. */
class KeystoreUnavailableException(cause: Throwable? = null) : Exception("keystore unavailable", cause)

/** One AES key of the Android Keystore, behind an interface so that the policy around it can be tested. */
internal interface KeyHolder {
    /** The key, or null when the Keystore does not return it (missing, or a failure it swallowed: keystore2 does). */
    fun get(): SecretKey?

    /**
     * A listing of the Keystore succeeded and lacks the key. A listing proves it works only when it shows the
     * probe key ([AndroidKeyHolder.PROBE], created when missing): failures list nothing, and a Keystore that
     * is merely empty (wiped) lists nothing either.
     */
    fun confirmedAbsent(): Boolean

    fun create(): SecretKey

    fun delete()
}

internal class AndroidKeyHolder(private val alias: String) : KeyHolder {
    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun get(): SecretKey? = (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    override fun confirmedAbsent(): Boolean = runCatching {
        absentFromListing(alias, { keyStore().aliases().toList() }, { generate(PROBE) })
    }.getOrDefault(false)

    override fun create(): SecretKey = generate(alias)

    private fun generate(name: String): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
        init(
            KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
    }.generateKey()

    override fun delete() = keyStore().deleteEntry(alias)

    internal companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        /** A key that seals nothing: listed, it proves a listing of the Keystore works. */
        const val PROBE = "nautile_probe"

        /**
         * [alias] is missing from a listing shown to work: one that lists the [PROBE] (created when missing,
         * then listed again). A listing without the probe even after creating it proves nothing.
         */
        fun absentFromListing(alias: String, list: () -> List<String>, createProbe: () -> Unit): Boolean {
            var aliases = list()
            if (PROBE !in aliases) {
                createProbe()
                aliases = list()
            }
            return PROBE in aliases && alias !in aliases
        }
    }
}

/**
 * AES-GCM with a Keystore key, careful about what a failure means. Only a failed decryption (bad tag,
 * a key the Keystore says it cannot recover or has invalidated, a key a working Keystore no longer lists)
 * counts as lost, and only when it happens again on every try; anything else is a passing failure.
 * A key is created only when nothing sealed with the old one exists, or when the old one is known to be gone:
 * creating it again under the same alias would replace a key that is merely unreachable for a moment.
 */
internal class KeystoreSealer(
    private val keys: KeyHolder,
    private val attempts: Int = 3,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    /** Android 13+ tells passing failures apart ([android.security.KeyStoreException.isTransientFailure]). */
    private val transientHint: (Throwable) -> Boolean = ::androidTransient,
    /** The error code of a Keystore exception of Android 8-12 (keystore1, read by reflection), null for anything else. */
    private val legacyCode: (Throwable) -> Int? = ::legacyKeystoreCode,
) {
    /** The key is absent although data sealed with it exists: never recreated, never mistaken for a passing failure. */
    private class KeyAbsent : Exception("key absent")

    fun open(blob: ByteArray): ByteArray {
        // Truncated: no key will ever open it.
        if (blob.size < IV_LEN + 16) throw KeyLostException(IllegalArgumentException("sealed value too short"))
        return retrying { openOnce(blob) }
    }

    private fun openOnce(blob: ByteArray): ByteArray {
        val key = keys.get() ?: if (keys.confirmedAbsent()) throw KeyAbsent() else throw KeystoreUnavailableException()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, IV_LEN)) }
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    /** [nothingSealed]: no value sealed with the current key exists, so a key may be created (or a broken one replaced). */
    fun seal(plain: ByteArray, nothingSealed: Boolean): ByteArray = retrying {
        try {
            val key = keys.get() ?: when {
                nothingSealed || keys.confirmedAbsent() -> keys.create()
                else -> throw KeystoreUnavailableException()
            }
            encrypt(key, plain)
        } catch (e: Exception) {
            // A key that cannot encrypt for good, while nothing depends on it: replace it.
            if (!nothingSealed || !isPermanent(e)) throw e
            keys.delete()
            encrypt(keys.create(), plain)
        }
    }

    private fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun <T> retrying(block: () -> T): T {
        var last: Throwable? = null
        for (i in 1..attempts) {
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (i < attempts) pause(PAUSE_MS * i)
            }
        }
        val e = last ?: KeystoreUnavailableException()
        throw if (isPermanent(e)) KeyLostException(e) else e as? KeystoreUnavailableException ?: KeystoreUnavailableException(e)
    }

    /** A decryption that can never succeed again (the cause chain is searched: the Keystore wraps its errors). */
    fun isPermanent(e: Throwable): Boolean {
        val chain = generateSequence(e) { it.cause }.take(8).toList()
        if (chain.any(transientHint)) return false
        if (chain.any { it is AEADBadTagException || it is KeyPermanentlyInvalidatedException || it is KeyAbsent }) return true
        if (chain.none { it is UnrecoverableKeyException }) return false
        // Android 8-12 wraps passing failures too (system error, locked…) in UnrecoverableKeyException:
        // only a key the Keystore says is missing or broken is lost.
        val codes = chain.mapNotNull(legacyCode)
        return codes.isEmpty() || codes.any { it in LOST_CODES }
    }

    companion object {
        const val IV_LEN = 12
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PAUSE_MS = 150L

        /** keystore1's KEY_NOT_FOUND and Keymaster's INVALID_KEY_BLOB: the key is gone or broken for good. */
        internal val LOST_CODES = setOf(7, -33)
        private const val KEYSTORE_EXCEPTION = "android.security.KeyStoreException"

        /** Before Android 13 the class is hidden: its code is read by reflection (13+ answers [androidTransient]). */
        private fun legacyKeystoreCode(e: Throwable): Int? {
            if (e.javaClass.name != KEYSTORE_EXCEPTION || Build.VERSION.SDK_INT >= 33) return null
            return runCatching { e.javaClass.getMethod("getErrorCode").invoke(e) as? Int }.getOrNull()
        }

        private fun androidTransient(e: Throwable): Boolean =
            Build.VERSION.SDK_INT >= 33 && e is android.security.KeyStoreException && (e.isTransientFailure || e.isSystemError)
    }
}
