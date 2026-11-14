package com.nautile.app

import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.nautile.app.ui.LockedScreen
import com.nautile.app.ui.theme.NautileTheme
import com.nautile.i18n.tr
import com.nautile.security.LockMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The lock, as an activity of its own above [MainActivity] in the same task. Everything of the app
 * underneath (its dialogs, menus and sheets, which are windows of their own, and the keyboard focus)
 * is out of reach while it is shown, and stays alive: open sessions, pending file pickers.
 * Back leaves the app (in the background, still locked); there is no way past it but unlocking.
 */
class LockActivity : FragmentActivity() {
    private var lockError by mutableStateOf<String?>(null)
    /** The stored code cannot be decrypted: read off the main thread ([readUnreadable]), kept here. */
    private var unreadable by mutableStateOf(false)
    /** Typed codes the Keystore could not check, in a row: past [UNAVAILABLE_TRIES], the phone's own lock is offered. */
    private var unavailable by mutableIntStateOf(0)
    private val keystoreDown get() = unavailable >= UNAVAILABLE_TRIES
    /** Built at each creation (see [com.nautile.security.AppLock.Unlocker]): after a rotation the prompt answers this one. */
    private lateinit var unlocker: com.nautile.security.AppLock.Unlocker
    /** A new key each time it asks: the typed digits and messages start afresh. */
    private var lockCount by mutableIntStateOf(0)
    private val lock get() = (application as NautileApp).appLock
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing of the app behind it either: no screenshot, a blank preview in the recent apps.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!lockNeeded()) {
            leave()
            return
        }
        enableEdgeToEdge()
        unavailable = savedInstanceState?.getInt(KEY_UNAVAILABLE) ?: 0
        unlocker = lock.unlocker(this, deviceLock = { unreadable || keystoreDown }, onSuccess = ::onUnlocked, onFailure = { lockError = it })
        onBackPressedDispatcher.addCallback(this) {
            // Given up: a widget's or shortcut's host asked meanwhile is not connected later.
            (application as NautileApp).connectRequest.forget()
            moveTaskToBack(true)
        }
        setContent { NautileTheme { Lock() } }
        if (savedInstanceState == null) requestUnlock() else readUnreadable()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_UNAVAILABLE, unavailable)
    }

    override fun onResume() {
        super.onResume()
        // Unlocked by a prompt that answered an instance destroyed meanwhile (rotation).
        if (!lockNeeded()) leave()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!lockNeeded()) leave()
    }

    override fun onStart() {
        super.onStart()
        // Unlocked meanwhile (another lock screen of this process), or the lock was switched off.
        if (!lockNeeded()) leave()
    }

    private fun lockNeeded() = !lock.unlockedInProcess && lock.enabled && lock.available

    @androidx.compose.runtime.Composable
    private fun Lock() {
        key(lockCount) {
            LockedScreen(
                method = lock.method,
                error = lockError ?: when {
                    unreadable -> unreadableMessage()
                    keystoreDown -> keystoreDownMessage()
                    else -> null
                },
                canBiometric = lock.method != LockMethod.BIOMETRIC && (lock.asksBiometric(unreadable) || (keystoreDown && lock.biometricAvailable)),
                onBiometric = ::biometric,
                check = { lock.check(it) },
                pinLength = lock.pinLength,
                initialWaitUntil = System.currentTimeMillis() + lock.waitMs(),
                onUnlocked = ::onUnlocked,
                onUnavailable = {
                    unavailable++
                    // The phone's lock opens instead, as for a code that can no longer be read.
                    if (unavailable == UNAVAILABLE_TRIES && lock.biometricAvailable) biometric()
                    if (keystoreDown) keystoreDownMessage() else null
                },
            )
        }
    }

    private fun unreadableMessage() =
        if (lock.biometricAvailable) tr("Le code ne peut plus être vérifié sur cet appareil : déverrouillez avec l'empreinte ou le code du téléphone.")
        else tr("Le code ne peut plus être vérifié et le téléphone n'a pas de verrouillage : effacez les données de Nautile dans les réglages d'Android.")

    /** The Keystore keeps failing: the phone's lock opens when there is one; otherwise a restart, or a documented way out. */
    private fun keystoreDownMessage() =
        if (lock.biometricAvailable) tr("Le Keystore d'Android ne répond toujours pas : déverrouillez avec l'empreinte ou le code du téléphone.")
        else tr("Le Keystore d'Android ne répond toujours pas : redémarrez le téléphone. Si cela persiste, effacez les données de Nautile (Réglages Android › Applications › Nautile › Stockage) puis restaurez une sauvegarde.")

    private fun requestUnlock() {
        lockError = null
        lockCount++
        readUnreadable { if (lock.asksBiometric(it)) biometric() }
    }

    /**
     * Reads once whether the stored code can still be decrypted: a Keystore read, slow (seconds) when it
     * fails, so never on the main thread nor at each recomposition.
     */
    private fun readUnreadable(then: (Boolean) -> Unit = {}) {
        lifecycleScope.launch {
            val u = withContext(Dispatchers.IO) { runCatching { lock.codeUnreadable }.getOrDefault(false) }
            unreadable = u
            then(u)
        }
    }

    private fun biometric() {
        lockError = null
        unlocker.authenticate()
    }

    private fun onUnlocked() {
        lock.unlockedInProcess = true
        leave()
    }

    /** Back to the app underneath (started first if this lock was left alone in its task). */
    private fun leave() {
        if (leaving) return
        leaving = true
        // No animation either way: the app underneath simply shows again.
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java), ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private companion object {
        const val KEY_UNAVAILABLE = "keystore_unavailable"
        /** Codes the Keystore could not check in a row before the phone's lock is offered instead. */
        const val UNAVAILABLE_TRIES = 2
    }
}
