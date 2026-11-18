package com.sikw00f.android.protocol

import io.dronefleet.mavlink.ardupilotmega.Battery2
import io.dronefleet.mavlink.ardupilotmega.CameraFeedback
import io.dronefleet.mavlink.ardupilotmega.EkfStatusReport
import io.dronefleet.mavlink.ardupilotmega.Hwstatus
import io.dronefleet.mavlink.ardupilotmega.Simstate
import io.dronefleet.mavlink.ardupilotmega.Wind
import io.dronefleet.mavlink.common.AdsbVehicle
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.AutopilotVersion
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.CommandAck
import io.dronefleet.mavlink.common.CommandInt
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.ExtendedSysState
import io.dronefleet.mavlink.common.FenceStatus
import io.dronefleet.mavlink.common.FollowTarget
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.GpsRawInt
import io.dronefleet.mavlink.common.HomePosition
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.MissionClearAll
import io.dronefleet.mavlink.common.MissionCount
import io.dronefleet.mavlink.common.MissionCurrent
import io.dronefleet.mavlink.common.MissionItem
import io.dronefleet.mavlink.common.MissionItemInt
import io.dronefleet.mavlink.common.MissionRequestList
import io.dronefleet.mavlink.common.NavControllerOutput
import io.dronefleet.mavlink.common.ParamRequestList
import io.dronefleet.mavlink.common.ParamSet
import io.dronefleet.mavlink.common.ParamValue
import io.dronefleet.mavlink.common.RcChannels
import io.dronefleet.mavlink.common.ServoOutputRaw
import io.dronefleet.mavlink.common.SetMode
import io.dronefleet.mavlink.common.Statustext
import io.dronefleet.mavlink.common.SysStatus
import io.dronefleet.mavlink.common.SystemTime
import io.dronefleet.mavlink.common.VfrHud
import io.dronefleet.mavlink.common.Vibration
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavModeFlag
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------

data class IntelEvent(
    val time: Long,
    val kind: Kind,
    val text: String,
    val severity: Severity = Severity.INFO
) {
    enum class Kind { VEHICLE, GCS, ARM, MODE, FLIGHT, STATUS, COMMAND, MISSION, HOME, FENCE, BATTERY, GPS, PARAM, SENSOR, PAYLOAD }
    enum class Severity { INFO, NOTICE, WARNING, CRITICAL }
}

data class GeoFix(val lat: Double, val lon: Double, val altM: Double? = null)
data class TrackPoint(val lat: Double, val lon: Double, val altM: Double?, val time: Long)
data class Waypoint(val seq: Int, val command: String, val lat: Double?, val lon: Double?, val altM: Float)

data class BatteryInfo(
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val remainingPct: Int? = null,
    val consumedMah: Int? = null,
    val cellsV: List<Double> = emptyList(),
    val tempC: Double? = null,
    val timeRemainingS: Int? = null,
    val battery2V: Double? = null,
    val battery2A: Double? = null
)

/**
 * Everything learned about one NetID from its MAVLink traffic, kept per role:
 * vehicle telemetry only counts when it comes from the vehicle's own system
 * id, ground-station traffic (heartbeat type GCS, usually sysid 255) is
 * tracked separately and its commands go into the event log.
 */
data class DroneIntel(
    val netId: Int,
    val firstSeen: Long? = null,
    val lastMessage: Long? = null,
    val messageCounts: Map<String, Int> = emptyMap(),

    // Identity / state (HEARTBEAT, STATUSTEXT, AUTOPILOT_VERSION, EXTENDED_SYS_STATE)
    val vehicleSysId: Int? = null,
    val vehicleCompId: Int? = null,
    val vehicleType: String? = null,
    val vehicleTypeId: Int? = null,
    val autopilot: String? = null,
    val autopilotId: Int? = null,
    val armed: Boolean? = null,
    val flightMode: String? = null,
    val systemState: String? = null,
    val landedState: String? = null,
    val vtolState: String? = null,
    val lastHeartbeat: Long? = null,
    val firmware: String? = null,
    val frame: String? = null,
    val boardUid: String? = null,
    val simulated: Boolean = false,

    // Power & health (SYS_STATUS, BATTERY_STATUS, BATTERY2, HWSTATUS, EKF, VIBRATION)
    val battery: BatteryInfo = BatteryInfo(),
    /** True once a battery-bearing message arrived, even if every field in it was "unknown". */
    val batteryMessageSeen: Boolean = false,
    val cpuLoadPct: Double? = null,
    val commDropPct: Double? = null,
    val unhealthySensors: List<String> = emptyList(),
    val sensorsPresent: List<String> = emptyList(),
    val ekfWorstVariance: Float? = null,
    val vibration: List<Float>? = null,
    val clipping: Long? = null,
    val boardVccV: Double? = null,

    // Position & motion
    val position: GeoFix? = null,
    val positionTime: Long? = null,
    val positionSource: String? = null,
    val altRelM: Double? = null,
    val altMslM: Double? = null,
    val headingDeg: Int? = null,
    val groundSpeed: Double? = null,
    val airSpeed: Double? = null,
    val climbRate: Double? = null,
    val throttlePct: Int? = null,
    val rollDeg: Double? = null,
    val pitchDeg: Double? = null,
    val track: List<TrackPoint> = emptyList(),

    // GPS quality
    val gpsFix: String? = null,
    val gpsFixType: Int? = null,
    val satellites: Int? = null,
    val hdop: Double? = null,
    val vdop: Double? = null,

    // Home / mission / fence
    val home: GeoFix? = null,
    val missionSeq: Int? = null,
    val missionTotal: Int? = null,
    val wpDistM: Int? = null,
    val targetBearing: Int? = null,
    val altErrorM: Float? = null,
    val xtrackErrorM: Float? = null,
    val missionExpected: Int? = null,
    val mission: Map<Int, Waypoint> = emptyMap(),
    val fenceBreached: Boolean? = null,
    val fenceBreachType: String? = null,
    val fenceBreachCount: Int? = null,

    // Environment / payload / pilot
    val windFromDeg: Float? = null,
    val windSpeed: Float? = null,
    val rcRssiPct: Int? = null,
    val rcChannelCount: Int? = null,
    val rcInputs: List<Int> = emptyList(),
    val motorOutputs: List<Int> = emptyList(),
    val gpsTimeUtcMs: Long? = null,
    val uptimeS: Long? = null,
    val photosTaken: Int = 0,
    val adsbContacts: Map<Long, String> = emptyMap(),

    // Parameters (PARAM_VALUE, heard when a GCS downloads them)
    val params: Map<String, Float> = emptyMap(),
    val paramTotal: Int? = null,

    // Ground station
    val gcsSysIds: Set<Int> = emptySet(),
    val gcsLastSeen: Long? = null,
    /** Only known when the GCS itself transmits a position (e.g. QGC Follow Me); most never do. */
    val gcsPosition: GeoFix? = null,
    val gcsPositionTime: Long? = null,

    val events: List<IntelEvent> = emptyList()
) {
    val missionWaypoints: List<Waypoint> get() = mission.values.sortedBy { it.seq }
}

