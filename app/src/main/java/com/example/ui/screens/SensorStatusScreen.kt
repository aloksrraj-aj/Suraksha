package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.hardware.AdapterProvisionState
import com.example.data.hardware.SensorAdapterDescriptor
import com.example.data.local.CachedSensorLogEntity
import com.example.data.local.SurakshaSettings
import com.example.data.network.NetworkState
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.InfoNoteBanner
import com.example.ui.components.VerificationBadge
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusVerified
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SensorStatusScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    adapters: List<SensorAdapterDescriptor>,
    cachedLogs: List<CachedSensorLogEntity>,
    onSaveGatewayUrl: (String) -> Unit,
    onPollAdapter: (SensorAdapterDescriptor) -> Unit,
) {
    var gatewayInput by remember(settings.hardwareGatewayUrl) { mutableStateOf(settings.hardwareGatewayUrl) }
    val blePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

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
                text = "Modular Sensor Bus: Live Wi-Fi/HTTP FastAPI gateways & BLE GATT nodes run separately from Simulated Providers and Future USB/ROS 2 contracts.",
                accentColor = MaterialTheme.colorScheme.primary,
            )

            // Edge Gateway Endpoint + BLE Permission Config
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Physical Field Hardware Integration", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = gatewayInput,
                        onValueChange = { gatewayInput = it },
                        label = { Text("Edge FastAPI Gateway URL (e.g. http://192.168.4.1:8000)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("gateway_url_input"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSaveGatewayUrl(gatewayInput) },
                            modifier = Modifier.testTag("save_gateway_url_button"),
                        ) { Text("Save Gateway") }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            OutlinedButton(
                                onClick = {
                                    blePermLauncher.launch(
                                        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                                    )
                                },
                            ) {
                                Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Grant BLE", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }

            // Adapters Catalog
            adapters.forEach { adapter ->
                val isFuture = adapter.provisionState == AdapterProvisionState.FUTURE_HARDWARE_INTERFACE
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Sensors,
                                    contentDescription = null,
                                    tint = when (adapter.provisionState) {
                                        AdapterProvisionState.LIVE_HTTP_GATEWAY, AdapterProvisionState.LIVE_BLE_GATT -> StatusVerified
                                        AdapterProvisionState.SIMULATED_PROVIDER -> StatusEstimated
                                        AdapterProvisionState.FUTURE_HARDWARE_INTERFACE -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Text(adapter.platformName, style = MaterialTheme.typography.titleSmall)
                            }
                            VerificationBadge(adapter.provisionState.label)
                        }

                        Text("${adapter.sensorType} • ${adapter.protocolInterface}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "Status: ${adapter.measurementStatus} • Health: ${adapter.sensorHealth} • Calibration: ${adapter.calibrationStatus}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(adapter.capabilitiesNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        if (!isFuture) {
                            OutlinedButton(
                                onClick = { onPollAdapter(adapter) },
                                modifier = Modifier.testTag("poll_adapter_${adapter.nodeId}"),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Poll & Fuse Packet", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }

            // Local SQLite Cached Telemetry Log
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Offline SQLite Sensor Telemetry Log (${cachedLogs.size})", style = MaterialTheme.typography.titleMedium)
                    if (cachedLogs.isEmpty()) {
                        Text("No telemetry snapshots recorded locally yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        cachedLogs.take(10).forEach { log ->
                            val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.capturedAtMillis))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${log.nodeId} [${log.sourceKind}]", style = MaterialTheme.typography.labelMedium)
                                    Text(log.summary, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("$timeLabel • ${log.confidencePercent}%", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
