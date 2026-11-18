package com.sikw00f.android.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.SweepGradient
import android.os.SystemClock
import org.osmdroid.api.IGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

private const val SWEEP_MS = 2600L
private const val MAX_RINGS = 200

/**
 * Radar sweep drawn as a map overlay, anchored to the phone's GPS position so
 * it pans and zooms with the map. Rings are real distances (a 1/2/5 step
 * picked for the zoom level) and repeat out to the farthest screen corner,
 * so the sweep always covers the whole view.
 */
class RadarOverlay(
    private val mapView: MapView,
    private val location: () -> IGeoPoint?,
    initialColor: Int
) : Overlay() {

    private val density = mapView.resources.displayMetrics.density
    private val targetRingSpacingPx = 72 * density
    private val point = Point()
    private val sweepMatrix = Matrix()
    private var startMs = 0L

    var scanning: Boolean = false
        set(value) {
            if (value && !field) startMs = SystemClock.uptimeMillis()
            field = value
            mapView.postInvalidate()
        }

    var color: Int = initialColor
        set(value) {
            field = value
            applyColor()
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * density }
    private val labelHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * density
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = 0xCC000000.toInt() // dark halo: labels sit on the dark map
    }

    init {
        applyColor()
    }

    private fun applyColor() {
        ringPaint.color = withAlpha(color, 0.45f)
        axisPaint.color = withAlpha(color, 0.3f)
        beamPaint.color = color
        labelPaint.color = color
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (!scanning || isEnabled.not()) return
        val geo = location() ?: return

        projection.toPixels(geo, point)
        val cx = point.x.toFloat()
        val cy = point.y.toFloat()
        val w = projection.width.toFloat()
        val h = projection.height.toFloat()
        // Distance to the farthest corner, so rings and sweep fill the view
        // even when the phone is panned near (or off) an edge.
        val reach = max(max(hypot(cx, cy), hypot(w - cx, cy)), max(hypot(cx, h - cy), hypot(w - cx, h - cy)))

        val pxPerMeter = projection.metersToPixels(1000f, geo.latitude, projection.zoomLevel) / 1000f
        if (pxPerMeter <= 0f) return
        val stepMeters = niceStep(targetRingSpacingPx / pxPerMeter)
        val stepPx = (stepMeters * pxPerMeter).toFloat()

        // Range rings with distance labels on the east axis.
        var ring = 1
        while (ring <= MAX_RINGS && stepPx * ring <= reach) {
            val r = stepPx * ring
            canvas.drawCircle(cx, cy, r, ringPaint)
            val label = formatDistance(stepMeters * ring)
            val lx = cx + r + 3 * density
            val ly = cy - 4 * density
            canvas.drawText(label, lx, ly, labelHaloPaint)
            canvas.drawText(label, lx, ly, labelPaint)
            ring++
        }
        canvas.drawLine(cx - reach, cy, cx + reach, cy, axisPaint)
        canvas.drawLine(cx, cy - reach, cx, cy + reach, axisPaint)

        val t = ((SystemClock.uptimeMillis() - startMs) % SWEEP_MS) / SWEEP_MS.toFloat()

        // Outward pulse.
        pulsePaint.color = withAlpha(color, 0.55f * (1f - t))
        canvas.drawCircle(cx, cy, reach * t, pulsePaint)

        // Sweep: gradient is brightest at 0°, then rotated to the beam angle
        // so the trail fades out behind it (clockwise sweep).
        val angle = t * 360f
        val gradient = SweepGradient(
            cx, cy,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(color, 0.45f)),
            floatArrayOf(0f, 0.75f, 1f)
        )
        sweepMatrix.setRotate(angle, cx, cy)
        gradient.setLocalMatrix(sweepMatrix)
        sweepPaint.shader = gradient
        canvas.drawCircle(cx, cy, reach, sweepPaint)

        val rad = Math.toRadians(angle.toDouble())
        canvas.drawLine(cx, cy, cx + reach * cos(rad).toFloat(), cy + reach * sin(rad).toFloat(), beamPaint)

        mapView.postInvalidateOnAnimation()
    }

    /** Smallest 1/2/5 × 10^n step that is at least [rawMeters]. */
    private fun niceStep(rawMeters: Float): Double {
        val magnitude = 10.0.pow(floor(log10(rawMeters.toDouble())))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= rawMeters }
    }

    private fun formatDistance(meters: Double): String = when {
        meters >= 1000 -> {
            val km = meters / 1000
            if (km % 1.0 == 0.0) "${km.toLong()} km" else String.format(Locale.US, "%.1f km", km)
        }
        else -> "${meters.toLong()} m"
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
