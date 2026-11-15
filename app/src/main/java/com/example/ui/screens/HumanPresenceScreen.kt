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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.example.data.model.OrgRole
import com.example.data.model.PresenceSignal
import com.example.data.network.NetworkState
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.InfoNoteBanner
import com.example.ui.components.VerificationBadge
import com.example.ui.components.formatAgeFreshness
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusVerified
import com.example.ui.viewmodel.DataUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HumanPresenceScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    currentRole: OrgRole,
    signalsState: DataUiState<List<PresenceSignal>>,
    onReviewSignal: (PresenceSignal, String, String) -> Unit,
    onAddSignal: (String, String, Long, Long, Long, String) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var verifyTarget by remember { mutableStateOf<PresenceSignal?>(null) }
    var verifyNote by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf("all") }

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
                text = "Critical Separation: Sensor indications are ESTIMATED/UNVERIFIED occupancy hints only. Simulated readings can NEVER be promoted to Field-Verified survivors.",
                accentColor = StatusEstimated,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to "All", "hardware" to "Hardware Only", "simulated" to "Simulated Only").forEach { (key, label) ->
                        FilterChip(selected = kindFilter == key, onClick = { kindFilter = key }, label = { Text(label) })
                    }
                }
                if (currentRole.canWriteOperations()) {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.testTag("log_presence_button"),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Log Estimate", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            when (signalsState) {
                DataUiState.Loading -> CircularProgressIndicator()
                is DataUiState.Error -> InfoNoteBanner(text = signalsState.message, accentColor = StatusCritical)
                is DataUiState.Success -> {
                    val filtered = signalsState.data.filter {
                        kindFilter == "all" || it.sourceKind == kindFilter
                    }
                    if (filtered.isEmpty()) {
                        Text("No matching presence signals logged.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        filtered.forEach { signal ->
                            val isSimulated = signal.sourceKind == "simulated" || signal.isSynthetic
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
                                        Text(signal.sectorLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            VerificationBadge(if (isSimulated) "SIMULATED" else "HARDWARE")
                                            VerificationBadge(signal.verificationStatus)
                                        }
                                    }

                                    Text(
                                        text = "Estimated Range: ${signal.estimatedMinCount}–${signal.estimatedMaxCount} individuals (Confidence ${signal.confidencePercent}%)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "Sensor: ${signal.signalType} • Node: ${signal.deviceId.ifBlank { "manual" }} • Calibration: ${signal.calibrationStatus} • Health: ${signal.sensorHealth}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        text = "Source: ${signal.dataSource} • ${formatAgeFreshness(signal.observationTimestampMillis)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (signal.verificationNotes.isNotBlank()) {
                                        Text("Notes: ${signal.verificationNotes}", style = MaterialTheme.typography.bodySmall)
                                    }

                                    if (currentRole.canWriteOperations()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (!isSimulated && signal.verificationStatus != "field_verified") {
                                                OutlinedButton(
                                                    onClick = {
                                                        verifyTarget = signal
                                                        verifyNote = ""
                                                    },
                                                ) {
                                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusVerified)
                                                    Text("Field Verify", modifier = Modifier.padding(start = 4.dp))
                                                }
                                            }
                                            if (signal.verificationStatus != "outdated") {
                                                OutlinedButton(
                                                    onClick = { onReviewSignal(signal, "outdated", "Marked outdated by field team.") },
                                                ) {
                                                    Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Text("Mark Outdated", modifier = Modifier.padding(start = 4.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var sector by remember { mutableStateOf("") }
        var signalType by remember { mutableStateOf("radar_respiration") }
        var minCount by remember { mutableStateOf("1") }
        var maxCount by remember { mutableStateOf("2") }
        var conf by remember { mutableStateOf("72") }
        var source by remember { mutableStateOf("Field Acoustic/IR Probe") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Log Estimated Presence Signal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sector, onValueChange = { sector = it }, label = { Text("Sector / Void Label") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = minCount, onValueChange = { minCount = it }, label = { Text("Min Est") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = maxCount, onValueChange = { maxCount = it }, label = { Text("Max Est") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = conf, onValueChange = { conf = it }, label = { Text("Conf %") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = source, onValueChange = { source = it }, label = { Text("Data Source") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("radar_respiration", "thermal_ir", "acoustic_vibration", "co2_plume").forEach { t ->
                            FilterChip(selected = signalType == t, onClick = { signalType = t }, label = { Text(t.take(7)) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val min = minCount.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    val max = maxCount.toLongOrNull()?.coerceAtLeast(min) ?: min
                    val c = conf.toLongOrNull()?.coerceIn(0L, 100L) ?: 50L
                    if (sector.isNotBlank()) {
                        onAddSignal(sector, signalType, min, max, c, source)
                        showAddDialog = false
                    }
                }) { Text("Log Estimate") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } },
        )
    }

    verifyTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { verifyTarget = null },
            title = { Text("Field-Verify Hardware Signal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Confirm physical visual or rescue confirmation for ${target.sectorLabel}.")
                    OutlinedTextField(
                        value = verifyNote,
                        onValueChange = { verifyNote = it },
                        label = { Text("Field Verification Notes (min 3 chars)") },
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = verifyNote.trim().length >= 3,
                    onClick = {
                        onReviewSignal(target, "field_verified", verifyNote.trim())
                        verifyTarget = null
                    },
                ) { Text("Confirm Field Verification") }
            },
            dismissButton = { TextButton(onClick = { verifyTarget = null }) { Text("Cancel") } },
        )
    }
}
