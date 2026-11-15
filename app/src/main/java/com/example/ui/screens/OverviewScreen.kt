package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.hardware.AdapterProvisionState
import com.example.data.hardware.SensorAdapterDescriptor
import com.example.data.local.SurakshaSettings
import com.example.data.model.MapLayerConfig
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.example.data.model.SurakshaSection
import com.example.data.model.TeamAlert
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.EthicalSafetyBanner
import com.example.ui.components.IndiaInteractiveMap
import com.example.ui.components.KpiMetricCard
import com.example.ui.components.SeverityBadge
import com.example.ui.components.VerificationBadge
import com.example.ui.components.formatSurakshaTimestamp
import com.example.ui.theme.BlueSky
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.NavyOutline
import com.example.ui.theme.NavySurface
import com.example.ui.theme.NavySurfaceHigh
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusVerified
import com.example.ui.viewmodel.AiTriageUiState
import com.example.ui.viewmodel.DataUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(
    settings: SurakshaSettings,
    teamsState: DataUiState<List<RescueTeam>>,
    locationsState: DataUiState<List<RescueLocation>>,
    signalsState: DataUiState<List<PresenceSignal>>,
    registryState: DataUiState<List<RescueRegistryEntry>>,
    alertsState: DataUiState<List<TeamAlert>>,
    adapters: List<SensorAdapterDescriptor>,
    aiTriageState: AiTriageUiState,
    onRunAiTriage: () -> Unit,
    onNavigateSection: (SurakshaSection) -> Unit,
) {
    val teams = (teamsState as? DataUiState.Success)?.data.orEmpty()
    val locations = (locationsState as? DataUiState.Success)?.data.orEmpty()
    val signals = (signalsState as? DataUiState.Success)?.data.orEmpty()
    val registry = (registryState as? DataUiState.Success)?.data.orEmpty()
    val alerts = (alertsState as? DataUiState.Success)?.data.orEmpty()

    val totalMembers = teams.sumOf { it.memberCount }
    val activeMembers = teams.sumOf { it.activeCount }
    val estMinPresence = signals.sumOf { it.estimatedMinCount }
    val estMaxPresence = signals.sumOf { it.estimatedMaxCount }
    val simAdaptersCount = adapters.count { it.provisionState == AdapterProvisionState.SIMULATED_PROVIDER }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            DemoAndOfflineStatusBanner(
                isDemoMode = settings.demoModeEnabled,
                isOfflineMode = settings.offlineMapMode,
            )
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(152.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(NavySurface),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_suraksha_banner_1794762444656),
                    contentDescription = "Suraksha operational radar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(NavyDeep.copy(alpha = 0.94f), NavyDeep.copy(alpha = 0.45f))
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "COMMAND OVERVIEW",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyanElectric,
                    )
                    Text(
                        text = "Project Suraksha Ops",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Humanitarian rescue coordination, environmental SLAM & presence estimation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 4 KPI Cards in 2x2 grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KpiMetricCard(
                        title = "Total Team Members",
                        value = totalMembers.toString(),
                        subtitle = "${teams.size} registered units",
                        icon = Icons.Filled.Groups,
                        accentColor = CyanElectric,
                        modifier = Modifier.weight(1f),
                    )
                    KpiMetricCard(
                        title = "Active Responders",
                        value = activeMembers.toString(),
                        subtitle = "Authorized voluntary sync",
                        icon = Icons.Filled.Groups,
                        accentColor = StatusVerified,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KpiMetricCard(
                        title = "Est. Presence Signals",
                        value = "$estMinPresence–$estMaxPresence",
                        subtitle = "${signals.size} sensor obs (NOT confirmed)",
                        icon = Icons.Filled.PersonSearch,
                        accentColor = StatusEstimated,
                        modifier = Modifier.weight(1f),
                    )
                    KpiMetricCard(
                        title = "Disaster Sites",
                        value = locations.size.toString(),
                        subtitle = "${registry.size} registry records",
                        icon = Icons.Filled.LocationOn,
                        accentColor = BlueSky,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Sensor Connection Status Strip
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, NavyOutline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateSection(SurakshaSection.SENSOR_STATUS) },
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
                            Icon(Icons.Filled.Sensors, contentDescription = null, tint = CyanElectric)
                            Text("Sensor Integration Status", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = "$simAdaptersCount Simulated • ${adapters.size - simAdaptersCount} Future HW",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanElectric,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        adapters.forEach { node ->
                            val isSim = node.provisionState == AdapterProvisionState.SIMULATED_PROVIDER
                            Surface(
                                color = if (isSim) StatusEstimated.copy(alpha = 0.14f) else NavySurfaceHigh,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSim) StatusEstimated.copy(alpha = 0.5f) else NavyOutline
                                ),
                            ) {
                                Text(
                                    text = if (isSim) "${node.platformName.take(18)} [SIM]" else "${node.platformName.take(18)} [HW]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSim) StatusEstimated else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Map Preview Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, NavyOutline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                            Icon(Icons.Filled.Map, contentDescription = null, tint = CyanElectric)
                            Text("National Disaster Map Preview", style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedButton(
                            onClick = { onNavigateSection(SurakshaSection.LIVE_MAP) },
                            modifier = Modifier.testTag("overview_open_map_button"),
                        ) {
                            Text("Open Map")
                        }
                    }
                    IndiaInteractiveMap(
                        locations = locations,
                        teams = teams,
                        layers = MapLayerConfig(),
                        isOfflineMode = settings.offlineMapMode,
                        interactive = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clickable { onNavigateSection(SurakshaSection.LIVE_MAP) },
                    )
                }
            }
        }

        // Gemini AI Situational Triage Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, CyanElectric.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = CyanElectric)
                            Text("AI Rescue Triage Briefing", style = MaterialTheme.typography.titleMedium)
                        }
                        Button(
                            onClick = onRunAiTriage,
                            enabled = aiTriageState !is AiTriageUiState.Loading,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanElectric, contentColor = NavyDeep),
                            modifier = Modifier.testTag("run_ai_triage_button"),
                        ) {
                            if (aiTriageState is AiTriageUiState.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = NavyDeep,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Analyzing")
                            } else {
                                Text("Generate Briefing")
                            }
                        }
                    }

                    when (aiTriageState) {
                        AiTriageUiState.Idle -> {
                            Text(
                                text = "Synthesizes active disaster locations, sensor signals, and registry gaps using Gemini via Firebase AI Logic. Output is advisory and never replaces field verification.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AiTriageUiState.Loading -> {
                            Text(
                                text = "Evaluating verification statuses, severity, and team availability...",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyanElectric,
                            )
                        }
                        is AiTriageUiState.Error -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    text = aiTriageState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                        is AiTriageUiState.Success -> {
                            val report = aiTriageState.report
                            Text(
                                text = report.executiveSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (report.prioritizedSectors.isNotEmpty()) {
                                Text("Prioritized Sectors", style = MaterialTheme.typography.labelLarge, color = CyanElectric)
                                report.prioritizedSectors.forEach {
                                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            if (report.verificationGaps.isNotEmpty()) {
                                Text("Verification Gaps", style = MaterialTheme.typography.labelLarge, color = StatusEstimated)
                                report.verificationGaps.forEach {
                                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (report.recommendedActions.isNotEmpty()) {
                                Text("Recommended Actions", style = MaterialTheme.typography.labelLarge, color = StatusVerified)
                                report.recommendedActions.forEach {
                                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            Text(
                                text = report.safetyDisclaimer,
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusEstimated,
                            )
                        }
                    }
                }
            }
        }

        // Recent Updates Feed
        item {
            Text(
                text = "Recent Updates & Dispatches",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        if (alerts.isEmpty() && registry.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NavySurface),
                    border = BorderStroke(1.dp, NavyOutline),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = CyanElectric)
                        Text(
                            text = "No updates recorded yet. Seed Demo Data in Settings or log a field report.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(alerts.take(3).size) { idx ->
                val alert = alerts[idx]
                Card(
                    colors = CardDefaults.cardColors(containerColor = NavySurface),
                    border = BorderStroke(1.dp, NavyOutline),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SeverityBadge(alert.priority)
                                Text(
                                    text = "${alert.teamCode} • ${alert.channel}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CyanElectric,
                                )
                            }
                            Text(
                                text = formatSurakshaTimestamp(alert.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = alert.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            items(registry.take(3).size) { idx ->
                val entry = registry[idx]
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${entry.locationName} • ${entry.informationSource}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        VerificationBadge(entry.verificationStatus)
                    }
                }
            }
        }

        item {
            EthicalSafetyBanner()
        }
    }
}
