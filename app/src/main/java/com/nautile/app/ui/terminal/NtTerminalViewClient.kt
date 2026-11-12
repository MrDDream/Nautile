package com.nautile.app.ui.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.nautile.app.session.TerminalPrefs
import kotlin.math.roundToInt

/** Sticky CTRL / ALT from the extra-keys row: active for the next key only. */
class ModifierKeys {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun consumeCtrl(): Boolean = ctrl.also { if (it) ctrl = false }
    fun consumeAlt(): Boolean = alt.also { if (it) alt = false }
}

class NtTerminalViewClient(
    private val prefs: TerminalPrefs,
    private val modifiers: ModifierKeys,
) : TerminalViewClient {
    var view: TerminalView? = null
    /** Called when the user taps this terminal (focuses its pane in split screen). */
    var onTap: () -> Unit = {}
    private var pendingScale = 1f

    fun showKeyboard() {
        val v = view ?: return
        v.requestFocus()
        v.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, 0)
    }

    /** Sends a key as if typed, honouring sticky modifiers and cursor-key modes. */
    fun sendKey(keyCode: Int) {
        val v = view ?: return
        v.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        v.onKeyUp(keyCode, KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /** Writes raw text (escape sequences, tmux prefixes…) to the session, ignoring sticky modifiers. */
    fun sendRaw(text: String) {
        val session = view?.currentSession ?: return
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    /** Pastes text (bracketed paste when the remote program asked for it). */
    fun paste(text: String) {
        view?.currentSession?.emulator?.paste(text)
    }

    fun hideKeyboard() {
        val v = view ?: return
        v.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    fun sendChar(c: Char) {
        view?.inputCodePoint(c.code, false, false)
    }

    override fun onScale(scale: Float): Float {
        // Termux accumulates the factor; apply it in steps so text stays crisp.
        if (scale < 0.9f || scale > 1.1f) {
            val size = (prefs.fontSize.value * scale).roundToInt()
            prefs.setFontSize(size)
            view?.setTextSize(prefs.fontSize.value)
            return 1f
        }
        pendingScale = scale
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) { onTap(); showKeyboard() }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey() = modifiers.consumeCtrl()
    override fun readAltKey() = modifiers.consumeAlt()
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
