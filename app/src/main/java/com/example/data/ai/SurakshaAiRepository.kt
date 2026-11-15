package com.example.data.ai

import android.util.Log
import com.example.data.model.AiTriageReport
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.example.data.model.SosIncident
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class SurakshaAiRepository {

    private val triageSchema = Schema.obj(
        properties = mapOf(
            "executiveSummary" to Schema.string(
                description = "Concise humanitarian disaster situation summary strictly derived from provided observations."
            ),
            "unacknowledgedSosSummary" to Schema.array(
                items = Schema.string(),
                description = "List of active or unacknowledged SOS incidents requiring responder action."
            ),
            "prioritizedSectors" to Schema.array(
                items = Schema.string(),
                description = "Top rescue sectors ordered by severity and verification status."
            ),
            "missingOrStaleDisclosures" to Schema.array(
                items = Schema.string(),
                description = "Explicit disclosure of missing GPS coordinates, outdated sensor signals (>120s), or simulated-only data."
            ),
            "recommendedActions" to Schema.array(
                items = Schema.string(),
                description = "Advisory next coordination steps requiring human commander approval."
            ),
        )
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = triageSchema
            }
        )
    }

    private val chatModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash"
        )
    }

    suspend fun generateSituationTriage(
        locations: List<RescueLocation>,
        signals: List<PresenceSignal>,
        teams: List<RescueTeam>,
        registry: List<RescueRegistryEntry>,
        incidents: List<SosIncident>,
        isDemoMode: Boolean,
    ): Result<AiTriageReport> {
        val sourcesUsed = buildList {
            if (incidents.isNotEmpty()) add("Firestore SOS Incidents (${incidents.size})")
            if (locations.isNotEmpty()) add("Disaster Sites (${locations.size})")
            if (signals.isNotEmpty()) add("Presence Signals (${signals.size})")
            if (teams.isNotEmpty()) add("Rescue Teams (${teams.size})")
            if (registry.isNotEmpty()) add("Rescue Registry (${registry.size})")
            if (isEmpty()) add("No authorized org records available")
        }

        val contextPrompt = buildOperationalContextPrompt(
            locations = locations,
            signals = signals,
            teams = teams,
            registry = registry,
            incidents = incidents,
            isDemoMode = isDemoMode,
        )

        return try {
            val response = withTimeout(60_000L) {
                model.generateContent(contextPrompt)
            }
            val jsonText = response.text ?: return Result.failure(
                IllegalStateException("AI model returned an empty triage response.")
            )
            parseAiTriageJson(jsonText, sourcesUsed)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI triage timed out", e)
            Result.failure(Exception("AI triage timed out after 60 seconds. Check network and retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI triage generation failed", e)
            Result.failure(e)
        }
    }

    suspend fun askOperationalQuestion(
        question: String,
        locations: List<RescueLocation>,
        signals: List<PresenceSignal>,
        teams: List<RescueTeam>,
        registry: List<RescueRegistryEntry>,
        incidents: List<SosIncident>,
        isDemoMode: Boolean,
    ): Result<String> {
        val trimmed = question.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a coordination question first."))
        }
        val context = buildOperationalContextPrompt(locations, signals, teams, registry, incidents, isDemoMode)
        val fullPrompt = """
            $context
            
            RESPONDER QUESTION:
            $trimmed
            
            ANSWER INSTRUCTIONS:
            - Answer strictly using the authorized operational data above.
            - Never invent counts, coordinates, team locations, or survivor statuses.
            - Explicitly disclose any missing GPS, simulated-only signals, or outdated/unverified data.
            - End with: "Advisory Only — Human commander approval required before dispatch."
        """.trimIndent()

        return try {
            val response = withTimeout(60_000L) {
                chatModel.generateContent(fullPrompt)
            }
            val text = response.text?.trim()
            if (text.isNullOrBlank()) {
                Result.failure(IllegalStateException("AI returned an empty answer."))
            } else {
                Result.success(text)
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("AI assistant timed out after 60 seconds."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI Q&A failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "SurakshaAiRepo"

        fun buildOperationalContextPrompt(
            locations: List<RescueLocation>,
            signals: List<PresenceSignal>,
            teams: List<RescueTeam>,
            registry: List<RescueRegistryEntry>,
            incidents: List<SosIncident>,
            isDemoMode: Boolean,
        ): String = buildString {
            appendLine("You are a humanitarian disaster-rescue coordination analyst for PROJECT SURAKSHA.")
            appendLine("Strict accuracy & humanitarian rules:")
            appendLine("1. NEVER fabricate GPS coordinates, team statuses, timestamps, or sensor counts.")
            appendLine("2. NEVER present simulated or estimated presence signals as confirmed survivors.")
            appendLine("3. Explicitly distinguish simulated vs hardware, and verified vs estimated vs unverified vs outdated.")
            appendLine("4. Explicitly mention missing location data or unacknowledged SOS alerts.")
            appendLine("5. Never profile or classify any individual negatively.")
            appendLine()
            appendLine("DEMO_MODE_ACTIVE: $isDemoMode")
            appendLine("SOS_INCIDENTS (${incidents.size}):")
            incidents.take(8).forEach {
                val locLabel = if (it.hasLocation) "${it.latitude},${it.longitude}" else "NO_GPS"
                appendLine("- [${it.incidentId}] ${it.priority} | status=${it.status} | team=${it.teamCode} | loc=$locLabel | desc=${it.description}")
            }
            appendLine("DISASTER_LOCATIONS (${locations.size}):")
            locations.take(8).forEach {
                appendLine("- ${it.name} (${it.region}) | hazard=${it.hazardType} | severity=${it.severity} | status=${it.verificationStatus} | src=${it.dataSource}")
            }
            appendLine("PRESENCE_SIGNALS (${signals.size}):")
            signals.take(8).forEach {
                appendLine("- ${it.sectorLabel} | kind=${it.sourceKind} | type=${it.signalType} | est=${it.estimatedMinCount}-${it.estimatedMaxCount} | conf=${it.confidencePercent}% | status=${it.verificationStatus} | health=${it.sensorHealth}")
            }
            appendLine("RESCUE_TEAMS (${teams.size}):")
            teams.take(8).forEach {
                val gps = if (it.hasValidLocation) "${it.latitude},${it.longitude}" else "UNAVAILABLE(${it.locationUnavailableReason})"
                appendLine("- ${it.teamCode} (${it.teamName}) | status=${it.status} | active=${it.activeCount}/${it.memberCount} | conn=${it.connectionStatus} | gps=$gps")
            }
            appendLine("REGISTRY_ENTRIES (${registry.size}):")
            registry.take(8).forEach {
                appendLine("- [${it.category}] ${it.title} at ${it.locationName} | rescue=${it.rescueStatus} | verification=${it.verificationStatus}")
            }
        }

        fun parseAiTriageJson(
            rawJson: String,
            dataSourcesUsed: List<String>,
        ): Result<AiTriageReport> {
            return try {
                val json = JSONObject(rawJson)
                val executiveSummary = json.optString("executiveSummary").trim()
                if (executiveSummary.isBlank()) {
                    return Result.failure(IllegalArgumentException("AI response omitted executiveSummary."))
                }
                fun extractList(key: String): List<String> =
                    json.optJSONArray(key)?.let { arr ->
                        (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { s -> s.isNotBlank() } }
                    } ?: emptyList()

                Result.success(
                    AiTriageReport(
                        executiveSummary = executiveSummary,
                        unacknowledgedSosSummary = extractList("unacknowledgedSosSummary"),
                        prioritizedSectors = extractList("prioritizedSectors"),
                        missingOrStaleDisclosures = extractList("missingOrStaleDisclosures"),
                        dataSourcesUsed = dataSourcesUsed,
                        recommendedActions = extractList("recommendedActions"),
                        requiresHumanApproval = true,
                    )
                )
            } catch (e: Exception) {
                Result.failure(IllegalArgumentException("Malformed AI JSON response: ${e.localizedMessage}"))
            }
        }
    }
}
