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

    @Test fun signatureNeedsPermission() {
        val key = agentKey(KeyAlgorithm.ED25519)
        val req = Buffer.PlainBuffer().putByte(13).putBytes(SshKeys.publicBlob(key.public)).putBytes(byteArrayOf(1)).putUInt32(0).compactData
        var asked = 0
        assertEquals(5, AgentProtocol.handle(req, listOf(key)) { asked++; false }[0].toInt())
        assertEquals(1, asked)
        assertEquals(14, AgentProtocol.handle(req, listOf(key)) { true }[0].toInt())
    }

    @Test fun refusalHoldsForTheConnection() {
        val m = SshKeys.generate(KeyAlgorithm.ED25519, "k")
        val p = SshKeys.load(m.privateKey, null)
        val key = AgentKey(p.public, p.private, "k", confirm = true)
        val req = Buffer.PlainBuffer().putByte(13).putBytes(SshKeys.publicBlob(key.public)).putBytes(byteArrayOf(1)).putUInt32(0).compactData
        var asked = 0
        val consent = AgentConsent { asked++; false }
        repeat(20) { assertEquals(5, AgentProtocol.handle(req, listOf(key), consent::mayUse)[0].toInt()) }
        assertEquals("a refusal must not prompt again", 1, asked)

        var allowedAsks = 0
        val allowed = AgentConsent { allowedAsks++; true }
        repeat(3) { assertEquals(14, AgentProtocol.handle(req, listOf(key), allowed::mayUse)[0].toInt()) }
        assertEquals(1, allowedAsks)

        val failing = AgentConsent { error("dismissed") }
        assertEquals(5, AgentProtocol.handle(req, listOf(key), failing::mayUse)[0].toInt())
    }
}
