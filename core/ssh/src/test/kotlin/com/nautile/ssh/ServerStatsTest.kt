package com.nautile.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerStatsTest {
    private val sample = """
        dwserver
        @@
        Linux 6.8.0-45-generic
        @@
        8
        @@
        cpu  1000 0 500 8000 500 0 0 0 0 0
        cpu  1060 0 520 8100 520 0 0 0 0 0
        @@
        0.52 0.61 0.70 1/523 12345
        @@
        MemTotal:       16000000 kB
        MemFree:         2000000 kB
        MemAvailable:    8000000 kB
        SwapTotal:       4000000 kB
        SwapFree:        3000000 kB
        @@
        /dev/nvme0n1p2  480000000 120000000 336000000  27% /
        @@
        93784.21 700000.00
        @@
            PID %CPU %MEM COMMAND
           1234 12.5  3.1 jellyfin
            987  4.0  1.2 dockerd
    """.trimIndent()

    @Test fun parsesEverything() {
        val s = ServerStats.parse(sample)
        assertEquals("dwserver", s.hostname)
        assertEquals(8, s.cores)
        assertEquals(40.0, s.cpuPercent!!, 0.01) // 80 busy of 200 jiffies
        assertEquals(0.52, s.load1, 0.001)
        assertEquals(50.0, s.memUsedPercent, 0.01)
        assertEquals(25.0, s.diskUsedPercent, 0.01)
        assertEquals(93784, s.uptimeSeconds)
        assertEquals(listOf("jellyfin", "dockerd"), s.topProcesses.map { it.command })
        assertEquals(12.5, s.topProcesses[0].cpu, 0.001)
    }
}
