package com.nautile.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nautile.data.db.NautileDatabase
import com.nautile.data.db.SyncSchema
import com.nautile.data.importer.ImportedHost
import com.nautile.data.model.AuthMethod
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.SshKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostRepositoryTest {
    private lateinit var db: NautileDatabase
    private lateinit var repo: HostRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NautileDatabase::class.java)
            .addCallback(SyncSchema.callback).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        repo = HostRepository(db)
    }
    @After fun tearDown() = db.close()

    @Test fun creatingAGroupThatExistsChangesNothing() = runBlocking {
        assertTrue(repo.createGroup(HostGroup(name = "Lab", color = 1)))
        assertTrue(repo.createGroup(HostGroup(name = "Prod", color = 2)))
        assertFalse(repo.createGroup(HostGroup(name = "Lab", color = 3, icon = "home")))
        val groups = db.hostDao().allGroups().sortedBy { it.sortOrder }
        assertEquals(listOf("Lab", "Prod"), groups.map { it.name })
        assertEquals(1L, groups[0].color)
        assertNull(groups[0].icon)
        assertEquals(listOf(0, 1), groups.map { it.sortOrder })
    }

    @Test fun importMatchesIdentityFilesWithTheKeychainAndLeavesExistingHostsAlone() = runBlocking {
        val key = db.keyDao().insertKey(SshKey(name = "id_ed25519", keyType = "ssh-ed25519", description = "", publicKey = "p", privateKey = "k", fingerprint = "f", encrypted = false))
        val existing = db.hostDao().upsertHost(Host(alias = "old", hostname = "o"))
        val result = repo.import(
            listOf(
                ImportedHost("bastion", "b"),
                ImportedHost("a", "1", identityFile = "~/.ssh/id_ed25519"),
                ImportedHost("b", "2", identityFile = "~/.ssh/work.pem", proxyJump = "bastion"),
                ImportedHost("old", "o", proxyJump = "bastion"),
            ),
        )
        assertEquals(HostRepository.ImportResult(added = 3, missingKeys = 1), result)
        val hosts = db.hostDao().allHosts().associateBy { it.alias }
        assertEquals(AuthMethod.KEY, hosts.getValue("a").authMethod)
        assertEquals(key, hosts.getValue("a").keyId)
        assertEquals(AuthMethod.PASSWORD, hosts.getValue("b").authMethod)
        assertNull(hosts.getValue("b").identity)
        assertEquals(hosts.getValue("bastion").id, hosts.getValue("b").jumpHostId)
        // « existing aliases are skipped »: not linked either.
        assertNull(db.hostDao().getHost(existing)!!.jumpHostId)
    }
}
