package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.example.data.location.HeadingState
import com.example.data.location.LocationFixState
import com.example.data.map.IndiaPlace
import com.example.data.map.OsmTileManager
import com.example.data.model.EnvironmentMode
import com.example.data.model.MapLayerConfig
import com.example.data.model.MemberLocation
import com.example.data.model.Mission
import com.example.data.model.RadarTarget
import com.example.data.model.SensorDevice
import com.example.data.model.SensorEvent
import com.example.data.model.SosAlert
import com.example.data.model.SosAlertStatus
import com.example.data.network.NetworkState
import com.example.data.repository.UiState
import com.example.ui.components.ActiveSosEmergencyBanner
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.HybridRadarCanvas
import com.example.ui.components.IndiaInteractiveMap
import com.example.ui.components.NoLiveSensorDataBanner
import com.example.ui.components.RadarCategoryBadge
import com.example.ui.components.SeverityBadge
import com.example.ui.components.formatAgeFreshness
import com.example.ui.theme.StatusCritical

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadarMapScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    currentUserId: String,
    activeMission: Mission?,
    radarTargets: List<RadarTarget>,
    memberLocations: List<MemberLocation>,
    sensorDevices: List<SensorDevice>,
    sensorEvents: List<SensorEvent>,
    sosAlertsState: UiState<List<SosAlert>>,
    headingState: HeadingState,
    deviceLocation: LocationFixState,
    mapLayers: MapLayerConfig,
    mapSearchResults: List<IndiaPlace>,
    tileManager: OsmTileManager,
    onSetRadarRange: (Double) -> Unit,
    onSetNorthUp: (Boolean) -> Unit,
    onUpdateMapLayers: (MapLayerConfig) -> Unit,
    onSearchPlaces: (String) -> Unit,
    onClearSearch: () -> Unit,
    onLogObservation: (SensorEvent, String, String) -> Unit,
    onTriggerSos: (String) -> Unit,
    onTransitionSos: (SosAlert, SosAlertStatus, String) -> Unit,
) {
    val allAlerts = (sosAlertsState as? UiState.Success)?.data.orEmpty()
    val openAlerts = allAlerts.filter { SosAlertStatus.fromValue(it.status).isOpen() }

    var showMapView by remember { mutableStateOf(false) }
    var selectedTarget by remember { mutableStateOf<RadarTarget?>(null) }
    var cancelAlertDialog by remember { mutableStateOf<SosAlert?>(null) }
    var showSosDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var focusCoords by remember { mutableStateOf<Triple<Double, Double, Float>?>(null) }

    val envMode = EnvironmentMode.fromValue(activeMission?.environmentMode.orEmpty())
    val rangeOptions = listOf(50.0, 100.0, 250.0, 500.0, 1000.0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 1040.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DemoAndOfflineStatusBanner(
                isDemoMode = settings.devDemoModeEnabled,
                isOfflineMode = settings.offlineMapMode,
                networkState = networkState,
                languageCode = settings.languageCode,
            )

            ActiveSosEmergencyBanner(
                openAlerts = openAlerts,
                onInspectAlerts = {
                    openAlerts.firstOrNull()?.let { a ->
                        selectedTarget = radarTargets.firstOrNull { it.sourceAlert?.alertId == a.alertId }
                    }
                },
            )

            // Radar vs Live Map View Toggle + SOS Trigger
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !showMapView,
                        onClick = { showMapView = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = { Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    ) { Text("Radar") }
                    SegmentedButton(
                        selected = showMapView,
                        onClick = { showMapView = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = { Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    ) { Text("Team Map") }
                }

                Button(
                    onClick = { showSosDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusCritical),
                    modifier = Modifier.testTag("radar_sos_button"),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("SOS", modifier = Modifier.padding(start = 4.dp))
                }
            }

            if (sensorEvents.isEmpty() && !settings.devDemoModeEnabled) {
                NoLiveSensorDataBanner(languageCode = settings.languageCode)
            }

            if (!showMapView) {
                // Range & North-Up/Heading-Up controls
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rangeOptions.forEach { r ->
                        FilterChip(
                            selected = settings.radarRangeMeters == r,
                            onClick = { onSetRadarRange(r) },
                            label = { Text(if (r >= 1000) "1 km" else "${r.toInt()}m") },
                        )
                    }
                    FilterChip(
                        selected = !settings.northUpMode,
                        onClick = { onSetNorthUp(!settings.northUpMode) },
                        leadingIcon = { Icon(Icons.Filled.CompassCalibration, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(if (settings.northUpMode) "North-Up" else "Heading-Up") },
                        modifier = Modifier.testTag("radar_orientation_toggle"),
                    )
                }

                HybridRadarCanvas(
                    targets = radarTargets,
                    radarRangeMeters = settings.radarRangeMeters,
                    environmentMode = envMode,
                    headingState = headingState,
                    northUpMode = settings.northUpMode,
                    selectedTargetId = selectedTarget?.id,
                    onTargetSelected = { selectedTarget = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                )
            } else {
                // Place / Coordinate search & Layer toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search India place or lat, lon") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            IconButton(onClick = { onSearchPlaces(searchQuery) }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search place")
                            }
                        },
                    )
                }
                if (mapSearchResults.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            mapSearchResults.take(4).forEach { place ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            focusCoords = Triple(place.latitude, place.longitude, 11.5f)
                                            onClearSearch()
                                        }
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("${place.name} (${place.stateOrUt})", style = MaterialTheme.typography.bodySmall)
                                    Text(place.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = mapLayers.showTerrain,
                        onClick = { onUpdateMapLayers(mapLayers.copy(showTerrain = !mapLayers.showTerrain)) },
                        label = { Text("Topo Terrain") },
                    )
                    FilterChip(
                        selected = mapLayers.showTeamMembers,
                        onClick = { onUpdateMapLayers(mapLayers.copy(showTeamMembers = !mapLayers.showTeamMembers)) },
                        label = { Text("Team") },
                    )
                    FilterChip(
                        selected = mapLayers.showSensorDetections,
                        onClick = { onUpdateMapLayers(mapLayers.copy(showSensorDetections = !mapLayers.showSensorDetections)) },
                        label = { Text("Presence") },
                    )
                    FilterChip(
                        selected = mapLayers.showBoundaryReference,
                        onClick = { onUpdateMapLayers(mapLayers.copy(showBoundaryReference = !mapLayers.showBoundaryReference)) },
                        label = { Text("India Boundary") },
                    )
                }

                IndiaInteractiveMap(
                    activeMission = activeMission,
                    memberLocations = memberLocations,
                    sensorDevices = sensorDevices,
                    sensorEvents = sensorEvents,
                    sosAlerts = allAlerts,
                    layers = mapLayers,
                    isOfflineMode = settings.offlineMapMode,
                    networkState = networkState,
                    deviceLocation = deviceLocation,
                    tileManager = tileManager,
                    customTileTemplateUrl = settings.customTileServerUrl,
                    focusLat = focusCoords?.first,
                    focusLon = focusCoords?.second,
                    focusZoom = focusCoords?.third,
                    onMemberSelected = { m ->
                        selectedTarget = radarTargets.firstOrNull { it.sourceMember?.userId == m.userId }
                    },
                    onSensorEventSelected = { ev ->
                        selectedTarget = radarTargets.firstOrNull { it.sourceEvent?.eventId == ev.eventId }
                    },
                    onAlertSelected = { a ->
                        selectedTarget = radarTargets.firstOrNull { it.sourceAlert?.alertId == a.alertId }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                )
            }

            // Target List for quick inspection
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tracked Targets & Alerts (${radarTargets.size})", style = MaterialTheme.typography.titleSmall)
                    if (radarTargets.isEmpty()) {
                        Text(
                            text = "No teammates, detections, or alerts currently reported in this mission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        radarTargets.forEach { target ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedTarget = target }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    RadarCategoryBadge(target.category)
                                    Text(target.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = buildString {
                                            append(target.distanceMeters?.let { "${it.toInt()}m" } ?: "Range N/A")
                                            append(" • ")
                                            append(target.bearingDegrees?.let { "${it.toInt()}°" } ?: "Bearing N/A")
                                            append(" • ")
                                            append(formatAgeFreshness(target.timestampMillis))
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Active SOS Alert Action List
            if (openAlerts.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, StatusCritical),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Open Emergency SOS Queue", style = MaterialTheme.typography.titleSmall, color = StatusCritical)
                        openAlerts.forEach { alert ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(alert.senderName, style = MaterialTheme.typography.bodyMedium)
                                    SeverityBadge(alert.status)
                                }
                                Text(alert.description, style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (alert.status == SosAlertStatus.ACTIVE.value) {
                                        OutlinedButton(onClick = { onTransitionSos(alert, SosAlertStatus.ACKNOWLEDGED, "") }) {
                                            Text("Acknowledge")
                                        }
                                    }
                                    if (alert.status != SosAlertStatus.RESPONDING.value) {
                                        OutlinedButton(onClick = { onTransitionSos(alert, SosAlertStatus.RESPONDING, "") }) {
                                            Text("Responding")
                                        }
                                    }
                                    OutlinedButton(onClick = { onTransitionSos(alert, SosAlertStatus.RESOLVED, "") }) {
                                        Text("Resolve")
                                    }
                                    if (alert.senderUid == currentUserId) {
                                        TextButton(onClick = { cancelAlertDialog = alert }) {
                                            Text("Cancel SOS", color = StatusCritical)
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

    // Target Details + Field Observation Dialog
    selectedTarget?.let { t ->
        var obsNote by remember(t.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { selectedTarget = null },
            title = { Text(t.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadarCategoryBadge(t.category)
                    Text(t.subtitle, style = MaterialTheme.typography.bodySmall)
                    Text(t.validityNote, style = MaterialTheme.typography.labelSmall)
                    t.sourceEvent?.respirationRateBpm?.let {
                        Text("Micro-Doppler Respiration Est.: $it bpm", style = MaterialTheme.typography.bodySmall)
                    }
                    if (t.sourceEvent != null && !settings.devDemoModeEnabled) {
                        OutlinedTextField(
                            value = obsNote,
                            onValueChange = { obsNote = it },
                            label = { Text("Field verification observation notes") },
                        )
                    }
                }
            },
            confirmButton = {
                if (t.sourceEvent != null && !settings.devDemoModeEnabled) {
                    Button(onClick = {
                        onLogObservation(t.sourceEvent, obsNote.ifBlank { "Operator inspected radar blip" }, "investigating")
                        selectedTarget = null
                    }) { Text("Save Observation") }
                } else {
                    Button(onClick = { selectedTarget = null }) { Text("Close") }
                }
            },
            dismissButton = {
                if (t.sourceEvent != null && !settings.devDemoModeEnabled) {
                    TextButton(onClick = { selectedTarget = null }) { Text("Close") }
                }
            },
        )
    }

    // Cancel SOS with Required Reason Dialog
    cancelAlertDialog?.let { alert ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { cancelAlertDialog = null },
            title = { Text("Cancel SOS Alert") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A mandatory cancellation reason is logged in the audit trail.")
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Cancellation reason (min 3 chars)") },
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (reason.trim().length >= 3) {
                            onTransitionSos(alert, SosAlertStatus.CANCELLED, reason.trim())
                            cancelAlertDialog = null
                        }
                    },
                    enabled = reason.trim().length >= 3,
                    colors = ButtonDefaults.buttonColors(containerColor = StatusCritical),
                ) { Text("Confirm Cancel") }
            },
            dismissButton = { TextButton(onClick = { cancelAlertDialog = null }) { Text("Keep Active") } },
        )
    }

    if (showSosDialog) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = { Text("Send Emergency SOS") },
            text = {
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Emergency description") })
            },
            confirmButton = {
                Button(
                    onClick = {
                        onTriggerSos(note)
                        showSosDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusCritical),
                ) { Text("SEND SOS") }
            },
            dismissButton = { TextButton(onClick = { showSosDialog = false }) { Text("Cancel") } },
        )
    }
}
