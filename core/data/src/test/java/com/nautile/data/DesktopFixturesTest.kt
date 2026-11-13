package com.nautile.data

import com.nautile.data.backup.BackupCodec
import com.nautile.data.backup.BackupData
import com.nautile.data.backup.SyncDevice
import com.nautile.data.model.AuthMethod
import com.nautile.data.model.ForwardKind
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.Identity
import com.nautile.data.model.KnownHost
import com.nautile.data.model.PortForward
import com.nautile.data.model.Protocol
import com.nautile.data.model.ProxyKind
import com.nautile.data.model.Snippet
import com.nautile.data.model.SshKey
import com.nautile.data.model.SyncTombstone
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Compatibility files for the desktop app (Rust), which must read what Android writes and the reverse:
 * `./gradlew :core:data:testDebugUnitTest --tests '*DesktopFixturesTest*' -PnautileFixtures`
 * writes them to desktop/tests/fixtures; without the property, only the reverse check runs (if the desktop wrote its file).
 */
class DesktopFixturesTest {
    private val dir = System.getProperty("nautile.fixtures").orEmpty()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Every kind of record, with the fields that are easy to lose: nulls, RDP/VNC settings, unicode, references. */
    private val data = BackupData(
        exportedAt = 1_762_000_000_000,
        groups = listOf(
            HostGroup(id = 1, name = "Homelab", color = 0xFFFFB547, sortOrder = 0, syncId = "a".repeat(32), updatedAt = 1_761_000_000_001),
            HostGroup(id = 2, name = "Bureau ☕", color = 0xFF5BD69A, sortOrder = 1, syncId = "b".repeat(32), updatedAt = 1_761_000_000_002),
        ),
        keys = listOf(
            SshKey(
                id = 5, name = "laptop", keyType = "ssh-ed25519", description = "ED25519",
                publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGq0 laptop",
                privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\n",
                fingerprint = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG", encrypted = true, savedPassphrase = "phrase",
                generated = true, createdAt = 1_760_000_000_000, syncId = "c".repeat(32), updatedAt = 1_761_000_000_003,
            ),
        ),
        identities = listOf(
            Identity(id = 7, name = "admin", username = "root", password = null, keyId = 5, syncId = "d".repeat(32), updatedAt = 1_761_000_000_004),
        ),
        hosts = listOf(
            Host(
                id = 10, alias = "docker-01", hostname = "10.0.0.20", port = 22, username = "mrd", protocol = Protocol.SSH,
                authMethod = AuthMethod.KEY, keyId = 5, agentForwarding = true, groupId = 1, color = 0xFFFFB547,
                tags = "prod,docker", startupCommand = "htop", osId = "debian", createdAt = 1_760_000_000_000,
                lastConnectedAt = 1_761_500_000_000, syncId = "e".repeat(32), updatedAt = 1_761_000_000_005,
            ),
            Host(
                id = 11, alias = "nas", hostname = "nas.lan", port = 2222, username = "admin", protocol = Protocol.MOSH,
                authMethod = AuthMethod.PASSWORD, password = "pä$\$wörd \"quoted\"", jumpHostId = 10,
                proxyType = ProxyKind.SOCKS5, proxyHost = "proxy.lan", proxyPort = 1080, proxyUsername = "u", proxyPassword = "p",
                knockSequence = "7000,u:8000,t:9000", groupId = 1, createdAt = 1_760_000_000_000, syncId = "f".repeat(32), updatedAt = 1_761_000_000_006,
            ),
            Host(
                id = 12, alias = "pc-bureau", hostname = "pc.lan", port = 3389, username = "Administrateur", protocol = Protocol.RDP,
                authMethod = AuthMethod.IDENTITY, identityId = 7, rdpDomain = "CORP", rdpResolution = "1920x1080", rdpScale = 150,
                rdpSecurity = "NLA", rdpAudio = false, rdpConsole = true, groupId = 2, createdAt = 1_760_000_000_000,
                syncId = "1".repeat(32), updatedAt = 1_761_000_000_007,
            ),
            Host(id = 13, alias = "pi", hostname = "pi.lan", port = 5900, protocol = Protocol.VNC, password = "vnc", createdAt = 1_760_000_000_000, syncId = "2".repeat(32), updatedAt = 1_761_000_000_008),
            Host(id = 14, alias = "routeur", hostname = "192.168.1.1", port = 23, protocol = Protocol.TELNET, createdAt = 1_760_000_000_000, syncId = "3".repeat(32), updatedAt = 1_761_000_000_009),
            Host(id = 15, alias = "usb", hostname = "", port = 0, protocol = Protocol.SERIAL, serialBaud = 115200, createdAt = 1_760_000_000_000, syncId = "4".repeat(32), updatedAt = 1_761_000_000_010),
            Host(id = 16, alias = "legacy", hostname = "old.lan", authMethod = AuthMethod.AGENT, identity = "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----", createdAt = 1_760_000_000_000, syncId = "5".repeat(32), updatedAt = 1),
        ),
        knownHosts = listOf(
            KnownHost(id = 1, host = "10.0.0.20", port = 22, keyType = "ssh-ed25519", publicKey = "AAAAC3NzaC1lZDI1NTE5AAAAIGq0", fingerprint = "SHA256:xyz", addedAt = 1_760_000_000_000),
        ),
        snippets = listOf(
            Snippet(id = 1, name = "Journal d'un service", command = "journalctl -u \${SERVICE} -n 100\nsystemctl status \${SERVICE}", tag = "Système", runCount = 17, createdAt = 1_760_000_000_000, syncId = "6".repeat(32), updatedAt = 1_761_000_000_011),
        ),
        forwards = listOf(
            PortForward(id = 1, name = "web", hostId = 10, kind = ForwardKind.LOCAL, bindPort = 8080, destHost = "127.0.0.1", destPort = 80, autoStart = true, syncId = "7".repeat(32), updatedAt = 1_761_000_000_012),
            PortForward(id = 2, name = "socks", hostId = 11, kind = ForwardKind.DYNAMIC, bindHost = "0.0.0.0", bindPort = 1080, syncId = "8".repeat(32), updatedAt = 1_761_000_000_013),
        ),
        tombstones = listOf(
            SyncTombstone("9".repeat(32), "host", 1_761_000_000_100),
            SyncTombstone("kh:old.lan:22:ssh-rsa", "known_host", 1_761_000_000_200),
        ),
        devices = listOf(SyncDevice("0b5c6b0e-6a2d-4c1f-9d3e-6f1d2a3b4c5d", "Pixel 9", 1_761_900_000_000)),
    )

