package com.nautile.rdp

enum class RdpSecurity { AUTO, NLA, TLS, RDP }

/** Image quality against bandwidth. */
enum class RdpQuality {
    /** FreeRDP measures the link; H.264 when the server offers it. */
    AUTO,
    /** 16-bit colour, no visual effects: mobile data, slow tunnels. */
    FAST,
    /** LAN profile with wallpaper and animations. */
    HIGH,
}

/** Everything FreeRDP needs to open one desktop. */
data class RdpConfig(
    val host: String,
    val port: Int = 3389,
    val username: String = "",
    val domain: String = "",
    val password: String? = null,
    val width: Int,
    val height: Int,
    /** Windows desktop scale in percent (100–500); null keeps the server's. */
    val scaleDesktop: Int? = null,
    val security: RdpSecurity = RdpSecurity.AUTO,
    val audio: Boolean = true,
    /** Connect to the console session (`mstsc /admin`). */
    val console: Boolean = false,
    val clientName: String = "Nautile",
    /** `socks5://[user:pass@]host:port` or `http://…`; null for a direct connection. */
    val proxy: String? = null,
    val quality: RdpQuality = RdpQuality.AUTO,
    /** Text clipboard shared both ways. */
    val clipboard: Boolean = true,
    val logLevel: String = "WARN",
)

/** Builds FreeRDP's command line (wfreerdp syntax) from a [RdpConfig]. Pure, unit-tested. */
object RdpArgs {
    fun build(c: RdpConfig, h264: Boolean): List<String> = buildList {
        add("nautile")
        add("/gdi:sw")
        add("/client-hostname:${c.clientName}")
        add("/v:${c.host}")
        add("/port:${c.port}")
        if (c.username.isNotEmpty()) add("/u:${c.username}")
        if (c.domain.isNotEmpty()) add("/d:${c.domain}")
        if (!c.password.isNullOrEmpty()) add("/p:${c.password}")
        val (w, h) = desktopSize(c.width, c.height)
        add("/size:${w}x$h")
        add(if (c.quality == RdpQuality.FAST) "/bpp:16" else "/bpp:32")
        c.scaleDesktop?.let { add("/scale-desktop:${it.coerceIn(100, 500)}") }
        when (c.security) {
            RdpSecurity.NLA -> add("/sec:nla")
            RdpSecurity.TLS -> add("/sec:tls")
            RdpSecurity.RDP -> add("/sec:rdp")
            RdpSecurity.AUTO -> {}
        }
        if (c.console) add("/admin")
        when (c.quality) {
            RdpQuality.AUTO -> {
                add("/network:auto")
                add(if (h264) "/gfx:AVC444,AVC420,RFX,progressive" else "/gfx")
                add("-wallpaper"); add("-menu-anims"); add("-window-drag"); add("+fonts"); add("+themes"); add("-aero")
            }
            RdpQuality.FAST -> {
                add("/network:broadband-low")
                add(if (h264) "/gfx:AVC420,progressive" else "/gfx")
                add("-wallpaper"); add("-menu-anims"); add("-window-drag"); add("-fonts"); add("-themes"); add("-aero")
            }
            RdpQuality.HIGH -> {
                add("/network:lan")
                add(if (h264) "/gfx:AVC444,AVC420,RFX,progressive" else "/gfx")
                add("+wallpaper"); add("+menu-anims"); add("+window-drag"); add("+fonts"); add("+themes"); add("+aero")
            }
        }
        if (c.clipboard) add("/clipboard")
        add("/disp")
        if (c.audio) { add("/audio-mode:0"); add("/sound") } else add("/audio-mode:2")
        c.proxy?.let { add("/proxy:$it") }
        add("/auto-reconnect")
        add("/kbd:unicode:on")
        add("/log-level:${c.logLevel}")
    }

    /** Clamps to sizes servers accept: width a multiple of 4, even height, at least 640×480. */
    fun desktopSize(width: Int, height: Int): Pair<Int, Int> =
        (width.coerceIn(640, 8192) / 4 * 4) to height.coerceIn(480, 8192).let { it - it % 2 }

    /** `socks5://user:pass@host:port` for FreeRDP's /proxy, with the credentials percent-encoded. */
    fun proxyUrl(kind: String, host: String, port: Int, user: String?, password: String?): String = buildString {
        append(kind).append("://")
        if (!user.isNullOrEmpty()) {
            append(encode(user))
            if (!password.isNullOrEmpty()) append(':').append(encode(password))
            append('@')
        }
        append(if (':' in host && !host.startsWith("[")) "[$host]" else host).append(':').append(port)
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
