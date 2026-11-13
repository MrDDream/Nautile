package com.nautile.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockSecretTest {
    @Test fun verifiesOnlyTheRightCode() {
        val stored = LockSecret.hash("482916", iterations = 1_000)
        assertTrue(LockSecret.verify("482916", stored))
        assertFalse(LockSecret.verify("482917", stored))
        assertFalse(LockSecret.verify("", stored))
        assertFalse("the code itself is never stored", stored.contains("482916"))
    }

    @Test fun saltedEachTime() {
        assertNotEquals(LockSecret.hash("1234", 1_000), LockSecret.hash("1234", 1_000))
    }

    @Test fun rejectsGarbage() {
        assertFalse(LockSecret.verify("1234", "nope"))
        assertFalse(LockSecret.verify("1234", "pbkdf2-sha256:x:y:z"))
    }

    @Test fun waitsLongerAfterRepeatedMistakes() {
        assertEquals(0L, LockSecret.lockoutMs(4))
        assertEquals(30_000L, LockSecret.lockoutMs(5))
        assertEquals(60_000L, LockSecret.lockoutMs(6))
        assertEquals(15 * 60_000L, LockSecret.lockoutMs(30))
    }

    @Test fun pinAndPasswordRules() {
        assertTrue(LockSecret.validPin("1234"))
        assertFalse(LockSecret.validPin("123"))
        assertFalse(LockSecret.validPin("12a4"))
        assertFalse(LockSecret.validPin("123456789"))
        assertTrue(LockSecret.validPassword("abcdef"))
        assertFalse(LockSecret.validPassword("abc"))
    }
}
