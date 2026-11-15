package com.example.data.sync

import android.content.Context
import android.util.Log
import com.example.data.local.PendingSyncOperationEntity
import com.example.data.local.SurakshaLocalDao
import com.example.data.local.SyncHistoryEntity
import com.example.data.model.SyncStatus
import com.example.data.network.NetworkMonitor
import com.example.data.network.NetworkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

sealed interface SyncDispatchResult {
    data class SyncedImmediately(val resourceId: String) : SyncDispatchResult
    data class QueuedOffline(val operationId: String, val reason: String) : SyncDispatchResult
}

interface SyncOperationExecutor {
    suspend fun executeOperation(op: PendingSyncOperationEntity): Result<Unit>
}

class OfflineSyncManager(
    private val dao: SurakshaLocalDao,
    private val networkMonitor: NetworkMonitor,
    private val scope: CoroutineScope,
    private val appContext: Context? = null,
) {
    val pendingOperations: Flow<List<PendingSyncOperationEntity>> = dao.observePendingSyncOperations()
    val syncHistory: Flow<List<SyncHistoryEntity>> = dao.observeSyncHistory()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSuccessfulSyncMillis = MutableStateFlow(0L)
    val lastSuccessfulSyncMillis: StateFlow<Long> = _lastSuccessfulSyncMillis.asStateFlow()

    private var executor: SyncOperationExecutor? = null

    fun attachExecutor(operationExecutor: SyncOperationExecutor) {
        this.executor = operationExecutor
        scope.launch {
            networkMonitor.networkState
                .collect { state ->
                    if (state != NetworkState.OFFLINE) {
                        flushPendingQueue()
                    }
                }
        }
    }

    suspend fun enqueuePendingOperation(
        orgId: String,
        entityType: String,
        payload: JSONObject,
        operationId: String = "${entityType}_${UUID.randomUUID().toString().replace("-", "").take(12)}",
        lastError: String = "Queued offline; awaiting connectivity",
    ): String {
        val now = System.currentTimeMillis()
        dao.upsertPendingSyncOperation(
            PendingSyncOperationEntity(
                operationId = operationId,
                orgId = orgId,
                entityType = entityType,
                payloadJson = payload.toString(),
                syncStatus = SyncStatus.PENDING_SYNC.value,
                retryCount = 0,
                lastError = lastError,
                createdAtMillis = now,
                updatedAtMillis = now,
                idempotencyKey = "${orgId}_${entityType}_$operationId",
                nextRetryAtMillis = 0L,
                conflictNote = "",
            )
        )
        appContext?.let { SurakshaSyncWorker.schedule(it) }
        return operationId
    }

    suspend fun retryOperationNow(operationId: String) {
        val existing = dao.getPendingOperationById(operationId) ?: return
        dao.upsertPendingSyncOperation(
            existing.copy(
                syncStatus = SyncStatus.PENDING_SYNC.value,
                retryCount = 0,
                nextRetryAtMillis = 0L,
                lastError = "Manual retry requested",
                updatedAtMillis = System.currentTimeMillis(),
            )
        )
        flushPendingQueue(forceIgnoreBackoff = true)
    }

    suspend fun flushPendingQueue(forceIgnoreBackoff: Boolean = false): Pair<Int, Int> {
        val exec = executor ?: return 0 to 0
        if (!_isSyncing.compareAndSet(expect = false, update = true)) return 0 to 0
        var syncedCount = 0
        var failedCount = 0
        try {
            if (networkMonitor.refreshNow() == NetworkState.OFFLINE) {
                return 0 to 0
            }
            val now = System.currentTimeMillis()
            val pending = dao.getRetryableOperations(if (forceIgnoreBackoff) Long.MAX_VALUE else now)
            for (op in pending) {
                val res = try {
                    exec.executeOperation(op)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }

                if (res.isSuccess) {
                    dao.deletePendingSyncOperation(op.operationId)
                    dao.insertSyncHistory(
                        SyncHistoryEntity(
                            operationId = op.operationId,
                            orgId = op.orgId,
                            entityType = op.entityType,
                            outcome = "synced",
                            detail = "Delivered to Firestore (${op.idempotencyKey.ifBlank { op.operationId }})",
                            syncedAtMillis = System.currentTimeMillis(),
                        )
                    )
                    syncedCount++
                } else {
                    val errorMsg = res.exceptionOrNull()?.localizedMessage ?: "Sync rejected by server"
                    val nextRetries = op.retryCount + 1
                    val isDeadLetter = nextRetries >= MAX_RETRIES
                    val status = if (isDeadLetter) SyncStatus.DEAD_LETTER.value else SyncStatus.FAILED.value
                    val nextRetryAt = System.currentTimeMillis() + computeBackoffMillis(nextRetries)
                    Log.w(TAG, "Sync failed for ${op.operationId} (attempt $nextRetries/$MAX_RETRIES): $errorMsg")
                    dao.upsertPendingSyncOperation(
                        op.copy(
                            syncStatus = status,
                            retryCount = nextRetries,
                            nextRetryAtMillis = nextRetryAt,
                            lastError = errorMsg.take(240),
                            conflictNote = if (errorMsg.contains("PERMISSION_DENIED", ignoreCase = true)) {
                                "Server rejected write (check org role or stale state); inspect before retry."
                            } else op.conflictNote,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    )
                    dao.insertSyncHistory(
                        SyncHistoryEntity(
                            operationId = op.operationId,
                            orgId = op.orgId,
                            entityType = op.entityType,
                            outcome = if (isDeadLetter) "dead_letter" else "failed",
                            detail = errorMsg.take(200),
                            syncedAtMillis = System.currentTimeMillis(),
                        )
                    )
                    failedCount++
                }
            }
            if (syncedCount > 0 || pending.isEmpty()) {
                _lastSuccessfulSyncMillis.value = System.currentTimeMillis()
            }
        } finally {
            _isSyncing.value = false
        }
        return syncedCount to failedCount
    }

    companion object {
        private const val TAG = "OfflineSyncManager"
        const val MAX_RETRIES = 5

        fun computeBackoffMillis(retryCount: Int): Long {
            val shift = retryCount.coerceIn(0, 6)
            return 5_000L * (1L shl shift) // 5s, 10s, 20s, 40s, 80s...
        }
    }
}
