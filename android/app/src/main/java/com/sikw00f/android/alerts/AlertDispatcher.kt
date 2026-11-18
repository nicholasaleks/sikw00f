package com.sikw00f.android.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sikw00f.android.DetectionLog
import com.sikw00f.android.LogBus
import com.sikw00f.android.LogLevel
import com.sikw00f.android.MainActivity
import com.sikw00f.android.R
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class AlertTrigger(val key: String, val title: String) {
    NEW_DRONE("new_drone", "Drone detected"),
    ARMED("armed", "Drone armed"),
    FIRST_GPS("first_gps", "Drone located"),
    CRITICAL("critical", "Critical drone message"),
    PROXIMITY("proximity", "Drone nearby"),
    STRONG_SIGNAL("strong_signal", "Strong drone signal"),
    TEST("test", "SiKW00F test alert")
}

/** One detection worth telling someone about. [fields] is extra telemetry for webhooks. */
data class Alert(
    val trigger: AlertTrigger,
    val netId: Int,
    val message: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val fields: Map<String, Any?> = emptyMap(),
    /** User's name for this NetID (Known drones), shown instead of "NetID n". */
    val label: String? = null
) {
    val who: String get() = label ?: "NetID $netId"
}

/**
 * Fans an [Alert] out to the actions the user enabled: system notification,
 * sound / vibration, and an HTTP webhook. Each (trigger, NetID) pair is rate
 * limited by the cooldown so a chatty drone can't flood the phone or a channel.
 */
