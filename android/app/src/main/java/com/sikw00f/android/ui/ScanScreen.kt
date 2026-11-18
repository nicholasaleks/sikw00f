package com.sikw00f.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sikw00f.android.protocol.DiscoveredDrone
import com.sikw00f.android.protocol.DroneIntel
import com.sikw00f.android.protocol.DronePosition
import com.sikw00f.android.protocol.FrequencyPlan
import com.sikw00f.android.protocol.ScanMode
import com.sikw00f.android.protocol.ScanState
import com.sikw00f.android.protocol.formatMHz
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Scan time limits offered in the options menu (seconds; 0 = run until stopped). */
private val TIMEOUTS = listOf(0L to "No limit", 60L to "1 min", 300L to "5 min", 900L to "15 min", 1800L to "30 min")

/**
 * Map on top, one slim control bar, and the rest of the screen for the
 * detected-drone list. Scan options live in a menu behind the tune icon,
 * and the map can be folded away to give the list the whole screen.
 */
@Composable
fun ScanScreen(viewModel: AppViewModel, onDroneClick: (Int) -> Unit) {
    val scanState by viewModel.scanState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val frequencyPlan by viewModel.frequencyPlan.collectAsState()
    val dronePositions by viewModel.dronePositions.collectAsState()
    val intel by viewModel.intel.collectAsState()
    val phone by viewModel.phoneLocation.collectAsState()
    val lockState by viewModel.lockState.collectAsState()
    val autoLock by viewModel.autoLock.collectAsState()
    val eccSweep by viewModel.eccSweep.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val firmwareStatus by viewModel.firmwareStatus.collectAsState()
    val scanBlocked by viewModel.scanBlocked.collectAsState()
    val radioPrep by viewModel.radioPrep.collectAsState()
    val known by viewModel.knownDrones.collectAsState()
    val stockFirmware = firmwareStatus == FirmwareStatus.Stock
    val connected = connectionState is ConnectionState.Connected
    val canScan = connected && !stockFirmware

    var stopOnDetect by rememberSaveable { mutableStateOf(false) }
    var timeoutS by rememberSaveable { mutableStateOf(0L) }
    var showMap by rememberSaveable { mutableStateOf(true) }

    val running = scanState as? ScanState.Running
    val drones = when (val s = scanState) {
        is ScanState.Idle -> emptyMap()
        is ScanState.Running -> s.drones
        is ScanState.Stopped -> s.drones
    }
    val locked = lockState as? LockState.Locked
    val statusLabel = radioPrep ?: if (firmwareStatus == FirmwareStatus.Checking) "Checking firmware…" else when (val l = lockState) {
        is LockState.Locking -> "Locking on to NetID ${l.netId}…"
        is LockState.Locked -> if (running != null) "Locked on NetID ${l.netId}" else "Locked on NetID ${l.netId} · stopped"
        else -> when (val s = scanState) {
            is ScanState.Idle -> "Idle"
            is ScanState.Running -> "${s.mode.label()} scan running"
            is ScanState.Stopped -> "${s.mode.label()} scan ${s.reason}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnimatedVisibility(visible = showMap) {
            DeviceLocationMap(
                scanning = running != null,
                statusLabel = statusLabel,
                drones = dronePositions,
                intel = intel,
                onPhoneLocation = { viewModel.updatePhoneLocation(it.latitude, it.longitude, it.accuracy) },
                modifier = Modifier.fillMaxWidth().height(190.dp)
            )
        }

        if (!connected) {
            Text(
                "Connect to a radio first (Connect tab).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else if (stockFirmware || scanBlocked != null) {
            ScanBlockedBanner(stockFirmware, scanBlocked)
        }

        // ---------------------------------------------------------- control bar
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running != null || locked != null) {
                LiveStatus(
                    label = if (locked != null) "Locked · NetID ${locked.netId}" else statusLabel,
                    active = running != null,
                    lockedIcon = locked != null,
                    modifier = Modifier.weight(1f)
                )
                if (locked != null) {
                    OutlinedButton(
                        onClick = { viewModel.releaseLock() },
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(36.dp)
                    ) { Text("Release") }
                    Spacer(Modifier.width(6.dp))
                }
                if (running != null) {
                    Button(
                        onClick = { viewModel.stopScan() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                }
            } else {
                Button(
                    onClick = { viewModel.startPassiveScan(stopOnDetect, timeoutS * 1000) },
                    enabled = canScan,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Passive")
                }
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(
                    onClick = { viewModel.startDeepScan(stopOnDetect, timeoutS * 1000) },
                    enabled = canScan,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text("Deep") }
                Spacer(Modifier.width(10.dp))
                Text(
                    optionSummary(autoLock, stopOnDetect, eccSweep, timeoutS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            ScanOptionsMenu(
                autoLock = autoLock, onAutoLock = viewModel::setAutoLock,
                stopOnDetect = stopOnDetect, onStopOnDetect = { stopOnDetect = it },
                eccSweep = eccSweep, onEccSweep = viewModel::setEccSweep,
                timeoutS = timeoutS, onTimeout = { timeoutS = it },
                scanRunning = running != null
            )
        }

        // ---------------------------------------------------------- list
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Detected" + if (drones.isNotEmpty()) " · ${drones.size}" else "",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            frequencyPlan?.let { plan ->
                Text(
                    "${formatMHz(plan.lowHz, 1)}–${formatMHz(plan.highHz, 1)} MHz · ${plan.channels} ch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showMap = !showMap }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Map,
                    contentDescription = if (showMap) "Hide map" else "Show map",
                    tint = if (showMap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (drones.isEmpty()) {
            Text(
                if (running != null) "Listening…" else "No drones detected yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(drones.values.toList(), key = { it.netId }) { drone ->
                DroneCard(
                    drone = drone,
                    plan = frequencyPlan,
                    position = dronePositions[drone.netId],
                    intel = intel[drone.netId],
                    phone = phone,
                    known = known[drone.netId],
                    lock = when (val l = lockState) {
                        is LockState.Locking -> if (l.netId == drone.netId) DroneLock.LOCKING else DroneLock.NONE
                        is LockState.Locked -> if (l.netId == drone.netId) DroneLock.LOCKED else DroneLock.NONE
                        else -> DroneLock.NONE
                    },
                    lockEnabled = connected && !busy && lockState !is LockState.Locking,
                    onLock = { viewModel.lockOn(drone.netId) },
                    onClick = { onDroneClick(drone.netId) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------- control bar parts

@Composable
private fun LiveStatus(label: String, active: Boolean, lockedIcon: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (lockedIcon) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun optionSummary(autoLock: Boolean, stopOnDetect: Boolean, eccSweep: Boolean, timeoutS: Long): String =
    listOfNotNull(
        if (autoLock) "Auto-lock" else null,
        if (stopOnDetect) "Stop on detect" else null,
        if (eccSweep) "ECC sweep" else null,
        TIMEOUTS.firstOrNull { it.first == timeoutS && it.first > 0 }?.second
    ).joinToString(" · ")

/** Scan options folded into one menu so they don't take a row of the screen. */
@Composable
private fun ScanOptionsMenu(
    autoLock: Boolean, onAutoLock: (Boolean) -> Unit,
    stopOnDetect: Boolean, onStopOnDetect: (Boolean) -> Unit,
    eccSweep: Boolean, onEccSweep: (Boolean) -> Unit,
    timeoutS: Long, onTimeout: (Long) -> Unit,
    scanRunning: Boolean
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Tune, contentDescription = "Scan options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ToggleItem("Auto-lock", "Lock on to the first drone heard", autoLock, enabled = true) { onAutoLock(it) }
            ToggleItem("Stop on detect", "End the scan at the first hit", stopOnDetect, enabled = !scanRunning) { onStopOnDetect(it) }
            ToggleItem("ECC sweep", "Alternate to also hear ECC links", eccSweep, enabled = true) { onEccSweep(it) }
            HorizontalDivider()
            Text(
                "Time limit",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
            )
            TIMEOUTS.forEach { (seconds, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    enabled = !scanRunning,
                    leadingIcon = { CheckSlot(timeoutS == seconds) },
                    onClick = { onTimeout(seconds); open = false }
                )
            }
        }
    }
}

@Composable
private fun ToggleItem(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        enabled = enabled,
        leadingIcon = { CheckSlot(checked) },
        onClick = { onChange(!checked) }
    )
}

@Composable
private fun CheckSlot(checked: Boolean) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

// ---------------------------------------------------------------- drone card

private enum class DroneLock { NONE, LOCKING, LOCKED }

/**
 * Three tight lines: who (NetID, state, ECC, on-map pin), what (vehicle and
 * live telemetry), and where on the band (MHz, packets, last heard). Signal
 * and the lock action sit on the right; everything else is on the detail page.
 */
@Composable
private fun DroneCard(
    drone: DiscoveredDrone,
    plan: FrequencyPlan?,
    position: DronePosition?,
    intel: DroneIntel?,
    phone: Pair<Double, Double>?,
    known: com.sikw00f.android.alerts.KnownDrone?,
    lock: DroneLock,
    lockEnabled: Boolean,
    onLock: () -> Unit,
    onClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val border = if (lock == DroneLock.LOCKED) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder()

    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick), border = border) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(droneColor(drone.netId)))
                    Spacer(Modifier.width(7.dp))
                    val named = known?.name?.takeIf { it.isNotBlank() }
                    Text(named ?: "NetID ${drone.netId}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (named != null) Tag("${drone.netId}", muted)
                    if (known?.friendly == true) Tag("FRIENDLY", MaterialTheme.colorScheme.primary)
                    intel?.armed?.let { armed ->
                        Tag(if (armed) "ARMED" else "disarmed", if (armed) MaterialTheme.colorScheme.error else muted)
                    }
                    intel?.flightMode?.let { Tag(it, MaterialTheme.colorScheme.primary) }
                    if (drone.ecc) Tag("ECC", MaterialTheme.colorScheme.tertiary)
                    if (position != null) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Place, contentDescription = "On map", tint = droneColor(drone.netId), modifier = Modifier.size(14.dp))
                    }
                }
                val what = listOfNotNull(intel?.let(::identityLine), intel?.let { telemetryLine(it, phone) }).joinToString(" · ")
                if (what.isNotEmpty()) {
                    Text(what, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOf(
                        channelLabel(drone.lastChannel, plan),
                        "${drone.packetsCount} pkts",
                        timeFormat.format(Date(drone.lastSeen ?: drone.firstSeen))
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (drone.linkIdle) {
                    Text(
                        "Link idle: radios connected, no telemetry being sent",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted
                    )
                } else if (drone.noMavlink) {
                    Text(
                        "Data but no MAVLink: encrypted or another protocol",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                drone.rssiDbm?.let { dbm ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignalBars(dbm)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "$dbm" + when (drone.rssiTrend) { 1 -> "↑"; -1 -> "↓"; else -> "" },
                            style = MaterialTheme.typography.labelSmall,
                            color = muted
                        )
                    }
                }
                when (lock) {
                    DroneLock.LOCKED -> LockedMark()
                    DroneLock.LOCKING -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(30.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Locking", style = MaterialTheme.typography.labelMedium)
                    }
                    DroneLock.NONE -> OutlinedButton(
                        onClick = onLock,
                        enabled = lockEnabled,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp)
                    ) { Text("Lock", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Spacer(Modifier.width(6.dp))
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
}

@Composable
private fun LockedMark() {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Locked", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

// ---------------------------------------------------------------- banners & labels

@Composable
private fun ScanBlockedBanner(stock: Boolean, reason: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (stock) "Scanning disabled: stock firmware" else "Scan blocked",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                if (stock) "Stock SiK can't hear other networks. Flash the SiKW00F firmware from the Connect tab."
                else "Couldn't confirm SiKW00F firmware: $reason. Press scan to retry, or check on the Connect tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** "Quadrotor · ArduPilot · ArduCopter V4.5.1", from the vehicle's own heartbeat/STATUSTEXT. */
private fun identityLine(i: DroneIntel): String? =
    listOfNotNull(i.vehicleType, i.autopilot, i.firmware ?: i.frame).joinToString(" · ").ifEmpty { null }

/** Battery, altitude, range and bearing from you, GCS presence, in one compact line. */
private fun telemetryLine(i: DroneIntel, phone: Pair<Double, Double>?): String? {
    val parts = mutableListOf<String>()
    val b = i.battery
    if (b.remainingPct != null || b.voltageV != null) {
        parts += "Batt " + listOfNotNull(b.remainingPct?.let { "$it%" }, b.voltageV?.let { "%.1f V".format(Locale.US, it) }).joinToString(" ")
    }
    i.altRelM?.let { parts += "alt %.0f m".format(Locale.US, it) }
    val p = i.position
    if (p != null && phone != null) {
        val r = FloatArray(2)
        android.location.Location.distanceBetween(phone.first, phone.second, p.lat, p.lon, r)
        val d = r[0]
        parts += (if (d >= 1000) "%.1f km".format(Locale.US, d / 1000) else "${d.toInt()} m") + " ${compassPoint(r[1])}"
    }
    i.gcsLastSeen?.let { seen ->
        if (System.currentTimeMillis() - seen < 10_000) parts += "GCS online"
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

private fun compassPoint(bearing: Float): String {
    val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val n = ((bearing % 360) + 360) % 360
    return points[((n + 22.5f) / 45f).toInt() % 8]
}

/** "915.563 MHz · ch 1" when the hop table is known, otherwise just the channel. */
internal fun channelLabel(channel: Int?, plan: FrequencyPlan?): String = when {
    channel == null -> "No hits yet"
    plan == null || channel >= plan.channels -> "Channel $channel"
    else -> "${formatMHz(plan.channelHz(channel))} MHz · ch $channel"
}

private fun ScanMode.label() = when (this) {
    ScanMode.PASSIVE -> "Passive"
    ScanMode.DEEP -> "Deep"
}
