package com.nautile.security

import android.content.SharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** A software key standing for the Keystore's, with the failures the real one shows. */
private class FakeKeys : KeyHolder {
    var key: SecretKey? = null
    /** get() answers null although the key is there (keystore2 swallows its errors). */
    var swallowNext = 0
    var throwOnGet: (() -> Exception)? = null
    var throwOnGetTimes = Int.MAX_VALUE
    var listed = true
    var created = 0
    var deleted = 0

    override fun get(): SecretKey? {
        throwOnGet?.let { if (throwOnGetTimes-- > 0) throw it() }
        if (swallowNext > 0) { swallowNext--; return null }
        return key
    }
    override fun confirmedAbsent() = listed && key == null
    override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it; created++ }
    override fun delete() { key = null; deleted++ }
}

/** Enough of SharedPreferences for the stores. */
internal class MemoryPrefs : SharedPreferences {
    val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in map
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val puts = mutableMapOf<String, Any?>()
        val removes = mutableSetOf<String>()
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { removes += map.keys }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { removes.forEach { map.remove(it) }; map.putAll(puts) }
    }
}

class KeystoreSealerTest {
    private val keys = FakeKeys()
    private val sealer = KeystoreSealer(keys, pause = {}, transientHint = { false })
    private val plain = "secret".toByteArray()

    private inline fun <reified E : Throwable> fails(block: () -> Unit): E {
        try { block() } catch (e: Throwable) { if (e is E) return e; throw e }
        fail("expected ${E::class.simpleName}"); throw IllegalStateException()
    }

    @Test fun sealsAndOpens() {
        val blob = sealer.seal(plain, nothingSealed = true)
        assertArrayEquals(plain, sealer.open(blob))
        assertEquals(1, keys.created)
    }

    @Test fun aSwallowedFailureIsRetriedNotTakenForALostKey() {
        val blob = sealer.seal(plain, nothingSealed = true)
        keys.swallowNext = 2
        assertArrayEquals(plain, sealer.open(blob))
        keys.swallowNext = 10
        fails<KeystoreUnavailableException> { sealer.open(blob) }
    }

    @Test fun aKeyUnreachableForAMomentIsNeverReplacedWhileDataDependsOnIt() {
        val blob = sealer.seal(plain, nothingSealed = true)
        keys.swallowNext = 10
        fails<KeystoreUnavailableException> { sealer.seal(plain, nothingSealed = false) }
        assertEquals(1, keys.created)
        // The real key is still the one that opens the data.
        keys.swallowNext = 0
        assertArrayEquals(plain, sealer.open(blob))
    }

    @Test fun anotherKeyIsALostKey() {
        val blob = sealer.seal(plain, nothingSealed = true)
        keys.create()
        val e = fails<KeyLostException> { sealer.open(blob) }
        assertTrue(e.cause is javax.crypto.AEADBadTagException)
    }

    @Test fun aKeyMissingFromAWorkingKeystoreIsLost() {
        val blob = sealer.seal(plain, nothingSealed = true)
        keys.key = null
        fails<KeyLostException> { sealer.open(blob) }
        // An empty or failed listing proves nothing.
        keys.listed = false
        fails<KeystoreUnavailableException> { sealer.open(blob) }
    }

    @Test fun anUnrecoverableKeyIsLostUnlessTheKeystoreSaysItPasses() {
        val blob = sealer.seal(plain, nothingSealed = true)
        keys.throwOnGet = { UnrecoverableKeyException("broken") }
        fails<KeyLostException> { sealer.open(blob) }
        val hinted = KeystoreSealer(keys, pause = {}, transientHint = { it.message == "busy" })
        keys.throwOnGet = { UnrecoverableKeyException("wrapped").apply { initCause(IllegalStateException("busy")) } }
        fails<KeystoreUnavailableException> { hinted.open(blob) }
    }

    @Test fun otherFailuresPass() {
        val blob = sealer.seal(plain, nothingSealed = true)
        keys.throwOnGet = { ProviderException("keystore crashed") }
        keys.throwOnGetTimes = 2
        assertArrayEquals(plain, sealer.open(blob))
        keys.throwOnGetTimes = 10
        fails<KeystoreUnavailableException> { sealer.open(blob) }
    }

    @Test fun aBrokenKeyIsReplacedOnlyWhenNothingDependsOnIt() {
        sealer.seal(plain, nothingSealed = true)
        keys.throwOnGet = { UnrecoverableKeyException("broken") }
        keys.throwOnGetTimes = 1
        val blob = sealer.seal(plain, nothingSealed = true)
        assertEquals(1, keys.deleted)
        assertArrayEquals(plain, sealer.open(blob))
        keys.throwOnGetTimes = 10
        fails<KeyLostException> { sealer.seal(plain, nothingSealed = false) }
        assertEquals(1, keys.deleted)
    }

