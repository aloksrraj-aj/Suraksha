package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.SurakshaAiRepository
import com.example.data.firebase.SurakshaMessagingService
import com.example.data.hardware.BleGattSensorAdapter
import com.example.data.hardware.EnvScenario
import com.example.data.hardware.SectorFusionSummary
import com.example.data.hardware.SensorAdapterDescriptor
import com.example.data.hardware.SensorArchitectureCatalog
import com.example.data.hardware.SensorFusionProcessor
import com.example.data.hardware.SlamEnvironmentFrame
import com.example.data.hardware.SlamExportFormatter
import com.example.data.hardware.SlamGatewayClient
import com.example.data.hardware.WifiHttpGatewayAdapter
import com.example.data.local.CachedSensorLogEntity
import com.example.data.local.JoinedOrgEntity
import com.example.data.local.OfflineCacheRepository
import com.example.data.local.OfflineMapRegionEntity
import com.example.data.local.PendingSyncOperationEntity
import com.example.data.local.SlamSessionEntity
import com.example.data.local.SurakshaPreferences
import com.example.data.local.SurakshaSettings
import com.example.data.local.SyncHistoryEntity
import com.example.data.location.LocationFixState
import com.example.data.location.SurakshaLocationBus
import com.example.data.location.SurakshaLocationController
import com.example.data.map.IndiaPlace
import com.example.data.map.OsmTileManager
import com.example.data.map.RegionDownloadProgress
import com.example.data.map.RescueRoute
import com.example.data.model.ActivityLogEntry
import com.example.data.model.AiTriageReport
import com.example.data.model.BlockedRoadMarker
import com.example.data.model.DroneTelemetry
import com.example.data.model.MapLayerConfig
import com.example.data.model.NotificationDispatchRecord
import com.example.data.model.OrgInvite
import com.example.data.model.OrgMember
import com.example.data.model.OrgRole
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueMission
import com.example.data.model.RescueOrganization
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.example.data.model.SosIncident
import com.example.data.model.SosIncidentStatus
import com.example.data.model.SyncStatus
import com.example.data.model.UserProfile
import com.example.data.network.NetworkMonitor
import com.example.data.network.NetworkState
import com.example.data.repository.SurakshaRepository
import com.example.data.sync.OfflineSyncManager
import com.example.data.sync.SyncOperationExecutor
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

sealed interface DataUiState<out T> {
    data object Loading : DataUiState<Nothing>
    data class Success<T>(val data: T) : DataUiState<T>
    data class Error(val message: String) : DataUiState<Nothing>
}

sealed interface AiTriageUiState {
    data object Idle : AiTriageUiState
    data object Loading : AiTriageUiState
    data class Success(val report: AiTriageReport) : AiTriageUiState
    data class Error(val message: String) : AiTriageUiState
}

data class DashboardFilterState(
    val hazardFilter: String = "all",
    val teamFilter: String = "all",
    val timeWindowHours: Int = 0, // 0 = All time, 24 = 24h, 72 = 72h
    val searchQuery: String = "",
)

