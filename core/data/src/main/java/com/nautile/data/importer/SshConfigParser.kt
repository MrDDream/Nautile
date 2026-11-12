package com.nautile.data.importer

import com.nautile.i18n.tr

/**
 * Reads OpenSSH `~/.ssh/config` text. Only concrete `Host` aliases are imported;
 * wildcard patterns (`*`, `?`, `!`) are applied as defaults to the hosts they match,
 * first value wins as in OpenSSH.
 */
object SshConfigParser {

    private class Block(val patterns: List<String>, val options: MutableMap<String, String> = linkedMapOf())

    fun parse(text: String): List<ImportedHost> {
        val blocks = mutableListOf<Block>()
        var current = Block(listOf("*")) // options before the first Host apply to all
        blocks += current

        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val (key, value) = splitKeyValue(line) ?: return@forEach
            when (key) {
                "host" -> {
                    current = Block(tokenize(value))
                    blocks += current
                }
                "match" -> {
                    // Match blocks are not supported: swallow their options.
                    current = Block(emptyList())
                    blocks += current
                }
                else -> current.options.putIfAbsent(key, unquote(value))
            }
        }

        val aliases = blocks.flatMap { b -> b.patterns.filter { isConcrete(it) } }.distinct()
        return aliases.map { alias ->
            val opts = linkedMapOf<String, String>()
            blocks.filter { b -> matches(b.patterns, alias) }.forEach { b ->
                b.options.forEach { (k, v) -> opts.putIfAbsent(k, v) }
            }
            ImportedHost(
                alias = alias,
                hostname = opts["hostname"]?.replace("%h", alias) ?: alias,
                port = opts[tr("port")]?.toIntOrNull() ?: 22,
                username = opts["user"].orEmpty(),
                identityFile = opts["identityfile"],
                proxyJump = opts["proxyjump"]?.split(',')?.first()?.trim()?.takeUnless { it.equals("none", true) },
            )
        }
    }

    private fun splitKeyValue(line: String): Pair<String, String>? {
        val idx = line.indexOfFirst { it == ' ' || it == '\t' || it == '=' }
        if (idx <= 0) return null
        val key = line.substring(0, idx).lowercase()
        val value = line.substring(idx).trimStart(' ', '\t', '=').trim()
        return if (value.isEmpty()) null else key to value
    }

    private fun tokenize(value: String): List<String> =
        Regex("\"([^\"]*)\"|(\\S+)").findAll(value).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    private fun unquote(v: String) = if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v.substring(1, v.length - 1) else v

    private fun isConcrete(p: String) = p.none { it == '*' || it == '?' || it == '!' }

    private fun matches(patterns: List<String>, alias: String): Boolean {
        var matched = false
        for (p in patterns) {
            val negated = p.startsWith('!')
            val glob = if (negated) p.substring(1) else p
            if (globToRegex(glob).matches(alias)) {
                if (negated) return false
                matched = true
            }
        }
        return matched
    }

    private fun globToRegex(glob: String): Regex = Regex(
        buildString {
            glob.forEach { c ->
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        },
        RegexOption.IGNORE_CASE,
    )
}
