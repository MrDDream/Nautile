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
        assertFalse("secrets must not appear in clear", file.contains("s3cret") || file.contains("nas"))
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
}