    @Test fun aTruncatedValueIsLost() {
        fails<KeyLostException> { sealer.open(ByteArray(10)) }
    }
}

class DatabaseKeyStoreTest {
    /** One fake Keystore entry per alias. */
    private val keystore = mutableMapOf<String, FakeKeys>()
    private val prefs = MemoryPrefs()
    private var boot = 5
    private val store = DatabaseKeyStore(
        prefs,
        { alias -> KeystoreSealer(keystore.getOrPut(alias) { FakeKeys() }, pause = {}, transientHint = { false }) },
        { boot },
    )
    private fun currentKeys() = keystore.getValue(prefs.map[DatabaseKeyStore.PREF_ALIAS] as String)

    @Test fun thePassphraseIsKept() {
        assertFalse(store.hasPassphrase())
        val p = store.passphrase()
        assertTrue(store.hasPassphrase())
        assertArrayEquals(p, store.passphrase())
    }

    @Test fun aKeystoreFailureNeitherResetsNorReplacesAnything() {
        val p = store.passphrase()
        val blob = prefs.map[DatabaseKeyStore.PREF_BLOB]
        val keys = currentKeys()
        keys.swallowNext = 100
        try { store.passphrase(); fail() } catch (_: KeystoreUnavailableException) {}
        assertEquals(blob, prefs.map[DatabaseKeyStore.PREF_BLOB])
        assertEquals(1, keys.created)
        keys.swallowNext = 0
        assertArrayEquals(p, store.passphrase())
    }

    @Test fun aLostPassphraseIsMovedAsideNeverDeleted() {
        store.passphrase()
        val blob = prefs.map[DatabaseKeyStore.PREF_BLOB]
        currentKeys().create()
        try { store.passphrase(); fail() } catch (_: KeyLostException) {}
        store.setAside("1234")
        assertEquals(blob, prefs.map["${DatabaseKeyStore.PREF_BLOB}.lost-1234"])
        assertFalse(store.hasPassphrase())
        val fresh = store.passphrase()
        assertArrayEquals(fresh, store.passphrase())
        assertEquals(blob, prefs.map["${DatabaseKeyStore.PREF_BLOB}.lost-1234"])
    }

    @Test fun startingAfreshNeverReplacesTheKeyOfAPassphraseSetAside() {
        val first = store.passphrase()
        val firstAlias = prefs.map[DatabaseKeyStore.PREF_ALIAS] as String
        val firstKey = currentKeys().key
        // « Repartir de zéro » while the Keystore only failed for a moment.
        store.setAside("1")
        val second = store.passphrase()
        val secondAlias = prefs.map[DatabaseKeyStore.PREF_ALIAS] as String
        assertNotEquals(firstAlias, secondAlias)
        assertEquals(firstAlias, prefs.map["${DatabaseKeyStore.PREF_ALIAS}.lost-1"])
        // The first key is untouched: the passphrase set aside still opens with it.
        assertEquals(firstKey, keystore.getValue(firstAlias).key)
        assertEquals(1, keystore.getValue(firstAlias).created)
        val aside = java.util.Base64.getDecoder().decode(prefs.map["${DatabaseKeyStore.PREF_BLOB}.lost-1"] as String)
        assertArrayEquals(first, KeystoreSealer(keystore.getValue(firstAlias), pause = {}, transientHint = { false }).open(aside))
        assertArrayEquals(second, store.passphrase())
    }

    @Test fun installsMadeBeforeKeepTheirAlias() {
        // A passphrase saved without its alias by an older version.
        val legacy = FakeKeys()
        keystore[DatabaseKeyStore.LEGACY_ALIAS] = legacy
        val p = ByteArray(32) { 3 }
        val sealed = KeystoreSealer(legacy, pause = {}, transientHint = { false }).seal(p, nothingSealed = true)
        prefs.map[DatabaseKeyStore.PREF_BLOB] = java.util.Base64.getEncoder().encodeToString(sealed)
        assertArrayEquals(p, store.passphrase())
        // Set aside, then afresh: the legacy key stays, the new passphrase has its own.
        store.setAside("old")
        assertEquals(DatabaseKeyStore.LEGACY_ALIAS, prefs.map["${DatabaseKeyStore.PREF_ALIAS}.lost-old"])
        store.passphrase()
        assertNotEquals(DatabaseKeyStore.LEGACY_ALIAS, prefs.map[DatabaseKeyStore.PREF_ALIAS])
        assertEquals(1, legacy.created)
        assertEquals(0, legacy.deleted)
    }

    @Test fun aLossMustBeSeenAfterTwoDistinctBootsOfThePhone() {
        // Many launches within one boot (a Keystore failing for a quarter of an hour) count once.
        repeat(5) { assertFalse(store.noteLoss(foreground = true)) }
        boot = 6
        assertTrue(store.noteLoss(foreground = true))
        // The passphrase opened meanwhile: everything starts again.
        store.clearLosses()
        assertFalse(store.noteLoss(foreground = true))
    }

