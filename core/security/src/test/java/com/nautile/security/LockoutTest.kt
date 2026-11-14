package com.nautile.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockoutTest {
    private val wait = 30_000L

    @Test fun theWaitRunsOnTheElapsedClock() {
        val s = Lockout.start(wait, nowRt = 1_000_000, nowBoot = 7)
        assertEquals(wait, Lockout.remaining(s, 1_000_000, 7).first)
        assertEquals(10_000L, Lockout.remaining(s, 1_020_000, 7).first)
        assertEquals(0L, Lockout.remaining(s, 1_040_000, 7).first)
    }

    @Test fun changingTheWallClockDoesNothing() {
        // Only the elapsed-time clock is read: moving the date forward cannot shorten the wait.
        val s = Lockout.start(wait, nowRt = 5_000, nowBoot = 1)
        assertEquals(wait - 1, Lockout.remaining(s, 5_001, 1).first)
    }

    @Test fun aRebootStartsTheFullWaitAgain() {
        val s = Lockout.start(wait, nowRt = 9_000_000, nowBoot = 3)
        // Another boot, uptime already past the old deadline.
        val (left, kept) = Lockout.remaining(s, 9_100_000, 4)
        assertEquals(wait, left)
        assertEquals(9_100_000 + wait, kept.deadlineRt)
        assertEquals(4, kept.boot)
        assertEquals(5_000L, Lockout.remaining(kept, 9_125_000, 4).first)
    }

    @Test fun aRebootIsSeenWithoutBootCountToo() {
        val s = Lockout.start(wait, nowRt = 9_000_000, nowBoot = -1)
        // Uptime restarted near zero: the deadline is further away than the whole wait.
        val (left, kept) = Lockout.remaining(s, 2_000, -1)
        assertEquals(wait, left)
        assertEquals(2_000 + wait, kept.deadlineRt)
    }

    @Test fun aWaitThatIsOverIsForgottenAndNotRearmedByTheNextBoot() {
        val s = Lockout.start(wait, nowRt = 1_000_000, nowBoot = 7)
        val (left, kept) = Lockout.remaining(s, 1_040_000, 7)
        assertEquals(0L, left)
        assertEquals(Lockout.CLEARED, kept)
        // Next boot: nothing to wait for any more.
        assertEquals(0L, Lockout.remaining(kept, 5_000, 8).first)
    }

    @Test fun theRelockDelayRunsOnTheElapsedClock() {
        val t = RelockTimer()
        assertFalse(t.onStart(10_000, 60_000))
        t.onStop(10_000, unlocked = true, changingConfig = false)
        assertFalse(t.onStart(69_999, 60_000))
        t.onStop(10_000, unlocked = true, changingConfig = false)
        assertTrue(t.onStart(70_000, 60_000))
        // Decided once per return.
        t.onStop(70_000, unlocked = false, changingConfig = false)
        assertFalse(t.onStart(80_000, 60_000))
        // A clock that went back (another boot) asks.
        t.onStop(10_000, unlocked = true, changingConfig = false)
        assertTrue(t.onStart(5_000, 60_000))
        t.onStop(10_000, unlocked = true, changingConfig = false)
        assertTrue(t.onStart(10_000, 0))
        // A rotation is not leaving the app.
        t.onStop(10_000, unlocked = true, changingConfig = true)
        assertFalse(t.onStart(10_000, 0))
    }

    @Test fun switchingBetweenTwoTasksOfTheAppIsNotLeavingIt() {
        val t = RelockTimer()
        t.onStart(0, 60_000)
        // B comes on screen before A stops.
        t.onStart(1_000, 60_000)
        t.onStop(1_000, unlocked = true, changingConfig = false)
        assertFalse(t.onStart(500_000, 60_000))
    }

    @Test fun aSecondActivityCannotLeaveTheFirstOneUnlocked() {
        val delay = 60_000L
        val p = ProcessLock()
        // A: unlocked, then the app goes to the background.
        p.onCreate(0, delay)
        p.unlocked = true
        p.onStart(0, delay)
        var aUnlocked = true
        p.onStop(1_000, changingConfig = false)
        // Another app starts a second MainActivity (B) once the delay is over: B locks…
        p.onCreate(100_000, delay)
        val bUnlocked = p.unlocked
        assertFalse(bUnlocked)
        p.onStart(100_000, delay)
        assertFalse(p.mustLock(bUnlocked, lockOn = true))
        p.onStop(100_500, changingConfig = false)
        // …and so does A when it comes back, although B already took the return to the foreground.
        p.onStart(101_000, delay)
        assertTrue(p.mustLock(aUnlocked, lockOn = true))
        aUnlocked = false
        assertFalse(p.mustLock(aUnlocked, lockOn = true))
        // Unlocked again (lock screen): nothing to lock.
        p.unlocked = true
        assertFalse(p.mustLock(true, lockOn = true))
    }

    @Test fun backBeforeTheDelayStaysUnlocked() {
        val p = ProcessLock()
        p.unlocked = true
        p.onStart(0, 60_000)
        p.onStop(1_000, changingConfig = false)
        p.onCreate(30_000, 60_000)
        p.onStart(30_000, 60_000)
        assertTrue(p.unlocked)
        assertFalse(p.mustLock(true, lockOn = true))
        // With the lock off, nothing locks either.
        p.unlocked = false
        assertFalse(p.mustLock(true, lockOn = false))
    }

    @Test fun noWaitBeforeTheFreeTries() {
        assertEquals(0L, Lockout.remaining(Lockout.start(0, 100, 1), 100, 2).first)
    }

    @Test fun filesAreSealedAndOldPlainFilesStayReadable() {
        val key = ByteArray(32) { it.toByte() }
        val c = FileCipher { key }
        val plain = "[{\"request\":\"ls\"}]".toByteArray()
        val sealed = c.seal(plain)
        assertTrue(FileCipher.isSealed(sealed))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("request"))
        assertArrayEquals(plain, c.open(sealed))
        assertArrayEquals(plain, c.open(plain))
        // Another key, or a tampered file: unreadable, not a crash.
        assertNull(FileCipher { ByteArray(32) }.open(sealed))
        assertNull(c.open(sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }))
        assertNull(FileCipher { null }.open(sealed))
    }

    @Test fun aKeyUnreachableForNowIsToldApartFromAFileThatWillNeverOpen() {
        val key = ByteArray(32) { it.toByte() }
        val sealed = FileCipher { key }.seal("x".toByteArray())
        assertEquals(FileCipher.Opened.Unavailable, FileCipher { null }.read(sealed))
        assertEquals(FileCipher.Opened.Unreadable, FileCipher(keyLost = { true }) { null }.read(sealed))
        assertEquals(FileCipher.Opened.Unreadable, FileCipher { ByteArray(32) }.read(sealed))
        assertTrue(FileCipher { null }.read("plain".toByteArray()) is FileCipher.Opened.Plain)
    }
}
