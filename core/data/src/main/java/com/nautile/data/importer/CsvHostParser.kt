package com.nautile.data.importer

import com.nautile.i18n.tr

/**
 * Reads a CSV export with a header row. Recognised columns (case-insensitive, any order):
 * `alias|label|name`, `hostname|host|address`, `port`, `username|user`, `group`, `tags`.
 * Tags inside a cell are separated by `;` or `|`.
 */
object CsvHostParser {

    fun parse(text: String): List<ImportedHost> {
        val rows = parseRows(text).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = header.indexOfFirst { it in names }
        val iAlias = col(tr("alias"), "label", "name")
        val iHost = col("hostname", "host", "address")
        val iPort = col(tr("port"))
        val iUser = col("username", "user")
        val iGroup = col("group")
        val iTags = col(tr("tags"))
        require(iHost >= 0) { tr("Colonne « hostname » introuvable") }

        return rows.drop(1).mapNotNull { row ->
            fun cell(i: Int) = if (i >= 0) row.getOrNull(i)?.trim().orEmpty() else ""
            val host = cell(iHost)
            if (host.isEmpty()) return@mapNotNull null
            ImportedHost(
                alias = cell(iAlias).ifEmpty { host },
                hostname = host,
                port = cell(iPort).toIntOrNull() ?: 22,
                username = cell(iUser),
                group = cell(iGroup).ifEmpty { null },
                tags = cell(iTags).split(';', '|').map { it.trim() }.filter { it.isNotEmpty() },
            )
        }
    }

    /** RFC 4180-style: quoted fields, doubled quotes, commas and newlines inside quotes. */
    internal fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row += field.toString(); field.clear() }
                '\r' -> {}
                '\n' -> { row += field.toString(); field.clear(); rows += row; row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows
    }
}
