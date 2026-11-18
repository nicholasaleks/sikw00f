package com.sikw00f.android.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sikw00f.android.LogBus
import com.sikw00f.android.protocol.DroneIntel
import com.sikw00f.android.protocol.DronePosition
import com.sikw00f.android.protocol.GeoFix
import com.sikw00f.android.sessions.Session
import com.sikw00f.android.sessions.SessionDrone
import com.sikw00f.android.sessions.SessionStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.US)
private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

/** Past scanning sessions, newest first (shown under the Logs tab). */
@Composable
fun SessionList(onOpen: (String) -> Unit) {
    val sessions by produceState<List<Session>?>(null) {
        value = withContext(Dispatchers.IO) { SessionStore.list() }
    }
    val list = sessions
    when {
        list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> Text(
            "No sessions recorded yet. Every app run is saved as a session with its detections, tracks and log.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { s -> SessionCard(s) { onOpen(s.id) } }
        }
    }
}

@Composable
private fun SessionCard(s: Session, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${dayFormat.format(Date(s.start))} · ${timeFormat.format(Date(s.start))}–${timeFormat.format(Date(s.end))}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (s.isCurrent) Text("Current", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                else Text(durationLabel(s.end - s.start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.drones.isEmpty()) {
                Text("No drones detected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    s.drones.take(8).forEach { d ->
                        Box(Modifier.size(8.dp).clip(CircleShape).background(droneColor(d.netId)))
                        Spacer(Modifier.width(4.dp))
                        Text("${d.netId}", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(10.dp))
                    }
                    if (s.drones.size > 8) Text("+${s.drones.size - 8}", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    listOfNotNull(
                        "${s.drones.size} drone${if (s.drones.size == 1) "" else "s"}",
                        s.positionCount.takeIf { it > 0 }?.let { "$it positions" },
                        s.locks.takeIf { it > 0 }?.let { "$it lock${if (it == 1) "" else "s"}" },
                        s.alerts.takeIf { it > 0 }?.let { "$it alert${if (it == 1) "" else "s"}" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** One session: every drone's track on a map, per-drone details, export and delete. */
@Composable
fun SessionDetailScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val session by produceState<Session?>(null, reload) {
        value = withContext(Dispatchers.IO) { SessionStore.load(id) }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                val s = session
                Text(s?.let { dayFormat.format(Date(it.start)) } ?: "Session", style = MaterialTheme.typography.titleLarge)
                s?.let {
                    Text(
                        "${clockFormat.format(Date(it.start))}–${clockFormat.format(Date(it.end))} · ${durationLabel(it.end - it.start)}" +
                            (if (it.isCurrent) " · in progress" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (session?.isCurrent == false) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete session") }
            }
        }

        val loaded = session
        if (loaded == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            SessionBody(loaded, context, scope)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this session?") },
            text = { Text("Its detections log and text log are removed from the phone. Export first if you need them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    session?.let { SessionStore.delete(it) }
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SessionDroneCard(d: SessionDrone) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = d.events.isNotEmpty()) { expanded = !expanded }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(droneColor(d.netId)))
                Spacer(Modifier.width(8.dp))
                Text("NetID ${d.netId}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (d.armedSeen) Text("ARMED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                if (d.ecc) {
                    Spacer(Modifier.width(8.dp))
                    Text("ECC", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            listOfNotNull(d.vehicleType, d.autopilot, d.firmware).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (d.freqsMHz.isNotEmpty()) {
                Text(freqSummary(d.freqsMHz), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                listOfNotNull(
                    "${clockFormat.format(Date(d.firstSeen))}–${clockFormat.format(Date(d.lastSeen))}",
                    d.packets?.let { "$it packets" },
                    d.track.size.takeIf { it > 0 }?.let { "$it positions" },
                    d.maxAltM?.let { "max %.0f m".format(Locale.US, it) },
                    d.minDistanceM?.let { "closest ${distanceLabel(it)}" },
                    d.rssiPeakDbm?.let { "peak $it dBm" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                d.events.asReversed().forEach { (t, text) ->
                    Text("${clockFormat.format(Date(t))}  $text", style = MaterialTheme.typography.bodySmall)
                }
            } else if (d.events.isNotEmpty()) {
                Text("${d.events.size} events · tap to show", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SessionBody(s: Session, context: Context, scope: kotlinx.coroutines.CoroutineScope) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val tracked = s.drones.filter { it.track.isNotEmpty() }
            DeviceLocationMap(
                scanning = false,
                statusLabel = if (tracked.isEmpty()) "No positions recorded" else "${tracked.size} track${if (tracked.size == 1) "" else "s"}",
                drones = tracked.associate { d ->
                    val p = d.track.last()
                    d.netId to DronePosition(d.netId, p.lat, p.lon, p.altM, p.time, "session")
                },
                intel = s.drones.associate { d -> d.netId to d.asIntel() },
                focusNetIds = s.drones.map { it.netId }.toSet(),
                showPhoneCoordinates = false,
                modifier = Modifier.fillMaxWidth().height(230.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { SessionStore.exportKml(s, ::droneColorArgb) }
                        share(context, listOf(f), "application/vnd.google-earth.kml+xml", "SiKW00F session ${s.id} (KML)")
                    }
                }, modifier = Modifier.weight(1f)) { Text("KML") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { SessionStore.exportGpx(s) }
                        share(context, listOf(f), "application/gpx+xml", "SiKW00F session ${s.id} (GPX)")
                    }
                }, modifier = Modifier.weight(1f)) { Text("GPX") }
                OutlinedButton(onClick = {
                    share(context, listOfNotNull(s.detectionsFile, s.logFile), "text/plain", "SiKW00F session ${s.id} (raw)")
                }, modifier = Modifier.weight(1f)) { Text("Raw logs") }
            }

            if (s.drones.isEmpty()) {
                Text("No drones were detected in this session.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.drones.forEach { d -> SessionDroneCard(d) }

            Text(
                listOfNotNull(
                    "${s.scans} scan${if (s.scans == 1) "" else "s"}",
                    "${s.locks} lock${if (s.locks == 1) "" else "s"}",
                    "${s.alerts} alert${if (s.alerts == 1) "" else "s"}",
                    s.observerTrack.takeIf { it.size > 1 }?.let { "you moved through ${it.size} points" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
    }
}

/** Minimal intel so the shared map can draw the track, START and HOME for a past drone. */
private fun SessionDrone.asIntel(): DroneIntel = DroneIntel(
    netId = netId,
    vehicleType = vehicleType,
    track = track,
    position = track.lastOrNull()?.let { GeoFix(it.lat, it.lon, it.altM) },
    positionTime = track.lastOrNull()?.time,
    home = home
)

/** A few frequencies are listed; a hopping link heard across the band is summarised as a range. */
private fun freqSummary(freqs: Set<String>): String {
    val sorted = freqs.mapNotNull { f -> f.toDoubleOrNull()?.let { it to f } }.sortedBy { it.first }.map { it.second }
    return if (sorted.size <= 4) sorted.joinToString(", ", postfix = " MHz")
    else "${sorted.first()}–${sorted.last()} MHz · ${sorted.size} frequencies"
}

private fun durationLabel(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "${ms / 1000} s"
        min < 60 -> "$min min"
        else -> "${min / 60} h ${min % 60} min"
    }
}

private fun distanceLabel(m: Double): String = if (m >= 1000) "%.1f km".format(Locale.US, m / 1000) else "${m.toInt()} m"

private fun share(context: Context, files: List<File>, mime: String, subject: String) {
    val existing = files.filter { it.exists() }
    if (existing.isEmpty()) {
        LogBus.w("Nothing to share for this session.")
        return
    }
    val authority = "${context.packageName}.logs"
    val uris = ArrayList(existing.map { FileProvider.getUriForFile(context, authority, it) })
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }.apply {
        type = mime
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
