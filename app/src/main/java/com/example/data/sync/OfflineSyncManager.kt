package com.example.data.sync

import android.util.Log
import com.example.data.local.PendingSyncOperationEntity
import com.example.data.local.SurakshaLocalDao
import com.example.data.model.SyncStatus
import com.example.data.network.NetworkMonitor
import com.example.data.network.NetworkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
) {
    val pendingOperations: Flow<List<PendingSyncOperationEntity>> = dao.observePendingSyncOperations()

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
            )
        )
        return operationId
    }

    suspend fun flushPendingQueue(): Pair<Int, Int> {
        val exec = executor ?: return 0 to 0
        if (!_isSyncing.compareAndSet(expect = false, update = true)) return 0 to 0
        var syncedCount = 0
        var failedCount = 0
        try {
            if (networkMonitor.refreshNow() == NetworkState.OFFLINE) {
                return 0 to 0
            }
            val pending = dao.getRetryableOperations()
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
                    syncedCount++
                } else {
                    val errorMsg = res.exceptionOrNull()?.localizedMessage ?: "Sync rejected by server"
                    Log.w(TAG, "Sync failed for ${op.operationId}: $errorMsg")
                    dao.upsertPendingSyncOperation(
                        op.copy(
                            syncStatus = SyncStatus.FAILED.value,
                            retryCount = op.retryCount + 1,
                            lastError = errorMsg.take(240),
                            updatedAtMillis = System.currentTimeMillis(),
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
    }
}