/** Result of folding one message in: the new state plus any events worth logging. */
data class IntelUpdate(val intel: DroneIntel, val newEvents: List<IntelEvent>)

/** Parameters worth surfacing on the detail page when a GCS download is heard. */
val KEY_PARAMS: Map<String, String> = linkedMapOf(
    "FRAME_CLASS" to "Frame class",
    "FRAME_TYPE" to "Frame type",
    "SYSID_THISMAV" to "Vehicle sysid",
    "BATT_CAPACITY" to "Battery capacity (mAh)",
    "BATT_LOW_VOLT" to "Battery low (V)",
    "BATT_FS_LOW_ACT" to "Low-battery action",
    "FS_THR_ENABLE" to "RC-loss failsafe",
    "FS_GCS_ENABLE" to "GCS-loss failsafe",
    "RTL_ALT" to "RTL altitude (cm)",
    "WPNAV_SPEED" to "Waypoint speed (cm/s)",
    "FENCE_ENABLE" to "Fence enabled",
    "FENCE_ALT_MAX" to "Fence max alt (m)",
    "FENCE_RADIUS" to "Fence radius (m)",
    "ARMING_CHECK" to "Arming checks",
    "SERIAL1_PROTOCOL" to "Telem1 protocol",
    "BRD_SAFETYENABLE" to "Safety switch"
)

// ---------------------------------------------------------------------------
// Reducer
// ---------------------------------------------------------------------------

private const val MAX_EVENTS = 300
private const val MAX_TRACK = 2000
private const val TRACK_MIN_INTERVAL_MS = 1_500L
private const val TRACK_MIN_MOVE_M = 3.0
private const val FUSED_FIX_PRIORITY_MS = 5_000L
private const val STATUSTEXT_DEDUPE_MS = 10_000L

private const val MAV_TYPE_GCS = 6
private const val MAV_COMP_ID_AUTOPILOT1 = 1
private const val MAV_AUTOPILOT_INVALID = 8
private const val MAV_AUTOPILOT_ARDUPILOT = 3
private const val MAV_AUTOPILOT_PX4 = 12

/** Heartbeat types that are peripherals or infrastructure, not the vehicle itself. */
private val NON_VEHICLE_TYPES = setOf(5, 6, 18, 26, 27, 30, 31, 32, 33, 34, 36, 37, 38, 39, 40, 41, 42)

/** Commands a GCS sends constantly while connecting; counted, not logged as operator actions. */
private val HOUSEKEEPING_COMMANDS = setOf(
    MavCmd.MAV_CMD_REQUEST_MESSAGE, MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL,
    MavCmd.MAV_CMD_REQUEST_AUTOPILOT_CAPABILITIES, MavCmd.MAV_CMD_REQUEST_PROTOCOL_VERSION,
    MavCmd.MAV_CMD_GET_HOME_POSITION, MavCmd.MAV_CMD_REQUEST_CAMERA_INFORMATION,
    MavCmd.MAV_CMD_REQUEST_CAMERA_SETTINGS, MavCmd.MAV_CMD_REQUEST_STORAGE_INFORMATION,
    MavCmd.MAV_CMD_REQUEST_CAMERA_CAPTURE_STATUS, MavCmd.MAV_CMD_REQUEST_FLIGHT_INFORMATION,
    MavCmd.MAV_CMD_GET_MESSAGE_INTERVAL
)

private val FIRMWARE_PATTERN = Regex("^(ArduCopter|ArduPlane|ArduRover|ArduSub|Blimp|AntennaTracker|ArduHeli|PX4)\\b.*", RegexOption.IGNORE_CASE)
private val FRAME_PATTERN = Regex("^Frame:\\s*(.+)", RegexOption.IGNORE_CASE)

