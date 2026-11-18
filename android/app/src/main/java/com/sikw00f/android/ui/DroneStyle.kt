package com.sikw00f.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * A distinct colour per NetID, the same everywhere (list, maps, detail page,
 * past sessions). Golden-angle hue steps keep nearby NetIDs far apart on the
 * colour wheel, so it reads as random without changing between runs.
 */
fun droneColor(netId: Int): Color {
    DroneColorOverrides.colors[netId]?.let { return Color(it) }
    val hue = ((netId * 137.508 + 8) % 360).toFloat()
    return Color.hsv(hue, 0.62f, 0.97f)
}

/** Colours the user picked for Known drones; observable so lists and maps recolour at once. */
object DroneColorOverrides {
    val colors = androidx.compose.runtime.mutableStateMapOf<Int, Int>()

    fun sync(known: Map<Int, com.sikw00f.android.alerts.KnownDrone>) {
        val wanted = known.values.mapNotNull { d -> d.color?.let { d.netId to it } }.toMap()
        (colors.keys - wanted.keys).forEach { colors.remove(it) }
        wanted.forEach { (k, v) -> if (colors[k] != v) colors[k] = v }
    }
}

/** Swatches offered when labelling a drone (ARGB). */
val DRONE_SWATCHES = listOf(
    0xFF4FC3F7.toInt(), 0xFF81C784.toInt(), 0xFFFFD54F.toInt(), 0xFFFF8A65.toInt(),
    0xFFE57373.toInt(), 0xFFBA68C8.toInt(), 0xFF7986CB.toInt(), 0xFFF5F5F5.toInt()
)

fun droneColorArgb(netId: Int): Int = droneColor(netId).toArgb()

/** 1–4 bars from a dBm reading (Si443x: about -120 dBm floor). */
fun signalBars(dbm: Int): Int = when {
    dbm > -70 -> 4
    dbm > -85 -> 3
    dbm > -100 -> 2
    else -> 1
}

fun signalWord(dbm: Int): String = when (signalBars(dbm)) {
    4 -> "very strong"
    3 -> "strong"
    2 -> "fair"
    else -> "weak"
}

@Composable
fun SignalBars(dbm: Int, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    val bars = signalBars(dbm)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= bars) color else MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
}
