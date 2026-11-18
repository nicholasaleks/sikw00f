package com.sikw00f.android.sessions

import com.sikw00f.android.LogBus
import com.sikw00f.android.protocol.GeoFix
import com.sikw00f.android.protocol.TrackPoint
import com.sikw00f.android.protocol.distanceM
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/** One drone as reconstructed from a session's detections log. */
data class SessionDrone(
    val netId: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val freqsMHz: Set<String> = emptySet(),
    val packets: Int? = null,
    val mavlinkMessages: Int? = null,
    val vehicleType: String? = null,
    val autopilot: String? = null,
    val firmware: String? = null,
    val armedSeen: Boolean = false,
    val ecc: Boolean = false,
    val rssiPeakDbm: Int? = null,
    val track: List<TrackPoint> = emptyList(),
    val home: GeoFix? = null,
    val events: List<Pair<Long, String>> = emptyList(),
    val maxAltM: Double? = null,
    /** Furthest it was from the phone while tracked. */
    val maxDistanceM: Double? = null,
    val minDistanceM: Double? = null
)

data class Session(
    val id: String,
    val start: Long,
    val end: Long,
    val drones: List<SessionDrone>,
    /** Where the phone was while scanning. */
    val observerTrack: List<TrackPoint>,
    val scans: Int,
    val locks: Int,
    val alerts: Int,
    val detectionsFile: File,
    val logFile: File?
) {
    val isCurrent: Boolean get() = id == LogBus.sessionId
    val positionCount: Int get() = drones.sumOf { it.track.size }
}

/**
 * Reads past scanning sessions back from files/logs/: each session is a
 * `detections-<id>.jsonl` (structured records, see DetectionLog) plus a
 * `sikw00f-<id>.log` text log. Also exports tracks as KML / GPX.
 */
