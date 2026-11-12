package com.nautile.data.backup

import com.nautile.i18n.tr

import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.Identity
import com.nautile.data.model.KnownHost
import com.nautile.data.model.PortForward
import com.nautile.data.model.Snippet
import com.nautile.data.model.SshKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Everything a backup carries. Ids are the exporting device's; import remaps them. */
/** A device taking part in the synchronisation (shown in Settings). */
@Serializable
data class SyncDevice(val id: String, val name: String, val lastSeen: Long)

@Serializable
data class BackupData(
    val version: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val groups: List<HostGroup> = emptyList(),
    val hosts: List<Host> = emptyList(),
    val keys: List<SshKey> = emptyList(),
    val identities: List<Identity> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val forwards: List<PortForward> = emptyList(),
    /** Deleted records (synchronisation). */
    val tombstones: List<com.nautile.data.model.SyncTombstone> = emptyList(),
    /** Devices that synchronise through this file. */
    val devices: List<SyncDevice> = emptyList(),
)

class WrongBackupPasswordException : Exception(tr("Mot de passe incorrect ou fichier altéré"))

/**
 * Encrypted backup file: a magic line, a JSON header with the KDF parameters, then the
 * AES-256-GCM ciphertext (header as associated data) in base64. Key = Argon2id(password).
 */
object BackupCodec {
    const val MAGIC = "NAUTILE-BACKUP-1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()

    @Serializable
    private data class Header(val kdf: String, val m: Int, val t: Int, val p: Int, val salt: String, val nonce: String)

    fun export(data: BackupData, password: String, memoryKiB: Int = 19_456, iterations: Int = 2): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = json.encodeToString(Header.serializer(), Header("argon2id", memoryKiB, iterations, 1, b64(salt), b64(nonce)))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(password, salt, memoryKiB, iterations, 1), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header.toByteArray())
        val sealed = cipher.doFinal(json.encodeToString(BackupData.serializer(), data).toByteArray())
        return "$MAGIC\n$header\n${Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(sealed)}\n"
    }

    fun import(text: String, password: String): BackupData {
        val lines = text.trim().lines()
        require(lines.size >= 3 && lines[0].trim() == MAGIC) { tr("Ce fichier n'est pas une sauvegarde Nautile") }
        val headerLine = lines[1].trim()
        val h = json.decodeFromString(Header.serializer(), headerLine)
        require(h.kdf == "argon2id" && h.m in 8..1_048_576 && h.t in 1..20 && h.p in 1..8) { tr("Paramètres de sauvegarde invalides") }
        val sealed = Base64.getMimeDecoder().decode(lines.drop(2).joinToString(""))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(password, unb64(h.salt), h.m, h.t, h.p), "AES"), GCMParameterSpec(128, unb64(h.nonce)))
        cipher.updateAAD(headerLine.toByteArray())
        val plain = try { cipher.doFinal(sealed) } catch (_: AEADBadTagException) { throw WrongBackupPasswordException() }
        return json.decodeFromString(BackupData.serializer(), plain.decodeToString())
    }

    private fun derive(password: String, salt: ByteArray, m: Int, t: Int, p: Int): ByteArray {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt).withMemoryAsKB(m).withIterations(t).withParallelism(p).build(),
        )
        return ByteArray(32).also { gen.generateBytes(password.toCharArray(), it) }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}
