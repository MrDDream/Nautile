package com.nautile.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.fragment.app.FragmentActivity
import com.nautile.app.ui.NautileRoot
import com.nautile.app.ui.LockedScreen
import com.nautile.app.ui.theme.NautileTheme

class MainActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)
    /** Unlocked once in this activity: later locks cover the app instead of tearing it down (keeps pickers' results). */
    private var everUnlocked by mutableStateOf(false)
    private var lockError by mutableStateOf<String?>(null)
    /** The biometric or PIN screen is up: leaving for it is not going to the background. */
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val lock = (application as NautileApp).appLock
        unlocked = savedInstanceState?.getBoolean(KEY_UNLOCKED) == true || !lock.enabled || !lock.available
        everUnlocked = unlocked
        applySecureFlag()
        setContent {
            NautileTheme {
                if (everUnlocked) androidx.compose.foundation.layout.Box {
                    // Kept composed while relocked so open sessions and pending file pickers survive.
                    androidx.compose.foundation.layout.Box(
                        if (unlocked) androidx.compose.ui.Modifier else androidx.compose.ui.Modifier.clearAndSetSemantics { },
                    ) { NautileRoot() }
                    if (!unlocked) androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize()
                            // Swallow every touch so nothing reaches the app underneath.
                            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
                    ) { LockedScreen(error = lockError, onUnlock = ::requestUnlock) }
                } else LockedScreen(error = lockError, onUnlock = ::requestUnlock)
            }
        }
        if (!unlocked) requestUnlock()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val id = intent?.getLongExtra(EXTRA_HOST_ID, -1L) ?: -1L
        if (id > 0) {
            (application as NautileApp).pendingConnect.value = id
            intent?.removeExtra(EXTRA_HOST_ID)
        }
    }

    override fun onStart() {
        super.onStart()
        applySecureFlag()
        // Coming back to the app: fetch what the other devices changed meanwhile.
        val app = application as NautileApp
        val last = app.backupPrefs.lastSync.value ?: 0L
        if (app.backupPrefs.syncEnabled.value && System.currentTimeMillis() - last > 2 * 60_000L) app.sync.requestSoon(500)
        val lock = (application as NautileApp).appLock
        if (unlocked && lock.shouldRelock()) {
            unlocked = false
            requestUnlock()
        }
    }

    override fun onStop() {
        super.onStop()
        if (unlocked && !prompting && !isChangingConfigurations) (application as NautileApp).appLock.onBackground()
    }

    /** Blocks screenshots and blanks the recent-apps thumbnail when asked in Settings. */
    fun applySecureFlag() {
        if ((application as NautileApp).appLock.hideInRecents) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, unlocked)
    }

    private fun requestUnlock() {
        lockError = null
        prompting = true
        (application as NautileApp).appLock.prompt(
            this,
            onSuccess = { prompting = false; unlocked = true; everUnlocked = true },
            onFailure = { prompting = false; lockError = it },
        )
    }

    companion object {
        private const val KEY_UNLOCKED = "unlocked"
        private const val EXTRA_HOST_ID = "host_id"

        /** Opens the app and connects to [hostId] (widget, launcher shortcuts). */
        fun connectIntent(context: android.content.Context, hostId: Long) =
            android.content.Intent(context, MainActivity::class.java)
                .setAction("com.nautile.app.CONNECT.$hostId")
                .putExtra(EXTRA_HOST_ID, hostId)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
