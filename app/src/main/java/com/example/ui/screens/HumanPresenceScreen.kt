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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.SurakshaSettings
import com.example.data.model.PresenceSignal
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.EthicalSafetyBanner
import com.example.ui.components.InfoNoteBanner
import com.example.ui.components.VerificationBadge
import com.example.ui.components.formatSurakshaTimestamp
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.NavyOutline
import com.example.ui.theme.NavySurface
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusOutdated
import com.example.ui.theme.StatusUnverified
import com.example.ui.theme.StatusVerified
import com.example.ui.viewmodel.DataUiState

@Composable
fun HumanPresenceScreen(
    settings: SurakshaSettings,
    signalsState: DataUiState<List<PresenceSignal>>,
    onVerifySignal: (PresenceSignal, String) -> Unit,
    onAddSignal: (String, String, Long, Long, Long, String) -> Unit,
) {
    val signals = (signalsState as? DataUiState.Success)?.data.orEmpty()
    var showAddDialog by remember { mutableStateOf(false) }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Human Presence Estimation", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Sensor indications only — never confirmed survivor counts",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusEstimated,
                    )
                }
                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanElectric, contentColor = NavyDeep),
                    modifier = Modifier.testTag("add_presence_signal_button"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Log Signal")
                }
            }
        }

        item {
            InfoNoteBanner(
                text = "Important Distinction: Sensor anomalies (Thermal IR, UWB Micro-Doppler, Acoustic, CO2) indicate possible human presence in rubble or flood zones and MUST be verified by field rescue personnel.",
                accentColor = StatusEstimated,
            )
        }

        items(signals, key = { it.id }) { signal ->
            val confColor = when {
                signal.verificationStatus == "verified" -> StatusVerified
                signal.verificationStatus == "outdated" -> StatusOutdated
                signal.confidencePercent >= 70 -> StatusEstimated
                else -> StatusUnverified
            }
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(signal.sectorLabel, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Signal: ${signal.signalType} • Source: ${signal.dataSource}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        VerificationBadge(signal.verificationStatus)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (signal.verificationStatus == "verified") {
                                "Field-verified occupancy: ${signal.estimatedMinCount}–${signal.estimatedMaxCount}"
                            } else {
                                "Possible occupancy (Est.): ${signal.estimatedMinCount}–${signal.estimatedMaxCount}"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = CyanElectric,
                        )
                        Text(
                            text = "${signal.confidencePercent}% conf.",
                            style = MaterialTheme.typography.labelMedium,
                            color = confColor,
                        )
                    }

                    LinearProgressIndicator(
                        progress = { (signal.confidencePercent / 100f).coerceIn(0f, 1f) },
                        color = confColor,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Observed: ${formatSurakshaTimestamp(signal.updatedAt ?: signal.createdAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (signal.verificationStatus != "outdated") {
                                OutlinedButton(
                                    onClick = { onVerifySignal(signal, "outdated") },
                                ) {
                                    Icon(Icons.Filled.History, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Outdated")
                                }
                            }
                            if (signal.verificationStatus != "verified") {
                                Button(
                                    onClick = { onVerifySignal(signal, "verified") },
                                    colors = ButtonDefaults.buttonColors(containerColor = StatusVerified, contentColor = NavyDeep),
                                ) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Field Verify")
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            EthicalSafetyBanner()
        }
    }

    if (showAddDialog) {
        AddPresenceSignalDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { sector, type, minC, maxC, conf, src ->
                onAddSignal(sector, type, minC, maxC, conf, src)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AddPresenceSignalDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, Long, Long, Long, String) -> Unit,
) {
    var sector by remember { mutableStateOf("") }
    var signalType by remember { mutableStateOf("radar_respiration") }
    var minStr by remember { mutableStateOf("1") }
    var maxStr by remember { mutableStateOf("2") }
    var confStr by remember { mutableStateOf("70") }
    var source by remember { mutableStateOf("Simulated UWB Radar") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Presence Observation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sector,
                    onValueChange = { sector = it },
                    label = { Text("Sector / Structure Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("thermal_ir", "radar_respiration", "acoustic_vibration").forEach { option ->
                        FilterChip(
                            selected = signalType == option,
                            onClick = { signalType = option },
                            label = { Text(option.substringBefore("_"), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minStr,
                        onValueChange = { minStr = it },
                        label = { Text("Est. Min") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxStr,
                        onValueChange = { maxStr = it },
                        label = { Text("Est. Max") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = confStr,
                        onValueChange = { confStr = it },
                        label = { Text("Conf %") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("Sensor / Data Source") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val minC = (minStr.toLongOrNull() ?: 1L).coerceIn(0L, 100L)
                    val maxC = (maxStr.toLongOrNull() ?: minC).coerceIn(minC, 100L)
                    val conf = (confStr.toLongOrNull() ?: 65L).coerceIn(0L, 100L)
                    if (sector.isNotBlank()) {
                        onConfirm(sector, signalType, minC, maxC, conf, source)
                    }
                },
                enabled = sector.isNotBlank(),
            ) {
                Text("Log Estimate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
