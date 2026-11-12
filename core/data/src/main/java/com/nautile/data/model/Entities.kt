package com.nautile.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Protocol { SSH, MOSH, TELNET, SERIAL, LOCAL, RDP, VNC }

/** A remote desktop (RDP or VNC) rather than a terminal. */
val Protocol.isDesktop: Boolean get() = this == Protocol.RDP || this == Protocol.VNC

/** AGENT is a legacy value from milestone 1, handled like PASSWORD. */
enum class AuthMethod { PASSWORD, KEY, AGENT, IDENTITY }

enum class ProxyKind { SOCKS5, HTTP }

@Entity(tableName = "host_group", indices = [Index(value = ["name"], unique = true)])
@kotlinx.serialization.Serializable
data class HostGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = DEFAULT_COLOR,
    val sortOrder: Int = 0,
    /** Same record on every device (synchronisation). Set by a trigger when empty. */
    @ColumnInfo(defaultValue = "") val syncId: String = "",
    /** Last change in epoch millis, kept by triggers; the latest change wins when syncing. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

@Entity(
    tableName = "host",
    foreignKeys = [
        ForeignKey(entity = HostGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = Host::class, parentColumns = ["id"], childColumns = ["jumpHostId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = SshKey::class, parentColumns = ["id"], childColumns = ["keyId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = Identity::class, parentColumns = ["id"], childColumns = ["identityId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("groupId"), Index("jumpHostId"), Index("keyId"), Index("identityId")],
)
@kotlinx.serialization.Serializable
data class Host(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    val hostname: String,
    val port: Int = 22,
    val username: String = "",
    val protocol: Protocol = Protocol.SSH,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    /** Stored in the SQLCipher-encrypted database. */
    val password: String? = null,
    /** Legacy (milestone 2): private key text, moved into [SshKey] at startup. */
    val identity: String? = null,
    val keyId: Long? = null,
    val identityId: Long? = null,
    @ColumnInfo(defaultValue = "0") val agentForwarding: Boolean = false,
    val proxyType: ProxyKind? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    /** Port knocking before connecting, e.g. `7000,8000,u:9000`. */
    val knockSequence: String? = null,
    /** Baud rate for SERIAL hosts. */
    val serialBaud: Int? = null,
    /** RDP: Windows domain. */
    val rdpDomain: String? = null,
    /** RDP: desktop size `WIDTHxHEIGHT`; null fits the screen. */
    val rdpResolution: String? = null,
    /** RDP: Windows desktop scale in percent; null picks one from the screen density. */
    val rdpScale: Int? = null,
    /** RDP: NLA, TLS or RDP; null negotiates. */
    val rdpSecurity: String? = null,
    /** RDP: play the remote sound on the phone. */
    @ColumnInfo(defaultValue = "1") val rdpAudio: Boolean = true,
    /** RDP: console session (mstsc /admin). */
    @ColumnInfo(defaultValue = "0") val rdpConsole: Boolean = false,
    /** Detected operating system (ubuntu, debian, macos…), for the host's logo. */
    val osId: String? = null,
    val groupId: Long? = null,
    val jumpHostId: Long? = null,
    val color: Long = DEFAULT_COLOR,
    /** Comma-separated tags. */
    val tags: String = "",
    val startupCommand: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
    /** Same record on every device (synchronisation). Set by a trigger when empty. */
    @ColumnInfo(defaultValue = "") val syncId: String = "",
    /** Last change in epoch millis, kept by triggers; the latest change wins when syncing. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
) {

    /** `user@host[:port]` as shown under the alias. */
    val address: String
        get() = when (protocol) {
            Protocol.LOCAL -> "ce téléphone"
            Protocol.SERIAL -> "USB série · ${serialBaud ?: 115_200} bauds"
            else -> networkAddress
        }

    private val networkAddress: String
        get() = buildString {
            if (username.isNotBlank()) append(username).append('@')
            append(hostname)
            if (port != protocol.defaultPort) append(':').append(port)
        }
}

val Protocol.defaultPort: Int
    get() = when (this) {
        Protocol.SSH, Protocol.MOSH -> 22
        Protocol.TELNET -> 23
        Protocol.RDP -> 3389
        Protocol.VNC -> 5900
        Protocol.SERIAL, Protocol.LOCAL -> 0
    }

const val DEFAULT_COLOR: Long = 0xFFFFB547

/** Palette offered by the host editor (matches the design). */
val HOST_COLORS: List<Long> = listOf(0xFFFFB547, 0xFF7AA2F7, 0xFF5BD69A, 0xFFF38BA8, 0xFF5A6672)

