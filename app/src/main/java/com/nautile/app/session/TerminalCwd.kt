package com.nautile.app.session

import com.termux.terminal.TerminalEmulator

/**
 * Where the shell of a terminal currently is, as far as it can be told without asking it:
 * OSC 7, then the window title Debian/Ubuntu's bash sets (« user@host: ~/dir »), then a classic
 * prompt (« user@host:~/dir$ ») on the cursor line. Null when none of them says.
 */
object TerminalCwd {
    private val TITLE = Regex("^[^\\s:]+@[^\\s:]+:\\s*(~.*|/.*)$")
    private val PROMPT = Regex("[\\w.-]+@[\\w.-]+:(~[^\\s$#]*|/[^\\s$#]*)\\s*[$#]")

    fun of(e: TerminalEmulator?): String? {
        e ?: return null
        e.currentDirectory?.takeIf { it.isNotBlank() }?.let { return it }
        e.title?.trim()?.let { t -> TITLE.find(t)?.groupValues?.get(1)?.trim()?.let { return it } }
        val line = runCatching { e.screen.getSelectedText(0, e.cursorRow, e.mColumns, e.cursorRow) }.getOrNull().orEmpty()
        return PROMPT.findAll(line).lastOrNull()?.groupValues?.get(1)
    }

    /** « ~ » and « ~/x » made absolute with [home]. */
    fun expand(path: String, home: String): String = when {
        path == "~" -> home
        path.startsWith("~/") -> home.trimEnd('/') + path.drop(1)
        else -> path
    }
}