object SessionStore {
    private const val MAX_EVENTS = 60
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun list(): List<Session> {
        val dir = LogBus.logDir ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith("detections-") && f.name.endsWith(".jsonl") }
            .orEmpty()
            .sortedByDescending { it.name }
            .mapNotNull { runCatching { parse(it) }.getOrNull() }
    }

    fun load(id: String): Session? {
        val f = File(LogBus.logDir ?: return null, "detections-$id.jsonl")
        return if (f.exists()) runCatching { parse(f) }.getOrNull() else null
    }

    fun delete(session: Session) {
        session.detectionsFile.delete()
        session.logFile?.delete()
    }

    private class Builder(val netId: Int, var first: Long) {
        var last = first
        val freqs = LinkedHashSet<String>()
        var packets: Int? = null
        var mavlink: Int? = null
        var vehicleType: String? = null
        var autopilot: String? = null
        var firmware: String? = null
        var armed = false
        var ecc = false
        var rssiPeak: Int? = null
        val track = ArrayList<TrackPoint>()
        var home: GeoFix? = null
        val events = ArrayList<Pair<Long, String>>()
        var maxAlt: Double? = null
        var maxDist: Double? = null
        var minDist: Double? = null

        fun build() = SessionDrone(
            netId, first, last, freqs, packets, mavlink, vehicleType, autopilot, firmware, armed, ecc, rssiPeak,
            track, home, events.takeLast(MAX_EVENTS), maxAlt, maxDist, minDist
        )
    }

    private fun parse(file: File): Session {
        val id = file.name.removePrefix("detections-").removeSuffix(".jsonl")
        val drones = LinkedHashMap<Int, Builder>()
        val observer = ArrayList<TrackPoint>()
        var start = Long.MAX_VALUE
        var end = 0L
        var scans = 0
        var locks = 0
        var alerts = 0

        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val j = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val t = runCatching { iso.parse(j.optString("time"))?.time }.getOrNull() ?: return@forEachLine
            start = minOf(start, t)
            end = maxOf(end, t)

            j.optJSONObject("observer")?.let { o ->
                val p = TrackPoint(o.optDouble("lat"), o.optDouble("lon"), null, t)
                val prev = observer.lastOrNull()
                if (prev == null || distanceM(prev.lat, prev.lon, p.lat, p.lon) > 5) observer += p
            }

            when (j.optString("type")) {
                "scan_start" -> scans++
                "lock" -> locks++
                "alert" -> alerts++
            }
            if (!j.has("netId") || j.isNull("netId")) return@forEachLine
            val netId = j.optInt("netId", -1).takeIf { it >= 0 } ?: return@forEachLine
            val d = drones.getOrPut(netId) { Builder(netId, t) }
            d.first = minOf(d.first, t)
            d.last = maxOf(d.last, t)

            when (j.optString("type")) {
                "detection" -> {
                    j.optStringOrNull("freq_mhz")?.let { d.freqs += it }
                    if (j.optBoolean("ecc", false)) d.ecc = true
                    j.optIntOrNull("rssi_dbm")?.let { d.rssiPeak = maxOf(d.rssiPeak ?: it, it) }
                }
                "position" -> {
                    val lat = j.optDouble("lat")
                    val lon = j.optDouble("lon")
                    if (!lat.isNaN() && !lon.isNaN()) {
                        val alt = j.optDoubleOrNull("alt_rel_m")
                        d.track += TrackPoint(lat, lon, alt, t)
                        alt?.let { d.maxAlt = maxOf(d.maxAlt ?: it, it) }
                        j.optJSONObject("observer")?.let { o ->
                            val dist = distanceM(o.optDouble("lat"), o.optDouble("lon"), lat, lon)
                            d.maxDist = maxOf(d.maxDist ?: dist, dist)
                            d.minDist = minOf(d.minDist ?: dist, dist)
                        }
                    }
                    j.optIntOrNull("rssi_dbm")?.let { d.rssiPeak = maxOf(d.rssiPeak ?: it, it) }
                }
                "intel" -> {
                    j.optStringOrNull("text")?.let { d.events += t to it }
                    if (j.optString("kind") == "ARM" && j.optBoolean("armed", false)) d.armed = true
                }
                "summary" -> {
                    j.optIntOrNull("packets")?.let { d.packets = maxOf(d.packets ?: 0, it) }
                    j.optIntOrNull("mavlink_messages")?.let { d.mavlink = maxOf(d.mavlink ?: 0, it) }
                    j.optStringOrNull("vehicle_type")?.let { d.vehicleType = it }
                    j.optStringOrNull("autopilot")?.let { d.autopilot = it }
                    j.optStringOrNull("firmware")?.let { d.firmware = it }
                    j.optJSONArray("freqs_mhz")?.let { arr -> for (k in 0 until arr.length()) d.freqs += arr.getString(k) }
                    j.optIntOrNull("rssi_peak_dbm")?.let { d.rssiPeak = maxOf(d.rssiPeak ?: it, it) }
                    if (j.optBoolean("ecc", false)) d.ecc = true
                    val hl = j.optDoubleOrNull("home_lat")
                    val ho = j.optDoubleOrNull("home_lon")
                    if (hl != null && ho != null) d.home = GeoFix(hl, ho)
                    if (j.optBoolean("armed", false)) d.armed = true
                }
            }
        }

        val logFile = File(file.parentFile, "sikw00f-$id.log").takeIf { it.exists() }
        if (start == Long.MAX_VALUE) start = idToTime(id) ?: file.lastModified()
        if (end == 0L) end = start
        return Session(id, start, end, drones.values.map { it.build() }, observer, scans, locks, alerts, file, logFile)
    }

    private fun idToTime(id: String): Long? =
        runCatching { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(id)?.time }.getOrNull()

    // ---------------------------------------------------------------- export

    /** Writes KML (Google Earth, ATAK, QGIS) for the session; tracks, homes, last fixes and the phone's path. */
    fun exportKml(session: Session, colorFor: (Int) -> Int): File {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""")
        sb.append("<name>SiKW00F session ${esc(session.id)}</name>")
        for (d in session.drones) {
            val argb = colorFor(d.netId)
            val abgr = String.format(Locale.US, "ff%02x%02x%02x", argb and 0xFF, (argb shr 8) and 0xFF, (argb shr 16) and 0xFF)
            sb.append("""<Style id="net${d.netId}"><LineStyle><color>$abgr</color><width>3</width></LineStyle>""")
            sb.append("<IconStyle><color>$abgr</color></IconStyle></Style>")
        }
        sb.append("""<Style id="observer"><LineStyle><color>ffdcc87c</color><width>2</width></LineStyle></Style>""")
        for (d in session.drones) {
            sb.append("<Folder><name>NetID ${d.netId}${d.vehicleType?.let { " · " + esc(it) } ?: ""}</name>")
            sb.append("<description>${esc(droneDescription(d))}</description>")
            if (d.track.size >= 2) {
                sb.append("<Placemark><name>NetID ${d.netId} track</name><styleUrl>#net${d.netId}</styleUrl>")
                sb.append("<LineString><tessellate>1</tessellate><altitudeMode>relativeToGround</altitudeMode><coordinates>")
                d.track.forEach { sb.append(String.format(Locale.US, "%.7f,%.7f,%.1f ", it.lon, it.lat, it.altM ?: 0.0)) }
                sb.append("</coordinates></LineString></Placemark>")
            }
            d.track.lastOrNull()?.let { p ->
                sb.append("<Placemark><name>NetID ${d.netId} last fix</name><styleUrl>#net${d.netId}</styleUrl>")
                sb.append("<TimeStamp><when>${iso.format(Date(p.time))}</when></TimeStamp>")
                sb.append(String.format(Locale.US, "<Point><coordinates>%.7f,%.7f</coordinates></Point></Placemark>", p.lon, p.lat))
            }
            d.home?.let { h ->
                sb.append("<Placemark><name>NetID ${d.netId} HOME</name><styleUrl>#net${d.netId}</styleUrl>")
                sb.append(String.format(Locale.US, "<Point><coordinates>%.7f,%.7f</coordinates></Point></Placemark>", h.lon, h.lat))
            }
            sb.append("</Folder>")
        }
        if (session.observerTrack.size >= 2) {
            sb.append("<Placemark><name>Observer (this phone)</name><styleUrl>#observer</styleUrl><LineString><tessellate>1</tessellate><coordinates>")
            session.observerTrack.forEach { sb.append(String.format(Locale.US, "%.7f,%.7f,0 ", it.lon, it.lat)) }
            sb.append("</coordinates></LineString></Placemark>")
        }
        sb.append("</Document></kml>")
        return writeExport("sikw00f-${session.id}.kml", sb.toString())
    }

    /** Writes GPX 1.1: one track per drone (plus the phone), HOME points as waypoints. */
    fun exportGpx(session: Session): File {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="SiKW00F" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.append("<metadata><name>SiKW00F session ${esc(session.id)}</name><time>${iso.format(Date(session.start))}</time></metadata>")
        for (d in session.drones) {
            d.home?.let { h ->
                sb.append(String.format(Locale.US, """<wpt lat="%.7f" lon="%.7f">""", h.lat, h.lon))
                sb.append("<name>NetID ${d.netId} HOME</name></wpt>")
            }
        }
        for (d in session.drones.filter { it.track.isNotEmpty() }) {
            sb.append("<trk><name>NetID ${d.netId}</name><desc>${esc(droneDescription(d))}</desc><trkseg>")
            d.track.forEach { p ->
                sb.append(String.format(Locale.US, """<trkpt lat="%.7f" lon="%.7f">""", p.lat, p.lon))
                p.altM?.let { sb.append(String.format(Locale.US, "<ele>%.1f</ele>", it)) }
                sb.append("<time>${iso.format(Date(p.time))}</time></trkpt>")
            }
            sb.append("</trkseg></trk>")
        }
        if (session.observerTrack.isNotEmpty()) {
            sb.append("<trk><name>Observer (this phone)</name><trkseg>")
            session.observerTrack.forEach { p ->
                sb.append(String.format(Locale.US, """<trkpt lat="%.7f" lon="%.7f"><time>%s</time></trkpt>""", p.lat, p.lon, iso.format(Date(p.time))))
            }
            sb.append("</trkseg></trk>")
        }
        sb.append("</gpx>")
        return writeExport("sikw00f-${session.id}.gpx", sb.toString())
    }

    private fun droneDescription(d: SessionDrone): String = listOfNotNull(
        d.vehicleType, d.autopilot, d.firmware,
        d.freqsMHz.takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = " MHz"),
        d.packets?.let { "$it packets" },
        d.rssiPeakDbm?.let { "peak $it dBm" },
        if (d.armedSeen) "armed during session" else null
    ).joinToString(" · ")

    private fun writeExport(name: String, content: String): File {
        val dir = File(LogBus.logDir, "exports").apply { mkdirs() }
        return File(dir, name).apply { writeText(content) }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
}
