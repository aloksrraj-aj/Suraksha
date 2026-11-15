package com.example.data.ai

import android.util.Log
import com.example.data.model.AiTriageReport
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
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
                description = "Concise humanitarian disaster situation summary based strictly on the provided observations."
            ),
            "prioritizedSectors" to Schema.array(
                items = Schema.string(),
                description = "Top 2-4 rescue sectors ordered by urgency and verification status."
            ),
            "verificationGaps" to Schema.array(
                items = Schema.string(),
                description = "Observations that remain unverified, estimated, or outdated and require human field verification."
            ),
            "recommendedActions" to Schema.array(
                items = Schema.string(),
                description = "Actionable next coordination steps for rescue teams."
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

    suspend fun generateSituationTriage(
        locations: List<RescueLocation>,
        signals: List<PresenceSignal>,
        teams: List<RescueTeam>,
        registry: List<RescueRegistryEntry>,
        isDemoMode: Boolean,
    ): Result<AiTriageReport> {
        val contextPrompt = buildString {
            appendLine("You are a humanitarian disaster-rescue coordination analyst for PROJECT SURAKSHA.")
            appendLine("Strict safety & accuracy rules:")
            appendLine("1. Never treat estimated human-presence signals as confirmed survivor counts.")
            appendLine("2. Clearly distinguish Verified, Estimated, Unverified, and Outdated records.")
            appendLine("3. Never classify or profile any person as a threat, criminal, or suspicious actor.")
            appendLine("4. If DEMO MODE is active, note that input observations are synthetic simulation records.")
            appendLine()
            appendLine("DEMO_MODE_ACTIVE: $isDemoMode")
            appendLine("DISASTER_LOCATIONS (${locations.size}):")
            locations.take(6).forEach {
                appendLine("- ${it.name} (${it.region}) | hazard=${it.hazardType} | severity=${it.severity} | status=${it.verificationStatus}")
            }
            appendLine("PRESENCE_SIGNALS (${signals.size}):")
            signals.take(6).forEach {
                appendLine("- ${it.sectorLabel} | type=${it.signalType} | est=${it.estimatedMinCount}-${it.estimatedMaxCount} | conf=${it.confidencePercent}% | status=${it.verificationStatus}")
            }
            appendLine("RESCUE_TEAMS (${teams.size}):")
            teams.take(6).forEach {
                appendLine("- ${it.teamCode} (${it.teamName}) | status=${it.status} | active=${it.activeCount}/${it.memberCount} | conn=${it.connectionStatus}")
            }
            appendLine("REGISTRY_ENTRIES (${registry.size}):")
            registry.take(6).forEach {
                appendLine("- [${it.category}] ${it.title} at ${it.locationName} | rescue=${it.rescueStatus} | verification=${it.verificationStatus}")
            }
        }

        return try {
            val response = withTimeout(60_000L) {
                model.generateContent(contextPrompt)
            }
            val jsonText = response.text ?: return Result.failure(
                IllegalStateException("AI model returned an empty triage response.")
            )
            val json = JSONObject(jsonText)
            val prioritized = json.optJSONArray("prioritizedSectors")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val gaps = json.optJSONArray("verificationGaps")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val actions = json.optJSONArray("recommendedActions")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()

            Result.success(
                AiTriageReport(
                    executiveSummary = json.optString("executiveSummary", "Situational summary generated."),
                    prioritizedSectors = prioritized,
                    verificationGaps = gaps,
                    recommendedActions = actions,
                )
            )
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

    private companion object {
        const val TAG = "SurakshaAiRepo"
    }
}
