package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.hardware.CellOccupancy
import com.example.data.hardware.EnvScenario
import com.example.data.hardware.SlamEnvironmentFrame
import com.example.data.local.SurakshaSettings
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.InfoNoteBanner
import com.example.ui.theme.BlueSky
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.NavyOutline
import com.example.ui.theme.NavySurface
import com.example.ui.theme.NavySurfaceHigh
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusVerified
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnvironmentMappingScreen(
    settings: SurakshaSettings,
    slamFrame: SlamEnvironmentFrame,
    selectedScenario: EnvScenario,
    sweepProgress: Float,
    isScanning: Boolean,
    onSelectScenario: (EnvScenario) -> Unit,
    onSweepProgressChange: (Float) -> Unit,
    onToggleScanning: () -> Unit,
) {
    var show3dPointCloud by remember { mutableStateOf(false) }
    var measurePointA by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var measurePointB by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var yawAngle by remember { mutableFloatStateOf(0.55f) }
    var pitchAngle by remember { mutableFloatStateOf(0.45f) }

    val measuredMeters = remember(measurePointA, measurePointB) {
        val a = measurePointA
        val b = measurePointB
        if (a != null && b != null) {
            hypot((b.first - a.first) * 500f, (b.second - a.second) * 500f)
        } else {
            null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DemoAndOfflineStatusBanner(
                isDemoMode = settings.demoModeEnabled,
                isOfflineMode = settings.offlineMapMode,
            )
        }

        item {
            InfoNoteBanner(
                text = slamFrame.disclaimer,
                accentColor = StatusEstimated,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Autonomous Env Mapping", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "${slamFrame.dataSource} • Coverage: ${slamFrame.scanCoveragePercent}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyanElectric,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { show3dPointCloud = !show3dPointCloud },
                        modifier = Modifier.testTag("toggle_3d_cloud_button"),
                    ) {
                        Icon(Icons.Filled.ViewInAr, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (show3dPointCloud) "2D Grid" else "3D Cloud")
                    }
                    Button(
                        onClick = onToggleScanning,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanElectric, contentColor = NavyDeep),
                        modifier = Modifier.testTag("toggle_slam_scan_button"),
                    ) {
                        Icon(if (isScanning) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (isScanning) "Pause" else "Scan")
                    }
                }
            }
        }

        // Scenario Selection
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EnvScenario.entries.forEach { scenario ->
                    FilterChip(
                        selected = scenario == selectedScenario,
                        onClick = {
                            measurePointA = null
                            measurePointB = null
                            onSelectScenario(scenario)
                        },
                        label = { Text(scenario.label.substringBefore(" ("), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }

        // Main Visualization Canvas (2D Occupancy Grid OR 3D Point Cloud)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, NavyOutline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!show3dPointCloud) {
                        // 2D 500m x 500m Occupancy Grid with Tap Distance Measurement
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(NavyDeep)
                                .border(1.dp, NavyOutline, RoundedCornerShape(12.dp)),
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectTapGestures { offset ->
                                            val nx = (offset.x / size.width).coerceIn(0f, 1f)
                                            val ny = (offset.y / size.height).coerceIn(0f, 1f)
                                            if (measurePointA == null || measurePointB != null) {
                                                measurePointA = nx to ny
                                                measurePointB = null
                                            } else {
                                                measurePointB = nx to ny
                                            }
                                        }
                                    }
                            ) {
                                val n = slamFrame.gridSize
                                val cellW = size.width / n
                                val cellH = size.height / n

                                for (row in 0 until n) {
                                    for (col in 0 until n) {
                                        val occ = slamFrame.cells[row * n + col]
                                        val color = when (occ) {
                                            CellOccupancy.UNKNOWN -> Color(0xFF0A1424)
                                            CellOccupancy.FREE -> Color(0xFF102A44)
                                            CellOccupancy.OBSTACLE -> CyanElectric.copy(alpha = 0.85f)
                                            CellOccupancy.RUBBLE_VOID -> Color(0xFF2563EB).copy(alpha = 0.6f)
                                            CellOccupancy.THERMAL_ANOMALY -> StatusEstimated
                                        }
                                        drawRect(
                                            color = color,
                                            topLeft = Offset(col * cellW + 1f, row * cellH + 1f),
                                            size = Size(cellW - 2f, cellH - 2f),
                                        )
                                    }
                                }

                                // SLAM Trajectory Path
                                if (slamFrame.trajectoryPoints.size > 1) {
                                    val path = Path().apply {
                                        slamFrame.trajectoryPoints.forEachIndexed { idx, (nx, ny) ->
                                            val pt = Offset(nx * size.width, ny * size.height)
                                            if (idx == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                                        }
                                    }
                                    drawPath(path, StatusVerified, style = Stroke(width = 3f))
                                    val last = slamFrame.trajectoryPoints.last()
                                    drawCircle(
                                        color = StatusVerified,
                                        radius = 8f,
                                        center = Offset(last.first * size.width, last.second * size.height),
                                    )
                                }

                                // Distance measurement overlay
                                val a = measurePointA
                                val b = measurePointB
                                if (a != null) {
                                    val pa = Offset(a.first * size.width, a.second * size.height)
                                    drawCircle(Color.White, radius = 8f, center = pa)
                                    if (b != null) {
                                        val pb = Offset(b.first * size.width, b.second * size.height)
                                        drawCircle(Color.White, radius = 8f, center = pb)
                                        drawLine(Color.White, pa, pb, strokeWidth = 3f)
                                    }
                                }
                            }

                            Surface(
                                color = NavySurface.copy(alpha = 0.88f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp),
                            ) {
                                Text(
                                    text = "2D SLAM Occupancy (500m × 500m, 20m/cell)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanElectric,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Filled.Straighten, contentDescription = null, tint = CyanElectric)
                                Text(
                                    text = if (measuredMeters != null) {
                                        "Measured Distance: ${"%.1f".format(measuredMeters)} m"
                                    } else {
                                        "Tap two grid points to measure distance"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (measuredMeters != null) StatusVerified else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (measurePointA != null) {
                                OutlinedButton(onClick = {
                                    measurePointA = null
                                    measurePointB = null
                                }) {
                                    Text("Clear")
                                }
                            }
                        }
                    } else {
                        // Interactive 3D Point-Cloud Visualization
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(NavyDeep)
                                .border(1.dp, NavyOutline, RoundedCornerShape(12.dp)),
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            yawAngle += dragAmount.x * 0.008f
                                            pitchAngle = (pitchAngle + dragAmount.y * 0.006f).coerceIn(0.15f, 1.35f)
                                        }
                                    }
                            ) {
                                val cx = size.width / 2f
                                val cy = size.height * 0.56f
                                val scale = size.minDimension / 560f
                                val cosY = cos(yawAngle)
                                val sinY = sin(yawAngle)
                                val cosP = cos(pitchAngle)
                                val sinP = sin(pitchAngle)

                                slamFrame.pointCloud.forEach { pt ->
                                    val rx = pt.xMeters * cosY - pt.yMeters * sinY
                                    val ry = pt.xMeters * sinY + pt.yMeters * cosY
                                    val rz = pt.zMeters * 8f

                                    val projX = cx + rx * scale
                                    val projY = cy + (ry * cosP - rz * sinP) * scale
                                    val color = when {
                                        pt.isThermalHotspot -> StatusEstimated
                                        pt.zMeters > 4f -> CyanElectric
                                        pt.zMeters > 1f -> BlueSky
                                        else -> Color(0xFF1E3A5F)
                                    }
                                    drawCircle(
                                        color = color,
                                        radius = if (pt.isThermalHotspot) 5.5f else 2.8f,
                                        center = Offset(projX, projY),
                                    )
                                }
                            }

                            Surface(
                                color = NavySurface.copy(alpha = 0.88f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp),
                            ) {
                                Text(
                                    text = "3D Point Cloud (${slamFrame.pointCloud.size} pts) • Drag to orbit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanElectric,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    // Legend
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        GridLegendDot(CyanElectric, "Obstacle / Wall")
                        GridLegendDot(Color(0xFF2563EB), "Rubble / Void")
                        GridLegendDot(StatusEstimated, "Thermal Anomaly (Est.)")
                        GridLegendDot(StatusVerified, "SLAM Path")
                    }

                    Text(
                        text = "Scan Sweep Progress (${slamFrame.scanCoveragePercent}%)",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = sweepProgress,
                        onValueChange = onSweepProgressChange,
                        valueRange = 0.15f..1.0f,
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurfaceHigh),
                border = BorderStroke(1.dp, NavyOutline),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Sector Notes: ${selectedScenario.label}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = selectedScenario.areaNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Planned Hardware Pipeline: LiDAR + Radar + Thermal + GPS/IMU -> ROS 2 SLAM -> Offline Occupancy Tile Cache.",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanElectric,
                    )
                }
            }
        }
    }
}

@Composable
private fun GridLegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(10.dp)
                .background(color, RoundedCornerShape(3.dp))
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
