package com.nautile.security

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Small secrets (API keys) encrypted with an AES-GCM key kept in the Android Keystore.
 * Every store of the same alias writes under one lock ([lockFor]), whichever instance: two first writes at
 * once would otherwise each create the key, the second replacing the one the first sealed with.
 */
class SecretStore internal constructor(
    private val prefs: SharedPreferences,
    private val sealer: KeystoreSealer,
    private val lock: Any,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("nautile_secrets", Context.MODE_PRIVATE),
        KeystoreSealer(AndroidKeyHolder(ALIAS)),
        lockFor(ALIAS),
    )

    /** What reading a secret gave. */
    sealed interface Read {
        data class Value(val value: String) : Read
        data object Absent : Read
        /** Stored, but its decryption failed for good ([KeyLostException]). */
        data object Unreadable : Read
        /** Stored, but the Keystore failed for another reason: it may work on the next try. */
        data object Unavailable : Read
    }

    fun read(name: String): Read {
        val stored = prefs.getString(name, null) ?: return Read.Absent
        val blob = runCatching { Base64.getDecoder().decode(stored) }.getOrNull() ?: return Read.Unreadable
        return try {
            Read.Value(String(sealer.open(blob)))
        } catch (_: KeyLostException) {
            Read.Unreadable
        } catch (_: Exception) {
            Read.Unavailable
        }
    }

    /** The secret, or null when it is absent or cannot be read now ([read] tells which). */
    fun get(name: String): String? = (read(name) as? Read.Value)?.value

    /** Whether [name] is stored, readable or not ([get] answers null for both). */
    fun contains(name: String): Boolean = prefs.contains(name)

    /**
     * Stores [value] (null or empty removes it). [sync] writes it to disk before returning: for a key that
     * protects other data, lost to a crash right after it was used.
     * @throws KeystoreUnavailableException when the Keystore cannot encrypt now (or its key is unreachable while secrets sealed with it exist).
     */
    fun put(name: String, value: String?, sync: Boolean = false) = synchronized(lock) {
        val edit = if (value.isNullOrEmpty()) prefs.edit().remove(name) else {
            // A key may only be created while nothing is sealed with the current one (see KeystoreSealer).
            val nothingSealed = prefs.all.isEmpty()
            val blob = sealer.seal(value.toByteArray(), nothingSealed)
            prefs.edit().putString(name, Base64.getEncoder().encodeToString(blob))
        }
        if (sync) {
            if (!edit.commit()) throw KeystoreUnavailableException(IllegalStateException("secret not saved"))
        } else edit.apply()
    }

    /**
     * Moves the stored value of [name], still sealed and never decrypted, to [newName] (kept, not deleted);
     * false when it could not be saved. [name] is then absent: the next [put] makes a new one.
     */
    fun moveAside(name: String, newName: String): Boolean = synchronized(lock) {
        val stored = prefs.getString(name, null) ?: return@synchronized true
        prefs.edit().putString(newName, stored).remove(name).commit()
    }

    internal companion object {
        const val ALIAS = "nautile_secrets_key"
        private val locks = ConcurrentHashMap<String, Any>()

        /** The one lock of the stores sealed with the Keystore key [alias]. */
        fun lockFor(alias: String): Any = locks.computeIfAbsent(alias) { Any() }
    }
}
