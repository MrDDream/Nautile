package com.nautile.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nautile.data.db.NautileDatabase
import com.nautile.data.db.SyncSchema
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.Snippet
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

    @Test fun nothingChangesWhenAlreadyInSync() {
        runBlocking { a.hostDao().upsertHost(Host(alias = "nas", hostname = "n")) }
        round()
        assertTrue(!pull(a, b).changed)
        assertTrue(!pull(b, a).changed)
    }
}
