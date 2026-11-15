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

    private var lastRequestAtMillis = 0L

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

    private val primaryTriageModel by lazy {
        Firebase.ai.generativeModel(
            modelName = PRIMARY_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = triageSchema
            }
        )
    }

    private val fallbackTriageModel by lazy {
        Firebase.ai.generativeModel(
            modelName = FALLBACK_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = triageSchema
            }
        )
    }

    private val primaryChatModel by lazy {
        Firebase.ai.generativeModel(modelName = PRIMARY_MODEL)
    }

    private val fallbackChatModel by lazy {
        Firebase.ai.generativeModel(modelName = FALLBACK_MODEL)
    }

    suspend fun generateSituationTriage(
        locations: List<RescueLocation>,
        signals: List<PresenceSignal>,
        teams: List<RescueTeam>,
        registry: List<RescueRegistryEntry>,
        incidents: List<SosIncident>,
        isDemoMode: Boolean,
        languageCode: String = "en",
    ): Result<AiTriageReport> {
        val now = System.currentTimeMillis()
        if (now - lastRequestAtMillis < COOLDOWN_MILLIS) {
            val waitSec = ((COOLDOWN_MILLIS - (now - lastRequestAtMillis)) / 1000L).coerceAtLeast(1L)
            return Result.failure(IllegalStateException("AI cooldown active (${waitSec}s remaining) to prevent rapid quota spikes."))
        }
        lastRequestAtMillis = now

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
            languageCode = languageCode,
            nowMillis = now,
        )

        var usedModel = PRIMARY_MODEL
        val response = try {
            withTimeout(60_000L) {
                primaryTriageModel.generateContent(contextPrompt)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Primary AI triage timed out", e)
            return Result.failure(Exception("AI triage timed out after 60 seconds. Check network and retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (primaryErr: Exception) {
            Log.w(TAG, "Primary model failed, retrying with $FALLBACK_MODEL: ${primaryErr.message}")
            usedModel = FALLBACK_MODEL
            try {
                withTimeout(45_000L) {
                    fallbackTriageModel.generateContent(contextPrompt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (fallbackErr: Exception) {
                Log.e(TAG, "Fallback AI triage also failed", fallbackErr)
                return Result.failure(fallbackErr)
            }
        }

        val jsonText = response.text ?: return Result.failure(
            IllegalStateException("AI model returned an empty triage response.")
        )
        return parseAiTriageJson(
            rawJson = jsonText,
            dataSourcesUsed = sourcesUsed,
            modelUsed = usedModel,
            languageCode = languageCode,
        )
    }

    suspend fun askOperationalQuestion(
        question: String,
        locations: List<RescueLocation>,
        signals: List<PresenceSignal>,
        teams: List<RescueTeam>,
        registry: List<RescueRegistryEntry>,
        incidents: List<SosIncident>,
        isDemoMode: Boolean,
        languageCode: String = "en",
    ): Result<String> {
        val trimmed = question.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a coordination question first."))
        }
        val now = System.currentTimeMillis()
        if (now - lastRequestAtMillis < COOLDOWN_MILLIS) {
            return Result.failure(IllegalStateException("Please wait a moment between AI queries."))
        }
        lastRequestAtMillis = now

        val context = buildOperationalContextPrompt(
            locations = locations,
            signals = signals,
            teams = teams,
            registry = registry,
            incidents = incidents,
            isDemoMode = isDemoMode,
            languageCode = languageCode,
            nowMillis = now,
        )
        val langInstruction = if (languageCode == "hi") {
            "Respond clearly in Hindi (Devanagari script), keeping technical IDs/codes in Latin characters."
        } else {
            "Respond concisely in English."
        }
        val fullPrompt = """
            $context
            
            RESPONDER QUESTION:
            $trimmed
            
            ANSWER INSTRUCTIONS:
            - $langInstruction
            - Answer strictly using the authorized operational data above.
            - Never invent counts, coordinates, team locations, timestamps, or survivor statuses.
            - Explicitly disclose any missing GPS, simulated-only signals, or outdated/unverified data.
            - End with: "Advisory Only — Human commander approval required before dispatch."
        """.trimIndent()

        return try {
            val response = try {
                withTimeout(60_000L) { primaryChatModel.generateContent(fullPrompt) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withTimeout(45_000L) { fallbackChatModel.generateContent(fullPrompt) }
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
        const val PRIMARY_MODEL = "gemini-3.8-flash"
        const val FALLBACK_MODEL = "gemini-3.5-flash-lite"
        private const val COOLDOWN_MILLIS = 4_000L

        fun buildOperationalContextPrompt(
            locations: List<RescueLocation>,
            signals: List<PresenceSignal>,
            teams: List<RescueTeam>,
            registry: List<RescueRegistryEntry>,
            incidents: List<SosIncident>,
            isDemoMode: Boolean,
            languageCode: String = "en",
            nowMillis: Long = System.currentTimeMillis(),
        ): String = buildString {
            appendLine("You are a humanitarian disaster-rescue coordination analyst for PROJECT SURAKSHA.")
            appendLine("Strict accuracy & humanitarian rules:")
            appendLine("1. NEVER fabricate GPS coordinates, team statuses, timestamps, or sensor counts.")
            appendLine("2. NEVER present simulated or estimated presence signals as confirmed survivors.")
            appendLine("3. Explicitly distinguish simulated vs hardware, and verified vs estimated vs unverified vs outdated.")
            appendLine("4. Explicitly mention missing location data, stale readings (>120s), or unacknowledged SOS alerts.")
            appendLine("5. Never profile or classify any individual negatively.")
            if (languageCode == "hi") {
                appendLine("6. Write all narrative values in Hindi (Devanagari), preserving technical IDs and coordinates.")
            }
            appendLine()
            appendLine("SNAPSHOT_TIME_MILLIS: $nowMillis")
            appendLine("DEMO_MODE_ACTIVE: $isDemoMode")
            appendLine("SOS_INCIDENTS (${incidents.size}):")
            incidents.take(8).forEach {
                val locLabel = if (it.hasLocation) "${it.latitude},${it.longitude}" else "NO_GPS"
                appendLine("- [${it.incidentId}] ${it.priority} | status=${it.status} | push=${it.notificationStatus} | team=${it.teamCode} | loc=$locLabel | desc=${it.description}")
            }
            appendLine("DISASTER_LOCATIONS (${locations.size}):")
            locations.take(8).forEach {
                appendLine("- ${it.name} (${it.region}) | hazard=${it.hazardType} | severity=${it.severity} | status=${it.verificationStatus} | src=${it.dataSource}")
            }
            appendLine("PRESENCE_SIGNALS (${signals.size}):")
            signals.take(8).forEach {
                val ageSec = if (it.observationTimestampMillis > 0L) ((nowMillis - it.observationTimestampMillis).coerceAtLeast(0L) / 1000L) else -1L
                appendLine("- ${it.sectorLabel} | kind=${it.sourceKind} | type=${it.signalType} | est=${it.estimatedMinCount}-${it.estimatedMaxCount} | conf=${it.confidencePercent}% | status=${it.verificationStatus} | ageSec=$ageSec | health=${it.sensorHealth}")
            }
            appendLine("RESCUE_TEAMS (${teams.size}):")
            teams.take(8).forEach {
                val gps = if (it.hasValidLocation) "${it.latitude},${it.longitude}" else "UNAVAILABLE(${it.locationUnavailableReason})"
                appendLine("- ${it.teamCode} (${it.teamName}) | status=${it.status} | active=${it.activeCount}/${it.memberCount} | conn=${it.connectionStatus} | gps=$gps")
            }
            appendLine("REGISTRY_ENTRIES (${registry.size}):")
            registry.take(8).forEach {
                val med = if (it.category == "medical_request") " | medUrgency=${it.medicalUrgency} | medCat=${it.medicalCategory}" else ""
                appendLine("- [${it.category}] ${it.title} at ${it.locationName} | rescue=${it.rescueStatus} | verification=${it.verificationStatus}$med")
            }
        }

        fun parseAiTriageJson(
            rawJson: String,
            dataSourcesUsed: List<String>,
            modelUsed: String = PRIMARY_MODEL,
            languageCode: String = "en",
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
                        modelUsed = modelUsed,
                        languageCode = languageCode,
                    )
                )
            } catch (e: Exception) {
                Result.failure(IllegalArgumentException("Malformed AI JSON response: ${e.localizedMessage}"))
            }
        }
    }
}