/** Folds one decoded MAVLink message from [sysId] into this NetID's intel. */
fun DroneIntel.update(sysId: Int, compId: Int, payload: Any, now: Long = System.currentTimeMillis()): IntelUpdate {
    val name = payload.javaClass.simpleName
    val b = IntelBuilder(
        copy(
            firstSeen = firstSeen ?: now,
            lastMessage = now,
            messageCounts = messageCounts + (name to ((messageCounts[name] ?: 0) + 1))
        ),
        now
    )

    if (payload is Heartbeat) {
        b.heartbeat(sysId, compId, payload)
        return b.result()
    }

    val fromGcs = sysId in b.i.gcsSysIds || (sysId == 255 && b.i.vehicleSysId != 255)
    val fromVehicle = !fromGcs && (b.i.vehicleSysId?.let { it == sysId } ?: true)

    when (payload) {
        // Either direction: a mission upload (GCS -> vehicle) or download (vehicle -> GCS).
        is MissionCount -> b.missionCount(payload, fromGcs)
        is MissionItemInt -> b.missionItem(payload.seq(), payload.missionType().value(), payload.command(), payload.x() / 1e7, payload.y() / 1e7, payload.z())
        is MissionItem -> b.missionItem(payload.seq(), payload.missionType().value(), payload.command(), payload.x().toDouble(), payload.y().toDouble(), payload.z())

        // Operator -> vehicle
        is CommandLong -> if (!fromVehicle) b.command(payload.command(), payload.param1(), payload.param2(), payload.param7(), null, null)
        is CommandInt -> if (!fromVehicle) b.command(payload.command(), payload.param1(), payload.param2(), payload.z(), payload.x() / 1e7, payload.y() / 1e7)
        is SetMode -> if (!fromVehicle) b.event(IntelEvent.Kind.COMMAND, "Operator requested mode ${b.modeName(payload.customMode())}", IntelEvent.Severity.NOTICE)
        is ParamSet -> if (!fromVehicle) b.event(IntelEvent.Kind.PARAM, "Operator set ${payload.paramId()} = ${fmtParam(payload.paramValue())}", IntelEvent.Severity.NOTICE)
        is ParamRequestList -> if (!fromVehicle) b.event(IntelEvent.Kind.PARAM, "Operator requested the full parameter list")
        is MissionRequestList -> if (!fromVehicle) b.event(IntelEvent.Kind.MISSION, "Operator requested the mission from the vehicle")
        is MissionClearAll -> if (!fromVehicle) b.event(IntelEvent.Kind.MISSION, "Operator cleared the mission", IntelEvent.Severity.NOTICE)

        // A GCS position is only on air when the GCS sends one (Follow Me / FOLLOW_TARGET).
        is FollowTarget -> if (!fromVehicle) b.gcsPosition(payload.lat() / 1e7, payload.lon() / 1e7, payload.alt().toDouble())
        is GlobalPositionInt -> if (fromGcs) b.gcsPosition(payload.lat() / 1e7, payload.lon() / 1e7, payload.alt() / 1000.0)
            else if (fromVehicle) b.vehicleMessage(payload)

        else -> if (fromVehicle) b.vehicleMessage(payload)
    }
    return b.result()
}

private class IntelBuilder(var i: DroneIntel, val now: Long) {
    val events = mutableListOf<IntelEvent>()

    fun event(kind: IntelEvent.Kind, text: String, severity: IntelEvent.Severity = IntelEvent.Severity.INFO) {
        events += IntelEvent(now, kind, text, severity)
    }

    fun result(): IntelUpdate {
        if (events.isEmpty()) return IntelUpdate(i, events)
        val merged = (i.events + events).let { if (it.size > MAX_EVENTS) it.takeLast(MAX_EVENTS) else it }
        return IntelUpdate(i.copy(events = merged), events)
    }

    // ---- identity ------------------------------------------------------

    fun heartbeat(sysId: Int, compId: Int, hb: Heartbeat) {
        val type = hb.type().value()
        val autopilot = hb.autopilot().value()
        if (type == MAV_TYPE_GCS) {
            if (sysId !in i.gcsSysIds) {
                event(IntelEvent.Kind.GCS, "Ground station connected (sysid $sysId)", IntelEvent.Severity.NOTICE)
            }
            i = i.copy(gcsSysIds = i.gcsSysIds + sysId, gcsLastSeen = now)
            return
        }
        if (autopilot == MAV_AUTOPILOT_INVALID || type in NON_VEHICLE_TYPES) return // camera, gimbal, companion...
        if (i.vehicleSysId != null && i.vehicleSysId != sysId) return // a second vehicle on this NetID; keep the first
        // The flight controller is component 1; once it has spoken, other components of
        // the same system (which may report a generic type) don't override its identity.
        if (i.vehicleCompId == MAV_COMP_ID_AUTOPILOT1 && compId != MAV_COMP_ID_AUTOPILOT1) return

        val typeName = if (type == 0) "Generic (airframe type not set)" else prettyEnum(hb.type().entry(), "MAV_TYPE_") ?: "Type $type"
        val autopilotName = when (autopilot) {
            MAV_AUTOPILOT_ARDUPILOT -> "ArduPilot"
            MAV_AUTOPILOT_PX4 -> "PX4"
            else -> prettyEnum(hb.autopilot().entry(), "MAV_AUTOPILOT_") ?: "Autopilot $autopilot"
        }
        val armed = hb.baseMode().flagsEnabled(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED)
        i = i.copy(vehicleTypeId = type, autopilotId = autopilot)
        val mode = modeName(hb.customMode())
        val state = prettyEnum(hb.systemStatus().entry(), "MAV_STATE_")

        if (i.vehicleSysId == null) {
            event(
                IntelEvent.Kind.VEHICLE,
                "Vehicle identified: $typeName running $autopilotName (sysid $sysId), ${if (armed) "ARMED" else "disarmed"}, mode $mode",
                IntelEvent.Severity.NOTICE
            )
        } else {
            if (i.armed != null && i.armed != armed) {
                event(IntelEvent.Kind.ARM, if (armed) "Vehicle ARMED" else "Vehicle disarmed", if (armed) IntelEvent.Severity.WARNING else IntelEvent.Severity.NOTICE)
            }
            if (i.flightMode != null && i.flightMode != mode) {
                event(IntelEvent.Kind.MODE, "Mode changed: ${i.flightMode} -> $mode", IntelEvent.Severity.NOTICE)
            }
        }
        if (state != i.systemState && (state == "Critical" || state == "Emergency")) {
            event(IntelEvent.Kind.VEHICLE, "Vehicle state: $state", IntelEvent.Severity.CRITICAL)
        }
        i = i.copy(
            vehicleSysId = sysId, vehicleCompId = compId, vehicleType = typeName, autopilot = autopilotName,
            armed = armed, flightMode = mode, systemState = state, lastHeartbeat = now
        )
    }

