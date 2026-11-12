package com.nautile.data

import com.nautile.data.importer.CsvHostParser
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvHostParserTest {
    @Test fun parsesHeaderInAnyOrderWithQuotes() {
        val csv = "Host,Label,Port,User,Group,Tags\r\n" +
            "192.168.1.10,nas-unraid,,root,Homelab,\"nas;storage\"\r\n" +
            "\n" +
            "vps.example.org,\"vps, \"\"ovh\"\"\",2222,debian,VPS,prod|web\n" +
            ",empty-host,22,x,,\n"
        val hosts = CsvHostParser.parse(csv)
        assertEquals(2, hosts.size)
        hosts[0].let {
            assertEquals("nas-unraid", it.alias)
            assertEquals(22, it.port)
            assertEquals("Homelab", it.group)
            assertEquals(listOf("nas", "storage"), it.tags)
        }
        hosts[1].let {
            assertEquals("vps, \"ovh\"", it.alias)
            assertEquals(2222, it.port)
            assertEquals(listOf("prod", "web"), it.tags)
        }
    }

    @Test fun aliasDefaultsToHostname() {
        val hosts = CsvHostParser.parse("hostname\npi.lan")
        assertEquals("pi.lan", hosts.single().alias)
    }
}
