package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.ActivityLogEntry
import com.example.data.model.BlockedRoadMarker
import com.example.data.model.DroneTelemetry
import com.example.data.model.NotificationDispatchRecord
import com.example.data.model.OrgInvite
import com.example.data.model.OrgMember
import com.example.data.model.OrgRole
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueMission
import com.example.data.model.RescueOrganization
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.example.data.model.SosAuditLog
import com.example.data.model.SosIncident
import com.example.data.model.SosIncidentStatus
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
import java.security.SecureRandom
import java.util.Locale
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

    private fun orgDoc(orgId: String) = db.collection("organizations").document(orgId)

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

    fun observeOrganization(orgId: String): Flow<RescueOrganization?> = flow {
        val path = "organizations/$orgId"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObject(
                        RescueOrganization::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamError(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    fun observeOrgMembers(orgId: String): Flow<List<OrgMember>> = flow {
        val path = "organizations/$orgId/members"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("members")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        OrgMember::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.role }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeOrgInvites(orgId: String): Flow<List<OrgInvite>> = flow {
        val path = "organizations/$orgId/invites"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("invites")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        OrgInvite::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeRescueTeams(orgId: String): Flow<List<RescueTeam>> = flow {
        val path = "organizations/$orgId/teams"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("teams")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RescueTeam::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeRescueLocations(orgId: String): Flow<List<RescueLocation>> = flow {
        val path = "organizations/$orgId/locations"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("locations")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RescueLocation::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observePresenceSignals(orgId: String): Flow<List<PresenceSignal>> = flow {
        val path = "organizations/$orgId/presence_signals"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("presence_signals")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        PresenceSignal::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.observationTimestampMillis }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeRescueRegistry(orgId: String): Flow<List<RescueRegistryEntry>> = flow {
        val path = "organizations/$orgId/registry"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("registry")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RescueRegistryEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeSosIncidents(orgId: String): Flow<List<SosIncident>> = flow {
        val path = "organizations/$orgId/sos_incidents"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("sos_incidents")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SosIncident::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeSosAuditLogs(orgId: String, incidentId: String): Flow<List<SosAuditLog>> = flow {
        val path = "organizations/$orgId/sos_incidents/$incidentId/audit_logs"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("sos_incidents").document(incidentId).collection("audit_logs")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SosAuditLog::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeNotificationDispatches(orgId: String): Flow<List<NotificationDispatchRecord>> = flow {
        val path = "organizations/$orgId/notification_dispatches"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("notification_dispatches")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        NotificationDispatchRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeActivityLogs(orgId: String): Flow<List<ActivityLogEntry>> = flow {
        val path = "organizations/$orgId/activity_logs"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("activity_logs")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ActivityLogEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.seconds ?: 0L }.take(60)
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeMissions(orgId: String): Flow<List<RescueMission>> = flow {
        val path = "organizations/$orgId/missions"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("missions")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RescueMission::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeBlockedRoads(orgId: String): Flow<List<BlockedRoadMarker>> = flow {
        val path = "organizations/$orgId/blocked_roads"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("blocked_roads")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        BlockedRoadMarker::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeDroneTelemetry(orgId: String): Flow<List<DroneTelemetry>> = flow {
        val path = "organizations/$orgId/drone_telemetry"
        val uid = auth.currentUser?.uid ?: ""
        emitAll(
            orgDoc(orgId).collection("drone_telemetry")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DroneTelemetry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    suspend fun ensureWorkspaceInitialized(
        displayName: String,
        callsign: String = "NDRF-CMD-01",
        preferredOrgId: String = "",
    ): Result<String> {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val defaultOrgId = "org_${uid.replace(Regex("[^a-zA-Z0-9_-]"), "").take(18)}"
        return try {
            val userSnap = userRef.get().await()
            val storedOrgId = userSnap.getString("activeOrgId").orEmpty()
            var targetOrgId = listOf(preferredOrgId, storedOrgId, defaultOrgId).firstOrNull { it.isNotBlank() }
                ?: defaultOrgId

            val orgSnapshot = try {
                orgDoc(targetOrgId).get().await()
            } catch (_: Exception) {
                targetOrgId = defaultOrgId
                runCatching { orgDoc(targetOrgId).get().await() }.getOrNull()
            }

            if (orgSnapshot == null || !orgSnapshot.exists()) {
                orgDoc(targetOrgId).set(
                    mapOf(
                        "orgCode" to "SUR-${uid.take(4).uppercase(Locale.US)}",
                        "orgName" to "Suraksha Command Sector",
                        "region" to "India Multi-Hazard Command",
                        "createdByUid" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }

            val memberRef = orgDoc(targetOrgId).collection("members").document(uid)
            val memberSnap = memberRef.get().await()
            if (!memberSnap.exists()) {
                memberRef.set(
                    mapOf(
                        "userId" to uid,
                        "orgId" to targetOrgId,
                        "displayName" to displayName.ifBlank { "Suraksha Responder" }.take(80),
                        "callsign" to callsign.take(30),
                        "role" to OrgRole.ORG_ADMIN.value,
                        "teamId" to "demo_team_ndrf01",
                        "availability" to "available",
                        "inviteCode" to "",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }

            if (!userSnap.exists()) {
                userRef.set(
                    mapOf(
                        "userId" to uid,
                        "displayName" to displayName.ifBlank { "Suraksha Responder" }.take(80),
                        "callsign" to callsign.take(30),
                        "activeOrgId" to targetOrgId,
                        "teamId" to "demo_team_ndrf01",
                        "teamName" to "NDRF Alpha Response",
                        "role" to OrgRole.ORG_ADMIN.value,
                        "locationSharingEnabled" to false,
                        "backgroundTrackingEnabled" to false,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            } else if (storedOrgId != targetOrgId) {
                userRef.update(
                    mapOf(
                        "activeOrgId" to targetOrgId,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }

            Result.success(targetOrgId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userRef.path)
            Result.failure(e)
        }
    }

    suspend fun switchActiveOrganization(orgId: String): Result<String> {
        val uid = requireUserId()
        val clean = orgId.trim()
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("Organization ID required"))
        return try {
            val memberSnap = orgDoc(clean).collection("members").document(uid).get().await()
            if (!memberSnap.exists()) {
                return Result.failure(IllegalStateException("You are not a member of organization $clean."))
            }
            val role = memberSnap.getString("role") ?: OrgRole.OBSERVER.value
            db.collection("users").document(uid).update(
                mapOf(
                    "activeOrgId" to clean,
                    "role" to role,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(clean)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun createOrganization(
        orgName: String,
        orgCode: String,
        region: String,
        creatorDisplayName: String,
        creatorCallsign: String,
    ): Result<String> {
        val uid = requireUserId()
        val orgId = "org_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        return try {
            orgDoc(orgId).set(
                mapOf(
                    "orgCode" to orgCode.trim().uppercase(Locale.US).take(32),
                    "orgName" to orgName.trim().take(100),
                    "region" to region.trim().take(80),
                    "createdByUid" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()

            orgDoc(orgId).collection("members").document(uid).set(
                mapOf(
                    "userId" to uid,
                    "orgId" to orgId,
                    "displayName" to creatorDisplayName.ifBlank { "Org Commander" }.take(80),
                    "callsign" to creatorCallsign.ifBlank { "CMD-01" }.take(30),
                    "role" to OrgRole.ORG_ADMIN.value,
                    "teamId" to "",
                    "availability" to "available",
                    "inviteCode" to "",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()

            db.collection("users").document(uid).update(
                mapOf(
                    "activeOrgId" to orgId,
                    "role" to OrgRole.ORG_ADMIN.value,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()

            logOrgActivity(orgId, creatorDisplayName, "invite", "Created organization $orgName ($orgCode)", orgId)
            Result.success(orgId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "organizations/$orgId")
            Result.failure(e)
        }
    }

    suspend fun createOrgInvite(
        orgId: String,
        inviteCode: String = "",
        defaultRole: String = OrgRole.OPERATOR.value,
        expiresInHours: Long = 48L,
        maxUses: Long = 25L,
    ): Result<String> {
        val uid = requireUserId()
        val rawCode = inviteCode.ifBlank { generateSecureInviteCode() }
        val cleanCode = rawCode.trim().uppercase(Locale.US).replace(Regex("[^A-Z0-9_-]"), "").take(32)
        if (cleanCode.length < 8) {
            return Result.failure(IllegalArgumentException("Invite code must be at least 8 high-entropy characters."))
        }
        val safeRole = if (defaultRole == OrgRole.OBSERVER.value) OrgRole.OBSERVER.value else OrgRole.OPERATOR.value
        val expiresAtMillis = System.currentTimeMillis() + expiresInHours.coerceIn(1L, 720L) * 3_600_000L
        val safeMaxUses = maxUses.coerceIn(1L, 500L)
        val docRef = orgDoc(orgId).collection("invites").document(cleanCode)
        return try {
            docRef.set(
                mapOf(
                    "orgId" to orgId,
                    "inviteCode" to cleanCode,
                    "defaultRole" to safeRole,
                    "active" to true,
                    "expiresAtMillis" to expiresAtMillis,
                    "maxUses" to safeMaxUses,
                    "usedCount" to 0L,
                    "createdByUid" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            logOrgActivity(orgId, "Org Admin", "invite", "Generated $safeRole invite ($safeMaxUses max uses)", cleanCode)
            Result.success(cleanCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun revokeOrgInvite(orgId: String, inviteCode: String): Result<Unit> {
        requireUserId()
        val docRef = orgDoc(orgId).collection("invites").document(inviteCode)
        return try {
            docRef.update(
                mapOf(
                    "active" to false,
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

    suspend fun joinOrganizationWithInvite(
        orgId: String,
        inviteCode: String,
        displayName: String,
        callsign: String,
    ): Result<String> {
        val uid = requireUserId()
        val cleanOrgId = orgId.trim()
        val cleanCode = inviteCode.trim().uppercase(Locale.US)
        if (cleanOrgId.isBlank() || cleanCode.length < 8) {
            return Result.failure(IllegalArgumentException("Organization ID and a valid 8+ character invite code are required."))
        }
        val inviteRef = orgDoc(cleanOrgId).collection("invites").document(cleanCode)
        val memberRef = orgDoc(cleanOrgId).collection("members").document(uid)
        return try {
            val assignedRole = db.runTransaction { tx ->
                val inviteSnap = tx.get(inviteRef)
                if (!inviteSnap.exists() || inviteSnap.getBoolean("active") != true) {
                    throw IllegalStateException("Invite code is invalid or revoked.")
                }
                val expiresAt = inviteSnap.getLong("expiresAtMillis") ?: 0L
                if (expiresAt > 0L && expiresAt <= System.currentTimeMillis()) {
                    throw IllegalStateException("Invite code has expired.")
                }
                val maxUses = inviteSnap.getLong("maxUses") ?: 1L
                val usedCount = inviteSnap.getLong("usedCount") ?: 0L
                if (usedCount >= maxUses) {
                    throw IllegalStateException("Invite code has reached its maximum use limit.")
                }
                val existingMember = tx.get(memberRef)
                if (existingMember.exists()) {
                    throw IllegalStateException("You are already a member of this organization.")
                }

                val defaultRole = inviteSnap.getString("defaultRole")
                    ?.takeIf { it == OrgRole.OPERATOR.value || it == OrgRole.OBSERVER.value }
                    ?: OrgRole.OPERATOR.value

                tx.set(
                    memberRef,
                    mapOf(
                        "userId" to uid,
                        "orgId" to cleanOrgId,
                        "displayName" to displayName.ifBlank { "Responder" }.take(80),
                        "callsign" to callsign.ifBlank { "OPS-01" }.take(30),
                        "role" to defaultRole,
                        "teamId" to "",
                        "availability" to "available",
                        "inviteCode" to cleanCode,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
                tx.update(
                    inviteRef,
                    mapOf(
                        "usedCount" to usedCount + 1L,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
                defaultRole
            }.await()

            db.collection("users").document(uid).update(
                mapOf(
                    "activeOrgId" to cleanOrgId,
                    "role" to assignedRole,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()

            Result.success(cleanOrgId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, memberRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateMemberRole(
        orgId: String,
        memberUid: String,
        newRole: OrgRole,
        teamId: String? = null,
    ): Result<Unit> {
        requireUserId()
        val docRef = orgDoc(orgId).collection("members").document(memberUid)
        return try {
            val updates = mutableMapOf<String, Any>(
                "role" to newRole.value,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            if (teamId != null) {
                updates["teamId"] = teamId.take(64)
            }
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateMemberAvailability(
        orgId: String,
        availability: String,
    ): Result<Unit> {
        val uid = requireUserId()
        val safeAvail = if (availability in setOf("available", "deployed", "standby", "offline")) availability else "available"
        val docRef = orgDoc(orgId).collection("members").document(uid)
        return try {
            docRef.update(
                mapOf(
                    "availability" to safeAvail,
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

    suspend fun createRescueTeam(
        orgId: String,
        team: RescueTeam,
        explicitId: String? = null,
    ): Result<String> {
        val uid = requireUserId()
        val docId = explicitId?.takeIf { it.isNotBlank() }
            ?: "team_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = orgDoc(orgId).collection("teams").document(docId)
        val hasValid = team.locationSharingOptIn &&
            team.hasValidLocation &&
            team.locationTimestampMillis > 0L &&
            team.latitude in -90.0..90.0 &&
            team.longitude in -180.0..180.0
        val payload = mapOf(
            "orgId" to orgId,
            "userId" to uid,
            "leaderUid" to team.leaderUid.ifBlank { uid },
            "teamCode" to team.teamCode.trim().take(32),
            "teamName" to team.teamName.trim().take(80),
            "memberCount" to team.memberCount.coerceIn(1L, 200L),
            "activeCount" to team.activeCount.coerceIn(0L, team.memberCount.coerceIn(1L, 200L)),
            "status" to team.status,
            "locationSharingOptIn" to team.locationSharingOptIn,
            "hasValidLocation" to hasValid,
            "latitude" to if (hasValid) team.latitude.coerceIn(-90.0, 90.0) else 0.0,
            "longitude" to if (hasValid) team.longitude.coerceIn(-180.0, 180.0) else 0.0,
            "accuracyMeters" to if (hasValid) team.accuracyMeters.coerceIn(0.0, 50_000.0) else 0.0,
            "locationTimestampMillis" to if (hasValid) team.locationTimestampMillis.coerceAtLeast(1L) else 0L,
            "locationUnavailableReason" to if (hasValid) "" else team.locationUnavailableReason.ifBlank { "Location sharing disabled" }.take(120),
            "batteryPercent" to team.batteryPercent.coerceIn(0L, 100L),
            "connectionStatus" to normalizeConnectionStatus(team.connectionStatus),
            "isSynthetic" to team.isSynthetic,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        return try {
            docRef.set(payload).await()
            logOrgActivity(orgId, team.teamCode, "team", "Registered team ${team.teamCode} (${team.teamName})", docId)
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateTeamLocation(
        orgId: String,
        teamId: String,
        optIn: Boolean,
        hasValidLocation: Boolean,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        timestampMillis: Long,
        unavailableReason: String,
        batteryPercent: Long,
        connectionStatus: String,
    ): Result<Unit> {
        requireUserId()
        val docRef = orgDoc(orgId).collection("teams").document(teamId)
        val valid = optIn && hasValidLocation && timestampMillis > 0L && latitude in -90.0..90.0 && longitude in -180.0..180.0
        return try {
            docRef.update(
                mapOf(
                    "locationSharingOptIn" to optIn,
                    "hasValidLocation" to valid,
                    "latitude" to if (valid) latitude else 0.0,
                    "longitude" to if (valid) longitude else 0.0,
                    "accuracyMeters" to if (valid) accuracyMeters.coerceIn(0.0, 50_000.0) else 0.0,
                    "locationTimestampMillis" to if (valid) timestampMillis.coerceAtLeast(1L) else 0L,
                    "locationUnavailableReason" to if (valid) "" else unavailableReason.take(120),
                    "batteryPercent" to batteryPercent.coerceIn(0L, 100L),
                    "connectionStatus" to normalizeConnectionStatus(connectionStatus),
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

    suspend fun deleteRescueTeam(orgId: String, teamId: String): Result<Unit> {
        requireUserId()
        val docRef = orgDoc(orgId).collection("teams").document(teamId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createRescueLocation(
        orgId: String,
        location: RescueLocation,
        explicitId: String? = null,
    ): Result<String> {
        val uid = requireUserId()
        val docId = explicitId?.takeIf { it.isNotBlank() }
            ?: "loc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = orgDoc(orgId).collection("locations").document(docId)
        val payload = mapOf(
            "orgId" to orgId,
            "userId" to uid,
            "name" to location.name.trim().take(100),
            "region" to location.region.trim().take(80),
            "hazardType" to location.hazardType,
            "severity" to location.severity,
            "latitude" to location.latitude.coerceIn(-90.0, 90.0),
            "longitude" to location.longitude.coerceIn(-180.0, 180.0),
            "radiusMeters" to location.radiusMeters.coerceIn(10.0, 100_000.0),
            "verificationStatus" to location.verificationStatus,
            "dataSource" to location.dataSource.trim().take(100),
            "isSynthetic" to location.isSynthetic,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        return try {
            docRef.set(payload).await()
            logOrgActivity(orgId, "Responder", "location", "Logged disaster site ${location.name} (${location.severity})", docId)
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteRescueLocation(orgId: String, locationId: String): Result<Unit> {
        requireUserId()
        val docRef = orgDoc(orgId).collection("locations").document(locationId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createPresenceSignal(
        orgId: String,
        signal: PresenceSignal,
        explicitId: String? = null,
    ): Result<String> {
        val uid = requireUserId()
        val docId = explicitId?.takeIf { it.isNotBlank() }
            ?: "sig_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = orgDoc(orgId).collection("presence_signals").document(docId)
        val minCount = signal.estimatedMinCount.coerceIn(0L, 500L)
        val maxCount = signal.estimatedMaxCount.coerceIn(minCount, 500L)
        val safeInitialStatus = if (signal.verificationStatus == "unverified") "unverified" else "estimated"
        val obsTs = signal.observationTimestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val payload = mapOf(
            "orgId" to orgId,
            "userId" to uid,
            "locationId" to signal.locationId.ifBlank { "sector_default" }.take(128),
            "sectorLabel" to signal.sectorLabel.trim().take(80),
            "signalType" to signal.signalType,
            "sourceKind" to if (signal.sourceKind == "hardware") "hardware" else "simulated",
            "deviceId" to signal.deviceId.take(80),
            "calibrationStatus" to signal.calibrationStatus,
            "sensorHealth" to signal.sensorHealth,
            "latitude" to if (signal.hasCoordinates) signal.latitude.coerceIn(-90.0, 90.0) else 0.0,
            "longitude" to if (signal.hasCoordinates) signal.longitude.coerceIn(-180.0, 180.0) else 0.0,
            "hasCoordinates" to signal.hasCoordinates,
            "estimatedMinCount" to minCount,
            "estimatedMaxCount" to maxCount,
            "confidencePercent" to signal.confidencePercent.coerceIn(0L, 100L),
            "verificationStatus" to safeInitialStatus,
            "dataSource" to signal.dataSource.trim().ifBlank { "Sensor Stream" }.take(100),
            "verifiedByUid" to "",
            "verificationNotes" to signal.verificationNotes.take(300),
            "observationTimestampMillis" to obsTs,
            "isSynthetic" to (signal.sourceKind != "hardware" || signal.isSynthetic),
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

    suspend fun reviewPresenceSignal(
        orgId: String,
        signal: PresenceSignal,
        newStatus: String,
        verificationNotes: String,
    ): Result<Unit> {
        val uid = requireUserId()
        if (newStatus == "field_verified" && (signal.sourceKind == "simulated" || signal.isSynthetic)) {
            return Result.failure(
                IllegalStateException("Simulated sensor signals can NEVER be marked field_verified. Log a confirmed sighting in Rescue Registry instead.")
            )
        }
        val docRef = orgDoc(orgId).collection("presence_signals").document(signal.id)
        val updatedConfidence = when (newStatus) {
            "field_verified" -> maxOf(signal.confidencePercent, 92L)
            "outdated" -> minOf(signal.confidencePercent, 35L)
            else -> signal.confidencePercent
        }
        return try {
            docRef.update(
                mapOf(
                    "verificationStatus" to newStatus,
                    "confidencePercent" to updatedConfidence,
                    "verifiedByUid" to if (newStatus == "field_verified") uid else signal.verifiedByUid,
                    "verificationNotes" to verificationNotes.ifBlank { "Reviewed by $uid" }.take(300),
                    "sensorHealth" to if (newStatus == "outdated") "degraded" else signal.sensorHealth,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            logOrgActivity(orgId, "Responder", "presence", "Signal ${signal.sectorLabel} marked $newStatus", signal.id)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createRegistryEntry(
        orgId: String,
        entry: RescueRegistryEntry,
        explicitId: String? = null,
    ): Result<String> {
        val uid = requireUserId()
        val docId = explicitId?.takeIf { it.isNotBlank() }
            ?: "reg_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = orgDoc(orgId).collection("registry").document(docId)
        val payload = mapOf(
            "orgId" to orgId,
            "userId" to uid,
            "category" to entry.category,
            "title" to entry.title.trim().take(120),
            "locationName" to entry.locationName.trim().take(100),
            "latitude" to if (entry.hasCoordinates) entry.latitude.coerceIn(-90.0, 90.0) else 0.0,
            "longitude" to if (entry.hasCoordinates) entry.longitude.coerceIn(-180.0, 180.0) else 0.0,
            "hasCoordinates" to entry.hasCoordinates,
            "details" to entry.details.trim().take(600),
            "sensitiveContactMasked" to entry.sensitiveContactMasked.trim().take(120),
            "photoUriLocal" to if (entry.photoConsentGranted) entry.photoUriLocal.take(240) else "",
            "photoConsentGranted" to entry.photoConsentGranted,
            "rescueStatus" to entry.rescueStatus,
            "verificationStatus" to entry.verificationStatus,
            "informationSource" to entry.informationSource.trim().ifBlank { "Field Desk" }.take(100),
            "verifierUid" to if (entry.verificationStatus == "verified") uid else entry.verifierUid.take(128),
            "verificationNotes" to entry.verificationNotes.trim().take(300),
            "duplicateOfId" to entry.duplicateOfId.take(128),
            "medicalCategory" to entry.medicalCategory.take(64),
            "medicalUrgency" to entry.medicalUrgency.ifBlank { "routine" }.take(32),
            "assignedMedicalTeam" to entry.assignedMedicalTeam.take(64),
            "linkedIncidentId" to entry.linkedIncidentId.take(128),
            "isSynthetic" to entry.isSynthetic,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        return try {
            docRef.set(payload).await()
            logOrgActivity(orgId, "Registry Desk", "registry", "Logged ${entry.category}: ${entry.title}", docId)
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateRegistryEntry(
        orgId: String,
        entry: RescueRegistryEntry,
    ): Result<Unit> {
        val uid = requireUserId()
        require(entry.id.isNotBlank()) { "Registry ID must not be blank for update" }
        val docRef = orgDoc(orgId).collection("registry").document(entry.id)
        return try {
            docRef.update(
                mapOf(
                    "category" to entry.category,
                    "title" to entry.title.trim().take(120),
                    "locationName" to entry.locationName.trim().take(100),
                    "latitude" to if (entry.hasCoordinates) entry.latitude.coerceIn(-90.0, 90.0) else 0.0,
                    "longitude" to if (entry.hasCoordinates) entry.longitude.coerceIn(-180.0, 180.0) else 0.0,
                    "hasCoordinates" to entry.hasCoordinates,
                    "details" to entry.details.trim().take(600),
                    "sensitiveContactMasked" to entry.sensitiveContactMasked.trim().take(120),
                    "photoUriLocal" to if (entry.photoConsentGranted) entry.photoUriLocal.take(240) else "",
                    "photoConsentGranted" to entry.photoConsentGranted,
                    "rescueStatus" to entry.rescueStatus,
                    "verificationStatus" to entry.verificationStatus,
                    "informationSource" to entry.informationSource.trim().take(100),
                    "verifierUid" to if (entry.verificationStatus == "verified") uid else entry.verifierUid,
                    "verificationNotes" to entry.verificationNotes.trim().take(300),
                    "duplicateOfId" to entry.duplicateOfId.take(128),
                    "medicalCategory" to entry.medicalCategory.take(64),
                    "medicalUrgency" to entry.medicalUrgency.ifBlank { "routine" }.take(32),
                    "assignedMedicalTeam" to entry.assignedMedicalTeam.take(64),
                    "linkedIncidentId" to entry.linkedIncidentId.take(128),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            logOrgActivity(orgId, "Registry Desk", "registry", "Updated ${entry.title} -> ${entry.rescueStatus}/${entry.verificationStatus}", entry.id)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createSosIncident(
        orgId: String,
        incident: SosIncident,
        explicitId: String? = null,
    ): Result<String> {
        val uid = requireUserId()
        val incidentId = explicitId?.takeIf { it.isNotBlank() }
            ?: "sos_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = orgDoc(orgId).collection("sos_incidents").document(incidentId)
        val hasCoords = incident.hasLocation &&
            incident.latitude in -90.0..90.0 &&
            incident.longitude in -180.0..180.0
        val payload = mapOf(
            "incidentId" to incidentId,
            "orgId" to orgId,
            "senderUid" to uid,
            "senderName" to incident.senderName.ifBlank { "Field Responder" }.take(80),
            "teamCode" to incident.teamCode.ifBlank { "FIELD" }.take(32),
            "priority" to incident.priority,
            "description" to incident.description.trim().take(500),
            "status" to SosIncidentStatus.DELIVERED.value,
            "hasLocation" to hasCoords,
            "latitude" to if (hasCoords) incident.latitude else 0.0,
            "longitude" to if (hasCoords) incident.longitude else 0.0,
            "accuracyMeters" to if (hasCoords) incident.accuracyMeters.coerceIn(0.0, 50_000.0) else 0.0,
            "assignedTeamId" to "",
            "assignedTeamCode" to "",
            "acknowledgedByUid" to "",
            "acknowledgedAtMillis" to 0L,
            "deliveryNote" to "Saved to Firestore; awaiting Cloud Function FCM dispatch.",
            "notificationStatus" to "pending_push",
            "pushAttemptedCount" to 0L,
            "isSynthetic" to incident.isSynthetic,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        return try {
            docRef.set(payload).await()
            docRef.collection("audit_logs").document("log_${UUID.randomUUID().toString().take(8)}").set(
                mapOf(
                    "orgId" to orgId,
                    "incidentId" to incidentId,
                    "actorUid" to uid,
                    "fromStatus" to SosIncidentStatus.CREATED.value,
                    "toStatus" to SosIncidentStatus.DELIVERED.value,
                    "note" to "Incident logged to Cloud Firestore; backend FCM trigger queued",
                    "createdAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            logOrgActivity(orgId, incident.senderName.ifBlank { "Responder" }, "sos", "Raised ${incident.priority.uppercase(Locale.US)} SOS (${incident.teamCode})", incidentId)
            Result.success(incidentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun transitionSosIncident(
        orgId: String,
        incident: SosIncident,
        targetStatus: SosIncidentStatus,
        assignedTeamId: String = incident.assignedTeamId,
        assignedTeamCode: String = incident.assignedTeamCode,
        note: String = "",
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = orgDoc(orgId).collection("sos_incidents").document(incident.incidentId)
        val updates = mutableMapOf<String, Any>(
            "status" to targetStatus.value,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (targetStatus == SosIncidentStatus.ACKNOWLEDGED && incident.acknowledgedByUid.isBlank()) {
            updates["acknowledgedByUid"] = uid
            updates["acknowledgedAtMillis"] = System.currentTimeMillis()
        }
        if (assignedTeamId.isNotBlank() || assignedTeamCode.isNotBlank()) {
            updates["assignedTeamId"] = assignedTeamId.take(128)
            updates["assignedTeamCode"] = assignedTeamCode.take(32)
        }
        if (note.isNotBlank()) {
            updates["deliveryNote"] = note.take(200)
        }
        return try {
            docRef.update(updates).await()
            docRef.collection("audit_logs").document("log_${UUID.randomUUID().toString().replace("-", "").take(10)}").set(
                mapOf(
                    "orgId" to orgId,
                    "incidentId" to incident.incidentId,
                    "actorUid" to uid,
                    "fromStatus" to incident.status,
                    "toStatus" to targetStatus.value,
                    "note" to note.ifBlank { "Transitioned ${incident.status} -> ${targetStatus.value}" }.take(300),
                    "createdAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            logOrgActivity(orgId, "Command", "sos", "SOS ${incident.incidentId} -> ${targetStatus.label}", incident.incidentId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createMission(orgId: String, mission: RescueMission): Result<String> {
        val uid = requireUserId()
        val missionId = mission.missionId.ifBlank {
            "msn_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        }
        val docRef = orgDoc(orgId).collection("missions").document(missionId)
        return try {
            docRef.set(
                mapOf(
                    "missionId" to missionId,
                    "orgId" to orgId,
                    "title" to mission.title.trim().take(120),
                    "description" to mission.description.trim().take(300),
                    "linkedIncidentId" to mission.linkedIncidentId.take(128),
                    "assignedTeamId" to mission.assignedTeamId.take(128),
                    "assignedTeamCode" to mission.assignedTeamCode.take(32),
                    "priority" to mission.priority,
                    "status" to mission.status,
                    "targetLatitude" to mission.targetLatitude.coerceIn(-90.0, 90.0),
                    "targetLongitude" to mission.targetLongitude.coerceIn(-180.0, 180.0),
                    "createdByUid" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            logOrgActivity(orgId, "Command", "mission", "Dispatched mission ${mission.title} -> ${mission.assignedTeamCode}", missionId)
            Result.success(missionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateMissionStatus(orgId: String, missionId: String, status: String): Result<Unit> {
        requireUserId()
        val docRef = orgDoc(orgId).collection("missions").document(missionId)
        return try {
            docRef.update(
                mapOf(
                    "status" to status,
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

    suspend fun createBlockedRoad(orgId: String, marker: BlockedRoadMarker): Result<String> {
        val uid = requireUserId()
        val docId = "road_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = orgDoc(orgId).collection("blocked_roads").document(docId)
        return try {
            docRef.set(
                mapOf(
                    "orgId" to orgId,
                    "reportedByUid" to uid,
                    "label" to marker.label.trim().take(120),
                    "latitude" to marker.latitude.coerceIn(-90.0, 90.0),
                    "longitude" to marker.longitude.coerceIn(-180.0, 180.0),
                    "severity" to marker.severity,
                    "active" to marker.active,
                    "notes" to marker.notes.trim().take(240),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            logOrgActivity(orgId, "GIS Scout", "location", "Flagged road obstacle: ${marker.label}", docId)
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun upsertDroneTelemetry(orgId: String, drone: DroneTelemetry): Result<Unit> {
        requireUserId()
        val droneId = drone.droneId.ifBlank { "uav_${UUID.randomUUID().toString().replace("-", "").take(8)}" }
        val docRef = orgDoc(orgId).collection("drone_telemetry").document(droneId)
        return try {
            docRef.set(
                mapOf(
                    "droneId" to droneId,
                    "orgId" to orgId,
                    "callsign" to drone.callsign.trim().take(32),
                    "sourceKind" to drone.sourceKind,
                    "status" to drone.status,
                    "latitude" to drone.latitude.coerceIn(-90.0, 90.0),
                    "longitude" to drone.longitude.coerceIn(-180.0, 180.0),
                    "altitudeMeters" to drone.altitudeMeters.coerceIn(0.0, 10000.0),
                    "batteryPercent" to drone.batteryPercent.coerceIn(0L, 100L),
                    "missionId" to drone.missionId.take(128),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun logOrgActivity(
        orgId: String,
        actorName: String,
        category: String,
        summary: String,
        targetId: String = "",
    ) {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            val logId = "act_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            orgDoc(orgId).collection("activity_logs").document(logId).set(
                mapOf(
                    "orgId" to orgId,
                    "actorUid" to uid,
                    "actorName" to actorName.ifBlank { "Responder" }.take(60),
                    "category" to category,
                    "summary" to summary.take(240),
                    "targetId" to targetId.take(128),
                    "createdAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        }
    }

    suspend fun migrateLegacyPersonalDataToOrg(orgId: String): Result<Int> {
        val uid = requireUserId()
        var migrated = 0
        return try {
            val legacyRoot = db.collection("users").document(uid)
            val legacyLocations = legacyRoot.collection("rescue_locations").get().await()
            legacyLocations.documents.forEach { doc ->
                val loc = doc.toObject(RescueLocation::class.java) ?: return@forEach
                createRescueLocation(orgId, loc.copy(orgId = orgId), explicitId = "mig_${doc.id}").getOrThrow()
                migrated++
            }
            val legacyTeams = legacyRoot.collection("rescue_teams").get().await()
            legacyTeams.documents.forEach { doc ->
                val team = doc.toObject(RescueTeam::class.java) ?: return@forEach
                createRescueTeam(orgId, team.copy(orgId = orgId), explicitId = "mig_${doc.id}").getOrThrow()
                migrated++
            }
            val legacyRegistry = legacyRoot.collection("rescue_registry").get().await()
            legacyRegistry.documents.forEach { doc ->
                val entry = doc.toObject(RescueRegistryEntry::class.java) ?: return@forEach
                createRegistryEntry(orgId, entry.copy(orgId = orgId), explicitId = "mig_${doc.id}").getOrThrow()
                migrated++
            }
            Result.success(migrated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "organizations/$orgId")
            Result.failure(e)
        }
    }

    suspend fun seedDemoDatasetIfEmpty(orgId: String): Result<Unit> {
        requireUserId()
        val locationsCol = orgDoc(orgId).collection("locations")
        return try {
            val existing = locationsCol.limit(1).get().await()
            if (!existing.isEmpty) {
                return Result.success(Unit)
            }
            seedDemoDatasetForce(orgId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, locationsCol.path)
            Result.failure(e)
        }
    }

    suspend fun seedDemoDatasetForce(orgId: String): Result<Unit> {
        val uid = requireUserId()
        return try {
            val loc1 = createRescueLocation(
                orgId,
                RescueLocation(
                    orgId = orgId,
                    userId = uid,
                    name = "Joshimath Ridge Landslide Zone",
                    region = "Chamoli, Uttarakhand",
                    hazardType = "landslide",
                    severity = "critical",
                    latitude = 30.5506,
                    longitude = 79.5660,
                    radiusMeters = 750.0,
                    verificationStatus = "verified",
                    dataSource = "Synthetic Field Feed (Demo)",
                    isSynthetic = true,
                ),
                explicitId = "demo_loc_joshimath",
            ).getOrThrow()

            val loc2 = createRescueLocation(
                orgId,
                RescueLocation(
                    orgId = orgId,
                    userId = uid,
                    name = "Kutch Masonry Collapse Sector B",
                    region = "Bhuj, Gujarat",
                    hazardType = "structural_collapse",
                    severity = "high",
                    latitude = 23.2420,
                    longitude = 69.6669,
                    radiusMeters = 500.0,
                    verificationStatus = "estimated",
                    dataSource = "Synthetic LiDAR Sim (Demo)",
                    isSynthetic = true,
                ),
                explicitId = "demo_loc_kutch",
            ).getOrThrow()

            createRescueLocation(
                orgId,
                RescueLocation(
                    orgId = orgId,
                    userId = uid,
                    name = "Brahmaputra Floodplain Embankment",
                    region = "Majuli, Assam",
                    hazardType = "flood",
                    severity = "high",
                    latitude = 26.9544,
                    longitude = 94.1678,
                    radiusMeters = 1500.0,
                    verificationStatus = "verified",
                    dataSource = "Synthetic River Gauge (Demo)",
                    isSynthetic = true,
                ),
                explicitId = "demo_loc_majuli",
            ).getOrThrow()

            createRescueTeam(
                orgId,
                RescueTeam(
                    orgId = orgId,
                    userId = uid,
                    leaderUid = uid,
                    teamCode = "NDRF-01",
                    teamName = "Himalaya Urban SAR Alpha",
                    memberCount = 12L,
                    activeCount = 10L,
                    status = "deployed",
                    locationSharingOptIn = true,
                    hasValidLocation = true,
                    latitude = 30.5530,
                    longitude = 79.5695,
                    accuracyMeters = 8.0,
                    locationTimestampMillis = System.currentTimeMillis(),
                    locationUnavailableReason = "",
                    batteryPercent = 84L,
                    connectionStatus = "online",
                    isSynthetic = true,
                ),
                explicitId = "demo_team_ndrf01",
            ).getOrThrow()

            createRescueTeam(
                orgId,
                RescueTeam(
                    orgId = orgId,
                    userId = uid,
                    leaderUid = uid,
                    teamCode = "SDRF-04",
                    teamName = "Brahmaputra Swift-Water Medical",
                    memberCount = 8L,
                    activeCount = 7L,
                    status = "active",
                    locationSharingOptIn = true,
                    hasValidLocation = true,
                    latitude = 26.9560,
                    longitude = 94.1691,
                    accuracyMeters = 14.0,
                    locationTimestampMillis = System.currentTimeMillis(),
                    locationUnavailableReason = "",
                    batteryPercent = 69L,
                    connectionStatus = "low_bandwidth",
                    isSynthetic = true,
                ),
                explicitId = "demo_team_sdrf04",
            ).getOrThrow()

            createRescueTeam(
                orgId,
                RescueTeam(
                    orgId = orgId,
                    userId = uid,
                    leaderUid = uid,
                    teamCode = "MED-02",
                    teamName = "Kutch Structural Triage Unit",
                    memberCount = 6L,
                    activeCount = 4L,
                    status = "standby",
                    locationSharingOptIn = false,
                    hasValidLocation = false,
                    latitude = 0.0,
                    longitude = 0.0,
                    accuracyMeters = 0.0,
                    locationTimestampMillis = 0L,
                    locationUnavailableReason = "Location sharing disabled by unit",
                    batteryPercent = 92L,
                    connectionStatus = "offline",
                    isSynthetic = true,
                ),
                explicitId = "demo_team_med02",
            ).getOrThrow()

            createPresenceSignal(
                orgId,
                PresenceSignal(
                    orgId = orgId,
                    userId = uid,
                    locationId = loc2,
                    sectorLabel = "Collapsed Wing B2 (Void Pocket)",
                    signalType = "radar_respiration",
                    sourceKind = "simulated",
                    deviceId = "sim-esp32-uwb-02",
                    calibrationStatus = "nominal",
                    sensorHealth = "healthy",
                    latitude = 23.2425,
                    longitude = 69.6673,
                    hasCoordinates = true,
                    estimatedMinCount = 1L,
                    estimatedMaxCount = 2L,
                    confidencePercent = 78L,
                    verificationStatus = "estimated",
                    dataSource = "Simulated UWB Micro-Doppler",
                    observationTimestampMillis = System.currentTimeMillis(),
                    isSynthetic = true,
                ),
                explicitId = "demo_sig_uwb",
            ).getOrThrow()

            createPresenceSignal(
                orgId,
                PresenceSignal(
                    orgId = orgId,
                    userId = uid,
                    locationId = loc1,
                    sectorLabel = "Ridge Terrace Retaining Wall",
                    signalType = "thermal_ir",
                    sourceKind = "simulated",
                    deviceId = "sim-rpi5-thermal-03",
                    calibrationStatus = "nominal",
                    sensorHealth = "healthy",
                    latitude = 30.5510,
                    longitude = 79.5665,
                    hasCoordinates = true,
                    estimatedMinCount = 1L,
                    estimatedMaxCount = 3L,
                    confidencePercent = 64L,
                    verificationStatus = "unverified",
                    dataSource = "Simulated Thermal IR (FLIR Model)",
                    observationTimestampMillis = System.currentTimeMillis(),
                    isSynthetic = true,
                ),
                explicitId = "demo_sig_thermal",
            ).getOrThrow()

            createRegistryEntry(
                orgId,
                RescueRegistryEntry(
                    orgId = orgId,
                    userId = uid,
                    category = "missing_person",
                    title = "Rohan Sharma (Age 34, Trekker)",
                    locationName = "Joshimath Ridge Trail Marker 6",
                    latitude = 30.5508,
                    longitude = 79.5655,
                    hasCoordinates = true,
                    details = "Last contact prior to ridge subsidence. Wearing blue rain shell; carrying orange pack.",
                    sensitiveContactMasked = "NOK: +91-98***-**412",
                    rescueStatus = "in_progress",
                    verificationStatus = "under_verification",
                    informationSource = "District Emergency Desk",
                    verificationNotes = "Awaiting visual confirmation from NDRF-01 scout.",
                    isSynthetic = true,
                ),
                explicitId = "demo_reg_missing",
            ).getOrThrow()

            createRegistryEntry(
                orgId,
                RescueRegistryEntry(
                    orgId = orgId,
                    userId = uid,
                    category = "verified_survivor",
                    title = "Meera Devi & Family (3 Individuals)",
                    locationName = "Majuli Ward 3 Relief Boat",
                    latitude = 26.9548,
                    longitude = 94.1680,
                    hasCoordinates = true,
                    details = "Safely evacuated to elevated community school shelter. Stable hydration and vitals.",
                    sensitiveContactMasked = "Med ID: SUR-MJL-084",
                    rescueStatus = "resolved",
                    verificationStatus = "verified",
                    informationSource = "SDRF-04 Field Medic",
                    verificationNotes = "Verified in person at relief shelter.",
                    isSynthetic = true,
                ),
                explicitId = "demo_reg_survivor",
            ).getOrThrow()

            createRegistryEntry(
                orgId,
                RescueRegistryEntry(
                    orgId = orgId,
                    userId = uid,
                    category = "medical_request",
                    title = "Portable Oxygen & Splint Kit Request",
                    locationName = "Kutch Collapse Sector B",
                    latitude = 23.2421,
                    longitude = 69.6667,
                    hasCoordinates = true,
                    details = "Requested trauma stabilization kit and 2 portable O2 cylinders for rubble extraction.",
                    sensitiveContactMasked = "Req Ref: MED-KT-19",
                    rescueStatus = "dispatched",
                    verificationStatus = "verified",
                    informationSource = "Sector Triage Lead",
                    verificationNotes = "Dispatched via ambulance convoy.",
                    medicalCategory = "trauma",
                    medicalUrgency = "critical",
                    assignedMedicalTeam = "MED-02",
                    linkedIncidentId = "demo_sos_01",
                    isSynthetic = true,
                ),
                explicitId = "demo_reg_medical",
            ).getOrThrow()

            createSosIncident(
                orgId,
                SosIncident(
                    orgId = orgId,
                    senderUid = uid,
                    senderName = "NDRF-01 Lead",
                    teamCode = "NDRF-01",
                    priority = "urgent",
                    description = "Synthetic Demo Dispatch: Micro-Doppler respiration anomaly logged in Sector B2. Maintain acoustic silence during confirmation.",
                    hasLocation = true,
                    latitude = 30.5512,
                    longitude = 79.5675,
                    accuracyMeters = 9.0,
                    isSynthetic = true,
                ),
                explicitId = "demo_sos_01",
            ).getOrThrow()

            createMission(
                orgId,
                RescueMission(
                    missionId = "demo_msn_joshimath",
                    orgId = orgId,
                    title = "Stabilize Joshimath Culvert Corridor",
                    description = "Synthetic Demo Mission: Clear debris along Marker 6 footpath for stretcher egress.",
                    linkedIncidentId = "demo_sos_01",
                    assignedTeamId = "demo_team_ndrf01",
                    assignedTeamCode = "NDRF-01",
                    priority = "high",
                    status = "in_progress",
                    targetLatitude = 30.5506,
                    targetLongitude = 79.5660,
                    createdByUid = uid,
                )
            ).getOrThrow()

            createBlockedRoad(
                orgId,
                BlockedRoadMarker(
                    orgId = orgId,
                    reportedByUid = uid,
                    label = "NH-7 Ridge Washout (Demo)",
                    latitude = 30.5518,
                    longitude = 79.5681,
                    severity = "blocked",
                    active = true,
                    notes = "Synthetic demo obstruction marker — use alternate spur route.",
                )
            ).getOrThrow()

            upsertDroneTelemetry(
                orgId,
                DroneTelemetry(
                    droneId = "demo_uav_garuda1",
                    orgId = orgId,
                    callsign = "GARUDA-UAV-1",
                    sourceKind = "simulated",
                    status = "airborne",
                    latitude = 30.5514,
                    longitude = 79.5668,
                    altitudeMeters = 68.0,
                    batteryPercent = 76L,
                    missionId = "demo_msn_joshimath",
                )
            ).getOrThrow()

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun normalizeConnectionStatus(raw: String): String {
        return when (raw.lowercase(Locale.US)) {
            "online", "offline", "stale", "low_bandwidth" -> raw.lowercase(Locale.US)
            else -> "offline"
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

    companion object {
        private val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()
        private val secureRandom = SecureRandom()

        fun generateSecureInviteCode(prefix: String = "SRK"): String {
            val part1 = (1..4).map { INVITE_ALPHABET[secureRandom.nextInt(INVITE_ALPHABET.size)] }.joinToString("")
            val part2 = (1..4).map { INVITE_ALPHABET[secureRandom.nextInt(INVITE_ALPHABET.size)] }.joinToString("")
            return "${prefix.uppercase(Locale.US)}-$part1-$part2"
        }
    }
}
