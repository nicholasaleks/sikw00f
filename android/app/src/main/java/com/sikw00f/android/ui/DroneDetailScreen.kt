package com.sikw00f.android.ui

import android.location.Location
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sikw00f.android.protocol.DiscoveredDrone
import com.sikw00f.android.protocol.DroneIntel
import com.sikw00f.android.protocol.IntelEvent
import com.sikw00f.android.protocol.KEY_PARAMS
import com.sikw00f.android.protocol.ScanMode
import com.sikw00f.android.protocol.ScanState
import com.sikw00f.android.protocol.formatMHz
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

private const val STALE_AFTER_MS = 10_000L
private const val MAX_EVENTS_SHOWN = 80
private const val MAX_WAYPOINTS_SHOWN = 40

@Composable
fun DroneDetailScreen(netId: Int, viewModel: AppViewModel, onBack: () -> Unit) {
    val scanState by viewModel.scanState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val result by viewModel.autotuneResult.collectAsState()
    val frequencyPlan by viewModel.frequencyPlan.collectAsState()
    val lockState by viewModel.lockState.collectAsState()
    val allIntel by viewModel.intel.collectAsState()
    val phone by viewModel.phoneLocation.collectAsState()
    val intel = allIntel[netId]
    val knownAll by viewModel.knownDrones.collectAsState()
    val knownDrone = knownAll[netId]
    var labelling by remember { mutableStateOf(false) }

    // Ticks once a second so "x s ago" labels and staleness stay current.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    val drone = when (val s = scanState) {
        is ScanState.Running -> s.drones[netId]
        is ScanState.Stopped -> s.drones[netId]
        is ScanState.Idle -> null
    }
    val scanMode = (scanState as? ScanState.Running)?.mode ?: (scanState as? ScanState.Stopped)?.mode

    LaunchedEffect(netId) { viewModel.clearAutotuneResult() }

    val lockedHere = (lockState as? LockState.Locked)?.netId == netId
    val lockingHere = (lockState as? LockState.Locking)?.netId == netId
    val connected = connectionState is ConnectionState.Connected

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------------------------------------------------------- header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = droneColor(netId), modifier = Modifier.size(12.dp)) {}
                    Spacer(Modifier.width(8.dp))
                    Text(knownDrone?.name?.takeIf { it.isNotBlank() } ?: "NetID $netId", style = MaterialTheme.typography.titleLarge)
                    if (knownDrone?.name?.isNotBlank() == true) {
                        Spacer(Modifier.width(8.dp))
                        Text("NetID $netId", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (knownDrone?.friendly == true) {
                        Spacer(Modifier.width(8.dp))
                        Text("FRIENDLY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                val identity = listOfNotNull(intel?.vehicleType, intel?.autopilot, intel?.firmware ?: intel?.frame)
                Text(
                    identity.joinToString(" · ").ifEmpty { "Vehicle not identified yet" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { labelling = true }) {
                Icon(
                    if (knownDrone != null) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Label,
                    contentDescription = if (knownDrone != null) "Edit label" else "Label this drone"
                )
            }
        }
        if (labelling) {
            KnownDroneDialog(
                netId = netId,
                existing = knownDrone,
                onSave = { viewModel.saveKnownDrone(it); labelling = false },
                onRemove = knownDrone?.let { { viewModel.removeKnownDrone(netId); labelling = false } },
                onDismiss = { labelling = false }
            )
        }

        val dronePositions by viewModel.dronePositions.collectAsState()
        val trackPoints = intel?.track?.size ?: 0
        DeviceLocationMap(
            scanning = false,
            statusLabel = when {
                intel?.position == null -> "No GPS heard yet"
                trackPoints >= 2 -> "Track · $trackPoints points"
                else -> "Live position"
            },
            drones = dronePositions.filterKeys { it == netId },
            intel = allIntel.filterKeys { it == netId },
            focusNetIds = setOf(netId),
            onPhoneLocation = { viewModel.updatePhoneLocation(it.latitude, it.longitude, it.accuracy) },
            modifier = Modifier.fillMaxWidth().height(190.dp)
        )

        StatusChips(intel, lockedHere, now, ecc = drone?.ecc == true)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.lockOn(netId) },
                enabled = !busy && connected,
                modifier = Modifier.weight(1f)
            ) {
                if (lockingHere) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (lockedHere) "Re-lock" else if (lockingHere) "Locking…" else "Lock on")
            }
            if (lockedHere) {
                OutlinedButton(
                    onClick = { viewModel.releaseLock() },
                    enabled = !busy && connected,
                    modifier = Modifier.weight(1f)
                ) { Text("Release") }
            } else {
                OutlinedButton(
                    onClick = { viewModel.autotuneToClone(netId.toString()) },
                    enabled = !busy && connected,
                    modifier = Modifier.weight(1f)
                ) { Text("Full lock") }
            }
        }
        (lockState as? LockState.Failed)?.takeIf { it.netId == netId }?.let {
            Text("Lock failed: ${it.message}. Try Full lock.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        result?.let { r ->
            Text(
                if (r.appliedKeys.isNotEmpty()) "Full lock matched ${r.appliedKeys.size} settings: ${r.appliedKeys.joinToString()}"
                else "Full lock couldn't read this drone's settings. Try a deep scan first.",
                style = MaterialTheme.typography.bodySmall,
                color = if (r.appliedKeys.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }

        if (intel == null) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "No MAVLink decoded from this NetID yet. Everything heard while scanning is decoded " +
                        "automatically; lock on to hear every packet instead of occasional overlaps.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            PositionSection(intel, phone, now)
            GpsSection(intel)
            BatterySection(intel)
            MissionSection(intel)
            HealthSection(intel)
            GroundStationSection(intel, now)
            PilotPayloadSection(intel)
            ParametersSection(intel)
        }

        RadioLinkSection(drone, intel, frequencyPlan, scanMode, now)
        if (intel != null) EventLogSection(intel)
        if (intel != null && intel.messageCounts.isNotEmpty()) MessageStatsSection(intel)
    }
}

