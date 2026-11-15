package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.data.model.RescueRegistryEntry
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.VerificationBadge
import com.example.ui.components.formatSurakshaTimestamp
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.NavyOutline
import com.example.ui.theme.NavySurface
import com.example.ui.theme.StatusVerified
import com.example.ui.viewmodel.DataUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RescueRegistryScreen(
    settings: SurakshaSettings,
    registryState: DataUiState<List<RescueRegistryEntry>>,
    onTogglePiiReveal: (Boolean) -> Unit,
    onCreateEntry: (String, String, String, String, String, String, String) -> Unit,
    onAdvanceEntry: (RescueRegistryEntry, String, String, String) -> Unit,
) {
    val entries = (registryState as? DataUiState.Success)?.data.orEmpty()
    var selectedCategory by remember { mutableStateOf("all") }
    var showCreateDialog by remember { mutableStateOf(false) }

    val filtered = remember(entries, selectedCategory) {
        if (selectedCategory == "all") entries else entries.filter { it.category == selectedCategory }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Rescue Information Registry", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Missing persons, survivors, medical requests & verification history",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onTogglePiiReveal(!settings.revealSensitivePii) }) {
                        Icon(
                            imageVector = if (settings.revealSensitivePii) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = "Toggle sensitive contact visibility",
                            tint = CyanElectric,
                        )
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanElectric, contentColor = NavyDeep),
                        modifier = Modifier.testTag("add_registry_entry_button"),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("New Entry")
                    }
                }
            }
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    "all" to "All",
                    "missing_person" to "Missing",
                    "verified_survivor" to "Survivors",
                    "medical_request" to "Medical",
                    "disaster_site" to "Sites",
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = selectedCategory == key,
                        onClick = { selectedCategory = key },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }

        items(filtered, key = { it.id }) { entry ->
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
                            Text(
                                text = entry.category.replace('_', ' ').uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanElectric,
                            )
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        }
                        VerificationBadge(entry.verificationStatus)
                    }

                    Text(
                        text = "Location: ${entry.locationName} • Status: ${entry.rescueStatus.uppercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        text = entry.details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = CyanElectric)
                        Text(
                            text = if (settings.revealSensitivePii) {
                                "Protected Ref: ${entry.sensitiveContactMasked}"
                            } else {
                                "Protected Ref: •••••••• (Tap eye icon to view)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = "Source: ${entry.informationSource} | History: ${entry.verificationNotes} (${formatSurakshaTimestamp(entry.updatedAt ?: entry.createdAt)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entry.verificationStatus != "verified") {
                            Button(
                                onClick = {
                                    onAdvanceEntry(
                                        entry,
                                        "in_progress",
                                        "verified",
                                        "Verified by responder on ${formatSurakshaTimestamp(entry.updatedAt)}"
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusVerified, contentColor = NavyDeep),
                            ) {
                                Text("Verify Record")
                            }
                        }
                        if (entry.rescueStatus != "resolved") {
                            OutlinedButton(
                                onClick = {
                                    onAdvanceEntry(
                                        entry,
                                        "resolved",
                                        entry.verificationStatus,
                                        "Rescue operation marked resolved."
                                    )
                                },
                            ) {
                                Text("Mark Resolved")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateRegistryDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { cat, title, loc, details, contact, verif, src ->
                onCreateEntry(cat, title, loc, details, contact, verif, src)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun CreateRegistryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String, String, String) -> Unit,
) {
    var category by remember { mutableStateOf("missing_person") }
    var title by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("Ref: Masked Contact") }
    var verification by remember { mutableStateOf("unverified") }
    var source by remember { mutableStateOf("Field Intake Desk") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Rescue Registry Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("missing_person", "verified_survivor", "medical_request").forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.substringBefore("_"), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title / Person / Request") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location / Sector") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Details") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it },
                    label = { Text("Protected Contact / Medical Ref") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("Information Source") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() && location.isNotBlank() && details.isNotBlank()) {
                        onConfirm(category, title, location, details, contact, verification, source)
                    }
                },
                enabled = title.isNotBlank() && location.isNotBlank() && details.isNotBlank(),
            ) {
                Text("Save Record")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
