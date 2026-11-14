package com.nautile.security

/**
 * The wait after too many wrong codes, measured on the elapsed-time clock (which the user cannot set),
 * not the wall clock. That clock restarts at boot: a deadline from another boot means the full wait
 * starts again.
 */
internal object Lockout {
    /** A deadline [deadlineRt] for a wait of [waitMs], set during boot [boot] (-1 when unknown). */
    data class State(val deadlineRt: Long, val waitMs: Long, val boot: Int)

    /**
     * What is left of [s] at [nowRt] during boot [nowBoot], and the state to keep (re-armed after a reboot).
     */
    fun remaining(s: State, nowRt: Long, nowBoot: Int): Pair<Long, State> {
        if (s.waitMs <= 0 || s.deadlineRt <= 0) return 0L to s
        val rebooted = (s.boot != -1 && nowBoot != -1 && s.boot != nowBoot) ||
            // Without a boot count: a deadline further away than the whole wait can only come from an earlier boot.
            s.deadlineRt - nowRt > s.waitMs
        if (rebooted) return s.waitMs to State(nowRt + s.waitMs, s.waitMs, nowBoot)
        val left = s.deadlineRt - nowRt
        // Over: forgotten, or the next boot would start it again.
        return if (left <= 0) 0L to CLEARED else left to s
    }

    /** No wait. */
    val CLEARED = State(0, 0, -1)

    fun start(waitMs: Long, nowRt: Long, nowBoot: Int) = State(if (waitMs > 0) nowRt + waitMs else 0, waitMs, nowBoot)
}

/**
 * How long the app has been in the background, on the elapsed-time clock: setting the date back
 * must not reopen it without asking. Counts the app's activities on screen, so that the time starts
 * only when the last one leaves (switching between two of its tasks is not leaving the app).
 */
internal class RelockTimer {
    private var started = 0
    private var since: Long? = null

    /** An activity of the app comes on screen at [nowRt]: whether the lock is due (decided once per return). */
    @Synchronized
    fun onStart(nowRt: Long, delayMs: Long): Boolean {
        started++
        return consume(nowRt, delayMs)
    }

    /** Whether the lock is due, for an activity being created (its [onStart] comes next). */
    @Synchronized
    fun due(nowRt: Long, delayMs: Long): Boolean = consume(nowRt, delayMs)

    /** An activity leaves the screen; [unlocked]: the app was unlocked; [changingConfig]: it is recreated at once. */
    @Synchronized
    fun onStop(nowRt: Long, unlocked: Boolean, changingConfig: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && unlocked && !changingConfig) since = nowRt
    }

    private fun consume(nowRt: Long, delayMs: Long): Boolean {
        val s = since ?: return false
        since = null
        // A clock that went back can only mean another boot: ask.
        return nowRt < s || nowRt - s >= delayMs
    }
}

/**
 * Whether the app is unlocked, for the whole process (never saved: a new process asks again). The lock is
 * decided here, not by each activity: when the delay is over, the process is locked, and every activity
 * that still shows the app unlocked must lock ([mustLock]), whichever of them came back first.
 */
internal class ProcessLock {
    @Volatile var unlocked = false
    private val timer = RelockTimer()

    fun onCreate(nowRt: Long, delayMs: Long) { if (timer.due(nowRt, delayMs)) unlocked = false }

    fun onStart(nowRt: Long, delayMs: Long) { if (timer.onStart(nowRt, delayMs)) unlocked = false }

    fun onStop(nowRt: Long, changingConfig: Boolean) = timer.onStop(nowRt, unlocked, changingConfig)

    /** An activity showing the app ([activityUnlocked]) must cover it: the process is locked and the lock is on. */
    fun mustLock(activityUnlocked: Boolean, lockOn: Boolean) = activityUnlocked && !unlocked && lockOn
}
