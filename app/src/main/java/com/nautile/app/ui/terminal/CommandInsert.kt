package com.nautile.app.ui.terminal

import com.termux.terminal.TerminalSession

/**
 * Puts a command (from the assistant) on the command line WITHOUT running it: nothing may reach
 * the shell as Enter until the user presses it.
 */
object CommandInsert {
    /** Escape, C1 and other control characters (but tabs and line breaks) a command never needs. */
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u0080-\\u009F]")
    private val CONTINUES = listOf("\\", "&&", "||", "|", ";", "&")
    /** Shell words after which the next line goes on without a separator (« do; » is a syntax error). */
    private val OPENS = Regex("""(?:^|[\s;])(?:do|then|else|\{|\()$|^case\s.+\sin$""")

    /**
     * [command] on a single line: line breaks would each act as Enter. Continuation lines are
     * joined with a space (a trailing backslash dropped), separate commands with « ; »; comment
     * lines are left out.
     */
    fun flatten(command: String): String {
        val lines = CONTROL.replace(command, "").replace("\r\n", "\n").replace('\r', '\n')
            .split('\n').map { it.trim() }
            // A comment line joined with « ; » would comment out every command after it.
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val out = StringBuilder()
        lines.forEachIndexed { i, line ->
            val last = i == lines.lastIndex
            val glue = CONTINUES.firstOrNull { line.endsWith(it) } ?: if (OPENS.containsMatchIn(line)) " " else null
            when {
                last -> out.append(line)
                glue == "\\" -> out.append(line.removeSuffix("\\").trimEnd()).append(' ')
                glue != null -> out.append(line).append(' ')
                else -> out.append(line).append("; ")
            }
        }
        return out.toString().trim()
    }

    /**
     * Types [command] at the cursor of [session], never followed by Enter. Always flattened to one
     * line, even when the remote program announced bracketed paste: that mode can stay set after
     * the program that asked for it is gone, and a line break would then run the command.
     */
    fun insert(session: TerminalSession, command: String) {
        val bytes = flatten(command).toByteArray()
        if (bytes.isNotEmpty()) session.write(bytes, 0, bytes.size)
    }
}