    fun modeName(customMode: Long): String = flightModeName(i.autopilotId, i.vehicleTypeId, customMode)

    fun gcsPosition(lat: Double, lon: Double, alt: Double?) {
        if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        if (i.gcsPosition == null) {
            event(IntelEvent.Kind.GCS, "Ground station position reported: %.6f, %.6f".format(Locale.US, lat, lon), IntelEvent.Severity.WARNING)
        }
        i = i.copy(gcsPosition = GeoFix(lat, lon, alt), gcsPositionTime = now)
    }

    // ---- vehicle telemetry ---------------------------------------------

    fun vehicleMessage(p: Any) {
        when (p) {
            is SysStatus -> sysStatus(p)
            is BatteryStatus -> batteryStatus(p)
            is Battery2 -> i = i.copy(battery = i.battery.copy(battery2V = mv(p.voltage()), battery2A = ca(p.currentBattery())))
            is ExtendedSysState -> extendedSysState(p)
            is Statustext -> statusText(p)
            is GlobalPositionInt -> globalPosition(p)
            is GpsRawInt -> gpsRaw(p)
            is VfrHud -> i = i.copy(
                airSpeed = p.airspeed().toDouble(), groundSpeed = p.groundspeed().toDouble(),
                headingDeg = p.heading(), throttlePct = p.throttle(), climbRate = p.climb().toDouble(),
                altMslM = p.alt().toDouble()
            )
            is Attitude -> i = i.copy(rollDeg = Math.toDegrees(p.roll().toDouble()), pitchDeg = Math.toDegrees(p.pitch().toDouble()))
            is HomePosition -> home(p)
            is MissionCurrent -> missionCurrent(p)
            is NavControllerOutput -> i = i.copy(
                wpDistM = p.wpDist(), targetBearing = p.targetBearing(),
                altErrorM = p.altError(), xtrackErrorM = p.xtrackError()
            )
            is FenceStatus -> fence(p)
            is EkfStatusReport -> ekf(p)
            is Vibration -> vibration(p)
            is Wind -> i = i.copy(windFromDeg = p.direction(), windSpeed = p.speed())
            is Hwstatus -> i = i.copy(boardVccV = p.vcc() / 1000.0)
            is RcChannels -> i = i.copy(
                rcChannelCount = p.chancount(),
                rcRssiPct = p.rssi().takeIf { it != 255 }?.let { it * 100 / 254 },
                rcInputs = listOf(p.chan1Raw(), p.chan2Raw(), p.chan3Raw(), p.chan4Raw(), p.chan5Raw(), p.chan6Raw(), p.chan7Raw(), p.chan8Raw())
                    .take(p.chancount().coerceIn(0, 8))
            )
            is ServoOutputRaw -> if (p.port() == 0) i = i.copy(
                motorOutputs = listOf(p.servo1Raw(), p.servo2Raw(), p.servo3Raw(), p.servo4Raw(), p.servo5Raw(), p.servo6Raw(), p.servo7Raw(), p.servo8Raw())
                    .filter { it > 0 }
            )
            is SystemTime -> i = i.copy(
                gpsTimeUtcMs = p.timeUnixUsec().toLong().takeIf { it > 0 }?.div(1000),
                uptimeS = p.timeBootMs() / 1000
            )
            is ParamValue -> paramValue(p)
            is CommandAck -> commandAck(p)
            is Simstate -> if (!i.simulated) {
                i = i.copy(simulated = true)
                event(IntelEvent.Kind.VEHICLE, "SIMSTATE seen: this is a simulated vehicle (ArduPilot SITL)", IntelEvent.Severity.WARNING)
            }
            is CameraFeedback -> {
                val n = i.photosTaken + 1
                i = i.copy(photosTaken = n)
                if (n == 1 || n % 25 == 0) event(IntelEvent.Kind.PAYLOAD, "Camera trigger #$n at %.6f, %.6f".format(Locale.US, p.lat() / 1e7, p.lng() / 1e7))
            }
            is AdsbVehicle -> {
                val callsign = p.callsign().trim().ifEmpty { "ICAO %06X".format(p.icaoAddress()) }
                if (p.icaoAddress() !in i.adsbContacts) event(IntelEvent.Kind.PAYLOAD, "ADS-B traffic reported by vehicle: $callsign")
                i = i.copy(adsbContacts = i.adsbContacts + (p.icaoAddress() to callsign))
            }
            is AutopilotVersion -> autopilotVersion(p)
        }
    }