    @Test fun aStartInTheBackgroundNeitherCountsNorSetsAside() {
        // Widget updates and workers start the process on their own: they only report the error.
        repeat(3) { assertFalse(store.noteLoss(foreground = false)); boot++ }
        assertFalse(store.noteLoss(foreground = true))
        boot++
        repeat(3) { assertFalse(store.noteLoss(foreground = false)) }
        assertTrue(store.noteLoss(foreground = true))
    }

    @Test fun anUnknownBootNeverCounts() {
        boot = -1
        repeat(3) { assertFalse(store.noteLoss(foreground = true)) }
        boot = 1
        assertFalse(store.noteLoss(foreground = true))
    }

    @Test fun theCountOfEarlierVersionsIsIgnored() {
        store.passphrase()
        prefs.map[DatabaseKeyStore.PREF_LOSSES] = 7
        assertFalse(store.noteLoss(foreground = true))
        store.setAside("x")
        assertFalse(DatabaseKeyStore.PREF_LOSSES in prefs.map)
        assertFalse(DatabaseKeyStore.PREF_LOSS_BOOTS in prefs.map)
    }
}

class KeystoreProbeTest {
    @Test fun anEmptyListingProvesNothingUntilTheProbeIsListed() {
        var created = 0
        // A failing listing: empty even after creating the probe.
        assertFalse(AndroidKeyHolder.absentFromListing("k", { emptyList() }, { created++ }))
        assertEquals(1, created)
        // An empty but working Keystore (wiped): the probe shows up once created, the key does not.
        val listed = mutableListOf<String>()
        assertTrue(AndroidKeyHolder.absentFromListing("k", { listed.toList() }, { listed += AndroidKeyHolder.PROBE }))
        // The probe listed with the key: present.
        assertFalse(AndroidKeyHolder.absentFromListing("k", { listOf(AndroidKeyHolder.PROBE, "k") }, { fail() }))
        // The probe listed, the key not: lost.
        assertTrue(AndroidKeyHolder.absentFromListing("k", { listOf(AndroidKeyHolder.PROBE, "other") }, { fail() }))
    }

    @Test fun anEmptyKeystoreIsLostNotUnavailableForever() {
        val keys = object : KeyHolder {
            val listed = mutableListOf<String>()
            override fun get(): SecretKey? = null
            override fun confirmedAbsent() = AndroidKeyHolder.absentFromListing("k", { listed.toList() }, { listed += AndroidKeyHolder.PROBE })
            override fun create(): SecretKey = throw IllegalStateException()
            override fun delete() {}
        }
        val sealer = KeystoreSealer(keys, pause = {}, transientHint = { false })
        try { sealer.open(ByteArray(40)); fail() } catch (_: KeyLostException) {}
    }
}

/** Stands for android.security.KeyStoreException of Android 8-12, whose code is read by reflection there. */
private class LegacyKeystoreFailure(val code: Int) : Exception("keystore $code")

class LegacyClassificationTest {
    private val keys = FakeKeys()
    private val sealer = KeystoreSealer(keys, pause = {}, transientHint = { false }, legacyCode = { (it as? LegacyKeystoreFailure)?.code })

    private fun wrapped(code: Int) = UnrecoverableKeyException("wrapped").apply { initCause(LegacyKeystoreFailure(code)) }

    @Test fun aKeystoreErrorOtherThanAMissingOrBrokenKeyPasses() {
        // SYSTEM_ERROR (4), LOCKED (2): passing failures wrapped in UnrecoverableKeyException.
        assertFalse(sealer.isPermanent(wrapped(4)))
        assertFalse(sealer.isPermanent(wrapped(2)))
        // KEY_NOT_FOUND (7), INVALID_KEY_BLOB (-33): lost.
        assertTrue(sealer.isPermanent(wrapped(7)))
        assertTrue(sealer.isPermanent(wrapped(-33)))
        // No code to read: as before, lost.
        assertTrue(sealer.isPermanent(UnrecoverableKeyException("plain")))
        // A bad tag stays lost whatever the code.
        assertTrue(sealer.isPermanent(javax.crypto.AEADBadTagException().apply { initCause(LegacyKeystoreFailure(4)) }))
    }

    @Test fun aSystemErrorIsRetriedThenUnavailable() {
        val blob = sealer.seal("x".toByteArray(), nothingSealed = true)
        keys.throwOnGet = { wrapped(4) }
        try { sealer.open(blob); fail() } catch (_: KeystoreUnavailableException) {}
        keys.throwOnGet = { wrapped(7) }
        try { sealer.open(blob); fail() } catch (_: KeyLostException) {}
    }
}

