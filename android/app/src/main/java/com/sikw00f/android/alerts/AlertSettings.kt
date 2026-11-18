package com.sikw00f.android.alerts

import android.content.Context
import org.json.JSONObject

/** What happens when a scan detects something. Persisted as one JSON blob. */
data class AlertSettings(
    val triggers: Triggers = Triggers(),
    val notification: NotificationAction = NotificationAction(),
    val sound: SoundAction = SoundAction(),
    val webhook: WebhookAction = WebhookAction(),
    val tak: TakSettings = TakSettings()
) {
    val anyActionEnabled: Boolean get() = notification.enabled || sound.enabled || webhook.enabled
}

/** Which detection events fire the enabled actions (TAK streams positions regardless). */
data class Triggers(
    val newDrone: Boolean = true,
    val armed: Boolean = false,
    val firstGps: Boolean = false,
    val critical: Boolean = false,
    val proximity: Boolean = false,
    val proximityM: Int = 500,
    /** Signal stronger than [strongSignalDbm]: a closeness warning that works without GPS. */
    val strongSignal: Boolean = false,
    val strongSignalDbm: Int = -70,
    /** Minimum gap between repeats of the same trigger for the same NetID. */
    val cooldownS: Int = 60
)

data class NotificationAction(
    val enabled: Boolean = false,
    /** Pops over other apps (high-importance channel) instead of arriving silently in the shade. */
    val headsUp: Boolean = true,
    val includePosition: Boolean = true
)

enum class AlertTone(val label: String) {
    ALARM("Alarm"),
    CHIME("Chime"),
    BEEP("Beeps")
}

data class SoundAction(
    val enabled: Boolean = false,
    /** Off = vibrate-only alerts. */
    val playSound: Boolean = true,
    val tone: AlertTone = AlertTone.BEEP,
    val vibrate: Boolean = true,
    /** Plays on the alarm stream, which rings even when the phone is on silent / vibrate. */
    val playOnSilent: Boolean = false
)

enum class WebhookFormat(val label: String) {
    JSON("JSON"),
    SLACK("Slack"),
    DISCORD("Discord")
}

data class WebhookAction(
    val enabled: Boolean = false,
    val url: String = "",
    val format: WebhookFormat = WebhookFormat.JSON,
    /** Sent verbatim as the Authorization header when set, e.g. "Bearer abc123". */
    val authorization: String = "",
    val includeTelemetry: Boolean = true
)

enum class TakTransport(val label: String, val defaultHost: String, val defaultPort: Int) {
    MULTICAST("Mesh", "239.2.3.1", 6969),
    LOCAL("This phone", "127.0.0.1", 4242),
    UNICAST("Server", "", 8087)
}

enum class CotAffiliation(val code: String, val label: String) {
    UNKNOWN("u", "Unknown"),
    SUSPECT("s", "Suspect"),
    HOSTILE("h", "Hostile"),
    NEUTRAL("n", "Neutral"),
    /** Used for Known drones marked friendly; not offered as a global choice. */
    FRIENDLY("f", "Friendly")
}

data class TakSettings(
    val enabled: Boolean = false,
    val transport: TakTransport = TakTransport.MULTICAST,
    val host: String = TakTransport.MULTICAST.defaultHost,
    val port: Int = TakTransport.MULTICAST.defaultPort,
    val affiliation: CotAffiliation = CotAffiliation.UNKNOWN,
    /** How long a marker stays live in TAK without a fresh update. */
    val staleS: Int = 60,
    val includeHomeAndGcs: Boolean = true
)

