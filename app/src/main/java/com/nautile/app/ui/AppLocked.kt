package com.nautile.app.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * True while the app is locked (MainActivity provides it). The lock screen is an activity of its own
 * above the app, so nothing here can be reached meanwhile; windows that appear by themselves (prompts
 * from a session) are still better left out while it is true, so that they never flash on the way back.
 */
val LocalAppLocked = compositionLocalOf { false }
