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
    /** The rest of the line, up to Enter, is a secret typed for the user. */
    private var secret = false

    /** The line typed so far, or null when it can't be known (after edits we don't follow). */
    val current: String? get() = if (dirty) null else buffer.toString()

    /** What is typed up to the next Enter (a password sent for the user) is neither followed nor kept. */
    fun suppressNextLine() {
        reset()
        secret = true
    }

    fun onInput(text: String) {
        for (ch in text) {
            if (secret) {
                if (ch == '\r' || ch == '\n') reset()
                continue
            }
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
        val line = if (!dirty) buffer.toString().trim().takeIf { it.isNotEmpty() && echoed(screen, it) }
        else afterPrompt(screen)?.takeIf { !asksForSecret(screen.removeSuffix(it)) }
        reset()
        if (!line.isNullOrBlank() && line.length <= 500) onCommit(line)
    }

    private fun reset() {
        buffer.setLength(0); dirty = false; escape = false; secret = false
    }

    companion object {
        private val PASSWORD_WORDS = listOf("password", "passphrase", "mot de passe", "passwort", "contraseña", "senha", "パスワード")

        /** True when [line] (a screen row) looks like a password prompt: sudo, su, ssh, mysql… */
        fun looksLikePasswordPrompt(line: String): Boolean {
            val l = line.lowercase()
            return PASSWORD_WORDS.any { it in l }
        }

        /** A password word on its own, not inside a path or a name (« ~/password-store », « feat/passwords »). */
        private val STANDALONE_PASSWORD_WORD = Regex(
            PASSWORD_WORDS.joinToString("|", "(?<![\\w/.~-])(?:", ")(?![\\w/.-])") { Regex.escape(it) },
            RegexOption.IGNORE_CASE,
        )

        /**
         * True when [prefix], the part of [row] before the typed text, asks for a secret. The words
         * are looked for in the end of the prompt (after its last marker: a folder or a branch named
         * « password-store » earlier in it does not count), and on their own anywhere in it. Without
         * a prompt marker the whole row is looked at: the typed text may be part of the question.
         */
        internal fun asksForSecret(prefix: String, row: String = prefix): Boolean {
            val marker = PROMPT_END.findAll(prefix).lastOrNull()
            val tail = if (marker == null) row else prefix.substring(marker.range.last + 1)
            return looksLikePasswordPrompt(tail) || STANDALONE_PASSWORD_WORD.containsMatchIn(prefix)
        }

        /**
         * True when [typed] shows on the cursor's row as an echo, after something that does not ask
         * for a secret: the row ends with it; or it follows a prompt marker and the rest of the row
         * is a right prompt (zsh RPROMPT, powerlevel10k, starship) or a greyed suggestion (fish,
         * zsh-autosuggestions); or, for a wrapped line, the row holds its end. Text typed at a prompt
         * that does not echo can still match the prompt itself (« password for dorian: »).
         */
        internal fun echoed(screen: String, typed: String): Boolean {
            if (screen.endsWith(typed)) return !asksForSecret(screen.removeSuffix(typed), screen)
            for (m in PROMPT_END.findAll(screen)) {
                val start = m.range.last + 1
                if (screen.startsWith(typed, start) && !asksForSecret(screen.substring(0, start))) return true
            }
            return typed.length > screen.length && screen.isNotEmpty() && typed.endsWith(screen)
        }

        private val PROMPT_END = Regex("""(?:[$#%>❯➜λ»]||)\s+""")

        /** Text after the last prompt marker of a screen line, if one is found. */
        fun afterPrompt(line: String): String? {
            val m = PROMPT_END.findAll(line).lastOrNull() ?: return null
            return line.substring(m.range.last + 1).trim().takeIf { it.isNotEmpty() }
        }
    }
}
