package com.sikw00f.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sikw00f.android.protocol.DroneIntel
import com.sikw00f.android.protocol.DronePosition
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

private fun hasLocationPermission(context: Context) = LOCATION_PERMISSIONS.any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * OpenStreetMap view that follows the phone's position. While [scanning], a
 * radar sweep is drawn around the phone. Drones with a GPS fix from their
 * telemetry ([drones]) are plotted as NetID markers.
 */
@Composable
fun DeviceLocationMap(
    scanning: Boolean,
    statusLabel: String,
    drones: Map<Int, DronePosition>,
    modifier: Modifier = Modifier,
    intel: Map<Int, DroneIntel> = emptyMap(),
    /**
     * Track view (detail page, past sessions): frames these drones' tracks
     * instead of following the phone; no radar.
     */
    focusNetIds: Set<Int>? = null,
    /** Hides the live phone position overlay text (past sessions). */
    showPhoneCoordinates: Boolean = true,
    onPhoneLocation: (Location) -> Unit = {}
) {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(hasLocationPermission(context)) }
    var location by remember { mutableStateOf<Location?>(null) }
    var recenterRequests by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> permitted = result.values.any { it } }

    LaunchedEffect(Unit) {
        if (!permitted) permissionLauncher.launch(LOCATION_PERMISSIONS)
    }
    // Snap back to the phone whenever a scan starts so the radar sits on it.
    LaunchedEffect(scanning) {
        if (scanning) recenterRequests++
    }

    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (permitted) {
            OsmLocationMap(
                scanning = scanning,
                drones = drones,
                intel = intel,
                recenterRequests = recenterRequests,
                focusNetIds = focusNetIds,
                onLocation = { location = it; onPhoneLocation(it) }
            )
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Filled.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Location access is off",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { permissionLauncher.launch(LOCATION_PERMISSIONS) }) { Text("Allow location") }
            }
        }

        StatusPill(
            label = statusLabel,
            active = scanning,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
        )

        val focused = focusNetIds?.singleOrNull()?.let { drones[it] }
        if (permitted && focused != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
            ) {
                Text(
                    String.format(Locale.US, "%.5f, %.5f", focused.latitude, focused.longitude) +
                        (focused.altitudeM?.let { String.format(Locale.US, "  %.0f m", it) } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        } else if (permitted && showPhoneCoordinates) {
            location?.let { loc ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                ) {
                    Text(
                        String.format(Locale.US, "%.5f, %.5f  ±%.0f m", loc.latitude, loc.longitude, loc.accuracy),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } ?: StatusPill(
                label = "Locating…",
                active = false,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
            )

        }
        if (permitted) {
            SmallFloatingActionButton(
                onClick = { recenterRequests++ },
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
            ) {
                if (focusNetIds != null) Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Fit the drone's track")
                else Icon(Icons.Filled.MyLocation, contentDescription = "Center on my location")
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, active: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        modifier = modifier
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (active) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun OsmLocationMap(
    scanning: Boolean,
    drones: Map<Int, DronePosition>,
    intel: Map<Int, DroneIntel>,
    recenterRequests: Int,
    focusNetIds: Set<Int>?,
    onLocation: (Location) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        Configuration.getInstance().apply {
            // OSM's tile policy requires an identifying user agent; keep the
            // tile cache in app-private storage (no storage permission needed).
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
            overlayManager.tilesOverlay.apply {
                setColorFilter(darkTileFilter())
                setLoadingBackgroundColor(0xFF1A1E22.toInt())
                setLoadingLineColor(0xFF262B30.toInt())
            }
        }
    }

    val myLocation = remember {
        object : MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) {
            override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
                super.onLocationChanged(location, source)
                if (location != null) mapView.post { onLocation(location) }
            }
        }.also {
            // Same marker whether or not the fix has a bearing, so it doesn't
            // flip between the default person and arrow icons as you move.
            val dot = locationDotBitmap(context)
            it.setPersonIcon(dot)
            it.setDirectionIcon(dot)
            it.setPersonAnchor(0.5f, 0.5f)
            it.setDirectionAnchor(0.5f, 0.5f)
            // Detail view follows the phone only until the drone's own positions arrive.
            it.enableFollowLocation()
            mapView.overlays.add(it)
        }
    }

    DisposableEffect(lifecycleOwner) {
        // Navigation can still deliver a lifecycle event after onDispose has
        // detached the map (osmdroid then throws on enableMyLocation), so
        // events are ignored once torn down.
        var detached = false
        val observer = LifecycleEventObserver { _, event ->
            if (detached) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    runCatching { myLocation.enableMyLocation() }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    myLocation.disableMyLocation()
                    mapView.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            detached = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            myLocation.disableMyLocation()
            mapView.onDetach()
        }
    }

    val radarColor = MaterialTheme.colorScheme.primary.toArgb()
    val radar = remember {
        // Inserted beneath the location overlay so the blue dot draws on top of the sweep.
        RadarOverlay(mapView, location = { myLocation.myLocation }, initialColor = radarColor)
            .also { mapView.overlays.add(0, it) }
    }

    LaunchedEffect(scanning, radarColor) {
        radar.color = radarColor
        radar.scanning = scanning
    }

    val droneMarkers = remember { mutableMapOf<Int, Marker>() }
    val markerColors = remember { mutableMapOf<Int, Int>() }
    // Re-run when a Known-drone colour changes so markers and trails recolour at once.
    val colorOverrides = DroneColorOverrides.colors.toMap()
    LaunchedEffect(drones, colorOverrides) {
        var newlySeen: GeoPoint? = null
        for (pos in drones.values) {
            val point = GeoPoint(pos.latitude, pos.longitude)
            val marker = droneMarkers.getOrPut(pos.netId) {
                newlySeen = point
                Marker(mapView).apply {
                    icon = BitmapDrawable(context.resources, droneMarkerBitmap(context, pos.netId, droneColorArgb(pos.netId)))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "NetID ${pos.netId}"
                    mapView.overlays.add(this)
                }
            }
            val c = droneColorArgb(pos.netId)
            if (markerColors[pos.netId] != c) {
                marker.icon = BitmapDrawable(context.resources, droneMarkerBitmap(context, pos.netId, c))
                markerColors[pos.netId] = c
            }
            marker.position = point
            marker.snippet = droneSnippet(pos, myLocation.myLocation)
            if (marker.isInfoWindowShown) marker.showInfoWindow() // refresh open bubble
        }
        (droneMarkers.keys - drones.keys).forEach { mapView.overlays.remove(droneMarkers.remove(it)) }

        // First fix for a drone: frame both it and the phone. Follow mode is
        // turned off so the next GPS update doesn't snap the view back; the
        // recenter button turns it on again.
        if (focusNetIds == null) newlySeen?.let { dronePoint ->
            val me = myLocation.myLocation
            if (me == null) {
                mapView.controller.animateTo(dronePoint)
            } else {
                myLocation.disableFollowLocation()
                val box = BoundingBox.fromGeoPoints(listOf(me, dronePoint)).increaseByScale(1.5f)
                mapView.zoomToBoundingBox(box, true, 0, 18.0, null)
            }
        }
        mapView.invalidate()
    }

    // Flight trails, home points and captured mission routes. Keyed on a small
    // signature so the overlays only rebuild when something visible changed.
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val homeColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val trails = remember { mutableMapOf<Int, Polyline>() }
    val routes = remember { mutableMapOf<Int, Polyline>() }
    val homes = remember { mutableMapOf<Int, Marker>() }
    val gcsMarkers = remember { mutableMapOf<Int, Marker>() }
    val startMarkers = remember { mutableMapOf<Int, Marker>() }
    val gcsColor = MaterialTheme.colorScheme.secondary.toArgb()
    val signature = intel.mapValues { (_, i) -> listOf(i.track.size, i.home, i.mission.size, i.gcsPosition) }
    LaunchedEffect(signature, colorOverrides) {
        val density = context.resources.displayMetrics.density
        // Index 1 = just above the radar, below the location dot and drone markers.
        fun addUnderMarkers(o: org.osmdroid.views.overlay.Overlay) = mapView.overlays.add(minOf(1, mapView.overlays.size), o)

        for ((id, i) in intel) {
            if (i.track.size >= 2) {
                val line = trails.getOrPut(id) {
                    Polyline(mapView).apply {
                        outlinePaint.color = droneColorArgb(id)
                        outlinePaint.alpha = 200
                        outlinePaint.strokeWidth = 4 * density
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        addUnderMarkers(this)
                    }
                }
                line.outlinePaint.color = droneColorArgb(id)
                line.setPoints(i.track.map { GeoPoint(it.lat, it.lon) })
                if (focusNetIds != null) {
                    val first = i.track.first()
                    val start = startMarkers.getOrPut(id) {
                        Marker(mapView).apply {
                            icon = BitmapDrawable(context.resources, labelPillBitmap(context, "START", routeColor))
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "First position heard · NetID $id"
                            mapView.overlays.add(this)
                        }
                    }
                    start.position = GeoPoint(first.lat, first.lon)
                    start.snippet = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(first.time))
                }
            }

            // ArduPilot stores home as mission item 0, so draw home -> waypoints.
            val wps = i.missionWaypoints.filter { it.seq > 0 && it.lat != null && it.lon != null }
            if (wps.isNotEmpty()) {
                val route = routes.getOrPut(id) {
                    Polyline(mapView).apply {
                        outlinePaint.color = routeColor
                        outlinePaint.strokeWidth = 3 * density
                        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12 * density, 8 * density), 0f)
                        addUnderMarkers(this)
                    }
                }
                val start = i.home?.let { listOf(GeoPoint(it.lat, it.lon)) } ?: emptyList()
                route.setPoints(start + wps.map { GeoPoint(it.lat!!, it.lon!!) })
            }

            // HOME_POSITION from the autopilot: where it armed / got its first fix.
            // Often near the operator, but it is not the GCS's location.
            i.home?.let { h ->
                val marker = homes.getOrPut(id) {
                    Marker(mapView).apply {
                        icon = BitmapDrawable(context.resources, labelPillBitmap(context, "HOME $id", homeColor))
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "Home (launch point) · NetID $id"
                        mapView.overlays.add(this)
                    }
                }
                marker.position = GeoPoint(h.lat, h.lon)
                marker.snippet = "Reported by the drone's autopilot (HOME_POSITION). The operator is often nearby. " +
                    "%.6f, %.6f".format(Locale.US, h.lat, h.lon)
            }

            // Only when the ground station itself transmits a position (e.g. QGC Follow Me).
            i.gcsPosition?.let { g ->
                val marker = gcsMarkers.getOrPut(id) {
                    Marker(mapView).apply {
                        icon = BitmapDrawable(context.resources, labelPillBitmap(context, "GCS $id", gcsColor))
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "Ground station · NetID $id"
                        mapView.overlays.add(this)
                    }
                }
                marker.position = GeoPoint(g.lat, g.lon)
                marker.snippet = "Position transmitted by the GCS itself. %.6f, %.6f".format(Locale.US, g.lat, g.lon)
            }
        }
        for (map in listOf(trails, routes)) {
            (map.keys - intel.keys).forEach { mapView.overlays.remove(map.remove(it)) }
        }
        for (map in listOf(homes, gcsMarkers, startMarkers)) {
            (map.keys - intel.keys).forEach { mapView.overlays.remove(map.remove(it)) }
        }
        mapView.invalidate()
    }

    // Single-drone view: frame everything known about it (track, current fix,
    // home, GCS). Runs on the first data and whenever the fit button is tapped.
    fun focusPoints(): List<GeoPoint> {
        val ids = focusNetIds ?: return emptyList()
        return buildList {
            for (id in ids) {
                val i = intel[id]
                i?.track?.forEach { add(GeoPoint(it.lat, it.lon)) }
                drones[id]?.let { add(GeoPoint(it.latitude, it.longitude)) }
                i?.home?.let { add(GeoPoint(it.lat, it.lon)) }
                i?.gcsPosition?.let { add(GeoPoint(it.lat, it.lon)) }
            }
        }
    }
    fun fitFocus() {
        val pts = focusPoints()
        if (pts.isEmpty()) {
            myLocation.enableFollowLocation()
            myLocation.myLocation?.let { mapView.controller.animateTo(it) }
            return
        }
        myLocation.disableFollowLocation()
        val apply = {
            if (pts.size == 1 || BoundingBox.fromGeoPoints(pts).let { it.latitudeSpan < 1e-4 && it.longitudeSpanWithDateLine < 1e-4 }) {
                mapView.controller.setZoom(17.0)
                mapView.controller.setCenter(pts.last())
            } else {
                mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.4f), false, 0, 18.0, null)
            }
        }
        if (mapView.width == 0 || mapView.height == 0) mapView.addOnFirstLayoutListener { _, _, _, _, _ -> apply() } else apply()
    }
    var focusFitted by remember { mutableStateOf(false) }
    val hasFocusData = focusNetIds != null && focusPoints().isNotEmpty()
    LaunchedEffect(hasFocusData) {
        if (hasFocusData && !focusFitted) {
            focusFitted = true
            fitFocus()
        }
    }

    LaunchedEffect(recenterRequests) {
        if (focusNetIds != null) {
            fitFocus()
        } else {
            myLocation.enableFollowLocation()
            myLocation.myLocation?.let { mapView.controller.animateTo(it) }
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

/** Blue "you are here" dot with a white ring, sized in dp. */
private fun locationDotBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (20 * density).toInt()
    val radius = size / 2f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(radius, radius, radius, paint)
    paint.color = 0xFF1A73E8.toInt()
    canvas.drawCircle(radius, radius, radius - 3 * density, paint)
    return bitmap
}

private val fixTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

/** Info-bubble text: fix time, altitude, and range/bearing from the phone. */
private fun droneSnippet(pos: DronePosition, me: GeoPoint?): String {
    val parts = mutableListOf("Fix ${fixTimeFormat.format(Date(pos.timestamp))}")
    pos.altitudeM?.let { parts += String.format(Locale.US, "alt %.0f m", it) }
    if (me != null) {
        val result = FloatArray(2)
        Location.distanceBetween(me.latitude, me.longitude, pos.latitude, pos.longitude, result)
        val meters = result[0]
        val range = if (meters >= 1000) String.format(Locale.US, "%.2f km", meters / 1000) else "${meters.toInt()} m"
        parts += "$range ${compassPoint(result[1])} of you"
    }
    return parts.joinToString(" · ")
}

private fun compassPoint(bearing: Float): String {
    val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val normalized = ((bearing % 360) + 360) % 360
    return points[((normalized + 22.5f) / 45f).toInt() % 8]
}

/** Round badge in [color] with the NetID in white, auto-shrinking long IDs. */
private fun droneMarkerBitmap(context: Context, netId: Int, color: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (30 * density).toInt()
    val radius = size / 2f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(radius, radius, radius, paint)
    paint.color = color
    canvas.drawCircle(radius, radius, radius - 2 * density, paint)

    val label = netId.toString()
    paint.color = android.graphics.Color.WHITE
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = 12 * density
    val maxWidth = size - 8 * density
    if (paint.measureText(label) > maxWidth) paint.textSize *= maxWidth / paint.measureText(label)
    val baseline = radius - (paint.descent() + paint.ascent()) / 2
    canvas.drawText(label, radius, baseline, paint)
    return bitmap
}

/**
 * Night-mode look for the standard OSM tiles: invert, rotate hue 180° (so
 * water stays blue and parks green after the invert), then dim slightly.
 */
private fun darkTileFilter(): ColorMatrixColorFilter {
    val invert = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    // CSS/SVG hue-rotate matrix at 180° (cos = -1, sin = 0).
    val cos = -1f
    val hue = ColorMatrix(
        floatArrayOf(
            0.213f + cos * 0.787f, 0.715f - cos * 0.715f, 0.072f - cos * 0.072f, 0f, 0f,
            0.213f - cos * 0.213f, 0.715f + cos * 0.285f, 0.072f - cos * 0.072f, 0f, 0f,
            0.213f - cos * 0.213f, 0.715f - cos * 0.715f, 0.072f + cos * 0.928f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    val dim = ColorMatrix().apply { setScale(0.85f, 0.85f, 0.9f, 1f) }
    invert.postConcat(hue)
    invert.postConcat(dim)
    return ColorMatrixColorFilter(invert)
}

/** Rounded label marker (e.g. "HOME 18", "GCS 18") so map symbols are self-explanatory. */
private fun labelPillBitmap(context: Context, text: String, color: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 11 * density
        textAlign = Paint.Align.CENTER
    }
    val padH = 7 * density
    val height = (20 * density).toInt()
    val width = (paint.measureText(text) + 2 * padH).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val rect = android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
    val r = height / 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawRoundRect(rect, r, r, paint)
    paint.color = color
    rect.inset(1.5f * density, 1.5f * density)
    canvas.drawRoundRect(rect, r, r, paint)
    paint.color = android.graphics.Color.BLACK
    canvas.drawText(text, width / 2f, height / 2f - (paint.descent() + paint.ascent()) / 2, paint)
    return bitmap
}
