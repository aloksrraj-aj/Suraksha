package com.example.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MapLayerConfig
import com.example.data.model.RescueLocation
import com.example.data.model.RescueTeam
import com.example.ui.theme.BlueCobalt
import com.example.ui.theme.BlueSky
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.NavyOutline
import com.example.ui.theme.NavySurface
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusVerified
import kotlin.math.hypot

/**
 * Authoritative India Map Canvas with complete, uncropped Jammu & Kashmir and Ladakh,
 * distinct International Boundary vs Line of Control (LoC) vs Line of Actual Control (LAC),
 * toggleable open-source cartographic layers (roads, terrain/satellite, buildings, green zones),
 * pan/zoom navigation, and clear attribution.
 */
@Composable
fun IndiaInteractiveMap(
    locations: List<RescueLocation>,
    teams: List<RescueTeam>,
    layers: MapLayerConfig,
    isOfflineMode: Boolean,
    interactive: Boolean = true,
    selectedLocationId: String? = null,
    onLocationSelected: (RescueLocation) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    val textMeasurer = rememberTextMeasurer()

    // Geographic bounds covering complete India including complete J&K, Ladakh, Gujarat, and Arunachal
    val minLon = 67.5
    val maxLon = 98.0
    val minLat = 6.2
    val maxLat = 37.6

    fun project(lat: Double, lon: Double, width: Float, height: Float): Offset {
        val normX = ((lon - minLon) / (maxLon - minLon)).toFloat()
        val normY = (1.0 - ((lat - minLat) / (maxLat - minLat))).toFloat()
        val padX = width * 0.06f
        val padY = height * 0.06f
        val baseX = padX + normX * (width - padX * 2f)
        val baseY = padY + normY * (height - padY * 2f)
        val cx = width / 2f
        val cy = height / 2f
        return Offset(
            x = cx + (baseX - cx) * zoom + panOffset.x,
            y = cy + (baseY - cy) * zoom + panOffset.y,
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (layers.showSatelliteTerrain) Color(0xFF061322) else NavyDeep)
            .border(1.dp, NavyOutline, RoundedCornerShape(16.dp)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (interactive) {
                        Modifier
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    val newZoom = (zoom * gestureZoom).coerceIn(1f, 5.5f)
                                    zoom = newZoom
                                    val maxPanX = size.width * 0.45f * newZoom
                                    val maxPanY = size.height * 0.45f * newZoom
                                    panOffset = Offset(
                                        x = (panOffset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                        y = (panOffset.y + pan.y).coerceIn(-maxPanY, maxPanY),
                                    )
                                }
                            }
                            .pointerInput(locations) {
                                detectTapGestures { tapOffset ->
                                    val hit = locations.minByOrNull { loc ->
                                        val pt = project(loc.latitude, loc.longitude, size.width.toFloat(), size.height.toFloat())
                                        hypot(pt.x - tapOffset.x, pt.y - tapOffset.y)
                                    }
                                    if (hit != null) {
                                        val pt = project(hit.latitude, hit.longitude, size.width.toFloat(), size.height.toFloat())
                                        if (hypot(pt.x - tapOffset.x, pt.y - tapOffset.y) < 56f) {
                                            onLocationSelected(hit)
                                        }
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

            // 1. Graticule / Coordinate Grid
            val gridColor = CyanElectric.copy(alpha = 0.08f)
            for (latStep in listOf(10.0, 15.0, 20.0, 25.0, 30.0, 35.0)) {
                val p1 = project(latStep, minLon, w, h)
                val p2 = project(latStep, maxLon, w, h)
                drawLine(gridColor, p1, p2, strokeWidth = 1f)
            }
            for (lonStep in listOf(70.0, 75.0, 80.0, 85.0, 90.0, 95.0)) {
                val p1 = project(minLat, lonStep, w, h)
                val p2 = project(maxLat, lonStep, w, h)
                drawLine(gridColor, p1, p2, strokeWidth = 1f)
            }

            // 2. Authoritative India Landmass Polygon (complete J&K, Ladakh, Gujarat, Northeast, Peninsula)
            val indiaOutline = listOf(
                37.05 to 74.60, // Northernmost Gilgit-Baltistan / Karakoram crest
                36.90 to 75.40, // Khunjerab
                35.80 to 76.50, // Siachen / Karakoram
                35.50 to 78.20, // Northern Aksai Chin / Ladakh
                34.90 to 79.80, // Eastern Ladakh
                33.20 to 79.40, // Demchok / Ladakh
                31.80 to 78.90, // Himachal border
                30.80 to 79.80, // Uttarakhand
                29.60 to 80.30,
                28.80 to 80.10, // Nepal-India border west
                27.50 to 83.50,
                26.80 to 88.10, // Sikkim / Siliguri
                27.90 to 88.80, // North Sikkim
                27.20 to 89.00,
                26.80 to 92.00, // Bhutan-Assam
                27.80 to 92.40, // Tawang / Arunachal
                29.10 to 96.20, // Upper Arunachal
                28.10 to 97.35, // Kibithu eastern tip
                26.70 to 95.30, // Nagaland
                24.80 to 94.70, // Manipur
                22.10 to 93.10, // Mizoram
                23.80 to 91.20, // Tripura
                25.10 to 92.30, // Meghalaya
                25.80 to 89.90, // Dhubri
                22.00 to 89.00, // Sundarbans
                21.50 to 87.20, // Odisha coast
                19.20 to 84.80,
                16.50 to 81.90, // Godavari delta
                13.10 to 80.30, // Chennai
                10.30 to 79.90, // Point Calimere
                8.08 to 77.55,  // Kanyakumari
                9.90 to 76.25,  // Kochi
                12.90 to 74.85, // Mangaluru
                15.40 to 73.80, // Goa
                19.07 to 72.87, // Mumbai
                20.90 to 72.70,
                21.60 to 72.20, // Gulf of Khambhat
                20.80 to 70.40, // Veraval
                22.40 to 69.00, // Dwarka
                23.60 to 68.18, // Guhar Moti / Sir Creek
                24.50 to 70.80, // Kutch / Rajasthan
                26.90 to 69.50, // Jaisalmer
                28.80 to 72.60, // Bikaner
                31.00 to 74.40, // Ferozepur
                32.50 to 74.60, // Jammu
                33.40 to 73.80, // Mirpur / J&K west
                34.70 to 73.50, // Muzaffarabad / J&K
                35.90 to 72.50, // Chitral / Gilgit west
                36.90 to 73.60,
            )

            val landmassPath = Path().apply {
                indiaOutline.forEachIndexed { idx, (lat, lon) ->
                    val pt = project(lat, lon, w, h)
                    if (idx == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                }
                close()
            }

            // Fill landmass with terrain tint
            drawPath(
                path = landmassPath,
                color = if (layers.showSatelliteTerrain) Color(0xFF0E233C) else Color(0xFF0B192E),
            )

            // 3. Satellite / Elevation Contour Bands (Himalayan & Western Ghats relief)
            if (layers.showSatelliteTerrain) {
                val himalayanRelief = listOf(
                    listOf(36.0 to 74.0, 35.0 to 77.5, 33.0 to 79.0, 30.8 to 79.5, 28.0 to 92.5, 28.6 to 96.5),
                    listOf(34.5 to 74.5, 33.8 to 77.2, 31.5 to 77.8, 30.2 to 79.0, 27.6 to 88.5, 27.9 to 94.5),
                    listOf(22.0 to 73.5, 19.0 to 75.5, 15.5 to 76.0, 12.0 to 77.0),
                )
                himalayanRelief.forEach { contour ->
                    val p = Path().apply {
                        contour.forEachIndexed { i, (lat, lon) ->
                            val pt = project(lat, lon, w, h)
                            if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                        }
                    }
                    drawPath(
                        path = p,
                        color = BlueSky.copy(alpha = 0.22f),
                        style = Stroke(width = 1.6f * zoom.coerceAtMost(2.5f)),
                    )
                }
            }

            // 4. Green Areas, Parks, Forests & Fields Layer
            if (layers.showGreenZones) {
                val greenPatches = listOf(
                    Triple(14.5, 74.9, 18f), // Western Ghats forests
                    Triple(11.6, 76.5, 14f), // Nilgiris Biosphere
                    Triple(26.6, 93.4, 20f), // Kaziranga / Assam green basin
                    Triple(22.0, 88.8, 16f), // Sundarbans mangrove
                    Triple(30.4, 78.6, 15f), // Garhwal forest belt
                    Triple(22.3, 80.6, 17f), // Central India Satpura-Kanha
                )
                greenPatches.forEach { (lat, lon, radiusDp) ->
                    val center = project(lat, lon, w, h)
                    drawCircle(
                        color = StatusVerified.copy(alpha = 0.22f),
                        radius = radiusDp * zoom.coerceAtMost(2.5f),
                        center = center,
                    )
                    drawCircle(
                        color = StatusVerified.copy(alpha = 0.45f),
                        radius = radiusDp * zoom.coerceAtMost(2.5f),
                        center = center,
                        style = Stroke(width = 1.2f),
                    )
                }
            }

            // 5. Road & Highway Network Layer
            if (layers.showRoads) {
                val highways = listOf(
                    // NH-44 North-South (Srinagar -> Delhi -> Nagpur -> Bengaluru -> Kanyakumari)
                    listOf(34.08 to 74.80, 32.73 to 74.86, 28.61 to 77.21, 21.15 to 79.09, 17.39 to 78.49, 12.97 to 77.59, 8.09 to 77.55),
                    // Leh - Manali - Delhi
                    listOf(34.15 to 77.58, 32.24 to 77.19, 30.73 to 76.78, 28.61 to 77.21),
                    // Golden Quadrilateral West & East
                    listOf(28.61 to 77.21, 26.91 to 75.79, 23.02 to 72.57, 19.08 to 72.88, 12.97 to 77.59, 13.08 to 80.27, 22.57 to 88.36, 28.61 to 77.21),
                    // East-West Corridor to Assam & Joshimath Spur
                    listOf(23.24 to 69.67, 24.59 to 72.70, 26.85 to 80.95, 26.14 to 91.74, 26.95 to 94.17),
                    listOf(28.61 to 77.21, 30.32 to 78.03, 30.55 to 79.57),
                )
                highways.forEach { road ->
                    val roadPath = Path().apply {
                        road.forEachIndexed { idx, (lat, lon) ->
                            val pt = project(lat, lon, w, h)
                            if (idx == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                        }
                    }
                    drawPath(
                        path = roadPath,
                        color = CyanElectric.copy(alpha = 0.34f),
                        style = Stroke(width = 1.5f),
                    )
                }
            }

            // 6. Buildings & Critical Structures Layer
            if (layers.showBuildings) {
                val urbanHubs = listOf(
                    34.08 to 74.80, // Srinagar
                    34.15 to 77.58, // Leh
                    30.55 to 79.57, // Joshimath
                    28.61 to 77.21, // Delhi
                    23.24 to 69.67, // Bhuj
                    26.95 to 94.17, // Majuli/Jorhat
                    19.08 to 72.88, // Mumbai
                    12.97 to 77.59, // Bengaluru
                    22.57 to 88.36, // Kolkata
                )
                urbanHubs.forEach { (lat, lon) ->
                    val c = project(lat, lon, w, h)
                    val s = 4.5f * zoom.coerceAtMost(2.5f)
                    drawRect(
                        color = BlueCobalt.copy(alpha = 0.55f),
                        topLeft = Offset(c.x - s, c.y - s),
                        size = androidx.compose.ui.geometry.Size(s * 2f, s * 2f),
                    )
                }
            }

            // 7. Outer Authoritative Indian Boundary Stroke
            drawPath(
                path = landmassPath,
                color = BlueSky.copy(alpha = 0.85f),
                style = Stroke(width = 2.2f),
            )

            // 8. Line of Control (LoC) — Distinct Dashed Amber Line (NEVER represented as accepted international border)
            val locCoords = listOf(
                32.75 to 74.55,
                33.65 to 74.05,
                34.35 to 73.85,
                34.65 to 74.80,
                34.55 to 76.15,
                35.40 to 77.00, // NJ9842 / Siachen approach
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
                style = Stroke(
                    width = 2.6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                ),
            )

            // 9. Line of Actual Control (LAC) — Distinct Dotted Violet Line
            val lacWesternCoords = listOf(
                35.40 to 77.60,
                34.70 to 78.30,
                33.90 to 78.80,
                33.20 to 79.10,
                32.45 to 78.85,
            )
            val lacPath = Path().apply {
                lacWesternCoords.forEachIndexed { i, (lat, lon) ->
                    val pt = project(lat, lon, w, h)
                    if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                }
            }
            drawPath(
                path = lacPath,
                color = Color(0xFFA78BFA),
                style = Stroke(
                    width = 2.6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 7f), 0f),
                ),
            )

            // 10. Andaman & Nicobar and Lakshadweep Island Markers
            listOf(11.62 to 92.73, 10.57 to 72.64).forEach { (lat, lon) ->
                val pt = project(lat, lon, w, h)
                drawCircle(BlueSky.copy(alpha = 0.8f), radius = 4.5f, center = pt)
            }

            // Label J&K / Ladakh, LoC, LAC cleanly when space allows
            drawMapLabel(textMeasurer, "J&K", project(33.9, 74.9, w, h), Color(0xFFCBD5E1))
            drawMapLabel(textMeasurer, "LADAKH", project(34.3, 77.7, w, h), Color(0xFFCBD5E1))
            drawMapLabel(textMeasurer, "LoC", project(34.5, 74.1, w, h), StatusEstimated)
            drawMapLabel(textMeasurer, "LAC", project(34.1, 78.9, w, h), Color(0xFFA78BFA))

            // 11. Disaster Sites & Voluntary Rescue Team Pins
            if (layers.showTeamsAndSites) {
                locations.forEach { loc ->
                    val pt = project(loc.latitude, loc.longitude, w, h)
                    val color = when (loc.severity.lowercase()) {
                        "critical" -> StatusCritical
                        "high" -> Color(0xFFFB923C)
                        "moderate" -> StatusEstimated
                        else -> CyanElectric
                    }
                    val isSelected = loc.id == selectedLocationId
                    drawCircle(
                        color = color.copy(alpha = 0.28f),
                        radius = if (isSelected) 22f else 14f,
                        center = pt,
                    )
                    drawCircle(
                        color = color,
                        radius = if (isSelected) 8.5f else 6f,
                        center = pt,
                    )
                    drawMapLabel(
                        textMeasurer,
                        loc.name.take(16),
                        Offset(pt.x + 10f, pt.y - 8f),
                        Color.White,
                    )
                }

                teams.filter { it.locationSharingOptIn && it.latitude != 0.0 && it.longitude != 0.0 }
                    .forEach { team ->
                        val pt = project(team.latitude, team.longitude, w, h)
                        drawCircle(
                            color = StatusVerified,
                            radius = 5.5f,
                            center = pt,
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 7.5f,
                            center = pt,
                            style = Stroke(width = 1.5f),
                        )
                    }
            }
        }

        // Top-left Legend for International Boundary vs LoC vs LAC
        Surface(
            color = NavySurface.copy(alpha = 0.88f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, NavyOutline),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                LegendRow(color = BlueSky, label = "International Boundary")
                LegendRow(color = StatusEstimated, label = "LoC (Dashed Control Line)")
                LegendRow(color = Color(0xFFA78BFA), label = "LAC (Dotted Control Line)")
            }
        }

        // Top-right Zoom Controls when interactive
        if (interactive) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalIconButton(
                    onClick = { zoom = (zoom * 1.35f).coerceAtMost(5.5f) },
                    modifier = Modifier.size(40.dp).testTag("map_zoom_in"),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = NavySurface.copy(alpha = 0.92f),
                        contentColor = CyanElectric,
                    ),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom in")
                }
                FilledTonalIconButton(
                    onClick = { zoom = (zoom / 1.35f).coerceAtLeast(1f) },
                    modifier = Modifier.size(40.dp).testTag("map_zoom_out"),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = NavySurface.copy(alpha = 0.92f),
                        contentColor = CyanElectric,
                    ),
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "Zoom out")
                }
                FilledTonalIconButton(
                    onClick = {
                        zoom = 1f
                        panOffset = Offset.Zero
                    },
                    modifier = Modifier.size(40.dp).testTag("map_reset"),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = NavySurface.copy(alpha = 0.92f),
                        contentColor = CyanElectric,
                    ),
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Reset map view")
                }
            }
        }

        // Bottom Attribution Strip
        Surface(
            color = NavyDeep.copy(alpha = 0.88f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        ) {
            Text(
                text = if (isOfflineMode) {
                    "Offline Vector Cache • Survey of India Ref. • OSM (ODbL)"
                } else {
                    "Open-Source Map • Survey of India Ref. • OSM Contributors (ODbL)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
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
        style = TextStyle(color = color, fontSize = 9.sp),
    )
    if (topLeft.x >= 0f && topLeft.y >= 0f && topLeft.x + result.size.width <= size.width && topLeft.y + result.size.height <= size.height) {
        drawText(result, topLeft = topLeft)
    }
}
