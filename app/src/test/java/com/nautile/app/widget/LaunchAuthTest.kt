package com.nautile.app.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchAuthTest {
    private val secret = ByteArray(32) { it.toByte() }

    @Test fun eachHostHasItsOwnProof() {
        val mac = LaunchAuth.mac(secret, 7)
        assertTrue(LaunchAuth.verify(secret, 7, mac))
        // What a launcher reads in host 7's intent opens host 7 only.
        assertFalse(LaunchAuth.verify(secret, 8, mac))
        assertNotEquals(mac, LaunchAuth.mac(secret, 8))
        // Nor does it reveal the secret, nor work with another install's.
        assertFalse(mac.contains(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret)))
        assertFalse(LaunchAuth.verify(ByteArray(32), 7, mac))
    }

    @Test fun missingOrDamagedProofsAreRefused() {
        val mac = LaunchAuth.mac(secret, 7)
        assertFalse(LaunchAuth.verify(secret, 7, null))
        assertFalse(LaunchAuth.verify(secret, 7, ""))
        assertFalse(LaunchAuth.verify(secret, 7, mac.dropLast(1)))
        assertFalse(LaunchAuth.verify(secret, 7, mac.dropLast(1) + (if (mac.last() == 'A') 'B' else 'A')))
        assertFalse(LaunchAuth.verify(secret, 0, LaunchAuth.mac(secret, 0)))
    }
}