/** SharedPreferences-backed store; tolerant of missing/old fields so upgrades keep settings. */
class AlertSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("alert_settings", Context.MODE_PRIVATE)

    fun load(): AlertSettings = runCatching {
        prefs.getString(KEY, null)?.let { fromJson(JSONObject(it)) }
    }.getOrNull() ?: AlertSettings()

    fun save(settings: AlertSettings) {
        prefs.edit().putString(KEY, toJson(settings).toString()).apply()
    }

    private companion object {
        const val KEY = "settings_v1"
        /** 2: the arm alert stopped being on by default. */
        const val VERSION = 2

        fun toJson(s: AlertSettings) = JSONObject()
            .put("version", VERSION)
            .put("triggers", JSONObject()
                .put("newDrone", s.triggers.newDrone)
                .put("armed", s.triggers.armed)
                .put("firstGps", s.triggers.firstGps)
                .put("critical", s.triggers.critical)
                .put("proximity", s.triggers.proximity)
                .put("proximityM", s.triggers.proximityM)
                .put("strongSignal", s.triggers.strongSignal)
                .put("strongSignalDbm", s.triggers.strongSignalDbm)
                .put("cooldownS", s.triggers.cooldownS))
            .put("notification", JSONObject()
                .put("enabled", s.notification.enabled)
                .put("headsUp", s.notification.headsUp)
                .put("includePosition", s.notification.includePosition))
            .put("sound", JSONObject()
                .put("enabled", s.sound.enabled)
                .put("playSound", s.sound.playSound)
                .put("tone", s.sound.tone.name)
                .put("vibrate", s.sound.vibrate)
                .put("playOnSilent", s.sound.playOnSilent))
            .put("webhook", JSONObject()
                .put("enabled", s.webhook.enabled)
                .put("url", s.webhook.url)
                .put("format", s.webhook.format.name)
                .put("authorization", s.webhook.authorization)
                .put("includeTelemetry", s.webhook.includeTelemetry))
            .put("tak", JSONObject()
                .put("enabled", s.tak.enabled)
                .put("transport", s.tak.transport.name)
                .put("host", s.tak.host)
                .put("port", s.tak.port)
                .put("affiliation", s.tak.affiliation.name)
                .put("staleS", s.tak.staleS)
                .put("includeHomeAndGcs", s.tak.includeHomeAndGcs))

        fun fromJson(j: JSONObject): AlertSettings {
            val d = AlertSettings()
            val t = j.optJSONObject("triggers") ?: JSONObject()
            val n = j.optJSONObject("notification") ?: JSONObject()
            val s = j.optJSONObject("sound") ?: JSONObject()
            val w = j.optJSONObject("webhook") ?: JSONObject()
            val k = j.optJSONObject("tak") ?: JSONObject()
            return AlertSettings(
                triggers = Triggers(
                    newDrone = t.optBoolean("newDrone", d.triggers.newDrone),
                    // Settings saved before v2 carried the old "on" default; reset it once.
                    armed = if (j.optInt("version", 1) < 2) false else t.optBoolean("armed", d.triggers.armed),
                    firstGps = t.optBoolean("firstGps", d.triggers.firstGps),
                    critical = t.optBoolean("critical", d.triggers.critical),
                    proximity = t.optBoolean("proximity", d.triggers.proximity),
                    proximityM = t.optInt("proximityM", d.triggers.proximityM),
                    strongSignal = t.optBoolean("strongSignal", d.triggers.strongSignal),
                    strongSignalDbm = t.optInt("strongSignalDbm", d.triggers.strongSignalDbm),
                    cooldownS = t.optInt("cooldownS", d.triggers.cooldownS)
                ),
                notification = NotificationAction(
                    enabled = n.optBoolean("enabled", d.notification.enabled),
                    headsUp = n.optBoolean("headsUp", d.notification.headsUp),
                    includePosition = n.optBoolean("includePosition", d.notification.includePosition)
                ),
                sound = SoundAction(
                    enabled = s.optBoolean("enabled", d.sound.enabled),
                    playSound = s.optBoolean("playSound", d.sound.playSound),
                    tone = enumOr(s.optString("tone"), d.sound.tone),
                    vibrate = s.optBoolean("vibrate", d.sound.vibrate),
                    playOnSilent = s.optBoolean("playOnSilent", d.sound.playOnSilent)
                ),
                webhook = WebhookAction(
                    enabled = w.optBoolean("enabled", d.webhook.enabled),
                    url = w.optString("url", d.webhook.url),
                    format = enumOr(w.optString("format"), d.webhook.format),
                    authorization = w.optString("authorization", d.webhook.authorization),
                    includeTelemetry = w.optBoolean("includeTelemetry", d.webhook.includeTelemetry)
                ),
                tak = TakSettings(
                    enabled = k.optBoolean("enabled", d.tak.enabled),
                    transport = enumOr(k.optString("transport"), d.tak.transport),
                    host = k.optString("host", d.tak.host),
                    port = k.optInt("port", d.tak.port),
                    affiliation = enumOr(k.optString("affiliation"), d.tak.affiliation),
                    staleS = k.optInt("staleS", d.tak.staleS),
                    includeHomeAndGcs = k.optBoolean("includeHomeAndGcs", d.tak.includeHomeAndGcs)
                )
            )
        }

        inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}
