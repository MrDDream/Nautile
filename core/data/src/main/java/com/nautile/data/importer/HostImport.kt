package com.nautile.data.importer

/** A file of hosts picked by the user: a CSV export or an OpenSSH config, told apart by its first line. */
object HostImport {
    /** Byte order mark that Windows tools (Excel « CSV UTF-8 », Notepad) put at the start of the file. */
    const val BOM = "\uFEFF"

    fun parse(input: String): List<ImportedHost> {
        val text = input.removePrefix(BOM)
        return if (looksCsv(text)) CsvHostParser.parse(text) else SshConfigParser.parse(text)
    }

    /** A header row naming the address column, separated by `,` or `;`, and not a `Host` line nor a `#` comment. */
    fun looksCsv(text: String): Boolean {
        val firstLine = text.removePrefix(BOM).lineSequence()
            .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }.orEmpty().lowercase()
        return (',' in firstLine || ';' in firstLine) && HEADER.containsMatchIn(firstLine) && !firstLine.trimStart().startsWith("host ")
    }

    private val HEADER = Regex("\\b(hostname|host|address)\\b")
}
