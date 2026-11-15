package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.SurakshaAiRepository
import com.example.data.hardware.BleGattSensorAdapter
import com.example.data.hardware.EnvScenario
import com.example.data.hardware.SensorAdapterDescriptor
import com.example.data.hardware.SensorArchitectureCatalog
import com.example.data.hardware.SensorFusionProcessor
import com.example.data.hardware.SlamEnvironmentFrame
import com.example.data.hardware.SlamExportFormatter
import com.example.data.hardware.WifiHttpGatewayAdapter
import com.example.data.local.CachedSensorLogEntity
import com.example.data.local.OfflineCacheRepository
import com.example.data.local.OfflineMapRegionEntity
import com.example.data.local.PendingSyncOperationEntity
import com.example.data.local.SlamSessionEntity
import com.example.data.local.SurakshaPreferences
import com.example.data.local.SurakshaSettings
import com.example.data.map.IndiaPlace
import com.example.data.map.OsmTileManager
import com.example.data.map.RegionDownloadProgress
import com.example.data.model.AiTriageReport
import com.example.data.model.MapLayerConfig
import com.example.data.model.OrgInvite
import com.example.data.model.OrgMember
import com.example.data.model.OrgRole
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueOrganization
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.example.data.model.SosIncident
import com.example.data.model.SosIncidentStatus
import com.example.data.model.UserProfile
import com.example.data.network.NetworkMonitor
import com.example.data.network.NetworkState
import com.example.data.location.LocationFixState
import com.example.data.location.SurakshaLocationBus
import com.example.data.location.SurakshaLocationController
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val verifiedRegistryCount: Int = 0,
    val estimatedSignalsCount: Int = 0,
    val fieldVerifiedSignalsCount: Int = 0,
    val deployedTeamsCount: Int = 0,
    val staleSensorsCount: Int = 0,
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
            mine?.let { OrgRole.fromValue(it.role) } ?: OrgRole.OPERATOR
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), OrgRole.OPERATOR)

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

    val rescueRegistryState: StateFlow<DataUiState<List<RescueRegistryEntry>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeRescueRegistry(orgId).toUiState("rescue registry")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    val sosIncidentsState: StateFlow<DataUiState<List<SosIncident>>> =
        activeOrgId.flatMapLatest { orgId ->
            if (orgId.isBlank()) flowOf(DataUiState.Loading) else repository.observeSosIncidents(orgId).toUiState("SOS incidents")
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
    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing
    val lastSuccessfulSyncMillis: StateFlow<Long> = syncManager.lastSuccessfulSyncMillis

    val cachedSensorLogs: StateFlow<List<CachedSensorLogEntity>> = offlineCache.recentSensorLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    val slamSessions: StateFlow<List<SlamSessionEntity>> = offlineCache.slamSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _dashboardFilter = MutableStateFlow(DashboardFilterState())
    val dashboardFilter: StateFlow<DashboardFilterState> = _dashboardFilter.asStateFlow()

    val analytics: StateFlow<OperationalAnalytics> = combine(
        sosIncidentsState,
        rescueRegistryState,
        presenceSignalsState,
        rescueTeamsState,
        _dashboardFilter,
    ) { sosState, regState, sigState, teamState, filter ->
        val now = System.currentTimeMillis()
        val windowMs = filter.timeWindowHours * 3600_000L
        val sosList = (sosState as? DataUiState.Success)?.data.orEmpty().filter {
            (filter.teamFilter == "all" || it.teamCode == filter.teamFilter) &&
                (windowMs == 0L || (now - (it.createdAt?.toDate()?.time ?: now)) <= windowMs)
        }
        val regList = (regState as? DataUiState.Success)?.data.orEmpty()
        val sigList = (sigState as? DataUiState.Success)?.data.orEmpty()
        val teamList = (teamState as? DataUiState.Success)?.data.orEmpty()

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
            verifiedRegistryCount = regList.count { it.verificationStatus == "verified" },
            estimatedSignalsCount = sigList.count { it.verificationStatus in listOf("estimated", "unverified") },
            fieldVerifiedSignalsCount = sigList.count { it.verificationStatus == "field_verified" },
            deployedTeamsCount = teamList.count { it.status == "deployed" || it.status == "active" },
            staleSensorsCount = sigList.count {
                it.verificationStatus == "outdated" ||
                    (it.observationTimestampMillis > 0L && now - it.observationTimestampMillis > SensorFusionProcessor.STALE_THRESHOLD_MILLIS)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), OperationalAnalytics())

    private val _mapLayers = MutableStateFlow(MapLayerConfig())
    val mapLayers: StateFlow<MapLayerConfig> = _mapLayers.asStateFlow()

    private val _mapSearchResults = MutableStateFlow<List<IndiaPlace>>(emptyList())
    val mapSearchResults: StateFlow<List<IndiaPlace>> = _mapSearchResults.asStateFlow()

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

    val sensorAdapters: StateFlow<List<SensorAdapterDescriptor>> = settings
        .map { s -> SensorArchitectureCatalog.buildAdapters(appContext, s.hardwareGatewayUrl).map { it.descriptor } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _aiTriageState = MutableStateFlow<AiTriageUiState>(AiTriageUiState.Idle)
    val aiTriageState: StateFlow<AiTriageUiState> = _aiTriageState.asStateFlow()

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
            }.onFailure { err ->
                _actionStatusMessage.value = "Workspace init error: ${err.localizedMessage}"
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

    fun startOfflineRegionDownload(region: OfflineMapRegionEntity) {
        if (networkState.value == NetworkState.OFFLINE) {
            _actionStatusMessage.value = "Cannot download tiles while device is offline."
            return
        }
        tileManager.startRegionDownload(viewModelScope, region, layerKind = if (_mapLayers.value.showTerrain) "topo" else "osm")
        _actionStatusMessage.value = "Downloading real OSM tiles for ${region.title}..."
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
                _actionStatusMessage.value = "Created organization $orgName ($newOrgId) as Org Admin."
            }.onFailure {
                _actionStatusMessage.value = it.localizedMessage ?: "Failed to create organization."
            }
        }
    }

    fun generateOrgInvite(inviteCode: String, defaultRole: String) {
        val orgId = _activeOrgId.value
        if (orgId.isBlank()) return
        viewModelScope.launch {
            val res = repository.createOrgInvite(orgId, inviteCode, defaultRole)
            _actionStatusMessage.value = res.fold(
                onSuccess = { code -> "Invite code $code active ($defaultRole)." },
                onFailure = { it.localizedMessage ?: "Failed to create invite (Admin required)." }
            )
        }
    }

    fun joinOrganization(targetOrgId: String, inviteCode: String, callsign: String) {
        viewModelScope.launch {
            val res = repository.joinOrganizationWithInvite(targetOrgId, inviteCode, initialDisplayName, callsign)
            res.onSuccess { joinedId ->
                _activeOrgId.value = joinedId
                preferences.setActiveOrgId(joinedId)
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
            if (enabled) {
                if (!locationController.hasLocationPermission()) {
                    SurakshaLocationBus.emit(LocationFixState.PermissionDenied())
                    _actionStatusMessage.value = "Grant location permission before enabling continuous GPS."
                    return@launch
                }
                if (!locationController.isDeviceLocationEnabled()) {
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

    fun pollSensorAdapter(adapterDescriptor: SensorAdapterDescriptor) {
        val orgId = _activeOrgId.value
        viewModelScope.launch {
            val adapter = when (adapterDescriptor.nodeId) {
                "gw-http-fastapi" -> WifiHttpGatewayAdapter(settings.value.hardwareGatewayUrl)
                "hw-ble-gatt-01" -> BleGattSensorAdapter(appContext)
                else -> SensorArchitectureCatalog.buildAdapters(appContext, settings.value.hardwareGatewayUrl)
                    .firstOrNull { it.descriptor.nodeId == adapterDescriptor.nodeId }
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
            _actionStatusMessage.value = "Processed ${fused.signals.size} packet(s) (${fused.deduplicatedCount} deduplicated, ${fused.staleCount} stale)."
        }
    }

    fun flushOfflineQueueNow() {
        viewModelScope.launch {
            val (synced, failed) = syncManager.flushPendingQueue()
            _actionStatusMessage.value = "Sync complete: $synced uploaded, $failed failed."
        }
    }

    fun runAiTriage() {
        val locations = (rescueLocationsState.value as? DataUiState.Success)?.data ?: emptyList()
        val signals = (presenceSignalsState.value as? DataUiState.Success)?.data ?: emptyList()
        val teams = (rescueTeamsState.value as? DataUiState.Success)?.data ?: emptyList()
        val registry = (rescueRegistryState.value as? DataUiState.Success)?.data ?: emptyList()
        val incidents = (sosIncidentsState.value as? DataUiState.Success)?.data ?: emptyList()

        viewModelScope.launch {
            _aiTriageState.value = AiTriageUiState.Loading
            val result = aiRepository.generateSituationTriage(
                locations = locations,
                signals = signals,
                teams = teams,
                registry = registry,
                incidents = incidents,
                isDemoMode = settings.value.demoModeEnabled,
            )
            _aiTriageState.value = result.fold(
                onSuccess = { AiTriageUiState.Success(it) },
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
