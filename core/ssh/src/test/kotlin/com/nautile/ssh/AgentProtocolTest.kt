package com.nautile.ssh

import net.schmizz.sshj.common.Buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProtocolTest {
    private fun agentKey(algo: KeyAlgorithm): AgentKey {
        val m = SshKeys.generate(algo, "k")
        val p = SshKeys.load(m.privateKey, null)
        return AgentKey(p.public, p.private, "k-$algo")
    }

    @Test fun listsIdentities() {
        val keys = listOf(agentKey(KeyAlgorithm.ED25519), agentKey(KeyAlgorithm.ECDSA_256))
        val reply = Buffer.PlainBuffer(AgentProtocol.handle(byteArrayOf(11), keys))
        assertEquals(12, reply.readByte().toInt())
        assertEquals(2, reply.readUInt32AsInt())
        assertTrue(reply.readBytes().contentEquals(SshKeys.publicBlob(keys[0].public)))
        assertEquals("k-ED25519", reply.readString())
    }

    @Test fun signsWithMatchingKeyAndVerifies() {
        for (algo in listOf(KeyAlgorithm.ED25519, KeyAlgorithm.ECDSA_384, KeyAlgorithm.RSA_3072)) {
            val key = agentKey(algo)
            val data = "challenge".toByteArray()
            val req = Buffer.PlainBuffer().putByte(13).putBytes(SshKeys.publicBlob(key.public)).putBytes(data).putUInt32(2).compactData
            val reply = Buffer.PlainBuffer(AgentProtocol.handle(req, listOf(key)))
            assertEquals(algo.name, 14, reply.readByte().toInt())
            val sig = Buffer.PlainBuffer(reply.readBytes())
            val name = sig.readString()
            if (algo == KeyAlgorithm.RSA_3072) assertEquals("rsa-sha2-256", name)
            assertTrue(sig.readBytes().isNotEmpty())
        }
    }

    @Test fun unknownKeyFails() {
        val req = Buffer.PlainBuffer().putByte(13).putBytes(byteArrayOf(1, 2)).putBytes(byteArrayOf(3)).putUInt32(0).compactData
        assertEquals(5, AgentProtocol.handle(req, listOf(agentKey(KeyAlgorithm.ED25519)))[0].toInt())
    }
}
