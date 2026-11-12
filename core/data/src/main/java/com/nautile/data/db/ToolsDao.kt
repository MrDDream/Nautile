package com.nautile.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.nautile.data.model.CommandHistory
import com.nautile.data.model.ConnectionLog
import androidx.room.Insert
import com.nautile.data.model.PortForward
import com.nautile.data.model.Snippet
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolsDao {
    @Query("SELECT * FROM connection_log ORDER BY at DESC LIMIT 200")
    fun observeConnections(): Flow<List<ConnectionLog>>

    @Insert
    suspend fun insertConnection(entry: ConnectionLog): Long

    /** Keeps the [keep] most recent entries. */
    @Query("DELETE FROM connection_log WHERE id NOT IN (SELECT id FROM connection_log ORDER BY at DESC LIMIT :keep)")
    suspend fun trimConnections(keep: Int = 200)

    @Query("DELETE FROM connection_log WHERE id = :id")
    suspend fun deleteConnection(id: Long)

    @Query("DELETE FROM connection_log")
    suspend fun clearConnections()

    @Query("SELECT * FROM snippet ORDER BY runCount DESC, name COLLATE NOCASE")
    fun observeSnippets(): Flow<List<Snippet>>

    @Upsert
    suspend fun upsertSnippet(snippet: Snippet): Long

    @Delete
    suspend fun deleteSnippet(snippet: Snippet)

    @Query("UPDATE snippet SET runCount = runCount + 1 WHERE id = :id")
    suspend fun countRun(id: Long)

    @Query("SELECT * FROM port_forward ORDER BY name COLLATE NOCASE")
    fun observeForwards(): Flow<List<PortForward>>

    @Query("SELECT * FROM port_forward WHERE hostId = :hostId AND autoStart = 1")
    suspend fun autoForwards(hostId: Long): List<PortForward>

    @Query("SELECT * FROM port_forward WHERE id = :id")
    suspend fun forward(id: Long): PortForward?

    @Upsert
    suspend fun upsertForward(forward: PortForward): Long

    @Delete
    suspend fun deleteForward(forward: PortForward)

    @Query("SELECT * FROM command_history WHERE hostId = :hostId AND command LIKE :pattern ESCAPE '\\' AND command != :prefix ORDER BY count DESC, lastUsed DESC LIMIT :limit")
    suspend fun suggestionsLike(hostId: Long, pattern: String, prefix: String, limit: Int): List<CommandHistory>

    /** Commands of [hostId] starting with [prefix], most used first. */
    suspend fun suggestions(hostId: Long, prefix: String, limit: Int = 5): List<CommandHistory> =
        suggestionsLike(hostId, prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", prefix, limit)

    @Query("INSERT OR IGNORE INTO command_history (hostId, command, count, lastUsed) VALUES (:hostId, :command, 0, :at)")
    suspend fun insertHistory(hostId: Long, command: String, at: Long)

    @Query("UPDATE command_history SET count = count + 1, lastUsed = :at WHERE hostId = :hostId AND command = :command")
    suspend fun bumpHistory(hostId: Long, command: String, at: Long)

    @Query("DELETE FROM command_history")
    suspend fun clearHistory()

    @Query("SELECT COUNT(*) FROM command_history")
    fun observeHistoryCount(): Flow<Int>

    @Query("SELECT * FROM snippet")
    suspend fun allSnippets(): List<Snippet>

    @Query("SELECT * FROM port_forward")
    suspend fun allForwards(): List<PortForward>

    @Query("SELECT * FROM command_history WHERE hostId = :hostId ORDER BY lastUsed DESC LIMIT 200")
    fun observeHistory(hostId: Long): Flow<List<CommandHistory>>
}
