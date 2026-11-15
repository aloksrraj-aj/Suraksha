package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.example.data.model.TeamAlert
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class SurakshaRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {

    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth,
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Responder must sign in with Google before accessing Suraksha data.")
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObject(
                        UserProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamError(error, OperationType.GET, path, userId)
                    throw error
                }
        )
    }

    fun observeRescueTeams(userId: String): Flow<List<RescueTeam>> = flow {
        val path = "users/$userId/rescue_teams"
        emitAll(
            db.collection("users").document(userId).collection("rescue_teams")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RescueTeam::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    fun observeRescueLocations(userId: String): Flow<List<RescueLocation>> = flow {
        val path = "users/$userId/rescue_locations"
        emitAll(
            db.collection("users").document(userId).collection("rescue_locations")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RescueLocation::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    fun observePresenceSignals(userId: String): Flow<List<PresenceSignal>> = flow {
        val path = "users/$userId/presence_signals"
        emitAll(
            db.collection("users").document(userId).collection("presence_signals")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        PresenceSignal::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.confidencePercent }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    fun observeRescueRegistry(userId: String): Flow<List<RescueRegistryEntry>> = flow {
        val path = "users/$userId/rescue_registry"
        emitAll(
            db.collection("users").document(userId).collection("rescue_registry")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RescueRegistryEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    fun observeTeamAlerts(userId: String): Flow<List<TeamAlert>> = flow {
        val path = "users/$userId/team_alerts"
        emitAll(
            db.collection("users").document(userId).collection("team_alerts")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        TeamAlert::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun ensureUserProfile(
        displayName: String,
        callsign: String = "NDRF-CMD-01",
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        return try {
            val existing = docRef.get().await()
            if (!existing.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to displayName.ifBlank { "Suraksha Responder" }.take(80),
                    "callsign" to callsign.take(30),
                    "teamId" to "ndrf-alpha",
                    "teamName" to "NDRF Alpha Response",
                    "role" to "coordinator",
                    "locationSharingEnabled" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateLocationSharingPreference(enabled: Boolean): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        return try {
            docRef.update(
                mapOf(
                    "locationSharingEnabled" to enabled,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createRescueTeam(team: RescueTeam): Result<String> {
        val uid = requireUserId()
        val docId = "team_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = db.collection("users").document(uid).collection("rescue_teams").document(docId)
        val payload = mapOf(
            "userId" to uid,
            "teamCode" to team.teamCode.trim().take(32),
            "teamName" to team.teamName.trim().take(80),
            "memberCount" to team.memberCount.coerceIn(1L, 200L),
            "activeCount" to team.activeCount.coerceIn(0L, team.memberCount.coerceIn(1L, 200L)),
            "status" to team.status,
            "locationSharingOptIn" to team.locationSharingOptIn,
            "latitude" to team.latitude.coerceIn(-90.0, 90.0),
            "longitude" to team.longitude.coerceIn(-180.0, 180.0),
            "batteryPercent" to team.batteryPercent.coerceIn(0L, 100L),
            "connectionStatus" to team.connectionStatus,
            "isSynthetic" to team.isSynthetic,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        return try {
            docRef.set(payload).await()
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateRescueTeam(team: RescueTeam): Result<Unit> {
        val uid = requireUserId()
        require(team.id.isNotBlank()) { "Team ID must not be blank for update" }
        requireNotNull(team.createdAt) { "Team createdAt must not be null for update" }
        val docRef = db.collection("users").document(uid).collection("rescue_teams").document(team.id)
        return try {
            docRef.set(team.copy(userId = uid, updatedAt = null)).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createRescueLocation(location: RescueLocation): Result<String> {
        val uid = requireUserId()
        val docId = "loc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = db.collection("users").document(uid).collection("rescue_locations").document(docId)
        val payload = mapOf(
            "userId" to uid,
            "name" to location.name.trim().take(100),
            "region" to location.region.trim().take(80),
            "hazardType" to location.hazardType,
            "severity" to location.severity,
            "latitude" to location.latitude.coerceIn(-90.0, 90.0),
            "longitude" to location.longitude.coerceIn(-180.0, 180.0),
            "verificationStatus" to location.verificationStatus,
            "dataSource" to location.dataSource.trim().take(100),
            "isSynthetic" to location.isSynthetic,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        return try {
            docRef.set(payload).await()
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updatePresenceSignalVerification(
        signalId: String,
        newStatus: String,
        newConfidence: Long,
        dataSource: String,
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("presence_signals").document(signalId)
        return try {
            docRef.update(
                mapOf(
                    "verificationStatus" to newStatus,
                    "confidencePercent" to newConfidence.coerceIn(0L, 100L),
                    "dataSource" to dataSource.take(100),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createPresenceSignal(signal: PresenceSignal): Result<String> {
        val uid = requireUserId()
        val docId = "sig_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = db.collection("users").document(uid).collection("presence_signals").document(docId)
        val minCount = signal.estimatedMinCount.coerceIn(0L, 500L)
        val maxCount = signal.estimatedMaxCount.coerceIn(minCount, 500L)
        val payload = mapOf(
            "userId" to uid,
            "locationId" to signal.locationId.ifBlank { "sector_default" }.take(128),
            "sectorLabel" to signal.sectorLabel.trim().take(80),
            "signalType" to signal.signalType,
            "estimatedMinCount" to minCount,
            "estimatedMaxCount" to maxCount,
            "confidencePercent" to signal.confidencePercent.coerceIn(0L, 100L),
            "verificationStatus" to signal.verificationStatus,
            "dataSource" to signal.dataSource.trim().take(100),
            "isSynthetic" to signal.isSynthetic,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        return try {
            docRef.set(payload).await()
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createRegistryEntry(entry: RescueRegistryEntry): Result<String> {
        val uid = requireUserId()
        val docId = "reg_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = db.collection("users").document(uid).collection("rescue_registry").document(docId)
        val payload = mapOf(
            "userId" to uid,
            "category" to entry.category,
            "title" to entry.title.trim().take(100),
            "locationName" to entry.locationName.trim().take(100),
            "details" to entry.details.trim().take(500),
            "sensitiveContactMasked" to entry.sensitiveContactMasked.trim().take(100),
            "rescueStatus" to entry.rescueStatus,
            "verificationStatus" to entry.verificationStatus,
            "informationSource" to entry.informationSource.trim().take(100),
            "verificationNotes" to entry.verificationNotes.trim().take(300),
            "isSynthetic" to entry.isSynthetic,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        return try {
            docRef.set(payload).await()
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateRegistryEntry(entry: RescueRegistryEntry): Result<Unit> {
        val uid = requireUserId()
        require(entry.id.isNotBlank()) { "Registry ID must not be blank for update" }
        requireNotNull(entry.createdAt) { "Registry createdAt must not be null for update" }
        val docRef = db.collection("users").document(uid).collection("rescue_registry").document(entry.id)
        return try {
            docRef.set(entry.copy(userId = uid, updatedAt = null)).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun sendTeamAlert(alert: TeamAlert): Result<String> {
        val uid = requireUserId()
        val docId = "alt_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = db.collection("users").document(uid).collection("team_alerts").document(docId)
        val payload = mapOf(
            "userId" to uid,
            "teamCode" to alert.teamCode.trim().take(32),
            "priority" to alert.priority,
            "message" to alert.message.trim().take(400),
            "channel" to alert.channel,
            "isSynthetic" to alert.isSynthetic,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        return try {
            docRef.set(payload).await()
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun seedDemoDatasetIfEmpty(): Result<Unit> {
        val uid = requireUserId()
        val locationsCol = db.collection("users").document(uid).collection("rescue_locations")
        return try {
            val existing = locationsCol.limit(1).get().await()
            if (!existing.isEmpty) {
                return Result.success(Unit)
            }
            seedDemoDatasetForce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, locationsCol.path)
            Result.failure(e)
        }
    }

    suspend fun seedDemoDatasetForce(): Result<Unit> {
        val uid = requireUserId()
        return try {
            val loc1 = createRescueLocation(
                RescueLocation(
                    userId = uid,
                    name = "Joshimath Ridge Landslide Zone",
                    region = "Chamoli, Uttarakhand",
                    hazardType = "landslide",
                    severity = "critical",
                    latitude = 30.5506,
                    longitude = 79.5660,
                    verificationStatus = "verified",
                    dataSource = "Synthetic Field Feed (Demo)",
                    isSynthetic = true,
                )
            ).getOrThrow()

            val loc2 = createRescueLocation(
                RescueLocation(
                    userId = uid,
                    name = "Kutch Masonry Collapse Sector B",
                    region = "Bhuj, Gujarat",
                    hazardType = "structural_collapse",
                    severity = "high",
                    latitude = 23.2420,
                    longitude = 69.6669,
                    verificationStatus = "estimated",
                    dataSource = "Synthetic LiDAR Sim (Demo)",
                    isSynthetic = true,
                )
            ).getOrThrow()

            createRescueLocation(
                RescueLocation(
                    userId = uid,
                    name = "Majuli Riverbank Flood Ward 3",
                    region = "Jorhat, Assam",
                    hazardType = "flood",
                    severity = "high",
                    latitude = 26.9544,
                    longitude = 94.1678,
                    verificationStatus = "verified",
                    dataSource = "Synthetic Flood Gauge (Demo)",
                    isSynthetic = true,
                )
            ).getOrThrow()

            createRescueLocation(
                RescueLocation(
                    userId = uid,
                    name = "Leh-Khardung High Altitude Shelter",
                    region = "Leh, Ladakh",
                    hazardType = "earthquake",
                    severity = "moderate",
                    latitude = 34.1526,
                    longitude = 77.5771,
                    verificationStatus = "unverified",
                    dataSource = "Synthetic Relay Ping (Demo)",
                    isSynthetic = true,
                )
            ).getOrThrow()

            createRescueTeam(
                RescueTeam(
                    userId = uid,
                    teamCode = "NDRF-01",
                    teamName = "Himalayan Search & Rescue Alpha",
                    memberCount = 12L,
                    activeCount = 10L,
                    status = "deployed",
                    locationSharingOptIn = true,
                    latitude = 30.5512,
                    longitude = 79.5675,
                    batteryPercent = 84L,
                    connectionStatus = "online",
                    isSynthetic = true,
                )
            ).getOrThrow()

            createRescueTeam(
                RescueTeam(
                    userId = uid,
                    teamCode = "SDRF-04",
                    teamName = "Brahmaputra Swift-Water Medical",
                    memberCount = 8L,
                    activeCount = 7L,
                    status = "active",
                    locationSharingOptIn = true,
                    latitude = 26.9560,
                    longitude = 94.1691,
                    batteryPercent = 69L,
                    connectionStatus = "low_bandwidth",
                    isSynthetic = true,
                )
            ).getOrThrow()

            createRescueTeam(
                RescueTeam(
                    userId = uid,
                    teamCode = "MED-02",
                    teamName = "Kutch Structural Triage Unit",
                    memberCount = 6L,
                    activeCount = 4L,
                    status = "standby",
                    locationSharingOptIn = false,
                    latitude = 0.0,
                    longitude = 0.0,
                    batteryPercent = 92L,
                    connectionStatus = "mesh_offline",
                    isSynthetic = true,
                )
            ).getOrThrow()

            createPresenceSignal(
                PresenceSignal(
                    userId = uid,
                    locationId = loc2,
                    sectorLabel = "Collapsed Wing B2 (Void Pocket)",
                    signalType = "radar_respiration",
                    estimatedMinCount = 1L,
                    estimatedMaxCount = 2L,
                    confidencePercent = 78L,
                    verificationStatus = "estimated",
                    dataSource = "Simulated UWB Micro-Doppler",
                    isSynthetic = true,
                )
            ).getOrThrow()

            createPresenceSignal(
                PresenceSignal(
                    userId = uid,
                    locationId = loc1,
                    sectorLabel = "Ridge Terrace Retaining Wall",
                    signalType = "thermal_ir",
                    estimatedMinCount = 1L,
                    estimatedMaxCount = 3L,
                    confidencePercent = 64L,
                    verificationStatus = "unverified",
                    dataSource = "Simulated Thermal IR (FLIR Model)",
                    isSynthetic = true,
                )
            ).getOrThrow()

            createPresenceSignal(
                PresenceSignal(
                    userId = uid,
                    locationId = loc1,
                    sectorLabel = "Lower Culvert Sub-Grade",
                    signalType = "acoustic_vibration",
                    estimatedMinCount = 0L,
                    estimatedMaxCount = 1L,
                    confidencePercent = 41L,
                    verificationStatus = "outdated",
                    dataSource = "Simulated Geophone Array",
                    isSynthetic = true,
                )
            ).getOrThrow()

            createRegistryEntry(
                RescueRegistryEntry(
                    userId = uid,
                    category = "missing_person",
                    title = "Rohan Sharma (Age 34, Trekker)",
                    locationName = "Joshimath Ridge Trail Marker 6",
                    details = "Last contact prior to ridge subsidence. Wearing blue rain shell; carrying orange pack.",
                    sensitiveContactMasked = "NOK: +91-98***-**412",
                    rescueStatus = "in_progress",
                    verificationStatus = "unverified",
                    informationSource = "District Emergency Desk",
                    verificationNotes = "Awaiting visual confirmation from NDRF-01 scout.",
                    isSynthetic = true,
                )
            ).getOrThrow()

            createRegistryEntry(
                RescueRegistryEntry(
                    userId = uid,
                    category = "verified_survivor",
                    title = "Meera Devi & Family (3 Individuals)",
                    locationName = "Majuli Ward 3 Relief Boat",
                    details = "Safely evacuated to elevated community school shelter. Stable hydration and vitals.",
                    sensitiveContactMasked = "Med ID: SUR-MJL-084",
                    rescueStatus = "resolved",
                    verificationStatus = "verified",
                    informationSource = "SDRF-04 Field Medic",
                    verificationNotes = "Verified in person by Dr. Sen at 08:40 IST.",
                    isSynthetic = true,
                )
            ).getOrThrow()

            createRegistryEntry(
                RescueRegistryEntry(
                    userId = uid,
                    category = "medical_request",
                    title = "Portable Oxygen & Splint Kit Request",
                    locationName = "Kutch Collapse Sector B",
                    details = "Requested trauma stabilization kit and 2 portable O2 cylinders for rubble extraction.",
                    sensitiveContactMasked = "Req Ref: MED-KT-19",
                    rescueStatus = "dispatched",
                    verificationStatus = "verified",
                    informationSource = "Sector Triage Lead",
                    verificationNotes = "Dispatched via ambulance convoy; ETA 18 min.",
                    isSynthetic = true,
                )
            ).getOrThrow()

            sendTeamAlert(
                TeamAlert(
                    userId = uid,
                    teamCode = "NDRF-01",
                    priority = "urgent",
                    message = "Synthetic Demo Dispatch: Micro-Doppler respiration anomaly logged in Sector B2. Maintain acoustic silence during confirmation.",
                    channel = "mesh_radio",
                    isSynthetic = true,
                )
            ).getOrThrow()

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun logStreamError(
        error: Throwable,
        operationType: OperationType,
        path: String,
        expectedUid: String,
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == expectedUid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }
}
