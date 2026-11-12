package com.nautile.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.ui.theme.Nt

/** An OS logo: a Nerd Font glyph (or short label) in the brand's color. */
data class OsBrand(val name: String, val glyph: String, val color: Color, val isGlyph: Boolean = true)

object OsBrands {
    private fun g(name: String, cp: Int, color: Long) = OsBrand(name, String(Character.toChars(cp)), Color(color))
    private fun t(name: String, label: String, color: Long) = OsBrand(name, label, Color(color), isGlyph = false)

    private val table = mapOf(
        "ubuntu" to g("Ubuntu", 0xF31B, 0xFFE95420), "kubuntu" to g("Kubuntu", 0xF333, 0xFF0079C1),
        "debian" to g("Debian", 0xF306, 0xFFD70A53), "raspbian" to g("Raspberry Pi OS", 0xF315, 0xFFC51A4A),
        "fedora" to g("Fedora", 0xF30A, 0xFF51A2DA), "centos" to g("CentOS", 0xF304, 0xFF9CCD2A),
        "redhat" to g("Red Hat", 0xF316, 0xFFEE0000), "rocky" to g("Rocky Linux", 0xF32B, 0xFF10B981),
        "almalinux" to g("AlmaLinux", 0xF31D, 0xFFFFC107), "arch" to g("Arch Linux", 0xF303, 0xFF1793D1),
        "manjaro" to g("Manjaro", 0xF312, 0xFF35BF5C), "endeavouros" to g("EndeavourOS", 0xF322, 0xFF9B6BDF),
        "artix" to g("Artix", 0xF31F, 0xFF10A0CC), "garuda" to g("Garuda", 0xF337, 0xFF9F7AEA),
        "alpine" to g("Alpine", 0xF300, 0xFF4A90C2), "opensuse" to g("openSUSE", 0xF314, 0xFF73BA25),
        "mint" to g("Linux Mint", 0xF30E, 0xFF86BE43), "popos" to g("Pop!_OS", 0xF32A, 0xFF48B9C7),
        "kali" to g("Kali", 0xF327, 0xFF367BF0), "parrot" to g("Parrot", 0xF329, 0xFF05EEFF),
        "nixos" to g("NixOS", 0xF313, 0xFF7EBAE4), "gentoo" to g("Gentoo", 0xF30D, 0xFFB7B2E6),
        "void" to g("Void", 0xF32E, 0xFF5FA36B), "elementary" to g("elementary OS", 0xF309, 0xFF64BAFF),
        "zorin" to g("Zorin", 0xF32F, 0xFF15A6F0), "slackware" to g("Slackware", 0xF318, 0xFF8B9FD8),
        "devuan" to g("Devuan", 0xF307, 0xFFA693D6), "deepin" to g("Deepin", 0xF321, 0xFF2CA7F8),
        "solus" to g("Solus", 0xF32D, 0xFF5294E2), "mageia" to g("Mageia", 0xF310, 0xFF2397D4),
        "coreos" to g("CoreOS", 0xF305, 0xFF53A3DA), "qubes" to g("Qubes", 0xF342, 0xFF63A0FF),
        "tails" to g("Tails", 0xF343, 0xFFA772D1), "postmarketos" to g("postmarketOS", 0xF374, 0xFF009900),
        "macos" to g("macOS", 0xF302, 0xFFE8EDF2), "freebsd" to g("FreeBSD", 0xF30C, 0xFFEB3B45),
        "openbsd" to g("OpenBSD", 0xF328, 0xFFF2CA30), "illumos" to g("illumos", 0xF326, 0xFFFF6347),
        "windows" to g("Windows", 0xF17A, 0xFF00A4EF), "android" to g("Android", 0xF17B, 0xFF3DDC84),
        "linux" to g("Linux", 0xF31A, 0xFFF5C518),
        "proxmox" to t("Proxmox VE", "PVE", 0xFFE57000), "unraid" to t("Unraid", "UR", 0xFFF15A2C),
        "synology" to t("Synology DSM", "DSM", 0xFF4D8FE3), "openwrt" to t("OpenWrt", "WRT", 0xFF00B5E2),
        "truenas" to t("TrueNAS", "TN", 0xFF0095D5),
    )

    /** Unknown Linux distributions fall back to Tux. */
    fun of(osId: String?): OsBrand? = osId?.let { table[it] ?: if (it != "unknown") table["linux"] else null }
}

/** Host avatar: the OS logo when known, else the alias initial in the host color. */
@Composable
fun HostAvatar(osId: String?, initial: String, hostColor: Color, size: Dp = 40.dp, corner: Dp = 11.dp, fontSize: TextUnit = 15.sp) {
    val brand = OsBrands.of(osId)
    val color = brand?.color ?: hostColor
    Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        when {
            brand == null -> Text(initial, color = color, fontWeight = FontWeight.Bold, fontSize = fontSize)
            brand.isGlyph -> Text(brand.glyph, color = color, fontFamily = Nt.Mono, fontSize = fontSize * 1.45f)
            else -> Text(brand.glyph, color = color, fontWeight = FontWeight.Bold, fontSize = fontSize * 0.7f)
        }
    }
}
