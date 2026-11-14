package com.nautile.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17 (API 37) blocks connections to the local network (192.168.x.x, 10.x.x.x, mDNS…) for an app that
 * targets it, until the user grants ACCESS_LOCAL_NETWORK (« Nearby devices »): without it, SSH, SFTP, RDP, VNC
 * and the Wi-Fi sync to a machine at home only time out.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Last known state, for messages built off any context (connection errors). */
    @Volatile
    var denied: Boolean = false
        private set

    /** True when this Android version needs the permission and it is not granted. Updates [denied]. */
    fun needsPermission(context: Context): Boolean {
        val needed = Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED
        denied = needed
        return needed
    }
}
