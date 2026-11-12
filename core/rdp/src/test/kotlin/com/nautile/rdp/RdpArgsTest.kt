package com.nautile.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpArgsTest {
    private val base = RdpConfig(host = "win.lan", width = 2401, height = 1081)

    @Test fun minimalCommandLine() {
        val a = RdpArgs.build(base, h264 = false)
        assertEquals("nautile", a.first())
        assertTrue("/v:win.lan" in a)
        assertTrue("/port:3389" in a)
        assertTrue("/size:2400x1080" in a)
        assertTrue("/gfx" in a)
        assertTrue("/sound" in a)
        assertFalse(a.any { it.startsWith("/u:") || it.startsWith("/p:") || it.startsWith("/sec:") || it.startsWith("/cert") })
    }

    @Test fun credentialsSecurityAndOptions() {
        val a = RdpArgs.build(
            base.copy(username = "alice", domain = "CORP", password = "p@ss,word", security = RdpSecurity.NLA,
                scaleDesktop = 900, audio = false, console = true, port = 13389),
            h264 = true,
        )
        assertTrue("/u:alice" in a)
        assertTrue("/d:CORP" in a)
        assertTrue("/p:p@ss,word" in a)
        assertTrue("/sec:nla" in a)
        assertTrue("/scale-desktop:500" in a)
        assertTrue("/audio-mode:2" in a)
        assertFalse("/sound" in a)
        assertTrue("/admin" in a)
        assertTrue("/port:13389" in a)
        assertTrue("/gfx:AVC444,AVC420,RFX,progressive" in a)
    }

    @Test fun qualityProfiles() {
        val fast = RdpArgs.build(base.copy(quality = RdpQuality.FAST, clipboard = false), h264 = true)
        assertTrue("/bpp:16" in fast)
        assertTrue("/network:broadband-low" in fast)
        assertTrue("-fonts" in fast)
        assertFalse("/clipboard" in fast)
        val high = RdpArgs.build(base.copy(quality = RdpQuality.HIGH), h264 = false)
        assertTrue("/network:lan" in high)
        assertTrue("+wallpaper" in high)
        assertTrue("/bpp:32" in high)
        assertTrue("/clipboard" in high)
    }

    @Test fun desktopSizeIsClamped() {
        assertEquals(640 to 480, RdpArgs.desktopSize(100, 100))
        assertEquals(1918 / 4 * 4 to 1078, RdpArgs.desktopSize(1918, 1079))
    }

    @Test fun proxyUrlEncodesCredentials() {
        assertEquals("socks5://bob:p%40ss%3Aw@proxy.lan:1080", RdpArgs.proxyUrl("socks5", "proxy.lan", 1080, "bob", "p@ss:w"))
        assertEquals("http://[fd00::1]:8080", RdpArgs.proxyUrl("http", "fd00::1", 8080, null, null))
    }
}
