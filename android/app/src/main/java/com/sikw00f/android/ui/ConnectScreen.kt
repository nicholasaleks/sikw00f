package com.sikw00f.android.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.sikw00f.android.protocol.FrequencyPlan
import com.sikw00f.android.protocol.formatMHz
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.sikw00f.android.protocol.FlashEvent
import com.sikw00f.android.transport.listUsbSerialDrivers

private data class FirmwareOption(val label: String, val assetFile: String, val description: String)

private val FIRMWARE_OPTIONS = listOf(
    FirmwareOption(
        label = "Modded",
        assetFile = "hm_trp_modded.ihx",
        description = "SiKW00F build (nicholasaleks/SiK): NetID bypass, silent stats frames and TDM sync for scanning and eavesdropping."
    ),
    FirmwareOption(
        label = "Stock",
        assetFile = "hm_trp_stock.ihx",
        description = "Upstream ArduPilot/SiK: resets the radio back to normal operation."
    )
)

/**
 * Single place to get a radio ready: pick a USB device, connect, then verify
 * or flash firmware. The device picker is shown only while disconnected and
 * the firmware tools only once connected, so the screen never shows both.
 */
@Composable
fun ConnectScreen(viewModel: AppViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val flashing by viewModel.flashing.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        StatusCard(
            state = connectionState,
            canDisconnect = !flashing,
            onDisconnect = viewModel::disconnect
        )

        val replugNeeded by viewModel.replugNeeded.collectAsState()
        if (replugNeeded) ReplugBanner(connected = connectionState is ConnectionState.Connected)

        if (connectionState is ConnectionState.Connected) {
            FirmwareSection(viewModel)
            RadioSection(viewModel)
        } else {
            DeviceSection(viewModel, connecting = connectionState is ConnectionState.Connecting)
        }
    }
}

// ---------------------------------------------------------------- status

@Composable
private fun StatusCard(state: ConnectionState, canDisconnect: Boolean, onDisconnect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container = when (state) {
        is ConnectionState.Connected -> colors.primaryContainer
        is ConnectionState.Error, is ConnectionState.Lost -> colors.errorContainer
        else -> colors.surfaceVariant
    }
    val (icon, title, subtitle) = when (state) {
        ConnectionState.Disconnected -> Triple(Icons.Filled.UsbOff, "Not connected", "Plug in a SiK radio over USB-OTG")
        ConnectionState.Connecting -> Triple(null, "Connecting…", "Opening serial port")
        is ConnectionState.Connected -> Triple(Icons.Filled.Usb, "Connected", "${state.label} · ${state.baud} baud")
        is ConnectionState.Error -> Triple(Icons.Filled.ErrorOutline, "Connection failed", state.message)
        is ConnectionState.Lost -> Triple(Icons.Filled.UsbOff, state.message, "Plug the radio back in and tap Connect")
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon == null) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (state is ConnectionState.Connected) {
                TextButton(onClick = onDisconnect, enabled = canDisconnect) { Text("Disconnect") }
            }
        }
    }
}

