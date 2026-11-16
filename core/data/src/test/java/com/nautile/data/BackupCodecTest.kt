package com.nautile.data

import com.nautile.data.backup.BackupCodec
import com.nautile.data.backup.BackupData
import com.nautile.data.backup.WrongBackupPasswordException
import com.nautile.data.model.AuthMethod
import com.nautile.data.model.ForwardKind
import com.nautile.data.model.Host
import com.nautile.data.model.PortForward
import com.nautile.data.model.ProxyKind
import com.nautile.data.model.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    private val data = BackupData(
        hosts = listOf(Host(id = 3, alias = "nas", hostname = "10.0.0.2", password = "s3cret", authMethod = AuthMethod.PASSWORD, proxyType = ProxyKind.SOCKS5)),
        snippets = listOf(Snippet(id = 1, name = "logs", command = "docker logs \${SVC}")),
        forwards = listOf(PortForward(id = 1, name = "web", hostId = 3, kind = ForwardKind.LOCAL, bindPort = 8080, destHost = "127.0.0.1", destPort = 80)),
    )

    @Test fun roundTripWithPassword() {
        val file = BackupCodec.export(data, "correct horse", memoryKiB = 64, iterations = 1)
        assertTrue(file.startsWith(BackupCodec.MAGIC))
        // The ciphertext is base64: « nas » can appear in it by chance (a few times in a thousand runs), never
        // with the quotes of the clear JSON around it.
        assertFalse("secrets must not appear in clear", file.contains("s3cret") || file.contains("\"nas\"") || file.contains("10.0.0.2"))
        val back = BackupCodec.import(file, "correct horse")
        assertEquals(data.hosts, back.hosts)
        assertEquals(data.snippets, back.snippets)
        assertEquals(data.forwards, back.forwards)
    }

    @Test fun wrongPasswordOrTamperingIsRejected() {
        val file = BackupCodec.export(data, "pw", memoryKiB = 64, iterations = 1)
        assertTrue(runCatching { BackupCodec.import(file, "nope") }.exceptionOrNull() is WrongBackupPasswordException)
        val tampered = file.replace("\"t\":1", "\"t\":2")
        assertTrue(runCatching { BackupCodec.import(tampered, "pw") }.isFailure)
        assertTrue(runCatching { BackupCodec.import("hello", "pw") }.isFailure)
    }

    @Test fun outOfBoundsKdfParametersAreRefusedBeforeDeriving() {
        val file = BackupCodec.export(data, "pw", memoryKiB = 64, iterations = 1)
        for (bad in listOf("\"m\":1048576", "\"m\":262145")) {
            val e = runCatching { BackupCodec.import(file.replace("\"m\":64", bad), "pw") }.exceptionOrNull()
            assertTrue("$bad: $e", e is IllegalArgumentException && e !is WrongBackupPasswordException)
        }
        assertTrue(runCatching { BackupCodec.import(file.replace("\"t\":1", "\"t\":11"), "pw") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { BackupCodec.import(file.replace("\"p\":1", "\"p\":9"), "pw") }.exceptionOrNull() is IllegalArgumentException)
        // Memory below 8 KiB per lane is refused too.
        val thin = file.replace("\"m\":64", "\"m\":63").replace("\"p\":1", "\"p\":8")
        assertTrue(runCatching { BackupCodec.import(thin, "pw") }.exceptionOrNull() is IllegalArgumentException)
        // The bounds themselves stay accepted (here only up to the password check).
        val atLimit = file.replace("\"t\":1", "\"t\":${BackupCodec.MAX_ITERATIONS}")
        assertTrue(runCatching { BackupCodec.import(atLimit, "pw") }.exceptionOrNull() is WrongBackupPasswordException)
    }

    /** A file sealed like [BackupCodec.export], around any [plain] text. */
    private fun sealedFile(plain: String, password: String): String {
        val salt = ByteArray(16) { 1 }
        val nonce = ByteArray(12) { 2 }
        val b64 = java.util.Base64.getEncoder()
        val header = """{"kdf":"argon2id","m":64,"t":1,"p":1,"salt":"${b64.encodeToString(salt)}","nonce":"${b64.encodeToString(nonce)}"}"""
        val gen = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        gen.init(
            org.bouncycastle.crypto.params.Argon2Parameters.Builder(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
                .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt).withMemoryAsKB(64).withIterations(1).withParallelism(1).build(),
        )
        val key = ByteArray(32).also { gen.generateBytes(password.toCharArray(), it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
        cipher.updateAAD(header.toByteArray())
        return "${BackupCodec.MAGIC}\n$header\n${b64.encodeToString(cipher.doFinal(plain.toByteArray()))}\n"
    }

    @Test fun anIncompatibleContentNeverQuotesTheDecryptedText() {
        val file = sealedFile("""{"hosts":[{"alias":"nas","password":"hunter2-secret","port":"not a number"}]}""", "pw")
        val e = runCatching { BackupCodec.import(file, "pw") }.exceptionOrNull()
        assertTrue(e.toString(), e is IllegalArgumentException && e !is kotlinx.serialization.SerializationException)
        assertFalse(e!!.message.orEmpty().contains("hunter2"))
        assertTrue(e.cause == null)
    }
}
