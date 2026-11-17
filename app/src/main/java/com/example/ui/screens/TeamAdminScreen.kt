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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import com.example.data.model.AuditLog
import com.example.data.model.BlockedMembership
import com.example.data.model.Invitation
import com.example.data.model.OrgMember
import com.example.data.model.OrgRole
import com.example.data.model.RescueOrganization
import com.example.data.model.RescueTeam
import com.example.data.repository.UiState
import com.example.ui.components.RevokedMembershipBanner
import com.example.ui.components.VerificationBadge
import com.example.ui.components.formatSurakshaTimestamp
import com.example.ui.theme.StatusCritical

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamAdminScreen(
    currentUserId: String,
    currentRole: OrgRole,
    isMembershipRevoked: Boolean,
    organizationState: UiState<RescueOrganization?>,
    membersState: UiState<List<OrgMember>>,
    blockedMembersState: UiState<List<BlockedMembership>>,
    invitationsState: UiState<List<Invitation>>,
    teamsState: UiState<List<RescueTeam>>,
    auditLogsState: UiState<List<AuditLog>>,
    onToggleAllowMemberInvites: (Boolean) -> Unit,
    onCreateInvitation: (OrgRole, String) -> Unit,
    onApproveOrRejectInvite: (String, Boolean) -> Unit,
    onUpdateMemberRole: (String, OrgRole) -> Unit,
    onRemoveAndBlockMember: (OrgMember, String) -> Unit,
    onReinstateBlockedMember: (BlockedMembership) -> Unit,
    onCreateTeam: (String, String) -> Unit,
) {
    val org = (organizationState as? UiState.Success)?.data
    val members = (membersState as? UiState.Success)?.data.orEmpty()
    val blocked = (blockedMembersState as? UiState.Success)?.data.orEmpty()
    val invites = (invitationsState as? UiState.Success)?.data.orEmpty()
    val teams = (teamsState as? UiState.Success)?.data.orEmpty()
    val logs = (auditLogsState as? UiState.Success)?.data.orEmpty()

    var showCreateTeamDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<OrgMember?>(null) }

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
            if (isMembershipRevoked) {
                RevokedMembershipBanner()
            }

            // Invite Policy & Team Creation Card
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
                            Text("Team & Access Governance", style = MaterialTheme.typography.titleMedium)
                            Text("Teams: ${teams.size} • Your role: ${currentRole.label}", style = MaterialTheme.typography.bodySmall)
                        }
                        VerificationBadge(currentRole.label)
                    }

                    if (org != null && currentRole.canManageOrg()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Allow Member Invite Requests", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "When enabled, team members may request invites, which still require Admin approval before use.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = org.allowMemberInvites,
                                onCheckedChange = onToggleAllowMemberInvites,
                                modifier = Modifier.testTag("allow_member_invites_switch"),
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (currentRole.canManageMissionsAndInvites() || org?.allowMemberInvites == true) {
                            Button(
                                onClick = { showInviteDialog = true },
                                modifier = Modifier.testTag("create_invite_btn"),
                            ) {
                                Text(if (currentRole.isAdmin()) "Create Invite" else "Request Invite")
                            }
                        }
                        if (currentRole.isAdmin()) {
                            OutlinedButton(onClick = { showCreateTeamDialog = true }, modifier = Modifier.testTag("create_team_btn")) {
                                Text("New Squad")
                            }
                        }
                    }
                }
            }

            // Active & Pending Invitations Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Invitations (${invites.size})", style = MaterialTheme.typography.titleSmall)
                    if (invites.isEmpty()) {
                        Text("No invitations created.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        invites.forEach { inv ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${inv.inviteCode} • ${inv.assignedRole} (${inv.usedCount}/${inv.maxUses})", style = MaterialTheme.typography.bodyMedium)
                                    VerificationBadge(inv.status)
                                }
                                if (currentRole.isAdmin() && inv.status == "pending_approval") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { onApproveOrRejectInvite(inv.inviteCode, true) }) { Text("Approve") }
                                        OutlinedButton(onClick = { onApproveOrRejectInvite(inv.inviteCode, false) }) { Text("Reject") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Organization Members + Role Assignment + Remove & Block
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Organization Members (${members.size})", style = MaterialTheme.typography.titleSmall)
                    members.forEach { member ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${member.displayName} (${member.callsign})", style = MaterialTheme.typography.bodyMedium)
                                    Text("Status: ${member.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                VerificationBadge(if (member.isRevoked()) "revoked" else member.role)
                            }
                            if (currentRole.canManageOrg() && member.userId != currentUserId && !member.isRevoked()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OrgRole.entries.forEach { roleOpt ->
                                        FilterChip(
                                            selected = member.role == roleOpt.value,
                                            onClick = { onUpdateMemberRole(member.userId, roleOpt) },
                                            label = { Text(roleOpt.label) },
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { removeTarget = member },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusCritical),
                                        modifier = Modifier.testTag("remove_member_${member.userId}"),
                                    ) { Text("Remove & Block") }
                                }
                            }
                        }
                    }
                }
            }

            // Blocked Memberships & Admin-Only Reinstatement
            if (currentRole.isAdmin() || blocked.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, StatusCritical.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Blocked / Revoked Memberships (${blocked.size})", style = MaterialTheme.typography.titleSmall, color = StatusCritical)
                        if (blocked.isEmpty()) {
                            Text("No blocked memberships.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            blocked.forEach { b ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("UID: ${b.userId}", style = MaterialTheme.typography.bodySmall)
                                        Text("Reason: ${b.reason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (currentRole.canManageOrg()) {
                                        Button(
                                            onClick = { onReinstateBlockedMember(b) },
                                            modifier = Modifier.testTag("reinstate_member_${b.userId}"),
                                        ) { Text("Reinstate") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Immutable Audit Log Trail
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Security & Operations Audit Logs (${logs.size})", style = MaterialTheme.typography.titleSmall)
                    logs.take(15).forEach { log ->
                        Text(
                            text = "[${log.category.uppercase()}] ${log.action}: ${log.summary} (${formatSurakshaTimestamp(log.createdAt)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    removeTarget?.let { target ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove & Block ${target.displayName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Immediately revokes org, team, and mission access and blocks rejoining unless reinstated by an Organization Admin.")
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Audit removal reason") },
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveAndBlockMember(target, reason.ifBlank { "Removed by Admin" })
                        removeTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusCritical),
                ) { Text("Remove & Block") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
        )
    }

    if (showInviteDialog) {
        var email by remember { mutableStateOf("") }
        var role by remember { mutableStateOf(OrgRole.TEAM_MEMBER) }
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text(if (currentRole.isAdmin()) "Create Organization Invite" else "Request Member Invite") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Target Responder Email (optional)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = role == OrgRole.TEAM_MEMBER, onClick = { role = OrgRole.TEAM_MEMBER }, label = { Text("Team Member") })
                        FilterChip(selected = role == OrgRole.SENSOR_OPERATOR, onClick = { role = OrgRole.SENSOR_OPERATOR }, label = { Text("Sensor Operator") })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCreateInvitation(role, email)
                    showInviteDialog = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showInviteDialog = false }) { Text("Cancel") } },
        )
    }

    if (showCreateTeamDialog) {
        var code by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateTeamDialog = false },
            title = { Text("Create Rescue Squad") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Squad Code (e.g. BRAVO-2)") }, singleLine = true)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Squad Name") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (code.isNotBlank() && name.isNotBlank()) {
                        onCreateTeam(code, name)
                        showCreateTeamDialog = false
                    }
                }) { Text("Create Squad") }
            },
            dismissButton = { TextButton(onClick = { showCreateTeamDialog = false }) { Text("Cancel") } },
        )
    }
}
