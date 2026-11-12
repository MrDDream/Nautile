package com.nautile.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class OsDetectorTest {
    private fun out(osRelease: String, kernel: String = "Linux", extras: String = "") = "$osRelease\n@@\n$kernel\n@@\n$extras"

    @Test fun readsOsReleaseId() {
        assertEquals("ubuntu", OsDetector.parse(out("NAME=\"Ubuntu\"\nID=ubuntu\nID_LIKE=debian")))
        assertEquals("redhat", OsDetector.parse(out("ID=\"rhel\"")))
        assertEquals("mint", OsDetector.parse(out("ID=linuxmint")))
        assertEquals("alpine", OsDetector.parse(out("ID=alpine")))
    }

    @Test fun specialSystemsWin() {
        assertEquals("proxmox", OsDetector.parse(out("ID=debian", extras = "proxmox")))
        assertEquals("unraid", OsDetector.parse(out("ID=slackware", extras = "unraid")))
        assertEquals("raspbian", OsDetector.parse(out("ID=debian", extras = "Raspberry Pi 4 Model B Rev 1.4")))
    }

    @Test fun fallsBackToKernel() {
        assertEquals("macos", OsDetector.parse(out("", kernel = "Darwin")))
        assertEquals("freebsd", OsDetector.parse(out("", kernel = "FreeBSD")))
        assertEquals("linux", OsDetector.parse(out("", kernel = "Linux")))
        assertEquals("unknown", OsDetector.parse(""))
    }
}
