package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.location.LocationFixState
import com.example.data.location.RadarGeoMath
import com.example.data.map.OsmTileManager
import com.example.data.model.MapLayerConfig
import com.example.data.model.MemberLocation
import com.example.data.model.Mission
import com.example.data.model.SensorDataValidity
import com.example.data.model.SensorDevice
import com.example.data.model.SensorEvent
import com.example.data.model.SosAlert
import com.example.data.model.SosAlertStatus
import com.example.data.network.NetworkState
import com.example.ui.theme.BlueSky
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusOutdated
import com.example.ui.theme.StatusVerified
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Real OpenStreetMap / OpenTopoMap Slippy Tile Map Canvas with:
 * - Genuine OSM raster tile download + disk/Room cache rendering
 * - Authoritative, uncropped J&K & Ladakh India Boundary reference + distinct LoC & LAC lines
 * - Live Rescue Team Members (GREEN / GREY), External Sensor Stations & Detections (YELLOW / GREY), SOS Alerts (RED)
 * - Active Mission Separation Threshold ring & distance ruler tool
 */
@Composable
fun IndiaInteractiveMap(
    activeMission: Mission?,
    memberLocations: List<MemberLocation>,
    sensorDevices: List<SensorDevice>,
    sensorEvents: List<SensorEvent>,
    sosAlerts: List<SosAlert>,
    layers: MapLayerConfig,
    isOfflineMode: Boolean,
    networkState: NetworkState = NetworkState.ONLINE_UNMETERED,
    deviceLocation: LocationFixState = LocationFixState.OptedOut(),
    tileManager: OsmTileManager? = null,
    customTileTemplateUrl: String = "",
    focusLat: Double? = null,
    focusLon: Double? = null,
    focusZoom: Float? = null,
    interactive: Boolean = true,
    onMemberSelected: (MemberLocation) -> Unit = {},
    onSensorEventSelected: (SensorEvent) -> Unit = {},
    onAlertSelected: (SosAlert) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var centerLat by remember { mutableDoubleStateOf(activeMission?.centerLatitude ?: 23.5) }
    var centerLon by remember { mutableDoubleStateOf(activeMission?.centerLongitude ?: 82.0) }
    var zoomLevel by remember { mutableFloatStateOf(if (activeMission != null) 11.5f else 4.2f) }
    var canvasWidthPx by remember { mutableFloatStateOf(900f) }
    var canvasHeightPx by remember { mutableFloatStateOf(700f) }
    var measureMode by remember { mutableStateOf(false) }
    var measureEndLatLon by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val loadedTiles = remember { mutableStateMapOf<String, Bitmap>() }
    var tileDrawRevision by remember { mutableIntStateOf(0) }
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    LaunchedEffect(focusLat, focusLon, focusZoom) {
        if (focusLat != null && focusLon != null) {
            centerLat = focusLat.coerceIn(5.0, 38.5)
            centerLon = focusLon.coerceIn(66.0, 99.5)
            if (focusZoom != null) zoomLevel = focusZoom.coerceIn(3.8f, 16.0f)
        }
    }

    val tileZ = floor(zoomLevel).toInt().coerceIn(4, 15)
    val layerKind = if (layers.showTerrain) "topo" else "osm"
    val allowNetworkFetch = !isOfflineMode && networkState != NetworkState.OFFLINE
    val baseTilePx = min(canvasWidthPx, canvasHeightPx).coerceAtLeast(320f) / 2.35f
    val tilePx = baseTilePx * 2.0.pow((zoomLevel - tileZ).toDouble()).toFloat()

    LaunchedEffect(centerLat, centerLon, tileZ, layerKind, allowNetworkFetch, customTileTemplateUrl, canvasWidthPx, canvasHeightPx) {
        if (tileManager == null) return@LaunchedEffect
        val (cxTile, cyTile) = OsmTileManager.latLonToFractionTile(centerLat, centerLon, tileZ)
        val halfTilesX = (canvasWidthPx / 2f / tilePx) + 1f
        val halfTilesY = (canvasHeightPx / 2f / tilePx) + 1f
        val maxIdx = (1 shl tileZ) - 1
        val minTx = floor(cxTile - halfTilesX).toInt().coerceIn(0, maxIdx)
        val maxTx = ceil(cxTile + halfTilesX).toInt().coerceIn(0, maxIdx)
        val minTy = floor(cyTile - halfTilesY).toInt().coerceIn(0, maxIdx)
        val maxTy = ceil(cyTile + halfTilesY).toInt().coerceIn(0, maxIdx)

        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                val key = "$layerKind/$tileZ/$tx/$ty"
                if (!loadedTiles.containsKey(key)) {
                    scope.launch {
                        val bmp = tileManager.loadOrFetchTile(
                            layerKind = layerKind,
                            z = tileZ,
                            x = tx,
                            y = ty,
                            allowNetwork = allowNetworkFetch,
                            customTileTemplateUrl = customTileTemplateUrl,
                        )
                        if (bmp != null) {
                            loadedTiles[key] = bmp
                            tileDrawRevision++
                        }
                    }
                }
            }
        }
    }

    fun project(lat: Double, lon: Double, w: Float, h: Float): Offset {
        val (cxTile, cyTile) = OsmTileManager.latLonToFractionTile(centerLat, centerLon, tileZ)
        val (fx, fy) = OsmTileManager.latLonToFractionTile(lat, lon, tileZ)
        return Offset(
            x = (w / 2f + (fx - cxTile) * tilePx).toFloat(),
            y = (h / 2f + (fy - cyTile) * tilePx).toFloat(),
        )
    }

    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outline
    val primaryColor = MaterialTheme.colorScheme.primary
    val bgFallback = if (layers.showTerrain) Color(0xFF0A1929) else MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgFallback)
            .border(1.dp, outlineColor, RoundedCornerShape(16.dp))
            .testTag("india_interactive_map"),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (interactive) {
                        Modifier
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    val newZoom = (zoomLevel + kotlin.math.ln(gestureZoom.toDouble()).toFloat() / kotlin.math.ln(2.0).toFloat())
                                        .coerceIn(3.8f, 16.0f)
                                    val newZ = floor(newZoom).toInt().coerceIn(4, 15)
                                    val newTilePx = baseTilePx * 2.0.pow((newZoom - newZ).toDouble()).toFloat()
                                    val (cx, cy) = OsmTileManager.latLonToFractionTile(centerLat, centerLon, newZ)
                                    val nextLatLon = OsmTileManager.tileToLatLon(
                                        cx - pan.x / newTilePx,
                                        cy - pan.y / newTilePx,
                                        newZ,
                                    )
                                    zoomLevel = newZoom
                                    centerLat = nextLatLon.first.coerceIn(4.0, 39.0)
                                    centerLon = nextLatLon.second.coerceIn(65.0, 100.0)
                                }
                            }
                            .pointerInput(memberLocations, sensorEvents, sosAlerts, centerLat, centerLon, zoomLevel, measureMode) {
                                detectTapGestures { tapOffset ->
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    if (measureMode) {
                                        val (cx, cy) = OsmTileManager.latLonToFractionTile(centerLat, centerLon, tileZ)
                                        measureEndLatLon = OsmTileManager.tileToLatLon(
                                            cx + (tapOffset.x - w / 2f) / tilePx,
                                            cy + (tapOffset.y - h / 2f) / tilePx,
                                            tileZ,
                                        )
                                        return@detectTapGestures
                                    }

                                    // Priority hit-test: open SOS alerts -> member locations -> sensor events
                                    val hitAlert = sosAlerts.filter { it.hasLocation }.minByOrNull {
                                        val pt = project(it.latitude, it.longitude, w, h)
                                        hypot(pt.x - tapOffset.x, pt.y - tapOffset.y)
                                    }?.takeIf {
                                        val pt = project(it.latitude, it.longitude, w, h)
                                        hypot(pt.x - tapOffset.x, pt.y - tapOffset.y) < 52f
                                    }
                                    if (hitAlert != null) {
                                        onAlertSelected(hitAlert)
                                        return@detectTapGestures
                                    }

                                    val hitMember = memberLocations.filter { it.hasValidLocation }.minByOrNull {
                                        val pt = project(it.latitude, it.longitude, w, h)
                                        hypot(pt.x - tapOffset.x, pt.y - tapOffset.y)
                                    }?.takeIf {
                                        val pt = project(it.latitude, it.longitude, w, h)
                                        hypot(pt.x - tapOffset.x, pt.y - tapOffset.y) < 52f
                                    }
                                    if (hitMember != null) {
                                        onMemberSelected(hitMember)
                                        return@detectTapGestures
                                    }

                                    val devMap = sensorDevices.associateBy { it.sensorId }
                                    val hitEvent = sensorEvents.minByOrNull { ev ->
                                        val coords = resolveEventCoordinates(ev, devMap[ev.sensorId]) ?: return@minByOrNull Float.MAX_VALUE
                                        val pt = project(coords.first, coords.second, w, h)
                                        hypot(pt.x - tapOffset.x, pt.y - tapOffset.y)
                                    }?.takeIf { ev ->
                                        val coords = resolveEventCoordinates(ev, devMap[ev.sensorId]) ?: return@takeIf false
                                        val pt = project(coords.first, coords.second, w, h)
                                        hypot(pt.x - tapOffset.x, pt.y - tapOffset.y) < 52f
                                    }
                                    if (hitEvent != null) {
                                        onSensorEventSelected(hitEvent)
                                    }
                                }
                            }
                    } else {
                        Modifier
                    }
                )
        ) {
            val w = size.width
            val h = size.height
            canvasWidthPx = w
            canvasHeightPx = h
            @Suppress("UNUSED_VARIABLE")
            val rev = tileDrawRevision

            val (cxTile, cyTile) = OsmTileManager.latLonToFractionTile(centerLat, centerLon, tileZ)
            val halfTilesX = (w / 2f / tilePx) + 1f
            val halfTilesY = (h / 2f / tilePx) + 1f
            val maxIdx = (1 shl tileZ) - 1
            val minTx = floor(cxTile - halfTilesX).toInt().coerceIn(0, maxIdx)
            val maxTx = ceil(cxTile + halfTilesX).toInt().coerceIn(0, maxIdx)
            val minTy = floor(cyTile - halfTilesY).toInt().coerceIn(0, maxIdx)
            val maxTy = ceil(cyTile + halfTilesY).toInt().coerceIn(0, maxIdx)

            // 1. Render OSM / OpenTopoMap raster tiles
            if (layers.showRoads || layers.showTerrain) {
                for (tx in minTx..maxTx) {
                    for (ty in minTy..maxTy) {
                        val left = (w / 2f + (tx - cxTile) * tilePx).roundToInt()
                        val top = (h / 2f + (ty - cyTile) * tilePx).roundToInt()
                        val right = (w / 2f + (tx + 1 - cxTile) * tilePx).roundToInt()
                        val bottom = (h / 2f + (ty + 1 - cyTile) * tilePx).roundToInt()
                        val key = "$layerKind/$tileZ/$tx/$ty"
                        val bmp = loadedTiles[key] ?: tileManager?.getMemoryCachedTile(layerKind, tileZ, tx, ty)
                        if (bmp != null) {
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset(left, top),
                                dstSize = IntSize((right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1)),
                            )
                        } else {
                            drawRect(
                                color = Color(0xFF0B1C30).copy(alpha = 0.35f),
                                topLeft = Offset(left.toFloat(), top.toFloat()),
                                size = androidx.compose.ui.geometry.Size((right - left).toFloat(), (bottom - top).toFloat()),
                            )
                        }
                    }
                }
            }

            // 2. Authoritative India Boundary Overlay (Uncropped J&K, Ladakh) + LoC / LAC distinction
            if (layers.showBoundaryReference) {
                val indiaOutline = listOf(
                    37.05 to 74.60, 36.90 to 75.40, 35.80 to 76.50, 35.50 to 78.20,
                    34.90 to 79.80, 33.20 to 79.40, 31.80 to 78.90, 30.80 to 79.80,
                    29.60 to 80.30, 28.80 to 80.10, 27.50 to 83.50, 26.80 to 88.10,
                    27.90 to 88.80, 27.20 to 89.00, 26.80 to 92.00, 27.80 to 92.40,
                    29.10 to 96.20, 28.10 to 97.35, 26.70 to 95.30, 24.80 to 94.70,
                    22.10 to 93.10, 23.80 to 91.20, 25.10 to 92.30, 25.80 to 89.90,
                    22.00 to 89.00, 21.50 to 87.20, 19.20 to 84.80, 16.50 to 81.90,
                    13.10 to 80.30, 10.30 to 79.90, 8.08 to 77.55, 9.90 to 76.25,
                    12.90 to 74.85, 15.40 to 73.80, 19.07 to 72.87, 20.90 to 72.70,
                    21.60 to 72.20, 20.80 to 70.40, 22.40 to 69.00, 23.60 to 68.18,
                    24.50 to 70.80, 26.90 to 69.50, 28.80 to 72.60, 31.00 to 74.40,
                    32.50 to 74.60, 33.40 to 73.80, 34.70 to 73.50, 35.90 to 72.50,
                    36.90 to 73.60,
                )
                val landmassPath = Path().apply {
                    indiaOutline.forEachIndexed { idx, (lat, lon) ->
                        val pt = project(lat, lon, w, h)
                        if (idx == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                    }
                    close()
                }
                drawPath(landmassPath, color = BlueSky.copy(alpha = 0.85f), style = Stroke(width = 2.4f))

                val locCoords = listOf(
                    32.75 to 74.55, 33.65 to 74.05, 34.35 to 73.85,
                    34.65 to 74.80, 34.55 to 76.15, 35.40 to 77.00,
                )
                val locPath = Path().apply {
                    locCoords.forEachIndexed { i, (lat, lon) ->
                        val pt = project(lat, lon, w, h)
                        if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                    }
                }
                drawPath(
                    path = locPath,
                    color = StatusEstimated,
                    style = Stroke(width = 2.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)),
                )

                val lacCoords = listOf(
                    35.40 to 77.60, 34.70 to 78.30, 33.90 to 78.80,
                    33.20 to 79.10, 32.45 to 78.85,
                )
                val lacPath = Path().apply {
                    lacCoords.forEachIndexed { i, (lat, lon) ->
                        val pt = project(lat, lon, w, h)
                        if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                    }
                }
                drawPath(
                    path = lacPath,
                    color = Color(0xFFA78BFA),
                    style = Stroke(width = 2.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 7f), 0f)),
                )
            }

            // 3. Separation threshold radius circle around mission leader or center
            if (layers.showSeparationRadius && activeMission != null && activeMission.separationAlertEnabled) {
                val leader = memberLocations.firstOrNull { it.userId == activeMission.leaderUid && it.hasValidLocation }
                val refLat = leader?.latitude ?: activeMission.centerLatitude
                val refLon = leader?.longitude ?: activeMission.centerLongitude
                val centerPt = project(refLat, refLon, w, h)
                val edgeGeo = RadarGeoMath.projectFromStation(refLat, refLon, activeMission.separationThresholdMeters, 90.0)
                if (edgeGeo != null) {
                    val edgePt = project(edgeGeo.first, edgeGeo.second, w, h)
                    val radiusPx = hypot(edgePt.x - centerPt.x, edgePt.y - centerPt.y)
                    if (radiusPx > 4f && radiusPx < maxOf(w, h) * 2f) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.45f),
                            radius = radiusPx,
                            center = centerPt,
                            style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)),
                        )
                    }
                }
            }

            // 4. Sensor Stations & Coverage Cones
            val devMap = sensorDevices.associateBy { it.sensorId }
            if (layers.showSensorStations) {
                sensorDevices.filter { it.hasPosition }.forEach { dev ->
                    val pt = project(dev.latitude, dev.longitude, w, h)
                    val edgeGeo = RadarGeoMath.projectFromStation(dev.latitude, dev.longitude, dev.maxRangeMeters, 90.0)
                    if (edgeGeo != null) {
                        val rPx = hypot(project(edgeGeo.first, edgeGeo.second, w, h).x - pt.x, project(edgeGeo.first, edgeGeo.second, w, h).y - pt.y)
                        if (rPx in 4f..800f) {
                            val startDeg = (dev.orientationDegrees - dev.sectorWidthDegrees / 2.0 - 90.0).toFloat()
                            drawArc(
                                color = primaryColor.copy(alpha = 0.14f),
                                startAngle = startDeg,
                                sweepAngle = dev.sectorWidthDegrees.toFloat(),
                                useCenter = true,
                                topLeft = Offset(pt.x - rPx, pt.y - rPx),
                                size = androidx.compose.ui.geometry.Size(rPx * 2f, rPx * 2f),
                            )
                        }
                    }
                    drawCircle(primaryColor, radius = 5f, center = pt)
                    drawMapLabel(textMeasurer, dev.displayName.take(14), Offset(pt.x + 8f, pt.y - 6f), primaryColor)
                }
            }

            // 5. External Sensor Presence Detections (YELLOW / GREY)
            if (layers.showSensorDetections) {
                val nowMs = System.currentTimeMillis()
                sensorEvents.filter { it.status != "cleared" }.forEach { ev ->
                    val coords = resolveEventCoordinates(ev, devMap[ev.sensorId]) ?: return@forEach
                    val pt = project(coords.first, coords.second, w, h)
                    val isGrey = (nowMs - ev.timestampUtcMillis) > RadarGeoMath.SENSOR_STALE_MS ||
                        ev.confidence < 0.40 ||
                        ev.dataValidity != SensorDataValidity.VALID.value
                    val color = if (isGrey) StatusOutdated else StatusEstimated
                    drawCircle(color.copy(alpha = 0.25f), radius = 14f, center = pt)
                    drawCircle(color, radius = 5.5f, center = pt)
                    drawMapLabel(textMeasurer, "PRESENCE ${(ev.confidence * 100).toInt()}%", Offset(pt.x + 8f, pt.y - 6f), color)
                }
            }

            // 6. Verified Rescue Team Members (GREEN / GREY)
            if (layers.showTeamMembers) {
                val nowMs = System.currentTimeMillis()
                memberLocations.filter { it.hasValidLocation && it.sharingEnabled }.forEach { m ->
                    val stale = (nowMs - m.locationTimestampMillis) > RadarGeoMath.LOCATION_STALE_MS ||
                        m.connectionStatus != "online"
                    val color = if (stale) StatusOutdated else StatusVerified
                    val pt = project(m.latitude, m.longitude, w, h)
                    drawCircle(color.copy(alpha = 0.22f), radius = 13f, center = pt)
                    drawCircle(color, radius = 6.5f, center = pt)
                    drawCircle(Color.White, radius = 8.5f, center = pt, style = Stroke(width = 1.6f))
                    drawMapLabel(textMeasurer, m.callsign.ifBlank { m.displayName }.take(12), Offset(pt.x + 9f, pt.y - 6f), color)
                }
            }

            // 7. Open Emergency SOS Alerts (RED)
            if (layers.showSosAlerts) {
                sosAlerts.filter { SosAlertStatus.fromValue(it.status).isOpen() && it.hasLocation }.forEach { alert ->
                    val pt = project(alert.latitude, alert.longitude, w, h)
                    drawCircle(StatusCritical.copy(alpha = 0.3f), radius = 20f, center = pt)
                    drawCircle(StatusCritical, radius = 8f, center = pt)
                    drawCircle(Color.White, radius = 10f, center = pt, style = Stroke(width = 2f))
                    drawMapLabel(textMeasurer, "SOS: ${alert.senderName.take(10)}", Offset(pt.x + 11f, pt.y - 7f), StatusCritical)
                }
            }

            // 8. Own Device Live GPS Marker & Distance Ruler
            if (deviceLocation is LocationFixState.Available) {
                val userPt = project(deviceLocation.latitude, deviceLocation.longitude, w, h)
                drawCircle(primaryColor.copy(alpha = 0.22f), radius = 18f, center = userPt)
                drawCircle(primaryColor, radius = 7f, center = userPt)
                drawCircle(Color.White, radius = 9f, center = userPt, style = Stroke(width = 2f))

                val end = measureEndLatLon
                if (end != null) {
                    val targetPt = project(end.first, end.second, w, h)
                    drawLine(
                        color = primaryColor,
                        start = userPt,
                        end = targetPt,
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                    )
                    val distMeters = RadarGeoMath.distanceMeters(
                        deviceLocation.latitude,
                        deviceLocation.longitude,
                        end.first,
                        end.second,
                    )
                    val label = if (distMeters >= 1000) {
                        String.format(Locale.US, "%.2f km", distMeters / 1000.0)
                    } else {
                        "${distMeters.toInt()} m"
                    }
                    drawMapLabel(textMeasurer, label, Offset((userPt.x + targetPt.x) / 2f, (userPt.y + targetPt.y) / 2f), Color.White)
                }
            }
        }

        // Top-left India Boundary Reference Legend
        Surface(
            color = surfaceColor.copy(alpha = 0.9f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, outlineColor),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                LegendRow(color = BlueSky, label = "India Ref Boundary (z${floor(zoomLevel).toInt()})")
                LegendRow(color = StatusEstimated, label = "LoC (Dashed Control Line)")
                LegendRow(color = Color(0xFFA78BFA), label = "LAC (Dotted Control Line)")
            }
        }

        // Top-right Zoom / GPS / Measure controls
        if (interactive) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalIconButton(
                    onClick = { zoomLevel = (zoomLevel + 1.0f).coerceAtMost(16.0f) },
                    modifier = Modifier.size(48.dp).testTag("map_zoom_in"),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = surfaceColor.copy(alpha = 0.92f),
                        contentColor = primaryColor,
                    ),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom in")
                }
                FilledTonalIconButton(
                    onClick = { zoomLevel = (zoomLevel - 1.0f).coerceAtLeast(3.8f) },
                    modifier = Modifier.size(48.dp).testTag("map_zoom_out"),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = surfaceColor.copy(alpha = 0.92f),
                        contentColor = primaryColor,
                    ),
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "Zoom out")
                }
                FilledTonalIconButton(
                    onClick = {
                        if (deviceLocation is LocationFixState.Available) {
                            centerLat = deviceLocation.latitude
                            centerLon = deviceLocation.longitude
                            zoomLevel = 14.0f
                        } else if (activeMission != null) {
                            centerLat = activeMission.centerLatitude
                            centerLon = activeMission.centerLongitude
                            zoomLevel = 12.0f
                        } else {
                            centerLat = 23.5
                            centerLon = 82.0
                            zoomLevel = 4.2f
                        }
                    },
                    modifier = Modifier.size(48.dp).testTag("map_reset"),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = surfaceColor.copy(alpha = 0.92f),
                        contentColor = primaryColor,
                    ),
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Center map on GPS or mission")
                }
                FilledTonalIconButton(
                    onClick = {
                        measureMode = !measureMode
                        if (!measureMode) measureEndLatLon = null
                    },
                    modifier = Modifier.size(48.dp).testTag("map_measure_toggle"),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (measureMode) primaryColor else surfaceColor.copy(alpha = 0.92f),
                        contentColor = if (measureMode) Color.Black else primaryColor,
                    ),
                ) {
                    Icon(Icons.Filled.Straighten, contentDescription = "Measure distance on map")
                }
            }
        }

        // Bottom-left Attribution Strip
        Surface(
            color = surfaceColor.copy(alpha = 0.9f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, outlineColor.copy(alpha = 0.6f)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        ) {
            val layerLabel = when {
                customTileTemplateUrl.isNotBlank() -> "Custom Tile Mirror"
                layers.showTerrain -> "OpenTopoMap"
                else -> "OpenStreetMap"
            }
            val netLabel = when {
                isOfflineMode || networkState == NetworkState.OFFLINE -> "Offline Disk Tile Cache"
                else -> "Live Tiles ($layerLabel)"
            }
            Text(
                text = "© $layerLabel (ODbL) • Survey of India Ref • $netLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private fun resolveEventCoordinates(ev: SensorEvent, station: SensorDevice?): Pair<Double, Double>? {
    if (ev.estimatedLatitude != null && ev.estimatedLongitude != null) {
        return ev.estimatedLatitude to ev.estimatedLongitude
    }
    if (station != null && station.hasPosition && ev.estimatedRangeMeters != null && ev.estimatedAzimuthDegrees != null) {
        return RadarGeoMath.projectFromStation(
            station.latitude,
            station.longitude,
            ev.estimatedRangeMeters,
            ev.estimatedAzimuthDegrees,
        )
    }
    return null
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 12.dp, height = 3.dp)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMapLabel(
    measurer: TextMeasurer,
    text: String,
    topLeft: Offset,
    color: Color,
) {
    val result = measurer.measure(
        text = text,
        style = TextStyle(color = color, fontSize = 10.sp),
    )
    if (topLeft.x >= 0f && topLeft.y >= 0f && topLeft.x + result.size.width <= size.width && topLeft.y + result.size.height <= size.height) {
        drawText(result, topLeft = topLeft)
    }
}