/** A trusted SSH host key (known_hosts). */
@Entity(tableName = "known_host", indices = [Index(value = ["host", "port", "keyType"], unique = true)])
@kotlinx.serialization.Serializable
data class KnownHost(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val host: String,
    val port: Int,
    val keyType: String,
    val publicKey: String,
    val fingerprint: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/** A private key of the keychain. Stored in the SQLCipher-encrypted database. */
@Entity(tableName = "ssh_key", indices = [Index(value = ["fingerprint"], unique = true)])
@kotlinx.serialization.Serializable
data class SshKey(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** e.g. ssh-ed25519 */
    val keyType: String,
    /** e.g. "RSA 4096" */
    val description: String,
    /** authorized_keys line */
    val publicKey: String,
    val privateKey: String,
    val fingerprint: String,
    /** The private text is protected by a passphrase. */
    val encrypted: Boolean,
    /** Remembered passphrase, if the user chose to. */
    val savedPassphrase: String? = null,
    val generated: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** Same record on every device (synchronisation). Set by a trigger when empty. */
    @ColumnInfo(defaultValue = "") val syncId: String = "",
    /** Last change in epoch millis, kept by triggers; the latest change wins when syncing. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

/** Reusable credentials: a user name plus a password and/or a key. */
@Entity(
    tableName = "identity",
    foreignKeys = [ForeignKey(entity = SshKey::class, parentColumns = ["id"], childColumns = ["keyId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("keyId")],
)
@kotlinx.serialization.Serializable
data class Identity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val username: String,
    val password: String? = null,
    val keyId: Long? = null,
    /** Same record on every device (synchronisation). Set by a trigger when empty. */
    @ColumnInfo(defaultValue = "") val syncId: String = "",
    /** Last change in epoch millis, kept by triggers; the latest change wins when syncing. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

/** A saved command. `${NAME}` placeholders are asked for before running. */
@Entity(tableName = "snippet")
@kotlinx.serialization.Serializable
data class Snippet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val command: String,
    val tag: String = "",
    @ColumnInfo(defaultValue = "0") val runCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Same record on every device (synchronisation). Set by a trigger when empty. */
    @ColumnInfo(defaultValue = "") val syncId: String = "",
    /** Last change in epoch millis, kept by triggers; the latest change wins when syncing. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
) {
    val variables: List<String>
        get() = VARIABLE.findAll(command).map { it.groupValues[1] }.distinct().toList()

    /** The command with `${NAME}` replaced by [values]; unknown names are left as is. */
    fun fill(values: Map<String, String>): String =
        VARIABLE.replace(command) { m -> values[m.groupValues[1]] ?: m.value }

    companion object {
        private val VARIABLE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
    }
}

enum class ForwardKind { LOCAL, REMOTE, DYNAMIC }

/** A port forwarding rule through [hostId]. */
@Entity(
    tableName = "port_forward",
    foreignKeys = [ForeignKey(entity = Host::class, parentColumns = ["id"], childColumns = ["hostId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("hostId")],
)
@kotlinx.serialization.Serializable
data class PortForward(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val hostId: Long,
    val kind: ForwardKind,
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val destHost: String = "",
    val destPort: Int = 0,
    /** Start with every terminal session to [hostId]. */
    @ColumnInfo(defaultValue = "0") val autoStart: Boolean = false,
    /** Same record on every device (synchronisation). Set by a trigger when empty. */
    @ColumnInfo(defaultValue = "") val syncId: String = "",
    /** Last change in epoch millis, kept by triggers; the latest change wins when syncing. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

/** A command typed on a host, for autocompletion. Only lines echoed by the server are kept (never passwords). */
@Entity(
    tableName = "command_history",
    foreignKeys = [ForeignKey(entity = Host::class, parentColumns = ["id"], childColumns = ["hostId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["hostId", "command"], unique = true)],
)
data class CommandHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val command: String,
    val count: Int = 1,
    val lastUsed: Long = System.currentTimeMillis(),
)

/** One connection made from the app, for the « Connexions › Historique » list (saved hosts and quick connections). */
@Entity(tableName = "connection_log", indices = [Index("at"), Index("hostId")])
data class ConnectionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The saved host, null for a quick connection (`root@192.168.1.2`). */
    val hostId: Long? = null,
    val alias: String,
    val protocol: Protocol,
    val username: String = "",
    val hostname: String,
    val port: Int,
    val at: Long = System.currentTimeMillis(),
)

/** A deleted record, so that the deletion reaches the other devices. [syncId] is the record's (or `kh:host:port:type`). */
@Entity(tableName = "sync_tombstone")
@kotlinx.serialization.Serializable
data class SyncTombstone(
    @PrimaryKey val syncId: String,
    /** Table of the record. */
    val kind: String,
    val deletedAt: Long,
)
