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
import com.example.data.local.SyncHistoryEntity
import com.example.data.model.DetectionObservation
import com.example.data.model.SosAlert
import com.example.data.model.SosAlertStatus
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
                    SyncHistoryEntity(
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
        val missionId = json.optString("missionId")
        if (missionId.isBlank()) {
            return kotlin.Result.failure(IllegalArgumentException("Missing missionId in queued operation"))
        }
        return when (entityType) {
            "create_sos" -> repo.createSosAlert(
                SosAlert(
                    alertId = docId,
                    orgId = orgId,
                    missionId = missionId,
                    senderUid = uid,
                    senderName = json.optString("senderName", displayName),
                    teamId = json.optString("teamId"),
                    alertType = json.optString("alertType", "sos"),
                    priority = json.optString("priority", "critical"),
                    hasLocation = json.optBoolean("hasLocation", false),
                    latitude = json.optDouble("latitude", 0.0),
                    longitude = json.optDouble("longitude", 0.0),
                    accuracyMeters = json.optDouble("accuracyMeters", 0.0),
                    locationTimestampMillis = json.optLong("locationTimestampMillis", System.currentTimeMillis()),
                    description = json.optString("description"),
                ),
                explicitId = docId,
            ).map { }

            "cancel_sos" -> repo.transitionSosAlert(
                orgId = orgId,
                missionId = missionId,
                alertId = json.optString("alertId", docId),
                nextStatus = SosAlertStatus.CANCELLED,
                actorName = displayName,
                cancelReason = json.optString("cancelReason", "Cancelled by responder"),
            )

            "sensor_observation" -> repo.logDetectionObservation(
                DetectionObservation(
                    detectionId = docId,
                    orgId = orgId,
                    missionId = missionId,
                    sensorEventId = json.optString("sensorEventId"),
                    sensorId = json.optString("sensorId"),
                    operatorUid = uid,
                    notes = json.optString("notes"),
                    outcome = json.optString("outcome", "investigating"),
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
