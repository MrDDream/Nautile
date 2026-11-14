package com.nautile.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

class KnownHostsVerifierTest {
    private class Store(val keys: MutableList<KnownKey> = mutableListOf()) : KnownHostStore {
        var replaced: Boolean? = null
        override fun find(host: String, port: Int) = keys.toList()
        override fun trust(info: HostKeyInfo, replace: Boolean) {
            replaced = replace
            if (replace) keys.removeAll { it.keyType == info.keyType }
            keys += KnownKey(info.keyType, info.publicKey, info.fingerprint)
        }
    }

    private class Prompts(val answer: Boolean) : SshPrompts {
        var asked: Pair<HostKeyInfo, KnownKey?>? = null
        override fun confirmHostKey(info: HostKeyInfo, previous: KnownKey?): Boolean { asked = info to previous; return answer }
        override fun password(target: SshTarget): String? = null
        override fun passphrase(target: SshTarget): String? = null
    }

    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
    private val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public

    @Test fun firstConnectionAsksWithoutPrevious() {
        val store = Store(); val prompts = Prompts(true)
        assertTrue(KnownHostsVerifier(store, prompts).verify("h", 22, rsa))
        assertNull(prompts.asked!!.second)
        assertEquals(false, store.replaced)
    }

    @Test fun knownKeyIsAcceptedSilently() {
        val info = KnownHostsVerifier.describe("h", 22, rsa)
        val store = Store(mutableListOf(KnownKey(info.keyType, info.publicKey, info.fingerprint))); val prompts = Prompts(false)
        assertTrue(KnownHostsVerifier(store, prompts).verify("h", 22, rsa))
        assertNull(prompts.asked)
    }

    @Test fun otherKeyTypeIsReportedAsChangedKey() {
        // Known with ECDSA; a man in the middle offers only RSA.
        val known = KnownHostsVerifier.describe("h", 22, ec)
        val store = Store(mutableListOf(KnownKey(known.keyType, known.publicKey, known.fingerprint)))
        val refuse = Prompts(false)
        assertFalse(KnownHostsVerifier(store, refuse).verify("h", 22, rsa))
        val (info, previous) = refuse.asked!!
        assertNotNull(previous)
        assertEquals(known.fingerprint, previous!!.fingerprint)
        assertTrue(info.keyType != previous.keyType)
        assertEquals(1, store.keys.size) // nothing trusted

        val accept = Prompts(true)
        assertTrue(KnownHostsVerifier(store, accept).verify("h", 22, rsa))
        assertEquals(false, store.replaced) // the ECDSA key stays known
    }

    @Test fun sameTypeChangeReplaces() {
        val old = KnownHostsVerifier.describe("h", 22, KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public)
        val store = Store(mutableListOf(KnownKey(old.keyType, old.publicKey, old.fingerprint))); val prompts = Prompts(true)
        assertTrue(KnownHostsVerifier(store, prompts).verify("h", 22, rsa))
        assertEquals(old.fingerprint, prompts.asked!!.second!!.fingerprint)
        assertEquals(true, store.replaced)
    }
}
