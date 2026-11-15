package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.data.network.NetworkState
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusOutdated
import com.example.ui.theme.StatusUnverified
import com.example.ui.theme.StatusVerified
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun DemoAndOfflineStatusBanner(
    isDemoMode: Boolean,
    isOfflineMode: Boolean,
    networkState: NetworkState = NetworkState.ONLINE_UNMETERED,
    pendingSyncCount: Int = 0,
    languageCode: String = "en",
    onRetrySync: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isHindi = languageCode == "hi"
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isDemoMode || isOfflineMode || networkState == NetworkState.OFFLINE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isDemoMode) {
                    Surface(
                        color = StatusEstimated.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StatusEstimated.copy(alpha = 0.65f)),
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(StatusEstimated)
                            )
                            Text(
                                text = if (isHindi) "डेमो मोड • सिंथेटिक डेटा" else "DEMO MODE • SYNTHETIC DATA",
                                style = MaterialTheme.typography.labelMedium,
                                color = StatusEstimated,
                            )
                        }
                    }
                }
                if (isOfflineMode || networkState == NetworkState.OFFLINE) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudOff,
                                contentDescription = "Offline status",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = when {
                                    networkState == NetworkState.OFFLINE -> if (isHindi) "नेटवर्क ऑफ़लाइन" else "NO NETWORK"
                                    else -> if (isHindi) "ऑफ़लाइन मैप मोड" else "OFFLINE MAP"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        if (pendingSyncCount > 0) {
            Surface(
                color = StatusUnverified.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StatusUnverified.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudQueue,
                            contentDescription = "Pending offline sync items",
                            tint = StatusUnverified,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "$pendingSyncCount pending write(s) queued for Firestore sync",
                            style = MaterialTheme.typography.labelMedium,
                            color = StatusUnverified,
                        )
                    }
                    if (onRetrySync != null && networkState != NetworkState.OFFLINE) {
                        TextButton(onClick = onRetrySync) {
                            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text("Sync", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VerificationBadge(
    status: String,
    modifier: Modifier = Modifier,
) {
    val normalized = status.lowercase(Locale.US)
    val (label, color) = when (normalized) {
        "verified", "field_verified" -> normalized.replace("_", " ").uppercase(Locale.US) to StatusVerified
        "estimated", "under_verification" -> normalized.replace("_", " ").uppercase(Locale.US) to StatusEstimated
        "unverified", "reported" -> normalized.uppercase(Locale.US) to StatusUnverified
        "outdated", "duplicate" -> normalized.uppercase(Locale.US) to StatusOutdated
        "resolved", "closed" -> normalized.uppercase(Locale.US) to StatusVerified
        else -> normalized.replace("_", " ").uppercase(Locale.US) to MaterialTheme.colorScheme.primary
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.65f)),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun SeverityBadge(
    severity: String,
    modifier: Modifier = Modifier,
) {
    val normalized = severity.lowercase(Locale.US)
    val color = when (normalized) {
        "critical", "sos", "failed" -> StatusCritical
        "high", "urgent", "pending_delivery" -> StatusUnverified
        "moderate", "created", "delivered" -> StatusEstimated
        "acknowledged", "team_assigned", "responding", "resolved" -> StatusVerified
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = modifier,
    ) {
        Text(
            text = normalized.replace("_", " ").uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun KpiMetricCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                maxLines = 2,
            )
        }
    }
}

@Composable
fun EthicalSafetyBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.VerifiedUser,
                contentDescription = "Humanitarian Ethics Safeguard",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Humanitarian Safeguard: Suraksha strictly assists rescue workers. Simulated sensor signals are never auto-verified, coordinates are never fabricated, and human approval is required for AI suggestions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun InfoNoteBanner(
    text: String,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = accentColor.copy(alpha = 0.1f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

fun formatSurakshaTimestamp(ts: Timestamp?): String {
    if (ts == null) return "Pending server time"
    val formatter = SimpleDateFormat("dd MMM, HH:mm", Locale.US)
    return formatter.format(ts.toDate())
}

fun formatAgeFreshness(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (timestampMillis <= 0L) return "No fix timestamp"
    val ageSec = ((nowMillis - timestampMillis).coerceAtLeast(0L)) / 1000L
    return when {
        ageSec < 60L -> "Live (${ageSec}s ago)"
        ageSec < 300L -> "Recent (${ageSec / 60L}m ago)"
        else -> "Stale (${ageSec / 60L}m ago)"
    }
}
