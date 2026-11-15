package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurakshaRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createAndObserveRescueLocation_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = SurakshaRepository(firestore, auth)

        val locId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createRescueLocation(
                RescueLocation(
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
                )
            ).getOrThrow()
        }

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeRescueLocations(uid).first { list -> list.any { it.id == locId } }
        }
        assertTrue(observed.any { it.id == locId && it.name == "Test Sector Ladakh" })
    }

    @Test
    fun createTeamPresenceAndRegistry_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = SurakshaRepository(firestore, auth)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureUserProfile("Cmdr. Test", "NDRF-99").getOrThrow()
            val teamId = repository.createRescueTeam(
                RescueTeam(
                    userId = uid,
                    teamCode = "NDRF-99",
                    teamName = "Unit 99",
                    memberCount = 5L,
                    activeCount = 4L,
                    status = "active",
                    locationSharingOptIn = true,
                    latitude = 30.55,
                    longitude = 79.56,
                    batteryPercent = 88L,
                    connectionStatus = "online",
                    isSynthetic = true,
                )
            ).getOrThrow()
            assertTrue(teamId.isNotBlank())

            val sigId = repository.createPresenceSignal(
                PresenceSignal(
                    userId = uid,
                    locationId = "loc_test",
                    sectorLabel = "Rubble Pocket A",
                    signalType = "thermal_ir",
                    estimatedMinCount = 1L,
                    estimatedMaxCount = 2L,
                    confidencePercent = 74L,
                    verificationStatus = "estimated",
                    dataSource = "Simulated FLIR",
                    isSynthetic = true,
                )
            ).getOrThrow()
            repository.updatePresenceSignalVerification(sigId, "verified", 92L, "Field Team Visual").getOrThrow()

            val regId = repository.createRegistryEntry(
                RescueRegistryEntry(
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
                )
            ).getOrThrow()
            assertTrue(regId.isNotBlank())
        }
    }

    @Test
    fun observeRescueLocations_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = SurakshaRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createRescueLocation(
                RescueLocation(
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
                )
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = SurakshaRepository(firestore, auth)
        val exception = runCatching {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeRescueLocations(aliceUid).first()
            }
        }.exceptionOrNull()

        val firestoreException = checkNotNull(exception?.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $exception"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
    }

    @Test
    fun observeRescueLocations_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = SurakshaRepository(firestore, auth)

        val exception = runCatching {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeRescueLocations("any_uid").first()
            }
        }.exceptionOrNull()

        val firestoreException = checkNotNull(exception?.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $exception"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_suraksha@test.com"
        const val BOB_EMAIL = "bob_suraksha@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
