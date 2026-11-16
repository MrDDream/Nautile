package com.nautile.data

import com.nautile.data.backup.BackupData
import com.nautile.data.model.Host
import com.nautile.data.model.Protocol
import com.nautile.data.model.Snippet
import com.nautile.data.model.SyncTombstone
import com.nautile.data.sync.SyncPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPartsTest {
    private fun data(alias: String, snippet: String) = BackupData(
        hosts = listOf(Host(alias = alias, hostname = "10.0.0.2")),
        snippets = listOf(Snippet(name = snippet, command = "ls")),
        tombstones = listOf("host", "snippet", "port_forward").map { SyncTombstone("$alias-$it", it, 1) },
    )

    @Test fun aThemeLeftOutIsNeitherTakenNorSent() {
        val d = SyncPart.without(data("nas", "logs"), setOf(SyncPart.SNIPPETS))
        assertTrue(d.snippets.isEmpty())
        assertEquals(1, d.hosts.size)
        assertEquals(listOf("host", "port_forward"), d.tombstones.map { it.kind })
    }

    @Test fun localShellsAndSerialPortsStayOnTheirDevice() {
        val mixed = data("nas", "logs").let { d ->
            d.copy(hosts = d.hosts + Host(alias = "shell", hostname = "", protocol = Protocol.LOCAL) + Host(alias = "router", hostname = "", protocol = Protocol.SERIAL))
        }
        assertEquals(listOf("nas"), SyncPart.without(mixed, emptySet()).hosts.map { it.alias })
        assertEquals(listOf("nas"), SyncPart.carry(mixed, data("pc", "x"), emptySet()).hosts.map { it.alias })
    }

    @Test fun leavingHostsOutLeavesTheirTunnelsOut() {
        assertEquals(setOf(SyncPart.HOSTS, SyncPart.TUNNELS), SyncPart.effective(setOf(SyncPart.HOSTS)))
        val d = SyncPart.without(data("nas", "logs"), setOf(SyncPart.HOSTS))
        assertTrue(d.hosts.isEmpty())
        assertEquals(listOf("snippet"), d.tombstones.map { it.kind })
    }

    @Test fun theSharedFileKeepsWhatTheOthersWroteOfAThemeLeftOut() {
        val mine = SyncPart.carry(data("pc", "mine"), data("nas", "theirs"), setOf(SyncPart.SNIPPETS))
        assertEquals("pc", mine.hosts.single().alias)
        assertEquals("theirs", mine.snippets.single().name)
        assertEquals(listOf("nas-snippet"), mine.tombstones.filter { it.kind == "snippet" }.map { it.syncId })
        // Nothing left out: nothing changes.
        val same = data("pc", "mine")
        assertEquals(same, SyncPart.carry(same, data("nas", "theirs"), emptySet()))
    }
}