class SecretStoreTest {
    private val keys = FakeKeys()
    private val prefs = MemoryPrefs()
    private val store = SecretStore(prefs, KeystoreSealer(keys, pause = {}, transientHint = { false }), Any())

    @Test fun readTellsAPassingFailureFromALostSecret() {
        assertEquals(SecretStore.Read.Absent, store.read("a"))
        store.put("a", "1", sync = true)
        assertEquals(SecretStore.Read.Value("1"), store.read("a"))
        keys.swallowNext = 100
        assertEquals(SecretStore.Read.Unavailable, store.read("a"))
        assertNull(store.get("a"))
        keys.swallowNext = 0
        keys.create()
        assertEquals(SecretStore.Read.Unreadable, store.read("a"))
    }

    @Test fun noKeyIsCreatedWhileSecretsSealedWithTheOldOneExist() {
        store.put("a", "1")
        val first = keys.key
        keys.swallowNext = 100
        try { store.put("b", "2"); fail() } catch (_: KeystoreUnavailableException) {}
        assertEquals(1, keys.created)
        keys.swallowNext = 0
        assertEquals(first, keys.key)
        assertEquals("1", store.get("a"))
        assertNotEquals(null, store.get("a"))
    }
}

class SecretStoreConcurrencyTest {
    /** A Keystore slow to create its key, as the real one is: two first writes at once both find it missing. */
    private class SlowKeys : KeyHolder {
        @Volatile var key: SecretKey? = null
        val created = java.util.concurrent.atomic.AtomicInteger()
        override fun get(): SecretKey? = key
        override fun confirmedAbsent() = key == null
        override fun create(): SecretKey {
            Thread.sleep(80)
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it; created.incrementAndGet() }
        }
        override fun delete() { key = null }
    }

    @Test fun twoStoresOfTheSameAliasNeverCreateTheKeyTwice() {
        val keys = SlowKeys()
        val prefs = MemoryPrefs()
        val lock = SecretStore.lockFor("test_alias")
        val a = SecretStore(prefs, KeystoreSealer(keys, pause = {}, transientHint = { false }), lock)
        val b = SecretStore(prefs, KeystoreSealer(keys, pause = {}, transientHint = { false }), SecretStore.lockFor("test_alias"))
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = listOf(a to "1", b to "2").mapIndexed { i, (store, value) ->
            Thread { start.await(); store.put("k$i", value, sync = true) }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(1, keys.created.get())
        assertEquals("1", a.get("k0"))
        assertEquals("2", b.get("k1"))
    }

    @Test fun movedAsideTheValueIsKeptSealed() {
        val keys = FakeKeys()
        val prefs = MemoryPrefs()
        val store = SecretStore(prefs, KeystoreSealer(keys, pause = {}, transientHint = { false }), Any())
        store.put("k", "v", sync = true)
        val sealed = prefs.map["k"]
        assertTrue(store.moveAside("k", "k.lost-1"))
        assertFalse(store.contains("k"))
        assertEquals(sealed, prefs.map["k.lost-1"])
        assertEquals(SecretStore.Read.Value("v"), store.read("k.lost-1"))
    }
}

class FileCipherKeyTest {
    private val keys = FakeKeys()
    private val prefs = MemoryPrefs()
    private val secrets = SecretStore(prefs, KeystoreSealer(keys, pause = {}, transientHint = { false }), Any())

    @Test fun aFileKeyLostForGoodIsSetAsideAndReplaced() {
        val first = FileCipher.of(secrets, stamp = { "s1" })
        val old = first.seal("a".toByteArray())
        // The Keystore key is gone (another one took its place): the file key can never be read again.
        keys.delete()
        keys.create()
        val next = FileCipher.of(secrets, stamp = { "s2" })
        assertEquals(FileCipher.Opened.Unreadable, next.read(old))
        // Kept aside, still sealed, and a new key saves again.
        assertTrue("file_key.lost-s2" in prefs.map)
        val sealed = next.seal("b".toByteArray())
        assertArrayEquals("b".toByteArray(), FileCipher.of(secrets).open(sealed))
    }

    @Test fun aFileKeyUnreachableForNowIsNeverReplaced() {
        val first = FileCipher.of(secrets)
        val blob = first.seal("a".toByteArray())
        val stored = prefs.map["file_key"]
        keys.swallowNext = 1_000
        val next = FileCipher.of(secrets)
        assertEquals(FileCipher.Opened.Unavailable, next.read(blob))
        try { next.seal("b".toByteArray()); fail() } catch (_: IllegalStateException) {}
        assertEquals(stored, prefs.map["file_key"])
        assertFalse(prefs.map.keys.any { it.startsWith("file_key.lost-") })
        keys.swallowNext = 0
        assertArrayEquals("a".toByteArray(), next.open(blob))
    }
}