    @Test fun writeForDesktop() {
        assumeTrue("only with -PnautileFixtures", dir.isNotEmpty())
        val out = File(dir).apply { mkdirs() }
        File(out, "android-backup.json").writeText(json.encodeToString(BackupData.serializer(), data))
        // Default Argon2id parameters, as the app writes them.
        File(out, "android.backup").writeText(BackupCodec.export(data, PASSWORD))
        File(out, "README.md").writeText(
            """
            # Fichiers de compatibilité Android ↔ Desktop

            Générés par `DesktopFixturesTest` (core/data) et `LanFixturesTest` (app) :
            `./gradlew :core:data:testDebugUnitTest :app:testDebugUnitTest --tests '*FixturesTest*' -PnautileFixtures`

            - `android.backup` : sauvegarde chiffrée par l'app Android (mot de passe `$PASSWORD`).
            - `android-backup.json` : son contenu en clair, tel que sérialisé par Android.
            - `lan-*.json` : poignées de main du protocole Wi-Fi v1 enregistrées avec des clés fixes.
            - `desktop.backup` : écrit par l'app Desktop ; `DesktopFixturesTest.readsDesktopBackup` vérifie qu'Android le relit.
            """.trimIndent() + "\n",
        )
    }

    /** The reverse direction: a file written by the desktop app must open on Android with nothing lost. */
    @Test fun readsDesktopBackup() {
        val file = File(dir.ifEmpty { "../../desktop/tests/fixtures" }, "desktop.backup")
        assumeTrue("desktop.backup not written yet", file.exists())
        val back = BackupCodec.import(file.readText(), PASSWORD)
        assertEquals(data.copy(exportedAt = back.exportedAt), back)
    }

    companion object {
        const val PASSWORD = "nautile-compat ✓"
    }
}
