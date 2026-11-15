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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.example.data.local.OfflineMapRegionEntity
import com.example.data.local.SurakshaSettings
import com.example.data.model.MapLayerConfig
import com.example.data.model.RescueLocation
import com.example.data.model.RescueTeam
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.IndiaInteractiveMap
import com.example.ui.components.InfoNoteBanner
import com.example.ui.components.SeverityBadge
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
fun LiveMapScreen(
    settings: SurakshaSettings,
    locationsState: DataUiState<List<RescueLocation>>,
    teamsState: DataUiState<List<RescueTeam>>,
    layers: MapLayerConfig,
    offlineRegions: List<OfflineMapRegionEntity>,
    onUpdateLayers: ((MapLayerConfig) -> MapLayerConfig) -> Unit,
    onToggleOfflineMode: (Boolean) -> Unit,
    onToggleRegionDownload: (String, Boolean) -> Unit,
    onRegisterLocation: (
        name: String,
        region: String,
        hazardType: String,
        severity: String,
        lat: Double,
        lon: Double,
        verificationStatus: String,
        dataSource: String,
    ) -> Unit,
) {
    val locations = (locationsState as? DataUiState.Success)?.data.orEmpty()
    val teams = (teamsState as? DataUiState.Success)?.data.orEmpty()
    var selectedLocation by remember { mutableStateOf<RescueLocation?>(null) }
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
                    Text("Interactive India Rescue Map", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Uncropped J&K & Ladakh • LoC/LAC distinguished • Open-source layers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanElectric, contentColor = NavyDeep),
                    modifier = Modifier.testTag("add_disaster_site_button"),
                ) {
                    Icon(Icons.Filled.AddLocationAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add Site")
                }
            }
        }

        // Interactive Map Canvas
        item {
            IndiaInteractiveMap(
                locations = locations,
                teams = teams,
                layers = layers,
                isOfflineMode = settings.offlineMapMode,
                interactive = true,
                selectedLocationId = selectedLocation?.id,
                onLocationSelected = { selectedLocation = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
            )
        }

        if (selectedLocation != null) {
            val loc = selectedLocation!!
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NavySurface),
                    border = BorderStroke(1.dp, CyanElectric),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(loc.name, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SeverityBadge(loc.severity)
                                VerificationBadge(loc.verificationStatus)
                            }
                        }
                        Text(
                            text = "${loc.region} • ${"%.4f".format(loc.latitude)}°N, ${"%.4f".format(loc.longitude)}°E",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanElectric,
                        )
                        Text(
                            text = "Hazard: ${loc.hazardType} • Source: ${loc.dataSource} • ${formatSurakshaTimestamp(loc.updatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Map Layer Toggles
        item {
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
                        ) {
                            Icon(Icons.Filled.Layers, contentDescription = null, tint = CyanElectric)
                            Text("Map Layers & Offline Mode", style = MaterialTheme.typography.titleMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Offline", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = settings.offlineMapMode,
                                onCheckedChange = onToggleOfflineMode,
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LayerChip("Roads", layers.showRoads) {
                            onUpdateLayers { it.copy(showRoads = !it.showRoads) }
                        }
                        LayerChip("Satellite / Terrain", layers.showSatelliteTerrain) {
                            onUpdateLayers { it.copy(showSatelliteTerrain = !it.showSatelliteTerrain) }
                        }
                        LayerChip("Buildings", layers.showBuildings) {
                            onUpdateLayers { it.copy(showBuildings = !it.showBuildings) }
                        }
                        LayerChip("Parks & Forests", layers.showGreenZones) {
                            onUpdateLayers { it.copy(showGreenZones = !it.showGreenZones) }
                        }
                        LayerChip("Teams & Sites", layers.showTeamsAndSites) {
                            onUpdateLayers { it.copy(showTeamsAndSites = !it.showTeamsAndSites) }
                        }
                    }
                }
            }
        }

        // Offline Regional Vector Cache Packs (SQLite Room)
        item {
            Text("Offline Regional Map Packs (SQLite Cache)", style = MaterialTheme.typography.titleMedium)
        }

        items(offlineRegions, key = { it.regionId }) { region ->
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, NavyOutline),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(region.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${region.boundingBoxLabel} • ${region.sizeMb} MB • ${region.tileCount} vector tiles",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanElectric,
                        )
                        Text(
                            text = region.attribution,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onToggleRegionDownload(region.regionId, !region.isDownloaded) },
                    ) {
                        Icon(
                            imageVector = if (region.isDownloaded) Icons.Filled.CloudDone else Icons.Filled.CloudDownload,
                            contentDescription = null,
                            tint = if (region.isDownloaded) StatusVerified else CyanElectric,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (region.isDownloaded) "Cached" else "Cache")
                    }
                }
            }
        }

        item {
            InfoNoteBanner(
                text = "Geographical Attribution: Uses open-source OpenStreetMap (ODbL) & Survey of India reference boundaries. Disputed control lines (LoC/LAC) are visually distinct and never cropped."
            )
        }
    }

    if (showAddDialog) {
        RegisterLocationDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, region, hazard, severity, lat, lon, verif, source ->
                onRegisterLocation(name, region, hazard, severity, lat, lon, verif, source)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun LayerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = CyanElectric.copy(alpha = 0.2f),
            selectedLabelColor = CyanElectric,
        ),
    )
}

@Composable
private fun RegisterLocationDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, Double, Double, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("Uttarakhand") }
    var latStr by remember { mutableStateOf("30.3165") }
    var lonStr by remember { mutableStateOf("78.0322") }
    var hazard by remember { mutableStateOf("landslide") }
    var severity by remember { mutableStateOf("high") }
    var verification by remember { mutableStateOf("unverified") }
    var source by remember { mutableStateOf("Field Dispatch Report") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register Disaster Location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Site Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it },
                    label = { Text("State / District") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latStr,
                        onValueChange = { latStr = it },
                        label = { Text("Lat (°N)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = lonStr,
                        onValueChange = { lonStr = it },
                        label = { Text("Lon (°E)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("landslide", "flood", "earthquake").forEach { option ->
                        FilterChip(
                            selected = hazard == option,
                            onClick = { hazard = option },
                            label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("critical", "high", "moderate").forEach { option ->
                        FilterChip(
                            selected = severity == option,
                            onClick = { severity = option },
                            label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
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
                    val lat = latStr.toDoubleOrNull()?.coerceIn(-90.0, 90.0) ?: 30.3165
                    val lon = lonStr.toDoubleOrNull()?.coerceIn(-180.0, 180.0) ?: 78.0322
                    if (name.isNotBlank()) {
                        onConfirm(name, region, hazard, severity, lat, lon, verification, source)
                    }
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Save Site")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
