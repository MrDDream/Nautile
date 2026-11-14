package com.nautile.ssh

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import com.nautile.i18n.tr
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PrivateKeyStringResource
import net.schmizz.sshj.userauth.password.Resource
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.pkcs.PBES2Parameters
import org.bouncycastle.asn1.pkcs.PBKDF2Params
import org.bouncycastle.asn1.pkcs.PKCS12PBEParams
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.util.Base64
import javax.crypto.BadPaddingException
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

/** A key refused before or while decrypting it: oversized, or too costly to derive. */
class KeyRefusedException(message: String) : IllegalArgumentException(message)

object SshKeys {
    private val random = SecureRandom()

    fun generate(algorithm: KeyAlgorithm, comment: String): SshKeyMaterial {
        if (algorithm == KeyAlgorithm.ED25519) {
            // BouncyCastle's raw keys, written as ssh-keygen does and read back by sshj (whatever key
            // classes its version uses).
            val priv = Ed25519PrivateKeyParameters(random)
            val pub = priv.generatePublicKey().encoded
            val text = encodeOpenSsh(ed25519Blob(pub), comment, null) { buf, type -> writeEd25519(buf, type, priv.encoded, pub) }
            return parse(text, null, comment)
        }
        val pair = when (algorithm) {
            KeyAlgorithm.ED25519 -> error("unreachable")
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
        val pub = try {
            load(text, object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray {
                    asked = true
                    return passphrase?.toCharArray() ?: throw PassphraseRequiredException()
                }
                override fun shouldRetry(resource: Resource<*>?) = false
            }).public
        } catch (e: PassphraseRequiredException) {
            throw e
        } catch (e: WrongPassphraseException) {
            throw e
        } catch (e: KeyRefusedException) {
            throw e
        } catch (e: Exception) {
            if (e.cause is PassphraseRequiredException) throw PassphraseRequiredException()
            if (asked) throw WrongPassphraseException()
            throw IllegalArgumentException(tr("Clé illisible : %1\$s", e.message), e)
        }
        return describe(pub, text.trim() + "\n", comment, encrypted = asked)
    }

    /**
     * Loads and decrypts a key for signing (authentication, agent). Decrypts at once (asking
     * [finder] if needed) so that a costly derivation runs only once.
     * @throws KeyRefusedException when [checkCost] refuses it or its derivation runs out of memory.
     */
    fun load(text: String, finder: PasswordFinder?): KeyProvider {
        checkCost(text)
        val trimmed = text.trim()
        return try {
            // sshj decrypts PKCS#8 through javax.crypto.EncryptedPrivateKeyInfo, which needs SunJCE's
            // PBES2 AlgorithmParameters (absent on Android): decrypt it here with BouncyCastle.
            val clear = pemBody(trimmed, "ENCRYPTED PRIVATE KEY")?.let { opensshText(decryptPkcs8(it, trimmed, finder)) } ?: trimmed
            val provider = SSHClient(DefaultConfig()).loadKeys(clear + "\n", null as String?, if (clear === trimmed) finder else null)
            KeyPairWrapper(provider.public, provider.private)
        } catch (e: OutOfMemoryError) {
            throw KeyRefusedException(tr("Clé refusée : mémoire insuffisante pour la déchiffrer"))
        }
    }