// ---------------------------------------------------------------- header chips

@Composable
private fun StatusChips(intel: DroneIntel?, locked: Boolean, now: Long, ecc: Boolean) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (locked) Chip("LOCKED", c.primary, c.onPrimary)
        if (ecc) Chip("ECC link", c.tertiaryContainer, c.onTertiaryContainer)
        intel?.armed?.let { armed ->
            if (armed) Chip("ARMED", c.error, c.onError) else Chip("Disarmed", c.surfaceVariant, c.onSurfaceVariant)
        }
        intel?.flightMode?.let { Chip(it, c.primaryContainer, c.onPrimaryContainer) }
        intel?.landedState?.let { Chip(it, c.secondaryContainer, c.onSecondaryContainer) }
        intel?.vtolState?.let { Chip(it, c.secondaryContainer, c.onSecondaryContainer) }
        if (intel?.simulated == true) Chip("SIMULATED", c.tertiary, c.onTertiary)
        if (intel?.fenceBreached == true) Chip("FENCE BREACH", c.error, c.onError)
        intel?.gcsLastSeen?.let { seen ->
            val age = now - seen
            if (age < STALE_AFTER_MS) Chip("GCS online", c.secondaryContainer, c.onSecondaryContainer)
            else Chip("GCS seen ${ago(age)}", c.surfaceVariant, c.onSurfaceVariant)
        }
        intel?.lastMessage?.let { last ->
            if (now - last > STALE_AFTER_MS) Chip("No MAVLink for ${ago(now - last)}", c.errorContainer, c.onErrorContainer)
        }
    }
}

