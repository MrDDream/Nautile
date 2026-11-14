package com.nautile.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class CleartextGuardTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private fun private(s: String) = CleartextGuard.isPrivate(ip(s))

    @Test fun localAddressesMayUsePlainHttp() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.10", "169.254.3.4", "100.64.0.1", "100.127.1.1", "::1", "fd12:3456::1", "fe80::1")
            .forEach { assertTrue(it, private(it)) }
    }

    @Test fun internetAddressesMayNot() {
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "100.128.0.1", "100.63.255.255", "2606:4700::1111", "1.1.1.1")
            .forEach { assertFalse(it, private(it)) }
    }

    @Test fun aGlobalIpv6OfTheHomeNetworkIsLocal() {
        // The phone on a home network: 2a01:cb00:1234:5600::42/64 (and 192.168.1.20/24).
        val nets = listOf(CleartextGuard.Net(ip("2a01:cb00:1234:5600::42"), 64), CleartextGuard.Net(ip("192.168.1.20"), 24))
        assertTrue(CleartextGuard.onNetwork(ip("2a01:cb00:1234:5600:1e2f:3a4b:5c6d:7e8f"), nets))
        assertFalse(CleartextGuard.onNetwork(ip("2a01:cb00:1234:5601::1"), nets))
        assertFalse(CleartextGuard.onNetwork(ip("2606:4700::1111"), nets))
        assertFalse(CleartextGuard.onNetwork(ip("8.8.8.8"), nets))
    }

    @Test fun prefixesOfAnyLength() {
        val net = CleartextGuard.Net(ip("203.0.113.70"), 26)
        assertTrue(CleartextGuard.inPrefix(ip("203.0.113.64"), net))
        assertTrue(CleartextGuard.inPrefix(ip("203.0.113.127"), net))
        assertFalse(CleartextGuard.inPrefix(ip("203.0.113.128"), net))
        // An address family never matches the other's prefix.
        assertFalse(CleartextGuard.inPrefix(ip("::ffff:1"), net))
    }

    @Test fun tooWideAPrefixIsNotALocalNetwork() {
        assertFalse(CleartextGuard.inPrefix(ip("2a01::1"), CleartextGuard.Net(ip("2a01::2"), 16)))
        assertFalse(CleartextGuard.inPrefix(ip("8.8.8.8"), CleartextGuard.Net(ip("8.1.1.1"), 8)))
        assertFalse(CleartextGuard.inPrefix(ip("8.8.8.8"), CleartextGuard.Net(ip("0.0.0.0"), 0)))
    }

    @Test fun localAddressesAreTriedFirst() {
        // nas.fritz.box: the global IPv6 first, as the resolver answers.
        val answer = listOf(ip("2606:4700::1111"), ip("192.168.178.20"), ip("2606:4700::1112"), ip("10.0.0.2"))
        val sorted = CleartextGuard.localFirst(answer, CleartextGuard::isPrivate)
        assertEquals(listOf(ip("192.168.178.20"), ip("10.0.0.2"), ip("2606:4700::1111"), ip("2606:4700::1112")), sorted)
    }
}
