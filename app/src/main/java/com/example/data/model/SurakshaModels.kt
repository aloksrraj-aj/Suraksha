package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class OrgRole(val value: String, val label: String) {
    ORGANIZATION_ADMIN("ORGANIZATION_ADMIN", "Organization Admin"),
    TEAM_ADMIN("TEAM_ADMIN", "Team Admin"),
    TEAM_MEMBER("TEAM_MEMBER", "Team Member"),
    SENSOR_OPERATOR("SENSOR_OPERATOR", "Sensor Operator");

    fun isAdmin(): Boolean = this == ORGANIZATION_ADMIN || this == TEAM_ADMIN
    fun canManageOrg(): Boolean = this == ORGANIZATION_ADMIN
    fun canOperateSensors(): Boolean = this == ORGANIZATION_ADMIN || this == TEAM_ADMIN || this == SENSOR_OPERATOR
    fun canManageMissionsAndInvites(): Boolean = this == ORGANIZATION_ADMIN || this == TEAM_ADMIN

    companion object {
        fun fromValue(raw: String): OrgRole =
            entries.firstOrNull { it.value == raw } ?: TEAM_MEMBER
    }
}

enum class MembershipStatus(val value: String, val label: String) {
    ACTIVE("ACTIVE", "Active"),
    REVOKED("REVOKED", "Revoked"),
    LEFT("LEFT", "Left");

    companion object {
        fun fromValue(raw: String): MembershipStatus =
            entries.firstOrNull { it.value == raw } ?: ACTIVE
    }
}

enum class EnvironmentMode(val value: String, val label: String) {
    BUILDING_COLLAPSED("BUILDING_COLLAPSED", "Mode A • Building / Rubble (UWB)"),
    OPEN_AREA("OPEN_AREA", "Mode B • Open Area (FMCW)");

    companion object {
        fun fromValue(raw: String): EnvironmentMode =
            entries.firstOrNull { it.value == raw } ?: OPEN_AREA
    }
}

enum class MissionStatus(val value: String, val label: String) {
    CREATED("created", "Created"),
    READY("ready", "Ready"),
    ACTIVE("active", "Active"),
    PAUSED("paused", "Paused"),
    COMPLETED("completed", "Completed"),
    CANCELLED("cancelled", "Cancelled");

    companion object {
        fun fromValue(raw: String): MissionStatus =
            entries.firstOrNull { it.value == raw } ?: CREATED
    }
}

enum class SosAlertStatus(val value: String, val label: String) {
    ACTIVE("active", "Active SOS"),
    ACKNOWLEDGED("acknowledged", "Acknowledged"),
    RESPONDING("responding", "Responding"),
    RESOLVED("resolved", "Resolved"),
    CANCELLED("cancelled", "Cancelled");

    fun isOpen(): Boolean = this == ACTIVE || this == ACKNOWLEDGED || this == RESPONDING

    companion object {
        fun fromValue(raw: String): SosAlertStatus =
            entries.firstOrNull { it.value == raw } ?: ACTIVE
    }
}

enum class SensorType(val value: String, val label: String, val defaultRangeMeters: Double) {
    UWB_LIFE_DETECTION_RADAR("uwb_life_detection_radar", "Through-Wall UWB Radar", 30.0),
    FMCW_RADAR("fmcw_radar", "Open-Area FMCW Radar", 250.0),
    THERMAL_IMAGING("thermal_imaging", "Thermal / IR Sensor", 120.0),
    ACOUSTIC_VIBRATION("acoustic_vibration", "Acoustic / Vibration Probe", 40.0),
    REGISTERED_BLE_TAG("registered_ble_tag", "Registered BLE Rescue Tag", 50.0),
    UWB_RANGING_TAG("uwb_ranging_tag", "UWB Ranging Tag", 80.0);

    companion object {
        fun fromValue(raw: String): SensorType =
            entries.firstOrNull { it.value == raw } ?: UWB_LIFE_DETECTION_RADAR
    }
}

