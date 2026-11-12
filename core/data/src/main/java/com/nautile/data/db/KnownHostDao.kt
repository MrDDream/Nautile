package com.nautile.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nautile.data.model.KnownHost
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_host WHERE host = :host AND port = :port")
    fun findBlocking(host: String, port: Int): List<KnownHost>

    @Query("DELETE FROM known_host WHERE host = :host AND port = :port AND keyType = :keyType")
    fun deleteBlocking(host: String, port: Int, keyType: String)

    @Upsert
    fun upsertBlocking(key: KnownHost)

    @Transaction
    fun replaceBlocking(key: KnownHost) {
        deleteBlocking(key.host, key.port, key.keyType)
        upsertBlocking(key)
    }

    @Query("SELECT * FROM known_host ORDER BY host, port")
    fun observeAll(): Flow<List<KnownHost>>

    @Delete
    suspend fun delete(key: KnownHost)

    @Query("SELECT * FROM known_host")
    suspend fun all(): List<KnownHost>
}
