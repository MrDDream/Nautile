package com.nautile.app.ui.hosts

import com.nautile.data.model.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickConnectTest {
    @Test fun userAtHost() {
        assertEquals(QuickTarget(Protocol.SSH, "root", "192.168.1.2", 22), QuickConnect.parse("root@192.168.1.2"))
        assertEquals(QuickTarget(Protocol.SSH, "", "nas.lan", 2222), QuickConnect.parse("nas.lan:2222"))
    }

    @Test fun schemesAndCommandLine() {
        assertEquals(QuickTarget(Protocol.RDP, "admin", "pc-bureau", 3389), QuickConnect.parse("rdp://admin@pc-bureau"))
        assertEquals(QuickTarget(Protocol.TELNET, "", "switch", 23), QuickConnect.parse("telnet://switch/"))
        assertEquals(QuickTarget(Protocol.SSH, "me", "host.example", 2200), QuickConnect.parse("ssh -p 2200 me@host.example"))
        assertEquals(QuickTarget(Protocol.MOSH, "me", "box", 22), QuickConnect.parse("mosh me@box"))
        assertEquals(QuickTarget(Protocol.SSH, "u", "fd00::1", 2022), QuickConnect.parse("u@[fd00::1]:2022"))
    }

    @Test fun rejectsNonsense() {
        assertNull(QuickConnect.parse("hello world"))
        assertNull(QuickConnect.parse("root@"))
        assertNull(QuickConnect.parse("host:99999"))
        assertNull(QuickConnect.parse("ftp://host"))
    }

    @Test fun searchWordsAreNotTargets() {
        assertFalse(QuickConnect.looksLikeTarget("docker"))
        assertTrue(QuickConnect.looksLikeTarget("root@docker"))
        assertTrue(QuickConnect.looksLikeTarget("10.0.0.5"))
        assertTrue(QuickConnect.looksLikeTarget("nas.lan"))
    }

    @Test fun labelHidesTheDefaultPort() {
        assertEquals("root@10.0.0.5", QuickConnect.parse("root@10.0.0.5")!!.label)
        assertEquals("pc:3390", QuickConnect.parse("rdp://pc:3390")!!.label)
    }
}
