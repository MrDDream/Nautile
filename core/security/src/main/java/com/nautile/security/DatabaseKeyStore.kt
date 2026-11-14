package com.nautile.security

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Base64

/**
 * Holds the random passphrase of the SQLCipher database, wrapped by an AES-GCM key that
 * never leaves the Android Keystore. The wrapped passphrase is never deleted: when it cannot be
 * opened any more it is moved aside with the database it opened ([setAside]).
 *
 * Each passphrase has a key of its own: its alias is saved next to it (a new one each time a passphrase
 * is made), so that starting afresh never replaces the key of a passphrase set aside. Installs made before
 * keep the first alias ([LEGACY_ALIAS]), read when no alias is saved.
 */
class DatabaseKeyStore internal constructor(
    private val prefs: SharedPreferences,
    private val sealerFor: (alias: String) -> KeystoreSealer,
    /** The phone's boot count ([bootCount]), negative when unknown. */
    private val boot: () -> Int,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("nautile_vault", Context.MODE_PRIVATE),
        { alias -> KeystoreSealer(AndroidKeyHolder(alias)) },
        { bootCount(context) },
    )

    /** A passphrase was created on this install (the database may exist). */
    fun hasPassphrase(): Boolean = prefs.contains(PREF_BLOB)

    /**
     * The passphrase, created on first use.
     * @throws KeyLostException when it can never be unwrapped again (confirmed on every try);
     * @throws KeystoreUnavailableException when the Keystore fails otherwise: nothing is lost, try again later.
     */
    @Synchronized
    fun passphrase(): ByteArray {
        val stored = prefs.getString(PREF_BLOB, null)
        if (stored != null) {
            val blob = runCatching { Base64.getDecoder().decode(stored) }.getOrNull() ?: throw KeyLostException()
            return sealerFor(prefs.getString(PREF_ALIAS, null) ?: LEGACY_ALIAS).open(blob)
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val alias = newAlias()
        val sealed = sealerFor(alias).seal(fresh, nothingSealed = alias !in aliasesInUse())
        // commit: a passphrase lost to a crash right after would leave a database nobody can open.
        val saved = prefs.edit().putString(PREF_BLOB, Base64.getEncoder().encodeToString(sealed)).putString(PREF_ALIAS, alias).commit()
        if (!saved) throw KeystoreUnavailableException(IllegalStateException("passphrase not saved"))
        return fresh
    }

    /** Aliases of the passphrases kept, the current one and those set aside (`.lost-*`): their keys must stay. */
    private fun aliasesInUse(): Set<String> {
        val all = prefs.all
        val blobs = all.keys.filter { it == PREF_BLOB || it.startsWith("$PREF_BLOB.lost-") }
        return blobs.mapTo(mutableSetOf()) { blob ->
            val aliasKey = if (blob == PREF_BLOB) PREF_ALIAS else PREF_ALIAS + blob.removePrefix(PREF_BLOB)
            all[aliasKey] as? String ?: LEGACY_ALIAS
        }
    }

    private fun newAlias(): String {
        val inUse = aliasesInUse()
        val random = SecureRandom()
        while (true) {
            val suffix = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val alias = "${LEGACY_ALIAS}_$suffix"
            if (alias !in inUse) return alias
        }
    }

    /**
     * Moves the wrapped passphrase aside, next to the database files renamed with the same [stamp]
     * (`db_passphrase.lost-<stamp>`, its alias as `db_passphrase_alias.lost-<stamp>`): the next [passphrase]
     * makes a new one with a key of its own, the old one and its key stay.
     */
    @Synchronized
    fun setAside(stamp: String) {
        val stored = prefs.getString(PREF_BLOB, null) ?: return
        val alias = prefs.getString(PREF_ALIAS, null) ?: LEGACY_ALIAS
        prefs.edit().putString("$PREF_BLOB.lost-$stamp", stored).putString("$PREF_ALIAS.lost-$stamp", alias)
            .remove(PREF_BLOB).remove(PREF_ALIAS).remove(PREF_LOSSES).remove(PREF_LOSS_BOOTS).commit()
    }

    /**
     * The passphrase was found lost ([KeyLostException]): whether the data may be set aside now. Only a launch
     * on screen ([foreground]) counts, and only once per boot of the phone: the loss must be seen after
     * [LOSSES_BEFORE_RESET] distinct boots, since a Keystore failing in a way that looks final may still answer
     * after a restart. A start in the background (widget, worker) never counts and never sets anything aside,
     * and neither does a boot that cannot be told ([bootCount] unknown).
     */
    @Synchronized
    fun noteLoss(foreground: Boolean): Boolean {
        if (!foreground) return false
        val boot = boot()
        if (boot < 0) return false
        val seen = lossBoots()
        val all = seen + boot
        if (all.size != seen.size) prefs.edit().putString(PREF_LOSS_BOOTS, all.joinToString(",")).commit()
        return all.size >= LOSSES_BEFORE_RESET
    }

    private fun lossBoots(): Set<Int> =
        prefs.getString(PREF_LOSS_BOOTS, null)?.split(',')?.mapNotNullTo(LinkedHashSet()) { it.trim().toIntOrNull() }.orEmpty()

    /** The passphrase opened: an earlier loss was a passing failure. */
    @Synchronized
    fun clearLosses() {
        if (prefs.contains(PREF_LOSSES) || prefs.contains(PREF_LOSS_BOOTS)) prefs.edit().remove(PREF_LOSSES).remove(PREF_LOSS_BOOTS).commit()
    }

    internal companion object {
        /** The alias of the installs made before each passphrase had its own key. */
        const val LEGACY_ALIAS = "nautile_db_key"
        const val PREF_BLOB = "db_passphrase"
        const val PREF_ALIAS = "db_passphrase_alias"
        /** Count of earlier versions (process starts, whatever the boot): no longer read, removed. */
        const val PREF_LOSSES = "db_passphrase_losses"
        /** The boot counts at which the passphrase was found lost by a launch on screen. */
        const val PREF_LOSS_BOOTS = "db_passphrase_loss_boots"
        /** Distinct boots after which the passphrase must be found lost before the data is set aside by itself. */
        const val LOSSES_BEFORE_RESET = 2
    }
}
