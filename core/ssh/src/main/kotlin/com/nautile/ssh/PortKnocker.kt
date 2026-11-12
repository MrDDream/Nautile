package com.nautile.ssh

import com.nautile.i18n.tr

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Port knocking: touches each port of a sequence before connecting, so a firewall (knockd…)
 * opens the real port. Sequence syntax: `7000,8000,9000` (TCP) — prefix a port with `u:` for UDP.
 */
object PortKnocker {
    data class Knock(val port: Int, val udp: Boolean)

    fun parse(sequence: String): List<Knock> = sequence.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { token ->
        val udp = token.startsWith("u:", ignoreCase = true)
        val port = token.removePrefix("u:").removePrefix("U:").removePrefix("t:").removePrefix("T:").toIntOrNull()
            ?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException(tr("Port de knocking invalide : %1\$s", token))
        Knock(port, udp)
    }

    fun knock(host: String, sequence: List<Knock>, delayMs: Long = 200, timeoutMs: Int = 300) {
        val address = InetAddress.getByName(host)
        for (k in sequence) {
            if (k.udp) DatagramSocket().use { it.send(DatagramPacket(ByteArray(1), 1, address, k.port)) }
            else runCatching { Socket().use { it.connect(InetSocketAddress(address, k.port), timeoutMs) } }
            Thread.sleep(delayMs)
        }
    }
}
