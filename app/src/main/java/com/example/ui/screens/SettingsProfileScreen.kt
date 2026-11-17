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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.local.OfflineMapRegionEntity
import com.example.data.local.PendingSyncOperationEntity
import com.example.data.local.SurakshaSettings
import com.example.data.local.SyncHistoryEntity
import com.example.data.map.RegionDownloadProgress
import com.example.data.model.OrgRole
import com.example.data.model.UserProfile
import com.example.data.network.NetworkState
import com.example.data.repository.UiState
import com.example.ui.components.InfoNoteBanner
import com.example.ui.components.VerificationBadge
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsProfileScreen(
    settings: SurakshaSettings,
    networkState: NetworkState,
    currentRole: OrgRole,
    profileState: UiState<UserProfile?>,
    offlineRegions: List<OfflineMapRegionEntity>,
    downloadProgress: Map<String, RegionDownloadProgress>,
    totalCachedTiles: Int,
    totalCachedBytes: Long,
    pendingSyncOps: List<PendingSyncOperationEntity>,
    syncHistory: List<SyncHistoryEntity>,
    onUpdateProfile: (String, String, String) -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onSetLanguage: (String) -> Unit,
    onToggleOfflineMapMode: (Boolean) -> Unit,
    onStartRegionDownload: (OfflineMapRegionEntity) -> Unit,
    onCancelRegionDownload: (String) -> Unit,
    onDeleteRegionCache: (String) -> Unit,
    onFlushSyncQueue: () -> Unit,
    onSyncPushToken: () -> Unit,
    onSignOut: () -> Unit,
) {
    val profile = (profileState as? UiState.Success)?.data
    var displayName by remember(profile?.displayName) { mutableStateOf(profile?.displayName.orEmpty()) }
    var callsign by remember(profile?.callsign) { mutableStateOf(profile?.callsign.orEmpty()) }
    var phone by remember(profile?.phoneOptional) { mutableStateOf(profile?.phoneOptional.orEmpty()) }

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
            // Responder Profile Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!profile?.photoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = profile?.photoUrl,
                                contentDescription = "Responder avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile?.displayName ?: "Rescue Responder", style = MaterialTheme.typography.titleMedium)
                            Text(profile?.email.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        VerificationBadge(currentRole.label)
                    }

                    OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Display Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = callsign, onValueChange = { callsign = it }, label = { Text("Callsign") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone (optional)") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onUpdateProfile(displayName, callsign, phone) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Save Profile") }
                        OutlinedButton(
                            onClick = onSyncPushToken,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Sync Push Token", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            // Theme & Language Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Appearance & Localization", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Dark Command Theme", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = settings.darkThemeEnabled,
                            onCheckedChange = onToggleDarkTheme,
                            modifier = Modifier.testTag("theme_toggle_switch"),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = settings.languageCode == "en", onClick = { onSetLanguage("en") }, label = { Text("English") })
                        FilterChip(selected = settings.languageCode == "hi", onClick = { onSetLanguage("hi") }, label = { Text("हिन्दी") })
                    }
                }
            }

            // Offline Maps & Room Cache Sync Queue
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
                            Text("Offline Map Mode", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "$totalCachedTiles cached tiles (%.2f MB)".format(totalCachedBytes / (1024.0 * 1024.0)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.offlineMapMode,
                            onCheckedChange = onToggleOfflineMapMode,
                            modifier = Modifier.testTag("settings_offline_switch"),
                        )
                    }

                    offlineRegions.forEach { region ->
                        val prog = downloadProgress[region.regionId]
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(region.title, style = MaterialTheme.typography.bodyMedium)
                                    Text("${region.boundingBoxLabel} • ${region.tilesStored} tiles", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (prog?.isDownloading == true) {
                                    TextButton(onClick = { onCancelRegionDownload(region.regionId) }) { Text("Pause") }
                                } else if (region.isDownloaded) {
                                    TextButton(onClick = { onDeleteRegionCache(region.regionId) }) { Text("Delete") }
                                } else {
                                    OutlinedButton(onClick = { onStartRegionDownload(region) }) {
                                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text("Cache", modifier = Modifier.padding(start = 4.dp))
                                    }
                                }
                            }
                            if (prog?.isDownloading == true && prog.totalTiles > 0) {
                                LinearProgressIndicator(
                                    progress = { prog.completedTiles.toFloat() / prog.totalTiles.toFloat() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onFlushSyncQueue,
                            enabled = pendingSyncOps.isNotEmpty() && networkState != NetworkState.OFFLINE,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Flush Offline Queue (${pendingSyncOps.size})", modifier = Modifier.padding(start = 6.dp))
                        }
                    }

                    if (syncHistory.isNotEmpty()) {
                        Text(
                            text = "Latest sync: ${syncHistory.first().entityType} -> ${syncHistory.first().outcome}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            InfoNoteBanner(
                text = "Hardware Boundary Note: Real GPS, compass, Room offline caching, OSM/OpenTopoMap tile downloads, HTTPS/WSS Edge Gateway clients, and Firebase Auth/Firestore/FCM backend dispatch are live. External UWB/FMCW/Thermal/Acoustic/BLE/UWB-Tag hardware adapters cleanly await physical device integration.",
                accentColor = StatusEstimated,
            )

            Button(
                onClick = onSignOut,
                colors = ButtonDefaults.buttonColors(containerColor = StatusCritical, contentColor = androidx.compose.ui.graphics.Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sign_out_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Sign Out", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
