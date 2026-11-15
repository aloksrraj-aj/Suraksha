package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class OrgRole(val value: String, val label: String) {
    ORG_ADMIN("org_admin", "Org Admin"),
    TEAM_LEADER("team_leader", "Team Leader"),
    OPERATOR("operator", "Rescue Operator"),
    MEDIC("medic", "Medical Personnel"),
    OBSERVER("observer", "Read-Only Observer");

    fun canWriteOperations(): Boolean = this != OBSERVER
    fun canManageOrg(): Boolean = this == ORG_ADMIN
    fun canAssignOrResolveSos(): Boolean = this == ORG_ADMIN || this == TEAM_LEADER
    fun canRevealSensitivePii(): Boolean = this == ORG_ADMIN || this == TEAM_LEADER || this == MEDIC

    companion object {
        fun fromValue(raw: String): OrgRole =
            entries.firstOrNull { it.value == raw } ?: OBSERVER
    }
}

enum class SosIncidentStatus(val value: String, val label: String) {
    CREATED("created", "Created"),
    PENDING_DELIVERY("pending_delivery", "Pending Delivery"),
    DELIVERED("delivered", "Server Saved"),
    ACKNOWLEDGED("acknowledged", "Acknowledged"),
    TEAM_ASSIGNED("team_assigned", "Team Assigned"),
    RESPONDING("responding", "Responding"),
    RESOLVED("resolved", "Resolved"),
    CLOSED("closed", "Closed"),
    FAILED("failed", "Failed");

    companion object {
        fun fromValue(raw: String): SosIncidentStatus =
            entries.firstOrNull { it.value == raw } ?: CREATED
    }
}

enum class SyncStatus(val value: String, val label: String) {
    LOCAL_ONLY("local_only", "Local Only"),
    PENDING_SYNC("pending_sync", "Pending Sync"),
    SERVER_SAVED("server_saved", "Server Saved"),
    FAILED("sync_failed", "Sync Failed"),
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val callsign: String = "",
    val activeOrgId: String = "",
    val teamId: String = "",
    val teamName: String = "",
    val role: String = "operator",
    val locationSharingEnabled: Boolean = false,
    val backgroundTrackingEnabled: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class RescueOrganization(
    @DocumentId val id: String = "",
    val orgCode: String = "",
    val orgName: String = "",
    val region: String = "",
    val createdByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class OrgInvite(
    @DocumentId val id: String = "",
    val orgId: String = "",
    val inviteCode: String = "",
    val defaultRole: String = "operator",
    val active: Boolean = true,
    val createdByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class OrgMember(
    @DocumentId val id: String = "",
    val userId: String = "",
    val orgId: String = "",
    val displayName: String = "",
    val callsign: String = "",
    val role: String = "operator",
    val teamId: String = "",
    val availability: String = "available",
    val inviteCode: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class RescueTeam(
    @DocumentId val id: String = "",
    val orgId: String = "",
    val userId: String = "",
    val leaderUid: String = "",
    val teamCode: String = "",
    val teamName: String = "",
    val memberCount: Long = 1L,
    val activeCount: Long = 1L,
    val status: String = "standby",
    val locationSharingOptIn: Boolean = false,
    val hasValidLocation: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Double = 0.0,
    val locationTimestampMillis: Long = 0L,
    val locationUnavailableReason: String = "Location sharing disabled",
    val batteryPercent: Long = 100L,
    val connectionStatus: String = "online",
    val isSynthetic: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class RescueLocation(
    @DocumentId val id: String = "",
    val orgId: String = "",
    val userId: String = "",
    val name: String = "",
    val region: String = "",
    val hazardType: String = "other",
    val severity: String = "high",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Double = 500.0,
    val verificationStatus: String = "unverified",
    val dataSource: String = "",
    val isSynthetic: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class PresenceSignal(
    @DocumentId val id: String = "",
    val orgId: String = "",
    val userId: String = "",
    val locationId: String = "",
    val sectorLabel: String = "",
    val signalType: String = "thermal_ir",
    val sourceKind: String = "simulated", // "simulated" or "hardware"
    val deviceId: String = "",
    val calibrationStatus: String = "nominal", // "calibrated", "needs_calibration", "nominal", "unknown"
    val sensorHealth: String = "healthy", // "healthy", "degraded", "error", "offline"
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val hasCoordinates: Boolean = false,
    val estimatedMinCount: Long = 0L,
    val estimatedMaxCount: Long = 1L,
    val confidencePercent: Long = 50L,
    val verificationStatus: String = "estimated", // "estimated", "unverified", "field_verified", "outdated"
    val dataSource: String = "",
    val verifiedByUid: String = "",
    val verificationNotes: String = "",
    val observationTimestampMillis: Long = 0L,
    val isSynthetic: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class RescueRegistryEntry(
    @DocumentId val id: String = "",
    val orgId: String = "",
    val userId: String = "",
    val category: String = "missing_person",
    val title: String = "",
    val locationName: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val hasCoordinates: Boolean = false,
    val details: String = "",
    val sensitiveContactMasked: String = "",
    val photoUriLocal: String = "",
    val photoConsentGranted: Boolean = false,
    val rescueStatus: String = "open",
    val verificationStatus: String = "reported", // "reported", "under_verification", "verified", "unverified", "duplicate", "resolved"
    val informationSource: String = "",
    val verifierUid: String = "",
    val verificationNotes: String = "",
    val duplicateOfId: String = "",
    val isSynthetic: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class SosIncident(
    @DocumentId val id: String = "",
    val incidentId: String = "",
    val orgId: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val teamCode: String = "",
    val priority: String = "sos", // "sos", "urgent", "routine"
    val description: String = "",
    val status: String = "created", // SosIncidentStatus
    val hasLocation: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Double = 0.0,
    val assignedTeamId: String = "",
    val assignedTeamCode: String = "",
    val acknowledgedByUid: String = "",
    val acknowledgedAtMillis: Long = 0L,
    val deliveryNote: String = "Saved to Firestore dispatch; FCM push requires trusted backend trigger.",
    val isSynthetic: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class SosAuditLog(
    @DocumentId val id: String = "",
    val orgId: String = "",
    val incidentId: String = "",
    val actorUid: String = "",
    val fromStatus: String = "",
    val toStatus: String = "",
    val note: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class TeamAlert(
    @DocumentId val id: String = "",
    val userId: String = "",
    val teamCode: String = "",
    val priority: String = "routine",
    val message: String = "",
    val channel: String = "firestore_dispatch",
    val isSynthetic: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

data class MapLayerConfig(
    val showRoads: Boolean = true,
    val showTerrain: Boolean = false,
    val showBuildings: Boolean = true,
    val showParksAndForests: Boolean = true,
    val showTeams: Boolean = true,
    val showDisasterSites: Boolean = true,
    val showMissingPersons: Boolean = true,
    val showPresenceEstimates: Boolean = true,
    val showVerifiedSurvivors: Boolean = true,
    val showBoundaryReference: Boolean = true,
)

data class AiTriageReport(
    val executiveSummary: String = "",
    val unacknowledgedSosSummary: List<String> = emptyList(),
    val prioritizedSectors: List<String> = emptyList(),
    val missingOrStaleDisclosures: List<String> = emptyList(),
    val dataSourcesUsed: List<String> = emptyList(),
    val recommendedActions: List<String> = emptyList(),
    val requiresHumanApproval: Boolean = true,
    val safetyDisclaimer: String = "Advisory AI analysis only. Does not invent readings or confirm survivors. Human approval is mandatory before dispatching teams or altering incident status.",
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
