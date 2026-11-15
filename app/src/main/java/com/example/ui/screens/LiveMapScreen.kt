package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.data.location.LocationFixState
import com.example.data.map.IndiaPlace
import com.example.data.map.OsmTileManager
import com.example.data.map.RegionDownloadProgress
import com.example.data.model.MapLayerConfig
import com.example.data.model.OrgRole
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.example.data.network.NetworkState
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.IndiaInteractiveMap
import com.example.ui.components.InfoNoteBanner
import com.example.ui.components.SeverityBadge
import com.example.ui.components.VerificationBadge
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusVerified
import com.example.ui.viewmodel.DataUiState
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveMapScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    currentRole: OrgRole,
    locationsState: DataUiState<List<RescueLocation>>,
    teamsState: DataUiState<List<RescueTeam>>,
    signalsState: DataUiState<List<PresenceSignal>>,
    registryState: DataUiState<List<RescueRegistryEntry>>,
    layers: MapLayerConfig,
    offlineRegions: List<OfflineMapRegionEntity>,
    downloadProgress: Map<String, RegionDownloadProgress>,
    totalCachedTiles: Int,
    totalCachedBytes: Long,
    deviceLocation: LocationFixState,
    tileManager: OsmTileManager,
    searchResults: List<IndiaPlace>,
    onSearchPlaces: (String) -> Unit,
    onClearSearch: () -> Unit,
    onUpdateLayers: ((MapLayerConfig) -> MapLayerConfig) -> Unit,
    onToggleOfflineMode: (Boolean) -> Unit,
    onStartRegionDownload: (OfflineMapRegionEntity) -> Unit,
    onCancelRegionDownload: (String) -> Unit,
    onDeleteRegionCache: (String) -> Unit,
    onRegisterLocation: (String, String, String, String, Double, Double, String, String) -> Unit,
) {
    val locations = (locationsState as? DataUiState.Success)?.data.orEmpty()
    val teams = (teamsState as? DataUiState.Success)?.data.orEmpty()
    val signals = (signalsState as? DataUiState.Success)?.data.orEmpty()
    val registry = (registryState as? DataUiState.Success)?.data.orEmpty()

    var selectedLocation by remember { mutableStateOf<RescueLocation?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var focusLat by remember { mutableStateOf<Double?>(null) }
    var focusLon by remember { mutableStateOf<Double?>(null) }
    var focusZoom by remember { mutableStateOf<Float?>(null) }

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

            // Search bar (City, District, State, or "lat, lon")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search city, state, or lat, lon") },
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = {
                                searchQuery = ""
                                onClearSearch()
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("map_search_input"),
                )
                Button(
                    onClick = { onSearchPlaces(searchQuery) },
                    modifier = Modifier.height(52.dp).testTag("map_search_button"),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Search map location")
                }
            }

            if (searchResults.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    searchResults.take(5).forEach { place ->
                        FilterChip(
                            selected = focusLat == place.latitude && focusLon == place.longitude,
                            onClick = {
                                focusLat = place.latitude
                                focusLon = place.longitude
                                focusZoom = 9.5f
                            },
                            label = { Text("${place.name} (${place.stateOrUt})") },
                        )
                    }
                }
            }

            // Toggleable Map Layers
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = layers.showRoads && !layers.showTerrain,
                    onClick = { onUpdateLayers { it.copy(showRoads = true, showTerrain = false) } },
                    label = { Text("OSM Street") },
                )
                FilterChip(
                    selected = layers.showTerrain,
                    onClick = { onUpdateLayers { it.copy(showTerrain = !it.showTerrain) } },
                    label = { Text("OpenTopo Terrain") },
                )
                FilterChip(
                    selected = layers.showDisasterSites,
                    onClick = { onUpdateLayers { it.copy(showDisasterSites = !it.showDisasterSites) } },
                    label = { Text("Sites") },
                )
                FilterChip(
                    selected = layers.showTeams,
                    onClick = { onUpdateLayers { it.copy(showTeams = !it.showTeams) } },
                    label = { Text("Teams") },
                )
                FilterChip(
                    selected = layers.showPresenceEstimates,
                    onClick = { onUpdateLayers { it.copy(showPresenceEstimates = !it.showPresenceEstimates) } },
                    label = { Text("Presence Est.") },
                )
                FilterChip(
                    selected = layers.showVerifiedSurvivors,
                    onClick = { onUpdateLayers { it.copy(showVerifiedSurvivors = !it.showVerifiedSurvivors) } },
                    label = { Text("Survivors") },
                )
                FilterChip(
                    selected = layers.showMissingPersons,
                    onClick = { onUpdateLayers { it.copy(showMissingPersons = !it.showMissingPersons) } },
                    label = { Text("Missing") },
                )
                FilterChip(
                    selected = layers.showBoundaryReference,
                    onClick = { onUpdateLayers { it.copy(showBoundaryReference = !it.showBoundaryReference) } },
                    label = { Text("India Ref Lines") },
                )
            }

            if (networkState == NetworkState.OFFLINE && totalCachedTiles == 0) {
                InfoNoteBanner(
                    text = "Device offline and no raster tiles are downloaded locally yet. Cached vector India boundaries remain available.",
                    accentColor = StatusCritical,
                )
            }

            IndiaInteractiveMap(
                locations = locations,
                teams = teams,
                signals = signals,
                registry = registry,
                layers = layers,
                isOfflineMode = settings.offlineMapMode,
                networkState = networkState,
                deviceLocation = deviceLocation,
                tileManager = tileManager,
                focusLat = focusLat,
                focusLon = focusLon,
                focusZoom = focusZoom,
                interactive = true,
                selectedLocationId = selectedLocation?.id,
                onLocationSelected = { selectedLocation = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(370.dp)
                    .testTag("live_map_canvas"),
            )

            // Selected Disaster Site Details + Add Site
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Disaster Sites (${locations.size})", style = MaterialTheme.typography.titleMedium)
                if (currentRole.canWriteOperations()) {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.testTag("add_disaster_site_button"),
                    ) {
                        Icon(Icons.Filled.AddLocationAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Log Site", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            selectedLocation?.let { loc ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(loc.name, style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SeverityBadge(loc.severity)
                                VerificationBadge(loc.verificationStatus)
                            }
                        }
                        Text("${loc.region} • Hazard: ${loc.hazardType} • Source: ${loc.dataSource}", style = MaterialTheme.typography.bodySmall)
                        val distLabel = if (deviceLocation is LocationFixState.Available) {
                            val km = OsmTileManager.haversineDistanceKm(deviceLocation.latitude, deviceLocation.longitude, loc.latitude, loc.longitude)
                            String.format(Locale.US, " • %.2f km from current GPS", km)
                        } else ""
                        Text(
                            text = String.format(Locale.US, "%.4f°N, %.4f°E%s", loc.latitude, loc.longitude, distLabel),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Real Offline Region Download & Storage Manager
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Offline OSM Region Cache", style = MaterialTheme.typography.titleMedium)
                            val mbTotal = totalCachedBytes / (1024.0 * 1024.0)
                            Text(
                                text = String.format(Locale.US, "Stored locally: %d tiles (%.2f MB)", totalCachedTiles, mbTotal),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Offline Mode", style = MaterialTheme.typography.labelMedium)
                            Switch(
                                checked = settings.offlineMapMode,
                                onCheckedChange = onToggleOfflineMode,
                                modifier = Modifier.testTag("offline_map_mode_switch"),
                            )
                        }
                    }

                    offlineRegions.forEach { region ->
                        val prog = downloadProgress[region.regionId]
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(region.title, style = MaterialTheme.typography.titleSmall)
                                        Text(region.boundingBoxLabel, style = MaterialTheme.typography.labelSmall)
                                        Text(
                                            text = if (region.isDownloaded) {
                                                String.format(
                                                    Locale.US,
                                                    "Downloaded • %d tiles • %.2f MB • %s",
                                                    region.tilesStored,
                                                    region.sizeMb,
                                                    region.dataVersion,
                                                )
                                            } else if (region.tilesStored > 0) {
                                                "Partial cache (${region.tilesStored} tiles) — Resume available"
                                            } else {
                                                "Not downloaded on device (0 bytes)"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (region.isDownloaded) StatusVerified else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (prog?.isDownloading == true) {
                                            OutlinedButton(onClick = { onCancelRegionDownload(region.regionId) }) {
                                                Text("Cancel")
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { onStartRegionDownload(region) },
                                                enabled = networkState != NetworkState.OFFLINE,
                                                modifier = Modifier.testTag("download_region_${region.regionId}"),
                                            ) {
                                                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Text(
                                                    text = if (region.isDownloaded) "Refresh" else if (region.tilesStored > 0) "Resume" else "Download",
                                                    modifier = Modifier.padding(start = 4.dp),
                                                )
                                            }
                                        }
                                        if (region.isDownloaded || region.tilesStored > 0) {
                                            IconButton(
                                                onClick = { onDeleteRegionCache(region.regionId) },
                                                modifier = Modifier.size(44.dp).testTag("delete_region_${region.regionId}"),
                                            ) {
                                                Icon(Icons.Filled.Delete, contentDescription = "Delete cached region", tint = StatusCritical)
                                            }
                                        }
                                    }
                                }

                                if (prog != null && (prog.isDownloading || prog.errorMessage != null)) {
                                    if (prog.totalTiles > 0) {
                                        LinearProgressIndicator(
                                            progress = { prog.completedTiles.toFloat() / prog.totalTiles.toFloat() },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            text = "Tiles ${prog.completedTiles}/${prog.totalTiles} (${prog.downloadedBytes / 1024L} KB)",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    prog.errorMessage?.let { err ->
                                        Text(err, style = MaterialTheme.typography.labelSmall, color = StatusCritical)
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
        var name by remember { mutableStateOf("") }
        var region by remember { mutableStateOf("") }
        var hazard by remember { mutableStateOf("landslide") }
        var severity by remember { mutableStateOf("high") }
        var latText by remember {
            mutableStateOf((deviceLocation as? LocationFixState.Available)?.latitude?.toString() ?: "")
        }
        var lonText by remember {
            mutableStateOf((deviceLocation as? LocationFixState.Available)?.longitude?.toString() ?: "")
        }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Register Disaster Location") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Site Name") }, singleLine = true)
                    OutlinedTextField(value = region, onValueChange = { region = it }, label = { Text("District, State/UT") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = latText, onValueChange = { latText = it }, label = { Text("Latitude") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = lonText, onValueChange = { lonText = it }, label = { Text("Longitude") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("landslide", "flood", "earthquake", "structural_collapse").forEach { h ->
                            FilterChip(selected = hazard == h, onClick = { hazard = h }, label = { Text(h.take(7)) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("critical", "high", "moderate").forEach { s ->
                            FilterChip(selected = severity == s, onClick = { severity = s }, label = { Text(s) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val lat = latText.toDoubleOrNull()
                        val lon = lonText.toDoubleOrNull()
                        if (name.isNotBlank() && region.isNotBlank() && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                            onRegisterLocation(name, region, hazard, severity, lat, lon, "unverified", "Field Operator Entry")
                            showAddDialog = false
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } },
        )
    }
}
