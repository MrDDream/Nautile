package com.nautile.app

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nautile.app.ui.LocalAppLocked
import com.nautile.app.ui.NautileRoot
import com.nautile.app.ui.StartupErrorScreen
import com.nautile.app.ui.theme.NautileTheme
import com.nautile.app.ui.theme.Nt
import com.nautile.app.widget.LaunchAuth

/**
 * The app. While it is locked, [LockActivity] covers it (a window of its own above all of this one's,
 * dialogs included); this activity also blanks its content, swallows keys and drops the focus.
 */
class MainActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)
    /** Unlocked once in this activity: later locks cover the app instead of tearing it down (keeps pickers' results). */
    private var everUnlocked by mutableStateOf(false)
    /** Why the database cannot be opened now (the Keystore failing), shown instead of the app. */
    private var databaseError by mutableStateOf<Throwable?>(null)
    /** The database is open: the app may show (opened off the main thread, see [openDatabase]). */
    private var databaseReady by mutableStateOf(false)
    /** Opening (or starting afresh) is under way. */
    private var opening by mutableStateOf(false)
    private var openJob: Job? = null

    private val app get() = application as NautileApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Intents of older versions carried the launch token: no longer accepted (see LaunchActivity).
        stripConnect(intent)
        val lock = app.appLock
        // The app stayed in the background too long: the whole process locks, before this one decides how to show.
        lock.onActivityCreated()
        // Never restored from savedInstanceState: after the process was killed in the background, a new one asks again.
        if (lock.unlockedInProcess || !lock.enabled || !lock.available) markUnlocked()
        applySecureFlag()
        // Switching the lock on also hides the app in the recent apps (by default): apply it before leaving.
        lock.addListener(lockChanged)
        // A host asked by a widget or shortcut while this is already on screen (split screen: no new onResume).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { app.connectRequest.asked.collect { deliverPending() } }
        }
        setContent {
            NautileTheme {
                CompositionLocalProvider(LocalAppLocked provides !unlocked) { Content() }
            }
        }
    }

    @Composable
    private fun Content() {
        val error = databaseError
        when {
            !everUnlocked -> Cover()
            error != null -> if (unlocked) StartupErrorScreen(
                error,
                busy = opening,
                onRetry = { openDatabase() },
                onStartAfresh = { openDatabase(afresh = true) },
            ) else Cover()
            !databaseReady -> Box(contentAlignment = Alignment.Center) {
                Cover()
                if (unlocked) Opening()
            }
            else -> Box {
                // Kept composed while relocked so open sessions and pending file pickers survive.
                Box(if (unlocked) Modifier else Modifier.clearAndSetSemantics { }) { NautileRoot() }
                if (!unlocked) Cover()
            }
        }
    }

    /** Opaque, takes every touch: what shows behind the lock screen. */
    @Composable
    private fun Cover() {
        Box(
            Modifier.fillMaxSize().background(Nt.Bg)
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        )
    }

    /** Shown only when opening the database takes long (the Keystore slow to answer). */
    @Composable
    private fun Opening() {
        var show by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(400); show = true }
        if (show) CircularProgressIndicator(color = Nt.Accent, modifier = Modifier.size(36.dp))
    }

    /**
     * Opens the database off the main thread (the Keystore may take seconds to answer, or fail: never an ANR),
     * as a launch on screen: the only one that may set lost data aside. [afresh]: the user chose to start an
     * empty database.
     */
    private fun openDatabase(afresh: Boolean = false) {
        if (openJob?.isActive == true) return
        if (!afresh && app.databaseOpen) {
            databaseError = null
            databaseReady = true
            return
        }
        opening = true
        openJob = lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { if (afresh) app.startAfresh() else app.openDatabaseOrError(foreground = true) }
            opening = false
            databaseError = error
            databaseReady = error == null
            if (error == null) {
                deliverPending()
                if (unlocked && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) app.refreshPinnedShortcuts()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        stripConnect(intent)
    }

    private fun stripConnect(intent: Intent?) {
        intent?.removeExtra(LaunchAuth.EXTRA_HOST_ID)
        intent?.removeExtra(LEGACY_EXTRA_TOKEN)
    }

    /** The host asked by a widget or shortcut, connected only once unlocked, on screen, with the database open. */
    private fun deliverPending() {
        if (!unlocked || databaseError != null || !databaseReady) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        // Asked a minute ago at most: an old tap never connects later by surprise.
        val id = app.connectRequest.take() ?: return
        app.pendingConnect.value = id
    }

    override fun onResume() {
        super.onResume()
        // After onStart has decided whether to lock again.
        deliverPending()
        // Pinned shortcuts refreshed in the background may have been refused (rate limit): again while on screen.
        if (unlocked && databaseReady) app.refreshPinnedShortcuts()
    }

    override fun onStart() {
        super.onStart()
        applySecureFlag()
        // Coming back to the app: fetch what the other devices changed meanwhile (the backup settings read
        // secrets from the Keystore the first time: off the main thread).
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val prefs = app.backupPrefs
                val last = prefs.lastSync.value ?: 0L
                if (prefs.syncEnabled.value && System.currentTimeMillis() - last > 2 * 60_000L) app.sync.requestSoon(500)
            }
        }
        val lock = app.appLock
        // Locks the process when the app stayed in the background too long. Every MainActivity then locks,
        // whichever came back first (another app may start a second one in a task of its own).
        lock.onForeground()
        when {
            // Unlocked by the lock screen (or the lock was switched off) while this was covered.
            !unlocked && (lock.unlockedInProcess || !lock.enabled || !lock.available) -> markUnlocked()
            lock.mustLock(unlocked) -> lockNow()
        }
        if (!unlocked) showLock()
    }

    private fun markUnlocked() {
        val first = !everUnlocked
        unlocked = true
        everUnlocked = true
        app.appLock.unlockedInProcess = true
        if (first || databaseError != null) openDatabase()
    }

    private fun lockNow() {
        unlocked = false
        app.appLock.unlockedInProcess = false
        // A physical keyboard would otherwise still type into the focused terminal.
        currentFocus?.let { v ->
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
        }
    }

    private fun showLock() {
        startActivity(Intent(this, LockActivity::class.java), ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
    }

    /**
     * Nothing typed reaches the app while it is locked (the lock screen is another activity).
     * RestrictedApi: a false positive on overriding the framework's Activity methods, which androidx marks.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (!unlocked) true else super.dispatchKeyEvent(event)

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = if (!unlocked) true else super.dispatchKeyShortcutEvent(event)

    private val lockChanged: () -> Unit = { runOnUiThread { applySecureFlag() } }

    override fun onDestroy() {
        app.appLock.removeListener(lockChanged)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // The delay runs once no MainActivity is on screen and the app is unlocked (covered by the lock screen, it is locked already).
        app.appLock.onBackground(isChangingConfigurations)
    }

    /** Blocks screenshots and blanks the recent-apps thumbnail when asked in Settings. */
    fun applySecureFlag() {
        if (app.appLock.hideInRecents) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private companion object {
        /** The raw launch token older widget and shortcut intents carried. */
        const val LEGACY_EXTRA_TOKEN = "token"
    }
}
