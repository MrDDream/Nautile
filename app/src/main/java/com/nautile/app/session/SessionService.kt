package com.nautile.app.session

import com.nautile.i18n.tr

import android.Manifest
import android.app.NotificationChannel
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.nautile.app.MainActivity
import com.nautile.app.R

/** Keeps the process alive while SSH sessions are open. */
class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, lastSessions, lastTunnels, lastTransfers, lastDesktops), type)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "sessions"
        private const val NOTIFICATION_ID = 1
        @Volatile private var lastSessions = 1
        @Volatile private var lastTunnels = 0
        @Volatile private var lastTransfers = 0
        @Volatile private var lastDesktops = 0

        fun update(context: Context, sessions: Int, tunnels: Int, transfers: Int, desktops: Int = 0) {
            lastSessions = sessions; lastTunnels = tunnels; lastTransfers = transfers; lastDesktops = desktops
            if (sessions + tunnels + transfers + desktops == 0) return
            ensureChannel(context)
            val granted = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification(context, sessions, tunnels, transfers, desktops))
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sessions actives", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun summary(sessions: Int, tunnels: Int, transfers: Int, desktops: Int) = listOfNotNull(
            sessions.takeIf { it > 0 }?.let { if (it > 1) tr("%1\$s sessions terminal", it) else tr("1 session terminal") },
            desktops.takeIf { it > 0 }?.let { if (it > 1) tr("%1\$s bureaux à distance", it) else tr("1 bureau à distance") },
            tunnels.takeIf { it > 0 }?.let { if (it > 1) tr("%1\$s tunnels actifs", it) else tr("1 tunnel actif") },
            transfers.takeIf { it > 0 }?.let { if (it > 1) tr("%1\$s transferts en cours", it) else tr("1 transfert en cours") },
        ).joinToString(" · ").ifEmpty { "Connexions actives" }

        private fun notification(context: Context, sessions: Int, tunnels: Int, transfers: Int, desktops: Int) = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle("Nautile")
            .setContentText(summary(sessions, tunnels, transfers, desktops))
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }
}
