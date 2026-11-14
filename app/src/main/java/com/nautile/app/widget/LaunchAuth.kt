package com.nautile.app.widget

import android.content.Context
import android.content.Intent
import com.nautile.app.LaunchActivity
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Proof that a widget or shortcut intent was made by this install. The launcher (and the widget host) can
 * read those intents: each carries an HMAC of its host, never the secret itself, so what it learns
 * opens only that host, which it could already do by tapping it.
 */
object LaunchAuth {
    private const val PREFS = "nautile_launch"
    /** The raw token of earlier versions, carried in the intents themselves: dropped, it may be known. */
    private const val KEY_LEGACY_TOKEN = "token"
    private const val KEY_SECRET = "hmac_secret"
    const val EXTRA_HOST_ID = "host_id"
    const val EXTRA_MAC = "mac"

    @Synchronized
    fun secret(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SECRET, null)?.let { s -> runCatching { Base64.getDecoder().decode(s) }.getOrNull()?.takeIf { it.size == 32 }?.let { return it } }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_SECRET, Base64.getEncoder().encodeToString(bytes)).remove(KEY_LEGACY_TOKEN).commit()
        return bytes
    }

    fun mac(secret: ByteArray, hostId: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("connect:$hostId".toByteArray()))
    }

    /** In constant time. */
    fun verify(secret: ByteArray, hostId: Long, mac: String?): Boolean =
        mac != null && hostId > 0 && MessageDigest.isEqual(mac(secret, hostId).toByteArray(), mac.toByteArray())

    /** Opens the app and connects to [hostId] (widget, launcher shortcuts), through [LaunchActivity]. */
    fun connectIntent(context: Context, hostId: Long): Intent =
        Intent(context, LaunchActivity::class.java)
            .setAction("com.nautile.app.CONNECT.$hostId")
            .putExtra(EXTRA_HOST_ID, hostId)
            .putExtra(EXTRA_MAC, mac(secret(context), hostId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
