package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.SurakshaSettings
import com.example.data.model.RescueTeam
import com.example.data.model.TeamAlert
import com.example.data.model.UserProfile
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.InfoNoteBanner
import com.example.ui.components.SeverityBadge
import com.example.ui.components.formatSurakshaTimestamp
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.NavyOutline
import com.example.ui.theme.NavySurface
import com.example.ui.theme.NavySurfaceHigh
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusVerified
import com.example.ui.viewmodel.DataUiState

@Composable
fun RescueTeamsScreen(
    settings: SurakshaSettings,
    profileState: DataUiState<UserProfile?>,
    teamsState: DataUiState<List<RescueTeam>>,
    alertsState: DataUiState<List<TeamAlert>>,
    onTogglePersonalLocationSharing: (Boolean) -> Unit,
    onToggleTeamLocationSharing: (RescueTeam, Boolean, Double, Double) -> Unit,
    onRegisterTeam: (String, String, Long, Long, String, Boolean, Double, Double) -> Unit,
    onSendDispatch: (String, String, String, String) -> Unit,
) {
    val context = LocalContext.current
    val profile = (profileState as? DataUiState.Success)?.data
    val teams = (teamsState as? DataUiState.Success)?.data.orEmpty()
    val alerts = (alertsState as? DataUiState.Success)?.data.orEmpty()

    var showRegisterDialog by remember { mutableStateOf(false) }
    var showDispatchDialog by remember { mutableStateOf(false) }
    var permissionDeniedNote by remember { mutableStateOf<String?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDeniedNote = null
            onTogglePersonalLocationSharing(true)
        } else {
            permissionDeniedNote = "Location permission declined. Voluntary GPS sharing remains disabled."
            onTogglePersonalLocationSharing(false)
        }
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
                    Text("Rescue Team Coordination", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Authorized membership & voluntary location sharing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showDispatchDialog = true },
                        modifier = Modifier.testTag("team_dispatch_button"),
                    ) {
                        Icon(Icons.Filled.Campaign, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Alert")
                    }
                    Button(
                        onClick = { showRegisterDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanElectric, contentColor = NavyDeep),
                        modifier = Modifier.testTag("register_team_button"),
                    ) {
                        Icon(Icons.Filled.GroupAdd, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }
        }

        // Personal Responder Authorization & Consent Card
        item {
            val sharingEnabled = profile?.locationSharingEnabled ?: false
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurfaceHigh),
                border = BorderStroke(1.dp, CyanElectric.copy(alpha = 0.4f)),
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
                                text = "${profile?.displayName ?: "Responder"} (${profile?.callsign ?: "NDRF-CMD"})",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Unit: ${profile?.teamName ?: "NDRF Alpha"} • Role: ${profile?.role ?: "coordinator"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyanElectric,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Voluntary GPS", style = MaterialTheme.typography.labelSmall)
                            Switch(
                                checked = sharingEnabled,
                                onCheckedChange = { wantShare ->
                                    if (!wantShare) {
                                        permissionDeniedNote = null
                                        onTogglePersonalLocationSharing(false)
                                    } else {
                                        val hasPerm = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.ACCESS_FINE_LOCATION
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasPerm) {
                                            onTogglePersonalLocationSharing(true)
                                        } else {
                                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("voluntary_location_switch"),
                            )
                        }
                    }
                    Text(
                        text = if (sharingEnabled) {
                            "Voluntary location sharing is ACTIVE with your explicit authorization."
                        } else {
                            "Location sharing is OFF. Suraksha never tracks individuals without explicit opt-in consent."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sharingEnabled) StatusVerified else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (permissionDeniedNote != null) {
                        Text(
                            text = permissionDeniedNote!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusEstimated,
                        )
                    }
                }
            }
        }

        items(teams, key = { it.id }) { team ->
            val statusColor = when (team.status.lowercase()) {
                "deployed", "active" -> StatusVerified
                "standby" -> StatusEstimated
                else -> StatusCritical
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
                            Text("${team.teamCode} — ${team.teamName}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Active: ${team.activeCount}/${team.memberCount} members • Status: ${team.status.uppercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor,
                            )
                        }
                        Switch(
                            checked = team.locationSharingOptIn,
                            onCheckedChange = { optIn ->
                                val coords = if (optIn) readLastKnownCoordsOrNull(context) else null
                                onToggleTeamLocationSharing(
                                    team,
                                    optIn,
                                    coords?.first ?: 28.6139,
                                    coords?.second ?: 77.2090,
                                )
                            },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, tint = CyanElectric)
                            Text("${team.batteryPercent}%", style = MaterialTheme.typography.labelMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Filled.Wifi, contentDescription = null, tint = CyanElectric)
                            Text(team.connectionStatus, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(
                            text = formatSurakshaTimestamp(team.updatedAt ?: team.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = if (team.locationSharingOptIn && (team.latitude != 0.0 || team.longitude != 0.0)) {
                            "Shared Coords: ${"%.4f".format(team.latitude)}°N, ${"%.4f".format(team.longitude)}°E (Voluntary)"
                        } else {
                            "Coordinates withheld (Team sharing disabled)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Text("Emergency Communication Log", style = MaterialTheme.typography.titleMedium)
        }

        if (alerts.isEmpty()) {
            item {
                InfoNoteBanner("No emergency communications transmitted yet.")
            }
        } else {
            items(alerts, key = { it.id }) { alert ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = NavySurface),
                    border = BorderStroke(1.dp, NavyOutline),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SeverityBadge(alert.priority)
                                Text("${alert.teamCode} • ${alert.channel}", style = MaterialTheme.typography.labelMedium, color = CyanElectric)
                            }
                            Text(formatSurakshaTimestamp(alert.createdAt), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(alert.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showRegisterDialog) {
        RegisterTeamDialog(
            onDismiss = { showRegisterDialog = false },
            onConfirm = { code, name, members, active, status, optIn, lat, lon ->
                onRegisterTeam(code, name, members, active, status, optIn, lat, lon)
                showRegisterDialog = false
            },
        )
    }

    if (showDispatchDialog) {
        SendDispatchDialog(
            defaultTeamCode = teams.firstOrNull()?.teamCode ?: "NDRF-01",
            onDismiss = { showDispatchDialog = false },
            onConfirm = { code, priority, msg, channel ->
                onSendDispatch(code, priority, msg, channel)
                showDispatchDialog = false
            },
        )
    }
}

@SuppressLint("MissingPermission")
private fun readLastKnownCoordsOrNull(context: Context): Pair<Double, Double>? {
    val fineGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!fineGranted && !coarseGranted) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        ?: return null
    return loc.latitude to loc.longitude
}

@Composable
private fun RegisterTeamDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, Long, Long, String, Boolean, Double, Double) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var membersStr by remember { mutableStateOf("8") }
    var status by remember { mutableStateOf("active") }
    var optIn by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register Rescue Team") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Team Identifier (e.g. NDRF-05)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Team Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = membersStr,
                    onValueChange = { membersStr = it },
                    label = { Text("Member Count") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("active", "deployed", "standby").forEach { option ->
                        FilterChip(
                            selected = status == option,
                            onClick = { status = option },
                            label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Voluntary Location Sharing", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = optIn, onCheckedChange = { optIn = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val count = (membersStr.toLongOrNull() ?: 6L).coerceIn(1L, 100L)
                    if (code.isNotBlank() && name.isNotBlank()) {
                        onConfirm(code, name, count, count, status, optIn, 28.6139, 77.2090)
                    }
                },
                enabled = code.isNotBlank() && name.isNotBlank(),
            ) {
                Text("Register")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SendDispatchDialog(
    defaultTeamCode: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit,
) {
    var teamCode by remember { mutableStateOf(defaultTeamCode) }
    var priority by remember { mutableStateOf("urgent") }
    var channel by remember { mutableStateOf("mesh_radio") }
    var message by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emergency Communication") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = teamCode,
                    onValueChange = { teamCode = it },
                    label = { Text("Target / Sender Team ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("sos", "urgent", "routine").forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p.uppercase(), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("mesh_radio", "satellite", "cellular").forEach { c ->
                        FilterChip(
                            selected = channel == c,
                            onClick = { channel = c },
                            label = { Text(c.substringBefore("_"), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Dispatch Message") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (teamCode.isNotBlank() && message.isNotBlank()) {
                        onConfirm(teamCode, priority, message, channel)
                    }
                },
                enabled = teamCode.isNotBlank() && message.isNotBlank(),
            ) {
                Text("Transmit")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
