package com.nautile.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM host ORDER BY alias COLLATE NOCASE")
    fun observeHosts(): Flow<List<Host>>

    @Query("SELECT * FROM host WHERE id = :id")
    suspend fun getHost(id: Long): Host?

    @Query("SELECT * FROM host WHERE alias = :alias LIMIT 1")
    suspend fun findByAlias(alias: String): Host?

    @Upsert
    suspend fun upsertHost(host: Host): Long

    @Delete
    suspend fun deleteHost(host: Host)

    @Query("UPDATE host SET osId = :osId WHERE id = :id")
    suspend fun setOs(id: Long, osId: String)

    @Query("UPDATE host SET lastConnectedAt = :at WHERE id = :id")
    suspend fun markConnected(id: Long, at: Long)

    @Query("SELECT * FROM host_group ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeGroups(): Flow<List<HostGroup>>

    @Query("SELECT * FROM host_group WHERE name = :name LIMIT 1")
    suspend fun findGroup(name: String): HostGroup?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(group: HostGroup): Long

    @Delete
    suspend fun deleteGroup(group: HostGroup)

    @androidx.room.Update
    suspend fun updateGroup(group: HostGroup)

    @Query("UPDATE host SET groupId = :groupId WHERE id = :hostId")
    suspend fun setHostGroup(hostId: Long, groupId: Long?)

    @Query("SELECT * FROM host")
    suspend fun allHosts(): List<Host>

    @Query("SELECT * FROM host_group")
    suspend fun allGroups(): List<HostGroup>
}
