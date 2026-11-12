package com.nautile.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.transport.TransportException

/**
 * Runs against a real sshd (see scripts/ssh-it.sh). Skipped unless -PsshIT is given.
 * The sshd must allow TCP forwarding so the jump-host test can hop to itself.
 */
class SshConnectorIT {
    private val host = System.getenv("IT_HOST") ?: "127.0.0.1"
    private val port = System.getenv("IT_PORT")?.toInt() ?: 2222
    private val innerPort = System.getenv("IT_INNER_PORT")?.toInt() ?: 2222
    private val user = System.getenv("IT_USER") ?: "nautile"
    private val pass = System.getenv("IT_PASS") ?: "nautile"

    private class MemoryStore : KnownHostStore {
        val keys = mutableMapOf<Pair<String, Int>, MutableList<KnownKey>>()
        override fun find(host: String, port: Int) = keys[host to port].orEmpty()
        override fun trust(info: HostKeyInfo, replace: Boolean) {
            val list = keys.getOrPut(info.host to info.port) { mutableListOf() }
            if (replace) list.removeAll { it.keyType == info.keyType }
            list += KnownKey(info.keyType, info.publicKey, info.fingerprint)
        }
    }

    private class Prompts(var accept: Boolean = true, var password: String? = null) : SshPrompts {
        val asked = mutableListOf<Pair<HostKeyInfo, KnownKey?>>()
        override fun confirmHostKey(info: HostKeyInfo, previous: KnownKey?): Boolean { asked += info to previous; return accept }
        override fun password(target: SshTarget) = password
        override fun passphrase(target: SshTarget): String? = null
    }

    @Before fun enabled() = assumeTrue(System.getProperty("nautile.sshIT") == "true")

    @Test fun trustOnFirstUseThenSilent() {
        val store = MemoryStore(); val prompts = Prompts()
        val target = SshTarget(host, port, user, SshAuth.Password(pass))
        SshConnector(store, prompts).connect(listOf(target)).use { assertTrue(it.isConnected) }
        assertEquals(1, prompts.asked.size)
        assertTrue(prompts.asked.single().first.fingerprint.startsWith("SHA256:"))
        SshConnector(store, prompts).connect(listOf(target)).close()
        assertEquals("second connection must not prompt", 1, prompts.asked.size)
    }

    @Test fun changedKeyIsReportedAndCanBeRefused() {
        val store = MemoryStore(); val prompts = Prompts()
        val target = SshTarget(host, port, user, SshAuth.Password(pass))
        SshConnector(store, prompts).connect(listOf(target)).close()
        val real = store.keys.values.single().single()
        store.keys.values.single()[0] = real.copy(publicKey = "AAAA" + real.publicKey.drop(4).reversed())
        prompts.accept = false
        runCatching { SshConnector(store, prompts).connect(listOf(target)) }.let { r ->
            assertTrue(r.exceptionOrNull() is TransportException)
        }
        assertNotNull("previous key passed to prompt", prompts.asked.last().second)
    }

