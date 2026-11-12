package com.nautile.app.keys

import com.nautile.i18n.tr

import com.nautile.data.db.KeyDao
import com.nautile.data.db.KeyUsage
import com.nautile.data.model.Identity
import com.nautile.data.model.SshKey
import com.nautile.ssh.AgentKey
import com.nautile.ssh.KeyAlgorithm
import com.nautile.ssh.SshKeyMaterial
import com.nautile.ssh.SshKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource

class DuplicateKeyException(val existing: SshKey) : Exception(tr("Cette clé est déjà dans le trousseau (%1\$s)", existing.name))

/** Keychain: generation, import, export of private keys and reusable identities. */
class KeyRepository(private val dao: KeyDao) {
    val keys: Flow<List<SshKey>> = dao.observeKeys()
    val usage: Flow<List<KeyUsage>> = dao.observeUsage()
    val identities: Flow<List<Identity>> = dao.observeIdentities()

    suspend fun key(id: Long) = dao.key(id)
    suspend fun identity(id: Long) = dao.identity(id)

    suspend fun generate(name: String, algorithm: KeyAlgorithm): SshKey {
        val m = withContext(Dispatchers.Default) { SshKeys.generate(algorithm, name) }
        return insert(name, m, savedPassphrase = null, generated = true)
    }

    /**
     * Imports [text]; throws [com.nautile.ssh.PassphraseRequiredException] when a passphrase is needed
     * and [DuplicateKeyException] when the key is already present.
     */
    suspend fun import(name: String, text: String, passphrase: String?, rememberPassphrase: Boolean): SshKey {
        val m = withContext(Dispatchers.Default) { SshKeys.parse(text, passphrase, name) }
        dao.keyByFingerprint(m.fingerprint)?.let { throw DuplicateKeyException(it) }
        return insert(name, m, savedPassphrase = passphrase.takeIf { rememberPassphrase && m.encrypted }, generated = false)
    }

    private suspend fun insert(name: String, m: SshKeyMaterial, savedPassphrase: String?, generated: Boolean): SshKey {
        val key = SshKey(
            name = name, keyType = m.keyType, description = m.description, publicKey = m.publicKey,
            privateKey = m.privateKey, fingerprint = m.fingerprint, encrypted = m.encrypted,
            savedPassphrase = savedPassphrase, generated = generated,
        )
        return key.copy(id = dao.insertKey(key))
    }

    suspend fun rename(key: SshKey, name: String) = dao.updateKey(key.copy(name = name))
    suspend fun forgetPassphrase(key: SshKey) = dao.updateKey(key.copy(savedPassphrase = null))
    suspend fun delete(key: SshKey) = dao.deleteKey(key)

    /**
     * OpenSSH text for saving to a file. Re-encodes with [newPassphrase] when the key can be decrypted
     * (not encrypted, or passphrase remembered); otherwise returns the original text.
     */
    suspend fun exportPrivate(key: SshKey, newPassphrase: String?): String = withContext(Dispatchers.Default) {
        if (key.encrypted && key.savedPassphrase == null) return@withContext key.privateKey
        SshKeys.export(material(key), key.savedPassphrase, newPassphrase, key.name)
    }

    fun canReencrypt(key: SshKey) = !key.encrypted || key.savedPassphrase != null

    /** Keys usable by a forwarded agent without asking anything (unencrypted or passphrase remembered). */
    suspend fun agentKeys(): List<AgentKey> = withContext(Dispatchers.Default) {
        dao.allKeys().filter { canReencrypt(it) }.mapNotNull { k ->
            runCatching {
                val p = SshKeys.load(k.privateKey, k.savedPassphrase?.let(::finder))
                AgentKey(p.public, p.private, k.name)
            }.getOrNull()
        }
    }

    suspend fun saveIdentity(identity: Identity): Long = dao.upsertIdentity(identity)
    suspend fun deleteIdentity(identity: Identity) = dao.deleteIdentity(identity)

    /** Moves milestone-2 key text stored on hosts into the keychain. */
    suspend fun migrateLegacyKeys() {
        for (host in dao.hostsWithLegacyKey()) {
            val text = host.identity ?: continue
            if (!text.contains("PRIVATE KEY") && !text.trimStart().startsWith("PuTTY-User-Key-File")) {
                dao.clearLegacyKey(host.id)
                continue
            }
            val key = runCatching { withContext(Dispatchers.Default) { SshKeys.parse(text, null, host.alias) } }
                .map { m -> dao.keyByFingerprint(m.fingerprint) ?: insert(tr("%1\$s (importée)", host.alias), m, null, false) }
                .getOrElse {
                    // Encrypted: the public half cannot be derived without the passphrase; keep the text.
                    val placeholder = SshKey(
                        name = tr("%1\$s (importée)", host.alias), keyType = "?", description = tr("Chiffrée"), publicKey = "",
                        privateKey = text, fingerprint = "legacy-${host.id}", encrypted = true,
                    )
                    dao.keyByFingerprint(placeholder.fingerprint) ?: placeholder.copy(id = dao.insertKey(placeholder))
                }
            dao.linkLegacyKey(host.id, key.id)
        }
    }

    companion object {
        fun material(k: SshKey) = SshKeyMaterial(k.privateKey, k.publicKey, k.keyType, k.description, k.fingerprint, k.encrypted)

        fun finder(pass: String) = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?) = pass.toCharArray()
            override fun shouldRetry(resource: Resource<*>?) = false
        }
    }
}
