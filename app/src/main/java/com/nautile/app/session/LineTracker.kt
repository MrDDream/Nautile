package com.nautile.app.session

/**
 * Follows what the user types to know the command line being edited and the commands run.
 *
 * Plain typing is tracked exactly. Once the line is edited in ways we can't follow (Tab completion,
 * arrows, history…) the line is read back from the screen at Enter, after the prompt. A line is only
 * committed if it is visible on screen, so text the server does not echo (passwords) is never kept.
 */
class LineTracker(
    /** The text of the cursor's screen row, read at Enter. */
    private val screenLine: () -> String?,
    private val onCommit: (String) -> Unit,
) {
    private val buffer = StringBuilder()
    private var dirty = false
    private var escape = false

    /** The line typed so far, or null when it can't be known (after edits we don't follow). */
    val current: String? get() = if (dirty) null else buffer.toString()

    fun onInput(text: String) {
        for (ch in text) {
            when {
                escape -> { if (ch in '@'..'~' && ch != '[' && ch != 'O') escape = false }
                ch == '\r' || ch == '\n' -> commit()
                ch == '\u001b' -> { escape = true; dirty = true }
                ch == '\u007f' || ch == '\b' -> if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
                ch == '\u0003' || ch == '\u0015' -> reset()
                ch < ' ' -> dirty = true
                else -> buffer.append(ch)
            }
        }
    }

    private fun commit() {
        val screen = screenLine()?.trimEnd().orEmpty()
        val line = if (!dirty) buffer.toString().trim().takeIf { it.isNotEmpty() && screen.contains(it) }
        else afterPrompt(screen)
        reset()
        if (!line.isNullOrBlank() && line.length <= 500) onCommit(line)
    }

    private fun reset() {
        buffer.setLength(0); dirty = false; escape = false
    }

    companion object {
        private val PROMPT_END = Regex("""(?:[$#%>❯➜λ»]||)\s+""")

        /** Text after the last prompt marker of a screen line, if one is found. */
        fun afterPrompt(line: String): String? {
            val m = PROMPT_END.findAll(line).lastOrNull() ?: return null
            return line.substring(m.range.last + 1).trim().takeIf { it.isNotEmpty() }
        }
    }
}
