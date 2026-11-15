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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.OfflineMapRegionEntity
import com.example.data.local.PendingSyncOperationEntity
import com.example.data.local.SurakshaSettings
import com.example.data.model.OrgInvite
import com.example.data.model.OrgMember
import com.example.data.model.OrgRole
import com.example.data.model.RescueOrganization
import com.example.data.model.UserProfile
import com.example.data.network.NetworkState
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.InfoNoteBanner
import com.example.ui.components.VerificationBadge
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusVerified
import com.example.ui.viewmodel.DataUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    currentRole: OrgRole,
    profileState: DataUiState<UserProfile?>,
    organizationState: DataUiState<RescueOrganization?>,
    membersState: DataUiState<List<OrgMember>>,
    invitesState: DataUiState<List<OrgInvite>>,
    offlineRegions: List<OfflineMapRegionEntity>,
    pendingSyncOps: List<PendingSyncOperationEntity>,
    onToggleDemoMode: (Boolean) -> Unit,
    onToggleOfflineMode: (Boolean) -> Unit,
    onTogglePiiReveal: (Boolean) -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onSetLanguage: (String) -> Unit,
    onSeedDemoData: () -> Unit,
    onCreateOrganization: (String, String, String) -> Unit,
    onJoinOrganization: (String, String, String) -> Unit,
    onGenerateInvite: (String, String) -> Unit,
    onUpdateMemberRole: (String, OrgRole) -> Unit,
    onMigrateLegacyData: () -> Unit,
    onFlushSyncQueue: () -> Unit,
    onSignOut: () -> Unit,
) {
    val profile = (profileState as? DataUiState.Success)?.data
    val org = (organizationState as? DataUiState.Success)?.data
    val members = (membersState as? DataUiState.Success)?.data.orEmpty()
    val invites = (invitesState as? DataUiState.Success)?.data.orEmpty()

    var showCreateOrgDialog by remember { mutableStateOf(false) }
    var showJoinOrgDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }

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
                pendingSyncCount = pendingSyncOps.size,
                languageCode = settings.languageCode,
                onRetrySync = onFlushSyncQueue,
            )

            // Responder Identity & Active Organization RBAC Card
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile?.displayName ?: "Authenticated Responder", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Org: ${org?.orgName ?: "..."} (${org?.id ?: ""})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        VerificationBadge(currentRole.label)
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { showCreateOrgDialog = true }, modifier = Modifier.testTag("create_org_button")) {
                            Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Create Org", modifier = Modifier.padding(start = 4.dp))
                        }
                        OutlinedButton(onClick = { showJoinOrgDialog = true }, modifier = Modifier.testTag("join_org_button")) {
                            Icon(Icons.Filled.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Join with Invite", modifier = Modifier.padding(start = 4.dp))
                        }
                        if (currentRole.canManageOrg()) {
                            OutlinedButton(onClick = { showInviteDialog = true }, modifier = Modifier.testTag("create_invite_button")) {
                                Text("Generate Invite Code")
                            }
                        }
                        OutlinedButton(onClick = onMigrateLegacyData, modifier = Modifier.testTag("migrate_legacy_button")) {
                            Text("Migrate Legacy Data")
                        }
                    }

                    if (invites.isNotEmpty() && currentRole.canManageOrg()) {
                        Text(
                            text = "Active Invites: ${invites.filter { it.active }.joinToString { "${it.inviteCode} (${it.defaultRole})" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Org Members & Admin Role Controls
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Organization Members (${members.size})", style = MaterialTheme.typography.titleSmall)
                    members.forEach { member ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("${member.displayName} (${member.callsign})", style = MaterialTheme.typography.bodyMedium)
                                VerificationBadge(member.role)
                            }
                            if (currentRole.canManageOrg() && member.userId != profile?.userId) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OrgRole.entries.forEach { roleOpt ->
                                        FilterChip(
                                            selected = member.role == roleOpt.value,
                                            onClick = { onUpdateMemberRole(member.userId, roleOpt) },
                                            label = { Text(roleOpt.label) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Display Theme, Language & Operations Preferences
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Appearance, Language & Mode", style = MaterialTheme.typography.titleSmall)
                    SettingSwitchRow(
                        title = "Dark Tactical Command Theme",
                        subtitle = "Switch between Night Dark and Daylight High-Contrast Light theme",
                        checked = settings.darkThemeEnabled,
                        onCheckedChange = onToggleDarkTheme,
                        tag = "theme_toggle_switch",
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Language (Localization)", style = MaterialTheme.typography.bodyMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = settings.languageCode == "en",
                                onClick = { onSetLanguage("en") },
                                label = { Text("English") },
                                modifier = Modifier.testTag("lang_chip_en"),
                            )
                            FilterChip(
                                selected = settings.languageCode == "hi",
                                onClick = { onSetLanguage("hi") },
                                label = { Text("हिन्दी") },
                                modifier = Modifier.testTag("lang_chip_hi"),
                            )
                        }
                    }
                    SettingSwitchRow(
                        title = "Demo Mode (Synthetic Dataset Banner)",
                        subtitle = "Clearly flags synthetic sample records across UI & AI triage",
                        checked = settings.demoModeEnabled,
                        onCheckedChange = onToggleDemoMode,
                        tag = "demo_mode_switch",
                    )
                    SettingSwitchRow(
                        title = "Offline Map Cache Mode",
                        subtitle = "Use downloaded local OSM tiles only without network map requests (${offlineRegions.count { it.isDownloaded }}/${offlineRegions.size} ready)",
                        checked = settings.offlineMapMode,
                        onCheckedChange = onToggleOfflineMode,
                        tag = "settings_offline_switch",
                    )
                    SettingSwitchRow(
                        title = "Reveal Masked Registry PII",
                        subtitle = if (currentRole.canRevealSensitivePii()) "Authorized for ${currentRole.label}" else "Locked for ${currentRole.label}",
                        checked = settings.revealSensitivePii,
                        onCheckedChange = onTogglePiiReveal,
                        tag = "settings_pii_switch",
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSeedDemoData, modifier = Modifier.weight(1f)) {
                            Text("Re-seed Demo Dataset")
                        }
                        OutlinedButton(
                            onClick = onFlushSyncQueue,
                            enabled = pendingSyncOps.isNotEmpty() && networkState != NetworkState.OFFLINE,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Flush Sync (${pendingSyncOps.size})", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            // Honest Deployment Boundaries Card
            InfoNoteBanner(
                text = "Field Readiness Boundary: Cloud Firestore RBAC, local Room sync queue, continuous GPS foreground service, OSM tile downloads, and Gemini triage are active on-device. Server-side push notifications (FCM), SMS/Satellite gateways, and physical Jetson/UWB/FLIR hardware require external field hardware and backend triggers.",
                accentColor = StatusEstimated,
            )

            Button(
                onClick = onSignOut,
                colors = ButtonDefaults.buttonColors(containerColor = StatusCritical, contentColor = androidx.compose.ui.graphics.Color.White),
                modifier = Modifier.fillMaxWidth().testTag("sign_out_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Sign Out", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (showCreateOrgDialog) {
        var name by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var region by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateOrgDialog = false },
            title = { Text("Create Rescue Organization") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Organization Name") }, singleLine = true)
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Org Code (e.g. NDRF-HQ)") }, singleLine = true)
                    OutlinedTextField(value = region, onValueChange = { region = it }, label = { Text("Primary Region") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank() && code.isNotBlank() && region.isNotBlank()) {
                        onCreateOrganization(name, code, region)
                        showCreateOrgDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateOrgDialog = false }) { Text("Cancel") } },
        )
    }

    if (showJoinOrgDialog) {
        var targetOrgId by remember { mutableStateOf("") }
        var inviteCode by remember { mutableStateOf("") }
        var callsign by remember { mutableStateOf("OPS-01") }
        AlertDialog(
            onDismissRequest = { showJoinOrgDialog = false },
            title = { Text("Join Organization via Invite") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = targetOrgId, onValueChange = { targetOrgId = it }, label = { Text("Organization ID") }, singleLine = true)
                    OutlinedTextField(value = inviteCode, onValueChange = { inviteCode = it }, label = { Text("Invite Code") }, singleLine = true)
                    OutlinedTextField(value = callsign, onValueChange = { callsign = it }, label = { Text("Your Callsign") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (targetOrgId.isNotBlank() && inviteCode.isNotBlank()) {
                        onJoinOrganization(targetOrgId, inviteCode, callsign)
                        showJoinOrgDialog = false
                    }
                }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { showJoinOrgDialog = false }) { Text("Cancel") } },
        )
    }

    if (showInviteDialog) {
        var code by remember { mutableStateOf("RESCUE26") }
        var role by remember { mutableStateOf(OrgRole.OPERATOR.value) }
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Generate Org Invite Code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Invite Code (4+ chars)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = role == "operator", onClick = { role = "operator" }, label = { Text("Operator") })
                        FilterChip(selected = role == "observer", onClick = { role = "observer" }, label = { Text("Observer") })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (code.trim().length >= 4) {
                        onGenerateInvite(code, role)
                        showInviteDialog = false
                    }
                }) { Text("Create Invite") }
            },
            dismissButton = { TextButton(onClick = { showInviteDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
        )
    }
}
