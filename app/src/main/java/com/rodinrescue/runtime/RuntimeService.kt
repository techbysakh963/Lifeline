package com.rodinrescue.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps Lifeline running while a built-in CLI downloads or waits for the user's sign-in. Without it Android pauses the
 * app as soon as the user switches to the browser to sign in, and the CLI inside loses its connection: seen on the
 * POCO, 2026-10-28, Codex's device sign-in failed with "error sending request" while it was waiting in the background
 * (the same sign-in run outside the app waited a full minute without an error). Shows an ongoing notification.
 */
class RuntimeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Built-in AI setup", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Lifeline").setContentText(intent?.getStringExtra(TEXT) ?: "Working…").setOngoing(true).setContentIntent(open).build()
        // Always go foreground first: Android crashes an app whose service, started with startForegroundService(), stops
        // before it did (seen 2026-10-28 when a quick status check stopped it within milliseconds).
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        if (intent?.action == STOP) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "runtime"; private const val ID = 7301; private const val TEXT = "text"; private const val STOP = "stop"
        private var holders = 0

        /** Starts (or updates) the service; pair every call with [release]. */
        @Synchronized fun hold(ctx: Context, text: String) {
            holders++
            runCatching { ctx.startForegroundService(Intent(ctx, RuntimeService::class.java).putExtra(TEXT, text)) }
        }

        @Synchronized fun release(ctx: Context) {
            holders = (holders - 1).coerceAtLeast(0)
            // Stopped from inside the service, after its startForeground() (never stopService() here).
            if (holders == 0) runCatching { ctx.startForegroundService(Intent(ctx, RuntimeService::class.java).setAction(STOP)) }
        }
    }
}
