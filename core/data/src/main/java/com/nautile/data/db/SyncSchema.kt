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

    /** The highest stamp ever written (2^62): far enough from Long.MAX_VALUE that `stamp + 1` stays an integer. */
    const val MAX_STAMP = 4611686018427387904L

    /** How far ahead of this clock a stamp is believed (a year); beyond, it is brought back (see [stampCap]). */
    const val FUTURE_LIMIT_MS = 31536000000L

    /**
     * 2026-11-01 00:00 UTC, before this version existed: a clock earlier than that is wrong (started before the network
     * time, no battery-backed clock…), and the stamps are not judged by it ([stampCap]). db.rs CLOCK_FLOOR_MS.
     */
    const val CLOCK_FLOOR_MS = 1793491200000L

    /**
     * The latest stamp believed at [now]: a year ahead, never above [MAX_STAMP]; only [MAX_STAMP] when [now] is before
     * [CLOCK_FLOOR_MS], a clock that wrong would otherwise bring back every stamp.
     */
    fun stampCap(now: Long): Long = if (now < CLOCK_FLOOR_MS) MAX_STAMP else minOf(now + FUTURE_LIMIT_MS, MAX_STAMP)

    /** One past [at], never above [MAX_STAMP]. */
    fun bump(at: Long): Long = minOf(at, MAX_STAMP - 1) + 1

    /** Synced tables and the columns whose change is an edit. */
    val TABLES: Map<String, List<String>> = mapOf(
        "host_group" to listOf("name", "color", "sortOrder", "icon"),
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

    /**
     * References between synced records: column → referenced table (db.rs REFS). Row ids differ between devices, so
     * a record's canonical content (SyncMerge.contentHash) names the referenced record by its sync id.
     */
    val REFS: Map<String, Map<String, String>> = mapOf(
        "host" to mapOf("keyId" to "ssh_key", "identityId" to "identity", "groupId" to "host_group", "jumpHostId" to "host"),
        "identity" to mapOf("keyId" to "ssh_key"),
        "port_forward" to mapOf("hostId" to "host"),
    )

    /**
     * True outside a merge. `sync_ctl.merging` is 1 while another device's data is written (SyncMerge, in its
     * transaction): the triggers then leave updatedAt and the tombstones alone, so what SQLite does on its own
     * (ON DELETE SET NULL / CASCADE when a remote deletion is applied) is not taken for a local edit.
     */
    private const val NOT_MERGING = "NOT EXISTS (SELECT 1 FROM sync_ctl WHERE merging != 0)"

    /** Puts the triggers aside while another device's data is written, then back. */
    const val MERGING_ON = "UPDATE sync_ctl SET merging = 1"
    const val MERGING_OFF = "UPDATE sync_ctl SET merging = 0"

    /** Tombstone id of a known host, which has no sync id of its own. */
    fun knownHostId(host: String, port: Int, keyType: String) = "kh:$host:$port:$keyType"

    fun statements(): List<String> = buildList {
        add("CREATE TABLE IF NOT EXISTS sync_ctl (merging INTEGER NOT NULL)")
        add("INSERT INTO sync_ctl (merging) SELECT 0 WHERE NOT EXISTS (SELECT 1 FROM sync_ctl)")
        // A merge never outlives its transaction; this only guards against a value left by hand.
        add(MERGING_OFF)
        TABLES.forEach { (table, columns) ->
            // Rows created before synchronisation existed.
            add("UPDATE $table SET syncId = lower(hex(randomblob(16))) WHERE syncId = ''")
            add("UPDATE $table SET updatedAt = 1 WHERE updatedAt = 0")
            // Recreated each time (like the others): their body changes with the versions.
            add("DROP TRIGGER IF EXISTS sync_ins_$table")
            add(
                """CREATE TRIGGER sync_ins_$table AFTER INSERT ON $table BEGIN
                    UPDATE $table SET
                        syncId = CASE WHEN NEW.syncId = '' THEN lower(hex(randomblob(16))) ELSE NEW.syncId END,
                        updatedAt = CASE WHEN NEW.updatedAt = 0 THEN $NOW ELSE MIN(NEW.updatedAt, $MAX_STAMP) END
                    WHERE id = NEW.id;
                END""",
            )
            // An edit that moves updatedAt forward keeps it. Any other edit, including one saved from a stale copy
            // (older updatedAt), is stamped now, and never below the previous value, so a row synced from a device
            // whose clock runs ahead still moves forward; never above MAX_STAMP, so it stays an integer. Not during a
            // merge, which writes updatedAt itself.
            add("DROP TRIGGER IF EXISTS sync_upd_$table")
            add(
                """CREATE TRIGGER sync_upd_$table AFTER UPDATE OF ${columns.joinToString(", ")} ON $table
                WHEN NEW.updatedAt <= OLD.updatedAt AND $NOT_MERGING BEGIN
                    UPDATE $table SET updatedAt = MIN(MAX($NOW, OLD.updatedAt + 1), $MAX_STAMP) WHERE id = NEW.id;
                END""",
            )
            // A deletion is dated after the row's last change, even one stamped by a clock running ahead, or it
            // would apply nowhere. Not during a merge, which writes the other device's tombstones itself.
            add("DROP TRIGGER IF EXISTS sync_del_$table")
            add(
                """CREATE TRIGGER sync_del_$table AFTER DELETE ON $table WHEN OLD.syncId != '' AND $NOT_MERGING BEGIN
                    INSERT OR REPLACE INTO sync_tombstone(syncId, kind, deletedAt)
                    VALUES (OLD.syncId, '$table', MIN(MAX($NOW, OLD.updatedAt + 1), $MAX_STAMP));
                END""",
            )
        }
        add("DROP TRIGGER IF EXISTS sync_del_known_host")
        add(
            """CREATE TRIGGER sync_del_known_host AFTER DELETE ON known_host WHEN $NOT_MERGING BEGIN
                INSERT OR REPLACE INTO sync_tombstone(syncId, kind, deletedAt)
                VALUES ('kh:' || OLD.host || ':' || OLD.port || ':' || OLD.keyType, 'known_host', MIN(MAX($NOW, OLD.addedAt + 1), $MAX_STAMP));
            END""",
        )
        // Trusting a key again cancels its tombstone, and is dated after it: a tombstone dated ahead (the key was trusted
        // on a device whose clock runs ahead) still held by another device would otherwise delete the new trust again.
        add("DROP TRIGGER IF EXISTS sync_ins_known_host")
        add(
            """CREATE TRIGGER sync_ins_known_host AFTER INSERT ON known_host BEGIN
                UPDATE known_host SET addedAt = MIN((SELECT deletedAt FROM sync_tombstone WHERE syncId = $KH_NEW) + 1, $MAX_STAMP)
                    WHERE id = NEW.id AND addedAt <= (SELECT deletedAt FROM sync_tombstone WHERE syncId = $KH_NEW);
                DELETE FROM sync_tombstone WHERE syncId = $KH_NEW;
            END""",
        )
        addAll(stampRepairs())
    }

    /** Tombstone id of the known host being inserted, in SQL ([knownHostId]). */
    private const val KH_NEW = "'kh:' || NEW.host || ':' || NEW.port || ':' || NEW.keyType"

    /**
     * Brings back the stamps beyond [stampCap] at [now] (written before stamps were bounded, or restored from a file):
     * a stamp that far ahead would win over every later edit, and one past Long.MAX_VALUE is stored as a REAL.
     * Tombstones are brought back the same way, never dropped: the deletion still applies. With a clock before
     * [CLOCK_FLOOR_MS] only what is past [MAX_STAMP] is. Run at each opening and before each merge; only updatedAt /
     * addedAt / deletedAt change, which the triggers do not watch. db.rs stamp_repairs, word for word.
     */
    fun stampRepairs(now: Long = System.currentTimeMillis()): List<String> = buildList {
        val cap = stampCap(now)
        TABLES.keys.forEach { add("UPDATE $it SET updatedAt = $cap WHERE updatedAt > $cap") }
        add("UPDATE known_host SET addedAt = $cap WHERE addedAt > $cap")
        add("UPDATE sync_tombstone SET deletedAt = $cap WHERE deletedAt > $cap")
    }

    /** Installs the triggers each time the database opens (they are idempotent). */
    val callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            statements().forEach(db::execSQL)
        }
    }
}
