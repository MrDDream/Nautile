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

    /** Two providers ready and one prompt of the user's, without the Android Keystore. */
    override val ai: com.nautile.app.ai.AiAssistant by lazy {
        val store = mutableMapOf(
            "ai_provider" to "OPENAI", "key_OPENAI" to "sk-test", "model_OPENAI" to "gpt-5-mini",
            "key_CLAUDE" to "sk-ant-test", "model_CLAUDE" to "claude-sonnet-5-5",
            "url_LOCAL" to "http://192.168.1.10:11434/v1", "model_LOCAL" to "qwen3",
        )
        com.nautile.app.ai.AiAssistant(
            object : com.nautile.app.ai.AiAssistant.Secrets {
                override fun get(name: String) = store[name]
                override fun put(name: String, value: String?) { if (value == null) store.remove(name) else store[name] = value }
            },
            getSharedPreferences("nautile_ai_test", MODE_PRIVATE),
        ).apply {
            if (prompts.value.isEmpty()) savePrompt(com.nautile.app.ai.AiPrompt("p1", com.nautile.i18n.tr("Pédagogue"), com.nautile.i18n.tr("Explique chaque option de la commande, pour quelqu'un qui débute.")))
        }
    }

    override fun onCreate() {
        // NautileApp.onCreate is skipped on purpose: the tests set the language themselves.
    }
}
