package com.nautile.ssh

import com.nautile.i18n.tr

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class KeyAlgorithm(val label: String) {
    ED25519("Ed25519"),
    RSA_3072("RSA 3072"),
    RSA_4096("RSA 4096"),
    ECDSA_256("ECDSA P-256"),
    ECDSA_384("ECDSA P-384"),
    ECDSA_521("ECDSA P-521"),
}

/** A key as stored by the app. [privateKey] is the text as imported or generated. */
data class SshKeyMaterial(
    val privateKey: String,
    /** `type base64 comment`, ready for authorized_keys. */
    val publicKey: String,
    val keyType: String,
    /** e.g. "ED25519", "RSA 4096", "ECDSA 256". */
    val description: String,
    val fingerprint: String,
    /** The private text itself is protected by a passphrase. */
    val encrypted: Boolean,
)

class PassphraseRequiredException : Exception(tr("Phrase de passe requise"))
class WrongPassphraseException : Exception(tr("Phrase de passe incorrecte"))

object SshKeys {
    private val random = SecureRandom()

    fun generate(algorithm: KeyAlgorithm, comment: String): SshKeyMaterial {
        val pair = when (algorithm) {
            KeyAlgorithm.ED25519 -> net.i2p.crypto.eddsa.KeyPairGenerator().generateKeyPair()
            KeyAlgorithm.RSA_3072, KeyAlgorithm.RSA_4096 -> KeyPairGenerator.getInstance("RSA").apply {
                initialize(if (algorithm == KeyAlgorithm.RSA_3072) 3072 else 4096, random)
            }.generateKeyPair()
            KeyAlgorithm.ECDSA_256, KeyAlgorithm.ECDSA_384, KeyAlgorithm.ECDSA_521 -> KeyPairGenerator.getInstance("EC").apply {
                val curve = when (algorithm) {
                    KeyAlgorithm.ECDSA_256 -> "secp256r1"
                    KeyAlgorithm.ECDSA_384 -> "secp384r1"
                    else -> "secp521r1"
                }
                initialize(ECGenParameterSpec(curve), random)
            }.generateKeyPair()
        }
        return describe(pair.public, encodeOpenSsh(pair, comment, null), comment, encrypted = false)
    }