    /** Decrypts an encrypted PKCS#8 key ([der]). */
    private fun decryptPkcs8(der: ByteArray, text: String, finder: PasswordFinder?): PrivateKeyInfo {
        val info = runCatching { PKCS8EncryptedPrivateKeyInfo(der) }
            .getOrElse { throw IllegalArgumentException(tr("Clé illisible : %1\$s", it.message), it) }
        val password = finder?.reqPassword(PrivateKeyStringResource(text)) ?: throw PassphraseRequiredException()
        return try {
            info.decryptPrivateKeyInfo(JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(bouncyCastle()).build(password))
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Exception) {
            val unsupported = generateSequence(e as Throwable) { it.cause }.take(8)
                .firstOrNull { it is OperatorCreationException || it is GeneralSecurityException && it !is BadPaddingException && it !is InvalidKeyException }
            if (unsupported != null) throw IllegalArgumentException(tr("Clé illisible : %1\$s", unsupported.message), e)
            // A wrong passphrase garbles the padding or the decrypted structure.
            throw WrongPassphraseException()
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * A decrypted PKCS#8 key re-encoded as an unencrypted openssh-key-v1 text, so that sshj reads
     * it with its own key classes (its PKCS#8 reader also misses EC keys without their public point).
     */
    private fun opensshText(info: PrivateKeyInfo): String {
        if (info.privateKeyAlgorithm.algorithm == EdECObjectIdentifiers.id_Ed25519) {
            val seed = (info.parsePrivateKey() as ASN1OctetString).octets
            require(seed.size == 32) { "Ed25519 seed" }
            val pub = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
            return encodeOpenSsh(ed25519Blob(pub), "", null) { buf, type -> writeEd25519(buf, type, seed, pub) }
        }
        val bc = bouncyCastle()
        val priv = JcaPEMKeyConverter().setProvider(bc).getPrivateKey(info)
        val pub = when (priv) {
            is RSAPrivateCrtKey -> KeyFactory.getInstance("RSA", bc).generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent))
            is org.bouncycastle.jce.interfaces.ECPrivateKey -> {
                val params = priv.parameters
                KeyFactory.getInstance("EC", bc).generatePublic(ECPublicKeySpec(params.g.multiply(priv.d).normalize(), params))
            }
            else -> throw IllegalArgumentException(tr("Type de clé non pris en charge : %1\$s", priv.algorithm))
        }
        return encodeOpenSsh(KeyPair(pub, priv), "", null)
    }

    /** The registered BouncyCastle provider (the app's, on Android), else a private instance. */
    private fun bouncyCastle(): Provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        ?.takeIf { it is BouncyCastleProvider } ?: privateBouncyCastle

    private val privateBouncyCastle by lazy { BouncyCastleProvider() }

    /** Largest key text accepted (a 16384-bit RSA key in PEM is about 13 KiB). */
    const val MAX_KEY_TEXT = 1 shl 20
    /** 64 MiB: what an Android app can allocate on top of its heap without dying. */
    const val MAX_ARGON2_MEMORY_KIB = 65_536L
    /**
     * Total Argon2 work (memory × passes): PuTTYgen's default memory (8 MiB) with up to 128
     * passes; PuTTYgen picks the passes for ~100 ms on the creating machine (often 13 to 34).
     */
    const val MAX_ARGON2_COST = 8_192L * 128
    const val MAX_ARGON2_PASSES = 1_000L
    const val MAX_ARGON2_PARALLELISM = 8L
    const val MAX_BCRYPT_ROUNDS = 1024
    const val MAX_PBKDF2_ITERATIONS = 10_000_000

    /**
     * Refuses, before any decryption, a key whose passphrase derivation would take minutes or
     * gigabytes (a file crafted to freeze or kill the app): PuTTY's Argon2 parameters,
     * openssh-key-v1's bcrypt rounds, PKCS#8 iterations.
     */
    fun checkCost(text: String) {
        fun refuse(what: String): Nothing = throw KeyRefusedException(tr("Clé refusée : paramètres de chiffrement excessifs (%1\$s)", what))
        if (text.length > MAX_KEY_TEXT) throw KeyRefusedException(tr("Fichier trop volumineux pour une clé"))
        val trimmed = text.trim()
        if (trimmed.startsWith("PuTTY-User-Key-File-")) {
            val headers = trimmed.lineSequence().mapNotNull { line ->
                line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() }
            }.toMap()
            fun num(name: String) = headers[name]?.let { it.toLongOrNull() ?: refuse(name) }
            val memory = num("Argon2-Memory")?.also { if (it !in 1..MAX_ARGON2_MEMORY_KIB) refuse("Argon2-Memory $it") }
            val passes = num("Argon2-Passes")?.also { if (it !in 1..MAX_ARGON2_PASSES) refuse("Argon2-Passes $it") }
            num("Argon2-Parallelism")?.let { if (it !in 1..MAX_ARGON2_PARALLELISM) refuse("Argon2-Parallelism $it") }
            if (memory != null && passes != null && memory * passes > MAX_ARGON2_COST) refuse("Argon2 $memory KiB × $passes")
            return
        }
        pemBody(trimmed, "OPENSSH PRIVATE KEY")?.let { der ->
            bcryptRounds(der)?.let { if (it > MAX_BCRYPT_ROUNDS) refuse("bcrypt rounds $it") }
            return
        }
        pemBody(trimmed, "ENCRYPTED PRIVATE KEY")?.let { der ->
            pkcs8Iterations(der)?.let { if (it > MAX_PBKDF2_ITERATIONS) refuse("PKCS#8 iterations $it") }
        }
    }

    /** The base64 body of a `-----BEGIN [label]-----` block, decoded; null when [text] is not one. */
    private fun pemBody(text: String, label: String): ByteArray? {
        val begin = "-----BEGIN $label-----"
        if (!text.startsWith(begin)) return null
        val body = text.removePrefix(begin).substringBefore("-----END").filterNot { it.isWhitespace() }
        return runCatching { Base64.getDecoder().decode(body) }.getOrNull()
    }

