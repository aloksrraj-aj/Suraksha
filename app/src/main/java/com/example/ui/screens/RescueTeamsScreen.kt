package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.SurakshaSettings
import com.example.data.location.LocationFixState
import com.example.data.location.SurakshaLocationBus
import com.example.data.model.OrgRole
import com.example.data.model.RescueTeam
import com.example.data.model.SosAuditLog
import com.example.data.model.SosIncident
import com.example.data.model.SosIncidentStatus
import com.example.data.model.UserProfile
import com.example.data.network.NetworkState
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.InfoNoteBanner
import com.example.ui.components.SeverityBadge
import com.example.ui.components.formatAgeFreshness
import com.example.ui.components.formatSurakshaTimestamp
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusVerified
import com.example.ui.viewmodel.DataUiState
import kotlinx.coroutines.flow.Flow
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RescueTeamsScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    currentRole: OrgRole,
    profileState: DataUiState<UserProfile?>,
    teamsState: DataUiState<List<RescueTeam>>,
    incidentsState: DataUiState<List<SosIncident>>,
    deviceLocation: LocationFixState,
    observeAuditLogs: (String) -> Flow<List<SosAuditLog>>,
    onTogglePersonalLocationSharing: (Boolean) -> Unit,
    onToggleTeamLocationSharing: (RescueTeam, Boolean) -> Unit,
    onRegisterTeam: (String, String, Long, Long, String, Boolean) -> Unit,
    onCreateSosIncident: (String, String, String, Boolean) -> Unit,
    onTransitionSos: (SosIncident, SosIncidentStatus, String, String) -> Unit,
) {
    val profile = (profileState as? DataUiState.Success)?.data
    val teams = (teamsState as? DataUiState.Success)?.data.orEmpty()
    val incidents = (incidentsState as? DataUiState.Success)?.data.orEmpty()

    var showTeamDialog by remember { mutableStateOf(false) }
    var showSosDialog by remember { mutableStateOf(false) }
    var assignTargetSos by remember { mutableStateOf<SosIncident?>(null) }
    var auditTargetSosId by remember { mutableStateOf<String?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            onTogglePersonalLocationSharing(true)
        }
    }

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

            // Voluntary Continuous GPS Sharing Card (Foreground Service + Consent Gate)
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = if (deviceLocation is LocationFixState.Available) Icons.Filled.GpsFixed else Icons.Filled.GpsOff,
                                contentDescription = "GPS status",
                                tint = if (deviceLocation is LocationFixState.Available) StatusVerified else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column {
                                Text("Voluntary Field GPS Tracking", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = SurakshaLocationBus.freshnessLabel(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = profile?.locationSharingEnabled == true || deviceLocation is LocationFixState.Available || deviceLocation is LocationFixState.Searching,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    val perms = buildList {
                                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }.toTypedArray()
                                    locationPermissionLauncher.launch(perms)
                                } else {
                                    onTogglePersonalLocationSharing(false)
                                }
                            },
                            modifier = Modifier.testTag("personal_gps_opt_in_switch"),
                        )
                    }

                    when (deviceLocation) {
                        is LocationFixState.Available -> {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Fix: %.5f°N, %.5f°E • ±%.1fm • Interval %ds (Battery %d%%)",
                                    deviceLocation.latitude,
                                    deviceLocation.longitude,
                                    deviceLocation.accuracyMeters,
                                    deviceLocation.intervalMillis / 1000L,
                                    deviceLocation.batteryPercent,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusVerified,
                            )
                        }
                        is LocationFixState.PermissionDenied -> InfoNoteBanner(text = deviceLocation.reason, accentColor = StatusCritical)
                        is LocationFixState.GpsDisabled -> InfoNoteBanner(text = deviceLocation.reason, accentColor = StatusEstimated)
                        else -> Unit
                    }
                }
            }

            // Action Header: Register Team & Raise SOS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Rescue Teams (${teams.size})", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (currentRole.canAssignOrResolveSos()) {
                        OutlinedButton(
                            onClick = { showTeamDialog = true },
                            modifier = Modifier.testTag("register_team_button"),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Team", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    if (currentRole.canWriteOperations()) {
                        Button(
                            onClick = { showSosDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusCritical, contentColor = androidx.compose.ui.graphics.Color.White),
                            modifier = Modifier.testTag("raise_sos_button"),
                        ) {
                            Icon(Icons.Filled.Sos, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("SOS", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            // Rescue Teams List
            when (teamsState) {
                DataUiState.Loading -> CircularProgressIndicator()
                is DataUiState.Error -> InfoNoteBanner(text = teamsState.message, accentColor = StatusCritical)
                is DataUiState.Success -> {
                    if (teams.isEmpty()) {
                        Text("No rescue teams registered in this organization.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        teams.forEach { team ->
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
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("${team.teamCode} • ${team.teamName}", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = "Active: ${team.activeCount}/${team.memberCount} • ${team.status.uppercase(Locale.US)} • Battery ${team.batteryPercent}% • ${team.connectionStatus}",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        if (currentRole.canWriteOperations()) {
                                            Switch(
                                                checked = team.locationSharingOptIn,
                                                onCheckedChange = { optIn -> onToggleTeamLocationSharing(team, optIn) },
                                            )
                                        }
                                    }
                                    val gpsText = if (team.locationSharingOptIn && team.hasValidLocation) {
                                        String.format(
                                            Locale.US,
                                            "GPS: %.4f°N, %.4f°E (±%.0fm) • %s",
                                            team.latitude,
                                            team.longitude,
                                            team.accuracyMeters,
                                            formatAgeFreshness(team.locationTimestampMillis),
                                        )
                                    } else {
                                        "Location unavailable: ${team.locationUnavailableReason.ifBlank { "Sharing disabled" }}"
                                    }
                                    Text(
                                        text = gpsText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (team.hasValidLocation) StatusVerified else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SOS Incident Workflow Section
            Text("SOS Incident Workflow (${incidents.size})", style = MaterialTheme.typography.titleMedium)
            InfoNoteBanner(
                text = "Dispatch Integrity: Incidents save directly to Cloud Firestore and track server ACK & responder assignments. Push FCM/SMS alerts require backend Cloud Function triggers.",
                accentColor = MaterialTheme.colorScheme.primary,
            )

            when (incidentsState) {
                DataUiState.Loading -> CircularProgressIndicator()
                is DataUiState.Error -> InfoNoteBanner(text = incidentsState.message, accentColor = StatusCritical)
                is DataUiState.Success -> {
                    incidents.forEach { inc ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(
                                1.dp,
                                if (inc.status == "closed") MaterialTheme.colorScheme.outline else StatusCritical.copy(alpha = 0.55f),
                            ),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("${inc.incidentId} • ${inc.teamCode}", style = MaterialTheme.typography.titleSmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SeverityBadge(inc.priority)
                                        SeverityBadge(inc.status)
                                    }
                                }
                                Text(inc.description, style = MaterialTheme.typography.bodyMedium)
                                val locLine = if (inc.hasLocation) {
                                    String.format(Locale.US, "GPS: %.4f°N, %.4f°E (±%.0fm)", inc.latitude, inc.longitude, inc.accuracyMeters)
                                } else {
                                    "Location unavailable (not attached or disabled)"
                                }
                                Text(
                                    text = "$locLine • Sender: ${inc.senderName} • ${formatSurakshaTimestamp(inc.createdAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (inc.assignedTeamCode.isNotBlank()) {
                                    Text("Assigned Unit: ${inc.assignedTeamCode}", style = MaterialTheme.typography.labelMedium, color = StatusVerified)
                                }

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (inc.status != "closed" && currentRole.canWriteOperations()) {
                                        if (inc.status in listOf("created", "pending_delivery", "delivered")) {
                                            OutlinedButton(onClick = {
                                                onTransitionSos(inc, SosIncidentStatus.ACKNOWLEDGED, inc.assignedTeamCode, "Responder acknowledged incident")
                                            }) { Text("Acknowledge") }
                                        }
                                        if (inc.status in listOf("acknowledged", "team_assigned")) {
                                            OutlinedButton(onClick = {
                                                onTransitionSos(inc, SosIncidentStatus.RESPONDING, inc.assignedTeamCode, "Team en route")
                                            }) { Text("Responding") }
                                        }
                                        if (currentRole.canAssignOrResolveSos()) {
                                            OutlinedButton(onClick = { assignTargetSos = inc }) { Text("Assign Team") }
                                            if (inc.status != "resolved") {
                                                OutlinedButton(onClick = {
                                                    onTransitionSos(inc, SosIncidentStatus.RESOLVED, inc.assignedTeamCode, "Field situation stabilized")
                                                }) { Text("Resolve") }
                                            }
                                            OutlinedButton(onClick = {
                                                onTransitionSos(inc, SosIncidentStatus.CLOSED, inc.assignedTeamCode, "Closed by command")
                                            }) { Text("Close") }
                                        }
                                    }
                                    TextButton(onClick = { auditTargetSosId = inc.incidentId }) {
                                        Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text("Audit Log", modifier = Modifier.padding(start = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTeamDialog) {
        var code by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var members by remember { mutableStateOf("8") }
        var active by remember { mutableStateOf("6") }
        var shareGps by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showTeamDialog = false },
            title = { Text("Register Rescue Team") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Team Code (e.g. NDRF-07)") }, singleLine = true)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Unit Name") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = members, onValueChange = { members = it }, label = { Text("Members") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = active, onValueChange = { active = it }, label = { Text("Active") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = shareGps, onCheckedChange = { shareGps = it })
                        Text("Opt in to share real device GPS (when available)")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val m = members.toLongOrNull()?.coerceIn(1L, 200L) ?: 1L
                    val a = active.toLongOrNull()?.coerceIn(0L, m) ?: m
                    if (code.isNotBlank() && name.isNotBlank()) {
                        onRegisterTeam(code, name, m, a, "active", shareGps)
                        showTeamDialog = false
                    }
                }) { Text("Register") }
            },
            dismissButton = { TextButton(onClick = { showTeamDialog = false }) { Text("Cancel") } },
        )
    }

    if (showSosDialog) {
        var teamCode by remember { mutableStateOf(teams.firstOrNull()?.teamCode ?: "NDRF-01") }
        var priority by remember { mutableStateOf("sos") }
        var description by remember { mutableStateOf("") }
        var includeGps by remember { mutableStateOf(deviceLocation is LocationFixState.Available) }

        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = { Text("Raise Emergency SOS Incident") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = teamCode, onValueChange = { teamCode = it }, label = { Text("Sender / Target Team Code") }, singleLine = true)
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Emergency Situation Description") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("sos", "urgent", "routine").forEach { p ->
                            FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.uppercase(Locale.US)) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeGps, onCheckedChange = { includeGps = it })
                        Text(
                            if (deviceLocation is LocationFixState.Available) "Attach live device GPS" else "Attach GPS (Currently unavailable)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = StatusCritical, contentColor = androidx.compose.ui.graphics.Color.White),
                    onClick = {
                        if (description.isNotBlank() && teamCode.isNotBlank()) {
                            onCreateSosIncident(teamCode, priority, description, includeGps)
                            showSosDialog = false
                        }
                    },
                ) { Text("Send SOS") }
            },
            dismissButton = { TextButton(onClick = { showSosDialog = false }) { Text("Cancel") } },
        )
    }

    assignTargetSos?.let { inc ->
        var teamCode by remember { mutableStateOf(inc.assignedTeamCode.ifBlank { teams.firstOrNull()?.teamCode ?: "" }) }
        AlertDialog(
            onDismissRequest = { assignTargetSos = null },
            title = { Text("Assign Team to ${inc.incidentId}") },
            text = {
                OutlinedTextField(value = teamCode, onValueChange = { teamCode = it }, label = { Text("Team Code") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    if (teamCode.isNotBlank()) {
                        onTransitionSos(inc, SosIncidentStatus.TEAM_ASSIGNED, teamCode, "Assigned unit $teamCode")
                        assignTargetSos = null
                    }
                }) { Text("Assign") }
            },
            dismissButton = { TextButton(onClick = { assignTargetSos = null }) { Text("Cancel") } },
        )
    }

    auditTargetSosId?.let { targetId ->
        val logs by remember(targetId) { observeAuditLogs(targetId) }.collectAsStateWithLifecycle(initialValue = emptyList())
        AlertDialog(
            onDismissRequest = { auditTargetSosId = null },
            title = { Text("Audit Trail: $targetId") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (logs.isEmpty()) {
                        Text("Loading or no transitions logged yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        logs.forEach { log ->
                            Text(
                                text = "• ${log.fromStatus} -> ${log.toStatus} (${formatSurakshaTimestamp(log.createdAt)})\n  ${log.note}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { auditTargetSosId = null }) { Text("Close") } },
        )
    }
}