    private fun sysStatus(p: SysStatus) {
        val present = p.onboardControlSensorsPresent().value()
        val enabled = p.onboardControlSensorsEnabled().value()
        val health = p.onboardControlSensorsHealth().value()
        val unhealthy = sensorNames(present and enabled and health.inv())
        val newlyBad = unhealthy - i.unhealthySensors.toSet()
        if (newlyBad.isNotEmpty()) event(IntelEvent.Kind.SENSOR, "Sensor unhealthy: ${newlyBad.joinToString()}", IntelEvent.Severity.WARNING)
        val remaining = p.batteryRemaining().takeIf { it in 0..100 }
        batteryThresholds(remaining)
        i = i.copy(
            batteryMessageSeen = true,
            unhealthySensors = unhealthy,
            sensorsPresent = sensorNames(present),
            cpuLoadPct = p.load() / 10.0,
            commDropPct = p.dropRateComm() / 100.0,
            battery = i.battery.copy(
                voltageV = mv(p.voltageBattery()) ?: i.battery.voltageV,
                currentA = ca(p.currentBattery()) ?: i.battery.currentA,
                remainingPct = remaining ?: i.battery.remainingPct
            )
        )
    }

    private fun batteryStatus(p: BatteryStatus) {
        if (p.id() != 0) {
            val packV = p.voltages().filter { it in 1..65534 }.sum().takeIf { it > 0 }?.div(1000.0)
            i = i.copy(battery = i.battery.copy(battery2V = packV ?: i.battery.battery2V, battery2A = ca(p.currentBattery()) ?: i.battery.battery2A))
            return
        }
        i = i.copy(batteryMessageSeen = true)
        val raw = p.voltages().filter { it in 1..65534 }.map { it / 1000.0 }
        // Without per-cell monitoring ArduPilot reports the whole pack in cell 1.
        val cells = if (raw.size == 1 && raw[0] > 4.5) emptyList() else raw
        val remaining = p.batteryRemaining().takeIf { it in 0..100 }
        batteryThresholds(remaining)
        i = i.copy(
            battery = i.battery.copy(
                voltageV = if (raw.size == 1 && raw[0] > 4.5) raw[0] else if (cells.isNotEmpty()) cells.sum() else i.battery.voltageV,
                cellsV = cells.ifEmpty { i.battery.cellsV },
                currentA = ca(p.currentBattery()) ?: i.battery.currentA,
                consumedMah = p.currentConsumed().takeIf { it >= 0 } ?: i.battery.consumedMah,
                remainingPct = remaining ?: i.battery.remainingPct,
                tempC = p.temperature().takeIf { it != Short.MAX_VALUE.toInt() }?.div(100.0) ?: i.battery.tempC,
                timeRemainingS = p.timeRemaining().takeIf { it > 0 } ?: i.battery.timeRemainingS
            )
        )
    }

    private fun batteryThresholds(remaining: Int?) {
        val prev = i.battery.remainingPct ?: return
        if (remaining == null) return
        for ((limit, sev) in listOf(15 to IntelEvent.Severity.CRITICAL, 30 to IntelEvent.Severity.WARNING)) {
            if (prev > limit && remaining <= limit) {
                event(IntelEvent.Kind.BATTERY, "Battery at $remaining%", sev)
                return
            }
        }
    }

    private fun extendedSysState(p: ExtendedSysState) {
        val landed = when (p.landedState().value()) {
            1 -> "On ground"; 2 -> "In air"; 3 -> "Taking off"; 4 -> "Landing"; else -> null
        }
        val vtol = when (p.vtolState().value()) {
            1 -> "Transition to fixed-wing"; 2 -> "Transition to multicopter"; 3 -> "Multicopter"; 4 -> "Fixed-wing"; else -> null
        }
        if (landed != null && i.landedState != null && landed != i.landedState) {
            event(IntelEvent.Kind.FLIGHT, "Flight state: ${i.landedState} -> $landed", IntelEvent.Severity.NOTICE)
        }
        i = i.copy(landedState = landed ?: i.landedState, vtolState = vtol ?: i.vtolState)
    }

    private fun statusText(p: Statustext) {
        val text = p.text().trim()
        if (text.isEmpty()) return
        FIRMWARE_PATTERN.matchEntire(text)?.let { if (i.firmware == null || i.firmware != text) i = i.copy(firmware = text) }
        FRAME_PATTERN.matchEntire(text)?.let { i = i.copy(frame = it.groupValues[1].trim()) }
        val sev = when (p.severity().value()) {
            in 0..2 -> IntelEvent.Severity.CRITICAL
            in 3..4 -> IntelEvent.Severity.WARNING
            5 -> IntelEvent.Severity.NOTICE
            else -> IntelEvent.Severity.INFO
        }
        // Pre-arm failures repeat every ~30 s; drop exact repeats inside a short window.
        val dup = i.events.lastOrNull { it.kind == IntelEvent.Kind.STATUS && it.text == text }
        if (dup != null && now - dup.time < STATUSTEXT_DEDUPE_MS) return
        event(IntelEvent.Kind.STATUS, text, sev)
    }

    private fun globalPosition(p: GlobalPositionInt) {
        val fix = mavlinkPosition(i.netId, p, now) ?: return
        i = i.copy(
            altRelM = p.relativeAlt() / 1000.0,
            altMslM = p.alt() / 1000.0,
            headingDeg = p.hdg().takeIf { it != 65535 }?.div(100) ?: i.headingDeg,
            climbRate = -p.vz() / 100.0
        )
        setPosition(GeoFix(fix.latitude, fix.longitude, p.relativeAlt() / 1000.0), fix.source)
    }

