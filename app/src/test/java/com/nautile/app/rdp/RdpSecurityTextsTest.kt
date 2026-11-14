package com.nautile.app.rdp

import com.nautile.rdp.RdpConnection
import com.nautile.rdp.RdpSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpSecurityTextsTest {
    @Test fun onlyStandardRdpSecurityIsWarnedAbout() {
        assertNotNull(RdpManager.securityWarning(RdpSecurity.RDP))
        assertNull(RdpManager.securityWarning(RdpSecurity.AUTO))
        assertNull(RdpManager.securityWarning(RdpSecurity.NLA))
        assertNull(RdpManager.securityWarning(RdpSecurity.TLS))
    }

    @Test fun clientSideFailuresAreDescribed() {
        assertEquals(
            "Le serveur demande un bureau trop grand (20000x20000)",
            RdpManager.describe("${RdpConnection.CLIENT_ERROR}: ${RdpConnection.DESKTOP_TOO_LARGE} 20000x20000"),
        )
        assertTrue(RdpManager.describe("${RdpConnection.CLIENT_ERROR}: java.lang.OutOfMemoryError").contains("OutOfMemoryError"))
    }

    @Test fun desktopSizeIsBoundedBeforeAllocation() {
        assertTrue(RdpConnection.desktopSizeAllowed(3840, 2160))
        assertTrue(RdpConnection.desktopSizeAllowed(8192, 4096))
        assertTrue(!RdpConnection.desktopSizeAllowed(8192, 8192))
        assertTrue(!RdpConnection.desktopSizeAllowed(0, 100))
        assertTrue(!RdpConnection.desktopSizeAllowed(100_000, 1))
    }
}
