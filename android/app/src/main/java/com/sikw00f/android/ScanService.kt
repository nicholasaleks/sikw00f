package com.sikw00f.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a scan alive with the screen off or the app in the background: a
 * foreground service (ongoing "Scanning…" notification) so Android doesn't
 * kill or freeze the process, plus a partial wake lock so the CPU keeps
 * draining the USB serial stream. The scan itself still runs in the
 * ViewModel; this only exists while a scan is running.
 */
class ScanService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sikw00f:scan")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_MS)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopRequested?.invoke()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Scanning"
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, build(this, text),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            )
        } catch (e: Exception) {
            // e.g. USB permission revoked: scanning still works in the foreground, just not backgrounded.
            LogBus.w("Background scanning unavailable: ${e.message}")
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "scanning"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_TEXT = "text"
        private const val ACTION_STOP = "com.sikw00f.android.STOP_SCAN"
        /** Safety net: the wake lock times out even if a stop is somehow missed. */
        private const val MAX_WAKE_MS = 12 * 60 * 60 * 1000L

        /** Set by the ViewModel; invoked by the notification's Stop action. */
        @Volatile var onStopRequested: (() -> Unit)? = null

        @Volatile private var running = false
        @Volatile private var lastText: String? = null

        /** Starts the service, or refreshes its notification text if it's already running. */
        fun update(context: Context, text: String) {
            val app = context.applicationContext
            if (running && text == lastText) return
            lastText = text
            if (!running) {
                running = true
                ensureChannel(app)
                ContextCompat.startForegroundService(app, Intent(app, ScanService::class.java).putExtra(EXTRA_TEXT, text))
            } else {
                try {
                    NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, build(app, text))
                } catch (_: SecurityException) {
                }
            }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            lastText = null
            context.applicationContext.stopService(Intent(context.applicationContext, ScanService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val ch = NotificationChannel(CHANNEL_ID, "Scanning", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a scan runs in the background"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        private fun build(context: Context, text: String) = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("SiKW00F scanning")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                0, "Stop scan",
                PendingIntent.getService(
                    context, 1,
                    Intent(context, ScanService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }
}