data class OperationalAnalytics(
    val activeIncidentsCount: Int = 0,
    val unacknowledgedSosCount: Int = 0,
    val avgAckLatencyMinutes: Double? = null,
    val disasterLocationsCount: Int = 0,
    val verifiedRegistryCount: Int = 0,
    val verifiedSurvivorCount: Int = 0,
    val missingPersonsCount: Int = 0,
    val medicalRequestsCount: Int = 0,
    val estimatedSignalsCount: Int = 0,
    val estimatedSurvivorSum: Int = 0,
    val fieldVerifiedSignalsCount: Int = 0,
    val deployedTeamsCount: Int = 0,
    val staleSensorsCount: Int = 0,
    val contradictionAlertsCount: Int = 0,
    val activeMissionsCount: Int = 0,
    val blockedRoadsCount: Int = 0,
    val onlineSensorsCount: Int = 0,
    val offlineSensorsCount: Int = 0,
    val deadLetterSyncCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SurakshaViewModel(
    private val appContext: Context,
    private val repository: SurakshaRepository,
    private val offlineCache: OfflineCacheRepository,
    val tileManager: OsmTileManager,
    private val networkMonitor: NetworkMonitor,
    private val syncManager: OfflineSyncManager,
    val locationController: SurakshaLocationController,
    private val preferences: SurakshaPreferences,
    private val aiRepository: SurakshaAiRepository,
    private val currentUserId: String,
    private val initialDisplayName: String,
) : ViewModel(), SyncOperationExecutor {

    val settings: StateFlow<SurakshaSettings> = preferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), SurakshaSettings())

    val networkState: StateFlow<NetworkState> = networkMonitor.networkState
    val locationFixState: StateFlow<LocationFixState> = locationController.locationState

    private val _activeOrgId = MutableStateFlow("")
    val activeOrgId: StateFlow<String> = _activeOrgId.asStateFlow()

    val userProfileState: StateFlow<DataUiState<UserProfile?>> =
        repository.observeUserProfile(currentUserId).toUiState("profile")

    val currentOrganization: StateFlow<DataUiState<RescueOrganization?>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeOrganization(orgId).toUiState("organization")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val orgMembersState: StateFlow<DataUiState<List<OrgMember>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeOrgMembers(orgId).toUiState("org members")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val orgInvitesState: StateFlow<DataUiState<List<OrgInvite>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeOrgInvites(orgId).toUiState("org invites")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val currentMemberRole: StateFlow<OrgRole> = orgMembersState
        .map { state ->
            val members = (state as? DataUiState.Success)?.data.orEmpty()
            val mine = members.firstOrNull { it.userId == currentUserId }
            mine?.let { OrgRole.fromValue(it.role) } ?: OrgRole.OBSERVER
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), OrgRole.OBSERVER)

    val rescueTeamsState: StateFlow<DataUiState<List<RescueTeam>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeRescueTeams(orgId).toUiState("rescue teams")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val rescueLocationsState: StateFlow<DataUiState<List<RescueLocation>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeRescueLocations(orgId).toUiState("rescue locations")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val presenceSignalsState: StateFlow<DataUiState<List<PresenceSignal>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observePresenceSignals(orgId).toUiState("presence signals")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val sectorSummaries: StateFlow<List<SectorFusionSummary>> = presenceSignalsState
        .map { state ->
            SensorFusionProcessor.summarizeSectors((state as? DataUiState.Success)?.data.orEmpty())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val rescueRegistryState: StateFlow<DataUiState<List<RescueRegistryEntry>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeRescueRegistry(orgId).toUiState("rescue registry")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val sosIncidentsState: StateFlow<DataUiState<List<SosIncident>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeSosIncidents(orgId).toUiState("SOS incidents")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val notificationDispatchesState: StateFlow<DataUiState<List<NotificationDispatchRecord>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeNotificationDispatches(orgId).toUiState("notification dispatches")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val activityLogsState: StateFlow<DataUiState<List<ActivityLogEntry>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeActivityLogs(orgId).toUiState("activity logs")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val missionsState: StateFlow<DataUiState<List<RescueMission>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeMissions(orgId).toUiState("missions")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val blockedRoadsState: StateFlow<DataUiState<List<BlockedRoadMarker>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeBlockedRoads(orgId).toUiState("blocked roads")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val droneTelemetryState: StateFlow<DataUiState<List<DroneTelemetry>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeDroneTelemetry(orgId).toUiState("drone telemetry")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val offlineRegions: StateFlow<List<OfflineMapRegionEntity>> = offlineCache.offlineRegions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val downloadProgress: StateFlow<Map<String, RegionDownloadProgress>> = tileManager.downloadProgress
    val totalCachedTileCount: StateFlow<Int> = offlineCache.totalCachedTileCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0)
    val totalCachedBytes: StateFlow<Long> = offlineCache.totalCachedBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0L)

    val pendingSyncOps: StateFlow<List<PendingSyncOperationEntity>> = syncManager.pendingOperations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    val syncHistory: StateFlow<List<SyncHistoryEntity>> = offlineCache.syncHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    val joinedOrganizations: StateFlow<List<JoinedOrgEntity>> = offlineCache.joinedOrganizations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing
    val lastSuccessfulSyncMillis: StateFlow<Long> = syncManager.lastSuccessfulSyncMillis

    val cachedSensorLogs: StateFlow<List<CachedSensorLogEntity>> = offlineCache.recentSensorLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    val slamSessions: StateFlow<List<SlamSessionEntity>> = offlineCache.slamSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _dashboardFilter = MutableStateFlow(DashboardFilterState())
    val dashboardFilter: StateFlow<DashboardFilterState> = _dashboardFilter.asStateFlow()

    val sensorAdapters: StateFlow<List<SensorAdapterDescriptor>> = settings
        .map { s ->
            SensorArchitectureCatalog.buildAdapters(
                context = appContext,
                httpGatewayUrl = s.hardwareGatewayUrl,
                httpGatewayToken = s.hardwareGatewayToken,
            ).map { it.descriptor }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val coreCollectionsAnalyticsFlow = combine(
        sosIncidentsState,
        rescueRegistryState,
        presenceSignalsState,
        rescueTeamsState,
        rescueLocationsState,
    ) { sosState, regState, sigState, teamState, locState ->
        listOf(sosState, regState, sigState, teamState, locState)
    }

    private val opsAuxAnalyticsFlow = combine(
        missionsState,
        blockedRoadsState,
        pendingSyncOps,
        sensorAdapters,
        _dashboardFilter,
    ) { mState, rState, syncOps, adapters, filter ->
        listOf(mState, rState, syncOps, adapters, filter)
    }

    @Suppress("UNCHECKED_CAST")
    val analytics: StateFlow<OperationalAnalytics> = combine(
        coreCollectionsAnalyticsFlow,
        opsAuxAnalyticsFlow,
    ) { core, aux ->
        val sosState = core[0] as DataUiState<List<SosIncident>>
        val regState = core[1] as DataUiState<List<RescueRegistryEntry>>
        val sigState = core[2] as DataUiState<List<PresenceSignal>>
        val teamState = core[3] as DataUiState<List<RescueTeam>>
        val locState = core[4] as DataUiState<List<RescueLocation>>

        val missionsStateVal = aux[0] as DataUiState<List<RescueMission>>
        val roadsStateVal = aux[1] as DataUiState<List<BlockedRoadMarker>>
        val syncOps = aux[2] as List<PendingSyncOperationEntity>
        val adapters = aux[3] as List<SensorAdapterDescriptor>
        val filter = aux[4] as DashboardFilterState

        val now = System.currentTimeMillis()
        val windowMs = filter.timeWindowHours * 3600_000L
        val q = filter.searchQuery.trim()
        val sosList = (sosState as? DataUiState.Success)?.data.orEmpty().filter {
            (filter.teamFilter == "all" || it.teamCode == filter.teamFilter) &&
                (windowMs == 0L || (now - (it.createdAt?.toDate()?.time ?: now)) <= windowMs) &&
                (q.isBlank() || it.description.contains(q, true) || it.incidentId.contains(q, true))
        }
        val regList = (regState as? DataUiState.Success)?.data.orEmpty()
        val sigList = (sigState as? DataUiState.Success)?.data.orEmpty()
        val teamList = (teamState as? DataUiState.Success)?.data.orEmpty()
        val locList = (locState as? DataUiState.Success)?.data.orEmpty().filter {
            filter.hazardFilter == "all" || it.hazardType == filter.hazardFilter
        }
        val missionList = (missionsStateVal as? DataUiState.Success)?.data.orEmpty()
        val roadList = (roadsStateVal as? DataUiState.Success)?.data.orEmpty()
        val summaries = SensorFusionProcessor.summarizeSectors(sigList)

        val ackLatencies = sosList.mapNotNull { inc ->
            val createdMs = inc.createdAt?.toDate()?.time
            if (createdMs != null && inc.acknowledgedAtMillis > createdMs) {
                (inc.acknowledgedAtMillis - createdMs) / 60_000.0
            } else null
        }

        OperationalAnalytics(
            activeIncidentsCount = sosList.count { it.status !in listOf("resolved", "closed") },
            unacknowledgedSosCount = sosList.count { it.status in listOf("created", "pending_delivery", "delivered") },
            avgAckLatencyMinutes = if (ackLatencies.isNotEmpty()) ackLatencies.average() else null,
            disasterLocationsCount = locList.size,
            verifiedRegistryCount = regList.count { it.verificationStatus == "verified" },
            verifiedSurvivorCount = regList.count { it.category == "verified_survivor" && it.verificationStatus == "verified" },
            missingPersonsCount = regList.count { it.category == "missing_person" && it.rescueStatus != "resolved" },
            medicalRequestsCount = regList.count { it.category == "medical_request" && it.rescueStatus != "resolved" },
            estimatedSignalsCount = sigList.count { it.verificationStatus in listOf("estimated", "unverified") },
            estimatedSurvivorSum = summaries.sumOf { it.combinedMaxCount },
            fieldVerifiedSignalsCount = sigList.count { it.verificationStatus == "field_verified" },
            deployedTeamsCount = teamList.count { it.status == "deployed" || it.status == "active" },
            staleSensorsCount = sigList.count {
                it.verificationStatus == "outdated" ||
                    (it.observationTimestampMillis > 0L && now - it.observationTimestampMillis > SensorFusionProcessor.STALE_THRESHOLD_MILLIS)
            },
            contradictionAlertsCount = summaries.count { it.hasContradiction },
            activeMissionsCount = missionList.count { it.status !in listOf("completed", "aborted") },
            blockedRoadsCount = roadList.count { it.active },
            onlineSensorsCount = adapters.count { it.sensorHealth == "healthy" },
            offlineSensorsCount = adapters.count { it.sensorHealth != "healthy" },
            deadLetterSyncCount = syncOps.count { it.syncStatus == SyncStatus.DEAD_LETTER.value },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), OperationalAnalytics())

    private val _mapLayers = MutableStateFlow(MapLayerConfig())
    val mapLayers: StateFlow<MapLayerConfig> = _mapLayers.asStateFlow()

    private val _mapSearchResults = MutableStateFlow<List<IndiaPlace>>(emptyList())
    val mapSearchResults: StateFlow<List<IndiaPlace>> = _mapSearchResults.asStateFlow()

    private val _activeRoute = MutableStateFlow<RescueRoute?>(null)
    val activeRoute: StateFlow<RescueRoute?> = _activeRoute.asStateFlow()

    private val _selectedScenario = MutableStateFlow(EnvScenario.COLLAPSED_URBAN)
    val selectedScenario: StateFlow<EnvScenario> = _selectedScenario.asStateFlow()

    private val _sweepProgress = MutableStateFlow(0.62f)
    val sweepProgress: StateFlow<Float> = _sweepProgress.asStateFlow()

    private val _isSlamScanning = MutableStateFlow(false)
    val isSlamScanning: StateFlow<Boolean> = _isSlamScanning.asStateFlow()

    private val _slamFrame = MutableStateFlow(
        SensorArchitectureCatalog.generateSlamFrame(EnvScenario.COLLAPSED_URBAN, 0.62f)
    )
    val slamFrame: StateFlow<SlamEnvironmentFrame> = _slamFrame.asStateFlow()

    private val _aiTriageState = MutableStateFlow<AiTriageUiState>(AiTriageUiState.Idle)
    val aiTriageState: StateFlow<AiTriageUiState> = _aiTriageState.asStateFlow()

    private val _aiTriageHistory = MutableStateFlow<List<AiTriageReport>>(emptyList())
    val aiTriageHistory: StateFlow<List<AiTriageReport>> = _aiTriageHistory.asStateFlow()

    private val _aiChatAnswer = MutableStateFlow<String?>(null)
    val aiChatAnswer: StateFlow<String?> = _aiChatAnswer.asStateFlow()

    private val _aiChatLoading = MutableStateFlow(false)
    val aiChatLoading: StateFlow<Boolean> = _aiChatLoading.asStateFlow()

    private val _exportedReportText = MutableStateFlow<String?>(null)
    val exportedReportText: StateFlow<String?> = _exportedReportText.asStateFlow()

    private val _actionStatusMessage = MutableStateFlow<String?>(null)
    val actionStatusMessage: StateFlow<String?> = _actionStatusMessage.asStateFlow()

    private var lastSyncedLocationAtMillis = 0L

    init {
        syncManager.attachExecutor(this)
        viewModelScope.launch {
            offlineCache.ensureDefaultRegionsSeeded()
            val orgRes = repository.ensureWorkspaceInitialized(
                displayName = initialDisplayName,
                preferredOrgId = settings.value.activeOrgId,
            )
            orgRes.onSuccess { resolvedOrgId ->
                _activeOrgId.value = resolvedOrgId
                preferences.setActiveOrgId(resolvedOrgId)
                repository.seedDemoDatasetIfEmpty(resolvedOrgId)
                SurakshaMessagingService.registerCurrentDeviceToken(
                    context = appContext,
                    orgId = resolvedOrgId,
                    notificationsEnabled = settings.value.notificationsEnabled,
                )
            }.onFailure { err ->
                _actionStatusMessage.value = "Workspace init error: ${err.localizedMessage}"
            }
        }
        viewModelScope.launch {
            combine(currentOrganization, currentMemberRole) { orgState, role ->
                (orgState as? DataUiState.Success)?.data to role
            }.collect { (org, role) ->
                if (org != null && org.id.isNotBlank()) {
                    offlineCache.recordJoinedOrganization(
                        orgId = org.id,
                        orgName = org.orgName,
                        orgCode = org.orgCode,
                        role = role.value,
                    )
                }
            }
        }
        // Real-time continuous GPS push to assigned/active responder team when opted in
        viewModelScope.launch {
            locationFixState
                .collect { fix ->
                    if (fix is LocationFixState.Available) {
                        val now = System.currentTimeMillis()
                        if (now - lastSyncedLocationAtMillis >= 15_000L) {
                            lastSyncedLocationAtMillis = now
                            pushDeviceLocationToTeam(fix)
                        }
                    }
                }
        }
        // Synthetic SLAM progression ticker (strictly demo/simulated)
        viewModelScope.launch {
            while (true) {
                delay(750L)
                if (_isSlamScanning.value) {
                    val next = if (_sweepProgress.value >= 0.98f) 0.25f else _sweepProgress.value + 0.08f
                    _sweepProgress.value = next
                    _slamFrame.value = SensorArchitectureCatalog.generateSlamFrame(_selectedScenario.value, next)
                }
            }
        }
    }

    fun clearStatusMessage() {
        _actionStatusMessage.value = null
    }

    fun clearExportedReport() {
        _exportedReportText.value = null
    }

    fun updateDashboardFilter(transform: (DashboardFilterState) -> DashboardFilterState) {
        _dashboardFilter.value = transform(_dashboardFilter.value)
    }

    fun updateMapLayers(transform: (MapLayerConfig) -> MapLayerConfig) {
        _mapLayers.value = transform(_mapLayers.value)
    }

    fun searchMapPlaces(query: String) {
        viewModelScope.launch {
            val allowNet = !settings.value.offlineMapMode && networkState.value != NetworkState.OFFLINE
            _mapSearchResults.value = tileManager.searchLocation(query, allowNet)
        }
    }

    fun clearMapSearch() {
        _mapSearchResults.value = emptyList()
    }

    fun selectEnvScenario(scenario: EnvScenario) {
        _selectedScenario.value = scenario
        _slamFrame.value = SensorArchitectureCatalog.generateSlamFrame(scenario, _sweepProgress.value)
    }

    fun setSweepProgress(progress: Float) {
        _sweepProgress.value = progress.coerceIn(0.15f, 1.0f)
        _slamFrame.value = SensorArchitectureCatalog.generateSlamFrame(_selectedScenario.value, _sweepProgress.value)
    }

    fun toggleSlamScanning() {
        _isSlamScanning.value = !_isSlamScanning.value
    }

    fun saveAndExportSlamSession(asCsv: Boolean) {
        val frame = _slamFrame.value
        val exported = if (asCsv) SlamExportFormatter.toCsv(frame) else SlamExportFormatter.toJson(frame)
        viewModelScope.launch {
            offlineCache.saveSlamSession(
                SlamSessionEntity(
                    sessionId = "slam_${UUID.randomUUID().toString().replace("-", "").take(10)}",
                    orgId = _activeOrgId.value,
                    scenario = frame.scenario.name,
                    sweepProgress = _sweepProgress.value,
                    gridSize = frame.gridSize,
                    cellSizeMeters = frame.cellSizeMeters,
                    obstacleCount = frame.obstacleCount,
                    voidCount = frame.voidCount,
                    thermalCount = frame.thermalCount,
                    sourceKind = "simulated",
                    uncertaintyNote = "±${String.format(Locale.US, "%.1f", frame.uncertaintyMeters)}m (Synthetic Grid)",
                    exportedJson = exported.take(4000),
                    createdAtMillis = System.currentTimeMillis(),
                )
            )
            _exportedReportText.value = exported
            _actionStatusMessage.value = "SLAM session saved locally (${if (asCsv) "CSV" else "JSON"})."
        }
    }

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch { preferences.setDemoMode(enabled) }
    }

    fun setOfflineMapMode(enabled: Boolean) {
        viewModelScope.launch { preferences.setOfflineMapMode(enabled) }
    }

    fun setRevealSensitivePii(reveal: Boolean) {
        if (reveal && !currentMemberRole.value.canRevealSensitivePii()) {
            _actionStatusMessage.value = "RBAC Denied: Only Org Admin, Team Leader, or Medic may reveal masked contact details."
            return
        }
        viewModelScope.launch { preferences.setRevealSensitivePii(reveal) }
    }

    fun setDarkThemeEnabled(dark: Boolean) {
        viewModelScope.launch { preferences.setDarkThemeEnabled(dark) }
    }

    fun setLanguageCode(code: String) {
        viewModelScope.launch { preferences.setLanguageCode(code) }
    }

    fun setHardwareGatewayUrl(url: String) {
        viewModelScope.launch {
            preferences.setHardwareGatewayUrl(url)
            _actionStatusMessage.value = if (url.isBlank()) "Edge gateway URL cleared." else "Saved edge gateway URL."
        }
    }

    fun setHardwareGatewayToken(token: String) {
        viewModelScope.launch {
            preferences.setHardwareGatewayToken(token)
            _actionStatusMessage.value = if (token.isBlank()) "Gateway Bearer token cleared." else "Saved gateway Bearer token."
        }
    }

    fun setSlamGatewayUrl(url: String) {
        viewModelScope.launch {
            preferences.setSlamGatewayUrl(url)
            _actionStatusMessage.value = if (url.isBlank()) "External SLAM URL cleared." else "Saved external SLAM URL."
        }
    }

    fun setCustomTileServerUrl(url: String) {
        viewModelScope.launch {
            preferences.setCustomTileServerUrl(url)
            _actionStatusMessage.value = if (url.isBlank()) "Custom tile server URL cleared." else "Saved custom tile server URL."
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setNotificationsEnabled(enabled)
            val orgId = _activeOrgId.value
            if (orgId.isNotBlank()) {
                SurakshaMessagingService.registerCurrentDeviceToken(appContext, orgId, enabled)
            }
            _actionStatusMessage.value = if (enabled) "SOS push notifications enabled." else "SOS push notifications muted for this device."
        }
    }

    fun startOfflineRegionDownload(region: OfflineMapRegionEntity) {
        if (networkState.value == NetworkState.OFFLINE) {
            _actionStatusMessage.value = "Cannot download tiles while device is offline."
            return
        }
        tileManager.startRegionDownload(
            scope = viewModelScope,
            region = region,
            layerKind = if (_mapLayers.value.showTerrain) "topo" else "osm",
            customTileTemplateUrl = settings.value.customTileServerUrl,
        )
        _actionStatusMessage.value = "Downloading policy-compliant map tiles for ${region.title}..."
    }

    fun cancelOfflineRegionDownload(regionId: String) {
        tileManager.cancelRegionDownload(regionId)
        _actionStatusMessage.value = "Region download paused; partial tiles kept for resume."
    }

    fun deleteOfflineRegion(regionId: String) {
        viewModelScope.launch {
            tileManager.deleteRegionCache(regionId)
            _actionStatusMessage.value = "Deleted local cached map tiles for region."
        }
    }

    fun computeRouteToSite(targetLat: Double, targetLon: Double) {
        val fix = locationFixState.value as? LocationFixState.Available
        val fallbackTeam = (rescueTeamsState.value as? DataUiState.Success)?.data
            ?.firstOrNull { it.locationSharingOptIn && it.hasValidLocation }
        val fromLat = fix?.latitude ?: fallbackTeam?.latitude ?: 28.6139
        val fromLon = fix?.longitude ?: fallbackTeam?.longitude ?: 77.2090
        val blocked = (blockedRoadsState.value as? DataUiState.Success)?.data.orEmpty()
        val allowNet = !settings.value.offlineMapMode && networkState.value != NetworkState.OFFLINE
        viewModelScope.launch {
            val route = tileManager.computeRescueRoute(
                fromLat = fromLat,
                fromLon = fromLon,
                toLat = targetLat,
                toLon = targetLon,
                blockedRoads = blocked,
                allowNetwork = allowNet,
            )
            _activeRoute.value = route
            _actionStatusMessage.value = "${route.sourceLabel}: ${String.format(Locale.US, "%.1f", route.distanceKm)} km"
        }
    }

    fun clearActiveRoute() {
        _activeRoute.value = null
    }

    fun createOrganization(orgName: String, orgCode: String, region: String) {
        viewModelScope.launch {
            val res = repository.createOrganization(
                orgName = orgName,
                orgCode = orgCode,
                region = region,
                creatorDisplayName = initialDisplayName,
                creatorCallsign = "CMD-01",
            )
            res.onSuccess { newOrgId ->
                _activeOrgId.value = newOrgId
                preferences.setActiveOrgId(newOrgId)
                SurakshaMessagingService.registerCurrentDeviceToken(appContext, newOrgId, settings.value.notificationsEnabled)
                _actionStatusMessage.value = "Created organization $orgName ($newOrgId) as Org Admin."
            }.onFailure {
                _actionStatusMessage.value = it.localizedMessage ?: "Failed to create organization."
            }
        }
    }

    fun switchOrganization(targetOrgId: String) {
        viewModelScope.launch {
            val res = repository.switchActiveOrganization(targetOrgId)
            res.onSuccess { switched ->
                _activeOrgId.value = switched
                preferences.setActiveOrgId(switched)
                SurakshaMessagingService.registerCurrentDeviceToken(appContext, switched, settings.value.notificationsEnabled)
                _actionStatusMessage.value = "Switched command workspace to $switched."
            }.onFailure {
                _actionStatusMessage.value = it.localizedMessage ?: "Failed to switch organization."
            }
        }
    }

    fun generateOrgInvite(
        inviteCode: String,
        defaultRole: String,
        expiresInHours: Long = 48L,
        maxUses: Long = 25L,
    ) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.createOrgInvite(orgId, inviteCode, defaultRole, expiresInHours, maxUses)
            _actionStatusMessage.value = res.fold(
                onSuccess = { code -> "Invite code $code active ($defaultRole, ${expiresInHours}h)." },
                onFailure = { it.localizedMessage ?: "Failed to create invite (Admin required)." }
            )
        }
    }

    fun revokeOrgInvite(inviteCode: String) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.revokeOrgInvite(orgId, inviteCode)
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Revoked invite code $inviteCode." },
                onFailure = { it.localizedMessage ?: "Failed to revoke invite." }
            )
        }
    }

    fun joinOrganization(targetOrgId: String, inviteCode: String, callsign: String) {
        viewModelScope.launch {
            val res = repository.joinOrganizationWithInvite(targetOrgId, inviteCode, initialDisplayName, callsign)
            res.onSuccess { joinedId ->
                _activeOrgId.value = joinedId
                preferences.setActiveOrgId(joinedId)
                SurakshaMessagingService.registerCurrentDeviceToken(appContext, joinedId, settings.value.notificationsEnabled)
                _actionStatusMessage.value = "Joined organization $joinedId."
            }.onFailure {
                _actionStatusMessage.value = it.localizedMessage ?: "Failed to join organization."
            }
        }
    }

    fun updateMemberRole(memberUid: String, newRole: OrgRole) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.updateMemberRole(orgId, memberUid, newRole)
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Updated member role to ${newRole.label}." },
                onFailure = { it.localizedMessage ?: "Role change rejected (Org Admin only)." }
            )
        }
    }

    fun updateMyAvailability(availability: String) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.updateMemberAvailability(orgId, availability)
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Duty status set to $availability." },
                onFailure = { it.localizedMessage ?: "Failed to update availability." }
            )
        }
    }

    fun migrateLegacyData() {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.migrateLegacyPersonalDataToOrg(orgId)
            _actionStatusMessage.value = res.fold(
                onSuccess = { count -> if (count == 0) "No legacy personal records found to migrate." else "Migrated $count legacy records into organization." },
                onFailure = { it.localizedMessage ?: "Legacy migration failed." }
            )
        }
    }

    fun seedDemoDataset() {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.seedDemoDatasetForce(orgId)
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Synthetic Demo Mode records added to org." },
                onFailure = { it.localizedMessage ?: "Failed to seed demo data." }
            )
        }
    }

    fun updateVoluntaryLocationSharing(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setLocationConsentEnabled(enabled)
            if (enabled) {
                if (!locationController.hasLocationPermission()) {
                    preferences.setLocationConsentEnabled(false)
                    SurakshaLocationBus.emit(LocationFixState.PermissionDenied())
                    _actionStatusMessage.value = "Grant location permission before enabling continuous GPS."
                    return@launch
                }
                if (!locationController.isDeviceLocationEnabled()) {
                    preferences.setLocationConsentEnabled(false)
                    SurakshaLocationBus.emit(LocationFixState.GpsDisabled())
                    _actionStatusMessage.value = "Enable device GPS / Location services first."
                    return@launch
                }
                locationController.startTracking(useForegroundService = true)
            } else {
                locationController.stopTracking()
            }

            val res = repository.updateLocationSharingPreference(enabled)
            _actionStatusMessage.value = res.fold(
                onSuccess = {
                    if (enabled) "Continuous foreground GPS tracking enabled." else "GPS tracking stopped and location sharing revoked."
                },
                onFailure = { it.localizedMessage ?: "Could not update sharing preference." }
            )
        }
    }

    private suspend fun pushDeviceLocationToTeam(fix: LocationFixState.Available) {
        val orgId = _activeOrgId.value.takeIf { it.isNotBlank() } ?: return
        val teams = (rescueTeamsState.value as? DataUiState.Success)?.data ?: return
        val myTeam = teams.firstOrNull { it.leaderUid == currentUserId && it.locationSharingOptIn }
            ?: teams.firstOrNull { it.userId == currentUserId && it.locationSharingOptIn }
            ?: return

        repository.updateTeamLocation(
            orgId = orgId,
            teamId = myTeam.id,
            optIn = true,
            hasValidLocation = true,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = fix.accuracyMeters.toDouble(),
            timestampMillis = fix.timestampMillis,
            unavailableReason = "",
            batteryPercent = fix.batteryPercent.toLong(),
            connectionStatus = if (networkState.value == NetworkState.OFFLINE) "offline" else "online",
        )
    }

    fun registerRescueTeam(
        teamCode: String,
        teamName: String,
        memberCount: Long,
        activeCount: Long,
        status: String,
        locationSharingOptIn: Boolean,
    ) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        val fix = locationFixState.value as? LocationFixState.Available
        val hasValid = locationSharingOptIn && fix != null
        val reason = when {
            !locationSharingOptIn -> "Location sharing disabled"
            fix == null -> locationFixReasonLabel(locationFixState.value)
            else -> ""
        }
        viewModelScope.launch {
            val team = RescueTeam(
                orgId = orgId,
                userId = currentUserId,
                leaderUid = currentUserId,
                teamCode = teamCode,
                teamName = teamName,
                memberCount = memberCount,
                activeCount = activeCount,
                status = status,
                locationSharingOptIn = locationSharingOptIn,
                hasValidLocation = hasValid,
                latitude = fix?.latitude ?: 0.0,
                longitude = fix?.longitude ?: 0.0,
                accuracyMeters = fix?.accuracyMeters?.toDouble() ?: 0.0,
                locationTimestampMillis = fix?.timestampMillis ?: 0L,
                locationUnavailableReason = reason,
                batteryPercent = SurakshaLocationController.readBatteryPercent(appContext).toLong(),
                connectionStatus = if (networkState.value == NetworkState.OFFLINE) "offline" else "online",
                isSynthetic = settings.value.demoModeEnabled,
            )
            if (networkState.value == NetworkState.OFFLINE) {
                val docId = "team_${UUID.randomUUID().toString().replace("-", "").take(12)}"
                syncManager.enqueuePendingOperation(
                    orgId = orgId,
                    entityType = OP_CREATE_TEAM,
                    operationId = docId,
                    payload = JSONObject().apply {
                        put("docId", docId)
                        put("teamCode", team.teamCode)
                        put("teamName", team.teamName)
                        put("memberCount", team.memberCount)
                        put("activeCount", team.activeCount)
                        put("status", team.status)
                        put("locationSharingOptIn", team.locationSharingOptIn)
                        put("isSynthetic", team.isSynthetic)
                    }
                )
                _actionStatusMessage.value = "Offline: Team $teamCode queued for automatic sync."
                return@launch
            }
            val res = repository.createRescueTeam(orgId, team)
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Team $teamCode saved to Firestore." },
                onFailure = { it.localizedMessage ?: "Failed to register team." }
            )
        }
    }

    fun toggleTeamLocationSharing(team: RescueTeam, optIn: Boolean) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        val fix = locationFixState.value as? LocationFixState.Available
        val valid = optIn && fix != null
        val reason = when {
            !optIn -> "Location sharing paused by operator"
            fix == null -> locationFixReasonLabel(locationFixState.value)
            else -> ""
        }
        viewModelScope.launch {
            val res = repository.updateTeamLocation(
                orgId = orgId,
                teamId = team.id,
                optIn = optIn,
                hasValidLocation = valid,
                latitude = fix?.latitude ?: 0.0,
                longitude = fix?.longitude ?: 0.0,
                accuracyMeters = fix?.accuracyMeters?.toDouble() ?: 0.0,
                timestampMillis = fix?.timestampMillis ?: 0L,
                unavailableReason = reason,
                batteryPercent = SurakshaLocationController.readBatteryPercent(appContext).toLong(),
                connectionStatus = if (networkState.value == NetworkState.OFFLINE) "offline" else "online",
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = {
                    when {
                        !optIn -> "${team.teamCode} location sharing disabled."
                        valid -> "${team.teamCode} updated with live device GPS."
                        else -> "${team.teamCode} opted in, but ${reason.lowercase(Locale.US)}."
                    }
                },
                onFailure = { it.localizedMessage ?: "Failed to update team location." }
            )
        }
    }

    fun registerDisasterLocation(
        name: String,
        region: String,
        hazardType: String,
        severity: String,
        latitude: Double,
        longitude: Double,
        verificationStatus: String,
        dataSource: String,
    ) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val loc = RescueLocation(
                orgId = orgId,
                userId = currentUserId,
                name = name,
                region = region,
                hazardType = hazardType,
                severity = severity,
                latitude = latitude,
                longitude = longitude,
                verificationStatus = verificationStatus,
                dataSource = dataSource,
                isSynthetic = settings.value.demoModeEnabled,
            )
            if (networkState.value == NetworkState.OFFLINE) {
                val docId = "loc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
                syncManager.enqueuePendingOperation(
                    orgId = orgId,
                    entityType = OP_CREATE_LOCATION,
                    operationId = docId,
                    payload = JSONObject().apply {
                        put("docId", docId)
                        put("name", loc.name)
                        put("region", loc.region)
                        put("hazardType", loc.hazardType)
                        put("severity", loc.severity)
                        put("latitude", loc.latitude)
                        put("longitude", loc.longitude)
                        put("verificationStatus", loc.verificationStatus)
                        put("dataSource", loc.dataSource)
                        put("isSynthetic", loc.isSynthetic)
                    }
                )
                _actionStatusMessage.value = "Offline: Disaster site '$name' queued for sync."
                return@launch
            }
            val res = repository.createRescueLocation(orgId, loc)
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Disaster site '$name' saved." },
                onFailure = { it.localizedMessage ?: "Failed to register location." }
            )
        }
    }

    fun logPresenceObservation(
        sectorLabel: String,
        signalType: String,
        minCount: Long,
        maxCount: Long,
        confidence: Long,
        dataSource: String,
    ) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        val fix = locationFixState.value as? LocationFixState.Available
        viewModelScope.launch {
            val res = repository.createPresenceSignal(
                orgId = orgId,
                signal = PresenceSignal(
                    orgId = orgId,
                    userId = currentUserId,
                    locationId = "sector_field",
                    sectorLabel = sectorLabel,
                    signalType = signalType,
                    sourceKind = if (settings.value.demoModeEnabled) "simulated" else "hardware",
                    deviceId = "manual-field-log",
                    calibrationStatus = "nominal",
                    sensorHealth = "healthy",
                    latitude = fix?.latitude ?: 0.0,
                    longitude = fix?.longitude ?: 0.0,
                    hasCoordinates = fix != null,
                    estimatedMinCount = minCount,
                    estimatedMaxCount = maxCount,
                    confidencePercent = confidence,
                    verificationStatus = "estimated",
                    dataSource = dataSource,
                    observationTimestampMillis = System.currentTimeMillis(),
                    isSynthetic = settings.value.demoModeEnabled,
                )
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Estimated presence signal logged (requires field verification)." },
                onFailure = { it.localizedMessage ?: "Failed to log presence signal." }
            )
        }
    }

    fun reviewPresenceSignal(signal: PresenceSignal, newStatus: String, notes: String) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.reviewPresenceSignal(orgId, signal, newStatus, notes)
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Signal marked $newStatus." },
                onFailure = { it.localizedMessage ?: "Signal update rejected." }
            )
        }
    }

    fun findDuplicateRegistryMatch(title: String, locationName: String): RescueRegistryEntry? {
        val existing = (rescueRegistryState.value as? DataUiState.Success)?.data ?: return null
        val normTitle = title.trim().lowercase(Locale.US)
        val normLoc = locationName.trim().lowercase(Locale.US)
        return existing.firstOrNull {
            it.rescueStatus != "resolved" &&
                it.title.trim().lowercase(Locale.US) == normTitle &&
                it.locationName.trim().lowercase(Locale.US) == normLoc
        }
    }

    fun createRegistryEntry(
        category: String,
        title: String,
        locationName: String,
        details: String,
        sensitiveContact: String,
        verificationStatus: String,
        informationSource: String,
        photoUriLocal: String = "",
        photoConsentGranted: Boolean = false,
        duplicateOfId: String = "",
        medicalCategory: String = "",
        medicalUrgency: String = "routine",
        assignedMedicalTeam: String = "",
        linkedIncidentId: String = "",
    ) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        val fix = locationFixState.value as? LocationFixState.Available
        viewModelScope.launch {
            val entry = RescueRegistryEntry(
                orgId = orgId,
                userId = currentUserId,
                category = category,
                title = title,
                locationName = locationName,
                latitude = fix?.latitude ?: 0.0,
                longitude = fix?.longitude ?: 0.0,
                hasCoordinates = fix != null,
                details = details,
                sensitiveContactMasked = sensitiveContact,
                photoUriLocal = photoUriLocal,
                photoConsentGranted = photoConsentGranted,
                rescueStatus = "open",
                verificationStatus = if (duplicateOfId.isNotBlank()) "duplicate" else verificationStatus,
                informationSource = informationSource,
                verificationNotes = if (duplicateOfId.isNotBlank()) "Flagged possible duplicate of $duplicateOfId" else "Logged by authenticated responder.",
                duplicateOfId = duplicateOfId,
                medicalCategory = medicalCategory,
                medicalUrgency = medicalUrgency,
                assignedMedicalTeam = assignedMedicalTeam,
                linkedIncidentId = linkedIncidentId,
                isSynthetic = settings.value.demoModeEnabled,
            )
            if (networkState.value == NetworkState.OFFLINE) {
                val docId = "reg_${UUID.randomUUID().toString().replace("-", "").take(12)}"
                syncManager.enqueuePendingOperation(
                    orgId = orgId,
                    entityType = OP_CREATE_REGISTRY,
                    operationId = docId,
                    payload = JSONObject().apply {
                        put("docId", docId)
                        put("category", entry.category)
                        put("title", entry.title)
                        put("locationName", entry.locationName)
                        put("details", entry.details)
                        put("sensitiveContactMasked", entry.sensitiveContactMasked)
                        put("verificationStatus", entry.verificationStatus)
                        put("informationSource", entry.informationSource)
                        put("medicalCategory", entry.medicalCategory)
                        put("medicalUrgency", entry.medicalUrgency)
                        put("assignedMedicalTeam", entry.assignedMedicalTeam)
                        put("linkedIncidentId", entry.linkedIncidentId)
                        put("isSynthetic", entry.isSynthetic)
                    }
                )
                _actionStatusMessage.value = "Offline: Registry record queued for auto-sync."
                return@launch
            }
            val res = repository.createRegistryEntry(orgId, entry)
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Registry record saved to Firestore." },
                onFailure = { it.localizedMessage ?: "Failed to save registry record." }
            )
        }
    }

    fun advanceRegistryEntry(
        entry: RescueRegistryEntry,
        newRescueStatus: String,
        newVerificationStatus: String,
        verificationNote: String,
    ) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.updateRegistryEntry(
                orgId,
                entry.copy(
                    rescueStatus = newRescueStatus,
                    verificationStatus = newVerificationStatus,
                    verificationNotes = verificationNote,
                )
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Registry entry updated." },
                onFailure = { it.localizedMessage ?: "Failed to update registry entry." }
            )
        }
    }

    fun createSosIncident(
        teamCode: String,
        priority: String,
        description: String,
        includeGps: Boolean,
    ) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        val fix = if (includeGps) locationFixState.value as? LocationFixState.Available else null
        viewModelScope.launch {
            val incident = SosIncident(
                orgId = orgId,
                senderUid = currentUserId,
                senderName = initialDisplayName,
                teamCode = teamCode,
                priority = priority,
                description = description,
                hasLocation = fix != null,
                latitude = fix?.latitude ?: 0.0,
                longitude = fix?.longitude ?: 0.0,
                accuracyMeters = fix?.accuracyMeters?.toDouble() ?: 0.0,
                isSynthetic = settings.value.demoModeEnabled,
            )
            if (networkState.value == NetworkState.OFFLINE) {
                val docId = "sos_${UUID.randomUUID().toString().replace("-", "").take(12)}"
                syncManager.enqueuePendingOperation(
                    orgId = orgId,
                    entityType = OP_CREATE_SOS,
                    operationId = docId,
                    payload = JSONObject().apply {
                        put("docId", docId)
                        put("teamCode", incident.teamCode)
                        put("priority", incident.priority)
                        put("description", incident.description)
                        put("hasLocation", incident.hasLocation)
                        put("latitude", incident.latitude)
                        put("longitude", incident.longitude)
                        put("accuracyMeters", incident.accuracyMeters)
                        put("isSynthetic", incident.isSynthetic)
                    }
                )
                _actionStatusMessage.value = "Offline: SOS incident ($docId) queued locally (Pending Delivery). Requires connectivity for server save."
                return@launch
            }
            val res = repository.createSosIncident(orgId, incident)
            _actionStatusMessage.value = res.fold(
                onSuccess = { id -> "SOS Incident $id saved to Firestore dispatch (server ACK)." },
                onFailure = { it.localizedMessage ?: "Failed to send SOS incident." }
            )
        }
    }

    fun transitionSosIncident(
        incident: SosIncident,
        targetStatus: SosIncidentStatus,
        assignedTeamCode: String = incident.assignedTeamCode,
        note: String = "",
    ) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.transitionSosIncident(
                orgId = orgId,
                incident = incident,
                targetStatus = targetStatus,
                assignedTeamCode = assignedTeamCode,
                note = note,
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { "SOS ${incident.incidentId} transitioned to ${targetStatus.label}." },
                onFailure = { it.localizedMessage ?: "SOS transition denied." }
            )
        }
    }

    fun createMission(
        title: String,
        description: String,
        assignedTeamCode: String,
        priority: String,
        targetLatitude: Double,
        targetLongitude: Double,
        linkedIncidentId: String = "",
    ) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.createMission(
                orgId = orgId,
                mission = RescueMission(
                    orgId = orgId,
                    title = title,
                    description = description,
                    linkedIncidentId = linkedIncidentId,
                    assignedTeamCode = assignedTeamCode,
                    priority = priority,
                    status = "planned",
                    targetLatitude = targetLatitude,
                    targetLongitude = targetLongitude,
                ),
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { id -> "Mission $id dispatched ($assignedTeamCode)." },
                onFailure = { it.localizedMessage ?: "Failed to create mission." }
            )
        }
    }

    fun updateMissionStatus(missionId: String, status: String) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.updateMissionStatus(orgId, missionId, status)
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Mission updated to $status." },
                onFailure = { it.localizedMessage ?: "Mission update failed." }
            )
        }
    }

    fun createBlockedRoad(
        label: String,
        latitude: Double,
        longitude: Double,
        severity: String,
        notes: String,
    ) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.createBlockedRoad(
                orgId = orgId,
                marker = BlockedRoadMarker(
                    orgId = orgId,
                    label = label,
                    latitude = latitude,
                    longitude = longitude,
                    severity = severity,
                    active = true,
                    notes = notes,
                ),
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Flagged road obstacle '$label'." },
                onFailure = { it.localizedMessage ?: "Failed to flag road obstacle." }
            )
        }
    }

    fun upsertDroneTelemetry(
        callsign: String,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        batteryPercent: Long,
        status: String,
        sourceKind: String = "simulated",
    ) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.upsertDroneTelemetry(
                orgId = orgId,
                drone = DroneTelemetry(
                    orgId = orgId,
                    callsign = callsign,
                    sourceKind = sourceKind,
                    status = status,
                    latitude = latitude,
                    longitude = longitude,
                    altitudeMeters = altitudeMeters,
                    batteryPercent = batteryPercent,
                ),
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Updated UAV telemetry for $callsign ($sourceKind)." },
                onFailure = { it.localizedMessage ?: "Failed to update UAV telemetry." }
            )
        }
    }

    fun fetchExternalSlamFrame() {
        val url = settings.value.slamGatewayUrl.ifBlank { settings.value.hardwareGatewayUrl }
        viewModelScope.launch {
            val res = SlamGatewayClient.fetchExternalSlamFrame(url, settings.value.hardwareGatewayToken)
            res.onSuccess { frame ->
                _isSlamScanning.value = false
                _slamFrame.value = frame
                _actionStatusMessage.value = "Loaded live external SLAM grid (${frame.gridSize}x${frame.gridSize}, ${frame.scanCoveragePercent}% covered)."
            }.onFailure { err ->
                _actionStatusMessage.value = err.localizedMessage ?: "External SLAM fetch failed."
            }
        }
    }

    fun testEdgeGatewayHealth() {
        viewModelScope.launch {
            val adapter = WifiHttpGatewayAdapter(
                gatewayBaseUrl = settings.value.hardwareGatewayUrl,
                authToken = settings.value.hardwareGatewayToken,
            )
            val res = adapter.checkHealth()
            _actionStatusMessage.value = res.fold(
                onSuccess = { it },
                onFailure = { it.localizedMessage ?: "Edge gateway health check failed." }
            )
        }
    }

    fun registerGatewayDevice() {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val adapter = WifiHttpGatewayAdapter(
                gatewayBaseUrl = settings.value.hardwareGatewayUrl,
                authToken = settings.value.hardwareGatewayToken,
            )
            val res = adapter.registerDevice("android_${currentUserId.take(8)}", orgId)
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Device registered with Edge FastAPI Gateway." },
                onFailure = { it.localizedMessage ?: "Edge gateway device registration failed." }
            )
        }
    }

    fun pollSensorAdapter(adapterDescriptor: SensorAdapterDescriptor) {
        val orgId = _activeOrgId.value
        viewModelScope.launch {
            val adapter = when (adapterDescriptor.nodeId) {
                "gw-http-fastapi" -> WifiHttpGatewayAdapter(
                    gatewayBaseUrl = settings.value.hardwareGatewayUrl,
                    authToken = settings.value.hardwareGatewayToken,
                )
                "hw-ble-gatt-01" -> BleGattSensorAdapter(appContext)
                else -> SensorArchitectureCatalog.buildAdapters(
                    context = appContext,
                    httpGatewayUrl = settings.value.hardwareGatewayUrl,
                    httpGatewayToken = settings.value.hardwareGatewayToken,
                ).firstOrNull { it.descriptor.nodeId == adapterDescriptor.nodeId }
            }
            if (adapter == null) {
                _actionStatusMessage.value = "Adapter ${adapterDescriptor.nodeId} unavailable."
                return@launch
            }
            val pollResult = adapter.pollTelemetryPackets()
            pollResult.onFailure { err ->
                _actionStatusMessage.value = err.localizedMessage ?: "Sensor poll failed."
                return@launch
            }
            val packets = pollResult.getOrThrow()
            val fused = SensorFusionProcessor.processPackets(packets, orgId, currentUserId)
            fused.signals.forEach { sig ->
                offlineCache.recordSensorSnapshot(
                    nodeId = sig.deviceId,
                    sensorType = sig.signalType,
                    summary = "${sig.sectorLabel} (${sig.verificationStatus}, ${sig.confidencePercent}%)",
                    confidencePercent = sig.confidencePercent.toInt(),
                    isSynthetic = sig.isSynthetic,
                    sourceKind = sig.sourceKind,
                    calibrationStatus = sig.calibrationStatus,
                    sensorHealth = sig.sensorHealth,
                )
                if (orgId.isNotBlank() && networkState.value != NetworkState.OFFLINE) {
                    repository.createPresenceSignal(orgId, sig)
                }
            }
            val contradictionMsg = if (fused.contradictionCount > 0) " • ${fused.contradictionCount} contradiction(s)!" else ""
            _actionStatusMessage.value = "Processed ${fused.signals.size} packet(s) (${fused.deduplicatedCount} deduplicated, ${fused.staleCount} stale$contradictionMsg)."
        }
    }

    fun flushOfflineQueueNow() {
        viewModelScope.launch {
            val (synced, failed) = syncManager.flushPendingQueue(forceIgnoreBackoff = true)
            _actionStatusMessage.value = "Sync complete: $synced uploaded, $failed failed."
        }
    }

    fun retryPendingSyncOperation(operationId: String) {
        viewModelScope.launch {
            syncManager.retryOperationNow(operationId)
            _actionStatusMessage.value = "Retried sync operation $operationId."
        }
    }

    fun runAiTriage() {
        val locations = (rescueLocationsState.value as? DataUiState.Success)?.data ?: emptyList()
        val signals = (presenceSignalsState.value as? DataUiState.Success)?.data ?: emptyList()
        val teams = (rescueTeamsState.value as? DataUiState.Success)?.data ?: emptyList()
        val registry = (rescueRegistryState.value as? DataUiState.Success)?.data ?: emptyList()
        val incidents = (sosIncidentsState.value as? DataUiState.Success)?.data ?: emptyList()
        val lang = settings.value.languageCode

        viewModelScope.launch {
            _aiTriageState.value = AiTriageUiState.Loading
            val result = aiRepository.generateSituationTriage(
                locations = locations,
                signals = signals,
                teams = teams,
                registry = registry,
                incidents = incidents,
                isDemoMode = settings.value.demoModeEnabled,
                languageCode = lang,
            )
            _aiTriageState.value = result.fold(
                onSuccess = { report ->
                    _aiTriageHistory.value = (listOf(report) + _aiTriageHistory.value).take(10)
                    val orgId = _activeOrgId.value
                    if (orgId.isNotBlank()) {
                        repository.logOrgActivity(
                            orgId = orgId,
                            actorName = initialDisplayName,
                            category = "ai",
                            summary = "Generated Gemini triage (${report.modelUsed}, $lang)",
                            targetId = "ai_triage_${report.generatedAtMillis}",
                        )
                    }
                    AiTriageUiState.Success(report)
                },
                onFailure = { AiTriageUiState.Error(it.localizedMessage ?: "AI triage failed.") }
            )
        }
    }

    fun askAiQuestion(question: String) {
        val locations = (rescueLocationsState.value as? DataUiState.Success)?.data ?: emptyList()
        val signals = (presenceSignalsState.value as? DataUiState.Success)?.data ?: emptyList()
        val teams = (rescueTeamsState.value as? DataUiState.Success)?.data ?: emptyList()
        val registry = (rescueRegistryState.value as? DataUiState.Success)?.data ?: emptyList()
        val incidents = (sosIncidentsState.value as? DataUiState.Success)?.data ?: emptyList()
        val lang = settings.value.languageCode

        viewModelScope.launch {
            _aiChatLoading.value = true
            val result = aiRepository.askOperationalQuestion(
                question = question,
                locations = locations,
                signals = signals,
                teams = teams,
                registry = registry,
                incidents = incidents,
                isDemoMode = settings.value.demoModeEnabled,
                languageCode = lang,
            )
            _aiChatLoading.value = false
            _aiChatAnswer.value = result.fold(
                onSuccess = { it },
                onFailure = { "AI Error: ${it.localizedMessage ?: "Request failed"}" }
            )
        }
    }

    fun exportOperationalCsvReport() {
        val locations = (rescueLocationsState.value as? DataUiState.Success)?.data.orEmpty()
        val incidents = (sosIncidentsState.value as? DataUiState.Success)?.data.orEmpty()
        val registry = (rescueRegistryState.value as? DataUiState.Success)?.data.orEmpty()
        val signals = (presenceSignalsState.value as? DataUiState.Success)?.data.orEmpty()
        val report = buildString {
            appendLine("# PROJECT SURAKSHA OPERATIONAL EXPORT")
            appendLine("org_id,${_activeOrgId.value}")
            appendLine("demo_mode,${settings.value.demoModeEnabled}")
            appendLine("type,id,title_or_sector,status_or_verification,location,is_synthetic")
            incidents.forEach {
                appendLine("SOS_INCIDENT,${it.incidentId},\"${it.description.take(60)}\",${it.status},${it.teamCode},${it.isSynthetic}")
            }
            locations.forEach {
                appendLine("DISASTER_SITE,${it.id},\"${it.name}\",${it.verificationStatus},\"${it.latitude},${it.longitude}\",${it.isSynthetic}")
            }
            signals.forEach {
                appendLine("PRESENCE_SIGNAL,${it.id},\"${it.sectorLabel}\",${it.verificationStatus},${it.sourceKind},${it.isSynthetic}")
            }
            registry.forEach {
                appendLine("REGISTRY_${it.category},${it.id},\"${it.title}\",${it.verificationStatus},\"${it.locationName}\",${it.isSynthetic}")
            }
        }
        _exportedReportText.value = report
    }

    override suspend fun executeOperation(op: PendingSyncOperationEntity): Result<Unit> {
        val json = JSONObject(op.payloadJson)
        val docId = json.optString("docId").takeIf { it.isNotBlank() } ?: op.operationId
        return when (op.entityType) {
            OP_CREATE_LOCATION -> repository.createRescueLocation(
                op.orgId,
                RescueLocation(
                    orgId = op.orgId,
                    userId = currentUserId,
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
            OP_CREATE_TEAM -> repository.createRescueTeam(
                op.orgId,
                RescueTeam(
                    orgId = op.orgId,
                    userId = currentUserId,
                    leaderUid = currentUserId,
                    teamCode = json.optString("teamCode"),
                    teamName = json.optString("teamName"),
                    memberCount = json.optLong("memberCount", 1L),
                    activeCount = json.optLong("activeCount", 1L),
                    status = json.optString("status", "standby"),
                    locationSharingOptIn = json.optBoolean("locationSharingOptIn", false),
                    locationUnavailableReason = "Synced from offline queue",
                    isSynthetic = json.optBoolean("isSynthetic", true),
                ),
                explicitId = docId,
            ).map { }
            OP_CREATE_REGISTRY -> repository.createRegistryEntry(
                op.orgId,
                RescueRegistryEntry(
                    orgId = op.orgId,
                    userId = currentUserId,
                    category = json.optString("category"),
                    title = json.optString("title"),
                    locationName = json.optString("locationName"),
                    details = json.optString("details"),
                    sensitiveContactMasked = json.optString("sensitiveContactMasked"),
                    verificationStatus = json.optString("verificationStatus", "reported"),
                    informationSource = json.optString("informationSource", "Offline Field Queue"),
                    medicalCategory = json.optString("medicalCategory", ""),
                    medicalUrgency = json.optString("medicalUrgency", "routine"),
                    assignedMedicalTeam = json.optString("assignedMedicalTeam", ""),
                    linkedIncidentId = json.optString("linkedIncidentId", ""),
                    isSynthetic = json.optBoolean("isSynthetic", true),
                ),
                explicitId = docId,
            ).map { }
            OP_CREATE_SOS -> repository.createSosIncident(
                op.orgId,
                SosIncident(
                    orgId = op.orgId,
                    senderUid = currentUserId,
                    senderName = initialDisplayName,
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
            else -> Result.failure(IllegalArgumentException("Unknown pending operation ${op.entityType}"))
        }
    }

    private fun locationFixReasonLabel(state: LocationFixState): String = when (state) {
        is LocationFixState.PermissionDenied -> state.reason
        is LocationFixState.GpsDisabled -> state.reason
        is LocationFixState.OptedOut -> state.reason
        is LocationFixState.Searching -> "Acquiring GNSS/network fix"
        is LocationFixState.Unavailable -> state.reason
        is LocationFixState.Available -> ""
    }

    private fun <T> Flow<T>.toUiState(label: String): StateFlow<DataUiState<T>> =
        this.map<T, DataUiState<T>> { DataUiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing $label", rootError)
                emit(DataUiState.Error(rootError.message ?: "Failed to load $label"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = DataUiState.Loading,
            )

    override fun onCleared() {
        networkMonitor.unregister()
        super.onCleared()
    }

    private companion object {
        const val TAG = "SurakshaVM"
        const val OP_CREATE_LOCATION = "create_location"
        const val OP_CREATE_TEAM = "create_team"
        const val OP_CREATE_REGISTRY = "create_registry"
        const val OP_CREATE_SOS = "create_sos"
    }
}
