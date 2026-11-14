package com.nautile.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectRequestTest {
    private var now = 1_000L
    private val request = ConnectRequest(ttlMs = 60_000L) { now }

    @Test fun takenOnceWithinAMinute() {
        request.ask(7)
        now += 59_000
        assertEquals(7L, request.take())
        assertNull(request.take())
    }

    @Test fun anOldTapNeverConnectsLater() {
        request.ask(7)
        now += 60_001
        assertNull(request.take())
    }

    @Test fun givingUpAtTheLockScreenForgetsIt() {
        request.ask(7)
        request.forget()
        assertNull(request.take())
        // The latest tap wins.
        request.ask(3)
        request.ask(4)
        assertEquals(4L, request.take())
    }

    @Test fun eachAskIsSignalled() {
        val before = request.asked.value
        request.ask(7)
        request.ask(7)
        assertEquals(before + 2, request.asked.value)
        assertEquals(7L, request.take())
    }
}