    /**
     * Validates [text] (OpenSSH, PEM or PuTTY) and derives its public half.
     * @throws PassphraseRequiredException when encrypted and [passphrase] is null.
     */
    fun parse(text: String, passphrase: String? = null, comment: String = ""): SshKeyMaterial {
        var asked = false
        val provider = load(text, object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray {
                asked = true
                return passphrase?.toCharArray() ?: throw PassphraseRequiredException()
            }
            override fun shouldRetry(resource: Resource<*>?) = false
        })
        val pub = try {
            provider.public.also { provider.private }
        } catch (e: PassphraseRequiredException) {
            throw e
        } catch (e: Exception) {
            if (e.cause is PassphraseRequiredException) throw PassphraseRequiredException()
            if (asked) throw WrongPassphraseException()
            throw IllegalArgumentException(tr("Clé illisible : %1\$s", e.message), e)
        }
        return describe(pub, text.trim() + "\n", comment, encrypted = asked)
    }

    /** Loads a key for signing (authentication, agent). */
    fun load(text: String, finder: PasswordFinder?): KeyProvider =
        SSHClient(DefaultConfig()).loadKeys(text.trim() + "\n", null as String?, finder)

    /** Re-encodes an existing key in OpenSSH format, optionally encrypted (aes256-ctr + bcrypt). */
    fun export(material: SshKeyMaterial, currentPassphrase: String?, newPassphrase: String?, comment: String): String {
        val provider = load(material.privateKey, currentPassphrase?.let { p ->
            object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?) = p.toCharArray()
                override fun shouldRetry(resource: Resource<*>?) = false
            }
        })
        return encodeOpenSsh(KeyPair(provider.public, provider.private), comment, newPassphrase?.takeIf { it.isNotEmpty() })
    }

    fun publicBlob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    private fun describe(pub: PublicKey, privateText: String, comment: String, encrypted: Boolean): SshKeyMaterial {
        val type = KeyType.fromKey(pub)
        val blob = publicBlob(pub)
        val bits = when (pub) {
            is java.security.interfaces.RSAPublicKey -> " ${pub.modulus.bitLength()}"
            is java.security.interfaces.ECPublicKey -> " ${pub.params.curve.field.fieldSize}"
            else -> ""
        }
        val label = when (type) {
            KeyType.ED25519 -> "ED25519"
            KeyType.RSA -> "RSA$bits"
            KeyType.ECDSA256, KeyType.ECDSA384, KeyType.ECDSA521 -> "ECDSA$bits"
            else -> type.toString()
        }
        return SshKeyMaterial(
            privateKey = privateText,
            publicKey = listOf(type.toString(), Base64.getEncoder().encodeToString(blob), comment).filter { it.isNotEmpty() }.joinToString(" "),
            keyType = type.toString(),
            description = label,
            fingerprint = KnownHostsVerifier.fingerprint(blob),
            encrypted = encrypted,
        )
    }

    /** openssh-key-v1, as written by `ssh-keygen`. */
    internal fun encodeOpenSsh(pair: KeyPair, comment: String, passphrase: String?): String {
        val pubBlob = publicBlob(pair.public)
        val blockSize = if (passphrase != null) 16 else 8
        val check = random.nextInt()
        val priv = Buffer.PlainBuffer().putUInt32(check.toLong() and 0xFFFFFFFFL).putUInt32(check.toLong() and 0xFFFFFFFFL)
        writePrivate(priv, pair.public, pair.private, pubBlob)
        priv.putString(comment)
        var pad = 1
        while (priv.available() % blockSize != 0) priv.putByte((pad++).toByte())
        var section = priv.compactData

        val out = Buffer.PlainBuffer()
        out.putRawBytes("openssh-key-v1\u0000".toByteArray())
        if (passphrase == null) {
            out.putString("none").putString("none").putString("")
        } else {
            val salt = ByteArray(16).also(random::nextBytes)
            val rounds = 16
            val derived = ByteArray(48)
            BCrypt().pbkdf(passphrase.toByteArray(), salt, rounds, derived)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, 0, 32, "AES"), IvParameterSpec(derived, 32, 16))
            section = cipher.doFinal(section)
            val kdf = Buffer.PlainBuffer().putBytes(salt).putUInt32(rounds.toLong()).compactData
            out.putString("aes256-ctr").putString("bcrypt").putBytes(kdf)
        }
        out.putUInt32(1).putBytes(pubBlob).putBytes(section)

        val b64 = Base64.getEncoder().encodeToString(out.compactData).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    private fun writePrivate(buf: Buffer.PlainBuffer, pub: PublicKey, priv: PrivateKey, pubBlob: ByteArray) {
        val fields = Buffer.PlainBuffer(pubBlob)
        val type = fields.readString()
        when (priv) {
            is EdDSAPrivateKey -> {
                val a = priv.abyte
                buf.putString(type).putBytes(a).putBytes(priv.seed + a)
            }
            is RSAPrivateCrtKey -> buf.putString(type)
                .putMPInt(priv.modulus).putMPInt(priv.publicExponent).putMPInt(priv.privateExponent)
                .putMPInt(priv.crtCoefficient).putMPInt(priv.primeP).putMPInt(priv.primeQ)
            is ECPrivateKey -> {
                val curve = fields.readString()
                val q = fields.readBytes()
                buf.putString(type).putString(curve).putBytes(q).putMPInt(priv.s)
            }
            else -> throw IllegalArgumentException(tr("Type de clé non pris en charge : %1\$s", priv.algorithm))
        }
    }
}
