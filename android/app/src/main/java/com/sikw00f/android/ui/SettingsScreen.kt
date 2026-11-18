package com.sikw00f.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sikw00f.android.alerts.AlertTone
import com.sikw00f.android.alerts.CotAffiliation
import com.sikw00f.android.alerts.TakTransport
import com.sikw00f.android.alerts.WebhookFormat

private val COOLDOWNS = listOf(30 to "30 s", 60 to "1 min", 300 to "5 min", 900 to "15 min")
private val STALE_TIMES = listOf(30 to "30 s", 60 to "1 min", 300 to "5 min")
private val SIGNAL_THRESHOLDS = listOf(-90, -80, -70, -60)

/** What the app does when a scan detects something, and where detections are shared. */
@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val settings by viewModel.alertSettings.collectAsState()
    val t = settings.triggers

    var notifDenied by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifDenied = !granted
        if (granted) viewModel.updateAlertSettings { it.copy(notification = it.notification.copy(enabled = true)) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        // ------------------------------------------------------------ triggers
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header("Alert when")
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    TriggerRow("A new drone is detected", t.newDrone) { v -> viewModel.updateAlertSettings { it.copy(triggers = it.triggers.copy(newDrone = v)) } }
                    TriggerRow("A drone arms its motors", t.armed) { v -> viewModel.updateAlertSettings { it.copy(triggers = it.triggers.copy(armed = v)) } }
                    TriggerRow("A drone's GPS position is first heard", t.firstGps) { v -> viewModel.updateAlertSettings { it.copy(triggers = it.triggers.copy(firstGps = v)) } }
                    TriggerRow("A drone comes within range of you", t.proximity) { v -> viewModel.updateAlertSettings { it.copy(triggers = it.triggers.copy(proximity = v)) } }
                    AnimatedVisibility(t.proximity) {
                        IntField(
                            "Radius (m)", t.proximityM,
                            Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth(), 10..50_000
                        ) { v -> viewModel.updateAlertSettings { it.copy(triggers = it.triggers.copy(proximityM = v)) } }
                    }
                    TriggerRow("A drone's signal gets strong (likely close, works without GPS)", t.strongSignal) { v -> viewModel.updateAlertSettings { it.copy(triggers = it.triggers.copy(strongSignal = v)) } }
                    AnimatedVisibility(t.strongSignal) {
                        Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Segments(SIGNAL_THRESHOLDS.map { it to "$it dBm" }, t.strongSignalDbm) { v ->
                                viewModel.updateAlertSettings { it.copy(triggers = it.triggers.copy(strongSignalDbm = v)) }
                            }
                            Text(
                                "Alerts when the signal is stronger than this. Closer to 0 = it must be nearer before alerting. Needs the updated firmware.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TriggerRow("Critical messages (failsafe, emergency)", t.critical) { v -> viewModel.updateAlertSettings { it.copy(triggers = it.triggers.copy(critical = v)) } }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Repeat for the same drone at most every", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Segments(COOLDOWNS, t.cooldownS) { v -> viewModel.updateAlertSettings { it.copy(triggers = it.triggers.copy(cooldownS = v)) } }
                    }
                }
            }
        }

        // ------------------------------------------------------------ actions
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header("Actions") {
                TextButton(onClick = viewModel::sendTestAlert, enabled = settings.anyActionEnabled) { Text("Send test") }
            }

            val n = settings.notification
            ActionCard(
                Icons.Filled.Notifications, "Push notification",
                if (notifDenied) "Notification permission denied in system settings" else "Alert in the notification shade",
                n.enabled,
                onToggle = { on ->
                    if (on && !viewModel.canPostNotifications() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.updateAlertSettings { it.copy(notification = it.notification.copy(enabled = on)) }
                    }
                }
            ) {
                SwitchRow("Pop up over other apps", n.headsUp) { v -> viewModel.updateAlertSettings { it.copy(notification = it.notification.copy(headsUp = v)) } }
                SwitchRow("Include drone coordinates", n.includePosition) { v -> viewModel.updateAlertSettings { it.copy(notification = it.notification.copy(includePosition = v)) } }
            }

            val s = settings.sound
            ActionCard(
                Icons.AutoMirrored.Filled.VolumeUp, "Sound & vibration",
                when {
                    !s.playSound && s.vibrate -> "Vibrate only"
                    s.playSound && !s.vibrate -> "Sound only"
                    !s.playSound && !s.vibrate -> "Turn on sound or vibrate"
                    else -> "Audible and haptic alert"
                },
                s.enabled,
                onToggle = { on -> viewModel.updateAlertSettings { it.copy(sound = it.sound.copy(enabled = on)) } }
            ) {
                SwitchRow("Sound", s.playSound) { v -> viewModel.updateAlertSettings { it.copy(sound = it.sound.copy(playSound = v)) } }
                AnimatedVisibility(s.playSound) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Segments(AlertTone.entries.map { it to it.label }, s.tone) { v -> viewModel.updateAlertSettings { it.copy(sound = it.sound.copy(tone = v)) } }
                        SwitchRow("Ring even when on silent", s.playOnSilent) { v -> viewModel.updateAlertSettings { it.copy(sound = it.sound.copy(playOnSilent = v)) } }
                    }
                }
                SwitchRow("Vibrate", s.vibrate) { v -> viewModel.updateAlertSettings { it.copy(sound = it.sound.copy(vibrate = v)) } }
            }

            val w = settings.webhook
            ActionCard(Icons.Filled.Link, "Webhook", if (w.enabled && w.url.isBlank()) "Add a URL to start sending" else "POST each alert to a URL", w.enabled,
                onToggle = { on -> viewModel.updateAlertSettings { it.copy(webhook = it.webhook.copy(enabled = on)) } }
            ) {
                OutlinedTextField(
                    value = w.url,
                    onValueChange = { v -> viewModel.updateAlertSettings { it.copy(webhook = it.webhook.copy(url = v.trim())) } },
                    label = { Text("URL") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    isError = w.url.isNotBlank() && !w.url.startsWith("http"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Segments(WebhookFormat.entries.map { it to it.label }, w.format) { v -> viewModel.updateAlertSettings { it.copy(webhook = it.webhook.copy(format = v)) } }
                if (w.format == WebhookFormat.JSON) {
                    OutlinedTextField(
                        value = w.authorization,
                        onValueChange = { v -> viewModel.updateAlertSettings { it.copy(webhook = it.webhook.copy(authorization = v)) } },
                        label = { Text("Authorization header (optional)") },
                        placeholder = { Text("Bearer …") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SwitchRow("Include full telemetry", w.includeTelemetry) { v -> viewModel.updateAlertSettings { it.copy(webhook = it.webhook.copy(includeTelemetry = v)) } }
                }
            }
        }

        // ------------------------------------------------------------ known drones
        KnownDronesSection(viewModel)

        // ------------------------------------------------------------ TAK
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header("TAK")
            val k = settings.tak
            var takNote by remember { mutableStateOf<String?>(null) }
            ActionCard(Icons.Filled.Map, "Cursor on Target", "Stream located drones to ATAK, WinTAK, iTAK or TAK Server", k.enabled,
                onToggle = { on -> viewModel.updateAlertSettings { it.copy(tak = it.tak.copy(enabled = on)) } }
            ) {
                Segments(TakTransport.entries.map { it to it.label }, k.transport) { v ->
                    viewModel.updateAlertSettings { it.copy(tak = it.tak.copy(transport = v, host = v.defaultHost, port = v.defaultPort)) }
                }
                Text(
                    when (k.transport) {
                        TakTransport.MULTICAST -> "SA multicast: every TAK device on the same Wi-Fi or mesh sees the tracks."
                        TakTransport.LOCAL -> "ATAK running on this phone, via its default UDP input."
                        TakTransport.UNICAST -> "A TAK Server or a single device, over plain UDP."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = k.host,
                        onValueChange = { v -> viewModel.updateAlertSettings { it.copy(tak = it.tak.copy(host = v.trim())) } },
                        label = { Text("Host") },
                        singleLine = true,
                        isError = k.host.isBlank(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(2f)
                    )
                    key(k.transport) {
                        IntField("Port", k.port, Modifier.weight(1f), 1..65535) { v -> viewModel.updateAlertSettings { it.copy(tak = it.tak.copy(port = v)) } }
                    }
                }
                Text("Mark drones as", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Segments(CotAffiliation.entries.filter { it != CotAffiliation.FRIENDLY }.map { it to it.label }, k.affiliation) { v -> viewModel.updateAlertSettings { it.copy(tak = it.tak.copy(affiliation = v)) } }
                Text("Marker goes stale after", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Segments(STALE_TIMES, k.staleS) { v -> viewModel.updateAlertSettings { it.copy(tak = it.tak.copy(staleS = v)) } }
                SwitchRow("Also send HOME and GCS markers", k.includeHomeAndGcs) { v -> viewModel.updateAlertSettings { it.copy(tak = it.tak.copy(includeHomeAndGcs = v)) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        takNote = if (viewModel.sendTakTest()) "Test marker sent at your position." else "Your location isn't known yet; open the Scan tab map first."
                    }, enabled = k.host.isNotBlank()) { Text("Send test marker") }
                    Spacer(Modifier.width(12.dp))
                    takNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Labelled NetIDs: your own aircraft go here so they stop raising alerts. */
@Composable
private fun KnownDronesSection(viewModel: AppViewModel) {
    val known by viewModel.knownDrones.collectAsState()
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header("Known drones") {
            TextButton(onClick = { adding = true }) { Text("Add") }
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            if (known.isEmpty()) {
                Text(
                    "Label your own aircraft by NetID so they don't trigger alerts or auto-lock. " +
                        "You can also label a drone from its detail page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Column(Modifier.padding(vertical = 4.dp)) {
                    known.values.sortedBy { it.netId }.forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().clickable { editing = d.netId }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(droneColor(d.netId))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull("NetID ${d.netId}", d.note.takeIf { it.isNotBlank() }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                if (d.friendly) "Friendly" else "Watch",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (d.friendly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        KnownDroneDialog(
            netId = null, existing = null,
            onSave = { viewModel.saveKnownDrone(it); adding = false },
            onRemove = null,
            onDismiss = { adding = false }
        )
    }
    editing?.let { id ->
        KnownDroneDialog(
            netId = id, existing = known[id],
            onSave = { viewModel.saveKnownDrone(it); editing = null },
            onRemove = { viewModel.removeKnownDrone(id); editing = null },
            onDismiss = { editing = null }
        )
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun Header(title: String, action: (@Composable () -> Unit)? = null) {
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

@Composable
private fun TriggerRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A toggleable action; its options slide open underneath only while it's on. */
@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle(!enabled) }
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        AnimatedVisibility(visible = enabled) {
            Column {
                HorizontalDivider()
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) { content() }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {}
            ) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        }
    }
}

/** Number field that keeps its own text while typing and commits only valid values. */
@Composable
private fun IntField(label: String, value: Int, modifier: Modifier, range: IntRange, onCommit: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter(Char::isDigit).take(6)
            text.toIntOrNull()?.takeIf { it in range }?.let(onCommit)
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.toIntOrNull()?.let { it !in range } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
