package com.nautile.app.ui.hosts

import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import org.junit.Assert.assertEquals
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
        assertEquals(listOf("Homelab", "VPS", HostsViewModel.UNGROUPED), s.groupNames)
    }

    @Test fun filtersByQueryAndGroup() {
        assertEquals(listOf("docker-01"), HostsViewModel.build(hosts, groups, "dock", null).sections.flatMap { it.hosts }.map { it.alias })
        assertEquals(listOf("VPS"), HostsViewModel.build(hosts, groups, "", "VPS").sections.map { it.title })
    }

    @Test fun addressHidesDefaultPort() {
        assertEquals("root@192.168.1.10", hosts[0].address)
        assertEquals("vps.example.org:2222", hosts[2].address)
    }

    @Test fun detectsCsvVersusSshConfig() {
        assertEquals("a", HostEditViewModel.parseImport("alias,hostname\na,1.2.3.4").single().alias)
        assertEquals("b", HostEditViewModel.parseImport("Host b\n  HostName 1.2.3.4").single().alias)
    }

    @Test fun relativeTimeBuckets() {
        val now = 1_000_000_000_000L
        assertEquals("il y a 2 min", relativeTime(now - 2 * 60_000, now))
        assertEquals("hier", relativeTime(now - 30 * 3_600_000L, now))
        assertTrue(relativeTime(now - 40L * 86_400_000, now).endsWith("mois"))
    }
}