    private fun gpsRaw(p: GpsRawInt) {
        val fixType = p.fixType().value()
        val fixName = gpsFixName(fixType)
        if (i.gpsFixType == null && fixType >= 2) {
            event(IntelEvent.Kind.GPS, "GPS: $fixName, ${p.satellitesVisible()} satellites")
        } else if (i.gpsFixType != null && (i.gpsFixType!! >= 3) != (fixType >= 3)) {
            event(IntelEvent.Kind.GPS, "GPS fix changed: ${gpsFixName(i.gpsFixType!!)} -> $fixName", if (fixType < 3) IntelEvent.Severity.WARNING else IntelEvent.Severity.INFO)
        }
        i = i.copy(
            gpsFix = fixName, gpsFixType = fixType,
            satellites = p.satellitesVisible().takeIf { it != 255 },
            hdop = p.eph().takeIf { it != 65535 }?.div(100.0),
            vdop = p.epv().takeIf { it != 65535 }?.div(100.0)
        )
        val fix = mavlinkPosition(i.netId, p, now) ?: return
        val fusedRecent = i.positionSource == "GLOBAL_POSITION_INT" && i.positionTime?.let { now - it < FUSED_FIX_PRIORITY_MS } == true
        if (!fusedRecent) setPosition(GeoFix(fix.latitude, fix.longitude, i.altRelM), fix.source)
    }

    private fun setPosition(fix: GeoFix, source: String) {
        if (i.position == null) {
            event(IntelEvent.Kind.GPS, "First position fix: %.6f, %.6f (%s)".format(Locale.US, fix.lat, fix.lon, source), IntelEvent.Severity.NOTICE)
        }
        val last = i.track.lastOrNull()
        val addPoint = last == null || now - last.time >= TRACK_MIN_INTERVAL_MS ||
            distanceM(last.lat, last.lon, fix.lat, fix.lon) >= TRACK_MIN_MOVE_M
        val track = if (!addPoint) i.track else (i.track + TrackPoint(fix.lat, fix.lon, fix.altM, now)).let {
            if (it.size > MAX_TRACK) it.takeLast(MAX_TRACK) else it
        }
        i = i.copy(position = fix, positionTime = now, positionSource = source, track = track)
    }

    private fun home(p: HomePosition) {
        val h = GeoFix(p.latitude() / 1e7, p.longitude() / 1e7, p.altitude() / 1000.0)
        if (h.lat == 0.0 && h.lon == 0.0) return
        val prev = i.home
        if (prev == null || distanceM(prev.lat, prev.lon, h.lat, h.lon) > 2.0) {
            event(IntelEvent.Kind.HOME, "Home (launch point) set by autopilot: %.6f, %.6f (alt %.0f m MSL)".format(Locale.US, h.lat, h.lon, h.altM ?: 0.0), IntelEvent.Severity.NOTICE)
        }
        i = i.copy(home = h)
    }

    private fun missionCurrent(p: MissionCurrent) {
        val total = p.total().takeIf { it != 65535 && it > 0 }
        if (i.missionSeq != null && i.missionSeq != p.seq()) {
            event(IntelEvent.Kind.MISSION, "Now heading to waypoint ${p.seq()}${total?.let { " of ${it - 1}" } ?: ""}")
        }
        i = i.copy(missionSeq = p.seq(), missionTotal = total ?: i.missionTotal)
    }

    private fun fence(p: FenceStatus) {
        val breached = p.breachStatus() != 0
        val type = prettyEnum(p.breachType().entry(), "FENCE_BREACH_")
        if (breached && i.fenceBreached != true) {
            event(IntelEvent.Kind.FENCE, "Geofence BREACHED${type?.let { " ($it)" } ?: ""}", IntelEvent.Severity.CRITICAL)
        } else if (!breached && i.fenceBreached == true) {
            event(IntelEvent.Kind.FENCE, "Geofence breach cleared")
        }
        i = i.copy(fenceBreached = breached, fenceBreachType = type, fenceBreachCount = p.breachCount())
    }

    private fun ekf(p: EkfStatusReport) {
        val worst = listOf(p.velocityVariance(), p.posHorizVariance(), p.posVertVariance(), p.compassVariance()).maxOrNull() ?: return
        // Mission Planner turns the EKF indicator red at 0.8.
        if (worst >= 0.8f && (i.ekfWorstVariance ?: 0f) < 0.8f) {
            event(IntelEvent.Kind.SENSOR, "EKF variance high (%.2f)".format(Locale.US, worst), IntelEvent.Severity.WARNING)
        }
        i = i.copy(ekfWorstVariance = worst)
    }

    private fun vibration(p: Vibration) {
        val v = listOf(p.vibrationX(), p.vibrationY(), p.vibrationZ())
        val worst = v.max()
        if (worst > 60f && (i.vibration?.max() ?: 0f) <= 60f) {
            event(IntelEvent.Kind.SENSOR, "Severe vibration (%.0f m/s²)".format(Locale.US, worst), IntelEvent.Severity.WARNING)
        }
        i = i.copy(vibration = v, clipping = p.clipping0() + p.clipping1() + p.clipping2())
    }

