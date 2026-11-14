package com.nautile.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nautile.i18n.tr
import java.util.concurrent.CopyOnWriteArrayList

/** Optional lock shown when the app opens: biometrics (or the device's code), or a PIN / password of the app's own. */
class AppLock(
    context: Context,
    /** The app's one store of secrets (another instance would still share its lock: [SecretStore.lockFor]). */
    private val secrets: SecretStore = SecretStore(context),
) {
    private val prefs = context.getSharedPreferences("nautile_lock", Context.MODE_PRIVATE)
    private val manager = BiometricManager.from(context)
    private val appContext = context.applicationContext ?: context

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Called when the lock is switched on or off (the widget hides the addresses while it is on). */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            listeners.forEach { runCatching { it() } }
        }

    private val process = ProcessLock()

    /**
     * Unlocked in this process. Never saved anywhere: a process started afresh (even when Android
     * restores the activity after killing it in the background) asks again. Set back to false here once
     * the app stayed in the background longer than [relockAfterMs] ([onForeground]).
     */
    var unlockedInProcess: Boolean
        get() = process.unlocked
        set(value) { process.unlocked = value }

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

    /** Hide the app's content in the recent apps and block screenshots (FLAG_SECURE); on by default while the lock is. */
    var hideInRecents: Boolean
        get() = prefs.getBoolean(KEY_SECURE, enabled)
        set(value) = prefs.edit().putBoolean(KEY_SECURE, value).apply()

    /*
     * The time in the background runs on the elapsed-time clock, not the wall clock: setting the date
     * back must not skip the lock.
     */

    /** A MainActivity is being created: locks the process when the delay is over (before it decides how to show). */
    fun onActivityCreated(nowRt: Long = SystemClock.elapsedRealtime()) = process.onCreate(nowRt, relockAfterMs)

    /** A MainActivity comes on screen: locks the process when the app stayed in the background too long. */
    fun onForeground(nowRt: Long = SystemClock.elapsedRealtime()) = process.onStart(nowRt, relockAfterMs)

    /** A MainActivity leaves the screen ([changingConfig]: recreated at once, not leaving). */
    fun onBackground(changingConfig: Boolean, nowRt: Long = SystemClock.elapsedRealtime()) = process.onStop(nowRt, changingConfig)

    /**
     * Whether an activity showing the app unlocked ([activityUnlocked]) must lock now: the process is locked
     * (by the delay, whichever activity came back first) and the lock is on and set up.
     */
    fun mustLock(activityUnlocked: Boolean): Boolean = process.mustLock(activityUnlocked, enabled && available)

    /** Fingerprint, face or the device's own code is set up. */
    val biometricAvailable: Boolean
        get() = manager.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Only fingerprint / face (for the shortcut next to a PIN: the device code would bypass it). */
    val strongBiometricAvailable: Boolean
        get() = manager.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * The chosen method is set up: otherwise the app opens unlocked rather than locking the user out.
     * A code that is kept but can no longer be read (Keystore) still counts: the lock stays shut ([codeUnreadable]).
     */
    val available: Boolean
        get() = when (method) {
            LockMethod.BIOMETRIC -> biometricAvailable
            LockMethod.PIN, LockMethod.PASSWORD -> secrets.contains(KEY_HASH)
        }

    /**
     * The PIN or password is stored but its decryption failed for good (not a passing Keystore failure,
     * which only asks to try again): only the device's own lock can open. Slow when the Keystore fails.
     */
    val codeUnreadable: Boolean
        get() = method != LockMethod.BIOMETRIC && secrets.read(KEY_HASH) == SecretStore.Read.Unreadable

    /** Whether unlocking starts with the biometric prompt. Reads [codeUnreadable]: slow when the Keystore fails. */
    val asksBiometric: Boolean
        get() = asksBiometric(codeUnreadable)

    /** [asksBiometric] for a [codeUnreadable] already read (off the main thread): reads no secret. */
    fun asksBiometric(codeUnreadable: Boolean): Boolean = when {
        method == LockMethod.BIOMETRIC -> true
        codeUnreadable -> biometricAvailable
        else -> biometricShortcut && strongBiometricAvailable
    }

    /** Sets a PIN or a password (slow: call it off the main thread) and switches to that method. */
    fun setCode(method: LockMethod, code: String) {
        require(method != LockMethod.BIOMETRIC)
        secrets.put(KEY_HASH, LockSecret.hash(code), sync = true)
        clearFailures(prefs.edit()).putInt(KEY_PIN_LENGTH, if (method == LockMethod.PIN) code.length else 0).apply()
        this.method = method
    }

    fun useBiometric() {
        method = LockMethod.BIOMETRIC
        secrets.put(KEY_HASH, null, sync = true)
        prefs.edit().remove(KEY_PIN_LENGTH).apply()
    }

    sealed interface Check {
        data object Ok : Check
        /** [triesLeft] before a wait; [unreadable]: the stored code cannot be decrypted, no code can match. */
        data class Wrong(val triesLeft: Int, val unreadable: Boolean = false) : Check
        /** [untilMs] on the wall clock, for the countdown only (the wait itself runs on the elapsed-time clock). */
        data class Wait(val untilMs: Long) : Check
        /** The Keystore failed for a moment: nothing was checked, nothing counted; try again. */
        data object Unavailable : Check
    }

    private fun bootCount(): Int = bootCount(appContext)

    private fun clearFailures(e: SharedPreferences.Editor) =
        e.putInt(KEY_FAILURES, 0).remove(KEY_LOCKED_UNTIL).remove(KEY_DEADLINE_RT).remove(KEY_WAIT).remove(KEY_BOOT)

    /** After another successful unlock (fingerprint, device code): the wrong codes typed before no longer count. */
    @Synchronized
    fun clearFailures() {
        clearFailures(prefs.edit()).commit()
    }

    /** Remaining wait after too many wrong codes, 0 when a code can be typed. */
    @Synchronized
    fun waitMs(): Long {
        val state = Lockout.State(prefs.getLong(KEY_DEADLINE_RT, 0), prefs.getLong(KEY_WAIT, 0), prefs.getInt(KEY_BOOT, -1))
        val (left, kept) = Lockout.remaining(state, SystemClock.elapsedRealtime(), bootCount())
        if (kept == Lockout.CLEARED && state != kept) {
            // Over: forgotten (the failure count stays, so the next wrong code waits longer).
            prefs.edit().remove(KEY_DEADLINE_RT).remove(KEY_WAIT).remove(KEY_BOOT).commit()
        } else if (kept != state) {
            prefs.edit().putLong(KEY_DEADLINE_RT, kept.deadlineRt).putInt(KEY_BOOT, kept.boot).commit()
        }
        return left
    }

    /** Checks a typed code (slow: call it off the main thread). */
    @Synchronized
    fun check(code: String): Check {
        waitMs().takeIf { it > 0 }?.let { return Check.Wait(System.currentTimeMillis() + it) }
        // Fails closed: a code that cannot be read never unlocks.
        val stored = when (val r = secrets.read(KEY_HASH)) {
            is SecretStore.Read.Value -> r.value
            SecretStore.Read.Unavailable -> return Check.Unavailable
            SecretStore.Read.Unreadable, SecretStore.Read.Absent -> return Check.Wrong(0, unreadable = true)
        }
        if (LockSecret.verify(code, stored)) {
            clearFailures(prefs.edit()).apply()
            return Check.Ok
        }
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        val wait = LockSecret.lockoutMs(failures)
        val s = Lockout.start(wait, SystemClock.elapsedRealtime(), bootCount())
        // commit: the count must be on disk before the answer, or killing the app would forget the try.
        prefs.edit().putInt(KEY_FAILURES, failures).remove(KEY_LOCKED_UNTIL)
            .putLong(KEY_DEADLINE_RT, s.deadlineRt).putLong(KEY_WAIT, s.waitMs).putInt(KEY_BOOT, s.boot).commit()
        return if (wait > 0) Check.Wait(System.currentTimeMillis() + wait) else Check.Wrong(LockSecret.FREE_TRIES - failures)
    }

    /**
     * The biometric prompt of [activity], to build in its onCreate each time it is created: after a rotation,
     * the prompt still on screen then answers the new activity rather than the destroyed one.
     * [deviceLock]: the phone's own lock (fingerprint or its code) is asked rather than the shortcut next to
     * the app's code, for a code that cannot be checked (unreadable, or the Keystore not answering).
     */
    inner class Unlocker internal constructor(
        activity: FragmentActivity,
        private val deviceLock: () -> Boolean,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        private val shortcut get() = method != LockMethod.BIOMETRIC && !deviceLock()

        private val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // Unlocked: earlier wrong codes must not keep a wait armed for the next start.
                    runCatching { clearFailures() }
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(
                    // Cancelling the fingerprint next to a PIN is just choosing to type the code.
                    if (shortcut) "" else errString.toString(),
                )
            },
        )

        fun authenticate() {
            val shortcut = shortcut
            val info = BiometricPrompt.PromptInfo.Builder().setTitle(tr("Déverrouiller Nautile"))
            if (shortcut) info.setAllowedAuthenticators(BIOMETRIC_WEAK).setNegativeButtonText(
                if (method == LockMethod.PIN) tr("Saisir le code PIN") else tr("Saisir le mot de passe"),
            ) else info.setAllowedAuthenticators(AUTHENTICATORS)
            prompt.authenticate(info.build())
        }
    }

    fun unlocker(activity: FragmentActivity, deviceLock: () -> Boolean, onSuccess: () -> Unit, onFailure: (String) -> Unit) =
        Unlocker(activity, deviceLock, onSuccess, onFailure)

    /**
     * Asks once (Settings): an unreadable code falls back to the device's own lock. Whether it is unreadable is
     * read off the main thread (slow when the Keystore fails), then the prompt opens on it.
     */
    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val unreadable = java.util.concurrent.atomic.AtomicBoolean(false)
        val unlocker = unlocker(activity, { unreadable.get() }, onSuccess, onFailure)
        val main = ContextCompat.getMainExecutor(activity)
        Thread {
            unreadable.set(runCatching { codeUnreadable }.getOrDefault(false))
            main.execute {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    runCatching { unlocker.authenticate() }.onFailure { onFailure(it.message.orEmpty()) }
                }
            }
        }.start()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_METHOD = "method"
        const val KEY_SHORTCUT = "biometric_shortcut"
        const val KEY_RELOCK = "relock_after_ms"
        const val KEY_SECURE = "hide_in_recents"
        const val KEY_FAILURES = "failures"
        /** Wall-clock deadline of older versions, removed on the next try. */
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val KEY_DEADLINE_RT = "locked_until_rt"
        const val KEY_WAIT = "locked_wait"
        const val KEY_BOOT = "locked_boot"
        const val KEY_HASH = "app_lock_code"
        const val KEY_PIN_LENGTH = "pin_length"
        const val DEFAULT_RELOCK_MS = 5 * 60_000L
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
