package com.example.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.SurakshaSettings
import com.example.data.model.OrgRole
import com.example.data.model.RescueRegistryEntry
import com.example.data.network.NetworkState
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.InfoNoteBanner
import com.example.ui.components.VerificationBadge
import com.example.ui.components.formatSurakshaTimestamp
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.viewmodel.DataUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RescueRegistryScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    currentRole: OrgRole,
    registryState: DataUiState<List<RescueRegistryEntry>>,
    findDuplicateMatch: (String, String) -> RescueRegistryEntry?,
    onTogglePiiReveal: (Boolean) -> Unit,
    onCreateEntry: (String, String, String, String, String, String, String, String, Boolean, String) -> Unit,
    onAdvanceEntry: (RescueRegistryEntry, String, String, String) -> Unit,
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("all") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var reviewTarget by remember { mutableStateOf<RescueRegistryEntry?>(null) }

    val canRevealPii = currentRole.canRevealSensitivePii() && settings.revealSensitivePii

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onTogglePiiReveal(!settings.revealSensitivePii) },
                    enabled = currentRole.canRevealSensitivePii(),
                    modifier = Modifier.testTag("toggle_pii_button"),
                ) {
                    Icon(
                        imageVector = if (canRevealPii) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = when {
                            !currentRole.canRevealSensitivePii() -> "PII Masked (${currentRole.label})"
                            canRevealPii -> "Hide Contact PII"
                            else -> "Reveal Masked Contact"
                        },
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }

                if (currentRole.canWriteOperations()) {
                    Button(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.testTag("add_registry_entry_button"),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("New Record", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search name, location, or details") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("registry_search_input"),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    "all" to "All",
                    "missing_person" to "Missing Persons",
                    "verified_survivor" to "Verified Survivors",
                    "medical_request" to "Medical Requests",
                    "disaster_site" to "Disaster Sites",
                ).forEach { (key, label) ->
                    FilterChip(selected = categoryFilter == key, onClick = { categoryFilter = key }, label = { Text(label) })
                }
            }

            when (registryState) {
                DataUiState.Loading -> CircularProgressIndicator()
                is DataUiState.Error -> InfoNoteBanner(text = registryState.message, accentColor = StatusCritical)
                is DataUiState.Success -> {
                    val filtered = registryState.data.filter { entry ->
                        (categoryFilter == "all" || entry.category == categoryFilter) &&
                            (searchQuery.isBlank() ||
                                entry.title.contains(searchQuery, ignoreCase = true) ||
                                entry.locationName.contains(searchQuery, ignoreCase = true) ||
                                entry.details.contains(searchQuery, ignoreCase = true))
                    }
                    if (filtered.isEmpty()) {
                        Text("No matching registry records found.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        filtered.forEach { entry ->
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
                                        Text(entry.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            VerificationBadge(entry.rescueStatus)
                                            VerificationBadge(entry.verificationStatus)
                                        }
                                    }
                                    Text("${entry.locationName} • Category: ${entry.category}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(entry.details, style = MaterialTheme.typography.bodySmall)
                                    if (entry.photoConsentGranted && entry.photoUriLocal.isNotBlank()) {
                                        val bmp = remember(entry.photoUriLocal) {
                                            runCatching {
                                                context.contentResolver.openInputStream(Uri.parse(entry.photoUriLocal))?.use { stream ->
                                                    BitmapFactory.decodeStream(stream)
                                                }
                                            }.getOrNull()
                                        }
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = "Consent-granted field photo",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(140.dp)
                                                    .clip(RoundedCornerShape(10.dp)),
                                            )
                                        }
                                    }
                                    val contactText = if (canRevealPii) {
                                        entry.sensitiveContactMasked.ifBlank { "No contact recorded" }
                                    } else {
                                        "***-***-MASKED (Authorized Admin/Leader/Medic only)"
                                    }
                                    Text("Contact Ref: $contactText", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        text = "Source: ${entry.informationSource} • ${formatSurakshaTimestamp(entry.updatedAt)} • Notes: ${entry.verificationNotes}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (currentRole.canWriteOperations()) {
                                        OutlinedButton(onClick = { reviewTarget = entry }) {
                                            Text("Update Verification / Status")
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

    if (showCreateDialog) {
        var category by remember { mutableStateOf("missing_person") }
        var title by remember { mutableStateOf("") }
        var locationName by remember { mutableStateOf("") }
        var details by remember { mutableStateOf("") }
        var contact by remember { mutableStateOf("") }
        var source by remember { mutableStateOf("Field Family / Responder Report") }
        var pickedUri by remember { mutableStateOf("") }
        var photoConsent by remember { mutableStateOf(false) }

        val photoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                pickedUri = uri.toString()
            }
        }
        val duplicateMatch = remember(title, locationName) {
            if (title.length >= 3 && locationName.length >= 3) findDuplicateMatch(title, locationName) else null
        }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Rescue Registry Entry") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (duplicateMatch != null) {
                        InfoNoteBanner(
                            text = "Possible duplicate of existing open record '${duplicateMatch.title}' (${duplicateMatch.id}). Saving will link as duplicate.",
                            accentColor = StatusEstimated,
                        )
                    }
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Name / Request Title") }, singleLine = true)
                    OutlinedTextField(value = locationName, onValueChange = { locationName = it }, label = { Text("Last Seen / Sector Location") }, singleLine = true)
                    OutlinedTextField(value = details, onValueChange = { details = it }, label = { Text("Details & Identifiers") })
                    OutlinedTextField(value = contact, onValueChange = { contact = it }, label = { Text("Masked Contact Reference") }, singleLine = true)
                    OutlinedTextField(value = source, onValueChange = { source = it }, label = { Text("Information Source") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("missing_person", "verified_survivor", "medical_request", "disaster_site").forEach { c ->
                            FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.take(7)) })
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(if (pickedUri.isBlank()) "Attach Optional Photo (Zero-Permission)" else "Photo Attached", modifier = Modifier.padding(start = 6.dp))
                    }
                    if (pickedUri.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = photoConsent, onCheckedChange = { photoConsent = it })
                            Text("Consent verified to store local photo reference", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = title.isNotBlank() && locationName.isNotBlank() && details.isNotBlank(),
                    onClick = {
                        onCreateEntry(
                            category,
                            title,
                            locationName,
                            details,
                            contact,
                            "reported",
                            source,
                            pickedUri,
                            photoConsent,
                            duplicateMatch?.id ?: "",
                        )
                        showCreateDialog = false
                    },
                ) { Text("Save Entry") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } },
        )
    }

    reviewTarget?.let { target ->
        var verStatus by remember { mutableStateOf(target.verificationStatus) }
        var rescueStatus by remember { mutableStateOf(target.rescueStatus) }
        var notes by remember { mutableStateOf(target.verificationNotes) }

        AlertDialog(
            onDismissRequest = { reviewTarget = null },
            title = { Text("Verify / Advance Record") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.title, style = MaterialTheme.typography.titleSmall)
                    Text("Verification Status (6 Levels)", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("reported", "under_verification", "verified", "unverified", "duplicate", "resolved").forEach { v ->
                            FilterChip(selected = verStatus == v, onClick = { verStatus = v }, label = { Text(v.replace("_", " ")) })
                        }
                    }
                    Text("Rescue Status", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("open", "dispatched", "in_progress", "resolved").forEach { r ->
                            FilterChip(selected = rescueStatus == r, onClick = { rescueStatus = r }, label = { Text(r.replace("_", " ")) })
                        }
                    }
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Verification Notes") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    onAdvanceEntry(target, rescueStatus, verStatus, notes)
                    reviewTarget = null
                }) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { reviewTarget = null }) { Text("Cancel") } },
        )
    }
}
