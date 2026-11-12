package com.nautile.app.ui.hosts

import com.nautile.data.model.Host
import com.nautile.data.model.Protocol
import com.nautile.data.model.defaultPort

/** A machine typed in the search bar to connect to without saving it. */
data class QuickTarget(val protocol: Protocol, val username: String, val hostname: String, val port: Int) {
    /** `user@host[:port]` as typed back to the user. */
    val label: String
        get() = buildString {
            if (username.isNotEmpty()) append(username).append('@')
            append(if (':' in hostname) "[$hostname]" else hostname)
            if (port != protocol.defaultPort) append(':').append(port)
        }

    /** A host that is never saved (id 0). */
    fun toHost() = Host(alias = label, hostname = hostname, port = port, username = username, protocol = protocol)
}

/** Reads `root@192.168.1.2`, `host:2222`, `rdp://admin@pc`, `telnet://switch`, `ssh user@host -p 22`… */
object QuickConnect {
    private val SCHEMES = mapOf("ssh" to Protocol.SSH, "mosh" to Protocol.MOSH, "telnet" to Protocol.TELNET, "rdp" to Protocol.RDP)
    private val HOST = Regex("""^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$""")
    private val USER = Regex("""^[A-Za-z0-9._\\-]+$""")

    fun parse(input: String, fallback: Protocol = Protocol.SSH): QuickTarget? {
        var text = input.trim()
        if (text.isEmpty() || ' ' in text && !text.startsWith("ssh ") && !text.startsWith("mosh ")) return null
        var protocol = fallback
        var port: Int? = null
        // `ssh [-p N] user@host [-p N]`
        if (text.startsWith("ssh ") || text.startsWith("mosh ")) {
            val words = text.split(Regex("\\s+"))
            protocol = if (words[0] == "mosh") Protocol.MOSH else Protocol.SSH
            var target: String? = null
            var i = 1
            while (i < words.size) {
                when {
                    words[i] == "-p" && i + 1 < words.size -> { port = words[i + 1].toIntOrNull() ?: return null; i++ }
                    words[i].startsWith("-") -> return null
                    target == null -> target = words[i]
                    else -> return null
                }
                i++
            }
            text = target ?: return null
        }
        Regex("""^([a-z]+)://(.*)$""", RegexOption.IGNORE_CASE).matchEntire(text)?.let { m ->
            protocol = SCHEMES[m.groupValues[1].lowercase()] ?: return null
            text = m.groupValues[2].trimEnd('/')
        }
        val user = text.substringBeforeLast('@', "")
        var rest = text.substringAfterLast('@')
        if (user.isNotEmpty() && !USER.matches(user)) return null
        val host: String
        if (rest.startsWith("[")) {
            host = rest.substringAfter('[').substringBefore(']')
            rest.substringAfter(']', "").removePrefix(":").takeIf { it.isNotEmpty() }?.let { port = it.toIntOrNull() ?: return null }
            if (!host.contains(':')) return null
        } else if (rest.count { it == ':' } == 1) {
            host = rest.substringBefore(':')
            port = rest.substringAfter(':').toIntOrNull() ?: return null
        } else if (rest.count { it == ':' } > 1) {
            host = rest // bare IPv6
        } else host = rest
        if (host.isEmpty() || (':' !in host && !HOST.matches(host))) return null
        val p = port ?: protocol.defaultPort
        if (p !in 1..65535) return null
        return QuickTarget(protocol, user, host, p)
    }

    /** Whether [input] reads as a machine to reach rather than a search word (`root@x`, `x:22`, an IP, a scheme…). */
    fun looksLikeTarget(input: String): Boolean {
        val t = input.trim()
        val q = parse(t) ?: return false
        return '@' in t || "://" in t || t.startsWith("ssh ") || t.startsWith("mosh ") || ':' in t ||
            Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(q.hostname) || q.hostname.count { it == '.' } >= 1
    }
}