    private fun paramValue(p: ParamValue) {
        val id = p.paramId().trim()
        if (id.isEmpty()) return
        val total = p.paramCount().takeIf { it in 1..65534 }
        val before = i.params.size
        val params = i.params + (id to p.paramValue())
        if (before == 0) event(IntelEvent.Kind.PARAM, "Parameter download heard${total?.let { " ($it total)" } ?: ""}")
        if (total != null && before < total && params.size >= total) {
            event(IntelEvent.Kind.PARAM, "All $total parameters captured", IntelEvent.Severity.NOTICE)
        }
        i = i.copy(params = params, paramTotal = total ?: i.paramTotal)
    }

    private fun commandAck(p: CommandAck) {
        val cmd = p.command().entry() ?: return
        if (cmd in HOUSEKEEPING_COMMANDS) return
        val result = prettyEnum(p.result().entry(), "MAV_RESULT_") ?: "result ${p.result().value()}"
        event(IntelEvent.Kind.COMMAND, "Vehicle answered ${commandName(cmd)}: $result",
            if (p.result().value() == 0) IntelEvent.Severity.INFO else IntelEvent.Severity.WARNING)
    }

    private fun autopilotVersion(p: AutopilotVersion) {
        val v = p.flightSwVersion()
        val version = "v${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}"
        val hash = p.flightCustomVersion()?.takeWhile { it != 0.toByte() }?.toByteArray()?.toString(Charsets.US_ASCII).orEmpty()
        val uid = p.uid()?.takeIf { it.signum() != 0 }?.toString(16)
        if (i.firmware == null) i = i.copy(firmware = "${i.autopilot ?: "Firmware"} $version${if (hash.isNotEmpty()) " ($hash)" else ""}")
        if (uid != null && uid != i.boardUid) event(IntelEvent.Kind.VEHICLE, "Flight controller UID $uid, firmware $version")
        i = i.copy(boardUid = uid ?: i.boardUid)
    }

    // ---- operator / mission -------------------------------------------

    fun command(cmdValue: io.dronefleet.mavlink.util.EnumValue<MavCmd>, p1: Float, p2: Float, p7: Float, lat: Double?, lon: Double?) {
        val cmd = cmdValue.entry()
        if (cmd != null && cmd in HOUSEKEEPING_COMMANDS) return
        val text = when (cmd) {
            MavCmd.MAV_CMD_COMPONENT_ARM_DISARM ->
                (if (p1 >= 0.5f) "ARM" else "DISARM") + (if (abs(p2 - 21196f) < 1f) " (forced)" else "")
            MavCmd.MAV_CMD_NAV_TAKEOFF -> "TAKEOFF to %.0f m".format(Locale.US, p7)
            MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH -> "RETURN TO LAUNCH"
            MavCmd.MAV_CMD_NAV_LAND -> "LAND"
            MavCmd.MAV_CMD_DO_SET_MODE -> "mode ${modeName(p2.toLong())}"
            MavCmd.MAV_CMD_MISSION_START -> "MISSION START"
            MavCmd.MAV_CMD_DO_CHANGE_SPEED -> "speed %.1f m/s".format(Locale.US, p2)
            MavCmd.MAV_CMD_DO_REPOSITION ->
                if (lat != null && lon != null) "REPOSITION to %.6f, %.6f".format(Locale.US, lat, lon) else "REPOSITION"
            MavCmd.MAV_CMD_DO_SET_HOME -> "SET HOME"
            MavCmd.MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN -> "REBOOT/SHUTDOWN"
            MavCmd.MAV_CMD_DO_FLIGHTTERMINATION -> "FLIGHT TERMINATION"
            null -> "command #${cmdValue.value()}"
            else -> commandName(cmd)
        }
        val critical = cmd == MavCmd.MAV_CMD_COMPONENT_ARM_DISARM || cmd == MavCmd.MAV_CMD_DO_FLIGHTTERMINATION || cmd == MavCmd.MAV_CMD_NAV_TAKEOFF
        event(IntelEvent.Kind.COMMAND, "Operator command: $text", if (critical) IntelEvent.Severity.WARNING else IntelEvent.Severity.NOTICE)
    }

    fun missionCount(p: MissionCount, fromGcs: Boolean) {
        if (p.missionType().value() != 0) return // fence / rally lists
        val n = p.count()
        event(IntelEvent.Kind.MISSION,
            if (fromGcs) "Operator uploading a mission: $n items" else "Vehicle sending its mission: $n items",
            IntelEvent.Severity.NOTICE)
        val keep = if (i.missionExpected == n) i.mission else emptyMap()
        i = i.copy(missionExpected = n, mission = keep)
    }

