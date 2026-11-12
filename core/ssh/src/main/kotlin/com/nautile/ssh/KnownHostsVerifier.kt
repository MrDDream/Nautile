package com.nautile.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/** Trust-on-first-use verification against a [KnownHostStore], asking the user for new or changed keys. */
class KnownHostsVerifier(
    private val store: KnownHostStore,
    private val prompts: SshPrompts,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val info = describe(hostname, port, key)
        val known = store.find(hostname, port)
        if (known.any { it.keyType == info.keyType && it.publicKey == info.publicKey }) return true
        val previous = known.firstOrNull { it.keyType == info.keyType }
        if (!prompts.confirmHostKey(info, previous)) return false
        store.trust(info, replace = previous != null)
        return true
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        store.find(hostname, port).map { it.keyType }

    companion object {
        fun describe(host: String, port: Int, key: PublicKey): HostKeyInfo {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            return HostKeyInfo(
                host = host,
                port = port,
                keyType = KeyType.fromKey(key).toString(),
                publicKey = Base64.getEncoder().encodeToString(blob),
                fingerprint = fingerprint(blob),
            )
        }

        fun fingerprint(blob: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }
}
