package com.nautile.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQLite triggers that keep the synchronisation metadata without touching the app's code:
 * every insert gets a sync id, every edit of a synced column moves `updatedAt`, every delete leaves a tombstone.
 * Columns that only matter on this device (last connection, run count, detected OS) do not count as edits.
 */
object SyncSchema {
    /** Epoch millis in SQL. */
    private const val NOW = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

    /** Synced tables and the columns whose change is an edit. */
    val TABLES: Map<String, List<String>> = mapOf(
        "host_group" to listOf("name", "color", "sortOrder"),
        "host" to listOf(
            "alias", "hostname", "port", "username", "protocol", "authMethod", "password", "identity", "keyId", "identityId",
            "agentForwarding", "proxyType", "proxyHost", "proxyPort", "proxyUsername", "proxyPassword", "knockSequence", "serialBaud",
            "rdpDomain", "rdpResolution", "rdpScale", "rdpSecurity", "rdpAudio", "rdpConsole", "groupId", "jumpHostId", "color", "tags", "startupCommand",
        ),
        "ssh_key" to listOf("name", "keyType", "description", "publicKey", "privateKey", "fingerprint", "encrypted", "savedPassphrase", "generated"),
        "identity" to listOf("name", "username", "password", "keyId"),
        "snippet" to listOf("name", "command", "tag"),
        "port_forward" to listOf("name", "hostId", "kind", "bindHost", "bindPort", "destHost", "destPort", "autoStart"),
    )

    /** Tombstone id of a known host, which has no sync id of its own. */
    fun knownHostId(host: String, port: Int, keyType: String) = "kh:$host:$port:$keyType"

    fun statements(): List<String> = buildList {
        TABLES.forEach { (table, columns) ->
            // Rows created before synchronisation existed.
            add("UPDATE $table SET syncId = lower(hex(randomblob(16))) WHERE syncId = ''")
            add("UPDATE $table SET updatedAt = 1 WHERE updatedAt = 0")
            add(
                """CREATE TRIGGER IF NOT EXISTS sync_ins_$table AFTER INSERT ON $table BEGIN
                    UPDATE $table SET
                        syncId = CASE WHEN NEW.syncId = '' THEN lower(hex(randomblob(16))) ELSE NEW.syncId END,
                        updatedAt = CASE WHEN NEW.updatedAt = 0 THEN $NOW ELSE NEW.updatedAt END
                    WHERE id = NEW.id;
                END""",
            )
            // Writing updatedAt explicitly (a merged remote change) keeps it; any other edit stamps now.
            add(
                """CREATE TRIGGER IF NOT EXISTS sync_upd_$table AFTER UPDATE OF ${columns.joinToString(", ")} ON $table
                WHEN NEW.updatedAt = OLD.updatedAt BEGIN
                    UPDATE $table SET updatedAt = $NOW WHERE id = NEW.id;
                END""",
            )
            add(
                """CREATE TRIGGER IF NOT EXISTS sync_del_$table AFTER DELETE ON $table WHEN OLD.syncId != '' BEGIN
                    INSERT OR REPLACE INTO sync_tombstone(syncId, kind, deletedAt) VALUES (OLD.syncId, '$table', $NOW);
                END""",
            )
        }
        add(
            """CREATE TRIGGER IF NOT EXISTS sync_del_known_host AFTER DELETE ON known_host BEGIN
                INSERT OR REPLACE INTO sync_tombstone(syncId, kind, deletedAt)
                VALUES ('kh:' || OLD.host || ':' || OLD.port || ':' || OLD.keyType, 'known_host', $NOW);
            END""",
        )
        // Trusting a key again cancels its tombstone.
        add(
            """CREATE TRIGGER IF NOT EXISTS sync_ins_known_host AFTER INSERT ON known_host BEGIN
                DELETE FROM sync_tombstone WHERE syncId = 'kh:' || NEW.host || ':' || NEW.port || ':' || NEW.keyType;
            END""",
        )
    }

    /** Installs the triggers each time the database opens (they are idempotent). */
    val callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            statements().forEach(db::execSQL)
        }
    }
}
