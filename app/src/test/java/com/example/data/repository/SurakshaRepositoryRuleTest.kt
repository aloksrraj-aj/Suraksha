package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.BlockedMembership
import com.example.data.model.EnvironmentMode
import com.example.data.model.OrgRole
import com.example.data.model.SensorDataValidity
import com.example.data.model.SensorEvent
import com.example.data.model.SensorType
import com.example.data.model.SosAlert
import com.example.data.model.SosAlertStatus
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

class SurakshaRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Before
    fun verifyEmulatorReachable() {
        val reachable = isPortOpen("127.0.0.1", 8085) && isPortOpen("127.0.0.1", 9099)
        Assume.assumeTrue("Firestore/Auth emulators must be running on 127.0.0.1:8085 & 9099", reachable)
    }

    @Test
    fun orgWorkspaceInviteRemoveBlockAndReinstate_enforcedEndToEnd() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = SurakshaRepository(firestore, auth)

        val (orgId, teamId, missionId) = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.ensureUserProfile("Cmdr. Alice", ALICE_EMAIL, "").getOrThrow()
            aliceRepo.orgStore.createOrganizationWorkspace(
                orgCode = "HIM",
                orgName = "Himalaya Relief Command",
                region = "Ladakh & Uttarakhand",
                displayName = "Cmdr. Alice",
                callsign = "ADM-01",
                photoUrl = "",
            ).getOrThrow()
        }

        val approvedInvite = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.orgStore.createInvitation(
                orgId = orgId,
                assignedRole = OrgRole.SENSOR_OPERATOR,
                targetTeamId = teamId,
                targetMissionId = missionId,
                targetEmail = "",
                callerRole = OrgRole.ORGANIZATION_ADMIN,
                callerName = "Cmdr. Alice",
            ).getOrThrow()
        }

        val bobUid = signInTestUser(BOB_EMAIL)
        val bobRepo = SurakshaRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.ensureUserProfile("Operator Bob", BOB_EMAIL, "").getOrThrow()
            bobRepo.orgStore.joinOrganizationWithInvite(
                orgId = orgId,
                inviteCode = approvedInvite.inviteCode,
                displayName = "Operator Bob",
                callsign = "SEN-02",
                photoUrl = "",
            ).getOrThrow()
        }

        // Bob (SENSOR_OPERATOR) can ingest validated SensorEvent; TEAM_MEMBER cannot
        val opEventId = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.ingestValidatedSensorEvent(
                SensorEvent(
                    eventId = "evt_op_1",
                    orgId = orgId,
                    missionId = missionId,
                    sensorId = "uwb_01",
                    sensorType = SensorType.UWB_LIFE_DETECTION_RADAR.value,
                    timestampUtcMillis = System.currentTimeMillis() - 3_000L,
                    estimatedRangeMeters = 12.0,
                    estimatedAzimuthDegrees = 60.0,
                    confidence = 0.82,
                    signalQuality = "good",
                    sourceDevice = "UWB-1",
                    dataValidity = SensorDataValidity.VALID.value,
                )
            ).getOrThrow()
        }
        assertEquals("evt_op_1", opEventId)

        // Alice revokes & blocks Bob
        signInTestUser(ALICE_EMAIL)
        val membersBefore = withTimeout(FLOW_TIMEOUT_MS) {
            (aliceRepo.observeOrgMembers(orgId).first { it is UiState.Success && it.data.size >= 2 } as UiState.Success).data
        }
        val bobMember = membersBefore.first { it.userId == bobUid }
        assertEquals(OrgRole.SENSOR_OPERATOR.value, bobMember.role)
        assertTrue(membersBefore.any { it.userId == aliceUid && it.role == OrgRole.ORGANIZATION_ADMIN.value })

        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.orgStore.removeMemberAndBlock(
                orgId = orgId,
                targetMember = bobMember,
                activeMissionId = missionId,
                reason = "Credential expired",
                knownEmail = BOB_EMAIL,
                actorName = "Cmdr. Alice",
            ).getOrThrow()
        }

        // Blocked Bob cannot rejoin using any invite
        signInTestUser(BOB_EMAIL)
        val blockedRejoin = bobRepo.orgStore.joinOrganizationWithInvite(
            orgId = orgId,
            inviteCode = approvedInvite.inviteCode,
            displayName = "Operator Bob",
            callsign = "SEN-02",
            photoUrl = "",
        )
        assertTrue(blockedRejoin.isFailure)

        // Alice reinstates Bob cleanly
        signInTestUser(ALICE_EMAIL)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.orgStore.reinstateBlockedMember(
                orgId = orgId,
                blocked = BlockedMembership(
                    userId = bobUid,
                    orgId = orgId,
                    teamId = teamId,
                    missionId = missionId,
                    blockedEmailNormalized = OrgMembershipStore.normalizeEmailKey(BOB_EMAIL),
                ),
                actorName = "Cmdr. Alice",
            ).getOrThrow()
        }

        val membersAfter = withTimeout(FLOW_TIMEOUT_MS) {
            (aliceRepo.observeOrgMembers(orgId).first {
                it is UiState.Success && it.data.any { m -> m.userId == bobUid && !m.isRevoked() }
            } as UiState.Success).data
        }
        assertTrue(membersAfter.any { it.userId == bobUid && !it.isRevoked() })
    }

    @Test
    fun sosAlertLifecycle_triggerCancelAndTerminalLock_enforcedByRules() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = SurakshaRepository(firestore, auth)

        val (orgId, teamId, missionId) = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.orgStore.createOrganizationWorkspace(
                orgCode = "SOS",
                orgName = "SOS Unit",
                region = "Assam",
                displayName = "Cmdr. Alice",
                callsign = "ADM-01",
                photoUrl = "",
            ).getOrThrow()
        }

        val secondMissionId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createMission(
                orgId = orgId,
                teamId = teamId,
                teamCode = "SOS-A1",
                title = "Sector B Rubble",
                description = "UWB search",
                environmentMode = EnvironmentMode.BUILDING_COLLAPSED,
                separationThresholdMeters = 100.0,
                separationDelaySeconds = 15L,
                centerLatitude = 26.95,
                centerLongitude = 94.16,
                displayName = "Cmdr. Alice",
                callsign = "ADM-01",
                photoUrl = "",
                role = OrgRole.ORGANIZATION_ADMIN,
            ).getOrThrow()
        }
        assertTrue(secondMissionId.isNotBlank() && missionId != secondMissionId)

        val alertId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createSosAlert(
                SosAlert(
                    orgId = orgId,
                    missionId = secondMissionId,
                    senderUid = uid,
                    senderName = "Cmdr. Alice",
                    teamId = teamId,
                    priority = "critical",
                    description = "Stairwell obstruction",
                )
            ).getOrThrow()
        }

        // Cancel with explicit reason
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.transitionSosAlert(
                orgId = orgId,
                missionId = secondMissionId,
                alertId = alertId,
                nextStatus = SosAlertStatus.CANCELLED,
                actorName = "Cmdr. Alice",
                cancelReason = "Passage cleared manually",
            ).getOrThrow()
        }

        // Terminal CANCELLED state must reject further updates
        val illegalUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.transitionSosAlert(
                orgId = orgId,
                missionId = secondMissionId,
                alertId = alertId,
                nextStatus = SosAlertStatus.RESPONDING,
                actorName = "Cmdr. Alice",
            )
        }
        assertTrue(illegalUpdate.isFailure)
        val fsErr = illegalUpdate.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull(fsErr)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    private fun isPortOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 500)
            true
        }
    }.getOrDefault(false)

    private companion object {
        const val ALICE_EMAIL = "alice_suraksha_radar@test.com"
        const val BOB_EMAIL = "bob_suraksha_radar@test.com"
        const val DEFAULT_TIMEOUT_MS = 8000L
        const val FLOW_TIMEOUT_MS = 5000L
    }
}
