package com.nautile.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Real encrypted keys from OpenSSL, ssh-keygen and PuTTY's v3 format. */
class EncryptedKeyFormatsTest {
    private fun resource(name: String) = javaClass.getResource("/keys/$name")!!.readText()

    /** `type base64` of a `.pub` file. */
    private fun expectedPublic(name: String) = resource("$name.pub").trim().split(' ').take(2).joinToString(" ")

    private val pkcs8Fixtures = listOf(
        "rsa2048-openssl-pkcs8.pem", // openssl pkcs8 -topk8 -v2 aes-256-cbc
        "ecdsa256-openssl-pkcs8.pem",
        "rsa2048-sshkeygen-pkcs8.pem", // ssh-keygen -m PKCS8 -N …
        "ecdsa384-sshkeygen-pkcs8.pem",
        "ed25519-openssl-pkcs8.pem", // openssl genpkey -algorithm ed25519, then pkcs8 -topk8
    )

    /**
     * Runs [block] as on Android: no SunJCE (hence no PBES2 AlgorithmParameters for
     * javax.crypto.EncryptedPrivateKeyInfo), no SunEC, BouncyCastle first.
     */
    private fun withoutSunJce(block: () -> Unit) {
        val removed = listOf("SunJCE", "SunEC").mapNotNull { name ->
            val all = Security.getProviders()
            val at = all.indexOfFirst { it.name == name }
            if (at < 0) null else (all[at] to at + 1).also { Security.removeProvider(name) }
        }
        // sshj registers BouncyCastle once and then refers to it by name: it must stay registered.
        net.schmizz.sshj.common.SecurityUtils.isBouncyCastleRegistered()
        val bcAt = Security.getProviders().indexOfFirst { it.name == BouncyCastleProvider.PROVIDER_NAME }
        val bc = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) ?: BouncyCastleProvider()
        Security.removeProvider(bc.name)
        Security.insertProviderAt(bc, 1)
        try {
            assertEquals(null, Security.getProvider("SunJCE"))
            block()
        } finally {
            Security.removeProvider(bc.name)
            removed.forEach { (p, at) -> Security.insertProviderAt(p, at) }
            if (bcAt >= 0) Security.insertProviderAt(bc, bcAt + 1) else Security.addProvider(bc)
        }
    }

    @Test fun encryptedPkcs8LoadsWithoutSunJce() = withoutSunJce {
        for (name in pkcs8Fixtures) {
            val text = resource(name)
            assertTrue(name, text.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----"))
            assertTrue(name, runCatching { SshKeys.parse(text) }.exceptionOrNull() is PassphraseRequiredException)
            assertTrue(name, runCatching { SshKeys.parse(text, "wrong") }.exceptionOrNull() is WrongPassphraseException)
            val m = SshKeys.parse(text, "nautile-test", "c")
            assertTrue(name, m.encrypted)
            assertEquals(name, expectedPublic(name), m.publicKey.substringBeforeLast(' '))

            // The authentication path: a usable signing key.
            val p = SshKeys.load(text, object : net.schmizz.sshj.userauth.password.PasswordFinder {
                override fun reqPassword(resource: net.schmizz.sshj.userauth.password.Resource<*>?) = "nautile-test".toCharArray()
                override fun shouldRetry(resource: net.schmizz.sshj.userauth.password.Resource<*>?) = false
            })
            val key = AgentKey(p.public, p.private, name)
            val data = "challenge".toByteArray()
            val req = Buffer.PlainBuffer().putByte(13).putBytes(SshKeys.publicBlob(key.public)).putBytes(data).putUInt32(2).compactData
            val reply = Buffer.PlainBuffer(AgentProtocol.handle(req, listOf(key)))
            assertEquals(name, 14, reply.readByte().toInt())
            val sigBlob = reply.readBytes()
            val sigName = Buffer.PlainBuffer(sigBlob).readString()
            val verifier = DefaultConfig().keyAlgorithms.first { it.name == sigName }.create().newSignature()
            verifier.initVerify(p.public)
            verifier.update(data)
            assertTrue("$name $sigName", verifier.verify(sigBlob))

            // Re-encoding to OpenSSH works from it too.
            val exported = SshKeys.export(m, "nautile-test", null, "c")
            assertEquals(name, m.fingerprint, SshKeys.parse(exported).fingerprint)
        }
    }

    @Test fun pkcs8IterationsAreReadFromRealKeys() {
        for (name in pkcs8Fixtures) {
            val der = Base64.getDecoder().decode(
                resource(name).substringAfter("-----BEGIN ENCRYPTED PRIVATE KEY-----").substringBefore("-----END").filterNot { it.isWhitespace() },
            )
            val n = SshKeys.pkcs8Iterations(der)
            assertTrue("$name: $n", n != null && n in 1..SshKeys.MAX_PBKDF2_ITERATIONS)
        }
    }

    @Test fun puttyV3WithPuttygenDefaultCostLoads() {
        val passes = 34 // PuTTYgen's default on a fast machine (13, 21, 34… are common)
        val (text, pubBlob) = puttyV3Ed25519("pass phrase", memoryKib = 8192, passes = passes, parallelism = 1)
        assertTrue(runCatching { SshKeys.parse(text) }.exceptionOrNull() is PassphraseRequiredException)
        assertTrue(runCatching { SshKeys.parse(text, "wrong") }.exceptionOrNull() is WrongPassphraseException)
        val m = SshKeys.parse(text, "pass phrase")
        assertTrue(m.encrypted)
        assertEquals(KnownHostsVerifier.fingerprint(pubBlob), m.fingerprint)
    }

    @Test fun argon2TotalCostIsBounded() {
        fun header(memory: Long, passes: Long, parallel: Long) = """
            PuTTY-User-Key-File-3: ssh-ed25519
            Encryption: aes256-cbc
            Key-Derivation: Argon2id
            Argon2-Memory: $memory
            Argon2-Passes: $passes
            Argon2-Parallelism: $parallel
        """.trimIndent()
        fun refused(memory: Long, passes: Long, parallel: Long = 1) =
            runCatching { SshKeys.checkCost(header(memory, passes, parallel)) }.exceptionOrNull() is KeyRefusedException
        // Accepted: PuTTYgen defaults, and the limits themselves.
        for (p in listOf(13L, 21L, 34L, 128L)) assertTrue("8192 × $p", !refused(8192, p))
        assertTrue(!refused(65_536, 16))
        assertTrue(!refused(1024, 1000))
        assertTrue(!refused(8192, 8, 8))
        // Refused: memory, total work, passes, parallelism.
        assertTrue(refused(65_537, 1))
        assertTrue(refused(262_144, 1))
        assertTrue(refused(65_536, 17))
        assertTrue(refused(8192, 129))
        assertTrue(refused(1, 1001))
        assertTrue(refused(8192, 8, 9))
        assertTrue(refused(0, 1))
    }

    /** A PuTTY v3 (.ppk) Ed25519 key encrypted with Argon2id, as PuTTYgen writes it. */
    private fun puttyV3Ed25519(passphrase: String, memoryKib: Int, passes: Int, parallelism: Int): Pair<String, ByteArray> {
        val random = SecureRandom()
        val priv = Ed25519PrivateKeyParameters(random)
        val pub = priv.generatePublicKey().encoded
        val pubBlob = Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(pub).compactData
        var privBlob = Buffer.PlainBuffer().putBytes(priv.encoded).compactData
        privBlob += ByteArray((16 - privBlob.size % 16) % 16).also(random::nextBytes)
        val salt = ByteArray(16).also(random::nextBytes)
        val out = ByteArray(80)
        Argon2BytesGenerator().apply {
            init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt).withMemoryAsKB(memoryKib).withIterations(passes).withParallelism(parallelism).build(),
            )
        }.generateBytes(passphrase.toByteArray(), out)
        val comment = "ppk-test"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(out, 48, 32, "HmacSHA256")) }.doFinal(
            Buffer.PlainBuffer().putString("ssh-ed25519").putString("aes256-cbc").putString(comment)
                .putBytes(pubBlob).putBytes(privBlob).compactData,
        )
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(out, 0, 32, "AES"), IvParameterSpec(out, 32, 16))
        }.doFinal(privBlob)
        fun lines(b: ByteArray) = Base64.getEncoder().encodeToString(b).chunked(64)
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val publicLines = lines(pubBlob)
        val privateLines = lines(encrypted)
        val text = buildString {
            appendLine("PuTTY-User-Key-File-3: ssh-ed25519")
            appendLine("Encryption: aes256-cbc")
            appendLine("Comment: $comment")
            appendLine("Public-Lines: ${publicLines.size}")
            publicLines.forEach(::appendLine)
            appendLine("Key-Derivation: Argon2id")
            appendLine("Argon2-Memory: $memoryKib")
            appendLine("Argon2-Passes: $passes")
            appendLine("Argon2-Parallelism: $parallelism")
            appendLine("Argon2-Salt: ${hex(salt)}")
            appendLine("Private-Lines: ${privateLines.size}")
            privateLines.forEach(::appendLine)
            appendLine("Private-MAC: ${hex(mac)}")
        }
        return text to pubBlob
    }
}
