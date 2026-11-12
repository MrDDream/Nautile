package com.nautile.app.screenshots

import androidx.room.Room
import com.nautile.app.NautileApp
import com.nautile.data.db.NautileDatabase
import com.nautile.data.db.SyncSchema

/** The app with an in-memory database and none of the start-up work (sync, shortcuts, widget). */
class ScreenshotApp : NautileApp() {
    override val database: NautileDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, NautileDatabase::class.java)
            .addCallback(SyncSchema.callback)
            .allowMainThreadQueries()
            .build()
    }

    override fun onCreate() {
        // NautileApp.onCreate is skipped on purpose: the tests set the language themselves.
    }
}
