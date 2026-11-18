package com.sikw00f.android.protocol

/** Kotlin equivalent of the per-NetID dict built up in core/scan.py's discovered_drones. */
data class DiscoveredDrone(
    val netId: Int,
    val packetsCount: Int = 0,
    val lastChannel: Int? = null,
    /** Physical channel index -> packets heard on it. */
    val channelHits: Map<Int, Int> = emptyMap(),
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long? = null,
    val params: Map<String, String> = emptyMap(),
    /** MAVLink messages decoded from this NetID's payload. */
    val mavlinkCount: Int = 0,
    /** Signal strength of the last packet, in dBm (needs the RSSI firmware build). */
    val rssiDbm: Int? = null,
    /** Smoothed signal strength: fast (~3 packets) and slow (~30 packets) averages, for trend. */
    val rssiFast: Double? = null,
    val rssiSlow: Double? = null,
    val rssiPeakDbm: Int? = null,
    /** Heard while this radio had ECC (golay) on: such networks are only audible with ECC on. */
    val ecc: Boolean = false,
    /** User-data bytes heard from this NetID (header lines excluded). */
    val payloadBytes: Long = 0
) {
    /** "closer" / "farther" when the short-term signal clearly departs from the long-term one. */
    val rssiTrend: Int
        get() {
            val f = rssiFast ?: return 0
            val s = rssiSlow ?: return 0
            return when {
                f - s > 3 -> 1
                s - f > 3 -> -1
                else -> 0
            }
        }

    /**
     * Radios linked but carrying no data: SiK pairs keep exchanging empty
     * timing packets when nothing is sent through them (flight controller off
     * or not outputting telemetry, GCS disconnected).
     */
    val linkIdle: Boolean get() = packetsCount >= 25 && mavlinkCount == 0 && payloadBytes < packetsCount * 2L

    /** Data is flowing but none of it decodes as MAVLink: encrypted, or another protocol. */
    val noMavlink: Boolean get() = packetsCount >= 25 && mavlinkCount == 0 && !linkIdle

    val minFreq: String? get() = params["MIN_FREQ"]
    val maxFreq: String? get() = params["MAX_FREQ"]
    val airSpeed: String? get() = params["AIR_SPEED"]
    val mavlink: String? get() = params["MAVLINK"]
    val lbtRssi: String? get() = params["LBT_RSSI"]
    val numChannels: String? get() = params["NUM_CHANNELS"]
}


/** Result of AutotuneEngine.autotuneToClone(): raw RTI5 lines, the parsed
 * parameters, and which keys were actually applied to this radio. */
data class AutotuneResult(
    val dumpLines: List<String>,
    val extractedParams: Map<String, String>,
    val appliedKeys: List<String>
)

/** Last GPS fix a drone reported in its own telemetry. [altitudeM] is relative to home when known. */
data class DronePosition(
    val netId: Int,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val timestamp: Long,
    /** MAVLink message the fix came from (GLOBAL_POSITION_INT or GPS_RAW_INT). */
    val source: String = ""
)

enum class ScanMode { PASSIVE, DEEP }

sealed class ScanState {
    data object Idle : ScanState()
    data class Running(val mode: ScanMode, val drones: Map<Int, DiscoveredDrone>) : ScanState()
    data class Stopped(val mode: ScanMode, val drones: Map<Int, DiscoveredDrone>, val reason: String) : ScanState()
}
