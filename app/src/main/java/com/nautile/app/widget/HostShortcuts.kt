package com.nautile.app.widget

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nautile.app.R
import com.nautile.data.model.Host
import com.nautile.i18n.tr

/** Launcher shortcuts: long-press on the app icon (recent hosts) and pinned shortcuts. */
object HostShortcuts {
    private fun info(context: Context, host: Host) = ShortcutInfoCompat.Builder(context, "host-${host.id}")
        .setShortLabel(host.alias.take(24))
        .setLongLabel(tr("Se connecter à %1\$s", host.alias).take(48))
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(LaunchAuth.connectIntent(context, host.id))
        .build()

    /** Asks the launcher to pin a shortcut to [host]; false if unsupported. */
    fun pin(context: Context, host: Host): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context) && ShortcutManagerCompat.requestPinShortcut(context, info(context, host), null)

    @Volatile private var pinnedRefreshed = false

    /** Shortcuts pinned by older versions still wait for their current intent ([refreshPinned]). */
    val pinnedPending: Boolean get() = !pinnedRefreshed

    /** The 4 most recently used hosts as dynamic shortcuts (call it off the main thread). */
    fun updateRecent(context: Context, hosts: List<Host>) {
        val recent = hosts.filter { it.lastConnectedAt != null }.sortedByDescending { it.lastConnectedAt }
            .take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4))
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, recent.map { info(context, it) }) }
        refreshPinned(context, hosts)
    }

    /**
     * Once per start: shortcuts pinned by older versions get the current intent (their old token is no longer
     * accepted: without it they only open the app). Android may refuse it in the background (rate limit):
     * then it is tried again, from MainActivity.onResume (call it off the main thread).
     */
    fun refreshPinned(context: Context, hosts: List<Host>) {
        if (pinnedRefreshed) return
        runCatching {
            val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }.toSet()
            val update = hosts.filter { "host-${it.id}" in pinned }.map { info(context, it) }
            if (update.isEmpty() || ShortcutManagerCompat.updateShortcuts(context, update)) pinnedRefreshed = true
        }
    }
}
