package com.example.data.ai

import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueTeam
import com.example.data.model.SosIncident
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiResponseValidationTest {

    @Test
    fun parseAiTriageJson_validPayload_forcesHumanApprovalAndPopulatesLists() {
        val raw = """
            {
              "executiveSummary": "1 active SOS awaiting acknowledgement in Sector A; thermal estimates are synthetic only.",
              "unacknowledgedSosSummary": ["SOS-101: Roof collapse reported without GPS fix"],
              "prioritizedSectors": ["Joshimath Ridge Landslide Zone (verified)"],
              "missingOrStaleDisclosures": ["Team NDRF-02 has opted out of live GPS sharing."],
              "recommendedActions": ["Confirm team readiness and request manual GPS ping."]
            }
        """.trimIndent()

        val result = SurakshaAiRepository.parseAiTriageJson(
            rawJson = raw,
            dataSourcesUsed = listOf("Firestore SOS Incidents (1)"),
        )
        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        assertTrue(report.requiresHumanApproval)
        assertTrue(report.safetyDisclaimer.contains("Human approval is mandatory"))
        assertEquals(1, report.unacknowledgedSosSummary.size)
        assertEquals(1, report.missingOrStaleDisclosures.size)
        assertEquals(listOf("Firestore SOS Incidents (1)"), report.dataSourcesUsed)
    }

    @Test
    fun parseAiTriageJson_missingSummaryOrMalformedJson_returnsFailure() {
        val missingSummary = """{"prioritizedSectors": ["Sector 1"]}"""
        assertTrue(SurakshaAiRepository.parseAiTriageJson(missingSummary, emptyList()).isFailure)
        assertTrue(SurakshaAiRepository.parseAiTriageJson("not json", emptyList()).isFailure)
    }

    @Test
    fun buildOperationalContextPrompt_groundsInRealAndSimulatedDisclosures() {
        val prompt = SurakshaAiRepository.buildOperationalContextPrompt(
            locations = listOf(
                RescueLocation(
                    name = "Joshimath Ridge",
                    region = "Uttarakhand",
                    hazardType = "landslide",
                    severity = "critical",
                    verificationStatus = "verified",
                    dataSource = "Field Report",
                )
            ),
            signals = listOf(
                PresenceSignal(
                    sectorLabel = "Rubble Wing B",
                    sourceKind = "simulated",
                    signalType = "thermal_ir",
                    estimatedMinCount = 1,
                    estimatedMaxCount = 2,
                    confidencePercent = 72,
                    verificationStatus = "estimated",
                )
            ),
            teams = listOf(
                RescueTeam(
                    teamCode = "NDRF-04",
                    teamName = "Alpha",
                    hasValidLocation = false,
                    locationUnavailableReason = "Permission denied",
                )
            ),
            registry = emptyList(),
            incidents = listOf(
                SosIncident(
                    incidentId = "sos_99",
                    priority = "sos",
                    status = "delivered",
                    teamCode = "NDRF-04",
                    hasLocation = false,
                    description = "Blocked tunnel",
                )
            ),
            isDemoMode = true,
        )

        assertTrue(prompt.contains("DEMO_MODE_ACTIVE: true"))
        assertTrue(prompt.contains("NO_GPS"))
        assertTrue(prompt.contains("UNAVAILABLE(Permission denied)"))
        assertTrue(prompt.contains("kind=simulated"))
    }
}
