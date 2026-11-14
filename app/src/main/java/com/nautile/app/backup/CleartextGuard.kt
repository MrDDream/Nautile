package com.nautile.app.backup

import com.nautile.i18n.tr
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Plain HTTP only towards the local network (a NAS, Ollama on a PC, a VPN address): anywhere else
 * passwords, API keys and terminal contents would cross the Internet unencrypted. Android's network
 * security config cannot express address ranges, so the check is here, as an OkHttp network
 * interceptor: it sees the address actually connected to (after DNS, on every redirect) and runs
 * before anything is sent. Pair it with [dns], which tries the local addresses of a name first.
 */
object CleartextGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.isHttps) {
            val route = chain.connection()?.route()
            val direct = route == null || route.proxy.type() == java.net.Proxy.Type.DIRECT
            val address = route?.socketAddress?.address
            if (!direct || address == null || !isLocal(address)) {
                throw IOException(tr("Connexion non chiffrée (http) refusée vers %1\$s : utilisez https, ou une adresse du réseau local.", request.url.host))
            }
        }
        return chain.proceed(request)
    }

    /**
     * Loopback, private ranges (RFC 1918, IPv6 ULA), link-local, the CGNAT range VPNs such as Tailscale use,
     * and any address inside the prefix of one of the device's own networks (a home network's global IPv6).
     */
    fun isLocal(a: InetAddress): Boolean = isPrivate(a) || onNetwork(a, deviceNetworks())

    internal fun isPrivate(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress) return true
        val b = a.address
        return when (a) {
            is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }

    /** A network the device is on: one of its addresses and the prefix length of that network. */
    internal data class Net(val address: InetAddress, val prefix: Int)

    /** Prefixes shorter than these are not a local network (a whole provider's range at most). */
    private const val MIN_PREFIX_V4 = 16
    private const val MIN_PREFIX_V6 = 48

    internal fun onNetwork(a: InetAddress, nets: List<Net>): Boolean = nets.any { inPrefix(a, it) }

    internal fun inPrefix(a: InetAddress, net: Net): Boolean {
        val x = a.address
        val n = net.address.address
        if (x.size != n.size) return false
        val min = if (x.size == 4) MIN_PREFIX_V4 else MIN_PREFIX_V6
        if (net.prefix < min || net.prefix > x.size * 8) return false
        val full = net.prefix / 8
        for (i in 0 until full) if (x[i] != n[i]) return false
        val rest = net.prefix % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (x[full].toInt() and mask) == (n[full].toInt() and mask)
    }

    private fun deviceNetworks(): List<Net> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.interfaceAddresses.orEmpty() }
            .mapNotNull { ia -> ia.address?.let { Net(it, ia.networkPrefixLength.toInt()) } }
    }.getOrDefault(emptyList())

    /**
     * The system's DNS with the local addresses first: OkHttp tries them in order, and the interceptor's refusal
     * of a plain-HTTP connection to a public address ends the call instead of moving to the next address.
     */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = localFirst(Dns.SYSTEM.lookup(hostname), ::isLocal)
    }

    /** Stable: the order within the local addresses, and within the others, is kept. */
    internal fun localFirst(addresses: List<InetAddress>, local: (InetAddress) -> Boolean): List<InetAddress> {
        val (near, far) = addresses.partition(local)
        return near + far
    }
}
