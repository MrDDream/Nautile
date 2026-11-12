package com.nautile.data.importer

/** A host read from an external source, before it is saved. */
data class ImportedHost(
    val alias: String,
    val hostname: String,
    val port: Int = 22,
    val username: String = "",
    val identityFile: String? = null,
    /** Alias of the jump host (first hop of ProxyJump). */
    val proxyJump: String? = null,
    val group: String? = null,
    val tags: List<String> = emptyList(),
)