enum class SensorDataValidity(val value: String, val label: String) {
    VALID("valid", "Valid"),
    LOW_CONFIDENCE("low_confidence", "Low Confidence"),
    STALE("stale", "Stale"),
    UNCERTAIN("uncertain", "Uncertain Geometry");

    companion object {
        fun fromValue(raw: String): SensorDataValidity =
            entries.firstOrNull { it.value == raw } ?: UNCERTAIN
    }
}

enum class RadarCategory {
    GREEN_TEAM_MEMBER,
    YELLOW_UNCLASSIFIED_PRESENCE,
    RED_EMERGENCY_ALERT,
    GREY_STALE_OR_UNCERTAIN,
}

enum class SyncStatus(val value: String, val label: String) {
    LOCAL_ONLY("local_only", "Local Only"),
    PENDING_SYNC("pending_sync", "Pending Sync"),
    SERVER_SAVED("server_saved", "Server Saved"),
    FAILED("sync_failed", "Sync Failed"),
    DEAD_LETTER("dead_letter", "Max Retries Exceeded"),
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val phoneOptional: String = "",
    val callsign: String = "",
    val activeOrgId: String = "",
    val activeTeamId: String = "",
    val activeMissionId: String = "",
    val locationSharingEnabled: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class RescueOrganization(
    @DocumentId val id: String = "",
    val orgCode: String = "",
    val orgName: String = "",
    val region: String = "",
    val allowMemberInvites: Boolean = false,
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
    val photoUrl: String = "",
    val role: String = OrgRole.TEAM_MEMBER.value,
    val teamId: String = "",
    val status: String = MembershipStatus.ACTIVE.value,
    val availability: String = "available",
    val inviteCode: String = "",
    val revokedByUid: String = "",
    val revokedAtMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun isRevoked(): Boolean = status == MembershipStatus.REVOKED.value
}

@IgnoreExtraProperties
data class BlockedMembership(
    @DocumentId val id: String = "",
    val userId: String = "",
    val orgId: String = "",
    val teamId: String = "",
    val missionId: String = "",
    val blockedByUid: String = "",
    val reason: String = "",
    val blockedEmailNormalized: String = "",
    val blockedAtMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class Invitation(
    @DocumentId val id: String = "",
    val orgId: String = "",
    val inviteCode: String = "",
    val assignedRole: String = OrgRole.TEAM_MEMBER.value,
    val targetTeamId: String = "",
    val targetMissionId: String = "",
    val targetEmail: String = "",
    val status: String = "approved", // "pending_approval", "approved", "rejected", "revoked"
    val active: Boolean = true,
    val expiresAtMillis: Long = 0L,
    val maxUses: Long = 10L,
    val usedCount: Long = 0L,
    val createdByUid: String = "",
    val createdByRole: String = "",
    val approvedByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun isRedeemable(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return status == "approved" &&
            active &&
            usedCount < maxUses &&
            (expiresAtMillis == 0L || expiresAtMillis > nowMillis)
    }
}

@IgnoreExtraProperties
data class RescueTeam(
    @DocumentId val id: String = "",
    val orgId: String = "",
    val teamCode: String = "",
    val teamName: String = "",
    val teamAdminUid: String = "",
    val allowMemberInvites: Boolean = false,
    val memberCount: Long = 1L,
    val status: String = "active",
    val createdByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class TeamMember(
    @DocumentId val id: String = "",
    val userId: String = "",
    val orgId: String = "",
    val teamId: String = "",
    val displayName: String = "",
    val callsign: String = "",
    val role: String = OrgRole.TEAM_MEMBER.value,
    val status: String = MembershipStatus.ACTIVE.value,
    val revokedByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class Mission(
    @DocumentId val id: String = "",
    val missionId: String = "",
    val orgId: String = "",
    val teamId: String = "",
    val teamCode: String = "",
    val title: String = "",
    val description: String = "",
    val environmentMode: String = EnvironmentMode.OPEN_AREA.value,
    val status: String = MissionStatus.ACTIVE.value,
    val separationAlertEnabled: Boolean = true,
    val separationThresholdMeters: Double = 150.0,
    val separationDelaySeconds: Long = 20L,
    val centerLatitude: Double = 30.5506,
    val centerLongitude: Double = 79.5660,
    val leaderUid: String = "",
    val createdByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class MissionMember(
    @DocumentId val id: String = "",
    val userId: String = "",
    val orgId: String = "",
    val missionId: String = "",
    val teamId: String = "",
    val displayName: String = "",
    val callsign: String = "",
    val photoUrl: String = "",
    val role: String = OrgRole.TEAM_MEMBER.value,
    val status: String = MembershipStatus.ACTIVE.value,
    val sharingEnabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class MemberLocation(
    @DocumentId val id: String = "",
    val userId: String = "",
    val orgId: String = "",
    val missionId: String = "",
    val teamId: String = "",
    val displayName: String = "",
    val callsign: String = "",
    val role: String = OrgRole.TEAM_MEMBER.value,
    val sharingEnabled: Boolean = false,
    val hasValidLocation: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Double = 0.0,
    val headingDegrees: Double = 0.0,
    val headingReliable: Boolean = false,
    val batteryPercent: Long = 100L,
    val connectionStatus: String = "online", // online, offline, stale, gps_disabled, permission_denied, paused
    val locationTimestampMillis: Long = 0L,
    val unavailableReason: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class SensorDevice(
    @DocumentId val id: String = "",
    val sensorId: String = "",
    val orgId: String = "",
    val missionId: String = "",
    val sensorType: String = SensorType.UWB_LIFE_DETECTION_RADAR.value,
    val displayName: String = "",
    val manufacturerModel: String = "",
    val transportType: String = "gateway_http_tls",
    val connectionStatus: String = "awaiting_integration", // awaiting_integration, connecting, connected, disconnected, error
    val hasPosition: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val orientationDegrees: Double = 0.0,
    val sectorWidthDegrees: Double = 120.0,
    val maxRangeMeters: Double = 50.0,
    val calibrationVersion: String = "uncalibrated",
    val lastCalibrationMillis: Long = 0L,
    val operatorUid: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class SensorHealth(
    @DocumentId val id: String = "",
    val sensorId: String = "",
    val orgId: String = "",
    val missionId: String = "",
    val healthState: String = "awaiting_integration", // healthy, degraded, offline, error, awaiting_integration
    val lastHeartbeatMillis: Long = 0L,
    val packetsReceived: Long = 0L,
    val packetsRejected: Long = 0L,
    val diagnosticNote: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class SensorEvent(
    @DocumentId val id: String = "",
    val eventId: String = "",
    val orgId: String = "",
    val missionId: String = "",
    val sensorId: String = "",
    val sensorType: String = SensorType.UWB_LIFE_DETECTION_RADAR.value,
    val timestampUtcMillis: Long = 0L,
    val detectionType: String = UNCLASSIFIED_PRESENCE_LABEL,
    val estimatedRangeMeters: Double? = null,
    val estimatedAzimuthDegrees: Double? = null,
    val estimatedElevationDegrees: Double? = null,
    val estimatedLatitude: Double? = null,
    val estimatedLongitude: Double? = null,
    val positionAccuracyMeters: Double? = null,
    val confidence: Double = 0.0,
    val signalQuality: String = "fair", // good, fair, poor, unusable
    val detectionDurationSeconds: Double? = null,
    val respirationRateBpm: Double? = null,
    val sourceDevice: String = "",
    val calibrationVersion: String = "",
    val rawDataReference: String = "",
    val dataValidity: String = SensorDataValidity.VALID.value,
    val status: String = "active", // active, stale, reviewed, cleared
    val operatorUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
) {
    companion object {
        const val UNCLASSIFIED_PRESENCE_LABEL = "UNCLASSIFIED POSSIBLE HUMAN PRESENCE"
    }
}

@IgnoreExtraProperties
data class DetectionObservation(
    @DocumentId val id: String = "",
    val detectionId: String = "",
    val orgId: String = "",
    val missionId: String = "",
    val sensorEventId: String = "",
    val sensorId: String = "",
    val operatorUid: String = "",
    val classificationLabel: String = SensorEvent.UNCLASSIFIED_PRESENCE_LABEL,
    val notes: String = "",
    val outcome: String = "unverified_observation", // unverified_observation, investigating, confirmed_clear, rescue_requested
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class SosAlert(
    @DocumentId val id: String = "",
    val alertId: String = "",
    val orgId: String = "",
    val missionId: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val teamId: String = "",
    val alertType: String = "sos", // sos, separation_alert
    val priority: String = "critical", // critical, urgent
    val status: String = SosAlertStatus.ACTIVE.value,
    val hasLocation: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Double = 0.0,
    val locationTimestampMillis: Long = 0L,
    val description: String = "",
    val acknowledgedByUid: String = "",
    val acknowledgedAtMillis: Long = 0L,
    val cancelledByUid: String = "",
    val cancelReason: String = "",
    val notificationStatus: String = "pending_dispatch",
    val deliveryNote: String = "Saved to Firestore; dispatching push notifications.",
    val pushAttemptedCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class GatewayDevice(
    @DocumentId val id: String = "",
    val gatewayId: String = "",
    val orgId: String = "",
    val name: String = "",
    val transportType: String = "http_tls", // http_tls, websocket_tls, mqtt_tls
    val endpointUri: String = "",
    val status: String = "unverified", // online, degraded, offline, unverified
    val bufferedEventCount: Long = 0L,
    val lastHeartbeatMillis: Long = 0L,
    val registeredByUid: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class AuditLog(
    @DocumentId val id: String = "",
    val orgId: String = "",
    val missionId: String = "",
    val teamId: String = "",
    val actorUid: String = "",
    val actorName: String = "",
    val category: String = "membership", // membership, removal, reinstatement, invite, role, mission, sos, sensor, gateway
    val action: String = "",
    val targetUid: String = "",
    val summary: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class BlockedRoadMarker(
    @DocumentId val id: String = "",
    val orgId: String = "",
    val reportedByUid: String = "",
    val label: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val severity: String = "blocked",
    val active: Boolean = true,
    val notes: String = "",
)

data class MapLayerConfig(
    val showRoads: Boolean = true,
    val showTerrain: Boolean = false,
    val showBoundaryReference: Boolean = true,
    val showTeamMembers: Boolean = true,
    val showSensorDetections: Boolean = true,
    val showSosAlerts: Boolean = true,
    val showSensorStations: Boolean = true,
    val showSeparationRadius: Boolean = true,
)

data class RadarTarget(
    val id: String,
    val category: RadarCategory,
    val title: String,
    val subtitle: String,
    val distanceMeters: Double?,
    val bearingDegrees: Double?,
    val hasDirection: Boolean,
    val confidence: Double? = null,
    val timestampMillis: Long = 0L,
    val validityNote: String = "",
    val sourceMember: MemberLocation? = null,
    val sourceEvent: SensorEvent? = null,
    val sourceAlert: SosAlert? = null,
)

data class SeparationWarningItem(
    val memberUid: String,
    val memberName: String,
    val callsign: String,
    val distanceMeters: Double,
    val thresholdMeters: Double,
    val continuousBreachSeconds: Long,
    val accuracyMeters: Double,
)

enum class SurakshaSection(val route: String, val label: String) {
    MISSIONS("missions", "Missions"),
    RADAR("radar", "Radar"),
    SENSORS("sensors", "Sensors"),
    TEAM("team", "Team"),
    SETTINGS("settings", "Settings"),
}
