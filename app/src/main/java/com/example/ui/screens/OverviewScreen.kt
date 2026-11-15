package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.example.data.hardware.SensorAdapterDescriptor
import com.example.data.local.SurakshaSettings
import com.example.data.location.LocationFixState
import com.example.data.map.OsmTileManager
import com.example.data.model.MapLayerConfig
import com.example.data.model.OrgRole
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueOrganization
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.example.data.model.SosIncident
import com.example.data.model.SurakshaSection
import com.example.data.network.NetworkState
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.EthicalSafetyBanner
import com.example.ui.components.IndiaInteractiveMap
import com.example.ui.components.InfoNoteBanner
import com.example.ui.components.KpiMetricCard
import com.example.ui.components.SeverityBadge
import com.example.ui.components.formatSurakshaTimestamp
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusUnverified
import com.example.ui.theme.StatusVerified
import com.example.ui.viewmodel.AiTriageUiState
import com.example.ui.viewmodel.DashboardFilterState
import com.example.ui.viewmodel.DataUiState
import com.example.ui.viewmodel.OperationalAnalytics
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    pendingSyncCount: Int,
    organizationState: DataUiState<RescueOrganization?>,
    currentRole: OrgRole,
    analytics: OperationalAnalytics,
    filter: DashboardFilterState,
    teamsState: DataUiState<List<RescueTeam>>,
    locationsState: DataUiState<List<RescueLocation>>,
    signalsState: DataUiState<List<PresenceSignal>>,
    registryState: DataUiState<List<RescueRegistryEntry>>,
    incidentsState: DataUiState<List<SosIncident>>,
    adapters: List<SensorAdapterDescriptor>,
    deviceLocation: LocationFixState,
    tileManager: OsmTileManager,
    aiTriageState: AiTriageUiState,
    aiChatAnswer: String?,
    aiChatLoading: Boolean,
    exportedReportText: String?,
    onUpdateFilter: ((DashboardFilterState) -> DashboardFilterState) -> Unit,
    onRetrySync: () -> Unit,
    onRunAiTriage: () -> Unit,
    onAskAiQuestion: (String) -> Unit,
    onExportCsvReport: () -> Unit,
    onClearExportedReport: () -> Unit,
    onNavigateSection: (SurakshaSection) -> Unit,
) {
    val context = LocalContext.current
    val teams = (teamsState as? DataUiState.Success)?.data.orEmpty()
    val locations = (locationsState as? DataUiState.Success)?.data.orEmpty()
    val signals = (signalsState as? DataUiState.Success)?.data.orEmpty()
    val registry = (registryState as? DataUiState.Success)?.data.orEmpty()
    val incidents = (incidentsState as? DataUiState.Success)?.data.orEmpty()
    val org = (organizationState as? DataUiState.Success)?.data
    var aiQuestionInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 960.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DemoAndOfflineStatusBanner(
                isDemoMode = settings.demoModeEnabled,
                isOfflineMode = settings.offlineMapMode,
                networkState = networkState,
                pendingSyncCount = pendingSyncCount,
                languageCode = settings.languageCode,
                onRetrySync = onRetrySync,
            )

            // Active Organization & Role Header
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = org?.orgName ?: "Command Organization",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${org?.orgCode ?: "..."} • Role: ${currentRole.label} • ${networkState.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = onExportCsvReport,
                        modifier = Modifier.testTag("export_csv_button"),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("CSV", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            // Dashboard Filter Strip
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(0 to "All Time", 24 to "24h", 72 to "72h").forEach { (hours, label) ->
                    FilterChip(
                        selected = filter.timeWindowHours == hours,
                        onClick = { onUpdateFilter { it.copy(timeWindowHours = hours) } },
                        label = { Text(label) },
                    )
                }
                FilterChip(
                    selected = filter.teamFilter == "all",
                    onClick = { onUpdateFilter { it.copy(teamFilter = "all") } },
                    label = { Text("All Teams") },
                )
                teams.take(3).forEach { team ->
                    FilterChip(
                        selected = filter.teamFilter == team.teamCode,
                        onClick = { onUpdateFilter { it.copy(teamFilter = team.teamCode) } },
                        label = { Text(team.teamCode) },
                    )
                }
            }

            // Live KPI Analytics Grid (strictly computed from authorized org Firestore records)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                KpiMetricCard(
                    title = "SOS Incidents",
                    value = "${analytics.activeIncidentsCount}",
                    subtitle = "${analytics.unacknowledgedSosCount} unacknowledged",
                    icon = Icons.Filled.NotificationImportant,
                    accentColor = if (analytics.unacknowledgedSosCount > 0) StatusCritical else StatusVerified,
                    modifier = Modifier.weight(1f),
                )
                KpiMetricCard(
                    title = "ACK Latency",
                    value = analytics.avgAckLatencyMinutes?.let { String.format(Locale.US, "%.1fm", it) } ?: "—",
                    subtitle = "Mean response ACK time",
                    icon = Icons.Filled.Warning,
                    accentColor = StatusEstimated,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                KpiMetricCard(
                    title = "Presence Estimates",
                    value = "${analytics.estimatedSignalsCount}",
                    subtitle = "${analytics.fieldVerifiedSignalsCount} field-verified • ${analytics.staleSensorsCount} stale",
                    icon = Icons.Filled.Radar,
                    accentColor = StatusEstimated,
                    modifier = Modifier.weight(1f),
                )
                KpiMetricCard(
                    title = "Active Teams & Verified",
                    value = "${analytics.deployedTeamsCount}/${teams.size}",
                    subtitle = "${analytics.verifiedRegistryCount} verified registry records",
                    icon = Icons.Filled.Groups,
                    accentColor = StatusVerified,
                    modifier = Modifier.weight(1f),
                )
            }

            // Live OSM Situation Map Preview
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
                        Column {
                            Text("India Operational Map Overview", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Real OSM tiles + J&K/Ladakh boundary ref (${locations.size} sites, ${adapters.size} adapters)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { onNavigateSection(SurakshaSection.LIVE_MAP) },
                            modifier = Modifier.testTag("overview_open_map_button"),
                        ) {
                            Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Open Map", modifier = Modifier.padding(start = 4.dp))
                        }
                    }

                    IndiaInteractiveMap(
                        locations = locations,
                        teams = teams,
                        signals = signals,
                        registry = registry,
                        layers = MapLayerConfig(),
                        isOfflineMode = settings.offlineMapMode,
                        networkState = networkState,
                        deviceLocation = deviceLocation,
                        tileManager = tileManager,
                        interactive = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp),
                    )
                }
            }

            // Gemini AI Rescue Assistant (Structured Briefing + Grounded Q&A)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("AI Rescue Coordination Briefing", style = MaterialTheme.typography.titleMedium)
                        }
                        Button(
                            onClick = onRunAiTriage,
                            enabled = aiTriageState !is AiTriageUiState.Loading && networkState != NetworkState.OFFLINE,
                            modifier = Modifier.testTag("run_ai_triage_button"),
                        ) {
                            Text(if (aiTriageState is AiTriageUiState.Loading) "Analyzing..." else "Generate Triage")
                        }
                    }

                    when (aiTriageState) {
                        AiTriageUiState.Idle -> {
                            Text(
                                text = "Generates structured JSON triage strictly from authorized org records, disclosing missing/stale readings and requiring human approval.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AiTriageUiState.Loading -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("Generating grounded triage from authorized org state...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        is AiTriageUiState.Error -> {
                            InfoNoteBanner(text = aiTriageState.message, accentColor = StatusCritical)
                        }
                        is AiTriageUiState.Success -> {
                            val r = aiTriageState.report
                            Text(r.executiveSummary, style = MaterialTheme.typography.bodyMedium)
                            if (r.unacknowledgedSosSummary.isNotEmpty()) {
                                Text("Unacknowledged / Active SOS:", style = MaterialTheme.typography.labelMedium, color = StatusCritical)
                                r.unacknowledgedSosSummary.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            }
                            if (r.prioritizedSectors.isNotEmpty()) {
                                Text("Prioritized Sectors:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                r.prioritizedSectors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            }
                            if (r.missingOrStaleDisclosures.isNotEmpty()) {
                                Text("Missing / Stale / Simulated Disclosures:", style = MaterialTheme.typography.labelMedium, color = StatusUnverified)
                                r.missingOrStaleDisclosures.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            }
                            if (r.recommendedActions.isNotEmpty()) {
                                Text("Recommended Actions (Human Approval Required):", style = MaterialTheme.typography.labelMedium, color = StatusVerified)
                                r.recommendedActions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            }
                            Text(
                                text = "Data Sources: ${r.dataSourcesUsed.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            InfoNoteBanner(text = r.safetyDisclaimer, accentColor = StatusEstimated)
                        }
                    }

                    // Natural-Language Operational Q&A
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = aiQuestionInput,
                            onValueChange = { aiQuestionInput = it },
                            label = { Text("Ask AI over authorized org data") },
                            placeholder = { Text("e.g., Which sectors lack GPS or need review?") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("ai_qa_input"),
                        )
                        IconButton(
                            onClick = {
                                if (aiQuestionInput.isNotBlank()) {
                                    onAskAiQuestion(aiQuestionInput)
                                }
                            },
                            enabled = !aiChatLoading && aiQuestionInput.isNotBlank() && networkState != NetworkState.OFFLINE,
                            modifier = Modifier.size(48.dp).testTag("ai_qa_send_button"),
                        ) {
                            if (aiChatLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send question to AI")
                            }
                        }
                    }
                    if (!aiChatAnswer.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = aiChatAnswer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            }

            // Recent SOS Incidents Feed
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
                        Text("Recent SOS & Field Incidents", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { onNavigateSection(SurakshaSection.RESCUE_TEAMS) }) {
                            Text("Manage SOS")
                        }
                    }
                    when (incidentsState) {
                        DataUiState.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        is DataUiState.Error -> InfoNoteBanner(text = incidentsState.message, accentColor = StatusCritical)
                        is DataUiState.Success -> {
                            if (incidents.isEmpty()) {
                                Text("No SOS incidents recorded in this organization.", style = MaterialTheme.typography.bodySmall)
                            } else {
                                incidents.take(4).forEach { inc ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text("${inc.teamCode} • ${inc.incidentId}", style = MaterialTheme.typography.labelLarge)
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    SeverityBadge(inc.priority)
                                                    SeverityBadge(inc.status)
                                                }
                                            }
                                            Text(inc.description, style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                text = "${formatSurakshaTimestamp(inc.createdAt)} • ${inc.deliveryNote}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            EthicalSafetyBanner()
        }
    }

    if (exportedReportText != null) {
        AlertDialog(
            onDismissRequest = onClearExportedReport,
            title = { Text("Exported Operational Report") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(exportedReportText, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Suraksha Operational Report")
                        putExtra(Intent.EXTRA_TEXT, exportedReportText)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Suraksha Report"))
                }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Share", modifier = Modifier.padding(start = 4.dp))
                }
            },
            dismissButton = {
                TextButton(onClick = onClearExportedReport) { Text("Close") }
            },
        )
    }
}
