package com.nautile.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CrashReporterTest {
    @Test fun masksWhatCouldNameTheUser() {
        val trace = """
            java.net.UnknownHostException: Unable to resolve host "nas.lan": No address associated with hostname
            	at java.net.Inet6AddressImpl.lookupHostByName(Inet6AddressImpl.java:156)
            Caused by: java.io.IOException: auth failed for root@192.168.1.2:22 (key /home/dorian/.ssh/id_ed25519) password=hunter2
            	at com.nautile.ssh.SshConnection.connect(SshConnection.kt:88)
        """.trimIndent()
        val out = CrashReporter.anonymize(trace)
        listOf("nas.lan", "192.168.1.2", "dorian", "hunter2", "root@").forEach { assertFalse(it, out.contains(it)) }
        val lines = out.lines()
        assertEquals("\tat java.net.Inet6AddressImpl.lookupHostByName(Inet6AddressImpl.java:156)", lines[1])
        assertEquals(true, lines[0].startsWith("java.net.UnknownHostException: "))
        assertEquals(true, lines[2].startsWith("Caused by: java.io.IOException: "))
    }
}
