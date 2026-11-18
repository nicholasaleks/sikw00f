package com.sikw00f.android.alerts

import com.sikw00f.android.LogBus
import com.sikw00f.android.LogLevel
import com.sikw00f.android.protocol.DroneIntel
import com.sikw00f.android.protocol.GeoFix
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Streams located drones to TAK (ATAK / WinTAK / iTAK / TAK Server) as
 * Cursor-on-Target XML over UDP: SA multicast for a local mesh, loopback for
 * ATAK on this phone, or unicast to a TAK Server's plain UDP input.
 *
 * Each drone is one CoT track (uid `sikw00f-net<NetID>`) updated as its
 * position arrives; home and the GCS (when transmitted) are linked markers.
 * Markers go stale in TAK on their own when updates stop.
 */
class TakCotSender {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastSent = HashMap<String, Long>()
    private var socket: MulticastSocket? = null
    private var lastError: String? = null

    private val cotTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Sends [intel]'s position (and home / GCS) if it has one, at most every couple of seconds per marker. */
    fun publish(intel: DroneIntel, freqMHz: String?, base: TakSettings, known: KnownDrone? = null) {
        if (!base.enabled) return
        val settings = if (known?.friendly == true) base.copy(affiliation = CotAffiliation.FRIENDLY) else base
        val name = known?.name?.takeIf { it.isNotBlank() }
        val fix = intel.position ?: return
        val now = System.currentTimeMillis()
        val droneUid = "sikw00f-net${intel.netId}"
        val events = buildList {
            if (due(droneUid, now, DRONE_INTERVAL_MS)) add(droneEvent(droneUid, intel, fix, freqMHz, settings, now, name))
            if (settings.includeHomeAndGcs) {
                intel.home?.let { home ->
                    val uid = "$droneUid-home"
                    if (due(uid, now, AUX_INTERVAL_MS)) add(markerEvent(uid, "b-m-p-s-m", "NetID ${intel.netId} HOME", home, droneUid, "Launch point reported by NetID ${intel.netId}", settings, now))
                }
                intel.gcsPosition?.let { gcs ->
                    val uid = "$droneUid-gcs"
                    val type = "a-${settings.affiliation.code}-G"
                    if (due(uid, now, AUX_INTERVAL_MS)) add(markerEvent(uid, type, "NetID ${intel.netId} GCS", gcs, droneUid, "Ground station position transmitted on NetID ${intel.netId}", settings, now))
                }
            }
        }
        if (events.isNotEmpty()) send(events, settings)
    }

    /** One-off marker at the phone's position so the user can confirm TAK is receiving. */
    fun sendTest(lat: Double, lon: Double, settings: TakSettings) {
        val now = System.currentTimeMillis()
        val xml = markerEvent("sikw00f-test", "b-m-p-s-m", "SiKW00F test", GeoFix(lat, lon), null, "Test marker from SiKW00F", settings.copy(staleS = 120), now)
        send(listOf(xml), settings.copy(enabled = true), announce = true)
    }

    fun close() {
        socket?.close()
        socket = null
    }

    private fun due(uid: String, now: Long, interval: Long): Boolean = synchronized(lastSent) {
        if (now - (lastSent[uid] ?: 0L) < interval) return false
        lastSent[uid] = now
        true
    }

    private fun send(events: List<String>, settings: TakSettings, announce: Boolean = false) {
        val host = settings.host.trim()
        if (host.isEmpty()) return
        scope.launch {
            try {
                val address = InetAddress.getByName(host)
                val s = synchronized(this@TakCotSender) {
                    socket ?: MulticastSocket().also {
                        it.timeToLive = 1 // stay on the local network segment
                        socket = it
                    }
                }
                for (xml in events) {
                    val bytes = xml.toByteArray(Charsets.UTF_8)
                    s.send(DatagramPacket(bytes, bytes.size, address, settings.port))
                }
                if (lastError != null || announce) LogBus.i("TAK: sending CoT to $host:${settings.port}")
                lastError = null
            } catch (e: Exception) {
                val msg = "${e.javaClass.simpleName}: ${e.message}"
                if (msg != lastError) LogBus.event("TAK", "CoT send to $host:${settings.port} failed: $msg", LogLevel.WARNING)
                lastError = msg
            }
        }
    }

    private fun droneEvent(uid: String, i: DroneIntel, fix: GeoFix, freqMHz: String?, s: TakSettings, now: Long, name: String?): String {
        val callsign = name ?: ("NetID ${i.netId}" + (i.vehicleType?.takeUnless { it.startsWith("Generic") }?.let { " $it" } ?: ""))
        val remarks = listOfNotNull(
            "SiK telemetry link NetID ${i.netId}",
            freqMHz?.let { "heard on $it MHz" },
            i.autopilot,
            i.firmware,
            i.armed?.let { if (it) "ARMED" else "disarmed" },
            i.flightMode?.let { "mode $it" },
            i.altRelM?.let { "%.0f m AGL".format(Locale.US, it) },
            i.battery.remainingPct?.let { "battery $it%" }
        ).joinToString(" · ")
        val ce = i.hdop?.let { (it * 5).coerceIn(3.0, 100.0) } ?: 15.0
        val track = if (i.headingDeg != null || i.groundSpeed != null) {
            """<track course="${i.headingDeg ?: 0}" speed="${"%.1f".format(Locale.US, i.groundSpeed ?: 0.0)}"/>"""
        } else ""
        return event(
            uid = uid,
            type = "a-${s.affiliation.code}-A-M-F-Q", // MIL-STD-2525 UAV
            lat = fix.lat, lon = fix.lon, hae = i.altMslM, ce = ce,
            detail = """<contact callsign="${esc(callsign)}"/>$track<remarks>${esc(remarks)}</remarks>""",
            staleS = s.staleS, now = now
        )
    }

    private fun markerEvent(uid: String, type: String, callsign: String, at: GeoFix, parent: String?, remarks: String, s: TakSettings, now: Long): String {
        val link = parent?.let { """<link uid="$it" relation="p-p" type="a-${s.affiliation.code}-A-M-F-Q"/>""" } ?: ""
        return event(
            uid = uid, type = type, lat = at.lat, lon = at.lon, hae = at.altM, ce = 20.0,
            detail = """<contact callsign="${esc(callsign)}"/>$link<remarks>${esc(remarks)}</remarks>""",
            staleS = s.staleS * 5, now = now
        )
    }

    private fun event(uid: String, type: String, lat: Double, lon: Double, hae: Double?, ce: Double, detail: String, staleS: Int, now: Long): String {
        val t = cotTime.format(Date(now))
        val stale = cotTime.format(Date(now + staleS.coerceAtLeast(10) * 1000L))
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<event version="2.0" uid="$uid" type="$type" how="m-g" time="$t" start="$t" stale="$stale">""" +
            """<point lat="${"%.7f".format(Locale.US, lat)}" lon="${"%.7f".format(Locale.US, lon)}" """ +
            """hae="${hae?.let { "%.1f".format(Locale.US, it) } ?: "9999999.0"}" ce="${"%.1f".format(Locale.US, ce)}" le="${if (hae != null) "15.0" else "9999999.0"}"/>""" +
            "<detail>$detail</detail></event>"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        const val DRONE_INTERVAL_MS = 2_000L
        const val AUX_INTERVAL_MS = 15_000L
    }
}
