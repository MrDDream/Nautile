package com.nautile.ssh

/** One hop of a connection: jump hosts first, the destination last. */
data class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: SshAuth,
    val label: String = host,
)

sealed interface SshAuth {
    /** [password] null means "ask when the server requests it" through [SshPrompts]. */
    data class Password(val password: String?) : SshAuth
    /** OpenSSH/PEM/PuTTY private key text; passphrase asked through [SshPrompts] when needed. */
    data class PrivateKey(val privateKey: String, val passphrase: String? = null) : SshAuth
}

/** What the server presented, for the known_hosts check. */
data class HostKeyInfo(
    val host: String,
    val port: Int,
    val keyType: String,
    /** Base64 of the SSH wire encoding, as in a known_hosts line. */
    val publicKey: String,
    /** `SHA256:…` as printed by OpenSSH. */
    val fingerprint: String,
)

data class KnownKey(val keyType: String, val publicKey: String, val fingerprint: String)

/** Persistence of trusted host keys (backed by Room in the app). */
interface KnownHostStore {
    fun find(host: String, port: Int): List<KnownKey>
    fun trust(info: HostKeyInfo, replace: Boolean)
}

/**
 * Questions asked to the user while connecting. Called on a background thread; implementations
 * block until the user answers. Returning null / false cancels the connection.
 */
interface SshPrompts {
    /** [previous] non-null means the key CHANGED since it was trusted. */
    fun confirmHostKey(info: HostKeyInfo, previous: KnownKey?): Boolean
    fun password(target: SshTarget): String?
    fun passphrase(target: SshTarget): String?
}

class SshCancelledException(message: String) : Exception(message)
