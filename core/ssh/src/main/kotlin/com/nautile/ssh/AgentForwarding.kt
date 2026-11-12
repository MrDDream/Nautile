package com.nautile.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannelOpener
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureECDSA
import net.schmizz.sshj.signature.SignatureRSA
import com.hierynomus.sshj.signature.SignatureEdDSA
import java.io.DataInputStream
import java.io.EOFException
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.TimeUnit

/** A key the forwarded agent may sign with. */
class AgentKey(val public: PublicKey, val private: PrivateKey, val comment: String)

/** Serves `auth-agent@openssh.com` channels opened by the server with [keys]. */
internal class AgentForwarder(conn: Connection, private val keys: () -> List<AgentKey>) :
    AbstractForwardedChannelOpener(TYPE, conn) {

    override fun handleOpen(buf: SSHPacket) {
        val chan = AgentChannel(conn, buf.readUInt32AsInt(), buf.readUInt32(), buf.readUInt32())
        chan.confirm()
        Thread({ serve(chan) }, "ssh-agent").apply { isDaemon = true }.start()
    }

    private fun serve(chan: AgentChannel) {
        val inp = DataInputStream(chan.inputStream)
        val out = chan.outputStream
        try {
            while (true) {
                val len = inp.readInt()
                if (len <= 0 || len > 256 * 1024) break
                val msg = ByteArray(len).also(inp::readFully)
                val reply = AgentProtocol.handle(msg, keys())
                out.write(Buffer.PlainBuffer().putBytes(reply).compactData)
                out.flush()
            }
        } catch (_: EOFException) {
        } catch (_: Exception) {
        } finally {
            runCatching { chan.close() }
        }
    }

    private class AgentChannel(conn: Connection, recipient: Int, window: Long, maxPacket: Long) :
        AbstractForwardedChannel(conn, TYPE, recipient, window, maxPacket, "", 0)

    companion object { const val TYPE = "auth-agent@openssh.com" }
}

/** The subset of the ssh-agent protocol a forwarded agent needs (list and sign). */
object AgentProtocol {
    private const val FAILURE = 5
    private const val REQUEST_IDENTITIES = 11
    private const val IDENTITIES_ANSWER = 12
    private const val SIGN_REQUEST = 13
    private const val SIGN_RESPONSE = 14
    private const val RSA_SHA2_256 = 2
    private const val RSA_SHA2_512 = 4

    fun handle(msg: ByteArray, keys: List<AgentKey>): ByteArray = try {
        val buf = Buffer.PlainBuffer(msg)
        when (buf.readByte().toInt()) {
            REQUEST_IDENTITIES -> Buffer.PlainBuffer().putByte(IDENTITIES_ANSWER.toByte()).putUInt32(keys.size.toLong()).apply {
                keys.forEach { putBytes(SshKeys.publicBlob(it.public)).putString(it.comment) }
            }.compactData
            SIGN_REQUEST -> {
                val blob = buf.readBytes()
                val data = buf.readBytes()
                val flags = buf.readUInt32().toInt()
                val key = keys.firstOrNull { SshKeys.publicBlob(it.public).contentEquals(blob) } ?: return failure()
                val sig = signer(key.public, flags)
                sig.initSign(key.private)
                sig.update(data)
                val encoded = Buffer.PlainBuffer().putString(sig.signatureName).putBytes(sig.encode(sig.sign())).compactData
                Buffer.PlainBuffer().putByte(SIGN_RESPONSE.toByte()).putBytes(encoded).compactData
            }
            else -> failure()
        }
    } catch (_: Exception) {
        failure()
    }

    private fun failure() = byteArrayOf(FAILURE.toByte())

    private fun signer(pub: PublicKey, flags: Int): Signature = when (KeyType.fromKey(pub)) {
        KeyType.RSA -> when {
            flags and RSA_SHA2_512 != 0 -> SignatureRSA.FactoryRSASHA512().create()
            flags and RSA_SHA2_256 != 0 -> SignatureRSA.FactoryRSASHA256().create()
            else -> SignatureRSA.FactorySSHRSA().create()
        }
        KeyType.ECDSA256 -> SignatureECDSA.Factory256().create()
        KeyType.ECDSA384 -> SignatureECDSA.Factory384().create()
        KeyType.ECDSA521 -> SignatureECDSA.Factory521().create()
        KeyType.ED25519 -> SignatureEdDSA.Factory().create()
        else -> throw IllegalArgumentException("Type non pris en charge")
    }
}

/** A session channel that can ask the server to forward the agent. */
internal class AgentSessionChannel(private val connection: Connection) : SessionChannel(connection) {
    fun requestAgentForwarding() {
        sendChannelRequest("auth-agent-req@openssh.com", true, Buffer.PlainBuffer())
            .await(connection.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    }
}
