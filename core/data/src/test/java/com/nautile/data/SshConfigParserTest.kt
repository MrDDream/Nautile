package com.nautile.data

import com.nautile.data.importer.SshConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SshConfigParserTest {
    private val config = """
        # global
        ServerAliveInterval 30

        Host vps-ovh
            HostName vps.example.org
            User debian
            Port 2222
            IdentityFile ~/.ssh/id_ed25519

        Host docker-01 nas
            HostName=192.168.1.20
            ProxyJump vps-ovh,other

        Host nas
            HostName 192.168.1.10   # first match wins: ignored
            User root

        Host *.lan "quoted alias"
            User pi

        Host *
            User fallback
            Port 22
    """.trimIndent()

    @Test fun importsOnlyConcreteAliases() {
        val hosts = SshConfigParser.parse(config)
        assertEquals(listOf("vps-ovh", "docker-01", "nas", "quoted alias"), hosts.map { it.alias })
    }

    @Test fun readsOptionsWithFirstValueWinning() {
        val h = SshConfigParser.parse(config).associateBy { it.alias }
        h.getValue("vps-ovh").let {
            assertEquals("vps.example.org", it.hostname)
            assertEquals("debian", it.username)
            assertEquals(2222, it.port)
            assertEquals("~/.ssh/id_ed25519", it.identityFile)
            assertNull(it.proxyJump)
        }
        h.getValue("docker-01").let {
            assertEquals("192.168.1.20", it.hostname)
            assertEquals("vps-ovh", it.proxyJump)
            assertEquals("fallback", it.username)
        }
        assertEquals("192.168.1.20", h.getValue("nas").hostname)
        assertEquals("root", h.getValue("nas").username)
        assertEquals("pi", h.getValue("quoted alias").username)
        assertEquals("quoted alias", h.getValue("quoted alias").hostname)
    }
}
