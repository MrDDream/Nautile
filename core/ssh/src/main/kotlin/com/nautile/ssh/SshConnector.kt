package com.nautile.ssh

import com.nautile.i18n.tr

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Opens SSH connections, possibly through a chain of jump hosts (ProxyJump). */
class SshConnector(
    private val store: KnownHostStore,
    private val prompts: SshPrompts,
    private val connectTimeoutMs: Int = 15_000,
    private val keepAliveSeconds: Int = 30,
) {
    /** [chain]: jump hosts in order, destination last. Blocking; call off the main thread. */
    fun connect(
        chain: List<SshTarget>,
        onStep: (String) -> Unit = {},
        /** Applies to the first hop only. */
        proxy: ProxyConfig? = null,
        /** Non-null enables agent forwarding on the destination with these keys. */
        agentKeys: (() -> List<AgentKey>)? = null,
    ): SshConnection {
        require(chain.isNotEmpty())
        val clients = mutableListOf<SSHClient>()
        try {
            chain.forEachIndexed { i, target ->
                onStep(if (i == chain.lastIndex) tr("Connexion à %1\$s…", target.label) else "Rebond via ${target.label}…")
                val client = SSHClient(DefaultConfig()).apply {
                    connectTimeout = connectTimeoutMs
                    timeout = 0
                    addHostKeyVerifier(KnownHostsVerifier(store, prompts))
                    if (i == 0 && proxy != null) socketFactory = ProxySocketFactory(proxy)
                }
                val previous = clients.lastOrNull()
                if (previous == null) client.connect(target.host, target.port)
                else client.connectVia(previous.newDirectConnection(target.host, target.port))
                clients += client
                client.connection.keepAlive.keepAliveInterval = keepAliveSeconds
                authenticate(client, target)
            }
            if (agentKeys != null) clients.last().connection.attach(AgentForwarder(clients.last().connection, agentKeys))
            return SshConnection(clients, agentForwarding = agentKeys != null)
        } catch (e: Exception) {
            clients.asReversed().forEach { runCatching { it.disconnect() } }
            throw e
        }
    }

    private fun authenticate(client: SSHClient, target: SshTarget) {
        when (val auth = target.auth) {
            is SshAuth.Password -> {
                var asked = false
                val finder = object : PasswordFinder {
                    override fun reqPassword(resource: Resource<*>?): CharArray {
                        val pw = auth.password ?: run {
                            asked = true
                            prompts.password(target) ?: throw SshCancelledException(tr("Connexion annulée"))
                        }
                        return pw.toCharArray()
                    }
                    override fun shouldRetry(resource: Resource<*>?) = false
                }
                try {
                    client.auth(target.username, AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
                } catch (e: UserAuthException) {
                    throw UserAuthException(if (asked || auth.password != null) tr("Mot de passe refusé") else e.message, e)
                }
            }
            is SshAuth.PrivateKey -> {
                val finder = object : PasswordFinder {
                    override fun reqPassword(resource: Resource<*>?): CharArray =
                        (auth.passphrase ?: prompts.passphrase(target) ?: throw SshCancelledException(tr("Connexion annulée"))).toCharArray()
                    override fun shouldRetry(resource: Resource<*>?) = false
                }
                val keys = client.loadKeys(auth.privateKey, null, finder)
                client.auth(target.username, AuthPublickey(keys))
            }
        }
    }
}

/** An authenticated connection; closing it also closes the jump hosts. */
class SshConnection internal constructor(
    private val clients: List<SSHClient>,
    private val agentForwarding: Boolean = false,
) : Closeable {
    val client: SSHClient get() = clients.last()
    val isConnected: Boolean get() = clients.all { it.isConnected && it.isAuthenticated }

    /** A session channel; asks for agent forwarding when enabled (ignored if the server refuses). */
    private fun newSession(): Session {
        if (!agentForwarding) return client.startSession()
        val chan = AgentSessionChannel(client.connection)
        chan.open()
        runCatching { chan.requestAgentForwarding() }
        return chan
    }

    fun openShell(columns: Int, rows: Int, term: String = "xterm-256color"): ShellChannel {
        val session = newSession()
        session.allocatePTY(term, columns, rows, 0, 0, mapOf(PTYMode.ECHO to 1))
        return ShellChannel(session, session.startShell())
    }

    /** Runs a command without a PTY and returns its stdout (used by the server monitor later). */
    fun exec(command: String): String = newSession().use { s ->
        s.exec(command).let { cmd ->
            val out = cmd.inputStream.readBytes().decodeToString()
            cmd.join()
            out
        }
    }

    override fun close() {
        clients.asReversed().forEach { runCatching { it.disconnect() } }
    }
}

class ShellChannel internal constructor(private val session: Session, private val shell: Session.Shell) : TerminalChannel {
    override val input: InputStream get() = shell.inputStream
    override val output: OutputStream get() = shell.outputStream
    val isOpen: Boolean get() = shell.isOpen

    override fun resize(columns: Int, rows: Int) {
        runCatching { shell.changeWindowDimensions(columns, rows, 0, 0) }
    }

    override fun close() {
        runCatching { shell.close() }
        runCatching { session.close() }
    }
}
