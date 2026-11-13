package com.nautile.security

import com.nautile.i18n.tr

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Optional lock shown when the app opens: biometrics (or the device's code), or a PIN / password of the app's own. */
class AppLock(context: Context) {
    private val prefs = context.getSharedPreferences("nautile_lock", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val manager = BiometricManager.from(context)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var method: LockMethod
        get() = prefs.getString(KEY_METHOD, null)?.let { runCatching { LockMethod.valueOf(it) }.getOrNull() } ?: LockMethod.BIOMETRIC
        private set(value) = prefs.edit().putString(KEY_METHOD, value.name).apply()

    /** Digits in the PIN, so the lock screen checks it as soon as it is complete. */
    val pinLength: Int get() = prefs.getInt(KEY_PIN_LENGTH, 0)

    /** With a PIN or a password: the fingerprint can unlock too, the code stays as the fallback. */
    var biometricShortcut: Boolean
        get() = prefs.getBoolean(KEY_SHORTCUT, true)
        set(value) = prefs.edit().putBoolean(KEY_SHORTCUT, value).apply()

    /** How long the app may stay in the background before asking again; 0 = as soon as it leaves the screen. */
    var relockAfterMs: Long
        get() = prefs.getLong(KEY_RELOCK, DEFAULT_RELOCK_MS)
        set(value) = prefs.edit().putLong(KEY_RELOCK, value).apply()

    /** Hide the app's content in the recent apps and block screenshots (FLAG_SECURE). */
    var hideInRecents: Boolean
        get() = prefs.getBoolean(KEY_SECURE, false)
        set(value) = prefs.edit().putBoolean(KEY_SECURE, value).apply()

    /** When the app went to the background, while unlocked (in memory only: a new process locks anyway). */
    @Volatile private var backgroundSince: Long? = null

    fun onBackground(now: Long = System.currentTimeMillis()) { backgroundSince = now }

    /** Whether coming back to the foreground must ask again. */
    fun shouldRelock(now: Long = System.currentTimeMillis()): Boolean {
        val since = backgroundSince ?: return false
        backgroundSince = null
        return enabled && available && now - since >= relockAfterMs
    }

    /** Fingerprint, face or the device's own code is set up. */
    val biometricAvailable: Boolean
        get() = manager.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Only fingerprint / face (for the shortcut next to a PIN: the device code would bypass it). */
    val strongBiometricAvailable: Boolean
        get() = manager.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    /** The chosen method can actually ask: otherwise the app opens unlocked rather than locking the user out. */
    val available: Boolean
        get() = when (method) {
            LockMethod.BIOMETRIC -> biometricAvailable
            LockMethod.PIN, LockMethod.PASSWORD -> secrets.get(KEY_HASH) != null
        }

    /** Whether unlocking starts with the biometric prompt. */
    val asksBiometric: Boolean
        get() = when (method) {
            LockMethod.BIOMETRIC -> true
            else -> biometricShortcut && strongBiometricAvailable
        }

    /** Sets a PIN or a password (slow: call it off the main thread) and switches to that method. */
    fun setCode(method: LockMethod, code: String) {
        require(method != LockMethod.BIOMETRIC)
        secrets.put(KEY_HASH, LockSecret.hash(code))
        prefs.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LOCKED_UNTIL, 0)
            .putInt(KEY_PIN_LENGTH, if (method == LockMethod.PIN) code.length else 0).apply()
        this.method = method
    }

    fun useBiometric() {
        method = LockMethod.BIOMETRIC
        secrets.put(KEY_HASH, null)
        prefs.edit().remove(KEY_PIN_LENGTH).apply()
    }

    sealed interface Check {
        data object Ok : Check
        /** [triesLeft] before a wait. */
        data class Wrong(val triesLeft: Int) : Check
        data class Wait(val untilMs: Long) : Check
    }

    /** Remaining wait after too many wrong codes, 0 when a code can be typed. */
    fun waitMs(now: Long = System.currentTimeMillis()): Long = (prefs.getLong(KEY_LOCKED_UNTIL, 0) - now).coerceAtLeast(0)

    /** Checks a typed code (slow: call it off the main thread). */
    fun check(code: String, now: Long = System.currentTimeMillis()): Check {
        prefs.getLong(KEY_LOCKED_UNTIL, 0).takeIf { it > now }?.let { return Check.Wait(it) }
        val stored = secrets.get(KEY_HASH) ?: return Check.Ok
        if (LockSecret.verify(code, stored)) {
            prefs.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LOCKED_UNTIL, 0).apply()
            return Check.Ok
        }
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        val wait = LockSecret.lockoutMs(failures)
        prefs.edit().putInt(KEY_FAILURES, failures).putLong(KEY_LOCKED_UNTIL, if (wait > 0) now + wait else 0).apply()
        return if (wait > 0) Check.Wait(now + wait) else Check.Wrong(LockSecret.FREE_TRIES - failures)
    }

    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val shortcut = method != LockMethod.BIOMETRIC
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(
                    // Cancelling the fingerprint next to a PIN is just choosing to type the code.
                    if (shortcut) "" else errString.toString(),
                )
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(tr("Déverrouiller Nautile"))
        if (shortcut) info.setAllowedAuthenticators(BIOMETRIC_WEAK).setNegativeButtonText(
            if (method == LockMethod.PIN) tr("Saisir le code PIN") else tr("Saisir le mot de passe"),
        ) else info.setAllowedAuthenticators(AUTHENTICATORS)
        prompt.authenticate(info.build())
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_METHOD = "method"
        const val KEY_SHORTCUT = "biometric_shortcut"
        const val KEY_RELOCK = "relock_after_ms"
        const val KEY_SECURE = "hide_in_recents"
        const val KEY_FAILURES = "failures"
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val KEY_HASH = "app_lock_code"
        const val KEY_PIN_LENGTH = "pin_length"
        const val DEFAULT_RELOCK_MS = 5 * 60_000L
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
