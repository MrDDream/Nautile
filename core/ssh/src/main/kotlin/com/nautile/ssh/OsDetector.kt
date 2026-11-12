package com.nautile.ssh

/** Identifies a server's operating system (distribution) from one SSH command, for its logo. */
object OsDetector {
    const val COMMAND = "cat /etc/os-release 2>/dev/null; echo @@; uname -s 2>/dev/null; echo @@; " +
        "[ -e /etc/unraid-version ] && echo unraid; command -v pveversion >/dev/null 2>&1 && echo proxmox; " +
        "[ -e /etc.defaults/VERSION ] && echo synology; [ -e /etc/openwrt_release ] && echo openwrt; " +
        "[ -e /etc/truenas-version ] || command -v midclt >/dev/null 2>&1 && echo truenas; " +
        "[ -r /proc/device-tree/model ] && tr -d '\\000' < /proc/device-tree/model; true"

    private val ALIASES = mapOf(
        "rhel" to "redhat", "ol" to "redhat", "linuxmint" to "mint", "pop" to "popos", "sles" to "opensuse", "sled" to "opensuse",
        "opensuse-leap" to "opensuse", "opensuse-tumbleweed" to "opensuse", "amzn" to "linux", "archarm" to "arch",
        "endeavouros" to "endeavouros", "rocky" to "rocky", "almalinux" to "almalinux", "kali" to "kali",
    )

    /** Known ids: see the app's logo table; unknown Linux distributions give their own id. */
    fun parse(output: String): String {
        val parts = output.split("@@").map { it.trim() }
        val osRelease = parts.getOrElse(0) { "" }.lines().associate { l ->
            l.substringBefore('=').trim() to l.substringAfter('=', "").trim().trim('"', '\'')
        }
        val kernel = parts.getOrElse(1) { "" }.lowercase()
        val extras = parts.getOrElse(2) { "" }.lowercase()
        when {
            "unraid" in extras.lines() -> return "unraid"
            "proxmox" in extras.lines() -> return "proxmox"
            "synology" in extras.lines() -> return "synology"
            "openwrt" in extras.lines() -> return "openwrt"
            "truenas" in extras.lines() -> return "truenas"
        }
        val id = osRelease["ID"]?.lowercase()?.takeIf { it.isNotEmpty() }
        if (id != null) {
            if ((id == "debian" || id == "raspbian") && "raspberry pi" in extras) return "raspbian"
            return ALIASES[id] ?: id
        }
        return when {
            kernel.startsWith("darwin") -> "macos"
            kernel.startsWith("freebsd") -> "freebsd"
            kernel.startsWith("openbsd") -> "openbsd"
            kernel.startsWith("linux") -> "linux"
            kernel.contains("mingw") || kernel.contains("msys") || kernel.contains("cygwin") -> "windows"
            else -> "unknown"
        }
    }
}
