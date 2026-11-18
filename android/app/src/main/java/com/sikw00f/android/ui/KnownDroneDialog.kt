package com.sikw00f.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sikw00f.android.alerts.KnownDrone

/**
 * Name a NetID, mark it friendly (no alerts, no auto-lock, friendly in TAK)
 * and optionally pick its colour. [netId] null = adding by NetID from Settings.
 */
@Composable
fun KnownDroneDialog(
    netId: Int?,
    existing: KnownDrone?,
    onSave: (KnownDrone) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var idText by remember { mutableStateOf(netId?.toString() ?: existing?.netId?.toString() ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var friendly by remember { mutableStateOf(existing?.friendly ?: true) }
    var color by remember { mutableStateOf(existing?.color) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    val id = idText.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Label drone" else "Edit ${existing.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (netId == null && existing == null) {
                    OutlinedTextField(
                        value = idText,
                        onValueChange = { idText = it.filter(Char::isDigit).take(5) },
                        label = { Text("NetID") },
                        singleLine = true,
                        isError = idText.isNotEmpty() && id == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Our test quad") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Friendly", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "No alerts, skipped by auto-lock, friendly in TAK",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = friendly, onCheckedChange = { friendly = it })
                }
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Automatic colour first, then the fixed swatches.
                    Swatch(
                        fill = id?.let { autoColor(it) } ?: MaterialTheme.colorScheme.surfaceVariant,
                        selected = color == null,
                        onClick = { color = null }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Automatic colour", tint = Color.Black.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                    }
                    DRONE_SWATCHES.forEach { c -> Swatch(Color(c), color == c, onClick = { color = c }) }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(120) },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { id?.let { onSave(KnownDrone(it, name.trim(), friendly, color, note.trim())) } },
                enabled = id != null
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** The generated colour, ignoring any override (for the "automatic" swatch). */
private fun autoColor(netId: Int): Color = Color.hsv(((netId * 137.508 + 8) % 360).toFloat(), 0.62f, 0.97f)

@Composable
private fun Swatch(fill: Color, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit = {}) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(fill)
            .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
        else content()
    }
}
