package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.example.data.hardware.GatewayTransportKind
import com.example.data.hardware.SensorAdapterDescriptor
import com.example.data.local.SurakshaSettings
import com.example.data.model.OrgRole
import com.example.data.model.SensorDevice
import com.example.data.model.SensorEvent
import com.example.data.network.NetworkState
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.NoLiveSensorDataBanner
import com.example.ui.components.VerificationBadge
import com.example.ui.components.formatAgeFreshness
import com.example.ui.theme.StatusEstimated

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SensorsHubScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    currentRole: OrgRole,
    hardwareCatalog: List<SensorAdapterDescriptor>,
    sensorDevices: List<SensorDevice>,
    sensorEvents: List<SensorEvent>,
    gatewayStatusText: String,
    maskedGatewayToken: String,
    onSaveGatewayConfig: (String, String, GatewayTransportKind, String) -> Unit,
    onPollGateway: () -> Unit,
    onValidateAndIngestJson: (String) -> Unit,
    onToggleDevDemoMode: (Boolean) -> Unit,
) {
    var gatewayName by remember { mutableStateOf("Field Edge Gateway") }
    var gatewayUrl by remember(settings.hardwareGatewayUrl) { mutableStateOf(settings.hardwareGatewayUrl) }
    var gatewayToken by remember { mutableStateOf("") }
    var selectedTransport by remember(settings.gatewayTransportType) {
        mutableStateOf(
            GatewayTransportKind.entries.firstOrNull { it.value == settings.gatewayTransportType }
                ?: GatewayTransportKind.HTTP_TLS
        )
    }
    var rawJsonInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 980.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DemoAndOfflineStatusBanner(
                isDemoMode = settings.devDemoModeEnabled,
                isOfflineMode = settings.offlineMapMode,
                networkState = networkState,
                languageCode = settings.languageCode,
            )

            if (sensorDevices.none { it.connectionStatus == "connected" } && !settings.devDemoModeEnabled) {
                NoLiveSensorDataBanner(languageCode = settings.languageCode)
            }

            // Edge Gateway Configuration (AndroidKeyStore AES-256-GCM Token Vault)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Edge Gateway / Sensor Hub", style = MaterialTheme.typography.titleMedium)
                        VerificationBadge(gatewayStatusText)
                    }
                    Text(
                        text = "Stored bearer token: $maskedGatewayToken (encrypted via AndroidKeyStore AES-256-GCM).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GatewayTransportKind.entries.forEach { kind ->
                            FilterChip(
                                selected = selectedTransport == kind,
                                onClick = { selectedTransport = kind },
                                label = { Text(kind.label) },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = gatewayName,
                        onValueChange = { gatewayName = it },
                        label = { Text("Gateway Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = gatewayUrl,
                        onValueChange = { gatewayUrl = it },
                        label = { Text("Endpoint URI (https://... or wss://...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = gatewayToken,
                        onValueChange = { gatewayToken = it },
                        label = { Text("Rotate Bearer Token (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onSaveGatewayConfig(gatewayName, gatewayUrl, selectedTransport, gatewayToken) },
                            enabled = currentRole.canOperateSensors(),
                            modifier = Modifier.weight(1f).testTag("save_gateway_btn"),
                        ) { Text("Save Gateway") }
                        Button(
                            onClick = onPollGateway,
                            enabled = currentRole.canOperateSensors() && gatewayUrl.isNotBlank(),
                            modifier = Modifier.weight(1f).testTag("poll_gateway_btn"),
                        ) { Text("Connect / Poll") }
                    }
                }
            }

            // Schema v1.0.0 Validator & Operator Ingestion
            if (currentRole.canOperateSensors()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SensorEvent JSON Schema v1.0.0 Ingestor", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Validates schemaVersion 1.0.0, replay deduplication, clock skew, coordinate bounds, confidence & humanitarian label rules.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = rawJsonInput,
                            onValueChange = { rawJsonInput = it },
                            label = { Text("Paste SensorEvent JSON v1.0.0 payload") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                onValidateAndIngestJson(rawJsonInput)
                                rawJsonInput = ""
                            },
                            enabled = rawJsonInput.isNotBlank(),
                            modifier = Modifier.testTag("ingest_sensor_json_btn"),
                        ) { Text("Validate & Ingest Payload") }
                    }
                }
            }

            // Isolated Dev Demo Mode Switch (Never writes to Firestore)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, StatusEstimated.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Dev Demo Mode (Isolated Test Fixtures)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "OFF by default. Injects local UI-only UWB & FMCW sample fixtures for radar testing without writing to Firestore.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.devDemoModeEnabled,
                        onCheckedChange = onToggleDevDemoMode,
                        modifier = Modifier.testTag("dev_demo_mode_switch"),
                    )
                }
            }

            // Hardware Integration Adapter Catalog (honest AWAITING_HARDWARE_INTEGRATION status)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Hardware Adapter Contracts (${hardwareCatalog.size})", style = MaterialTheme.typography.titleSmall)
                    hardwareCatalog.forEach { adapter ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(adapter.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                VerificationBadge(adapter.integrationStatus.label)
                            }
                            Text(
                                text = "${adapter.capability.notes} Max nominal range: ${adapter.capability.nominalMaxRangeMeters.toInt()}m.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = adapter.protocolRequirement,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // Recent Validated Sensor Events
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recent Sensor Events (${sensorEvents.size})", style = MaterialTheme.typography.titleSmall)
                    if (sensorEvents.isEmpty()) {
                        Text(
                            text = "No validated sensor events in current mission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        sensorEvents.take(15).forEach { ev ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${ev.detectionType} • ${ev.sourceDevice}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        text = "Conf ${(ev.confidence * 100).toInt()}% • ${ev.signalQuality} • ${formatAgeFreshness(ev.timestampUtcMillis)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                VerificationBadge(ev.dataValidity)
                            }
                        }
                    }
                }
            }
        }
    }
}
