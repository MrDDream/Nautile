package com.nautile.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nautile.data.db.NautileDatabase
import com.nautile.data.db.SyncSchema
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.KnownHost
import com.nautile.data.model.Snippet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two « devices » (two databases) synchronising through snapshots, with the real triggers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncMergeTest {
    private lateinit var a: NautileDatabase
    private lateinit var b: NautileDatabase

    private fun db(): NautileDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NautileDatabase::class.java)
        .addCallback(SyncSchema.callback).allowMainThreadQueries().build()

    @Before fun setUp() { a = db(); b = db(); a.openHelper.writableDatabase; b.openHelper.writableDatabase }
    @After fun tearDown() { a.close(); b.close() }

    /** [to] pulls [from]. */
    private fun pull(to: NautileDatabase, from: NautileDatabase) = runBlocking { SyncMerge(to).merge(SyncMerge(from).snapshot(emptyList())) }
    /** A full round: each side pulls the other. */
    private fun round() { pull(a, b); pull(b, a) }
    private fun tick() = Thread.sleep(5)
    private fun hosts(db: NautileDatabase) = runBlocking { db.hostDao().allHosts().sortedBy { it.alias } }

    @Test fun triggersStampEditsButNotLocalState() = runBlocking {
        val id = a.hostDao().upsertHost(Host(alias = "nas", hostname = "10.0.0.2"))
        val h = a.hostDao().getHost(id)!!
        assertEquals(32, h.syncId.length)
        assertTrue(h.updatedAt > 1)
        tick(); a.hostDao().markConnected(id, 123L)
        assertEquals(h.updatedAt, a.hostDao().getHost(id)!!.updatedAt)
        tick(); a.hostDao().upsertHost(a.hostDao().getHost(id)!!.copy(alias = "nas2"))
        assertTrue(a.hostDao().getHost(id)!!.updatedAt > h.updatedAt)
        a.hostDao().deleteHost(a.hostDao().getHost(id)!!)
        assertEquals(h.syncId, a.syncDao().tombstones().single().syncId)
    }

    @Test fun newHostsAndEditsTravelBothWays() = runBlocking {
        val g = a.hostDao().insertGroup(HostGroup(name = "Homelab"))
        a.hostDao().upsertHost(Host(alias = "nas", hostname = "10.0.0.2", groupId = g))
        round()
        val onB = hosts(b).single()
        assertEquals("10.0.0.2", onB.hostname)
        assertEquals("Homelab", b.hostDao().allGroups().single { it.id == onB.groupId }.name)
        tick(); b.hostDao().upsertHost(onB.copy(hostname = "10.0.0.3"))
        round()
        assertEquals("10.0.0.3", hosts(a).single().hostname)
        assertEquals(hosts(a).single().syncId, hosts(b).single().syncId)
    }

    @Test fun latestChangeWins() = runBlocking {
        a.hostDao().upsertHost(Host(alias = "vps", hostname = "1.1.1.1"))
        round()
        b.hostDao().upsertHost(hosts(b).single().copy(port = 2222)); tick()
        a.hostDao().upsertHost(hosts(a).single().copy(port = 2200))
        round()
        assertEquals(2200, hosts(a).single().port)
        assertEquals(2200, hosts(b).single().port)
    }

    @Test fun deletionsPropagateAndALaterEditRevives() = runBlocking {
        a.toolsDao().upsertSnippet(Snippet(name = "df", command = "df -h"))
        a.hostDao().upsertHost(Host(alias = "old", hostname = "x"))
        round()
        tick(); a.hostDao().deleteHost(hosts(a).single())
        round()
        assertTrue(hosts(b).isEmpty())
        // Edited on B after the deletion: comes back everywhere.
        val snip = b.toolsDao().allSnippets().single()
        a.toolsDao().deleteSnippet(a.toolsDao().allSnippets().single()); tick()
        b.toolsDao().upsertSnippet(snip.copy(command = "df -h /"))
        round()
        assertEquals("df -h /", a.toolsDao().allSnippets().single().command)
        assertNull(a.syncDao().tombstones().firstOrNull { it.syncId == snip.syncId })
    }

    @Test fun sameHostOnBothDevicesConverges() = runBlocking {
        a.hostDao().upsertHost(Host(alias = "pi", hostname = "192.168.1.5", username = "pi"))
        b.hostDao().upsertHost(Host(alias = "pi", hostname = "192.168.1.6", username = "pi"))
        // Simulate data from before synchronisation existed: same age on both sides.
        a.openHelper.writableDatabase.execSQL("UPDATE host SET updatedAt = 1")
        b.openHelper.writableDatabase.execSQL("UPDATE host SET updatedAt = 1")
        round(); round()
        assertEquals(1, hosts(a).size)
        assertEquals(hosts(a).single().hostname, hosts(b).single().hostname)
        assertEquals(hosts(a).single().syncId, hosts(b).single().syncId)
    }

    @Test fun localStateIsKeptAndJumpHostsAreLinked() = runBlocking {
        val bastion = a.hostDao().upsertHost(Host(alias = "bastion", hostname = "b.example"))
        a.hostDao().upsertHost(Host(alias = "inner", hostname = "10.1.0.4", jumpHostId = bastion))
        round()
        val innerB = hosts(b).single { it.alias == "inner" }
        assertEquals(hosts(b).single { it.alias == "bastion" }.id, innerB.jumpHostId)
        b.hostDao().markConnected(innerB.id, 42L)
        tick(); a.hostDao().upsertHost(hosts(a).single { it.alias == "inner" }.copy(tags = "prod"))
        round()
        val after = hosts(b).single { it.alias == "inner" }
        assertEquals("prod", after.tags)
        assertEquals(42L, after.lastConnectedAt)
        assertNotEquals(0L, after.updatedAt)
    }

    @Test fun editSavedFromAStaleCopyIsStampedAndTravels() = runBlocking {
        a.hostDao().upsertHost(Host(alias = "web", hostname = "1.1.1.1"))
        round()
        // The editor opens on A, then a synchronisation brings B's newer edit before it is saved.
        val stale = hosts(a).single()
        tick(); b.hostDao().upsertHost(hosts(b).single().copy(port = 2222))
        round()
        assertEquals(2222, hosts(a).single().port)
        tick(); a.hostDao().upsertHost(stale.copy(hostname = "2.2.2.2"))
        assertTrue(hosts(a).single().updatedAt > hosts(b).single().updatedAt)
        round()
        assertEquals("2.2.2.2", hosts(b).single().hostname)
        assertEquals(hosts(a).single().updatedAt, hosts(b).single().updatedAt)
    }

    @Test fun editAfterAStampFromTheFutureStillMovesForward() = runBlocking {
        val id = a.hostDao().upsertHost(Host(alias = "x", hostname = "h"))
        val future = System.currentTimeMillis() + 3_600_000
        a.openHelper.writableDatabase.execSQL("UPDATE host SET updatedAt = $future")
        a.hostDao().upsertHost(a.hostDao().getHost(id)!!.copy(port = 2))
        assertTrue(a.hostDao().getHost(id)!!.updatedAt > future)
    }

    @Test fun mergedChangesKeepTheirTime() = runBlocking {
        a.hostDao().insertGroup(HostGroup(name = "Lab"))
        a.toolsDao().upsertSnippet(Snippet(name = "up", command = "uptime"))
        round()
        tick(); a.hostDao().updateGroup(a.hostDao().allGroups().single().copy(color = 1))
        a.toolsDao().upsertSnippet(a.toolsDao().allSnippets().single().copy(command = "uptime -p"))
        round()
        assertEquals(a.hostDao().allGroups().single().updatedAt, b.hostDao().allGroups().single().updatedAt)
        assertEquals(a.toolsDao().allSnippets().single().updatedAt, b.toolsDao().allSnippets().single().updatedAt)
        assertTrue(!pull(a, b).changed)
    }

    @Test fun renamedThenRecreatedStaysTwoRecords() = runBlocking {
        val bastion = a.hostDao().upsertHost(Host(alias = "bastion", hostname = "b"))
        a.hostDao().upsertHost(Host(alias = "web", hostname = "10.0.0.1", jumpHostId = bastion))
        round()
        // Renamed, and a new host takes its old name (without a jump host, so it is merged first).
        tick(); a.hostDao().upsertHost(hosts(a).single { it.alias == "web" }.copy(alias = "web-old"))
        a.hostDao().upsertHost(Host(alias = "web", hostname = "10.0.0.2"))
        round(); round()
        for (db in listOf(a, b)) {
            assertEquals(listOf("bastion", "web", "web-old"), hosts(db).map { it.alias })
            assertEquals("10.0.0.2", hosts(db).single { it.alias == "web" }.hostname)
            assertEquals("10.0.0.1", hosts(db).single { it.alias == "web-old" }.hostname)
        }
        assertEquals(hosts(a).map { it.syncId }, hosts(b).map { it.syncId })
    }

    @Test fun groupRenamedOntoAnotherGroupsNameKeepsBoth() = runBlocking {
        val dev = a.hostDao().insertGroup(HostGroup(name = "Dev"))
        a.hostDao().upsertHost(Host(alias = "d1", hostname = "d", groupId = dev))
        round()
        val prod = b.hostDao().insertGroup(HostGroup(name = "Prod"))
        b.hostDao().upsertHost(Host(alias = "p1", hostname = "p", groupId = prod))
        val devId = a.hostDao().allGroups().single().syncId
        val prodId = b.hostDao().allGroups().single { it.name == "Prod" }.syncId
        tick(); a.hostDao().updateGroup(a.hostDao().allGroups().single().copy(name = "Prod"))
        round(); round()
        fun groups(db: NautileDatabase) = runBlocking { db.hostDao().allGroups().associate { it.name to it.syncId } }
        assertEquals(setOf("Prod", "Prod (2)"), groups(a).keys)
        assertEquals(groups(a), groups(b))
        // The smaller sync id keeps the name; every host stays in its group.
        assertEquals(minOf(devId, prodId), groups(a)["Prod"])
        for (db in listOf(a, b)) {
            val bySync = db.hostDao().allGroups().associate { it.id to it.syncId }
            assertEquals(devId, bySync[hosts(db).single { it.alias == "d1" }.groupId])
            assertEquals(prodId, bySync[hosts(db).single { it.alias == "p1" }.groupId])
        }
    }

    @Test fun aRemoteDeletionDoesNotDateTheRecordsThatPointedToIt() = runBlocking {
        val prod = a.hostDao().insertGroup(HostGroup(name = "Prod"))
        a.hostDao().upsertHost(Host(alias = "web", hostname = "10.0.0.1", groupId = prod))
        round()
        // B deletes the group (its own host leaves it: a local edit), then changes the port.
        tick(); b.hostDao().deleteGroup(b.hostDao().allGroups().single())
        tick(); b.hostDao().upsertHost(hosts(b).single().copy(port = 2222))
        tick(); round()
        for (db in listOf(a, b)) {
            val h = hosts(db).single()
            assertEquals(2222 to null, h.port to h.groupId)
            assertTrue(db.hostDao().allGroups().isEmpty())
        }
        assertEquals(hosts(a).single().updatedAt, hosts(b).single().updatedAt)
    }

    @Test fun sameTimeEditsOfOneRecordConverge() = runBlocking {
        val ahead = System.currentTimeMillis() + 3_600_000
        a.hostDao().upsertHost(Host(alias = "web", hostname = "10.0.0.1", updatedAt = ahead))
        round()
        // Both edits are stamped one past the stamp from the future: same updatedAt, same sync id.
        a.hostDao().upsertHost(hosts(a).single().copy(port = 2200))
        b.hostDao().upsertHost(hosts(b).single().copy(hostname = "10.0.0.2"))
        assertEquals(ahead + 1, hosts(a).single().updatedAt)
        assertEquals(ahead + 1, hosts(b).single().updatedAt)
        round()
        val ha = hosts(a).single()
        val hb = hosts(b).single()
        assertEquals(ha.port to ha.hostname, hb.port to hb.hostname)
        assertTrue("one of the two edits wins, whole", (ha.port to ha.hostname) in setOf(2200 to "10.0.0.1", 22 to "10.0.0.2"))
        assertEquals(ha.updatedAt, hb.updatedAt)
        assertTrue(!pull(a, b).changed && !pull(b, a).changed)
    }

    @Test fun contentHashIsTheDesktopOne() {
        // Same record and expected value as sync.rs content_hash_is_the_android_one.
        val h = Host(
            alias = "café ☕", hostname = "10.0.0.1", port = 2222, username = "root", password = "pä\$\$ \"q\"",
            groupId = 7, keyId = 3, tags = "prod,web", rdpAudio = false,
        )
        val fields = Json { encodeDefaults = true }.encodeToJsonElement(h).jsonObject
        assertEquals(
            "5a8e550a672b7a693f41d592e4ceeaf66491c13219081a684d152d8ef36d45db",
            SyncMerge.contentHash("host", fields, mapOf("groupId" to "g-1")),
        )
    }

    @Test fun aConvergedSyncIdCountsAsAChange() = runBlocking {
        a.hostDao().upsertHost(Host(alias = "pi", hostname = "192.168.1.5"))
        b.hostDao().upsertHost(Host(alias = "pi", hostname = "192.168.1.5"))
        a.openHelper.writableDatabase.execSQL("UPDATE host SET updatedAt = 1")
        b.openHelper.writableDatabase.execSQL("UPDATE host SET updatedAt = 1")
        val ia = hosts(a).single().syncId
        val ib = hosts(b).single().syncId
        // The side with the larger sync id adopts the other one: what shows it must refresh.
        val (bigger, smaller) = if (ia > ib) a to b else b to a
        assertTrue(pull(bigger, smaller).changed)
        assertEquals(minOf(ia, ib), hosts(bigger).single().syncId)
    }

    @Test fun triggersAreBackAfterAMerge() = runBlocking {
        a.hostDao().upsertHost(Host(alias = "nas", hostname = "n"))
        round()
        b.openHelper.writableDatabase.query("SELECT merging FROM sync_ctl").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        val before = hosts(b).single()
        tick(); b.hostDao().upsertHost(before.copy(port = 2))
        assertTrue(hosts(b).single().updatedAt > before.updatedAt)
        b.hostDao().deleteHost(hosts(b).single())
        assertEquals(1, b.syncDao().tombstones().size)
    }

    @Test fun aDeletionIsDatedAfterAChangeStampedInTheFuture() = runBlocking {
        val ahead = System.currentTimeMillis() + 3_600_000
        val id = a.hostDao().upsertHost(Host(alias = "nas", hostname = "n", updatedAt = ahead))
        a.knownHostDao().upsertBlocking(KnownHost(host = "n", port = 22, keyType = "ssh-ed25519", publicKey = "AAAA", fingerprint = "SHA256:x", addedAt = ahead))
        a.hostDao().deleteHost(a.hostDao().getHost(id)!!)
        a.knownHostDao().deleteBlocking("n", 22, "ssh-ed25519")
        val tombs = a.syncDao().tombstones()
        assertEquals(2, tombs.size)
        assertTrue(tombs.toString(), tombs.all { it.deletedAt == ahead + 1 })
    }

    @Test fun deletingARecordStampedInTheFutureAppliesEverywhere() = runBlocking {
        a.hostDao().upsertHost(Host(alias = "nas", hostname = "n", updatedAt = System.currentTimeMillis() + 3_600_000))
        round()
        a.hostDao().deleteHost(hosts(a).single())
        round()
        assertTrue(hosts(a).isEmpty() && hosts(b).isEmpty())
    }

    /** The SQL type of [column] in every row of [table]. */
    private fun types(db: NautileDatabase, table: String, column: String) =
        db.openHelper.writableDatabase.query("SELECT typeof($column) FROM $table").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    @Test fun stampsNeverGoPastTheCap() = runBlocking {
        val id = a.hostDao().upsertHost(Host(alias = "x", hostname = "h", updatedAt = Long.MAX_VALUE))
        assertEquals(SyncSchema.MAX_STAMP, a.hostDao().getHost(id)!!.updatedAt)
        // Written before stamps were bounded: one past it no longer turns into a REAL.
        val w = a.openHelper.writableDatabase
        w.execSQL("UPDATE host SET updatedAt = ${Long.MAX_VALUE}")
        a.hostDao().upsertHost(a.hostDao().getHost(id)!!.copy(port = 2))
        assertEquals(SyncSchema.MAX_STAMP, a.hostDao().getHost(id)!!.updatedAt)
        w.execSQL("UPDATE host SET updatedAt = ${Long.MAX_VALUE}")
        a.hostDao().deleteHost(a.hostDao().getHost(id)!!)
        assertEquals(SyncSchema.MAX_STAMP, a.syncDao().tombstones().single().deletedAt)
        assertEquals(listOf("integer"), types(a, "sync_tombstone", "deletedAt"))
        // A REAL left by an older version is brought back when the database opens.
        a.hostDao().upsertHost(Host(alias = "y", hostname = "h"))
        w.execSQL("UPDATE host SET updatedAt = 1e19")
        assertEquals(listOf("real"), types(a, "host", "updatedAt"))
        SyncSchema.statements().forEach(w::execSQL)
        assertEquals(listOf("integer"), types(a, "host", "updatedAt"))
        assertTrue(hosts(a).single().updatedAt <= System.currentTimeMillis() + SyncSchema.FUTURE_LIMIT_MS)
        // The tombstone is brought back too, not dropped.
        assertTrue(a.syncDao().tombstones().single().deletedAt <= System.currentTimeMillis() + SyncSchema.FUTURE_LIMIT_MS)
    }

    @Test fun aClockBeforeTheFloorBringsNothingBack() = runBlocking {
        assertEquals(SyncSchema.MAX_STAMP, SyncSchema.stampCap(0))
        assertEquals(SyncSchema.MAX_STAMP, SyncSchema.stampCap(SyncSchema.CLOCK_FLOOR_MS - 1))
        assertEquals(SyncSchema.CLOCK_FLOOR_MS + SyncSchema.FUTURE_LIMIT_MS, SyncSchema.stampCap(SyncSchema.CLOCK_FLOOR_MS))
        // Stamped by clocks that were right, two years after this clock (started at 1970, before the network time).
        val later = System.currentTimeMillis() + 2 * SyncSchema.FUTURE_LIMIT_MS
        a.hostDao().upsertHost(Host(alias = "nas", hostname = "n", updatedAt = later))
        a.toolsDao().upsertSnippet(Snippet(name = "df", command = "df -h", updatedAt = later))
        a.toolsDao().deleteSnippet(a.toolsDao().allSnippets().single())
        a.hostDao().upsertHost(Host(alias = "real", hostname = "r"))
        val w = a.openHelper.writableDatabase
        w.execSQL("UPDATE host SET updatedAt = 1e19 WHERE alias = 'real'")
        SyncSchema.stampRepairs(now = 0).forEach(w::execSQL)
        // Nothing judged by that clock; only what no integer holds is brought back.
        assertEquals(listOf(later, SyncSchema.MAX_STAMP), hosts(a).map { it.updatedAt })
        assertEquals(later + 1, a.syncDao().tombstones().single().deletedAt)
        // With a clock past the floor, both come back to a year ahead of it, the tombstone kept.
        val now = SyncSchema.CLOCK_FLOOR_MS
        SyncSchema.stampRepairs(now).forEach(w::execSQL)
        assertEquals(listOf(now + SyncSchema.FUTURE_LIMIT_MS, now + SyncSchema.FUTURE_LIMIT_MS), hosts(a).map { it.updatedAt })
        assertEquals(now + SyncSchema.FUTURE_LIMIT_MS, a.syncDao().tombstones().single().deletedAt)
    }

    @Test fun stampsFromFarAheadAreBelievedUpToAYear() = runBlocking {
        a.hostDao().upsertHost(Host(alias = "nas", hostname = "n"))
        a.toolsDao().upsertSnippet(Snippet(name = "df", command = "df -h"))
        round()
        tick(); a.hostDao().upsertHost(hosts(a).single().copy(hostname = "n2"))
        val w = a.openHelper.writableDatabase
        w.execSQL("UPDATE host SET updatedAt = ${Long.MAX_VALUE}")
        w.execSQL("UPDATE snippet SET updatedAt = ${Long.MAX_VALUE}")
        a.toolsDao().deleteSnippet(a.toolsDao().allSnippets().single())
        val before = System.currentTimeMillis()
        pull(b, a)
        val after = System.currentTimeMillis()
        val h = hosts(b).single()
        assertEquals("n2", h.hostname)
        assertTrue(h.updatedAt in before + SyncSchema.FUTURE_LIMIT_MS..after + SyncSchema.FUTURE_LIMIT_MS)
        // A deletion dated that far ahead is believed up to a year too: it still applies.
        assertTrue(b.toolsDao().allSnippets().isEmpty())
        assertTrue(b.syncDao().tombstones().single().deletedAt in before + SyncSchema.FUTURE_LIMIT_MS..after + SyncSchema.FUTURE_LIMIT_MS)
        // Its own stamps are brought back too before it merges.
        pull(a, b)
        assertTrue(hosts(a).single().updatedAt <= System.currentTimeMillis() + SyncSchema.FUTURE_LIMIT_MS)
        assertTrue(a.syncDao().tombstones().single().deletedAt <= System.currentTimeMillis() + SyncSchema.FUTURE_LIMIT_MS)
        assertTrue(a.toolsDao().allSnippets().isEmpty())
        assertTrue(!pull(a, b).changed && !pull(b, a).changed)
    }

    @Test fun aKeyTrustedAgainOutlivesATombstoneDatedAhead() = runBlocking {
        // Trusted on a device whose clock runs ahead.
        val ahead = System.currentTimeMillis() + 3_600_000
        val key = KnownHost(host = "n", port = 22, keyType = "ssh-ed25519", publicKey = "AAAA", fingerprint = "SHA256:x", addedAt = ahead)
        a.knownHostDao().upsertBlocking(key)
        round()
        // Removed on B: the tombstone is dated after that trust, so ahead too, and both devices keep it.
        b.knownHostDao().deleteBlocking("n", 22, "ssh-ed25519")
        round()
        assertTrue(a.knownHostDao().all().isEmpty())
        assertEquals(ahead + 1, a.syncDao().tombstones().single().deletedAt)
        // The new key is trusted on A with this clock: dated after the tombstone, which B still holds.
        a.knownHostDao().upsertBlocking(key.copy(publicKey = "BBBB", fingerprint = "SHA256:y", addedAt = System.currentTimeMillis()))
        assertEquals(ahead + 2, a.knownHostDao().all().single().addedAt)
        round(); round()
        for (db in listOf(a, b)) {
            val k = db.knownHostDao().all().single()
            assertEquals("SHA256:y" to ahead + 2, k.fingerprint to k.addedAt)
            // The old tombstone may still travel (for 90 days), dated before the new trust.
            assertTrue(db.syncDao().tombstones().all { it.deletedAt < k.addedAt })
        }
    }

    /** The columns hashed by a version that does not hash `port`: for it, two versions differing by their port are a tie it keeps. */
    private val older = SyncSchema.TABLES + ("host" to SyncSchema.TABLES.getValue("host") - "port")

    /**
     * One host on [x] and [y], each with its own port and both stamped one past a stamp from the future; the version
     * of [x] keeps its own too ([y]'s port hashes after). The stamp both have.
     */
    private fun tiedPorts(x: NautileDatabase, y: NautileDatabase): Long = runBlocking {
        val ahead = System.currentTimeMillis() + 3_600_000
        x.hostDao().upsertHost(Host(alias = "web", hostname = "10.0.0.1", updatedAt = ahead))
        SyncMerge(y).merge(SyncMerge(x).snapshot(emptyList()))
        val json = Json { encodeDefaults = true }
        fun hash(h: Host) = SyncMerge.contentHash("host", json.encodeToJsonElement(h).jsonObject, emptyMap())
        val mine = hosts(x).single().copy(port = 2200)
        val theirs = (2201..2600).first { hash(mine.copy(port = it)) > hash(mine) }
        x.hostDao().upsertHost(mine)
        y.hostDao().upsertHost(hosts(y).single().copy(port = theirs))
        assertEquals(ahead + 1, hosts(x).single().updatedAt)
        assertEquals(ahead + 1, hosts(y).single().updatedAt)
        ahead + 1
    }

    @Test fun aTieDecidedDifferentlyByEachVersionConverges() = runBlocking {
        // B runs a version that does not hash `port`.
        fun pullOlder(to: NautileDatabase, from: NautileDatabase) = runBlocking {
            SyncMerge(to, older).merge(SyncMerge(from).snapshot(emptyList()))
        }
        val ahead = tiedPorts(a, b) - 1
        pull(a, b); pullOlder(b, a)
        pull(a, b); pullOlder(b, a)
        assertEquals(2200, hosts(a).single().port)
        assertEquals(2200, hosts(b).single().port)
        assertEquals(ahead + 2, hosts(a).single().updatedAt)
        assertEquals(ahead + 2, hosts(b).single().updatedAt)
        assertTrue(!pull(a, b).changed && !pullOlder(b, a).changed)
    }

    /**
     * A Wi-Fi exchange where each side took its snapshot before merging the other's (an older responder): only the
     * responder moves a tie past, so the next exchange settles it, whichever version decides on which side.
     */
    @Test fun aTieMergedBothWaysAtOnceConverges() = runBlocking {
        for (olderInitiates in listOf(false, true)) {
            val (i, r) = db() to db()
            try {
                tiedPorts(i, r)
                val initiator = if (olderInitiates) SyncMerge(i, older) else SyncMerge(i)
                val responder = if (olderInitiates) SyncMerge(r) else SyncMerge(r, older)
                /** One exchange, both snapshots first; whether anything changed. */
                suspend fun atOnce(): Boolean {
                    val fromI = SyncMerge(i).snapshot(emptyList())
                    val fromR = SyncMerge(r).snapshot(emptyList())
                    val initiatorChanged = initiator.merge(fromR, moveTies = false).changed
                    val responderChanged = responder.merge(fromI).changed
                    return initiatorChanged || responderChanged
                }
                var exchanges = 0
                while (atOnce()) assertTrue("no convergence", ++exchanges < 4)
                assertEquals(hosts(i).single().port, hosts(r).single().port)
                assertEquals(hosts(i).single().updatedAt, hosts(r).single().updatedAt)
            } finally {
                i.close(); r.close()
            }
        }
    }

    /** The Wi-Fi exchange: the responder merges first, then answers with its data merged; one exchange settles a tie. */
    @Test fun aTieMergedByTheResponderFirstConvergesInOneExchange() = runBlocking {
        val at = tiedPorts(a, b)
        // B responds, with the older version.
        assertTrue(SyncMerge(b, older).merge(SyncMerge(a).snapshot(emptyList())).changed)
        assertTrue(SyncMerge(a).merge(SyncMerge(b).snapshot(emptyList()), moveTies = false).changed)
        assertEquals(hosts(a).single().port, hosts(b).single().port)
        assertEquals(at + 1, hosts(a).single().updatedAt)
        assertEquals(at + 1, hosts(b).single().updatedAt)
        assertTrue(!SyncMerge(b, older).merge(SyncMerge(a).snapshot(emptyList())).changed)
        assertTrue(!SyncMerge(a).merge(SyncMerge(b).snapshot(emptyList()), moveTies = false).changed)
    }

    @Test fun nothingChangesWhenAlreadyInSync() {
        runBlocking { a.hostDao().upsertHost(Host(alias = "nas", hostname = "n")) }
        round()
        assertTrue(!pull(a, b).changed)
        assertTrue(!pull(b, a).changed)
    }
}
