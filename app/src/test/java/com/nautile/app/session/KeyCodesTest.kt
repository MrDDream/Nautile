package com.nautile.app.session

import android.view.KeyEvent
import com.termux.terminal.KeyCodes
import org.junit.Assert.assertEquals
import org.junit.Test

/** The emulator's own key codes must stay Android's, since Android events are passed through unchanged. */
class KeyCodesTest {
    @Test fun sameAsAndroid() {
        val android = KeyEvent::class.java.fields.filter { it.name.startsWith("KEYCODE_") }.associate { it.name to it.getInt(null) }
        val ours = KeyCodes::class.java.fields.filter { it.name.startsWith("KEYCODE_") }
        assertEquals(51, ours.size)
        ours.forEach { f -> assertEquals(f.name, android[f.name], f.getInt(null)) }
    }
}
