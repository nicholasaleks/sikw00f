package com.sikw00f.android.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sikw00f.android.LogBus
import com.sikw00f.android.LogEntry
import com.sikw00f.android.LogLevel

private enum class LogFilter(val label: String) { ALL("All"), DETECTIONS("Detections"), PROBLEMS("Warnings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onOpenSession: (String) -> Unit = {}) {
    val context = LocalContext.current
    var showSessions by rememberSaveable { mutableStateOf(false) }
    val entries by LogBus.entries.collectAsState()
    var verbose by remember { mutableStateOf(LogBus.verbose) }
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }

    val shown = when (filter) {
        LogFilter.ALL -> entries
        LogFilter.DETECTIONS -> entries.filter { it.tag != null }
        LogFilter.PROBLEMS -> entries.filter { it.level == LogLevel.WARNING || it.level == LogLevel.ERROR }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                listOf(false to "Live log", true to "Past sessions").forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = showSessions == value,
                        onClick = { showSessions = value },
                        shape = SegmentedButtonDefaults.itemShape(index, 2)
                    ) { Text(label) }
                }
            }
            if (!showSessions) {
                IconButton(onClick = { shareSessionLogs(context) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share session logs")
                }
                IconButton(onClick = { LogBus.clear() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear screen (files are kept)")
                }
            }
        }

        // No early return from inside Column: with this Compose compiler it
        // unbalances the composer's group stack and crashes on recomposition.
        if (showSessions) {
            SessionList(onOpenSession)
        } else {
            LiveLog(shown, verbose, filter, onFilter = { filter = it }, onVerbose = { verbose = it; LogBus.verbose = it })
        }
    }
}

@Composable
private fun LiveLog(
    shown: List<LogEntry>,
    verbose: Boolean,
    filter: LogFilter,
    onFilter: (LogFilter) -> Unit,
    onVerbose: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LogFilter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { onFilter(f) }, label = { Text(f.label) })
            }
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = verbose,
                onClick = { onVerbose(!verbose) },
                label = { Text("Debug") }
            )
        }

        Text(
            "Saved to ${LogBus.sessionLog?.name ?: "session file"} + detections JSONL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(shown.asReversed()) { entry -> LogLine(entry) }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    Text(
        LogBus.formatted(entry),
        color = colorFor(entry.level),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (entry.tag != null) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun colorFor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
}

/** Shares this session's text log and detections JSONL through the system share sheet. */
private fun shareSessionLogs(context: Context) {
    val files = LogBus.sessionFiles()
    if (files.isEmpty()) {
        LogBus.w("No session log files to share yet.")
        return
    }
    val authority = "${context.packageName}.logs"
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, authority, it) })
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, "SiKW00F session ${LogBus.sessionId}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share SiKW00F logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