/** Shown after a successful flash until the radio has been re-plugged and reconnected. */
@Composable
private fun ReplugBanner(connected: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Unplug the radio and plug it back in",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    if (connected) "The new firmware is installed. Re-plug the radio so it starts cleanly, then tap Connect."
                    else "Then tap Connect below. The app re-checks the firmware and reads its settings automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

// ---------------------------------------------------------------- devices

@Composable
private fun DeviceSection(viewModel: AppViewModel, connecting: Boolean) {
    val context = LocalContext.current
    var baud by rememberSaveable { mutableStateOf("57600") }
    var drivers by remember { mutableStateOf<List<UsbSerialDriver>>(listUsbSerialDrivers(context)) }

    // Keep the list current as radios are plugged in or pulled out.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                drivers = listUsbSerialDrivers(context)
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("USB devices") {
            IconButton(onClick = { drivers = listUsbSerialDrivers(context) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh USB devices")
            }
        }

        OutlinedTextField(
            value = baud,
            onValueChange = { baud = it.filter(Char::isDigit) },
            label = { Text("Baud rate") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        if (drivers.isEmpty()) {
            Text(
                "No USB serial devices found. Connect the radio, then tap refresh.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        drivers.forEach { driver ->
            DeviceRow(
                driver = driver,
                enabled = !connecting,
                onConnect = { viewModel.connectUsb(context, driver, baud.toIntOrNull() ?: 57600) }
            )
        }
    }
}

@Composable
private fun DeviceRow(driver: UsbSerialDriver, enabled: Boolean, onConnect: () -> Unit) {
    val device = driver.device
    // productName can throw on some OEM builds before USB permission is granted.
    val name = runCatching { device.productName }.getOrNull() ?: "USB serial device"

    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "%04X:%04X · %s".format(device.vendorId, device.productId, device.deviceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = onConnect, enabled = enabled) { Text("Connect") }
        }
    }
}

// ---------------------------------------------------------------- firmware

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirmwareSection(viewModel: AppViewModel) {
    val context = LocalContext.current
    val status by viewModel.firmwareStatus.collectAsState()
    val flashing by viewModel.flashing.collectAsState()
    val events by viewModel.flashEvents.collectAsState()
    val busy by viewModel.busy.collectAsState()

    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var eraseParams by rememberSaveable { mutableStateOf(false) }
    val selected = FIRMWARE_OPTIONS[selectedIndex]
    val showFlash = expanded || flashing
    val chevronRotation by animateFloatAsState(if (showFlash) 180f else 0f, label = "chevron")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Firmware")

        OutlinedCard(Modifier.fillMaxWidth()) {
            // Current firmware + check action
            FirmwareStatusRow(
                status = status,
                checkEnabled = !busy && !flashing,
                onCheck = viewModel::checkFirmwareModification
            )

            HorizontalDivider()

            // Collapsible flash panel
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !flashing) { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text("Flash firmware", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (showFlash) "Collapse" else "Expand",
                    modifier = Modifier.rotate(chevronRotation)
                )
            }

            AnimatedVisibility(visible = showFlash) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        FIRMWARE_OPTIONS.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = index == selectedIndex,
                                onClick = { selectedIndex = index },
                                enabled = !flashing,
                                shape = SegmentedButtonDefaults.itemShape(index, FIRMWARE_OPTIONS.size)
                            ) { Text(option.label) }
                        }
                    }

                    Text(
                        selected.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Reset parameters to defaults",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = eraseParams, onCheckedChange = { eraseParams = it }, enabled = !flashing)
                    }

                    Button(
                        onClick = { viewModel.flashFirmware(context, selected.assetFile, eraseParams) },
                        enabled = !flashing && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (flashing) "Flashing…" else "Flash ${selected.label.lowercase()} firmware") }

                    FlashProgress(events)
                }
            }
        }
    }
}

