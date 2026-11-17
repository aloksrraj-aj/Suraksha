package com.example.data.repository

import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.AuditLog
import com.example.data.model.BlockedMembership
import com.example.data.model.EnvironmentMode
import com.example.data.model.Invitation
import com.example.data.model.MembershipStatus
import com.example.data.model.MissionStatus
import com.example.data.model.OrgMember
import com.example.data.model.OrgRole
import com.example.data.model.RescueOrganization
import com.example.data.model.RescueTeam
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * Handles Organization creation, invitations, RBAC role updates, atomic member removal/blocking,
 * email-bypass blocking, and Admin-only reinstatement with immutable audit logs.
 */
class OrgMembershipStore(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
) {
    private val random = SecureRandom()

    private fun requireUid(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("Google Sign-In authentication required")

    suspend fun createOrganizationWorkspace(
        orgCode: String,
        orgName: String,
        region: String,
        displayName: String,
        callsign: String,
        photoUrl: String,
    ): Result<Triple<String, String, String>> = runCatching {
        val uid = requireUid()
        val nowMs = System.currentTimeMillis()
        val orgId = "org_${orgCode.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "_")}_${UUID.randomUUID().toString().take(6)}"
        val teamId = "team_alpha_${UUID.randomUUID().toString().take(6)}"
        val missionId = "mission_${UUID.randomUUID().toString().take(8)}"

        val orgRef = db.collection("organizations").document(orgId)
        val teamCode = "${orgCode.uppercase(Locale.US).take(6)}-A1"
        val cleanCallsign = callsign.ifBlank { "ADM-01" }

        orgRef.set(
            mapOf(
                "orgCode" to orgCode.uppercase(Locale.US).take(32),
                "orgName" to orgName.trim().take(100),
                "region" to region.trim().take(80),
                "allowMemberInvites" to false,
                "createdByUid" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()

        orgRef.collection("members").document(uid).set(
            mapOf(
                "userId" to uid,
                "orgId" to orgId,
                "displayName" to displayName.ifBlank { "Org Commander" }.take(80),
                "callsign" to cleanCallsign,
                "photoUrl" to photoUrl.take(512),
                "role" to OrgRole.ORGANIZATION_ADMIN.value,
                "teamId" to teamId,
                "status" to MembershipStatus.ACTIVE.value,
                "availability" to "available",
                "inviteCode" to "",
                "revokedByUid" to "",
                "revokedAtMillis" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()

        val batch = db.batch()
        batch.set(
            orgRef.collection("teams").document(teamId),
            mapOf(
                "orgId" to orgId,
                "teamCode" to teamCode,
                "teamName" to "Alpha Rescue Squad",
                "teamAdminUid" to uid,
                "allowMemberInvites" to false,
                "memberCount" to 1L,
                "status" to "active",
                "createdByUid" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.set(
            orgRef.collection("teams").document(teamId).collection("teamMembers").document(uid),
            mapOf(
                "userId" to uid,
                "orgId" to orgId,
                "teamId" to teamId,
                "displayName" to displayName.ifBlank { "Org Commander" }.take(80),
                "callsign" to cleanCallsign,
                "role" to OrgRole.ORGANIZATION_ADMIN.value,
                "status" to MembershipStatus.ACTIVE.value,
                "revokedByUid" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
        val missionRef = orgRef.collection("missions").document(missionId)
        batch.set(
            missionRef,
            mapOf(
                "missionId" to missionId,
                "orgId" to orgId,
                "teamId" to teamId,
                "teamCode" to teamCode,
                "title" to "${orgName.trim().take(40)} Primary SAR",
                "description" to "Shared search-and-rescue radar & responder coordination sector",
                "environmentMode" to EnvironmentMode.OPEN_AREA.value,
                "status" to MissionStatus.ACTIVE.value,
                "separationAlertEnabled" to true,
                "separationThresholdMeters" to 150.0,
                "separationDelaySeconds" to 20L,
                "centerLatitude" to 30.5506,
                "centerLongitude" to 79.5660,
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
                "displayName" to displayName.ifBlank { "Org Commander" }.take(80),
                "callsign" to cleanCallsign,
                "photoUrl" to photoUrl.take(512),
                "role" to OrgRole.ORGANIZATION_ADMIN.value,
                "status" to MembershipStatus.ACTIVE.value,
                "sharingEnabled" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.set(
            orgRef.collection("auditLogs").document("audit_org_$nowMs"),
            mapOf(
                "orgId" to orgId,
                "missionId" to missionId,
                "teamId" to teamId,
                "actorUid" to uid,
                "actorName" to displayName.ifBlank { "Org Commander" },
                "category" to "membership",
                "action" to "ORG_CREATED",
                "targetUid" to uid,
                "summary" to "Created organization $orgName ($orgCode) & primary mission",
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.commit().await()
        Triple(orgId, teamId, missionId)
    }.onFailure {
        handleFirestoreError(it, OperationType.WRITE, "organizations")
    }

    suspend fun setAllowMemberInvites(orgId: String, allow: Boolean): Result<Unit> = runCatching {
        val uid = requireUid()
        db.collection("organizations").document(orgId)
            .update(
                mapOf(
                    "allowMemberInvites" to allow,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        logAudit(orgId, "", "", uid, "Org Admin", "invite", "TOGGLE_MEMBER_INVITES", uid, "allowMemberInvites=$allow")
    }.onFailure {
        handleFirestoreError(it, OperationType.UPDATE, "organizations/$orgId")
    }

    suspend fun createInvitation(
        orgId: String,
        assignedRole: OrgRole,
        targetTeamId: String,
        targetMissionId: String,
        targetEmail: String,
        callerRole: OrgRole,
        callerName: String,
        maxUses: Long = 10L,
        expiresHours: Long = 48L,
    ): Result<Invitation> = runCatching {
        val uid = requireUid()
        val safeRole = if (assignedRole == OrgRole.SENSOR_OPERATOR) OrgRole.SENSOR_OPERATOR else OrgRole.TEAM_MEMBER
        val code = generateInviteCode()
        val expiresAt = System.currentTimeMillis() + (expiresHours.coerceIn(1L, 720L) * 3_600_000L)
        val isAdminCaller = callerRole.isAdmin()
        val status = if (isAdminCaller) "approved" else "pending_approval"
        val approvedBy = if (isAdminCaller) uid else ""

        val payload = mapOf(
            "orgId" to orgId,
            "inviteCode" to code,
            "assignedRole" to safeRole.value,
            "targetTeamId" to targetTeamId,
            "targetMissionId" to targetMissionId,
            "targetEmail" to targetEmail.trim().lowercase(Locale.US),
            "status" to status,
            "active" to isAdminCaller,
            "expiresAtMillis" to expiresAt,
            "maxUses" to maxUses.coerceIn(1L, 500L),
            "usedCount" to 0L,
            "createdByUid" to uid,
            "createdByRole" to callerRole.value,
            "approvedByUid" to approvedBy,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        db.collection("organizations").document(orgId)
            .collection("invitations").document(code)
            .set(payload)
            .await()

        logAudit(
            orgId = orgId,
            missionId = targetMissionId,
            teamId = targetTeamId,
            actorUid = uid,
            actorName = callerName,
            category = "invite",
            action = if (isAdminCaller) "CREATE_APPROVED_INVITE" else "REQUEST_MEMBER_INVITE",
            targetUid = "",
            summary = "Invitation $code ($status) for ${safeRole.value}",
        )
        Invitation(
            id = code,
            orgId = orgId,
            inviteCode = code,
            assignedRole = safeRole.value,
            targetTeamId = targetTeamId,
            targetMissionId = targetMissionId,
            targetEmail = targetEmail.trim().lowercase(Locale.US),
            status = status,
            active = isAdminCaller,
            expiresAtMillis = expiresAt,
            maxUses = maxUses.coerceIn(1L, 500L),
            usedCount = 0L,
            createdByUid = uid,
            createdByRole = callerRole.value,
            approvedByUid = approvedBy,
        )
    }.onFailure {
        handleFirestoreError(it, OperationType.CREATE, "organizations/$orgId/invitations")
    }

    suspend fun approveOrRejectInvitation(
        orgId: String,
        inviteCode: String,
        approve: Boolean,
        actorName: String,
    ): Result<Unit> = runCatching {
        val uid = requireUid()
        db.collection("organizations").document(orgId)
            .collection("invitations").document(inviteCode)
            .update(
                mapOf(
                    "status" to if (approve) "approved" else "rejected",
                    "active" to approve,
                    "approvedByUid" to if (approve) uid else "",
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        logAudit(
            orgId = orgId,
            missionId = "",
            teamId = "",
            actorUid = uid,
            actorName = actorName,
            category = "invite",
            action = if (approve) "APPROVE_INVITE" else "REJECT_INVITE",
            targetUid = "",
            summary = "Invitation $inviteCode marked ${if (approve) "approved" else "rejected"}",
        )
    }.onFailure {
        handleFirestoreError(it, OperationType.UPDATE, "organizations/$orgId/invitations/$inviteCode")
    }

    suspend fun joinOrganizationWithInvite(
        orgId: String,
        inviteCode: String,
        displayName: String,
        callsign: String,
        photoUrl: String,
    ): Result<Triple<String, String, String>> = runCatching {
        val uid = requireUid()
        val orgRef = db.collection("organizations").document(orgId)
        val inviteRef = orgRef.collection("invitations").document(inviteCode.trim())
        val memberRef = orgRef.collection("members").document(uid)
        val blockedRef = orgRef.collection("blockedMemberships").document(uid)

        var joinedRole = OrgRole.TEAM_MEMBER.value
        val result = db.runTransaction { tx ->
            val blockedSnap = tx.get(blockedRef)
            if (blockedSnap.exists()) {
                throw IllegalStateException("Access denied: your membership in this organization was revoked by an Admin.")
            }
            val invSnap = tx.get(inviteRef)
            if (!invSnap.exists()) {
                throw IllegalArgumentException("Invalid invitation code.")
            }
            val inv = invSnap.toObject(Invitation::class.java)
                ?: throw IllegalArgumentException("Malformed invitation record.")
            if (!inv.isRedeemable()) {
                throw IllegalStateException("Invitation is pending approval, expired, revoked, or exhausted.")
            }

            joinedRole = inv.assignedRole
            tx.set(
                memberRef,
                mapOf(
                    "userId" to uid,
                    "orgId" to orgId,
                    "displayName" to displayName.ifBlank { "Rescue Responder" }.take(80),
                    "callsign" to callsign.ifBlank { "RES-${uid.take(4)}" }.take(32),
                    "photoUrl" to photoUrl.take(512),
                    "role" to inv.assignedRole,
                    "teamId" to inv.targetTeamId,
                    "status" to MembershipStatus.ACTIVE.value,
                    "availability" to "available",
                    "inviteCode" to inviteCode.trim(),
                    "revokedByUid" to "",
                    "revokedAtMillis" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            tx.update(
                inviteRef,
                mapOf(
                    "usedCount" to inv.usedCount + 1L,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            Triple(orgId, inv.targetTeamId, inv.targetMissionId)
        }.await()

        val (_, teamId, missionId) = result
        if (missionId.isNotBlank()) {
            orgRef.collection("missions").document(missionId)
                .collection("missionMembers").document(uid)
                .set(
                    mapOf(
                        "userId" to uid,
                        "orgId" to orgId,
                        "missionId" to missionId,
                        "teamId" to teamId,
                        "displayName" to displayName.ifBlank { "Rescue Responder" }.take(80),
                        "callsign" to callsign.ifBlank { "RES-${uid.take(4)}" }.take(32),
                        "photoUrl" to photoUrl.take(512),
                        "role" to joinedRole,
                        "status" to MembershipStatus.ACTIVE.value,
                        "sharingEnabled" to false,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
        }
        result
    }.onFailure {
        handleFirestoreError(it, OperationType.WRITE, "organizations/$orgId/members")
    }

    suspend fun updateMemberRole(
        orgId: String,
        targetUid: String,
        newRole: OrgRole,
        actorName: String,
    ): Result<Unit> = runCatching {
        val uid = requireUid()
        db.collection("organizations").document(orgId)
            .collection("members").document(targetUid)
            .update(
                mapOf(
                    "role" to newRole.value,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        logAudit(orgId, "", "", uid, actorName, "role", "UPDATE_ROLE", targetUid, "Assigned role ${newRole.value}")
    }.onFailure {
        handleFirestoreError(it, OperationType.UPDATE, "organizations/$orgId/members/$targetUid")
    }

    /**
     * Critical Membership-Removal Rule:
     * Marks member REVOKED, writes blockedMemberships/{targetUid} (+ blockedEmails if verified email known),
     * revokes active mission membership, and records an immutable AuditLog.
     */
    suspend fun removeMemberAndBlock(
        orgId: String,
        targetMember: OrgMember,
        activeMissionId: String,
        reason: String,
        knownEmail: String = "",
        actorName: String,
    ): Result<Unit> = runCatching {
        val adminUid = requireUid()
        val nowMs = System.currentTimeMillis()
        val orgRef = db.collection("organizations").document(orgId)
        val emailKey = normalizeEmailKey(knownEmail)
        val batch = db.batch()

        batch.update(
            orgRef.collection("members").document(targetMember.userId),
            mapOf(
                "status" to MembershipStatus.REVOKED.value,
                "revokedByUid" to adminUid,
                "revokedAtMillis" to nowMs,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.set(
            orgRef.collection("blockedMemberships").document(targetMember.userId),
            mapOf(
                "userId" to targetMember.userId,
                "orgId" to orgId,
                "teamId" to targetMember.teamId,
                "missionId" to activeMissionId,
                "blockedByUid" to adminUid,
                "reason" to reason.ifBlank { "Removed by Admin" }.take(300),
                "blockedEmailNormalized" to emailKey,
                "blockedAtMillis" to nowMs,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        )
        if (emailKey.isNotBlank()) {
            batch.set(
                orgRef.collection("blockedEmails").document(emailKey),
                mapOf(
                    "orgId" to orgId,
                    "emailKey" to emailKey,
                    "blockedUid" to targetMember.userId,
                    "blockedByUid" to adminUid,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            )
        }
        if (activeMissionId.isNotBlank()) {
            batch.set(
                orgRef.collection("missions").document(activeMissionId)
                    .collection("blockedMemberships").document(targetMember.userId),
                mapOf(
                    "userId" to targetMember.userId,
                    "orgId" to orgId,
                    "teamId" to targetMember.teamId,
                    "missionId" to activeMissionId,
                    "blockedByUid" to adminUid,
                    "reason" to reason.ifBlank { "Removed by Admin" }.take(300),
                    "blockedEmailNormalized" to emailKey,
                    "blockedAtMillis" to nowMs,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            )
        }
        batch.set(
            orgRef.collection("auditLogs").document("audit_remove_${targetMember.userId}_$nowMs"),
            mapOf(
                "orgId" to orgId,
                "missionId" to activeMissionId,
                "teamId" to targetMember.teamId,
                "actorUid" to adminUid,
                "actorName" to actorName,
                "category" to "removal",
                "action" to "REMOVE_AND_BLOCK_MEMBER",
                "targetUid" to targetMember.userId,
                "summary" to "Removed & blocked ${targetMember.displayName}: ${reason.ifBlank { "Admin decision" }}",
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.commit().await()
        Unit
    }.onFailure {
        handleFirestoreError(it, OperationType.WRITE, "organizations/$orgId/blockedMemberships/${targetMember.userId}")
    }

    /** Only authorized Organization Admin can reinstate a blocked member. */
    suspend fun reinstateBlockedMember(
        orgId: String,
        blocked: BlockedMembership,
        actorName: String,
    ): Result<Unit> = runCatching {
        val adminUid = requireUid()
        val nowMs = System.currentTimeMillis()
        val orgRef = db.collection("organizations").document(orgId)
        val batch = db.batch()
        batch.delete(orgRef.collection("blockedMemberships").document(blocked.userId))
        if (blocked.blockedEmailNormalized.isNotBlank()) {
            batch.delete(orgRef.collection("blockedEmails").document(blocked.blockedEmailNormalized))
        }
        if (blocked.missionId.isNotBlank()) {
            batch.delete(
                orgRef.collection("missions").document(blocked.missionId)
                    .collection("blockedMemberships").document(blocked.userId)
            )
        }
        batch.update(
            orgRef.collection("members").document(blocked.userId),
            mapOf(
                "status" to MembershipStatus.ACTIVE.value,
                "revokedByUid" to "",
                "revokedAtMillis" to 0L,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.set(
            orgRef.collection("auditLogs").document("audit_reinstate_${blocked.userId}_$nowMs"),
            mapOf(
                "orgId" to orgId,
                "missionId" to blocked.missionId,
                "teamId" to blocked.teamId,
                "actorUid" to adminUid,
                "actorName" to actorName,
                "category" to "reinstatement",
                "action" to "REINSTATE_MEMBER",
                "targetUid" to blocked.userId,
                "summary" to "Admin $actorName reinstated user ${blocked.userId}",
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.commit().await()
        Unit
    }.onFailure {
        handleFirestoreError(it, OperationType.WRITE, "organizations/$orgId/blockedMemberships/${blocked.userId}")
    }

    suspend fun createTeam(orgId: String, teamCode: String, teamName: String, actorName: String): Result<String> = runCatching {
        val uid = requireUid()
        val teamId = "team_${UUID.randomUUID().toString().take(8)}"
        db.collection("organizations").document(orgId)
            .collection("teams").document(teamId)
            .set(
                mapOf(
                    "orgId" to orgId,
                    "teamCode" to teamCode.uppercase(Locale.US).take(32),
                    "teamName" to teamName.trim().take(80),
                    "teamAdminUid" to uid,
                    "allowMemberInvites" to false,
                    "memberCount" to 1L,
                    "status" to "active",
                    "createdByUid" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        logAudit(orgId, "", teamId, uid, actorName, "membership", "CREATE_TEAM", uid, "Created team $teamName ($teamCode)")
        teamId
    }.onFailure {
        handleFirestoreError(it, OperationType.CREATE, "organizations/$orgId/teams")
    }

    suspend fun logAudit(
        orgId: String,
        missionId: String,
        teamId: String,
        actorUid: String,
        actorName: String,
        category: String,
        action: String,
        targetUid: String,
        summary: String,
    ) {
        runCatching {
            val id = "audit_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            db.collection("organizations").document(orgId)
                .collection("auditLogs").document(id)
                .set(
                    mapOf(
                        "orgId" to orgId,
                        "missionId" to missionId,
                        "teamId" to teamId,
                        "actorUid" to actorUid,
                        "actorName" to actorName.take(80),
                        "category" to category,
                        "action" to action.take(80),
                        "targetUid" to targetUid.take(128),
                        "summary" to summary.take(300),
                        "createdAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
        }
    }

    private fun generateInviteCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(10) {
            repeat(10) { append(chars[random.nextInt(chars.length)]) }
        }
    }

    companion object {
        fun normalizeEmailKey(email: String): String {
            val clean = email.trim().lowercase(Locale.US)
            return if (clean.isEmpty()) "" else clean.replace(Regex("[^a-z0-9]"), "_")
        }
    }
}
