package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.hardware.CellOccupancy
import com.example.data.hardware.EnvScenario
import com.example.data.hardware.SlamEnvironmentFrame
import com.example.data.local.SlamSessionEntity
import com.example.data.local.SurakshaSettings
import com.example.data.network.NetworkState
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.InfoNoteBanner
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusVerified
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnvironmentMappingScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    slamFrame: SlamEnvironmentFrame,
    selectedScenario: EnvScenario,
    sweepProgress: Float,
    isScanning: Boolean,
    slamSessions: List<SlamSessionEntity>,
    onSelectScenario: (EnvScenario) -> Unit,
    onSweepProgressChange: (Float) -> Unit,
    onToggleScanning: () -> Unit,
    onSaveAndExport: (Boolean) -> Unit,
) {
    var yawAngle by remember { mutableFloatStateOf(0.55f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 960.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DemoAndOfflineStatusBanner(
                isDemoMode = settings.demoModeEnabled,
                isOfflineMode = settings.offlineMapMode,
                networkState = networkState,
                languageCode = settings.languageCode,
            )

            InfoNoteBanner(
                text = slamFrame.disclaimer,
                accentColor = StatusEstimated,
            )

            // Scenario Picker + Scan Controls
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EnvScenario.entries.forEach { scenario ->
                    FilterChip(
                        selected = selectedScenario == scenario,
                        onClick = { onSelectScenario(scenario) },
                        label = { Text(scenario.name.replace("_", " ")) },
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selectedScenario.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Coverage: ${slamFrame.scanCoveragePercent}% • Uncertainty ±${String.format(Locale.US, "%.1f", slamFrame.uncertaintyMeters)}m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Button(
                            onClick = onToggleScanning,
                            modifier = Modifier.testTag("slam_scan_toggle"),
                        ) {
                            Icon(if (isScanning) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(if (isScanning) "Pause" else "Sweep", modifier = Modifier.padding(start = 4.dp))
                        }
                    }

                    Slider(
                        value = sweepProgress,
                        onValueChange = onSweepProgressChange,
                        valueRange = 0.15f..1.0f,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onSaveAndExport(false) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Save + Export JSON", modifier = Modifier.padding(start = 4.dp))
                        }
                        OutlinedButton(onClick = { onSaveAndExport(true) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Save + Export CSV", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            // 2D Occupancy Grid + Trajectory
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "2D Occupancy Grid (Obstacles: ${slamFrame.obstacleCount}, Voids: ${slamFrame.voidCount}, Thermal: ${slamFrame.thermalCount})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val primary = MaterialTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .background(Color(0xFF071120), RoundedCornerShape(12.dp)),
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                            val n = slamFrame.gridSize
                            val cellW = size.width / n
                            val cellH = size.height / n
                            for (r in 0 until n) {
                                for (c in 0 until n) {
                                    val cell = slamFrame.cells[r * n + c]
                                    val color = when (cell) {
                                        CellOccupancy.UNKNOWN -> Color(0xFF0E1D33)
                                        CellOccupancy.FREE -> Color(0xFF15314B)
                                        CellOccupancy.OBSTACLE -> StatusCritical.copy(alpha = 0.85f)
                                        CellOccupancy.RUBBLE_VOID -> StatusEstimated.copy(alpha = 0.85f)
                                        CellOccupancy.THERMAL_ANOMALY -> Color(0xFFF43F5E)
                                    }
                                    drawRect(
                                        color = color,
                                        topLeft = Offset(c * cellW + 1f, r * cellH + 1f),
                                        size = Size((cellW - 2f).coerceAtLeast(1f), (cellH - 2f).coerceAtLeast(1f)),
                                    )
                                }
                            }
                            if (slamFrame.trajectoryPoints.size > 1) {
                                val path = Path().apply {
                                    slamFrame.trajectoryPoints.forEachIndexed { idx, (nx, ny) ->
                                        val px = nx * size.width
                                        val py = ny * size.height
                                        if (idx == 0) moveTo(px, py) else lineTo(px, py)
                                    }
                                }
                                drawPath(path, color = primary, style = Stroke(width = 3f))
                            }
                        }
                    }
                }
            }

            // 3D Isometric Point Cloud Preview
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "3D Point Cloud Projection (${slamFrame.pointCloud.size} points • Simulated)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Slider(value = yawAngle, onValueChange = { yawAngle = it }, valueRange = 0.0f..3.14f)
                    val primary = MaterialTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .background(Color(0xFF071120), RoundedCornerShape(12.dp)),
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cx = size.width / 2f
                            val cy = size.height * 0.62f
                            val cosA = cos(yawAngle)
                            val sinA = sin(yawAngle)
                            val scale = minOf(size.width, size.height) / 520f
                            slamFrame.pointCloud.forEach { pt ->
                                val rx = pt.xMeters * cosA - pt.yMeters * sinA
                                val ry = pt.xMeters * sinA + pt.yMeters * cosA
                                val sx = cx + rx * scale
                                val sy = cy + ry * 0.45f * scale - pt.zMeters * 8f
                                val dotColor = when {
                                    pt.isThermalHotspot -> StatusCritical
                                    pt.zMeters > 3.0f -> StatusEstimated
                                    pt.zMeters > 0.5f -> primary
                                    else -> StatusVerified.copy(alpha = 0.5f)
                                }
                                drawCircle(dotColor, radius = if (pt.isThermalHotspot) 4.5f else 2.2f, center = Offset(sx, sy))
                            }
                        }
                    }
                }
            }

            // Local SLAM Scan Session History
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saved Scan Sessions (${slamSessions.size})", style = MaterialTheme.typography.titleMedium)
                    if (slamSessions.isEmpty()) {
                        Text("No SLAM sessions saved locally yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        slamSessions.take(5).forEach { sess ->
                            val timeLabel = SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(sess.createdAtMillis))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("${sess.scenario} (${(sess.sweepProgress * 100).toInt()}%)", style = MaterialTheme.typography.bodySmall)
                                Text("$timeLabel • ${sess.uncertaintyNote}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
