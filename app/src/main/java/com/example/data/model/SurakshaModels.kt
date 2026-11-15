package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val callsign: String = "",
    val teamId: String = "",
    val teamName: String = "",
    val role: String = "coordinator",
    val locationSharingEnabled: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class RescueTeam(
    @DocumentId val id: String = "",
    val userId: String = "",
    val teamCode: String = "",
    val teamName: String = "",
    val memberCount: Long = 1L,
    val activeCount: Long = 1L,
    val status: String = "standby",
    val locationSharingOptIn: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val batteryPercent: Long = 100L,
    val connectionStatus: String = "online",
    val isSynthetic: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class RescueLocation(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val region: String = "",
    val hazardType: String = "other",
    val severity: String = "high",
    val latitude: Double = 28.6139,
    val longitude: Double = 77.2090,
    val verificationStatus: String = "unverified",
    val dataSource: String = "",
    val isSynthetic: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class PresenceSignal(
    @DocumentId val id: String = "",
    val userId: String = "",
    val locationId: String = "",
    val sectorLabel: String = "",
    val signalType: String = "thermal_ir",
    val estimatedMinCount: Long = 0L,
    val estimatedMaxCount: Long = 1L,
    val confidencePercent: Long = 50L,
    val verificationStatus: String = "estimated",
    val dataSource: String = "",
    val isSynthetic: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class RescueRegistryEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val category: String = "missing_person",
    val title: String = "",
    val locationName: String = "",
    val details: String = "",
    val sensitiveContactMasked: String = "",
    val rescueStatus: String = "open",
    val verificationStatus: String = "unverified",
    val informationSource: String = "",
    val verificationNotes: String = "",
    val isSynthetic: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class TeamAlert(
    @DocumentId val id: String = "",
    val userId: String = "",
    val teamCode: String = "",
    val priority: String = "routine",
    val message: String = "",
    val channel: String = "mesh_radio",
    val isSynthetic: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

data class MapLayerConfig(
    val showRoads: Boolean = true,
    val showSatelliteTerrain: Boolean = true,
    val showBuildings: Boolean = true,
    val showGreenZones: Boolean = true,
    val showTeamsAndSites: Boolean = true,
)

data class AiTriageReport(
    val executiveSummary: String = "",
    val prioritizedSectors: List<String> = emptyList(),
    val verificationGaps: List<String> = emptyList(),
    val recommendedActions: List<String> = emptyList(),
    val safetyDisclaimer: String = "AI triage is an estimation aid only. All human-presence indicators require field team verification.",
)

enum class SurakshaSection(val route: String, val label: String) {
    OVERVIEW("overview", "Overview"),
    LIVE_MAP("live_map", "Live Map"),
    ENV_MAPPING("env_mapping", "Env Map"),
    HUMAN_PRESENCE("human_presence", "Presence"),
    RESCUE_TEAMS("rescue_teams", "Teams"),
    RESCUE_REGISTRY("rescue_registry", "Registry"),
    SENSOR_STATUS("sensor_status", "Sensors"),
    SETTINGS("settings", "Settings"),
}
