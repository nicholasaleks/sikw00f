package com.sikw00f.android.protocol

import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.MavMissionType
import io.dronefleet.mavlink.common.MavSeverity
import io.dronefleet.mavlink.common.MissionCount
import io.dronefleet.mavlink.common.MissionItemInt
import io.dronefleet.mavlink.common.Statustext
import io.dronefleet.mavlink.common.SysStatus
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.dronefleet.mavlink.minimal.MavModeFlag
import io.dronefleet.mavlink.minimal.MavState
import io.dronefleet.mavlink.minimal.MavType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DroneIntelTest {

    private fun vehicleHb(armed: Boolean, mode: Long) = Heartbeat.builder()
        .type(MavType.MAV_TYPE_QUADROTOR)
        .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
        .apply {
            if (armed) baseMode(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED, MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
            else baseMode(MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
        }
        .customMode(mode)
        .systemStatus(MavState.MAV_STATE_ACTIVE)
        .build()

    private val gcsHb = Heartbeat.builder()
        .type(MavType.MAV_TYPE_GCS)
        .autopilot(MavAutopilot.MAV_AUTOPILOT_INVALID)
        .baseMode(MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
        .customMode(0)
        .systemStatus(MavState.MAV_STATE_ACTIVE)
        .build()

    private fun DroneIntel.feed(sysId: Int, payload: Any, compId: Int = 1) = update(sysId, compId, payload, now = 1000L)

    @Test
    fun gcsHeartbeatDoesNotOverwriteVehicleState() {
        var i = DroneIntel(netId = 18).feed(1, vehicleHb(armed = true, mode = 5)).intel
        val gcs = i.feed(255, gcsHb, compId = 190)
        i = gcs.intel
        assertEquals(true, i.armed)
        assertEquals("LOITER", i.flightMode)
        assertEquals("Quadrotor", i.vehicleType)
        assertEquals("ArduPilot", i.autopilot)
        assertEquals(setOf(255), i.gcsSysIds)
        assertTrue(gcs.newEvents.any { it.kind == IntelEvent.Kind.GCS })
    }

    @Test
    fun componentHeartbeatsAreIgnored() {
        val camera = Heartbeat.builder().type(MavType.MAV_TYPE_CAMERA).autopilot(MavAutopilot.MAV_AUTOPILOT_INVALID)
            .baseMode(MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED).customMode(0).systemStatus(MavState.MAV_STATE_ACTIVE).build()
        val i = DroneIntel(netId = 18).feed(1, camera, compId = 100).intel
        assertNull(i.vehicleSysId)
        assertNull(i.armed)
    }

    @Test
    fun armAndModeChangesAreLogged() {
        var i = DroneIntel(netId = 18).feed(1, vehicleHb(armed = false, mode = 0)).intel
        val armed = i.feed(1, vehicleHb(armed = true, mode = 0))
        assertTrue(armed.newEvents.any { it.kind == IntelEvent.Kind.ARM && it.text.contains("ARMED") })
        i = armed.intel
        val mode = i.feed(1, vehicleHb(armed = true, mode = 6))
        assertTrue(mode.newEvents.any { it.kind == IntelEvent.Kind.MODE && it.text.contains("RTL") })
    }

    @Test
    fun telemetryFromGcsSysIdIsNotTreatedAsVehicle() {
        var i = DroneIntel(netId = 18).feed(1, vehicleHb(armed = false, mode = 0)).intel
        i = i.feed(255, gcsHb).intel
        val gcsPos = GlobalPositionInt.builder().lat(100000000).lon(200000000).relativeAlt(1000).alt(5000).hdg(0).build()
        i = i.feed(255, gcsPos).intel
        assertNull(i.position)
        val vehiclePos = GlobalPositionInt.builder().lat(436282700).lon(-795025500).relativeAlt(42000).alt(150000).hdg(9000).build()
        i = i.feed(1, vehiclePos).intel
        assertEquals(43.62827, i.position!!.lat, 1e-6)
        assertEquals(42.0, i.altRelM!!, 1e-6)
        assertEquals(90, i.headingDeg)
    }

    @Test
    fun statusTextSetsFirmwareAndFrame() {
        var i = DroneIntel(netId = 18).feed(1, vehicleHb(armed = false, mode = 0)).intel
        i = i.feed(1, Statustext.builder().severity(MavSeverity.MAV_SEVERITY_INFO).text("ArduCopter V4.5.1 (2ec40b79)").build()).intel
        i = i.feed(1, Statustext.builder().severity(MavSeverity.MAV_SEVERITY_INFO).text("Frame: QUAD/X").build()).intel
        val prearm = i.feed(1, Statustext.builder().severity(MavSeverity.MAV_SEVERITY_CRITICAL).text("PreArm: GPS not healthy").build())
        assertEquals("ArduCopter V4.5.1 (2ec40b79)", prearm.intel.firmware)
        assertEquals("QUAD/X", prearm.intel.frame)
        assertEquals(IntelEvent.Severity.CRITICAL, prearm.newEvents.single().severity)
        // Exact repeat inside the dedupe window is dropped.
        val repeat = prearm.intel.feed(1, Statustext.builder().severity(MavSeverity.MAV_SEVERITY_CRITICAL).text("PreArm: GPS not healthy").build())
        assertTrue(repeat.newEvents.isEmpty())
    }

    @Test
    fun batteryAndSensorHealthFromSysStatus() {
        val status = SysStatus.builder()
            .onboardControlSensorsPresent(io.dronefleet.mavlink.util.EnumValue.create(io.dronefleet.mavlink.common.MavSysStatusSensor::class.java, 0b100000))
            .onboardControlSensorsEnabled(io.dronefleet.mavlink.util.EnumValue.create(io.dronefleet.mavlink.common.MavSysStatusSensor::class.java, 0b100000))
            .onboardControlSensorsHealth(io.dronefleet.mavlink.util.EnumValue.create(io.dronefleet.mavlink.common.MavSysStatusSensor::class.java, 0))
            .voltageBattery(16_420).currentBattery(1_250).batteryRemaining(76).load(355).dropRateComm(0)
            .build()
        val r = DroneIntel(netId = 18).feed(1, vehicleHb(false, 0)).intel.feed(1, status)
        assertEquals(16.42, r.intel.battery.voltageV!!, 1e-6)
        assertEquals(12.5, r.intel.battery.currentA!!, 1e-6)
        assertEquals(76, r.intel.battery.remainingPct)
        assertEquals(listOf("GPS"), r.intel.unhealthySensors)
        assertTrue(r.newEvents.any { it.kind == IntelEvent.Kind.SENSOR })
    }

    @Test
    fun operatorCommandsAreLoggedButHousekeepingIsNot() {
        var i = DroneIntel(netId = 18).feed(1, vehicleHb(false, 0)).intel
        i = i.feed(255, gcsHb).intel
        val arm = i.feed(255, CommandLong.builder().targetSystem(1).targetComponent(1).command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM).param1(1f).build())
        assertTrue(arm.newEvents.single().text.contains("ARM"))
        val chatter = arm.intel.feed(255, CommandLong.builder().targetSystem(1).targetComponent(1).command(MavCmd.MAV_CMD_REQUEST_MESSAGE).param1(242f).build())
        assertTrue(chatter.newEvents.isEmpty())
    }

    @Test
    fun missionUploadIsReconstructed() {
        var i = DroneIntel(netId = 18).feed(1, vehicleHb(false, 0)).intel
        i = i.feed(255, gcsHb).intel
        i = i.feed(255, MissionCount.builder().targetSystem(1).count(3).missionType(MavMissionType.MAV_MISSION_TYPE_MISSION).build()).intel
        for (seq in 0 until 3) {
            val r = i.feed(
                255,
                MissionItemInt.builder().targetSystem(1).seq(seq).command(MavCmd.MAV_CMD_NAV_WAYPOINT)
                    .x(436280000 + seq * 1000).y(-795020000).z(30f).missionType(MavMissionType.MAV_MISSION_TYPE_MISSION).build()
            )
            i = r.intel
            if (seq == 2) assertTrue(r.newEvents.any { it.text.startsWith("Mission captured") })
        }
        assertEquals(3, i.missionWaypoints.size)
        assertEquals("WAYPOINT", i.missionWaypoints[1].command)
        assertEquals(43.6281, i.missionWaypoints[1].lat!!, 1e-6)
    }

    @Test
    fun flightModeTablesPerVehicle() {
        assertEquals("LOITER", flightModeName(3, 2, 5))
        assertEquals("LAND", flightModeName(3, 2, 9))
        assertEquals("FBWA", flightModeName(3, 1, 5))
        assertEquals("QLOITER", flightModeName(3, 20, 19))
        assertEquals("HOLD", flightModeName(3, 10, 4))
        assertEquals("MISSION", flightModeName(12, 2, (4L shl 16) or (4L shl 24)))
        assertEquals("HOLD", flightModeName(12, 0, (4L shl 16) or (3L shl 24)))
        assertFalse(flightModeName(3, 2, 99).isEmpty())
    }
}
