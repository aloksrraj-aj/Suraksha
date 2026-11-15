package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.hardware.AdapterProvisionState
import com.example.data.hardware.SensorAdapterDescriptor
import com.example.data.local.CachedSensorLogEntity
import com.example.data.local.SurakshaSettings
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.InfoNoteBanner
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.NavyOutline
import com.example.ui.theme.NavySurface
import com.example.ui.theme.StatusEstimated
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SensorStatusScreen(
    settings: SurakshaSettings,
    adapters: List<SensorAdapterDescriptor>,
    cachedLogs: List<CachedSensorLogEntity>,
    onCaptureSnapshot: (SensorAdapterDescriptor) -> Unit,
) {
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
            Text("Hardware Integration & Sensors", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Modular adapter interfaces for Raspberry Pi, Jetson, ESP32, LiDAR & Thermal units",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            InfoNoteBanner(
                text = "Honest Telemetry Guarantee: Simulated adapters are strictly labeled SIMULATED PROVIDER. Physical LiDAR, Radar, and GNSS units are labeled FUTURE HARDWARE until connected.",
                accentColor = StatusEstimated,
            )
        }

        items(adapters, key = { it.nodeId }) { adapter ->
            val isSimulated = adapter.provisionState == AdapterProvisionState.SIMULATED_PROVIDER
            val badgeColor = if (isSimulated) StatusEstimated else CyanElectric
            val badgeText = if (isSimulated) "SIMULATED PROVIDER" else "FUTURE HARDWARE"

            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, NavyOutline),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = if (isSimulated) Icons.Filled.Memory else Icons.Filled.Cable,
                                contentDescription = null,
                                tint = badgeColor,
                            )
                            Column {
                                Text(adapter.platformName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = adapter.sensorType,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CyanElectric,
                                )
                            }
                        }
                        Surface(
                            color = badgeColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.6f)),
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }

                    Text(
                        text = "Interface: ${adapter.protocolInterface}\nEndpoint: ${adapter.topicOrEndpoint}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        text = adapter.capabilitiesNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Surface(
                        color = NavyDeep,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, NavyOutline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = adapter.samplePayloadJson,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanElectric,
                            modifier = Modifier.padding(10.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = adapter.measurementStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                        )
                        if (isSimulated) {
                            OutlinedButton(onClick = { onCaptureSnapshot(adapter) }) {
                                Icon(Icons.Filled.Save, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Log to SQLite")
                            }
                        }
                    }
                }
            }
        }

        if (cachedLogs.isNotEmpty()) {
            item {
                Text("Recent Local SQLite Telemetry Logs", style = MaterialTheme.typography.titleMedium)
            }
            items(cachedLogs.take(6), key = { it.id }) { log ->
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.capturedAtMillis))
                Card(
                    colors = CardDefaults.cardColors(containerColor = NavySurface),
                    border = BorderStroke(1.dp, NavyOutline),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${log.nodeId} • $timeStr (Synthetic=${log.isSynthetic})", style = MaterialTheme.typography.labelSmall, color = CyanElectric)
                        Text(log.summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
