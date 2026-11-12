package com.nautile.app.widget

import com.nautile.i18n.tr

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nautile.app.MainActivity
import com.nautile.app.R
import com.nautile.data.model.Host

/** Launcher shortcuts: long-press on the app icon (recent hosts) and pinned shortcuts. */
object HostShortcuts {
    private fun info(context: Context, host: Host) = ShortcutInfoCompat.Builder(context, "host-${host.id}")
        .setShortLabel(host.alias.take(24))
        .setLongLabel(tr("Se connecter à %1\$s", host.alias).take(48))
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(MainActivity.connectIntent(context, host.id))
        .build()

    /** Asks the launcher to pin a shortcut to [host]; false if unsupported. */
    fun pin(context: Context, host: Host): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context) && ShortcutManagerCompat.requestPinShortcut(context, info(context, host), null)

    /** The 4 most recently used hosts as dynamic shortcuts. */
    fun updateRecent(context: Context, hosts: List<Host>) {
        val recent = hosts.filter { it.lastConnectedAt != null }.sortedByDescending { it.lastConnectedAt }
            .take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4))
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, recent.map { info(context, it) }) }
    }
}
