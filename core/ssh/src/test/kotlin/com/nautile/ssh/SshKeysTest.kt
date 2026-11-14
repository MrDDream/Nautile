package com.nautile.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class SshKeysTest {
    @Test fun generatedKeysParseBackWithSameFingerprint() {
        KeyAlgorithm.entries.filter { it != KeyAlgorithm.RSA_4096 }.forEach { algo ->
            val key = SshKeys.generate(algo, "test@nautile")
            assertTrue(key.privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
            val parsed = SshKeys.parse(key.privateKey)
            assertEquals(algo.name, key.fingerprint, parsed.fingerprint)
            assertFalse(parsed.encrypted)
            assertTrue(key.publicKey.endsWith(" test@nautile"))
        }
    }

    @Test fun encryptedExportNeedsPassphrase() {
        val key = SshKeys.generate(KeyAlgorithm.ED25519, "c")
        val exported = SshKeys.export(key, null, "s3cret", "c")
        assertTrue(runCatching { SshKeys.parse(exported) }.exceptionOrNull() is PassphraseRequiredException)
        assertTrue(runCatching { SshKeys.parse(exported, "wrong") }.isFailure)
        val back = SshKeys.parse(exported, "s3cret")
        assertTrue(back.encrypted)
        assertEquals(key.fingerprint, back.fingerprint)
    }

    @Test fun descriptionsAndTypes() {
        assertEquals("ED25519", SshKeys.generate(KeyAlgorithm.ED25519, "").description)
        assertEquals("ECDSA 384", SshKeys.generate(KeyAlgorithm.ECDSA_384, "").description)
        assertEquals("RSA 3072", SshKeys.generate(KeyAlgorithm.RSA_3072, "").description)
    }

    /** Cross-check with OpenSSH's own tool when it is installed. */
    @Test fun sshKeygenAgreesWithOurEncoding() {
        val keygen = listOf("/usr/bin/ssh-keygen", "/bin/ssh-keygen").map(::File).firstOrNull { it.canExecute() }
        assumeTrue(keygen != null)
        val dir = kotlin.io.path.createTempDirectory("coq").toFile()
        try {
            for (algo in listOf(KeyAlgorithm.ED25519, KeyAlgorithm.RSA_3072, KeyAlgorithm.ECDSA_521)) {
                for (pass in listOf(null, "pw")) {
                    val key = SshKeys.generate(algo, "x")
                    val text = if (pass == null) key.privateKey else SshKeys.export(key, null, pass, "x")
                    val f = File(dir, "k").apply {
                        writeText(text)
                        java.nio.file.Files.setPosixFilePermissions(toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
                    }
                    val p = ProcessBuilder(keygen!!.path, "-y", "-P", pass ?: "", "-f", f.path).redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    p.waitFor(30, TimeUnit.SECONDS)
                    assertEquals("$algo pass=$pass: $out", key.publicKey.substringBeforeLast(' '), out.split(' ').take(2).joinToString(" "))
                    f.delete()
                }
            }
        } finally { dir.deleteRecursively() }
    }

    private fun refused(text: String) = runCatching { SshKeys.parse(text, "pw") }.exceptionOrNull()
        .let { it is IllegalArgumentException && it !is WrongPassphraseException && it.message.orEmpty().contains("excessifs") }

    @Test fun puttyArgon2ParametersAreBounded() {
        fun ppk(memory: Int, passes: Int, parallel: Int) = """
            PuTTY-User-Key-File-3: ssh-ed25519
            Encryption: aes256-cbc
            Comment: x
            Public-Lines: 2
            AAAAC3NzaC1lZDI1NTE5AAAAIJ7Y
            AAAA
            Key-Derivation: Argon2id
            Argon2-Memory: $memory
            Argon2-Passes: $passes
            Argon2-Parallelism: $parallel
            Argon2-Salt: 00112233445566778899aabbccddeeff
            Private-Lines: 1
            AAAA
            Private-MAC: 00
        """.trimIndent()
        assertTrue(refused(ppk(4_194_304, 1, 1)))
        assertTrue(refused(ppk(8192, 1000, 1)))
        assertTrue(refused(ppk(8192, 8, 64)))
        // Within bounds: goes on to the parser (which fails on this fake key for another reason).
        assertFalse(refused(ppk(8192, 8, 1)))
    }

    @Test fun opensshBcryptRoundsAreBounded() {
        val key = SshKeys.generate(KeyAlgorithm.ED25519, "c")
        val exported = SshKeys.export(key, null, "pw", "c")
        assertEquals(16L, SshKeys.bcryptRounds(der(exported)))
        // Same key with 1 000 000 rounds: refused before the (minutes long) derivation.
        val blob = der(exported)
        val at = indexOfRounds(blob)
        val patched = blob.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(at, 1_000_000) }
        val text = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + java.util.Base64.getEncoder().encodeToString(patched) + "\n-----END OPENSSH PRIVATE KEY-----\n"
        assertTrue(refused(text))
        assertFalse(refused(exported))
    }

    @Test fun pkcs8IterationsAreBounded() {
        fun pkcs8(iterations: Int): String {
            val gen = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val enc = org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder(org.bouncycastle.openssl.PKCS8Generator.AES_256_CBC)
                .setIterationCount(iterations).setPassword("pw".toCharArray()).build()
            val obj = org.bouncycastle.openssl.jcajce.JcaPKCS8Generator(gen.private, enc).generate()
            val w = java.io.StringWriter()
            org.bouncycastle.util.io.pem.PemWriter(w).use { it.writeObject(obj) }
            return w.toString()
        }
        val ok = pkcs8(2048)
        assertEquals(2048L, SshKeys.pkcs8Iterations(der(ok, "ENCRYPTED PRIVATE KEY")))
        // Within bounds: actually decrypted (not merely « not refused »).
        assertTrue(SshKeys.parse(ok, "pw").encrypted)
        // Same key, its PBKDF2 count raised to 20 million (deriving it would take a long while).
        val info = org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo.getInstance(der(ok, "ENCRYPTED PRIVATE KEY"))
        val pbes2 = org.bouncycastle.asn1.pkcs.PBES2Parameters.getInstance(info.encryptionAlgorithm.parameters)
        val kdf = org.bouncycastle.asn1.pkcs.PBKDF2Params.getInstance(pbes2.keyDerivationFunc.parameters)
        val slow = org.bouncycastle.asn1.pkcs.PBES2Parameters(
            org.bouncycastle.asn1.pkcs.KeyDerivationFunc(
                org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_PBKDF2,
                org.bouncycastle.asn1.pkcs.PBKDF2Params(kdf.salt, 20_000_000, kdf.prf),
            ),
            pbes2.encryptionScheme,
        )
        val patched = org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo(
            org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_PBES2, slow),
            info.encryptedData,
        ).encoded
        val text = "-----BEGIN ENCRYPTED PRIVATE KEY-----\n" + java.util.Base64.getEncoder().encodeToString(patched) + "\n-----END ENCRYPTED PRIVATE KEY-----\n"
        assertTrue(refused(text))
    }

    @Test fun hugeKeyTextIsRefused() {
        val big = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "A".repeat(SshKeys.MAX_KEY_TEXT + 1)
        assertTrue(runCatching { SshKeys.parse(big) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun der(pem: String, label: String = "OPENSSH PRIVATE KEY"): ByteArray =
        java.util.Base64.getDecoder().decode(pem.substringAfter("-----BEGIN $label-----").substringBefore("-----END").filterNot { it.isWhitespace() })

    /** Offset of the bcrypt rounds: magic, cipher, kdf name, kdf options (salt, then rounds). */
    private fun indexOfRounds(blob: ByteArray): Int {
        val b = java.nio.ByteBuffer.wrap(blob)
        var pos = "openssh-key-v1\u0000".length
        repeat(2) { pos += 4 + b.getInt(pos) } // cipher, kdf
        pos += 4 // kdf options length
        pos += 4 + b.getInt(pos) // salt
        return pos
    }
}
