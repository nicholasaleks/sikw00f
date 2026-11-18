package com.sikw00f.android

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class LogLevel { DEBUG, INFO, WARNING, ERROR }

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val message: String,
    /** Set for important detection events (DETECT, GPS, VEHICLE, ARM, COMMAND...); used by the Logs filter. */
    val tag: String? = null
)

/**
 * App log: an in-memory ring buffer the UI observes, mirrored to Logcat and
 * appended to a per-session text file under files/logs/ so nothing is lost
 * when the buffer rolls over or the app closes.
 */
object LogBus {
    private const val MAX_ENTRIES = 2000
    private const val MAX_SESSIONS_KEPT = 30

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    internal val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sikw00f-log").apply { isDaemon = true } }

    @Volatile var verbose: Boolean = false

    var logDir: File? = null
        private set
    var sessionLog: File? = null
        private set
    var sessionId: String = ""
        private set

    /** Opens this session's log files; call once from the Activity. */
    fun init(context: Context) {
        if (sessionLog != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        logDir = dir
        sessionLog = File(dir, "sikw00f-$sessionId.log")
        DetectionLog.open(File(dir, "detections-$sessionId.jsonl"))
        io.execute { pruneOldSessions(dir) }
    }

    /** The current session's files (text log + structured detections), for sharing. */
    fun sessionFiles(): List<File> = listOfNotNull(sessionLog, DetectionLog.file).filter { it.exists() }

    fun d(message: String) {
        if (verbose) push(LogLevel.DEBUG, message, null)
        android.util.Log.d("SiKW00F", message)
    }

    fun i(message: String) {
        push(LogLevel.INFO, message, null)
        android.util.Log.i("SiKW00F", message)
    }

    fun w(message: String) {
        push(LogLevel.WARNING, message, null)
        android.util.Log.w("SiKW00F", message)
    }

    fun e(message: String) {
        push(LogLevel.ERROR, message, null)
        android.util.Log.e("SiKW00F", message)
    }

    /** An important detection event, tagged so it can be filtered and found later. */
    fun event(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        push(level, "[$tag] $message", tag)
        android.util.Log.println(
            when (level) {
                LogLevel.ERROR -> android.util.Log.ERROR
                LogLevel.WARNING -> android.util.Log.WARN
                else -> android.util.Log.INFO
            },
            "SiKW00F", "[$tag] $message"
        )
    }

    fun formatted(entry: LogEntry): String =
        "[${timeFormat.format(Date(entry.timestamp))}] ${entry.level}: ${entry.message}"

    /** Clears the on-screen buffer; the session files keep everything. */
    fun clear() {
        _entries.value = emptyList()
    }

    private fun push(level: LogLevel, message: String, tag: String?) {
        val entry = LogEntry(level = level, message = message, tag = tag)
        val next = (_entries.value + entry)
        _entries.value = if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
        val file = sessionLog ?: return
        val line = "${fileTimeFormat.format(Date(entry.timestamp))} ${level.name.padEnd(7)} $message\n"
        io.execute { runCatching { FileWriter(file, true).use { it.write(line) } } }
    }

    private fun pruneOldSessions(dir: File) {
        val sessions = dir.listFiles { f -> f.name.startsWith("sikw00f-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: return
        sessions.drop(MAX_SESSIONS_KEPT).forEach { old ->
            old.delete()
            File(dir, old.name.replace("sikw00f-", "detections-").replace(".log", ".jsonl")).delete()
        }
    }
}

/**
 * Structured, append-only record of detections (one JSON object per line):
 * new NetIDs with frequency, vehicle intel events, throttled position fixes,
 * locks and per-drone summaries, each stamped with the observer's (phone's)
 * position when known. Meant for after-action analysis or import elsewhere.
 */
object DetectionLog {
    var file: File? = null
        private set

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile private var observer: Triple<Double, Double, Float>? = null

    internal fun open(f: File) {
        file = f
    }

    /** Latest phone position, attached to every record. */
    fun setObserver(lat: Double, lon: Double, accuracyM: Float) {
        observer = Triple(lat, lon, accuracyM)
    }

    fun observerPosition(): Pair<Double, Double>? = observer?.let { it.first to it.second }

    fun record(type: String, fields: Map<String, Any?>) {
        val f = file ?: return
        val now = System.currentTimeMillis()
        val json = JSONObject()
        json.put("time", isoFormat.format(Date(now)))
        json.put("type", type)
        for ((k, v) in fields) {
            if (v == null) continue
            json.put(
                k,
                when (v) {
                    is Collection<*> -> org.json.JSONArray(v)
                    is Map<*, *> -> JSONObject(v.entries.associate { it.key.toString() to it.value })
                    else -> v
                }
            )
        }
        observer?.let { (lat, lon, acc) ->
            json.put("observer", JSONObject().put("lat", lat).put("lon", lon).put("accuracy_m", acc.toDouble()))
        }
        val line = json.toString() + "\n"
        LogBus.io.execute { runCatching { FileWriter(f, true).use { it.write(line) } } }
    }
}
