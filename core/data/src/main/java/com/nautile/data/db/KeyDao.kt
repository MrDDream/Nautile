package com.nautile.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.nautile.data.model.Host
import com.nautile.data.model.Identity
import com.nautile.data.model.SshKey
import kotlinx.coroutines.flow.Flow

data class KeyUsage(val keyId: Long, val count: Int)

@Dao
interface KeyDao {
    @Query("SELECT * FROM ssh_key ORDER BY name COLLATE NOCASE")
    fun observeKeys(): Flow<List<SshKey>>

    @Query("SELECT * FROM ssh_key ORDER BY name COLLATE NOCASE")
    suspend fun allKeys(): List<SshKey>

    @Query("SELECT * FROM ssh_key WHERE id = :id")
    suspend fun key(id: Long): SshKey?

    @Query("SELECT * FROM ssh_key WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun keyByFingerprint(fingerprint: String): SshKey?

    @Insert
    suspend fun insertKey(key: SshKey): Long

    @Update
    suspend fun updateKey(key: SshKey)

    @Delete
    suspend fun deleteKey(key: SshKey)

    /** Hosts using a key directly or through an identity. */
    @Query(
        """SELECT k.id AS keyId,
           (SELECT COUNT(*) FROM host h LEFT JOIN identity i ON h.identityId = i.id
             WHERE (h.authMethod = 'KEY' AND h.keyId = k.id) OR (h.authMethod = 'IDENTITY' AND i.keyId = k.id)) AS count
           FROM ssh_key k""",
    )
    fun observeUsage(): Flow<List<KeyUsage>>

    @Query("SELECT * FROM identity ORDER BY name COLLATE NOCASE")
    fun observeIdentities(): Flow<List<Identity>>

    @Query("SELECT * FROM identity WHERE id = :id")
    suspend fun identity(id: Long): Identity?

    @Upsert
    suspend fun upsertIdentity(identity: Identity): Long

    @Delete
    suspend fun deleteIdentity(identity: Identity)

    @Query("SELECT * FROM host WHERE identity IS NOT NULL AND keyId IS NULL")
    suspend fun hostsWithLegacyKey(): List<Host>

    @Query("UPDATE host SET keyId = :keyId, identity = NULL WHERE id = :hostId")
    suspend fun linkLegacyKey(hostId: Long, keyId: Long)

    @Query("UPDATE host SET identity = NULL WHERE id = :hostId")
    suspend fun clearLegacyKey(hostId: Long)

    @Query("SELECT * FROM identity")
    suspend fun allIdentities(): List<Identity>
}