    /** The bcrypt rounds of an openssh-key-v1 blob, null when it uses no KDF or cannot be read. */
    internal fun bcryptRounds(blob: ByteArray): Long? = runCatching {
        val magic = "openssh-key-v1\u0000".toByteArray()
        if (blob.size < magic.size || !blob.copyOfRange(0, magic.size).contentEquals(magic)) return@runCatching null
        val buf = Buffer.PlainBuffer(blob.copyOfRange(magic.size, blob.size))
        buf.readString() // cipher
        val kdf = buf.readString()
        val options = buf.readBytes()
        if (kdf != "bcrypt") return@runCatching null
        val o = Buffer.PlainBuffer(options)
        o.readBytes() // salt
        o.readUInt32()
    }.getOrNull()

    /** PBKDF2 (PBES2) or PKCS#12 PBE iteration count of an encrypted PKCS#8 key; null when unknown. */
    internal fun pkcs8Iterations(der: ByteArray): Long? = runCatching {
        val alg = EncryptedPrivateKeyInfo.getInstance(der).encryptionAlgorithm
        if (alg.algorithm == PKCSObjectIdentifiers.id_PBES2) {
            val kdf = PBES2Parameters.getInstance(alg.parameters).keyDerivationFunc
            if (kdf.algorithm != PKCSObjectIdentifiers.id_PBKDF2) return@runCatching null
            PBKDF2Params.getInstance(kdf.parameters).iterationCount.toLong()
        } else {
            PKCS12PBEParams.getInstance(alg.parameters).iterations.toLong()
        }
    }.getOrNull()

    /** Re-encodes an existing key in OpenSSH format, optionally encrypted (aes256-ctr + bcrypt). */
    fun export(material: SshKeyMaterial, currentPassphrase: String?, newPassphrase: String?, comment: String): String {
        val provider = load(material.privateKey, currentPassphrase?.let { p ->
            object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?) = p.toCharArray()
                override fun shouldRetry(resource: Resource<*>?) = false
            }
        })
        val pubBlob = publicBlob(provider.public)
        val private = provider.private
        return encodeOpenSsh(pubBlob, comment, newPassphrase?.takeIf { it.isNotEmpty() }) { buf, _ -> writePrivate(buf, private, pubBlob) }
    }

    fun publicBlob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    private fun describe(pub: PublicKey, privateText: String, comment: String, encrypted: Boolean): SshKeyMaterial {
        val type = KeyType.fromKey(pub)
        val blob = publicBlob(pub)
        val bits = when (pub) {
            is RSAPublicKey -> " ${pub.modulus.bitLength()}"
            is ECPublicKey -> " ${pub.params.curve.field.fieldSize}"
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

    internal fun encodeOpenSsh(pair: KeyPair, comment: String, passphrase: String?): String {
        val pubBlob = publicBlob(pair.public)
        return encodeOpenSsh(pubBlob, comment, passphrase) { buf, _ -> writePrivate(buf, pair.private, pubBlob) }
    }

    private fun ed25519Blob(pub: ByteArray): ByteArray = Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(pub).compactData

    /** openssh-key-v1, as written by `ssh-keygen`; [writeKey] writes the key fields of the private section. */
    private fun encodeOpenSsh(pubBlob: ByteArray, comment: String, passphrase: String?, writeKey: (Buffer.PlainBuffer, String) -> Unit): String {
        val blockSize = if (passphrase != null) 16 else 8
        val check = random.nextInt()
        val priv = Buffer.PlainBuffer().putUInt32(check.toLong() and 0xFFFFFFFFL).putUInt32(check.toLong() and 0xFFFFFFFFL)
        writeKey(priv, Buffer.PlainBuffer(pubBlob).readString())
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

    private fun writeEd25519(buf: Buffer.PlainBuffer, type: String, seed: ByteArray, pub: ByteArray) {
        buf.putString(type).putBytes(pub).putBytes(seed + pub)
    }

    private fun writePrivate(buf: Buffer.PlainBuffer, priv: PrivateKey, pubBlob: ByteArray) {
        val fields = Buffer.PlainBuffer(pubBlob)
        val type = fields.readString()
        if (type == "ssh-ed25519") {
            // The public point comes from the blob, the seed from the PKCS#8 encoding: works with any
            // provider's Ed25519 key classes (net.i2p eddsa in older sshj, BouncyCastle since).
            val pub = fields.readBytes()
            val seed = (PrivateKeyInfo.getInstance(priv.encoded).parsePrivateKey() as ASN1OctetString).octets
            require(seed.size == 32) { "Ed25519 seed" }
            writeEd25519(buf, type, seed, pub)
            return
        }
        when (priv) {
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