@Composable
private fun Chip(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

// ---------------------------------------------------------------- sections

@Composable
private fun BatterySection(i: DroneIntel) {
    val b = i.battery
    if (b.voltageV == null && b.remainingPct == null && b.currentA == null) {
        if (i.batteryMessageSeen) {
            Section("Battery", Icons.Filled.BatteryFull, trailing = "not reported") {
                Text(
                    "The vehicle sends battery messages but every value is \"unknown\" (voltage 0, remaining -1). " +
                        "Typical of a bench flight controller with no power module connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    val c = MaterialTheme.colorScheme
    val pct = b.remainingPct
    val tone = when {
        pct == null -> c.primary
        pct <= 15 -> c.error
        pct <= 30 -> c.tertiary
        else -> c.primary
    }
    Section("Battery", Icons.Filled.BatteryFull, trailing = pct?.let { "$it%" }) {
        if (pct != null) {
            LinearProgressIndicator(
                progress = { pct / 100f },
                color = tone,
                trackColor = c.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
        }
        StatGrid(
            "Voltage" to b.voltageV?.let { "%.2f V".format(Locale.US, it) },
            "Current" to b.currentA?.let { "%.1f A".format(Locale.US, it) },
            "Power" to if (b.voltageV != null && b.currentA != null) "%.0f W".format(Locale.US, b.voltageV * b.currentA) else null,
            "Used" to b.consumedMah?.let { "$it mAh" },
            "Time left" to b.timeRemainingS?.let { duration(it.toLong()) },
            "Temp" to b.tempC?.let { "%.0f °C".format(Locale.US, it) },
            "Cells" to b.cellsV.takeIf { it.isNotEmpty() }?.let { cells ->
                "${cells.size}S · " + cells.joinToString(" / ") { "%.2f".format(Locale.US, it) }
            },
            "Battery 2" to b.battery2V?.let { v -> "%.2f V".format(Locale.US, v) + (b.battery2A?.let { " · %.1f A".format(Locale.US, it) } ?: "") },
            "Capacity" to i.params["BATT_CAPACITY"]?.takeIf { it > 0 }?.let { "${it.toInt()} mAh" }
        )
    }
}

@Composable
private fun PositionSection(i: DroneIntel, phone: Pair<Double, Double>?, now: Long) {
    val p = i.position
    if (p == null && i.altRelM == null && i.groundSpeed == null) return
    val fromYou = if (p != null && phone != null) rangeBearing(phone.first, phone.second, p.lat, p.lon) else null
    val fromHome = if (p != null && i.home != null) rangeBearing(i.home.lat, i.home.lon, p.lat, p.lon) else null
    Section("Position & motion", Icons.Filled.Explore, trailing = i.positionTime?.let { ago(now - it) }) {
        p?.let {
            Text(
                "%.6f, %.6f".format(Locale.US, it.lat, it.lon),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        StatGrid(
            "From you" to fromYou,
            "From home" to fromHome,
            "Altitude" to i.altRelM?.let { "%.1f m".format(Locale.US, it) },
            "Alt MSL" to i.altMslM?.let { "%.0f m".format(Locale.US, it) },
            "Ground spd" to i.groundSpeed?.let { "%.1f m/s".format(Locale.US, it) },
            "Air spd" to i.airSpeed?.takeIf { it > 0.0 }?.let { "%.1f m/s".format(Locale.US, it) },
            "Climb" to i.climbRate?.let { "%+.1f m/s".format(Locale.US, it) },
            "Heading" to i.headingDeg?.let { "$it° ${compass(it.toFloat())}" },
            "Throttle" to i.throttlePct?.let { "$it%" },
            "Roll / pitch" to if (i.rollDeg != null && i.pitchDeg != null) "%.0f° / %.0f°".format(Locale.US, i.rollDeg, i.pitchDeg) else null,
            "Source" to i.positionSource?.replace('_', ' ')?.lowercase(Locale.US),
            "Track pts" to i.track.size.takeIf { it > 1 }?.toString()
        )
    }
}

@Composable
private fun GpsSection(i: DroneIntel) {
    if (i.gpsFix == null) return
    val bad = (i.gpsFixType ?: 0) < 3
    Section("GPS", Icons.Filled.GpsFixed, trailing = i.gpsFix, trailingColor = if (bad) MaterialTheme.colorScheme.error else null) {
        StatGrid(
            "Satellites" to i.satellites?.toString(),
            "HDOP" to i.hdop?.let { "%.2f".format(Locale.US, it) },
            "VDOP" to i.vdop?.let { "%.2f".format(Locale.US, it) },
            "GPS time" to i.gpsTimeUtcMs?.let { utc(it) },
            "Uptime" to i.uptimeS?.let { duration(it) }
        )
    }
}

@Composable
private fun MissionSection(i: DroneIntel) {
    if (i.home == null && i.missionSeq == null && i.mission.isEmpty() && i.missionExpected == null) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val waypoints = i.missionWaypoints
    Section("Mission & home", Icons.Filled.Route, trailing = i.missionSeq?.let { seq ->
        "WP $seq" + (i.missionTotal?.let { " / ${it - 1}" } ?: "")
    }) {
        StatGrid(
            "Home (launch)" to i.home?.let { "%.5f, %.5f".format(Locale.US, it.lat, it.lon) },
            "To waypoint" to i.wpDistM?.let { distance(it.toDouble()) },
            "Target brg" to i.targetBearing?.let { "$it° ${compass(it.toFloat())}" },
            "Alt error" to i.altErrorM?.let { "%+.1f m".format(Locale.US, it) },
            "Crosstrack" to i.xtrackErrorM?.let { "%.1f m".format(Locale.US, it) },
            "Captured" to if (i.missionExpected != null || waypoints.isNotEmpty()) {
                "${waypoints.size}" + (i.missionExpected?.let { " of $it" } ?: "") + " items"
            } else null
        )
        if (waypoints.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Flight plan", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                waypoints.take(MAX_WAYPOINTS_SHOWN).forEach { wp ->
                    val current = wp.seq == i.missionSeq
                    Text(
                        "${wp.seq.toString().padStart(2)}  ${wp.command.padEnd(14).take(14)} " +
                            "%5.0fm".format(Locale.US, wp.altM) +
                            (if (wp.lat != null && wp.lon != null) "  %.5f, %.5f".format(Locale.US, wp.lat, wp.lon) else ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal
                    )
                }
                if (waypoints.size > MAX_WAYPOINTS_SHOWN) {
                    Text("+${waypoints.size - MAX_WAYPOINTS_SHOWN} more (all saved in the detections log)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun HealthSection(i: DroneIntel) {
    val hasData = i.sensorsPresent.isNotEmpty() || i.ekfWorstVariance != null || i.vibration != null ||
        i.cpuLoadPct != null || i.boardVccV != null || i.fenceBreached != null
    if (!hasData) return
    val c = MaterialTheme.colorScheme
    Section(
        "Health", Icons.Filled.MonitorHeart,
        trailing = if (i.unhealthySensors.isEmpty()) "OK" else "${i.unhealthySensors.size} issue(s)",
        trailingColor = if (i.unhealthySensors.isEmpty()) null else c.error
    ) {
        if (i.unhealthySensors.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                i.unhealthySensors.forEach { Chip(it, c.errorContainer, c.onErrorContainer) }
            }
        } else if (i.sensorsPresent.isNotEmpty()) {
            Text("All ${i.sensorsPresent.size} reported sensors healthy", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        StatGrid(
            "EKF" to i.ekfWorstVariance?.let { v -> (if (v < 0.5f) "Good" else if (v < 0.8f) "Marginal" else "Bad") + " (%.2f)".format(Locale.US, v) },
            "Vibration" to i.vibration?.let { v -> v.joinToString(" / ") { "%.0f".format(Locale.US, it) } + " m/s²" },
            "Clipping" to i.clipping?.toString(),
            "CPU load" to i.cpuLoadPct?.let { "%.0f%%".format(Locale.US, it) },
            "Link drops" to i.commDropPct?.let { "%.1f%%".format(Locale.US, it) },
            "Board Vcc" to i.boardVccV?.let { "%.2f V".format(Locale.US, it) },
            "Fence" to i.fenceBreached?.let { breached ->
                if (breached) "BREACHED" + (i.fenceBreachType?.let { " ($it)" } ?: "") else "OK" + (i.fenceBreachCount?.takeIf { it > 0 }?.let { " ($it past)" } ?: "")
            }
        )
    }
}

@Composable
private fun EventLogSection(i: DroneIntel) {
    if (i.events.isEmpty()) return
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Section("Event log", Icons.AutoMirrored.Filled.List, trailing = "${i.events.size}") {
        i.events.asReversed().take(MAX_EVENTS_SHOWN).forEachIndexed { index, e ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    fmt.format(Date(e.time)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(e.kind.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.text, style = MaterialTheme.typography.bodySmall, color = severityColor(e.severity))
                }
            }
        }
    }
}

@Composable
private fun GroundStationSection(i: DroneIntel, now: Long) {
    if (i.gcsSysIds.isEmpty()) return
    val commands = i.events.count { it.kind == IntelEvent.Kind.COMMAND }
    Section("Ground station", Icons.Filled.SettingsRemote, trailing = i.gcsLastSeen?.let { if (now - it < STALE_AFTER_MS) "online" else ago(now - it) }) {
        StatGrid(
            "GCS sysid" to i.gcsSysIds.sorted().joinToString(),
            "GCS position" to (i.gcsPosition?.let { "%.5f, %.5f".format(Locale.US, it.lat, it.lon) } ?: "not transmitted"),
            "Vehicle sysid" to i.vehicleSysId?.toString(),
            "Commands" to commands.toString(),
            "Last heard" to i.gcsLastSeen?.let { ago(now - it) }
        )
    }
}

@Composable
private fun PilotPayloadSection(i: DroneIntel) {
    val hasData = i.rcChannelCount != null || i.motorOutputs.isNotEmpty() || i.windSpeed != null ||
        i.photosTaken > 0 || i.adsbContacts.isNotEmpty()
    if (!hasData) return
    Section("Pilot, motors & payload", Icons.Filled.Gamepad) {
        StatGrid(
            "RC RSSI" to i.rcRssiPct?.let { "$it%" },
            "RC channels" to i.rcChannelCount?.takeIf { it > 0 }?.toString(),
            "Motors" to i.motorOutputs.takeIf { it.isNotEmpty() }?.size?.toString(),
            "Wind" to i.windSpeed?.let { s -> "%.1f m/s".format(Locale.US, s) + (i.windFromDeg?.let { " from ${compass(it)}" } ?: "") },
            "Photos" to i.photosTaken.takeIf { it > 0 }?.toString(),
            "ADS-B" to i.adsbContacts.takeIf { it.isNotEmpty() }?.values?.joinToString()
        )
        if (i.rcInputs.isNotEmpty()) {
            MonoLine("Sticks  " + i.rcInputs.mapIndexed { n, v -> "${n + 1}:$v" }.joinToString(" "))
        }
        if (i.motorOutputs.isNotEmpty()) {
            MonoLine("Motors  " + i.motorOutputs.joinToString(" "))
        }
    }
}

@Composable
private fun ParametersSection(i: DroneIntel) {
    if (i.params.isEmpty()) return
    val found = KEY_PARAMS.filterKeys { it in i.params }
    Section("Parameters", Icons.Filled.Tune, trailing = "${i.params.size}" + (i.paramTotal?.let { " / $it" } ?: "")) {
        if (found.isEmpty()) {
            Text("Key parameters not captured yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            StatGrid(*found.map { (key, label) -> label to formatParam(i.params.getValue(key)) }.toTypedArray(), columns = 2)
        }
    }
}

@Composable
private fun RadioLinkSection(
    drone: DiscoveredDrone?,
    intel: DroneIntel?,
    plan: com.sikw00f.android.protocol.FrequencyPlan?,
    scanMode: ScanMode?,
    now: Long
) {
    if (drone == null && intel == null) return
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val heard = drone?.channelHits?.entries?.sortedByDescending { it.value }?.take(6)?.map { it.key }?.sorted()
    Section("Radio link", Icons.Filled.CellTower, trailing = drone?.lastSeen?.let { ago(now - it) }) {
        StatGrid(
            "Packets" to drone?.packetsCount?.toString(),
            "MAVLink msgs" to (intel?.messageCounts?.values?.sum() ?: drone?.mavlinkCount)?.toString(),
            "Last hit" to drone?.let { channelLabel(it.lastChannel, plan) },
            "First seen" to drone?.firstSeen?.let { fmt.format(Date(it)) },
            "Heard on" to heard?.takeIf { it.isNotEmpty() }?.joinToString(", ") { ch ->
                plan?.takeIf { ch < it.channels }?.let { formatMHz(it.channelHz(ch), 2) } ?: "ch $ch"
            },
            columns = 2
        )
        if (drone?.rssiDbm != null) {
            val dbm = drone.rssiDbm
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBars(dbm, droneColor(drone.netId))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "$dbm dBm · ${signalWord(dbm)}" + when (drone.rssiTrend) {
                            1 -> " · getting closer"
                            -1 -> " · moving away"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "avg ${drone.rssiSlow?.toInt() ?: dbm} dBm · peak ${drone.rssiPeakDbm ?: dbm} dBm. " +
                            "Stronger usually means closer, but antennas and terrain matter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if ((drone?.packetsCount ?: 0) >= 10) {
            Text(
                "Signal strength needs the updated SiKW00F firmware (Connect → Flash firmware).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (drone?.linkIdle == true) {
            Text(
                "Link idle: the radio pair is connected and exchanging empty timing packets, but nothing is " +
                    "sending telemetry through it. Usually the flight controller is off, rebooting or not " +
                    "outputting on that port, or the ground station has disconnected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (drone?.noMavlink == true) {
            Text(
                "${drone.payloadBytes} bytes of data heard but none decode as MAVLink. The link may be " +
                    "AES-encrypted, or carry something other than MAVLink.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        if (scanMode == ScanMode.DEEP && drone != null && drone.params.isNotEmpty()) {
            Text("Drone radio settings (deep scan)", style = MaterialTheme.typography.labelLarge)
            StatGrid(*drone.params.entries.take(12).map { it.key to it.value }.toTypedArray(), columns = 2)
        }
    }
}

@Composable
private fun MessageStatsSection(i: DroneIntel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Section("Messages", Icons.Filled.BarChart, trailing = "${i.messageCounts.size} types") {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (expanded) "Hide breakdown" else "Show breakdown", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (expanded) {
            i.messageCounts.entries.sortedByDescending { it.value }.forEach { (name, count) ->
                MonoLine("${name.padEnd(24).take(24)} $count")
            }
        }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun Section(
    title: String,
    icon: ImageVector,
    trailing: String? = null,
    trailingColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                trailing?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = trailingColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

/** Label/value pairs laid out in [columns] columns; pairs with a null value are skipped. */
@Composable
private fun StatGrid(vararg stats: Pair<String, String?>, columns: Int = 3) {
    val shown = stats.filter { it.second != null }
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value!!, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MonoLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

@Composable
private fun severityColor(s: IntelEvent.Severity): Color = when (s) {
    IntelEvent.Severity.CRITICAL -> MaterialTheme.colorScheme.error
    IntelEvent.Severity.WARNING -> MaterialTheme.colorScheme.tertiary
    IntelEvent.Severity.NOTICE -> MaterialTheme.colorScheme.primary
    IntelEvent.Severity.INFO -> MaterialTheme.colorScheme.onSurface
}

// ---------------------------------------------------------------- formatting

private fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 2 -> "now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ago"
    }
}

private fun duration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private fun distance(m: Double): String = if (m >= 1000) "%.2f km".format(Locale.US, m / 1000) else "${m.toInt()} m"

private fun compass(deg: Float): String {
    val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val n = ((deg % 360) + 360) % 360
    return points[((n + 22.5f) / 45f).toInt() % 8]
}

private fun rangeBearing(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String {
    val r = FloatArray(2)
    Location.distanceBetween(fromLat, fromLon, toLat, toLon, r)
    return "${distance(r[0].toDouble())} ${compass(r[1])}"
}

private val utcFormat = SimpleDateFormat("HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private fun utc(ms: Long): String = utcFormat.format(Date(ms))

private fun formatParam(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.2f".format(Locale.US, v)
