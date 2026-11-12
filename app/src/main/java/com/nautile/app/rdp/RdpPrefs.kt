package com.nautile.app.rdp

import android.content.Context
import com.nautile.rdp.RdpQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How touches drive the remote mouse. */
enum class RdpTouchMode {
    /** The finger is the pointer: touch where you click. */
    DIRECT,
    /** The finger moves an arrow, like a laptop touchpad. */
    TOUCHPAD,
}

/** Defaults shared by every remote desktop session (per-host options live on the host). */
class RdpPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("nautile_rdp", Context.MODE_PRIVATE)

    private fun <E : Enum<E>> read(key: String, values: Array<E>, default: E): E =
        prefs.getString(key, null)?.let { v -> values.firstOrNull { it.name == v } } ?: default

    private val _touchMode = MutableStateFlow(read("touch_mode", RdpTouchMode.entries.toTypedArray(), RdpTouchMode.DIRECT))
    val touchMode: StateFlow<RdpTouchMode> = _touchMode.asStateFlow()
    fun setTouchMode(m: RdpTouchMode) { _touchMode.value = m; prefs.edit().putString("touch_mode", m.name).apply() }

    private val _quality = MutableStateFlow(read("quality", RdpQuality.entries.toTypedArray(), RdpQuality.AUTO))
    /** Applies to the next connection. */
    val quality: StateFlow<RdpQuality> = _quality.asStateFlow()
    fun setQuality(q: RdpQuality) { _quality.value = q; prefs.edit().putString("quality", q.name).apply() }

    private fun flag(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))
    private fun save(key: String, flow: MutableStateFlow<Boolean>, v: Boolean) { flow.value = v; prefs.edit().putBoolean(key, v).apply() }

    private val _clipboard = flag("clipboard", true)
    /** Share text both ways between the phone and the remote desktop (next connection). */
    val clipboard: StateFlow<Boolean> = _clipboard.asStateFlow()
    fun setClipboard(v: Boolean) = save("clipboard", _clipboard, v)

    private val _immersive = flag("immersive", true)
    /** Hide Android's status and navigation bars during a session. */
    val immersive: StateFlow<Boolean> = _immersive.asStateFlow()
    fun setImmersive(v: Boolean) = save("immersive", _immersive, v)

    private val _keyBar = flag("key_bar", true)
    /** Show the Ctrl/Alt/Win/arrows bar together with the keyboard. */
    val keyBar: StateFlow<Boolean> = _keyBar.asStateFlow()
    fun setKeyBar(v: Boolean) = save("key_bar", _keyBar, v)

    private val _pointer = flag("pointer", true)
    /** Draw the Windows pointer in direct touch mode (always drawn with the touchpad). */
    val pointer: StateFlow<Boolean> = _pointer.asStateFlow()
    fun setPointer(v: Boolean) = save("pointer", _pointer, v)
}
