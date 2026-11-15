package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.OrgRole
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.example.data.model.SosIncident
import com.example.data.model.SosIncidentStatus
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurakshaRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun workspaceInitAndOrgFlow_createInviteJoinAndRoleUpdate_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = SurakshaRepository(firestore, auth)

        val orgId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.ensureWorkspaceInitialized("Cmdr. Alice", "NDRF-01").getOrThrow()
            val createdOrgId = aliceRepo.createOrganization(
                orgName = "Himalaya Relief Command",
                orgCode = "HRC-01",
                region = "Uttarakhand & Ladakh",
                creatorDisplayName = "Cmdr. Alice",
                creatorCallsign = "NDRF-01",
            ).getOrThrow()
            aliceRepo.createOrgInvite(createdOrgId, "JOIN2026", OrgRole.OPERATOR.value).getOrThrow()
            createdOrgId
        }

        val bobUid = signInTestUser(BOB_EMAIL)
        val bobRepo = SurakshaRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.ensureWorkspaceInitialized("Responder Bob", "OPS-02").getOrThrow()
            bobRepo.joinOrganizationWithInvite(orgId, "JOIN2026", "Responder Bob", "OPS-02").getOrThrow()
        }

        // Alice (org_admin) promotes Bob to team_leader
        signInTestUser(ALICE_EMAIL)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.updateMemberRole(orgId, bobUid, OrgRole.TEAM_LEADER, "team_alpha").getOrThrow()
        }

        val members = withTimeout(FLOW_TIMEOUT_MS) {
            aliceRepo.observeOrgMembers(orgId).first { list -> list.size >= 2 }
        }
        assertTrue(members.any { it.userId == aliceUid && it.role == OrgRole.ORG_ADMIN.value })
        assertTrue(members.any { it.userId == bobUid && it.role == OrgRole.TEAM_LEADER.value })
    }

    @Test
    fun rescueLocationTeamPresenceAndRegistry_authenticatedMember_succeedsAndEnforcesSimulatedInvariant() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = SurakshaRepository(firestore, auth)

        val orgId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureWorkspaceInitialized("Cmdr. Alice", "NDRF-01").getOrThrow()
        }

        val locId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createRescueLocation(
                orgId = orgId,
                location = RescueLocation(
                    orgId = orgId,
                    userId = uid,
                    name = "Test Sector Ladakh",
                    region = "Leh, Ladakh",
                    hazardType = "landslide",
                    severity = "high",
                    latitude = 34.1526,
                    longitude = 77.5771,
                    verificationStatus = "estimated",
                    dataSource = "Simulated Field Feed",
                    isSynthetic = true,
                ),
            ).getOrThrow()
        }

        val observedLocs = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeRescueLocations(orgId).first { list -> list.any { it.id == locId } }
        }
        assertTrue(observedLocs.any { it.id == locId && it.name == "Test Sector Ladakh" })

        val teamId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createRescueTeam(
                orgId = orgId,
                team = RescueTeam(
                    orgId = orgId,
                    userId = uid,
                    leaderUid = uid,
                    teamCode = "NDRF-99",
                    teamName = "Unit 99",
                    memberCount = 5L,
                    activeCount = 4L,
                    status = "active",
                    locationSharingOptIn = true,
                    hasValidLocation = true,
                    latitude = 30.55,
                    longitude = 79.56,
                    accuracyMeters = 12.0,
                    locationTimestampMillis = System.currentTimeMillis(),
                    locationUnavailableReason = "",
                    batteryPercent = 88L,
                    connectionStatus = "online",
                    isSynthetic = true,
                ),
            ).getOrThrow()
        }
        assertTrue(teamId.isNotBlank())

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateTeamLocation(
                orgId = orgId,
                teamId = teamId,
                optIn = false,
                hasValidLocation = false,
                latitude = 0.0,
                longitude = 0.0,
                accuracyMeters = 0.0,
                timestampMillis = 0L,
                unavailableReason = "User opted out",
                batteryPercent = 82L,
                connectionStatus = "online",
            ).getOrThrow()
        }

        val sigId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createPresenceSignal(
                orgId = orgId,
                signal = PresenceSignal(
                    orgId = orgId,
                    userId = uid,
                    locationId = locId,
                    sectorLabel = "Rubble Pocket A",
                    signalType = "thermal_ir",
                    sourceKind = "simulated",
                    deviceId = "sim-thermal-01",
                    calibrationStatus = "nominal",
                    sensorHealth = "healthy",
                    estimatedMinCount = 1L,
                    estimatedMaxCount = 2L,
                    confidencePercent = 74L,
                    verificationStatus = "estimated",
                    dataSource = "Simulated FLIR",
                    observationTimestampMillis = System.currentTimeMillis(),
                    isSynthetic = true,
                ),
            ).getOrThrow()
        }

        val simSignals = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observePresenceSignals(orgId).first { list -> list.any { it.id == sigId } }
        }
        val simSignal = simSignals.first { it.id == sigId }

        // Simulated signal can NEVER be marked field_verified
        val verifyAttempt = repository.reviewPresenceSignal(orgId, simSignal, "field_verified", "Attempt")
        assertTrue(verifyAttempt.isFailure)

        // Marking simulated signal outdated succeeds
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.reviewPresenceSignal(orgId, simSignal, "outdated", "Stale simulated sweep").getOrThrow()
        }

        val regId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createRegistryEntry(
                orgId = orgId,
                entry = RescueRegistryEntry(
                    orgId = orgId,
                    userId = uid,
                    category = "missing_person",
                    title = "Missing Individual Test",
                    locationName = "Sector A",
                    details = "Reported near ridge",
                    sensitiveContactMasked = "+91-98***-**001",
                    rescueStatus = "open",
                    verificationStatus = "unverified",
                    informationSource = "Hotline",
                    verificationNotes = "Queued",
                    isSynthetic = true,
                ),
            ).getOrThrow()
        }
        assertTrue(regId.isNotBlank())
    }

    @Test
    fun sosIncidentWorkflow_createTransitionAuditAndClosedLock_enforcedByRules() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = SurakshaRepository(firestore, auth)

        val orgId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureWorkspaceInitialized("Cmdr. Alice", "NDRF-01").getOrThrow()
        }

        val incidentId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createSosIncident(
                orgId = orgId,
                incident = SosIncident(
                    orgId = orgId,
                    senderUid = uid,
                    senderName = "Cmdr. Alice",
                    teamCode = "NDRF-01",
                    priority = "sos",
                    description = "Roof collapse near Sector C",
                    hasLocation = false,
                    isSynthetic = true,
                ),
            ).getOrThrow()
        }

        val delivered = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSosIncidents(orgId).first { list -> list.any { it.incidentId == incidentId } }
        }.first { it.incidentId == incidentId }
        assertEquals(SosIncidentStatus.DELIVERED.value, delivered.status)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.transitionSosIncident(
                orgId = orgId,
                incident = delivered,
                targetStatus = SosIncidentStatus.ACKNOWLEDGED,
                note = "Control room ack",
            ).getOrThrow()
        }

        val acked = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSosIncidents(orgId).first { list ->
                list.any { it.incidentId == incidentId && it.status == SosIncidentStatus.ACKNOWLEDGED.value }
            }
        }.first { it.incidentId == incidentId }

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.transitionSosIncident(
                orgId = orgId,
                incident = acked,
                targetStatus = SosIncidentStatus.CLOSED,
                note = "Operation closed cleanly",
            ).getOrThrow()
        }

        val closed = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSosIncidents(orgId).first { list ->
                list.any { it.incidentId == incidentId && it.status == SosIncidentStatus.CLOSED.value }
            }
        }.first { it.incidentId == incidentId }

        val logs = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSosAuditLogs(orgId, incidentId).first { it.size >= 3 }
        }
        assertTrue(logs.any { it.toStatus == SosIncidentStatus.CLOSED.value })

        // Terminal closed state must reject any further updates under Firestore security rules
        val postCloseAttempt = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.transitionSosIncident(
                orgId = orgId,
                incident = closed,
                targetStatus = SosIncidentStatus.RESPONDING,
                note = "Illegal reopen",
            )
        }
        assertTrue(postCloseAttempt.isFailure)
        val fsErr = postCloseAttempt.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull(fsErr)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    @Test
    fun crossOrgAndUnauthenticatedAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = SurakshaRepository(firestore, auth)
        val aliceOrgId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.ensureWorkspaceInitialized("Alice", "NDRF-01").getOrThrow()
            val org = aliceRepo.createOrganization(
                orgName = "Alice Private Org",
                orgCode = "ALICE-01",
                region = "Assam",
                creatorDisplayName = "Alice",
                creatorCallsign = "NDRF-01",
            ).getOrThrow()
            aliceRepo.createRescueLocation(
                orgId = org,
                location = RescueLocation(
                    orgId = org,
                    userId = aliceUid,
                    name = "Alice Private Zone",
                    region = "Assam",
                    hazardType = "flood",
                    severity = "high",
                    latitude = 26.95,
                    longitude = 94.16,
                    verificationStatus = "verified",
                    dataSource = "Sim",
                    isSynthetic = true,
                ),
            ).getOrThrow()
            org
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = SurakshaRepository(firestore, auth)
        val crossOrgError = runCatching {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeRescueLocations(aliceOrgId).first()
            }
        }.exceptionOrNull()

        val crossOrgFsErr = checkNotNull(crossOrgError?.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $crossOrgError"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, crossOrgFsErr.code)

        auth.signOut()
        val unauthError = runCatching {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeRescueLocations(aliceOrgId).first()
            }
        }.exceptionOrNull()

        val unauthFsErr = checkNotNull(unauthError?.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $unauthError"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, unauthFsErr.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_suraksha_v2@test.com"
        const val BOB_EMAIL = "bob_suraksha_v2@test.com"
        const val DEFAULT_TIMEOUT_MS = 6000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
