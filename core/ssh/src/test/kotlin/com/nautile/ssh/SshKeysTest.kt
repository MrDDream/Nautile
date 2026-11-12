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
}