    fun missionItem(seq: Int, missionType: Int, cmd: io.dronefleet.mavlink.util.EnumValue<MavCmd>, lat: Double, lon: Double, alt: Float) {
        if (missionType != 0) return
        val hasPos = !(lat == 0.0 && lon == 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0
        val wp = Waypoint(seq, cmd.entry()?.let(::commandName) ?: "cmd ${cmd.value()}", if (hasPos) lat else null, if (hasPos) lon else null, alt)
        val had = i.mission.size
        val mission = i.mission + (seq to wp)
        val expected = i.missionExpected
        if (expected != null && had < expected && mission.size >= expected) {
            event(IntelEvent.Kind.MISSION, "Mission captured: $expected items", IntelEvent.Severity.NOTICE)
        }
        i = i.copy(mission = mission)
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun mv(v: Int): Double? = v.takeIf { it in 1..65534 }?.div(1000.0)
private fun ca(v: Int): Double? = v.takeIf { it >= 0 }?.div(100.0)
private fun fmtParam(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.3f".format(Locale.US, v)

fun prettyEnum(e: Enum<*>?, prefix: String): String? = e?.name?.removePrefix(prefix)?.replace('_', ' ')
    ?.lowercase(Locale.US)?.replaceFirstChar { it.titlecase(Locale.US) }

fun commandName(cmd: MavCmd): String = cmd.name.removePrefix("MAV_CMD_").removePrefix("NAV_").removePrefix("DO_").replace('_', ' ')

fun gpsFixName(type: Int): String = when (type) {
    0, 1 -> "No fix"; 2 -> "2D fix"; 3 -> "3D fix"; 4 -> "DGPS"; 5 -> "RTK float"; 6 -> "RTK fixed"; 7 -> "Static"; 8 -> "PPP"
    else -> "Fix type $type"
}

private val SENSOR_BITS = listOf(
    0 to "Gyro", 1 to "Accel", 2 to "Compass", 3 to "Baro", 4 to "Airspeed", 5 to "GPS",
    6 to "Optical flow", 7 to "Vision", 8 to "Laser", 10 to "Rate control", 11 to "Attitude",
    12 to "Yaw position", 13 to "Altitude control", 14 to "Position control", 15 to "Motor outputs",
    16 to "RC receiver", 17 to "Gyro 2", 18 to "Accel 2", 19 to "Compass 2", 20 to "Geofence",
    21 to "AHRS", 22 to "Terrain", 24 to "Logging", 25 to "Battery", 26 to "Proximity",
    28 to "Pre-arm checks", 29 to "Obstacle avoidance", 30 to "Propulsion"
)

private fun sensorNames(mask: Int): List<String> = SENSOR_BITS.filter { (bit, _) -> mask and (1 shl bit) != 0 }.map { it.second }

/** Equirectangular distance; plenty accurate at drone ranges. */
fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
    val y = Math.toRadians(lat2 - lat1)
    return sqrt(x * x + y * y) * r
}

private val COPTER_MODES = mapOf(
    0L to "STABILIZE", 1L to "ACRO", 2L to "ALT_HOLD", 3L to "AUTO", 4L to "GUIDED", 5L to "LOITER",
    6L to "RTL", 7L to "CIRCLE", 9L to "LAND", 11L to "DRIFT", 13L to "SPORT", 14L to "FLIP",
    15L to "AUTOTUNE", 16L to "POSHOLD", 17L to "BRAKE", 18L to "THROW", 19L to "AVOID_ADSB",
    20L to "GUIDED_NOGPS", 21L to "SMART_RTL", 22L to "FLOWHOLD", 23L to "FOLLOW", 24L to "ZIGZAG",
    25L to "SYSTEMID", 26L to "AUTOROTATE", 27L to "AUTO_RTL"
)
private val PLANE_MODES = mapOf(
    0L to "MANUAL", 1L to "CIRCLE", 2L to "STABILIZE", 3L to "TRAINING", 4L to "ACRO", 5L to "FBWA",
    6L to "FBWB", 7L to "CRUISE", 8L to "AUTOTUNE", 10L to "AUTO", 11L to "RTL", 12L to "LOITER",
    13L to "TAKEOFF", 14L to "AVOID_ADSB", 15L to "GUIDED", 17L to "QSTABILIZE", 18L to "QHOVER",
    19L to "QLOITER", 20L to "QLAND", 21L to "QRTL", 22L to "QAUTOTUNE", 23L to "QACRO", 24L to "THERMAL",
    25L to "LOITER_ALT_QLAND"
)
private val ROVER_MODES = mapOf(
    0L to "MANUAL", 1L to "ACRO", 3L to "STEERING", 4L to "HOLD", 5L to "LOITER", 6L to "FOLLOW",
    7L to "SIMPLE", 8L to "DOCK", 9L to "CIRCLE", 10L to "AUTO", 11L to "RTL", 12L to "SMART_RTL",
    15L to "GUIDED", 16L to "INITIALISING"
)
private val SUB_MODES = mapOf(
    0L to "STABILIZE", 1L to "ACRO", 2L to "ALT_HOLD", 3L to "AUTO", 4L to "GUIDED", 7L to "CIRCLE",
    9L to "SURFACE", 16L to "POSHOLD", 19L to "MANUAL"
)
// Named the way QGroundControl shows them (e.g. AUTO.LOITER is "HOLD").
private val PX4_MAIN = mapOf(1 to "MANUAL", 2 to "ALTITUDE", 3 to "POSITION", 4 to "AUTO", 5 to "ACRO", 6 to "OFFBOARD", 7 to "STABILIZED", 8 to "RATTITUDE")
private val PX4_AUTO = mapOf(1 to "READY", 2 to "TAKEOFF", 3 to "HOLD", 4 to "MISSION", 5 to "RETURN", 6 to "LAND", 8 to "FOLLOW ME", 9 to "PRECISION LAND")

/** Decodes HEARTBEAT.custom_mode per autopilot and vehicle class. */
fun flightModeName(autopilot: Int?, vehicleType: Int?, customMode: Long): String {
    if (autopilot == MAV_AUTOPILOT_PX4) {
        val main = ((customMode shr 16) and 0xFF).toInt()
        val sub = ((customMode shr 24) and 0xFF).toInt()
        val mainName = PX4_MAIN[main] ?: "MODE $main"
        return if (main == 4) PX4_AUTO[sub] ?: "AUTO $sub" else mainName
    }
    val table = when (vehicleType) {
        1, 19, 20, 21, 22, 23, 24, 25 -> PLANE_MODES
        10, 11 -> ROVER_MODES
        12 -> SUB_MODES
        else -> COPTER_MODES
    }
    return table[customMode] ?: "MODE $customMode"
}