    @Test fun asksPasswordWhenMissingAndRejectsWrongOne() {
        val prompts = Prompts(password = pass)
        SshConnector(MemoryStore(), prompts).connect(listOf(SshTarget(host, port, user, SshAuth.Password(null)))).close()
        val r = runCatching { SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.Password("nope")))) }
        assertTrue(r.exceptionOrNull() is UserAuthException)
    }

    @Test fun shellEchoesCommandOutput() {
        SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.Password(pass)))).use { c ->
            val shell = c.openShell(80, 24)
            shell.output.write("echo nautile-\$((40+2)); exit\n".toByteArray()); shell.output.flush()
            val out = shell.input.readBytes().decodeToString()
            assertTrue(out, out.contains("nautile-42"))
            shell.close()
        }
    }

    private fun authorize(key: SshKeyMaterial) {
        SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.Password(pass)))).use { c ->
            c.exec("mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '${key.publicKey}' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys")
        }
    }

    @Test fun authenticatesWithGeneratedKeys() {
        for (algo in listOf(KeyAlgorithm.ED25519, KeyAlgorithm.ECDSA_256, KeyAlgorithm.RSA_3072)) {
            val key = SshKeys.generate(algo, "it-$algo")
            authorize(key)
            SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.PrivateKey(key.privateKey)))).use {
                assertEquals(algo.name, "ok", it.exec("echo ok").trim())
            }
        }
    }

    @Test fun authenticatesWithEncryptedKeyAskingPassphrase() {
        val key = SshKeys.generate(KeyAlgorithm.ED25519, "it-enc")
        authorize(key)
        val encrypted = SshKeys.export(key, null, "phrase", "it-enc")
        val prompts = object : SshPrompts by Prompts() {
            var asked = 0
            override fun passphrase(target: SshTarget): String { asked++; return "phrase" }
        }
        SshConnector(MemoryStore(), prompts).connect(listOf(SshTarget(host, port, user, SshAuth.PrivateKey(encrypted)))).close()
        assertEquals(1, prompts.asked)
    }

    @Test fun forwardsAgentToServer() {
        val key = SshKeys.generate(KeyAlgorithm.ED25519, "agent-key")
        val p = SshKeys.load(key.privateKey, null)
        val agent = listOf(AgentKey(p.public, p.private, "agent-key"))
        SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.Password(pass))), agentKeys = { agent }).use { c ->
            val out = c.exec("ssh-add -l 2>&1; echo SOCK=\$SSH_AUTH_SOCK")
            assertTrue(out, out.contains(key.fingerprint))
        }
    }

    private val socksPort = System.getenv("IT_SOCKS_PORT")?.toInt()
    private val httpPort = System.getenv("IT_HTTP_PORT")?.toInt()
    /** Name of the sshd as seen from the proxies (resolved by the proxy, not by us). */
    private val innerHost = System.getenv("IT_INNER_HOST") ?: "sshd"

    @Test fun connectsThroughSocks5ProxyWithAuth() {
        assumeTrue(socksPort != null)
        val proxy = ProxyConfig(ProxyType.SOCKS5, host, socksPort!!, "puser", "ppass")
        SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(innerHost, innerPort, user, SshAuth.Password(pass))), proxy = proxy).use {
            assertEquals("ok", it.exec("echo ok").trim())
        }
        val bad = proxy.copy(password = "nope")
        assertTrue(runCatching { SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(innerHost, innerPort, user, SshAuth.Password(pass))), proxy = bad) }.isFailure)
    }

    @Test fun connectsThroughHttpProxy() {
        assumeTrue(httpPort != null)
        val proxy = ProxyConfig(ProxyType.HTTP, host, httpPort!!)
        SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(innerHost, innerPort, user, SshAuth.Password(pass))), proxy = proxy).use {
            assertEquals("ok", it.exec("echo ok").trim())
        }
    }

    private fun banner(host: String, port: Int, proxy: ProxyConfig? = null): String {
        val socket = if (proxy != null) ProxySocketFactory(proxy).createSocket(host, port) else java.net.Socket(host, port)
        socket.use { s -> s.soTimeout = 10_000; return s.getInputStream().bufferedReader().readLine() }
    }

    @Test fun localForwardReachesServerSide() {
        SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.Password(pass)))).use { c ->
            c.startForward(ForwardSpec(ForwardType.LOCAL, "127.0.0.1", 0, "127.0.0.1", innerPort)).use { f ->
                assertTrue(banner("127.0.0.1", f.boundPort).startsWith("SSH-2.0"))
            }
        }
    }

    @Test fun dynamicForwardIsASocksProxy() {
        SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.Password(pass)))).use { c ->
            c.startForward(ForwardSpec(ForwardType.DYNAMIC, "127.0.0.1", 0)).use { f ->
                val proxy = ProxyConfig(ProxyType.SOCKS5, "127.0.0.1", f.boundPort)
                assertTrue(banner("127.0.0.1", innerPort, proxy).startsWith("SSH-2.0"))
                assertTrue("remote name resolution", banner("localhost", innerPort, proxy).startsWith("SSH-2.0"))
            }
        }
    }

    @Test fun remoteForwardComesBackHere() {
        java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1")).use { local ->
            Thread {
                runCatching { local.accept().use { it.getOutputStream().write("pong-nautile\n".toByteArray()) } }
            }.apply { isDaemon = true }.start()
            SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.Password(pass)))).use { c ->
                c.startForward(ForwardSpec(ForwardType.REMOTE, "127.0.0.1", 19099, "127.0.0.1", local.localPort)).use {
                    val out = c.exec("timeout 8 sh -c 'nc 127.0.0.1 19099 < /dev/null || bash -c \"cat < /dev/tcp/127.0.0.1/19099\"' 2>&1 | head -1")
                    assertTrue(out, out.contains("pong-nautile"))
                }
            }
        }
    }

    @Test fun sftpRoundTrip() {
        SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.Password(pass)))).use { c ->
            c.openSftp().use { sftp ->
                val home = sftp.home()
                val dir = SftpSession.join(home, "coq-it-${System.nanoTime()}")
                sftp.mkdir(dir)
                sftp.mkdir(SftpSession.join(dir, "sub"))
                val payload = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
                var reported = 0L
                sftp.openWrite(SftpSession.join(dir, "data.bin")).use { copyWithProgress(payload.inputStream(), it, { p -> reported = p }) }
                assertEquals(payload.size.toLong(), reported)
                val listing = sftp.list(dir)
                assertEquals(listOf("sub", "data.bin"), listing.map { it.name })
                assertTrue(listing[0].isDirectory)
                assertEquals(payload.size.toLong(), listing[1].size)
                val back = sftp.openRead(SftpSession.join(dir, "data.bin")).use { it.readBytes() }
                assertTrue(back.contentEquals(payload))
                assertEquals(payload.size - 1000, sftp.openRead(SftpSession.join(dir, "data.bin"), 1000).use { it.readBytes() }.size)
                sftp.rename(SftpSession.join(dir, "data.bin"), SftpSession.join(dir, "sub/moved.bin"))
                sftp.chmod(SftpSession.join(dir, "sub/moved.bin"), "600".toInt(8))
                assertEquals("600".toInt(8), sftp.stat(SftpSession.join(dir, "sub/moved.bin")).permissions)
                sftp.delete(dir, recursive = true)
                assertTrue(!sftp.exists(dir))
            }
        }
    }

    @Test fun moshSessionRunsCommands() {
        val conn = SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.Password(pass))))
        val mosh = conn.use { MoshChannel.start(it, host, 100, 30, "mosh-server new -s -p 60001:60005 -c 256 -l LANG=C.UTF-8") }
        mosh.use { ch ->
            val seen = StringBuilder()
            val reader = Thread {
                val buf = ByteArray(4096)
                while (true) { val n = runCatching { ch.input.read(buf) }.getOrDefault(-1); if (n < 0) break; synchronized(seen) { seen.append(String(buf, 0, n)) } }
            }.apply { isDaemon = true; start() }
            val deadline = System.currentTimeMillis() + 15_000
            Thread.sleep(1500)
            ch.output.write("echo mosh-ok-\$((6*7))\r".toByteArray())
            while (System.currentTimeMillis() < deadline && synchronized(seen) { !seen.contains("mosh-ok-42") }) Thread.sleep(100)
            assertTrue("output: " + synchronized(seen) { seen.toString().takeLast(500) }, synchronized(seen) { seen.contains("mosh-ok-42") })
            ch.resize(120, 40)
            ch.output.write("stty size\r".toByteArray())
            val d2 = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < d2 && synchronized(seen) { !seen.contains("40 120") }) Thread.sleep(100)
            assertTrue("resize propagated", synchronized(seen) { seen.contains("40 120") })
            reader.isDaemon
        }
    }

    @Test fun serverStatsFromRealHost() {
        SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.Password(pass)))).use { c ->
            val s = ServerStats.parse(c.exec(ServerStats.COMMAND))
            assertTrue(s.memTotalKb > 0); assertTrue(s.cores >= 1); assertTrue(s.diskTotalKb > 0); assertNotNull(s.cpuPercent)
        }
    }

    @Test fun detectsOsOfRealHost() {
        SshConnector(MemoryStore(), Prompts()).connect(listOf(SshTarget(host, port, user, SshAuth.Password(pass)))).use { c ->
            assertEquals("alpine", OsDetector.parse(c.exec(OsDetector.COMMAND)))
        }
    }

    @Test fun connectsThroughJumpHost() {
        val jump = SshTarget(host, port, user, SshAuth.Password(pass), label = "jump")
        val dest = SshTarget("127.0.0.1", innerPort, user, SshAuth.Password(pass), label = "dest")
        val steps = mutableListOf<String>()
        SshConnector(MemoryStore(), Prompts()).connect(listOf(jump, dest), steps::add).use { c ->
            assertTrue(c.isConnected)
            assertEquals("ok", c.exec("echo ok").trim())
        }
        assertEquals(2, steps.size)
    }
}
