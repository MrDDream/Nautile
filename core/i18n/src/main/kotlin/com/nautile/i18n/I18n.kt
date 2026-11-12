package com.nautile.i18n

import java.util.Locale

/**
 * Minimal translation layer: the source text is French; [tr] returns the English version from
 * [EnStrings] when English is active. Formats use `%1$s` placeholders.
 */
object I18n {
    @Volatile var english: Boolean = false

    /** Strings with no English entry yet (for tests and debugging). */
    fun missing(keys: Collection<String>): List<String> = keys.filter { it !in EnStrings.map }
}

fun tr(fr: String, vararg args: Any?): String {
    val pattern = if (I18n.english) EnStrings.map[fr] ?: fr else fr
    return if (args.isEmpty()) pattern else String.format(Locale.ROOT, pattern, *args)
}
