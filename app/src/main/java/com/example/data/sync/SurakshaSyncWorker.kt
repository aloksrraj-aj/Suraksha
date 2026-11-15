package com.example.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.SurakshaLocalDatabase
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.example.data.model.SosIncident
import com.example.data.model.SyncStatus
import com.example.data.repository.SurakshaRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SurakshaSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val uid = Firebase.auth.currentUser?.uid ?: return Result.retry()
        val displayName = Firebase.auth.currentUser?.displayName ?: "Field Responder"
        val dao = SurakshaLocalDatabase.getInstance(applicationContext).localDao()
        val repo = SurakshaRepository(applicationContext)
        val pending = dao.getRetryableOperations(System.currentTimeMillis())
        if (pending.isEmpty()) return Result.success()

        var needsRetry = false
        for (op in pending) {
            val res = executePendingOp(repo, uid, displayName, op.orgId, op.operationId, op.entityType, op.payloadJson)
            if (res.isSuccess) {
                dao.deletePendingSyncOperation(op.operationId)
                dao.insertSyncHistory(
                    com.example.data.local.SyncHistoryEntity(
                        operationId = op.operationId,
                        orgId = op.orgId,
                        entityType = op.entityType,
                        outcome = "synced",
                        detail = "Synced via background WorkManager",
                        syncedAtMillis = System.currentTimeMillis(),
                    )
                )
            } else {
                val nextRetries = op.retryCount + 1
                val terminal = nextRetries >= OfflineSyncManager.MAX_RETRIES
                val errMsg = res.exceptionOrNull()?.localizedMessage ?: "WorkManager sync failed"
                dao.upsertPendingSyncOperation(
                    op.copy(
                        syncStatus = if (terminal) SyncStatus.DEAD_LETTER.value else SyncStatus.FAILED.value,
                        retryCount = nextRetries,
                        nextRetryAtMillis = System.currentTimeMillis() + OfflineSyncManager.computeBackoffMillis(nextRetries),
                        lastError = errMsg.take(240),
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                )
                if (!terminal) needsRetry = true
            }
        }
        return if (needsRetry) Result.retry() else Result.success()
    }

    private suspend fun executePendingOp(
        repo: SurakshaRepository,
        uid: String,
        displayName: String,
        orgId: String,
        operationId: String,
        entityType: String,
        payloadJson: String,
    ): kotlin.Result<Unit> {
        val json = JSONObject(payloadJson)
        val docId = json.optString("docId").takeIf { it.isNotBlank() } ?: operationId
        return when (entityType) {
            "create_location" -> repo.createRescueLocation(
                orgId,
                RescueLocation(
                    orgId = orgId,
                    userId = uid,
                    name = json.optString("name"),
                    region = json.optString("region"),
                    hazardType = json.optString("hazardType"),
                    severity = json.optString("severity"),
                    latitude = json.optDouble("latitude"),
                    longitude = json.optDouble("longitude"),
                    verificationStatus = json.optString("verificationStatus", "unverified"),
                    dataSource = json.optString("dataSource"),
                    isSynthetic = json.optBoolean("isSynthetic", true),
                ),
                explicitId = docId,
            ).map { }
            "create_team" -> repo.createRescueTeam(
                orgId,
                RescueTeam(
                    orgId = orgId,
                    userId = uid,
                    leaderUid = uid,
                    teamCode = json.optString("teamCode"),
                    teamName = json.optString("teamName"),
                    memberCount = json.optLong("memberCount", 1L),
                    activeCount = json.optLong("activeCount", 1L),
                    status = json.optString("status", "standby"),
                    locationSharingOptIn = json.optBoolean("locationSharingOptIn", false),
                    locationUnavailableReason = "Synced from offline WorkManager queue",
                    isSynthetic = json.optBoolean("isSynthetic", true),
                ),
                explicitId = docId,
            ).map { }
            "create_registry" -> repo.createRegistryEntry(
                orgId,
                RescueRegistryEntry(
                    orgId = orgId,
                    userId = uid,
                    category = json.optString("category"),
                    title = json.optString("title"),
                    locationName = json.optString("locationName"),
                    details = json.optString("details"),
                    sensitiveContactMasked = json.optString("sensitiveContactMasked"),
                    verificationStatus = json.optString("verificationStatus", "reported"),
                    informationSource = json.optString("informationSource", "Offline Field Queue"),
                    isSynthetic = json.optBoolean("isSynthetic", true),
                ),
                explicitId = docId,
            ).map { }
            "create_sos" -> repo.createSosIncident(
                orgId,
                SosIncident(
                    orgId = orgId,
                    senderUid = uid,
                    senderName = displayName,
                    teamCode = json.optString("teamCode"),
                    priority = json.optString("priority", "sos"),
                    description = json.optString("description"),
                    hasLocation = json.optBoolean("hasLocation", false),
                    latitude = json.optDouble("latitude", 0.0),
                    longitude = json.optDouble("longitude", 0.0),
                    accuracyMeters = json.optDouble("accuracyMeters", 0.0),
                    isSynthetic = json.optBoolean("isSynthetic", false),
                ),
                explicitId = docId,
            ).map { }
            "create_presence" -> repo.createPresenceSignal(
                orgId,
                PresenceSignal(
                    orgId = orgId,
                    userId = uid,
                    locationId = json.optString("locationId", "sector_field"),
                    sectorLabel = json.optString("sectorLabel"),
                    signalType = json.optString("signalType", "thermal_ir"),
                    sourceKind = json.optString("sourceKind", "simulated"),
                    deviceId = json.optString("deviceId", "offline-sensor"),
                    estimatedMinCount = json.optLong("estimatedMinCount", 1L),
                    estimatedMaxCount = json.optLong("estimatedMaxCount", 2L),
                    confidencePercent = json.optLong("confidencePercent", 50L),
                    verificationStatus = "estimated",
                    dataSource = json.optString("dataSource", "Offline Cached Telemetry"),
                    observationTimestampMillis = json.optLong("observationTimestampMillis", System.currentTimeMillis()),
                    isSynthetic = json.optBoolean("isSynthetic", true),
                ),
                explicitId = docId,
            ).map { }
            else -> kotlin.Result.failure(IllegalArgumentException("Unknown offline op: $entityType"))
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "suraksha_offline_sync_work"

        fun schedule(context: Context) {
            runCatching {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val req = OneTimeWorkRequestBuilder<SurakshaSyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        androidx.work.BackoffPolicy.EXPONENTIAL,
                        15,
                        TimeUnit.SECONDS,
                    )
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, req)
            }
        }
    }
}
