package com.nautile.app.ui.hosts

import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsLogicTest {
    private val groups = listOf(HostGroup(1, "Homelab"), HostGroup(2, "VPS"))
    private val hosts = listOf(
        Host(1, "nas", "192.168.1.10", username = "root", groupId = 1),
        Host(2, "docker-01", "192.168.1.20", groupId = 1, tags = "docker"),
        Host(3, "vps-ovh", "vps.example.org", port = 2222, groupId = 2),
        Host(4, "pihole", "192.168.1.53"),
    )

    @Test fun groupsInOrderWithUngroupedLast() {
        val s = HostsViewModel.build(hosts, groups, "", null)
        assertEquals(listOf("Homelab", "VPS", HostsViewModel.UNGROUPED), s.sections.map { it.title })
        assertEquals(listOf(1L, 2L, HostsViewModel.NO_GROUP), s.groupFilters)
        assertEquals(mapOf(1L to 2, 2L to 1, HostsViewModel.NO_GROUP to 1), s.counts)
    }

    @Test fun filtersByQueryAndGroup() {
        assertEquals(listOf("docker-01"), HostsViewModel.build(hosts, groups, "dock", null).sections.flatMap { it.hosts }.map { it.alias })
        assertEquals(listOf("VPS"), HostsViewModel.build(hosts, groups, "", 2L).sections.map { it.title })
        assertEquals(listOf("pihole"), HostsViewModel.build(hosts, groups, "", HostsViewModel.NO_GROUP).sections.single().hosts.map { it.alias })
        // A selected group that no longer exists: back to « Tous ».
        val gone = HostsViewModel.build(hosts, groups, "", 9L)
        assertNull(gone.selectedGroup)
        assertEquals(3, gone.sections.size)
    }

    @Test fun aGroupCalledLikeTheUngroupedSectionKeepsUniqueKeys() {
        val clash = groups + HostGroup(3, HostsViewModel.UNGROUPED) + HostGroup(4, "quick")
        val list = hosts + Host(5, "x", "x", groupId = 3) + Host(6, "y", "y", groupId = 4)
        val s = HostsViewModel.build(list, clash, "", null)
        assertEquals(listOf("Homelab", "VPS", HostsViewModel.UNGROUPED, "quick", HostsViewModel.UNGROUPED), s.sections.map { it.title })
        assertEquals(s.sections.size, s.sections.map { it.key }.toSet().size)
        assertTrue(s.sections.none { it.key == "quick" })
        assertEquals(listOf("x"), HostsViewModel.build(list, clash, "", 3L).sections.single().hosts.map { it.alias })
    }

    @Test fun jumpCandidatesExcludeCycles() {
        val chain = listOf(
            Host(1, "a", "a", jumpHostId = 2),
            Host(2, "b", "b", jumpHostId = 3),
            Host(3, "c", "c"),
            Host(4, "d", "d"),
            Host(5, "rdp", "r", protocol = com.nautile.data.model.Protocol.RDP),
        )
        // c is reached through a then b: a and b would make a loop, c itself is excluded.
        assertEquals(listOf("d"), HostEditViewModel.jumpCandidates(chain, 3).map { it.alias })
        assertEquals(listOf("b", "c", "d"), HostEditViewModel.jumpCandidates(chain, 1).map { it.alias })
        assertEquals(listOf("a", "b", "c", "d"), HostEditViewModel.jumpCandidates(chain, 0).map { it.alias })
    }

    @Test fun addressHidesDefaultPort() {
        assertEquals("root@192.168.1.10", hosts[0].address)
        assertEquals("vps.example.org:2222", hosts[2].address)
    }

    @Test fun relativeTimeBuckets() {
        val now = 1_000_000_000_000L
        assertEquals("il y a 2 min", relativeTime(now - 2 * 60_000, now))
        assertEquals("hier", relativeTime(now - 30 * 3_600_000L, now))
        assertTrue(relativeTime(now - 40L * 86_400_000, now).endsWith("mois"))
    }

    @Test fun shortRelativeTimeDropsTheIlYa() {
        val now = 1_000_000_000_000L
        assertEquals("22 min", relativeTime(now - 22 * 60_000, now, short = true))
        assertEquals("3 h", relativeTime(now - 3 * 3_600_000L, now, short = true))
        assertEquals("hier", relativeTime(now - 30 * 3_600_000L, now, short = true))
    }

    @Test fun linksToDeletedRecordsAreDroppedBeforeSaving() {
        val h = Host(1, "web", "10.0.0.1", keyId = 4, identityId = 5, jumpHostId = 6)
        val kept = HostEditViewModel.withoutMissingLinks(h, keyExists = true, identityExists = true, jumpExists = true)
        assertEquals(h, kept)
        val gone = HostEditViewModel.withoutMissingLinks(h, keyExists = false, identityExists = true, jumpExists = false)
        assertEquals(Triple(null, 5L, null), Triple(gone.keyId, gone.identityId, gone.jumpHostId))
    }
}
