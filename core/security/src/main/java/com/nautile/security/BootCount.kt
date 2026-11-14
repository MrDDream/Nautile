package com.nautile.security

import android.content.Context
import android.provider.Settings

/** How many times the phone has started ([Settings.Global.BOOT_COUNT]), -1 when unknown. */
fun bootCount(context: Context): Int =
    runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
