package com.nautile.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.nautile.app.widget.LaunchAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where the widget, the launcher shortcuts and the notification lead. Never shown: it notes the host asked
 * (when the intent proves it was made by this install, [LaunchAuth]) and brings the app's task to the front
 * as it was, so that a locked app stays behind its [LockActivity] (an intent aimed at [MainActivity] would
 * start a second one above the lock, or clear the lock away).
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(LaunchAuth.EXTRA_HOST_ID, -1L)
        if (savedInstanceState == null && id > 0 && LaunchAuth.verify(LaunchAuth.secret(this), id, intent.getStringExtra(LaunchAuth.EXTRA_MAC))) {
            // Connected by MainActivity once unlocked and on screen (within a minute).
            (application as NautileApp).connectRequest.ask(id)
        }
        val task = runCatching {
            getSystemService(ActivityManager::class.java)?.appTasks.orEmpty().firstOrNull { t ->
                // Annotated nullable since API 37: a task without its info starts the app afresh (below).
                val info = checkNotNull(t.taskInfo)
                info.baseActivity?.className != LaunchActivity::class.java.name
            }
        }.getOrNull()
        if (task == null || runCatching { task.moveToFront() }.isFailure) {
            startActivity(
                Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }
        finish()
    }

    companion object {
        /** Opens the app as it was left (the notification of open sessions), the lock included. */
        fun openIntent(context: Context): Intent =
            Intent(context, LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * The host a widget or shortcut asked for, kept until the app is unlocked and on screen, for [ttlMs] at most:
 * a tap left behind a lock screen that was given up never connects later by surprise. [now]: elapsed time.
 */
class ConnectRequest(private val ttlMs: Long = 60_000L, private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var id: Long? = null
    private var at = 0L
    private val _asked = MutableStateFlow(0L)

    /**
     * Bumped at each [ask]: followed while [MainActivity] is on screen, so that a host asked while it already is
     * (split screen, where no new onResume comes) is still connected.
     */
    val asked: StateFlow<Long> = _asked.asStateFlow()

    fun ask(hostId: Long) {
        synchronized(this) {
            id = hostId
            at = now()
        }
        _asked.update { it + 1 }
    }

    /** The host asked, once (null when none, or asked too long ago). */
    @Synchronized
    fun take(): Long? {
        val asked = id ?: return null
        id = null
        val age = now() - at
        return asked.takeIf { age in 0..ttlMs }
    }

    @Synchronized
    fun forget() { id = null }
}
