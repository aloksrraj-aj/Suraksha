package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.AuditLog
import com.example.data.model.BlockedMembership
import com.example.data.model.DetectionObservation
import com.example.data.model.EnvironmentMode
import com.example.data.model.GatewayDevice
import com.example.data.model.Invitation
import com.example.data.model.MemberLocation
import com.example.data.model.MembershipStatus
import com.example.data.model.Mission
import com.example.data.model.MissionMember
import com.example.data.model.MissionStatus
import com.example.data.model.OrgMember
import com.example.data.model.OrgRole
import com.example.data.model.RescueOrganization
import com.example.data.model.RescueTeam
import com.example.data.model.SensorDevice
import com.example.data.model.SensorEvent
import com.example.data.model.SensorHealth
import com.example.data.model.SosAlert
import com.example.data.model.SosAlertStatus
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import java.util.UUID

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String, val isPermissionDenied: Boolean = false) : UiState<Nothing>
}

class SurakshaRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(context.applicationContext.getString(R.string.firestore_database_id)),
        auth = Firebase.auth,
    )

    val orgStore = OrgMembershipStore(db, auth)

    private fun requireUid(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("Google Sign-In authentication required")

    // --- User Profile ---

    fun observeUserProfile(userId: String): Flow<UiState<UserProfile?>> = callbackFlow {
        if (userId.isBlank()) {
            trySend(UiState.Success(null))
            awaitClose { }
            return@callbackFlow
        }
        val reg = db.collection("users").document(userId).addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.GET, "users/$userId")
                trySend(UiState.Error(err.localizedMessage ?: "Failed to read profile"))
                return@addSnapshotListener
            }
            trySend(UiState.Success(snap?.toObject(UserProfile::class.java)))
        }
        awaitClose { reg.remove() }
    }

    suspend fun ensureUserProfile(
        displayName: String,
        email: String,
        photoUrl: String,
    ): Result<UserProfile> = runCatching {
        val uid = requireUid()
        val ref = db.collection("users").document(uid)
        val snap = ref.get().await()
        if (snap.exists()) {
            snap.toObject(UserProfile::class.java) ?: UserProfile(userId = uid)
        } else {
            val callsign = "RES-${uid.take(4).uppercase()}"
            val data = mapOf(
                "userId" to uid,
                "displayName" to displayName.ifBlank { "Rescue Responder" }.take(80),
                "email" to email.take(160),
                "photoUrl" to photoUrl.take(512),
                "phoneOptional" to "",
                "callsign" to callsign,
                "activeOrgId" to "",
                "activeTeamId" to "",
                "activeMissionId" to "",
                "locationSharingEnabled" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            ref.set(data).await()
            UserProfile(
                id = uid,
                userId = uid,
                displayName = displayName.ifBlank { "Rescue Responder" },
                email = email,
                photoUrl = photoUrl,
                callsign = callsign,
            )
        }
    }.onFailure {
        handleFirestoreError(it, OperationType.WRITE, "users")
    }

    suspend fun updateUserProfileWorkspace(
        activeOrgId: String? = null,
        activeTeamId: String? = null,
        activeMissionId: String? = null,
        displayName: String? = null,
        callsign: String? = null,
        phoneOptional: String? = null,
        locationSharingEnabled: Boolean? = null,
    ): Result<Unit> = runCatching {
        val uid = requireUid()
        val updates = mutableMapOf<String, Any>("updatedAt" to FieldValue.serverTimestamp())
        activeOrgId?.let { updates["activeOrgId"] = it }
        activeTeamId?.let { updates["activeTeamId"] = it }
        activeMissionId?.let { updates["activeMissionId"] = it }
        displayName?.let { updates["displayName"] = it.trim().take(80) }
        callsign?.let { updates["callsign"] = it.trim().take(32) }
        phoneOptional?.let { updates["phoneOptional"] = it.trim().take(32) }
        locationSharingEnabled?.let { updates["locationSharingEnabled"] = it }
        db.collection("users").document(uid).update(updates).await()
        Unit
    }.onFailure {
        handleFirestoreError(it, OperationType.UPDATE, "users")
    }

    // --- Organization & Admin Streams ---

    fun observeOrganization(orgId: String): Flow<UiState<RescueOrganization?>> =
        if (orgId.isBlank()) flowOf(UiState.Success(null))
        else callbackFlow {
            val reg = db.collection("organizations").document(orgId).addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.GET, "organizations/$orgId")
                    trySend(UiState.Error(err.localizedMessage ?: "Access denied", isPermissionDenied = true))
                    return@addSnapshotListener
                }
                trySend(UiState.Success(snap?.toObject(RescueOrganization::class.java)))
            }
            awaitClose { reg.remove() }
        }

    fun observeOrgMembers(orgId: String): Flow<UiState<List<OrgMember>>> =
        observeQuery(db.collection("organizations").document(orgId).collection("members").orderBy("displayName"), orgId)

    fun observeBlockedMemberships(orgId: String): Flow<UiState<List<BlockedMembership>>> =
        observeQuery(db.collection("organizations").document(orgId).collection("blockedMemberships"), orgId)

    fun observeInvitations(orgId: String): Flow<UiState<List<Invitation>>> =
        observeQuery(db.collection("organizations").document(orgId).collection("invitations").orderBy("createdAt", Query.Direction.DESCENDING), orgId)

    fun observeTeams(orgId: String): Flow<UiState<List<RescueTeam>>> =
        observeQuery(db.collection("organizations").document(orgId).collection("teams").orderBy("teamName"), orgId)

    fun observeMissions(orgId: String): Flow<UiState<List<Mission>>> =
        observeQuery(db.collection("organizations").document(orgId).collection("missions").orderBy("updatedAt", Query.Direction.DESCENDING), orgId)

    fun observeAuditLogs(orgId: String): Flow<UiState<List<AuditLog>>> =
        observeQuery(db.collection("organizations").document(orgId).collection("auditLogs").orderBy("createdAt", Query.Direction.DESCENDING).limit(80), orgId)

    fun observeGatewayDevices(orgId: String): Flow<UiState<List<GatewayDevice>>> =
        observeQuery(db.collection("organizations").document(orgId).collection("gatewayDevices"), orgId)

    // --- Mission Live Streams ---

    fun observeMissionMembers(orgId: String, missionId: String): Flow<UiState<List<MissionMember>>> =
        observeMissionQuery(orgId, missionId, "missionMembers")

    fun observeMemberLocations(orgId: String, missionId: String): Flow<UiState<List<MemberLocation>>> =
        observeMissionQuery(orgId, missionId, "locations")

    fun observeSensorDevices(orgId: String, missionId: String): Flow<UiState<List<SensorDevice>>> =
        observeMissionQuery(orgId, missionId, "sensorDevices")

    fun observeSensorHealth(orgId: String, missionId: String): Flow<UiState<List<SensorHealth>>> =
        observeMissionQuery(orgId, missionId, "sensorHealth")

    fun observeSensorEvents(orgId: String, missionId: String): Flow<UiState<List<SensorEvent>>> =
        if (orgId.isBlank() || missionId.isBlank()) flowOf(UiState.Success(emptyList()))
        else callbackFlow {
            val ref = db.collection("organizations").document(orgId)
                .collection("missions").document(missionId)
                .collection("sensorEvents")
                .orderBy("timestampUtcMillis", Query.Direction.DESCENDING)
                .limit(120)
            val reg = ref.addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "sensorEvents")
                    trySend(UiState.Error(err.localizedMessage ?: "Sensor event stream error"))
                    return@addSnapshotListener
                }
                trySend(UiState.Success(snap?.toObjects(SensorEvent::class.java).orEmpty()))
            }
            awaitClose { reg.remove() }
        }

    fun observeDetections(orgId: String, missionId: String): Flow<UiState<List<DetectionObservation>>> =
        observeMissionQuery(orgId, missionId, "detections")

    fun observeSosAlerts(orgId: String, missionId: String): Flow<UiState<List<SosAlert>>> =
        if (orgId.isBlank() || missionId.isBlank()) flowOf(UiState.Success(emptyList()))
        else callbackFlow {
            val ref = db.collection("organizations").document(orgId)
                .collection("missions").document(missionId)
                .collection("sosAlerts")
                .orderBy("createdAt", Query.Direction.DESCENDING)
            val reg = ref.addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "sosAlerts")
                    trySend(UiState.Error(err.localizedMessage ?: "SOS stream error"))
                    return@addSnapshotListener
                }
                trySend(UiState.Success(snap?.toObjects(SosAlert::class.java).orEmpty()))
            }
            awaitClose { reg.remove() }
        }

    // --- Mission Operations ---

    suspend fun createMission(
        orgId: String,
        teamId: String,
        teamCode: String,
        title: String,
        description: String,
        environmentMode: EnvironmentMode,
        separationThresholdMeters: Double,
        separationDelaySeconds: Long,
        centerLatitude: Double,
        centerLongitude: Double,
        displayName: String,
        callsign: String,
        photoUrl: String,
        role: OrgRole,
    ): Result<String> = runCatching {
        val uid = requireUid()
        val missionId = "mission_${UUID.randomUUID().toString().take(8)}"
        val missionRef = db.collection("organizations").document(orgId).collection("missions").document(missionId)
        val batch = db.batch()
        batch.set(
            missionRef,
            mapOf(
                "missionId" to missionId,
                "orgId" to orgId,
                "teamId" to teamId,
                "teamCode" to teamCode,
                "title" to title.trim().take(120),
                "description" to description.trim().take(500),
                "environmentMode" to environmentMode.value,
                "status" to MissionStatus.ACTIVE.value,
                "separationAlertEnabled" to true,
                "separationThresholdMeters" to separationThresholdMeters.coerceIn(10.0, 10_000.0),
                "separationDelaySeconds" to separationDelaySeconds.coerceIn(0L, 600L),
                "centerLatitude" to centerLatitude,
                "centerLongitude" to centerLongitude,
                "leaderUid" to uid,
                "createdByUid" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.set(
            missionRef.collection("missionMembers").document(uid),
            mapOf(
                "userId" to uid,
                "orgId" to orgId,
                "missionId" to missionId,
                "teamId" to teamId,
                "displayName" to displayName.ifBlank { "Responder" }.take(80),
                "callsign" to callsign.ifBlank { "RES-01" }.take(32),
                "photoUrl" to photoUrl.take(512),
                "role" to role.value,
                "status" to MembershipStatus.ACTIVE.value,
                "sharingEnabled" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.commit().await()
        orgStore.logAudit(orgId, missionId, teamId, uid, displayName, "mission", "CREATE_MISSION", uid, "Created mission: $title")
        missionId
    }.onFailure {
        handleFirestoreError(it, OperationType.CREATE, "organizations/$orgId/missions")
    }

    suspend fun joinMissionParticipant(
        orgId: String,
        missionId: String,
        teamId: String,
        displayName: String,
        callsign: String,
        photoUrl: String,
        role: OrgRole,
    ): Result<Unit> = runCatching {
        val uid = requireUid()
        db.collection("organizations").document(orgId)
            .collection("missions").document(missionId)
            .collection("missionMembers").document(uid)
            .set(
                mapOf(
                    "userId" to uid,
                    "orgId" to orgId,
                    "missionId" to missionId,
                    "teamId" to teamId,
                    "displayName" to displayName.ifBlank { "Responder" }.take(80),
                    "callsign" to callsign.ifBlank { "RES-${uid.take(4)}" }.take(32),
                    "photoUrl" to photoUrl.take(512),
                    "role" to role.value,
                    "status" to MembershipStatus.ACTIVE.value,
                    "sharingEnabled" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        Unit
    }.onFailure {
        handleFirestoreError(it, OperationType.WRITE, "organizations/$orgId/missions/$missionId/missionMembers")
    }

    suspend fun updateMissionSettings(
        orgId: String,
        mission: Mission,
        environmentMode: EnvironmentMode,
        status: MissionStatus,
        separationEnabled: Boolean,
        separationThresholdMeters: Double,
        separationDelaySeconds: Long,
    ): Result<Unit> = runCatching {
        requireUid()
        db.collection("organizations").document(orgId)
            .collection("missions").document(mission.missionId)
            .set(
                mapOf(
                    "missionId" to mission.missionId,
                    "orgId" to orgId,
                    "teamId" to mission.teamId,
                    "teamCode" to mission.teamCode,
                    "title" to mission.title,
                    "description" to mission.description,
                    "environmentMode" to environmentMode.value,
                    "status" to status.value,
                    "separationAlertEnabled" to separationEnabled,
                    "separationThresholdMeters" to separationThresholdMeters.coerceIn(10.0, 10_000.0),
                    "separationDelaySeconds" to separationDelaySeconds.coerceIn(0L, 600L),
                    "centerLatitude" to mission.centerLatitude,
                    "centerLongitude" to mission.centerLongitude,
                    "leaderUid" to mission.leaderUid,
                    "createdByUid" to mission.createdByUid,
                    "createdAt" to (mission.createdAt ?: FieldValue.serverTimestamp()),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        Unit
    }.onFailure {
        handleFirestoreError(it, OperationType.UPDATE, "organizations/$orgId/missions/${mission.missionId}")
    }

    suspend fun pushMemberLocation(location: MemberLocation): Result<Unit> = runCatching {
        val uid = requireUid()
        db.collection("organizations").document(location.orgId)
            .collection("missions").document(location.missionId)
            .collection("locations").document(uid)
            .set(
                mapOf(
                    "userId" to uid,
                    "orgId" to location.orgId,
                    "missionId" to location.missionId,
                    "teamId" to location.teamId,
                    "displayName" to location.displayName.take(80),
                    "callsign" to location.callsign.take(32),
                    "role" to location.role,
                    "sharingEnabled" to location.sharingEnabled,
                    "hasValidLocation" to location.hasValidLocation,
                    "latitude" to location.latitude,
                    "longitude" to location.longitude,
                    "accuracyMeters" to location.accuracyMeters,
                    "headingDegrees" to location.headingDegrees,
                    "headingReliable" to location.headingReliable,
                    "batteryPercent" to location.batteryPercent,
                    "connectionStatus" to location.connectionStatus,
                    "locationTimestampMillis" to location.locationTimestampMillis,
                    "unavailableReason" to location.unavailableReason.take(120),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        Unit
    }.onFailure {
        handleFirestoreError(it, OperationType.WRITE, "locations")
    }

    suspend fun saveSensorDevice(device: SensorDevice): Result<Unit> = runCatching {
        val uid = requireUid()
        val id = device.sensorId.ifBlank { "sensor_${UUID.randomUUID().toString().take(8)}" }
        db.collection("organizations").document(device.orgId)
            .collection("missions").document(device.missionId)
            .collection("sensorDevices").document(id)
            .set(
                mapOf(
                    "sensorId" to id,
                    "orgId" to device.orgId,
                    "missionId" to device.missionId,
                    "sensorType" to device.sensorType,
                    "displayName" to device.displayName.take(80),
                    "manufacturerModel" to device.manufacturerModel.take(80),
                    "transportType" to device.transportType,
                    "connectionStatus" to device.connectionStatus,
                    "hasPosition" to device.hasPosition,
                    "latitude" to device.latitude,
                    "longitude" to device.longitude,
                    "orientationDegrees" to device.orientationDegrees.coerceIn(0.0, 360.0),
                    "sectorWidthDegrees" to device.sectorWidthDegrees.coerceIn(0.0, 360.0),
                    "maxRangeMeters" to device.maxRangeMeters.coerceIn(1.0, 5000.0),
                    "calibrationVersion" to device.calibrationVersion.take(40),
                    "lastCalibrationMillis" to device.lastCalibrationMillis,
                    "operatorUid" to uid,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        Unit
    }.onFailure {
        handleFirestoreError(it, OperationType.WRITE, "sensorDevices")
    }

    suspend fun ingestValidatedSensorEvent(event: SensorEvent): Result<String> = runCatching {
        val uid = requireUid()
        val eventId = event.eventId.ifBlank { "evt_${UUID.randomUUID().toString().take(10)}" }
        db.collection("organizations").document(event.orgId)
            .collection("missions").document(event.missionId)
            .collection("sensorEvents").document(eventId)
            .set(
                mapOf(
                    "eventId" to eventId,
                    "orgId" to event.orgId,
                    "missionId" to event.missionId,
                    "sensorId" to event.sensorId,
                    "sensorType" to event.sensorType,
                    "timestampUtcMillis" to event.timestampUtcMillis,
                    "detectionType" to SensorEvent.UNCLASSIFIED_PRESENCE_LABEL,
                    "estimatedRangeMeters" to event.estimatedRangeMeters,
                    "estimatedAzimuthDegrees" to event.estimatedAzimuthDegrees,
                    "estimatedElevationDegrees" to event.estimatedElevationDegrees,
                    "estimatedLatitude" to event.estimatedLatitude,
                    "estimatedLongitude" to event.estimatedLongitude,
                    "positionAccuracyMeters" to event.positionAccuracyMeters,
                    "confidence" to event.confidence.coerceIn(0.0, 1.0),
                    "signalQuality" to event.signalQuality,
                    "detectionDurationSeconds" to event.detectionDurationSeconds,
                    "respirationRateBpm" to event.respirationRateBpm,
                    "sourceDevice" to event.sourceDevice.take(100),
                    "calibrationVersion" to event.calibrationVersion.take(40),
                    "rawDataReference" to event.rawDataReference.take(200),
                    "dataValidity" to event.dataValidity,
                    "status" to event.status,
                    "operatorUid" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        eventId
    }.onFailure {
        handleFirestoreError(it, OperationType.CREATE, "sensorEvents")
    }

    suspend fun logDetectionObservation(obs: DetectionObservation, explicitId: String? = null): Result<String> = runCatching {
        val uid = requireUid()
        val id = explicitId ?: "obs_${UUID.randomUUID().toString().take(10)}"
        db.collection("organizations").document(obs.orgId)
            .collection("missions").document(obs.missionId)
            .collection("detections").document(id)
            .set(
                mapOf(
                    "detectionId" to id,
                    "orgId" to obs.orgId,
                    "missionId" to obs.missionId,
                    "sensorEventId" to obs.sensorEventId,
                    "sensorId" to obs.sensorId,
                    "operatorUid" to uid,
                    "classificationLabel" to SensorEvent.UNCLASSIFIED_PRESENCE_LABEL,
                    "notes" to obs.notes.trim().take(400),
                    "outcome" to obs.outcome,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        id
    }.onFailure {
        handleFirestoreError(it, OperationType.CREATE, "detections")
    }

    suspend fun createSosAlert(alert: SosAlert, explicitId: String? = null): Result<String> = runCatching {
        val uid = requireUid()
        val id = explicitId ?: "sos_${UUID.randomUUID().toString().take(10)}"
        val missionRef = db.collection("organizations").document(alert.orgId)
            .collection("missions").document(alert.missionId)
        missionRef.collection("sosAlerts").document(id)
            .set(
                mapOf(
                    "alertId" to id,
                    "orgId" to alert.orgId,
                    "missionId" to alert.missionId,
                    "senderUid" to uid,
                    "senderName" to alert.senderName.ifBlank { "Responder" }.take(80),
                    "teamId" to alert.teamId,
                    "alertType" to alert.alertType,
                    "priority" to alert.priority,
                    "status" to SosAlertStatus.ACTIVE.value,
                    "hasLocation" to alert.hasLocation,
                    "latitude" to alert.latitude,
                    "longitude" to alert.longitude,
                    "accuracyMeters" to alert.accuracyMeters,
                    "locationTimestampMillis" to alert.locationTimestampMillis,
                    "description" to alert.description.trim().take(500),
                    "acknowledgedByUid" to "",
                    "acknowledgedAtMillis" to 0L,
                    "cancelledByUid" to "",
                    "cancelReason" to "",
                    "notificationStatus" to "pending_dispatch",
                    "deliveryNote" to "Saved to Firestore; dispatching push alerts.",
                    "pushAttemptedCount" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        orgStore.logAudit(alert.orgId, alert.missionId, alert.teamId, uid, alert.senderName, "sos", "SOS_TRIGGERED", uid, alert.description)
        id
    }.onFailure {
        handleFirestoreError(it, OperationType.CREATE, "sosAlerts")
    }

    suspend fun transitionSosAlert(
        orgId: String,
        missionId: String,
        alertId: String,
        nextStatus: SosAlertStatus,
        actorName: String,
        cancelReason: String = "",
    ): Result<Unit> = runCatching {
        val uid = requireUid()
        val updates = mutableMapOf<String, Any>(
            "status" to nextStatus.value,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        when (nextStatus) {
            SosAlertStatus.ACKNOWLEDGED -> {
                updates["acknowledgedByUid"] = uid
                updates["acknowledgedAtMillis"] = System.currentTimeMillis()
            }
            SosAlertStatus.CANCELLED -> {
                updates["cancelledByUid"] = uid
                updates["cancelReason"] = cancelReason.trim().take(300)
            }
            else -> Unit
        }
        db.collection("organizations").document(orgId)
            .collection("missions").document(missionId)
            .collection("sosAlerts").document(alertId)
            .update(updates)
            .await()
        orgStore.logAudit(orgId, missionId, "", uid, actorName, "sos", "SOS_${nextStatus.name}", uid, "SOS $alertId -> ${nextStatus.value} $cancelReason".trim())
        Unit
    }.onFailure {
        handleFirestoreError(it, OperationType.UPDATE, "sosAlerts/$alertId")
    }

    suspend fun upsertGatewayDevice(gateway: GatewayDevice): Result<Unit> = runCatching {
        val uid = requireUid()
        val id = gateway.gatewayId.ifBlank { "gw_${UUID.randomUUID().toString().take(8)}" }
        db.collection("organizations").document(gateway.orgId)
            .collection("gatewayDevices").document(id)
            .set(
                mapOf(
                    "gatewayId" to id,
                    "orgId" to gateway.orgId,
                    "name" to gateway.name.trim().take(80),
                    "transportType" to gateway.transportType,
                    "endpointUri" to gateway.endpointUri.trim().take(256),
                    "status" to gateway.status,
                    "bufferedEventCount" to gateway.bufferedEventCount,
                    "lastHeartbeatMillis" to gateway.lastHeartbeatMillis,
                    "registeredByUid" to uid,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        Unit
    }.onFailure {
        handleFirestoreError(it, OperationType.WRITE, "gatewayDevices")
    }

    private inline fun <reified T> observeQuery(query: Query, orgId: String): Flow<UiState<List<T>>> =
        if (orgId.isBlank()) flowOf(UiState.Success(emptyList()))
        else callbackFlow {
            val reg = query.addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "organizations/$orgId")
                    trySend(UiState.Error(err.localizedMessage ?: "Query denied or unavailable", isPermissionDenied = true))
                    return@addSnapshotListener
                }
                trySend(UiState.Success(snap?.toObjects(T::class.java).orEmpty()))
            }
            awaitClose { reg.remove() }
        }

    private inline fun <reified T> observeMissionQuery(orgId: String, missionId: String, subcollection: String): Flow<UiState<List<T>>> =
        if (orgId.isBlank() || missionId.isBlank()) flowOf(UiState.Success(emptyList()))
        else callbackFlow {
            val ref = db.collection("organizations").document(orgId)
                .collection("missions").document(missionId)
                .collection(subcollection)
            val reg = ref.addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "missions/$missionId/$subcollection")
                    trySend(UiState.Error(err.localizedMessage ?: "Mission stream error"))
                    return@addSnapshotListener
                }
                trySend(UiState.Success(snap?.toObjects(T::class.java).orEmpty()))
            }
            awaitClose { reg.remove() }
        }
}
