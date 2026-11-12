package com.nautile.rdp

import android.view.KeyEvent

/** Pointer flags (MS-RDPBCGR TS_POINTER_EVENT) and Windows virtual-key codes. */
object RdpInput {
    const val PTR_MOVE = 0x0800
    const val PTR_DOWN = 0x8000
    const val PTR_LEFT = 0x1000
    const val PTR_RIGHT = 0x2000
    const val PTR_MIDDLE = 0x4000
    const val PTR_WHEEL = 0x0200
    const val PTR_WHEEL_NEGATIVE = 0x0100

    /** One wheel notch (120 units) up, or down (−120 as a 9-bit two's complement). */
    fun wheel(up: Boolean) = if (up) PTR_WHEEL or 0x0078 else PTR_WHEEL or PTR_WHEEL_NEGATIVE or 0x0088

    const val VK_BACK = 0x08
    const val VK_TAB = 0x09
    const val VK_RETURN = 0x0D
    const val VK_SHIFT = 0x10
    const val VK_CONTROL = 0x11
    const val VK_MENU = 0x12
    const val VK_ESCAPE = 0x1B
    const val VK_SPACE = 0x20
    const val VK_PRIOR = 0x21
    const val VK_NEXT = 0x22
    const val VK_END = 0x23
    const val VK_HOME = 0x24
    const val VK_LEFT = 0x25
    const val VK_UP = 0x26
    const val VK_RIGHT = 0x27
    const val VK_DOWN = 0x28
    const val VK_SNAPSHOT = 0x2C
    const val VK_INSERT = 0x2D
    const val VK_DELETE = 0x2E
    const val VK_LWIN = 0x5B
    const val VK_RWIN = 0x5C
    const val VK_APPS = 0x5D
    const val VK_F1 = 0x70
    const val VK_LSHIFT = 0xA0
    const val VK_RSHIFT = 0xA1
    const val VK_LCONTROL = 0xA2
    const val VK_RCONTROL = 0xA3
    const val VK_LMENU = 0xA4
    const val VK_RMENU = 0xA5
    const val VK_CAPITAL = 0x14

    /** Windows virtual-key code for an Android key, or null when it has none. */
    fun vk(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x41 + (keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 0x30 + (keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> VK_F1 + (keyCode - KeyEvent.KEYCODE_F1)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> 0x60 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> VK_RETURN
        KeyEvent.KEYCODE_DEL -> VK_BACK
        KeyEvent.KEYCODE_FORWARD_DEL -> VK_DELETE
        KeyEvent.KEYCODE_TAB -> VK_TAB
        KeyEvent.KEYCODE_ESCAPE -> VK_ESCAPE
        KeyEvent.KEYCODE_SPACE -> VK_SPACE
        KeyEvent.KEYCODE_DPAD_LEFT -> VK_LEFT
        KeyEvent.KEYCODE_DPAD_UP -> VK_UP
        KeyEvent.KEYCODE_DPAD_RIGHT -> VK_RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN -> VK_DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> VK_HOME
        KeyEvent.KEYCODE_MOVE_END -> VK_END
        KeyEvent.KEYCODE_PAGE_UP -> VK_PRIOR
        KeyEvent.KEYCODE_PAGE_DOWN -> VK_NEXT
        KeyEvent.KEYCODE_INSERT -> VK_INSERT
        KeyEvent.KEYCODE_SYSRQ -> VK_SNAPSHOT
        KeyEvent.KEYCODE_SHIFT_LEFT -> VK_LSHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> VK_RSHIFT
        KeyEvent.KEYCODE_CTRL_LEFT -> VK_LCONTROL
        KeyEvent.KEYCODE_CTRL_RIGHT -> VK_RCONTROL
        KeyEvent.KEYCODE_ALT_LEFT -> VK_LMENU
        KeyEvent.KEYCODE_ALT_RIGHT -> VK_RMENU
        KeyEvent.KEYCODE_META_LEFT -> VK_LWIN
        KeyEvent.KEYCODE_META_RIGHT -> VK_RWIN
        KeyEvent.KEYCODE_MENU -> VK_APPS
        KeyEvent.KEYCODE_CAPS_LOCK -> VK_CAPITAL
        KeyEvent.KEYCODE_MINUS -> 0xBD
        KeyEvent.KEYCODE_EQUALS -> 0xBB
        KeyEvent.KEYCODE_COMMA -> 0xBC
        KeyEvent.KEYCODE_PERIOD -> 0xBE
        KeyEvent.KEYCODE_SEMICOLON -> 0xBA
        KeyEvent.KEYCODE_SLASH -> 0xBF
        KeyEvent.KEYCODE_GRAVE -> 0xC0
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0xDB
        KeyEvent.KEYCODE_BACKSLASH -> 0xDC
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xDD
        KeyEvent.KEYCODE_APOSTROPHE -> 0xDE
        else -> null
    }
}
