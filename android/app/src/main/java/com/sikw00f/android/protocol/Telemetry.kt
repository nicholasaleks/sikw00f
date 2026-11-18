package com.sikw00f.android.protocol

import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.GpsRawInt

/**
 * GPS fix carried by a MAVLink payload: the autopilot's fused
 * GLOBAL_POSITION_INT, or GPS_RAW_INT with at least a 2D fix. Null otherwise.
 */
fun mavlinkPosition(netId: Int, payload: Any, now: Long = System.currentTimeMillis()): DronePosition? {
    val (lat, lon, alt, source) = when (payload) {
        is GlobalPositionInt -> Quad(payload.lat(), payload.lon(), payload.relativeAlt() / 1000.0, "GLOBAL_POSITION_INT")
        is GpsRawInt -> {
            if (payload.fixType().value() < 2) return null
            Quad(payload.lat(), payload.lon(), null, "GPS_RAW_INT")
        }
        else -> return null
    }
    if (lat == 0 && lon == 0) return null
    val latDeg = lat / 1e7
    val lonDeg = lon / 1e7
    if (latDeg !in -90.0..90.0 || lonDeg !in -180.0..180.0) return null
    return DronePosition(netId, latDeg, lonDeg, alt, now, source)
}

private data class Quad(val lat: Int, val lon: Int, val alt: Double?, val source: String)
