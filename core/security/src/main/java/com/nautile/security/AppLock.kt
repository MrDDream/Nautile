package com.nautile.security

import com.nautile.i18n.tr

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Optional biometric / device-credential lock shown when the app opens. */
class AppLock(context: Context) {
    private val prefs = context.getSharedPreferences("nautile_lock", Context.MODE_PRIVATE)
    private val manager = BiometricManager.from(context)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

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

    val available: Boolean
        get() = manager.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(errString.toString())
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(tr("Déverrouiller Nautile"))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_RELOCK = "relock_after_ms"
        const val KEY_SECURE = "hide_in_recents"
        const val DEFAULT_RELOCK_MS = 5 * 60_000L
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
