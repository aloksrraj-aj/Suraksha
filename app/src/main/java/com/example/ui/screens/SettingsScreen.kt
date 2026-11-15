package com.example.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.OfflineMapRegionEntity
import com.example.data.local.SurakshaSettings
import com.example.data.model.UserProfile
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.EthicalSafetyBanner
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.NavyOutline
import com.example.ui.theme.NavySurface
import com.example.ui.theme.StatusCritical
import com.example.ui.viewmodel.DataUiState

@Composable
fun SettingsScreen(
    settings: SurakshaSettings,
    profileState: DataUiState<UserProfile?>,
    offlineRegions: List<OfflineMapRegionEntity>,
    onToggleDemoMode: (Boolean) -> Unit,
    onToggleOfflineMode: (Boolean) -> Unit,
    onTogglePiiReveal: (Boolean) -> Unit,
    onSeedDemoData: () -> Unit,
    onSignOut: () -> Unit,
) {
    val profile = (profileState as? DataUiState.Success)?.data
    val cachedCount = offlineRegions.count { it.isDownloaded }

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
            Text("System Settings & Architecture", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, NavyOutline),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Operational Modes", style = MaterialTheme.typography.titleMedium)

                    SettingToggleRow(
                        title = "Demo Mode Indicator",
                        subtitle = "Clearly marks all synthetic simulation & demo data across screens.",
                        checked = settings.demoModeEnabled,
                        onCheckedChange = onToggleDemoMode,
                    )
                    SettingToggleRow(
                        title = "Offline Map Mode",
                        subtitle = "Prioritizes $cachedCount cached SQLite vector packages when disconnected.",
                        checked = settings.offlineMapMode,
                        onCheckedChange = onToggleOfflineMode,
                    )
                    SettingToggleRow(
                        title = "Reveal Protected Registry Contacts",
                        subtitle = "Unmasks next-of-kin and medical reference IDs for authorized responders.",
                        checked = settings.revealSensitivePii,
                        onCheckedChange = onTogglePiiReveal,
                    )

                    OutlinedButton(
                        onClick = onSeedDemoData,
                        modifier = Modifier.fillMaxWidth().testTag("seed_demo_data_button"),
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Append Synthetic Demo Dataset")
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, NavyOutline),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Architecture & Hardware Roadmap", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "• Cloud Sync & Auth: Google Sign-In + Cloud Firestore offline-capable persistence.\n" +
                            "• Local Storage: Room SQLite database for offline vector tile packs & edge sensor snapshots.\n" +
                            "• AI Triage: Gemini 3.8 Flash via Firebase AI Logic with App Check attestation.\n" +
                            "• Future Edge Bridge: Modular SurakshaSensorAdapter supporting ROS 2 Humble / FastAPI over WebSocket & MQTT on Raspberry Pi 5, NVIDIA Jetson Orin, and ESP32-S3.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            EthicalSafetyBanner()
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, NavyOutline),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile?.displayName ?: "Authenticated Responder", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Callsign: ${profile?.callsign ?: "NDRF-CMD-01"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyanElectric,
                        )
                    }
                    Button(
                        onClick = onSignOut,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusCritical, contentColor = NavyDeep),
                        modifier = Modifier.testTag("sign_out_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Sign Out")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
