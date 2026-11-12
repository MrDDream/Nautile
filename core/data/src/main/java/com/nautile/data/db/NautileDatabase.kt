package com.nautile.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.nautile.data.model.Host
import com.nautile.data.model.HostGroup
import com.nautile.data.model.Identity
import com.nautile.data.model.KnownHost
import com.nautile.data.model.SshKey
import com.nautile.data.model.Snippet
import com.nautile.data.model.PortForward
import com.nautile.data.model.CommandHistory
import com.nautile.data.model.ConnectionLog
import com.nautile.data.model.SyncTombstone
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [Host::class, HostGroup::class, KnownHost::class, SshKey::class, Identity::class, Snippet::class, PortForward::class, CommandHistory::class, ConnectionLog::class, SyncTombstone::class],
    version = 10,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10)],
)
@TypeConverters(Converters::class)
abstract class NautileDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun keyDao(): KeyDao
    abstract fun toolsDao(): ToolsDao
    abstract fun syncDao(): SyncDao

    companion object {
        /** Opens the SQLCipher-encrypted database with [passphrase] (kept by core:security). */
        fun open(context: Context, passphrase: ByteArray): NautileDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, NautileDatabase::class.java, "nautile.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addCallback(SyncSchema.callback)
                .build()
        }
    }
}