class AlertDispatcher(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val lastFired = HashMap<Pair<AlertTrigger, Int>, Long>()
    private var nextNotificationId = 1000

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    init {
        createChannels()
    }

    fun fire(alert: Alert, settings: AlertSettings) {
        val isTest = alert.trigger == AlertTrigger.TEST
        if (!isTest) {
            if (!settings.anyActionEnabled) return
            val key = alert.trigger to alert.netId
            val now = System.currentTimeMillis()
            synchronized(lastFired) {
                val last = lastFired[key]
                if (last != null && now - last < settings.triggers.cooldownS * 1000L) return
                lastFired[key] = now
            }
        }
        val actions = buildList {
            if (settings.notification.enabled) add("notification")
            if (settings.sound.enabled) add("sound")
            if (settings.webhook.enabled && settings.webhook.url.isNotBlank()) add("webhook")
        }
        if (actions.isEmpty()) {
            if (isTest) LogBus.w("Test alert: no alert actions are enabled.")
            return
        }
        LogBus.event("ALERT", "${alert.trigger.title} · ${alert.who}: ${alert.message} → ${actions.joinToString()}")
        if (!isTest) {
            DetectionLog.record("alert", mapOf("netId" to alert.netId, "label" to alert.label, "trigger" to alert.trigger.key, "message" to alert.message, "actions" to actions))
        }
        if (settings.notification.enabled) notify(alert, settings.notification)
        if (settings.sound.enabled) playSound(settings.sound)
        if ("webhook" in actions) postWebhook(alert, settings.webhook)
    }

    /** Clears per-drone cooldowns, e.g. when a new scan starts. */
    fun resetCooldowns() = synchronized(lastFired) { lastFired.clear() }

    // ---------------------------------------------------------------- notification

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = app.getSystemService(NotificationManager::class.java)
        // Silent channels: audio is the Sound action's job, so the two never double up.
        listOf(
            NotificationChannel(CHANNEL_URGENT, "Drone alerts", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_QUIET, "Drone alerts (quiet)", NotificationManager.IMPORTANCE_DEFAULT)
        ).forEach { ch ->
            ch.description = "Detections from SiKW00F scans"
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }

    fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notify(alert: Alert, cfg: NotificationAction) {
        if (!canPostNotifications()) {
            LogBus.w("Notification skipped: permission not granted.")
            return
        }
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = buildString {
            append(alert.message)
            if (cfg.includePosition && alert.lat != null && alert.lon != null) {
                append("\n%.5f, %.5f".format(Locale.US, alert.lat, alert.lon))
            }
        }
        val n = NotificationCompat.Builder(app, if (cfg.headsUp) CHANNEL_URGENT else CHANNEL_QUIET)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(if (alert.trigger == AlertTrigger.TEST) alert.trigger.title else "${alert.trigger.title} · ${alert.who}")
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (cfg.headsUp) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One notification per drone + trigger, so repeats update instead of stacking.
        val id = if (alert.trigger == AlertTrigger.TEST) nextNotificationId++ else alert.netId * 16 + alert.trigger.ordinal
        try {
            NotificationManagerCompat.from(app).notify(id, n)
        } catch (e: SecurityException) {
            LogBus.w("Notification blocked: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- sound & vibration

    private fun playSound(cfg: SoundAction) {
        main.post {
            if (cfg.playSound) try {
                when (cfg.tone) {
                    AlertTone.BEEP -> {
                        val stream = if (cfg.playOnSilent) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
                        val tg = ToneGenerator(stream, 90)
                        tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
                        main.postDelayed({ tg.release() }, 1200)
                    }
                    AlertTone.ALARM, AlertTone.CHIME -> {
                        val type = if (cfg.tone == AlertTone.ALARM) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
                        val uri = RingtoneManager.getDefaultUri(type) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        val ringtone = RingtoneManager.getRingtone(app, uri) ?: return@post
                        ringtone.audioAttributes = AudioAttributes.Builder()
                            .setUsage(if (cfg.playOnSilent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        ringtone.play()
                        // Alarm tones loop; cap them so an alert is a nudge, not a siren.
                        main.postDelayed({ ringtone.stop() }, 3000)
                    }
                }
            } catch (e: Exception) {
                LogBus.w("Alert sound failed: ${e.message}")
            }
            if (cfg.vibrate) vibrate()
        }
    }

    private fun vibrate() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250, 120, 400), -1))
    }

    // ---------------------------------------------------------------- webhook

    private fun postWebhook(alert: Alert, cfg: WebhookAction) {
        scope.launch {
            val started = System.currentTimeMillis()
            try {
                val body = webhookBody(alert, cfg).toString().toByteArray(Charsets.UTF_8)
                val conn = (URL(cfg.url.trim()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("User-Agent", "SiKW00F-Android")
                    if (cfg.authorization.isNotBlank()) setRequestProperty("Authorization", cfg.authorization.trim())
                }
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                conn.disconnect()
                val ms = System.currentTimeMillis() - started
                if (code in 200..299) LogBus.i("Webhook delivered (HTTP $code, $ms ms)")
                else LogBus.event("ALERT", "Webhook returned HTTP $code", LogLevel.WARNING)
            } catch (e: Exception) {
                LogBus.event("ALERT", "Webhook failed: ${e.javaClass.simpleName}: ${e.message}", LogLevel.WARNING)
            }
        }
    }

    private fun webhookBody(alert: Alert, cfg: WebhookAction): JSONObject {
        val headline = if (alert.trigger == AlertTrigger.TEST) alert.trigger.title else "${alert.trigger.title} · ${alert.who}"
        val position = if (alert.lat != null && alert.lon != null) " (%.5f, %.5f)".format(Locale.US, alert.lat, alert.lon) else ""
        val line = "$headline: ${alert.message}$position"
        return when (cfg.format) {
            WebhookFormat.SLACK -> JSONObject().put("text", ":rotating_light: $line")
            WebhookFormat.DISCORD -> JSONObject().put("content", "🚨 $line").put("username", "SiKW00F")
            WebhookFormat.JSON -> JSONObject().apply {
                put("source", "sikw00f")
                put("event", alert.trigger.key)
                put("title", headline)
                put("message", alert.message)
                put("netId", alert.netId)
                alert.label?.let { put("name", it) }
                put("time", isoFormat.format(Date()))
                if (alert.lat != null && alert.lon != null) put("position", JSONObject().put("lat", alert.lat).put("lon", alert.lon))
                DetectionLog.observerPosition()?.let { put("observer", JSONObject().put("lat", it.first).put("lon", it.second)) }
                if (cfg.includeTelemetry) {
                    val t = JSONObject()
                    alert.fields.forEach { (k, v) -> if (v != null) t.put(k, v) }
                    put("telemetry", t)
                }
            }
        }
    }

    private companion object {
        const val CHANNEL_URGENT = "drone_alerts"
        const val CHANNEL_QUIET = "drone_alerts_quiet"
    }
}