@Composable
private fun FirmwareStatusRow(status: FirmwareStatus, checkEnabled: Boolean, onCheck: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    data class View(val icon: ImageVector?, val tint: Color, val title: String, val subtitle: String)

    val view = when (status) {
        FirmwareStatus.Unknown -> View(Icons.AutoMirrored.Filled.HelpOutline, colors.onSurfaceVariant, "Not checked", "Check whether the SiKW00F mod is installed")
        FirmwareStatus.Checking -> View(null, colors.primary, "Checking…", "Querying ATI5")
        FirmwareStatus.Modded -> View(Icons.Filled.CheckCircle, colors.primary, "SiKW00F modded", "PROMISCUOUS_MODE detected")
        FirmwareStatus.Stock -> View(Icons.Filled.Warning, colors.tertiary, "Stock firmware", "Flash the modded build to scan and eavesdrop")
        FirmwareStatus.NoResponse -> View(Icons.Filled.ErrorOutline, colors.error, "No response", "Radio didn't answer the ATI5 query")
        is FirmwareStatus.Error -> View(Icons.Filled.ErrorOutline, colors.error, "Check failed", status.message)
    }

    Row(
        Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (view.icon == null) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
        } else {
            Icon(view.icon, contentDescription = null, tint = view.tint)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(view.title, style = MaterialTheme.typography.titleSmall)
            Text(view.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        TextButton(onClick = onCheck, enabled = checkEnabled) {
            Text(if (status == FirmwareStatus.Unknown) "Check" else "Recheck")
        }
    }
}

/** Compact progress: a bar plus the latest stage line. The full log lives in the Logs tab. */
@Composable
private fun FlashProgress(events: List<FlashEvent>) {
    if (events.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val failed = events.lastOrNull { it is FlashEvent.Failed } as? FlashEvent.Failed
    val done = events.any { it == FlashEvent.Done }

    when {
        failed != null -> ResultLine(Icons.Filled.ErrorOutline, colors.error, failed.message)
        done -> ResultLine(Icons.Filled.CheckCircle, colors.primary, "Flash complete. Unplug the radio and plug it back in.")
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val progress = events.lastOrNull { it is FlashEvent.Progress } as? FlashEvent.Progress
            val stage = events.lastOrNull { it is FlashEvent.Stage } as? FlashEvent.Stage
            if (progress != null && progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.count.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${progress.stage} · ${progress.count}/${progress.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            stage?.let {
                Text(it.message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResultLine(icon: ImageVector, tint: Color, message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

// ---------------------------------------------------------------- radio parameters

private val AIR_SPEEDS = listOf(2, 4, 8, 16, 19, 24, 32, 48, 64, 96, 128, 192, 250)
private val TX_POWERS = listOf(1, 2, 5, 8, 11, 14, 17, 20)

/**
 * The connected radio's live parameters: a glanceable summary, the
 * promiscuous-mode switch, and (collapsed) an editor that writes every
 * change in one save + reboot, plus the raw ATI5 list.
 */
@Composable
private fun RadioSection(viewModel: AppViewModel) {
    val params by viewModel.radioParams.collectAsState()
    val plan by viewModel.frequencyPlan.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val flashing by viewModel.flashing.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val scanning = scanState is com.sikw00f.android.protocol.ScanState.Running
    val enabled = !busy && !flashing

    val baseline by viewModel.baseline.collectAsState()
    val lockState by viewModel.lockState.collectAsState()
    val diff = remember(baseline, params) {
        val base = baseline ?: emptyMap()
        val now = params ?: emptyMap()
        base.mapNotNull { (k, v) -> now[k]?.trim()?.takeIf { it != v }?.let { Triple(k, it, v) } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (diff.isNotEmpty()) {
            RestoreBanner(
                diff = diff,
                locked = lockState is LockState.Locked,
                enabled = enabled,
                onRestore = viewModel::restoreRadio,
                onKeep = viewModel::discardBaseline
            )
        }
        SectionHeader("Radio") {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            IconButton(onClick = viewModel::checkFirmwareModification, enabled = enabled) {
                Icon(Icons.Filled.Refresh, contentDescription = "Re-read radio parameters")
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            val p = params
            if (p == null) {
                Text(
                    if (busy) "Reading parameters…" else "Parameters not read yet. Tap refresh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                RadioParamsBody(p, plan, enabled, scanning, viewModel)
            }
        }
    }
}

@Composable
private fun RadioParamsBody(
    p: Map<String, String>,
    plan: FrequencyPlan?,
    enabled: Boolean,
    scanning: Boolean,
    viewModel: AppViewModel
) {
    var editOpen by rememberSaveable { mutableStateOf(false) }
    var allOpen by rememberSaveable { mutableStateOf(false) }
    Column {
            ParamSummary(p, plan)

            if (p.containsKey("PROMISCUOUS_MODE")) {
                HorizontalDivider()
                PromiscuousRow(
                    on = p["PROMISCUOUS_MODE"]?.trim() == "1",
                    enabled = enabled,
                    onChange = viewModel::setPromiscuousMode
                )
            }

            HorizontalDivider()
            ExpandableRow(Icons.Filled.Edit, "Edit parameters", editOpen, enabled = true) { editOpen = !editOpen }
            AnimatedVisibility(visible = editOpen) {
                ParamEditor(
                    params = p,
                    enabled = enabled,
                    scanning = scanning,
                    onSave = viewModel::applyRadioParams,
                    onReboot = viewModel::rebootRadio
                )
            }

            HorizontalDivider()
            ExpandableRow(Icons.AutoMirrored.Filled.ListAlt, "All parameters (${p.size})", allOpen, enabled = true) { allOpen = !allOpen }
            AnimatedVisibility(visible = allOpen) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                    p.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(k, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
    }
}

/**
 * The radio still carries settings SiKW00F changed (a lock, ECC sweep or
 * promiscuous mode), possibly from an earlier session. One tap writes the
 * saved originals back in a single save + reboot.
 */
@Composable
private fun RestoreBanner(
    diff: List<Triple<String, String, String>>,
    locked: Boolean,
    enabled: Boolean,
    onRestore: () -> Unit,
    onKeep: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (locked) "Radio is retuned for a lock" else "Radio isn't on its own settings",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                diff.take(4).joinToString(" · ") { (k, now, orig) -> "${paramLabel(k)} $now → $orig" } +
                    if (diff.size > 4) " · +${diff.size - 4} more" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Restore puts back the settings it had before SiKW00F changed them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onKeep, enabled = enabled) { Text("Keep") }
                TextButton(onClick = onRestore, enabled = enabled) { Text("Restore") }
            }
        }
    }
}

private fun paramLabel(key: String): String = when (key) {
    "NETID" -> "NetID"
    "PROMISCUOUS_MODE" -> "Promiscuous"
    "NUM_CHANNELS" -> "Channels"
    "MIN_FREQ" -> "Min kHz"
    "MAX_FREQ" -> "Max kHz"
    "AIR_SPEED" -> "Air"
    "TXPOWER" -> "TX"
    else -> key.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
}

@Composable
private fun ParamSummary(p: Map<String, String>, plan: FrequencyPlan?) {
    val minKHz = p["MIN_FREQ"]?.toLongOrNull()
    val maxKHz = p["MAX_FREQ"]?.toLongOrNull()
    val band = if (minKHz != null && maxKHz != null) "${trimMHz(minKHz)}–${trimMHz(maxKHz)}" else "—"
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("NetID", p["NETID"] ?: "—", Modifier.weight(0.8f))
            StatTile("Band MHz", band, Modifier.weight(1.6f))
            StatTile("Channels", p["NUM_CHANNELS"] ?: "—", Modifier.weight(0.9f))
        }
        val details = listOfNotNull(
            p["AIR_SPEED"]?.let { "Air $it kbps" },
            p["TXPOWER"]?.let { "TX $it dBm" },
            plan?.let { "step ${it.stepHz / 1000} kHz" },
            p["ECC"]?.let { if (it == "1") "ECC on" else null },
            p["SERIAL_SPEED"]?.let { "serial ${it}k" }
        )
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun trimMHz(kHz: Long): String {
    val mhz = kHz / 1000.0
    return if (kHz % 1000 == 0L) mhz.toLong().toString() else formatMHz(kHz * 1000, 3).trimEnd('0').trimEnd('.')
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PromiscuousRow(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.SettingsInputAntenna, contentDescription = null,
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Promiscuous mode", style = MaterialTheme.typography.titleSmall)
            Text(
                if (on) "On · hears every NetID nearby" else "Off · hears only its own NetID. Scans switch it on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ExpandableRow(icon: ImageVector, title: String, open: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ExpandMore, contentDescription = if (open) "Collapse" else "Expand", modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun ParamEditor(
    params: Map<String, String>,
    enabled: Boolean,
    scanning: Boolean,
    onSave: (Map<String, String>) -> Unit,
    onReboot: () -> Unit
) {
    // Re-seeded whenever the radio reports new values (after a save, lock or re-read).
    var netId by remember(params) { mutableStateOf(params["NETID"].orEmpty()) }
    var channels by remember(params) { mutableStateOf(params["NUM_CHANNELS"].orEmpty()) }
    var minFreq by remember(params) { mutableStateOf(params["MIN_FREQ"].orEmpty()) }
    var maxFreq by remember(params) { mutableStateOf(params["MAX_FREQ"].orEmpty()) }
    var airSpeed by remember(params) { mutableStateOf(params["AIR_SPEED"].orEmpty()) }
    var txPower by remember(params) { mutableStateOf(params["TXPOWER"].orEmpty()) }

    val edited = linkedMapOf(
        "NETID" to netId, "NUM_CHANNELS" to channels, "MIN_FREQ" to minFreq,
        "MAX_FREQ" to maxFreq, "AIR_SPEED" to airSpeed, "TXPOWER" to txPower
    )
    val changes = edited.filter { (k, v) -> v.isNotBlank() && params.containsKey(k) && v.trim() != params[k]?.trim() }
    val chOk = channels.toIntOrNull()?.let { it in 1..50 } ?: false
    val freqOk = (minFreq.toLongOrNull() ?: 0) > 0 && (maxFreq.toLongOrNull() ?: 0) > (minFreq.toLongOrNull() ?: 0)
    val valid = netId.toIntOrNull() != null && chOk && freqOk

    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("NetID", netId, { netId = it }, Modifier.weight(1f), changed = "NETID" in changes)
            NumberField("Channels", channels, { channels = it }, Modifier.weight(1f), changed = "NUM_CHANNELS" in changes, isError = !chOk)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("Min kHz", minFreq, { minFreq = it }, Modifier.weight(1f), changed = "MIN_FREQ" in changes, isError = !freqOk)
            NumberField("Max kHz", maxFreq, { maxFreq = it }, Modifier.weight(1f), changed = "MAX_FREQ" in changes, isError = !freqOk)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (params.containsKey("AIR_SPEED")) {
                ChoiceField("Air kbps", airSpeed, choicesWith(AIR_SPEEDS, airSpeed), { airSpeed = it }, Modifier.weight(1f), "AIR_SPEED" in changes)
            }
            if (params.containsKey("TXPOWER")) {
                ChoiceField("TX dBm", txPower, choicesWith(TX_POWERS, txPower), { txPower = it }, Modifier.weight(1f), "TXPOWER" in changes)
            }
        }

        Text(
            when {
                changes.isEmpty() -> "Edit a value to save it to the radio."
                scanning -> "Saving stops the running scan, writes ${changes.size} change(s) and reboots the radio once."
                else -> "Writes ${changes.size} change(s) and reboots the radio once (~3 s)."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onReboot, enabled = enabled) { Text("Reboot") }
            Spacer(Modifier.weight(1f))
            if (changes.isNotEmpty()) {
                TextButton(onClick = {
                    netId = params["NETID"].orEmpty(); channels = params["NUM_CHANNELS"].orEmpty()
                    minFreq = params["MIN_FREQ"].orEmpty(); maxFreq = params["MAX_FREQ"].orEmpty()
                    airSpeed = params["AIR_SPEED"].orEmpty(); txPower = params["TXPOWER"].orEmpty()
                }) { Text("Revert") }
            }
            Button(onClick = { onSave(changes) }, enabled = enabled && valid && changes.isNotEmpty()) {
                Text("Save to radio")
            }
        }
    }
}

private fun choicesWith(base: List<Int>, current: String): List<String> {
    val all = base.map { it.toString() }
    return if (current.isBlank() || current in all) all else (all + current).sortedBy { it.toIntOrNull() ?: 0 }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    changed: Boolean,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(7)) },
        label = { Text(if (changed) "$label •" else label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(
    label: String,
    value: String,
    choices: List<String>,
    onChange: (String) -> Unit,
    modifier: Modifier,
    changed: Boolean
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(if (changed) "$label •" else label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { c ->
                DropdownMenuItem(text = { Text(c) }, onClick = { onChange(c); open = false })
            }
        }
    }
}

// ---------------------------------------------------------------- shared

@Composable
private fun SectionHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
    }
}
