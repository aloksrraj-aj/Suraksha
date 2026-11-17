package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.JoinedOrgEntity
import com.example.data.local.SurakshaSettings
import com.example.data.location.LocationFixState
import com.example.data.model.EnvironmentMode
import com.example.data.model.MemberLocation
import com.example.data.model.Mission
import com.example.data.model.MissionStatus
import com.example.data.model.OrgRole
import com.example.data.model.RescueOrganization
import com.example.data.model.RescueTeam
import com.example.data.model.SensorDevice
import com.example.data.model.SensorEvent
import com.example.data.model.SeparationWarningItem
import com.example.data.model.SosAlert
import com.example.data.model.SosAlertStatus
import com.example.data.network.NetworkState
import com.example.data.repository.UiState
import com.example.ui.components.ActiveSosEmergencyBanner
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.EthicalSafetyBanner
import com.example.ui.components.KpiMetricCard
import com.example.ui.components.NoLiveSensorDataBanner
import com.example.ui.components.RevokedMembershipBanner
import com.example.ui.components.VerificationBadge
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusVerified

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissionsDashboardScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    pendingSyncCount: Int,
    currentRole: OrgRole,
    isMembershipRevoked: Boolean,
    organizationState: UiState<RescueOrganization?>,
    joinedOrgs: List<JoinedOrgEntity>,
    teamsState: UiState<List<RescueTeam>>,
    missionsState: UiState<List<Mission>>,
    activeMission: Mission?,
    memberLocations: List<MemberLocation>,
    sensorDevices: List<SensorDevice>,
    sensorEvents: List<SensorEvent>,
    sosAlertsState: UiState<List<SosAlert>>,
    separationWarnings: List<SeparationWarningItem>,
    locationFixState: LocationFixState,
    onCreateOrganization: (String, String, String) -> Unit,
    onJoinOrganization: (String, String) -> Unit,
    onSelectWorkspace: (String, String, String) -> Unit,
    onCreateMission: (String, String, EnvironmentMode, Double, Long) -> Unit,
    onUpdateMissionSettings: (Mission, EnvironmentMode, MissionStatus, Boolean, Double, Long) -> Unit,
    onToggleLocationSharing: (Boolean) -> Unit,
    onDismissBatteryPrompt: () -> Unit,
    onTriggerSos: (String) -> Unit,
    onOpenRadar: () -> Unit,
    onFlushSync: () -> Unit,
) {
    val context = LocalContext.current
    val org = (organizationState as? UiState.Success)?.data
    val teams = (teamsState as? UiState.Success)?.data.orEmpty()
    val missions = (missionsState as? UiState.Success)?.data.orEmpty()
    val openAlerts = ((sosAlertsState as? UiState.Success)?.data.orEmpty())
        .filter { SosAlertStatus.fromValue(it.status).isOpen() }

    var showCreateOrgDialog by remember { mutableStateOf(false) }
    var showJoinOrgDialog by remember { mutableStateOf(false) }
    var showCreateMissionDialog by remember { mutableStateOf(false) }
    var showSosDialog by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            onToggleLocationSharing(true)
        }
    }

    val pm = context.getSystemService(PowerManager::class.java)
    val isBatteryExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } else true

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
                pendingSyncCount = pendingSyncCount,
                languageCode = settings.languageCode,
                onRetrySync = onFlushSync,
            )

            if (isMembershipRevoked) {
                RevokedMembershipBanner()
            }

            ActiveSosEmergencyBanner(
                openAlerts = openAlerts,
                onInspectAlerts = onOpenRadar,
            )

            // Workspace Header: Organization + Team + Mission selector
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
                            Text(
                                text = org?.orgName ?: "No Organization Selected",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (org != null) "Org Code: ${org.orgCode} • ${org.region}" else "Create a rescue organization or join via invite code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        VerificationBadge(currentRole.label)
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { showCreateOrgDialog = true }, modifier = Modifier.testTag("dash_create_org_btn")) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Create Org", modifier = Modifier.padding(start = 4.dp))
                        }
                        OutlinedButton(onClick = { showJoinOrgDialog = true }, modifier = Modifier.testTag("dash_join_org_btn")) {
                            Text("Join with Invite")
                        }
                        if (org != null && currentRole.canManageMissionsAndInvites()) {
                            Button(onClick = { showCreateMissionDialog = true }, modifier = Modifier.testTag("dash_create_mission_btn")) {
                                Text("New Mission")
                            }
                        }
                    }

                    if (joinedOrgs.size > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            joinedOrgs.forEach { jOrg ->
                                FilterChip(
                                    selected = jOrg.orgId == settings.activeOrgId,
                                    onClick = { onSelectWorkspace(jOrg.orgId, "", "") },
                                    label = { Text(jOrg.orgName) },
                                )
                            }
                        }
                    }

                    if (missions.isNotEmpty()) {
                        Text("Missions (${missions.size})", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            missions.forEach { m ->
                                FilterChip(
                                    selected = m.missionId == activeMission?.missionId,
                                    onClick = { onSelectWorkspace(m.orgId, m.teamId, m.missionId) },
                                    label = { Text("${m.title} (${m.status})") },
                                )
                            }
                        }
                    }
                }
            }

            // KPI Row
            val activeMembers = memberLocations.count { it.hasValidLocation && it.connectionStatus == "online" }
            val connectedDevices = sensorDevices.count { it.connectionStatus == "connected" }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                KpiMetricCard(
                    title = "Active Team",
                    value = "$activeMembers / ${memberLocations.size}",
                    subtitle = "${teams.size} squad(s)",
                    icon = Icons.Filled.Groups,
                    accentColor = StatusVerified,
                    modifier = Modifier.weight(1f),
                )
                KpiMetricCard(
                    title = "Sensors",
                    value = "$connectedDevices",
                    subtitle = "${sensorEvents.size} detections",
                    icon = Icons.Filled.Sensors,
                    accentColor = if (connectedDevices > 0) StatusEstimated else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                KpiMetricCard(
                    title = "Open SOS",
                    value = "${openAlerts.size}",
                    subtitle = "${separationWarnings.size} sep warning(s)",
                    icon = Icons.Filled.Warning,
                    accentColor = if (openAlerts.isNotEmpty() || separationWarnings.isNotEmpty()) StatusCritical else StatusVerified,
                    modifier = Modifier.weight(1f),
                )
            }

            // No Live Sensor Data clear disclosure when no connected sensors & Dev Demo Mode OFF
            if (connectedDevices == 0 && sensorEvents.isEmpty() && !settings.devDemoModeEnabled) {
                NoLiveSensorDataBanner(languageCode = settings.languageCode)
            }

            // Voluntary Live GPS Sharing & Battery Optimization Prompt
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
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Share Live Responder GPS Location", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = when (locationFixState) {
                                    is LocationFixState.Available ->
                                        "Live fix: %.5f, %.5f (±%.0fm)".format(locationFixState.latitude, locationFixState.longitude, locationFixState.accuracyMeters)
                                    is LocationFixState.GpsDisabled -> locationFixState.reason
                                    is LocationFixState.PermissionDenied -> locationFixState.reason
                                    is LocationFixState.OptedOut -> locationFixState.reason
                                    is LocationFixState.Searching -> "Acquiring GPS satellites..."
                                    is LocationFixState.Unavailable -> locationFixState.reason
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.locationConsentEnabled,
                            onCheckedChange = { enable ->
                                if (!enable) {
                                    onToggleLocationSharing(false)
                                } else {
                                    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    if (hasFine) {
                                        onToggleLocationSharing(true)
                                    } else {
                                        val reqs = buildList {
                                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                add(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        }.toTypedArray()
                                        permLauncher.launch(reqs)
                                    }
                                }
                            },
                            modifier = Modifier.testTag("mission_gps_sharing_switch"),
                        )
                    }

                    if (settings.locationConsentEnabled && !isBatteryExempt && !settings.batteryOptPromptDismissed) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = StatusEstimated, modifier = Modifier.size(16.dp))
                                Text(
                                    text = "Disable battery optimization so rescue tracking doesn't stall.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusEstimated,
                                )
                            }
                            TextButton(onClick = {
                                runCatching {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                }
                                onDismissBatteryPrompt()
                            }) {
                                Text("Configure")
                            }
                        }
                    }
                }
            }

            // Active Mission Controls: Environment Mode A/B + Separation Threshold + Lifecycle
            if (activeMission != null) {
                var threshold by remember(activeMission.missionId, activeMission.separationThresholdMeters) {
                    mutableDoubleStateOf(activeMission.separationThresholdMeters)
                }
                var delaySec by remember(activeMission.missionId, activeMission.separationDelaySeconds) {
                    mutableLongStateOf(activeMission.separationDelaySeconds)
                }
                var sepEnabled by remember(activeMission.missionId, activeMission.separationAlertEnabled) {
                    mutableStateOf(activeMission.separationAlertEnabled)
                }
                var envMode by remember(activeMission.missionId, activeMission.environmentMode) {
                    mutableStateOf(EnvironmentMode.fromValue(activeMission.environmentMode))
                }
                var mStatus by remember(activeMission.missionId, activeMission.status) {
                    mutableStateOf(MissionStatus.fromValue(activeMission.status))
                }

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
                            Text("Mission: ${activeMission.title}", style = MaterialTheme.typography.titleSmall)
                            VerificationBadge(mStatus.value)
                        }

                        Text("Environment Scan Mode", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EnvironmentMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = envMode == mode,
                                    onClick = {
                                        envMode = mode
                                        if (currentRole.canManageMissionsAndInvites()) {
                                            onUpdateMissionSettings(activeMission, mode, mStatus, sepEnabled, threshold, delaySec)
                                        }
                                    },
                                    label = { Text(mode.label) },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Team Separation Alert (${threshold.toInt()}m / ${delaySec}s delay)", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = sepEnabled,
                                onCheckedChange = {
                                    sepEnabled = it
                                    if (currentRole.canManageMissionsAndInvites()) {
                                        onUpdateMissionSettings(activeMission, envMode, mStatus, it, threshold, delaySec)
                                    }
                                },
                            )
                        }

                        if (currentRole.canManageMissionsAndInvites()) {
                            Slider(
                                value = threshold.toFloat(),
                                onValueChange = { threshold = it.toDouble() },
                                valueRange = 25f..1000f,
                                onValueChangeFinished = {
                                    onUpdateMissionSettings(activeMission, envMode, mStatus, sepEnabled, threshold, delaySec)
                                },
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MissionStatus.entries.forEach { st ->
                                    FilterChip(
                                        selected = mStatus == st,
                                        onClick = {
                                            mStatus = st
                                            onUpdateMissionSettings(activeMission, envMode, st, sepEnabled, threshold, delaySec)
                                        },
                                        label = { Text(st.label) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Active Separation Warnings
            if (separationWarnings.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    border = BorderStroke(1.dp, StatusCritical),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("SEPARATION ALERT — MEMBERS BEYOND THRESHOLD", style = MaterialTheme.typography.labelLarge, color = StatusCritical)
                        separationWarnings.forEach { w ->
                            Text(
                                text = "• ${w.callsign} (${w.memberName}): ${w.distanceMeters.toInt()}m away (limit ${w.thresholdMeters.toInt()}m, ${w.continuousBreachSeconds}s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // Quick Actions: Open Radar + Emergency SOS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onOpenRadar,
                    modifier = Modifier.weight(1f).testTag("open_radar_cta"),
                ) {
                    Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Open Radar", modifier = Modifier.padding(start = 6.dp))
                }
                Button(
                    onClick = { showSosDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusCritical),
                    modifier = Modifier.weight(1f).testTag("dashboard_sos_button"),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Send SOS", modifier = Modifier.padding(start = 6.dp))
                }
            }

            EthicalSafetyBanner()
        }
    }

    if (showCreateOrgDialog) {
        var code by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var region by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateOrgDialog = false },
            title = { Text("Create Rescue Organization") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Organization Name") }, singleLine = true)
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Org Code (e.g. NDRF-01)") }, singleLine = true)
                    OutlinedTextField(value = region, onValueChange = { region = it }, label = { Text("Operating Region") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank() && code.isNotBlank()) {
                        onCreateOrganization(code, name, region.ifBlank { "India" })
                        showCreateOrgDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateOrgDialog = false }) { Text("Cancel") } },
        )
    }

    if (showJoinOrgDialog) {
        var orgId by remember { mutableStateOf("") }
        var inviteCode by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJoinOrgDialog = false },
            title = { Text("Join Organization") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = orgId, onValueChange = { orgId = it }, label = { Text("Organization ID") }, singleLine = true)
                    OutlinedTextField(value = inviteCode, onValueChange = { inviteCode = it }, label = { Text("Approved Invite Code") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (orgId.isNotBlank() && inviteCode.isNotBlank()) {
                        onJoinOrganization(orgId, inviteCode)
                        showJoinOrgDialog = false
                    }
                }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { showJoinOrgDialog = false }) { Text("Cancel") } },
        )
    }

    if (showCreateMissionDialog) {
        var title by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf(EnvironmentMode.OPEN_AREA) }
        AlertDialog(
            onDismissRequest = { showCreateMissionDialog = false },
            title = { Text("Create SAR Mission") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Mission Title") }, singleLine = true)
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Sector / Hazard Description") })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EnvironmentMode.entries.forEach { m ->
                            FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (title.isNotBlank()) {
                        onCreateMission(title, desc, mode, 150.0, 20L)
                        showCreateMissionDialog = false
                    }
                }) { Text("Launch Mission") }
            },
            dismissButton = { TextButton(onClick = { showCreateMissionDialog = false }) { Text("Cancel") } },
        )
    }

    if (showSosDialog) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = { Text("Confirm Emergency SOS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Transmits high-priority SOS alert to all active mission responders and triggers push notification escalation.")
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Emergency details (optional)") })
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onTriggerSos(note)
                        showSosDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusCritical),
                ) { Text("SEND SOS NOW") }
            },
            dismissButton = { TextButton(onClick = { showSosDialog = false }) { Text("Cancel") } },
        )
    }
}
