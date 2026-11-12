package com.nautile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.Identity
import com.nautile.data.model.PortForward
import com.nautile.data.model.Snippet
import com.nautile.data.model.SshKey
import com.nautile.data.model.SyncTombstone

/** Lookups and low-level writes used by the synchronisation merge. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_tombstone") suspend fun tombstones(): List<SyncTombstone>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTombstone(t: SyncTombstone)
    @Query("DELETE FROM sync_tombstone WHERE syncId = :syncId") suspend fun dropTombstone(syncId: String)
    @Query("DELETE FROM sync_tombstone WHERE deletedAt < :before") suspend fun pruneTombstones(before: Long)

    @Query("SELECT * FROM host_group WHERE syncId = :s") suspend fun group(s: String): HostGroup?
    @Query("SELECT * FROM host WHERE syncId = :s") suspend fun host(s: String): Host?
    @Query("SELECT * FROM ssh_key WHERE syncId = :s") suspend fun key(s: String): SshKey?
    @Query("SELECT * FROM identity WHERE syncId = :s") suspend fun identity(s: String): Identity?
    @Query("SELECT * FROM snippet WHERE syncId = :s") suspend fun snippet(s: String): Snippet?
    @Query("SELECT * FROM port_forward WHERE syncId = :s") suspend fun forward(s: String): PortForward?

    @Query("UPDATE host_group SET syncId = :s WHERE id = :id") suspend fun setGroupSyncId(id: Long, s: String)
    @Query("UPDATE host SET syncId = :s WHERE id = :id") suspend fun setHostSyncId(id: Long, s: String)
    @Query("UPDATE ssh_key SET syncId = :s WHERE id = :id") suspend fun setKeySyncId(id: Long, s: String)
    @Query("UPDATE identity SET syncId = :s WHERE id = :id") suspend fun setIdentitySyncId(id: Long, s: String)
    @Query("UPDATE snippet SET syncId = :s WHERE id = :id") suspend fun setSnippetSyncId(id: Long, s: String)
    @Query("UPDATE port_forward SET syncId = :s WHERE id = :id") suspend fun setForwardSyncId(id: Long, s: String)

    @Query("DELETE FROM host_group WHERE syncId = :s AND updatedAt <= :at") suspend fun deleteGroup(s: String, at: Long): Int
    @Query("DELETE FROM host WHERE syncId = :s AND updatedAt <= :at") suspend fun deleteHost(s: String, at: Long): Int
    @Query("DELETE FROM ssh_key WHERE syncId = :s AND updatedAt <= :at") suspend fun deleteKey(s: String, at: Long): Int
    @Query("DELETE FROM identity WHERE syncId = :s AND updatedAt <= :at") suspend fun deleteIdentity(s: String, at: Long): Int
    @Query("DELETE FROM snippet WHERE syncId = :s AND updatedAt <= :at") suspend fun deleteSnippet(s: String, at: Long): Int
    @Query("DELETE FROM port_forward WHERE syncId = :s AND updatedAt <= :at") suspend fun deleteForward(s: String, at: Long): Int
    @Query("DELETE FROM known_host WHERE 'kh:' || host || ':' || port || ':' || keyType = :s AND addedAt <= :at") suspend fun deleteKnownHost(s: String, at: Long): Int

    /** The latest local change, to know whether there is something to send. */
    @Query(
        """SELECT MAX(m) FROM (
            SELECT MAX(updatedAt) AS m FROM host UNION ALL SELECT MAX(updatedAt) FROM host_group
            UNION ALL SELECT MAX(updatedAt) FROM ssh_key UNION ALL SELECT MAX(updatedAt) FROM identity
            UNION ALL SELECT MAX(updatedAt) FROM snippet UNION ALL SELECT MAX(updatedAt) FROM port_forward
            UNION ALL SELECT MAX(addedAt) FROM known_host UNION ALL SELECT MAX(deletedAt) FROM sync_tombstone)""",
    )
    suspend fun lastChange(): Long?
}
