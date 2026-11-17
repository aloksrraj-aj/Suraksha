package com.example.ui.viewmodel

import android.content.Context
import android.os.BatteryManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.hardware.DevDemoFixtureProvider
import com.example.data.hardware.GatewayTransportConfig
import com.example.data.hardware.GatewayTransportKind
import com.example.data.hardware.HttpTlsGatewayAdapter
import com.example.data.hardware.MqttGatewayContract
import com.example.data.hardware.SensorAdapterDescriptor
import com.example.data.hardware.SensorArchitectureCatalog
import com.example.data.hardware.SensorEventV1Parser
import com.example.data.hardware.WebSocketGatewayAdapter
import com.example.data.local.CachedMemberLocationEntity
import com.example.data.local.CachedSensorEventEntity
import com.example.data.local.CachedSensorHealthEntity
import com.example.data.local.JoinedOrgEntity
import com.example.data.local.OfflineCacheRepository
import com.example.data.local.OfflineMapRegionEntity
import com.example.data.local.PendingSyncOperationEntity
import com.example.data.local.SecureTokenStorage
import com.example.data.local.SurakshaPreferences
import com.example.data.local.SurakshaSettings
import com.example.data.local.SyncHistoryEntity
import com.example.data.location.CompassHeadingProvider
import com.example.data.location.HeadingState
import com.example.data.location.LocationFixState
import com.example.data.location.RadarGeoMath
import com.example.data.location.SeparationAlertEvaluator
import com.example.data.location.SurakshaLocationController
import com.example.data.map.IndiaPlace
import com.example.data.map.OsmTileManager
import com.example.data.map.RegionDownloadProgress
import com.example.data.model.AuditLog
import com.example.data.model.BlockedMembership
import com.example.data.model.DetectionObservation
import com.example.data.model.EnvironmentMode
import com.example.data.model.GatewayDevice
import com.example.data.model.Invitation
import com.example.data.model.MapLayerConfig
import com.example.data.model.MemberLocation
import com.example.data.model.Mission
import com.example.data.model.MissionMember
import com.example.data.model.MissionStatus
import com.example.data.model.OrgMember
import com.example.data.model.OrgRole
import com.example.data.model.RadarTarget
import com.example.data.model.RescueOrganization
import com.example.data.model.RescueTeam
import com.example.data.model.SensorDevice
import com.example.data.model.SensorEvent
import com.example.data.model.SensorHealth
import com.example.data.model.SeparationWarningItem
import com.example.data.model.SosAlert
import com.example.data.model.SosAlertStatus
import com.example.data.model.UserProfile
import com.example.data.network.NetworkMonitor
import com.example.data.network.NetworkState
import com.example.data.repository.SurakshaRepository
import com.example.data.repository.UiState
import com.example.data.sync.OfflineSyncManager
import com.example.data.sync.SyncOperationExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class SurakshaViewModel(
    private val appContext: Context,
    private val repository: SurakshaRepository,
    private val offlineCache: OfflineCacheRepository,
    val tileManager: OsmTileManager,
    private val networkMonitor: NetworkMonitor,
    private val syncManager: OfflineSyncManager,
    private val locationController: SurakshaLocationController,
    private val preferences: SurakshaPreferences,
    val tokenStorage: SecureTokenStorage,
    val currentUserId: String,
    private val initialDisplayName: String,
    private val initialEmail: String,
    private val initialPhotoUrl: String,
) : ViewModel() {

    private val compassProvider = CompassHeadingProvider(appContext)
    private val separationEvaluator = SeparationAlertEvaluator()
    private val sensorParser = SensorEventV1Parser()
    private val httpGateway = HttpTlsGatewayAdapter(sensorParser)
    private val wsGateway = WebSocketGatewayAdapter(sensorParser)
    private val mqttGateway = MqttGatewayContract()

    val settings: StateFlow<SurakshaSettings> = preferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SurakshaSettings())

    val networkState: StateFlow<NetworkState> = networkMonitor.networkState
    val locationFixState: StateFlow<LocationFixState> = locationController.locationState
    val headingState: StateFlow<HeadingState> = compassProvider.headingState

    private val _activeOrgId = MutableStateFlow("")
    val activeOrgId: StateFlow<String> = _activeOrgId.asStateFlow()

    private val _activeTeamId = MutableStateFlow("")
    val activeTeamId: StateFlow<String> = _activeTeamId.asStateFlow()

    private val _activeMissionId = MutableStateFlow("")
    val activeMissionId: StateFlow<String> = _activeMissionId.asStateFlow()

    val userProfileState: StateFlow<UiState<UserProfile?>> =
        repository.observeUserProfile(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    val currentOrganization: StateFlow<UiState<RescueOrganization?>> =
        _activeOrgId.flatMapLatest { repository.observeOrganization(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(null))

    val orgMembersState: StateFlow<UiState<List<OrgMember>>> =
        _activeOrgId.flatMapLatest { repository.observeOrgMembers(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val blockedMembersState: StateFlow<UiState<List<BlockedMembership>>> =
        _activeOrgId.flatMapLatest { repository.observeBlockedMemberships(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val invitationsState: StateFlow<UiState<List<Invitation>>> =
        _activeOrgId.flatMapLatest { repository.observeInvitations(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val teamsState: StateFlow<UiState<List<RescueTeam>>> =
        _activeOrgId.flatMapLatest { repository.observeTeams(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val missionsState: StateFlow<UiState<List<Mission>>> =
        _activeOrgId.flatMapLatest { repository.observeMissions(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val auditLogsState: StateFlow<UiState<List<AuditLog>>> =
        _activeOrgId.flatMapLatest { repository.observeAuditLogs(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val gatewayDevicesState: StateFlow<UiState<List<GatewayDevice>>> =
        _activeOrgId.flatMapLatest { repository.observeGatewayDevices(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    private val missionPair = combine(_activeOrgId, _activeMissionId) { o, m -> o to m }

    val missionMembersState: StateFlow<UiState<List<MissionMember>>> =
        missionPair.flatMapLatest { (o, m) -> repository.observeMissionMembers(o, m) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val memberLocationsState: StateFlow<UiState<List<MemberLocation>>> =
        missionPair.flatMapLatest { (o, m) -> repository.observeMemberLocations(o, m) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val sensorDevicesState: StateFlow<UiState<List<SensorDevice>>> =
        missionPair.flatMapLatest { (o, m) -> repository.observeSensorDevices(o, m) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val sensorHealthState: StateFlow<UiState<List<SensorHealth>>> =
        missionPair.flatMapLatest { (o, m) -> repository.observeSensorHealth(o, m) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val sensorEventsState: StateFlow<UiState<List<SensorEvent>>> =
        missionPair.flatMapLatest { (o, m) -> repository.observeSensorEvents(o, m) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val detectionsState: StateFlow<UiState<List<DetectionObservation>>> =
        missionPair.flatMapLatest { (o, m) -> repository.observeDetections(o, m) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val sosAlertsState: StateFlow<UiState<List<SosAlert>>> =
        missionPair.flatMapLatest { (o, m) -> repository.observeSosAlerts(o, m) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Success(emptyList()))

    val offlineRegions: StateFlow<List<OfflineMapRegionEntity>> = offlineCache.offlineRegions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingSyncOps: StateFlow<List<PendingSyncOperationEntity>> = offlineCache.pendingSyncOperations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncHistory: StateFlow<List<SyncHistoryEntity>> = offlineCache.syncHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val joinedOrgs: StateFlow<List<JoinedOrgEntity>> = offlineCache.joinedOrganizations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cachedMemberLocations: StateFlow<List<CachedMemberLocationEntity>> =
        _activeMissionId.flatMapLatest { mid ->
            if (mid.isBlank()) flowOf(emptyList()) else offlineCache.observeCachedMemberLocations(mid)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cachedSensorHealth: StateFlow<List<CachedSensorHealthEntity>> =
        _activeMissionId.flatMapLatest { mid ->
            if (mid.isBlank()) flowOf(emptyList()) else offlineCache.observeCachedSensorHealth(mid)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cachedSensorEvents: StateFlow<List<CachedSensorEventEntity>> =
        _activeMissionId.flatMapLatest { mid ->
            if (mid.isBlank()) flowOf(emptyList()) else offlineCache.observeCachedSensorEvents(mid)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadProgress: StateFlow<Map<String, RegionDownloadProgress>> = tileManager.downloadProgress
    val totalCachedTileCount: StateFlow<Int> = offlineCache.totalCachedTileCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val totalCachedBytes: StateFlow<Long> = offlineCache.totalCachedBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val currentRole: StateFlow<OrgRole> = orgMembersState.map { state ->
        val mine = (state as? UiState.Success)?.data?.firstOrNull { it.userId == currentUserId }
        OrgRole.fromValue(mine?.role.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OrgRole.TEAM_MEMBER)

    val isMembershipRevoked: StateFlow<Boolean> = combine(orgMembersState, currentOrganization) { mState, oState ->
        val mine = (mState as? UiState.Success)?.data?.firstOrNull { it.userId == currentUserId }
        val permDenied = (oState as? UiState.Error)?.isPermissionDenied == true ||
            (mState as? UiState.Error)?.isPermissionDenied == true
        mine?.isRevoked() == true || (permDenied && _activeOrgId.value.isNotBlank())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val activeMission: StateFlow<Mission?> = combine(missionsState, _activeMissionId) { mState, mid ->
        (mState as? UiState.Success)?.data?.firstOrNull { it.missionId == mid }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val hardwareCatalog: List<SensorAdapterDescriptor> = SensorArchitectureCatalog.descriptors()

    private val _mapLayers = MutableStateFlow(MapLayerConfig())
    val mapLayers: StateFlow<MapLayerConfig> = _mapLayers.asStateFlow()

    private val _mapSearchResults = MutableStateFlow<List<IndiaPlace>>(emptyList())
    val mapSearchResults: StateFlow<List<IndiaPlace>> = _mapSearchResults.asStateFlow()

    private val _separationWarnings = MutableStateFlow<List<SeparationWarningItem>>(emptyList())
    val separationWarnings: StateFlow<List<SeparationWarningItem>> = _separationWarnings.asStateFlow()

    private val _gatewayStatusText = MutableStateFlow("No Edge Gateway Connected")
    val gatewayStatusText: StateFlow<String> = _gatewayStatusText.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    /** Effective sensor devices (returns isolated fixture devices ONLY in Dev Demo Mode; never written to Firestore). */
    val effectiveSensorDevices: StateFlow<List<SensorDevice>> = combine(
        sensorDevicesState,
        settings,
        _activeOrgId,
        _activeMissionId,
    ) { state, cfg, orgId, missionId ->
        val live = (state as? UiState.Success)?.data.orEmpty()
        if (cfg.devDemoModeEnabled) DevDemoFixtureProvider.buildIsolatedFixtureDevices(orgId, missionId) else live
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Effective sensor events (isolated fixtures ONLY in Dev Demo Mode; otherwise Firestore live + Room offline cache fallback). */
    val effectiveSensorEvents: StateFlow<List<SensorEvent>> = combine(
        sensorEventsState,
        cachedSensorEvents,
        networkState,
        settings,
        _activeOrgId,
        _activeMissionId,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val state = arr[0] as UiState<List<SensorEvent>>
        @Suppress("UNCHECKED_CAST")
        val cached = arr[1] as List<CachedSensorEventEntity>
        val net = arr[2] as NetworkState
        val cfg = arr[3] as SurakshaSettings
        val orgId = arr[4] as String
        val missionId = arr[5] as String

        if (cfg.devDemoModeEnabled) {
            DevDemoFixtureProvider.buildIsolatedFixtureEvents(orgId, missionId)
        } else {
            val live = (state as? UiState.Success)?.data.orEmpty()
            if (live.isNotEmpty() || net != NetworkState.OFFLINE) live else cached.map { it.toModel() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Effective member locations (Firestore live + Room offline cache fallback). */
    val effectiveMemberLocations: StateFlow<List<MemberLocation>> = combine(
        memberLocationsState,
        cachedMemberLocations,
        networkState,
    ) { state, cached, net ->
        val live = (state as? UiState.Success)?.data.orEmpty()
        if (live.isNotEmpty() || net != NetworkState.OFFLINE) live else cached.map { it.toModel() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val radarTargets: StateFlow<List<RadarTarget>> = combine(
        locationFixState,
        effectiveMemberLocations,
        effectiveSensorDevices,
        effectiveSensorEvents,
        sosAlertsState,
    ) { fix, members, devs, events, alertsState ->
        val selfLat = (fix as? LocationFixState.Available)?.latitude
        val selfLon = (fix as? LocationFixState.Available)?.longitude
        val alerts = (alertsState as? UiState.Success)?.data.orEmpty()
        RadarGeoMath.buildRadarTargets(
            selfUid = currentUserId,
            selfLat = selfLat,
            selfLon = selfLon,
            members = members,
            sensorDevices = devs,
            sensorEvents = events,
            sosAlerts = alerts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        compassProvider.start()
        networkMonitor.refreshNow()

        viewModelScope.launch {
            offlineCache.ensureDefaultRegionsSeeded()
            repository.ensureUserProfile(initialDisplayName, initialEmail, initialPhotoUrl)
        }

        // Sync active workspace from settings + UserProfile
        viewModelScope.launch {
            combine(settings, userProfileState) { cfg, pState -> cfg to (pState as? UiState.Success)?.data }
                .collect { (cfg, profile) ->
                    val orgId = cfg.activeOrgId.ifBlank { profile?.activeOrgId.orEmpty() }
                    val teamId = cfg.activeTeamId.ifBlank { profile?.activeTeamId.orEmpty() }
                    val missionId = cfg.activeMissionId.ifBlank { profile?.activeMissionId.orEmpty() }
                    if (_activeOrgId.value != orgId) _activeOrgId.value = orgId
                    if (_activeTeamId.value != teamId) _activeTeamId.value = teamId
                    if (_activeMissionId.value != missionId) _activeMissionId.value = missionId
                }
        }

        // Auto-resume foreground GPS ONLY if explicit consent already saved & permissions valid
        viewModelScope.launch {
            preferences.settingsFlow.collect { cfg ->
                if (cfg.locationConsentEnabled &&
                    locationController.hasLocationPermission() &&
                    locationController.isDeviceLocationEnabled() &&
                    locationFixState.value is LocationFixState.OptedOut
                ) {
                    locationController.startTracking(useForegroundService = true)
                }
            }
        }

        // Cache live locations & sensor events into Room for offline survival
        viewModelScope.launch {
            memberLocationsState.collect { state ->
                val mid = _activeMissionId.value
                val list = (state as? UiState.Success)?.data.orEmpty()
                if (mid.isNotBlank() && list.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    offlineCache.cacheMissionLocations(
                        mid,
                        list.map {
                            CachedMemberLocationEntity(
                                cacheKey = "${it.missionId}:${it.userId}",
                                orgId = it.orgId,
                                missionId = it.missionId,
                                userId = it.userId,
                                displayName = it.displayName,
                                callsign = it.callsign,
                                role = it.role,
                                sharingEnabled = it.sharingEnabled,
                                hasValidLocation = it.hasValidLocation,
                                latitude = it.latitude,
                                longitude = it.longitude,
                                accuracyMeters = it.accuracyMeters,
                                headingDegrees = it.headingDegrees,
                                headingReliable = it.headingReliable,
                                batteryPercent = it.batteryPercent,
                                connectionStatus = it.connectionStatus,
                                locationTimestampMillis = it.locationTimestampMillis,
                                unavailableReason = it.unavailableReason,
                                cachedAtMillis = now,
                            )
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            sensorEventsState.collect { state ->
                val list = (state as? UiState.Success)?.data.orEmpty()
                if (list.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    offlineCache.cacheSensorEvents(
                        list.map {
                            CachedSensorEventEntity(
                                eventId = it.eventId,
                                orgId = it.orgId,
                                missionId = it.missionId,
                                sensorId = it.sensorId,
                                sensorType = it.sensorType,
                                timestampUtcMillis = it.timestampUtcMillis,
                                detectionType = it.detectionType,
                                estimatedRangeMeters = it.estimatedRangeMeters,
                                estimatedAzimuthDegrees = it.estimatedAzimuthDegrees,
                                estimatedLatitude = it.estimatedLatitude,
                                estimatedLongitude = it.estimatedLongitude,
                                confidence = it.confidence,
                                signalQuality = it.signalQuality,
                                sourceDevice = it.sourceDevice,
                                dataValidity = it.dataValidity,
                                status = it.status,
                                cachedAtMillis = now,
                            )
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            sensorDevicesState.collect { state ->
                val list = (state as? UiState.Success)?.data.orEmpty()
                if (list.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    offlineCache.cacheSensorHealth(
                        list.map {
                            CachedSensorHealthEntity(
                                sensorId = it.sensorId,
                                orgId = it.orgId,
                                missionId = it.missionId,
                                sensorType = it.sensorType,
                                displayName = it.displayName,
                                connectionStatus = it.connectionStatus,
                                healthState = if (it.connectionStatus == "connected") "healthy" else "awaiting_integration",
                                lastHeartbeatMillis = now,
                                packetsReceived = 0L,
                                packetsRejected = 0L,
                                diagnosticNote = it.manufacturerModel,
                                cachedAtMillis = now,
                            )
                        }
                    )
                }
            }
        }

        // Push own MemberLocation & evaluate team separation on location/heading updates
        viewModelScope.launch {
            combine(locationFixState, headingState, activeMission, userProfileState) { fix, heading, mission, pState ->
                listOf(fix, heading, mission, (pState as? UiState.Success)?.data)
            }.collect { tuple ->
                val fix = tuple[0] as LocationFixState
                val heading = tuple[1] as HeadingState
                val mission = tuple[2] as Mission? ?: return@collect
                val profile = tuple[3] as UserProfile?
                val orgId = _activeOrgId.value
                if (orgId.isBlank() || mission.status != MissionStatus.ACTIVE.value) return@collect

                val statusText = when (fix) {
                    is LocationFixState.Available -> "online"
                    is LocationFixState.GpsDisabled -> "gps_disabled"
                    is LocationFixState.PermissionDenied -> "permission_denied"
                    is LocationFixState.OptedOut -> "paused"
                    is LocationFixState.Searching -> "online"
                    is LocationFixState.Unavailable -> "stale"
                }
                val avail = fix as? LocationFixState.Available
                repository.pushMemberLocation(
                    MemberLocation(
                        userId = currentUserId,
                        orgId = orgId,
                        missionId = mission.missionId,
                        teamId = _activeTeamId.value.ifBlank { mission.teamId },
                        displayName = profile?.displayName?.ifBlank { initialDisplayName } ?: initialDisplayName,
                        callsign = profile?.callsign?.ifBlank { "RES-${currentUserId.take(4)}" } ?: "RES-01",
                        role = currentRole.value.value,
                        sharingEnabled = settings.value.locationConsentEnabled,
                        hasValidLocation = avail != null,
                        latitude = avail?.latitude ?: 0.0,
                        longitude = avail?.longitude ?: 0.0,
                        accuracyMeters = avail?.accuracyMeters?.toDouble() ?: 0.0,
                        headingDegrees = heading.azimuthDegrees,
                        headingReliable = heading.reliability.isUsable,
                        batteryPercent = readBatteryPercent().toLong(),
                        connectionStatus = statusText,
                        locationTimestampMillis = avail?.timestampMillis ?: System.currentTimeMillis(),
                        unavailableReason = when (fix) {
                            is LocationFixState.GpsDisabled -> fix.reason
                            is LocationFixState.PermissionDenied -> fix.reason
                            is LocationFixState.OptedOut -> fix.reason
                            is LocationFixState.Unavailable -> fix.reason
                            else -> ""
                        },
                    )
                )
            }
        }

        // Separation evaluation ticker
        viewModelScope.launch {
            while (true) {
                val mission = activeMission.value
                if (mission != null && mission.separationAlertEnabled && mission.status == MissionStatus.ACTIVE.value) {
                    val locs = effectiveMemberLocations.value
                    val leader = locs.firstOrNull { it.userId == mission.leaderUid && it.hasValidLocation }
                    val refLat = leader?.latitude ?: mission.centerLatitude
                    val refLon = leader?.longitude ?: mission.centerLongitude
                    _separationWarnings.value = separationEvaluator.evaluate(
                        leaderOrCenterLat = refLat,
                        leaderOrCenterLon = refLon,
                        members = locs,
                        thresholdMeters = mission.separationThresholdMeters,
                        delaySeconds = mission.separationDelaySeconds,
                    )
                } else {
                    _separationWarnings.value = emptyList()
                }
                delay(5_000L)
            }
        }

        // Ingest incoming WebSocket events cleanly when connected
        viewModelScope.launch {
            wsGateway.incomingEvents.collect { event ->
                repository.ingestValidatedSensorEvent(event)
            }
        }

        // Attach OfflineSyncManager executor
        syncManager.attachExecutor(object : SyncOperationExecutor {
            override suspend fun executeOperation(op: PendingSyncOperationEntity): Result<Unit> {
                val json = JSONObject(op.payloadJson)
                val docId = json.optString("docId").takeIf { it.isNotBlank() } ?: op.operationId
                val missionId = json.optString("missionId")
                return when (op.entityType) {
                    "create_sos" -> repository.createSosAlert(
                        SosAlert(
                            alertId = docId,
                            orgId = op.orgId,
                            missionId = missionId,
                            senderUid = currentUserId,
                            senderName = json.optString("senderName", initialDisplayName),
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
                    "cancel_sos" -> repository.transitionSosAlert(
                        orgId = op.orgId,
                        missionId = missionId,
                        alertId = json.optString("alertId", docId),
                        nextStatus = SosAlertStatus.CANCELLED,
                        actorName = initialDisplayName,
                        cancelReason = json.optString("cancelReason", "Cancelled"),
                    )
                    "sensor_observation" -> repository.logDetectionObservation(
                        DetectionObservation(
                            detectionId = docId,
                            orgId = op.orgId,
                            missionId = missionId,
                            sensorEventId = json.optString("sensorEventId"),
                            sensorId = json.optString("sensorId"),
                            operatorUid = currentUserId,
                            notes = json.optString("notes"),
                            outcome = json.optString("outcome", "investigating"),
                        ),
                        explicitId = docId,
                    ).map { }
                    else -> Result.failure(IllegalArgumentException("Unsupported queued operation: ${op.entityType}"))
                }
            }
        })
    }

    override fun onCleared() {
        compassProvider.stop()
        super.onCleared()
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    // --- Organization, Mission & Membership Actions ---

    fun createOrganization(orgCode: String, orgName: String, region: String) {
        viewModelScope.launch {
            val profile = (userProfileState.value as? UiState.Success)?.data
            val name = profile?.displayName?.ifBlank { initialDisplayName } ?: initialDisplayName
            val callsign = profile?.callsign?.ifBlank { "ADM-01" } ?: "ADM-01"
            val res = repository.orgStore.createOrganizationWorkspace(
                orgCode = orgCode,
                orgName = orgName,
                region = region,
                displayName = name,
                callsign = callsign,
                photoUrl = initialPhotoUrl,
            )
            res.onSuccess { (orgId, teamId, missionId) ->
                preferences.setActiveWorkspace(orgId, teamId, missionId)
                repository.updateUserProfileWorkspace(orgId, teamId, missionId)
                offlineCache.recordJoinedOrganization(orgId, orgName, orgCode, OrgRole.ORGANIZATION_ADMIN.value)
                _actionMessage.value = "Organization & primary mission created."
            }.onFailure {
                _actionMessage.value = it.localizedMessage ?: "Failed to create organization"
            }
        }
    }

    fun joinOrganization(orgId: String, inviteCode: String) {
        viewModelScope.launch {
            val profile = (userProfileState.value as? UiState.Success)?.data
            val res = repository.orgStore.joinOrganizationWithInvite(
                orgId = orgId.trim(),
                inviteCode = inviteCode.trim(),
                displayName = profile?.displayName?.ifBlank { initialDisplayName } ?: initialDisplayName,
                callsign = profile?.callsign?.ifBlank { "RES-01" } ?: "RES-01",
                photoUrl = initialPhotoUrl,
            )
            res.onSuccess { (joinedOrg, teamId, missionId) ->
                preferences.setActiveWorkspace(joinedOrg, teamId, missionId)
                repository.updateUserProfileWorkspace(joinedOrg, teamId, missionId)
                offlineCache.recordJoinedOrganization(joinedOrg, joinedOrg, "ORG", OrgRole.TEAM_MEMBER.value)
                _actionMessage.value = "Joined organization $joinedOrg."
            }.onFailure {
                _actionMessage.value = it.localizedMessage ?: "Unable to join organization"
            }
        }
    }

    fun selectWorkspace(orgId: String, teamId: String, missionId: String) {
        viewModelScope.launch {
            preferences.setActiveWorkspace(orgId, teamId, missionId)
            repository.updateUserProfileWorkspace(orgId, teamId, missionId)
            if (orgId.isNotBlank() && missionId.isNotBlank()) {
                val profile = (userProfileState.value as? UiState.Success)?.data
                repository.joinMissionParticipant(
                    orgId = orgId,
                    missionId = missionId,
                    teamId = teamId,
                    displayName = profile?.displayName?.ifBlank { initialDisplayName } ?: initialDisplayName,
                    callsign = profile?.callsign?.ifBlank { "RES-01" } ?: "RES-01",
                    photoUrl = initialPhotoUrl,
                    role = currentRole.value,
                )
            }
        }
    }

    fun createMission(
        title: String,
        description: String,
        mode: EnvironmentMode,
        thresholdMeters: Double,
        delaySeconds: Long,
    ) {
        val orgId = _activeOrgId.value
        val teamId = _activeTeamId.value.ifBlank { "team_default" }
        val teamCode = (teamsState.value as? UiState.Success)?.data?.firstOrNull { it.id == teamId }?.teamCode ?: "TEAM"
        val fix = locationFixState.value as? LocationFixState.Available
        val profile = (userProfileState.value as? UiState.Success)?.data
        viewModelScope.launch {
            repository.createMission(
                orgId = orgId,
                teamId = teamId,
                teamCode = teamCode,
                title = title,
                description = description,
                environmentMode = mode,
                separationThresholdMeters = thresholdMeters,
                separationDelaySeconds = delaySeconds,
                centerLatitude = fix?.latitude ?: 30.5506,
                centerLongitude = fix?.longitude ?: 79.5660,
                displayName = profile?.displayName?.ifBlank { initialDisplayName } ?: initialDisplayName,
                callsign = profile?.callsign?.ifBlank { "LDR-01" } ?: "LDR-01",
                photoUrl = initialPhotoUrl,
                role = currentRole.value,
            ).onSuccess { missionId ->
                preferences.setActiveWorkspace(orgId, teamId, missionId)
                repository.updateUserProfileWorkspace(orgId, teamId, missionId)
                _actionMessage.value = "Mission created & activated."
            }.onFailure {
                _actionMessage.value = it.localizedMessage ?: "Mission creation denied"
            }
        }
    }

    fun updateMissionSettings(
        mission: Mission,
        mode: EnvironmentMode,
        status: MissionStatus,
        separationEnabled: Boolean,
        thresholdMeters: Double,
        delaySeconds: Long,
    ) {
        viewModelScope.launch {
            repository.updateMissionSettings(
                orgId = _activeOrgId.value,
                mission = mission,
                environmentMode = mode,
                status = status,
                separationEnabled = separationEnabled,
                separationThresholdMeters = thresholdMeters,
                separationDelaySeconds = delaySeconds,
            ).onSuccess {
                _actionMessage.value = "Mission configuration updated."
            }.onFailure {
                _actionMessage.value = it.localizedMessage ?: "Mission update failed"
            }
        }
    }

    fun createTeam(teamCode: String, teamName: String) {
        viewModelScope.launch {
            val actor = (userProfileState.value as? UiState.Success)?.data?.displayName ?: initialDisplayName
            repository.orgStore.createTeam(_activeOrgId.value, teamCode, teamName, actor)
                .onSuccess { _actionMessage.value = "Team $teamName created." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Team creation denied" }
        }
    }

    fun toggleAllowMemberInvites(allow: Boolean) {
        viewModelScope.launch {
            repository.orgStore.setAllowMemberInvites(_activeOrgId.value, allow)
                .onSuccess { _actionMessage.value = if (allow) "Member invites require Admin approval." else "Member invites disabled (Admin only)." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to update invite policy" }
        }
    }

    fun createInvitation(assignedRole: OrgRole, targetEmail: String) {
        val orgId = _activeOrgId.value
        val actor = (userProfileState.value as? UiState.Success)?.data?.displayName ?: initialDisplayName
        viewModelScope.launch {
            repository.orgStore.createInvitation(
                orgId = orgId,
                assignedRole = assignedRole,
                targetTeamId = _activeTeamId.value,
                targetMissionId = _activeMissionId.value,
                targetEmail = targetEmail,
                callerRole = currentRole.value,
                callerName = actor,
            ).onSuccess { inv ->
                _actionMessage.value = if (inv.status == "approved") {
                    "Invite ready: ${inv.inviteCode}"
                } else {
                    "Invite ${inv.inviteCode} queued for Admin approval."
                }
            }.onFailure {
                _actionMessage.value = it.localizedMessage ?: "Invite creation denied"
            }
        }
    }

    fun approveOrRejectInvitation(inviteCode: String, approve: Boolean) {
        val actor = (userProfileState.value as? UiState.Success)?.data?.displayName ?: initialDisplayName
        viewModelScope.launch {
            repository.orgStore.approveOrRejectInvitation(_activeOrgId.value, inviteCode, approve, actor)
                .onSuccess { _actionMessage.value = "Invite $inviteCode ${if (approve) "approved" else "rejected"}." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Action denied" }
        }
    }

    fun updateMemberRole(targetUid: String, newRole: OrgRole) {
        val actor = (userProfileState.value as? UiState.Success)?.data?.displayName ?: initialDisplayName
        viewModelScope.launch {
            repository.orgStore.updateMemberRole(_activeOrgId.value, targetUid, newRole, actor)
                .onSuccess { _actionMessage.value = "Role updated to ${newRole.label}" }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Role change denied" }
        }
    }

    fun removeAndBlockMember(targetMember: OrgMember, reason: String) {
        val actor = (userProfileState.value as? UiState.Success)?.data?.displayName ?: initialDisplayName
        viewModelScope.launch {
            repository.orgStore.removeMemberAndBlock(
                orgId = _activeOrgId.value,
                targetMember = targetMember,
                activeMissionId = _activeMissionId.value,
                reason = reason,
                actorName = actor,
            ).onSuccess {
                _actionMessage.value = "${targetMember.displayName} removed, blocked & audited."
            }.onFailure {
                _actionMessage.value = it.localizedMessage ?: "Member removal denied"
            }
        }
    }

    fun reinstateBlockedMember(blocked: BlockedMembership) {
        val actor = (userProfileState.value as? UiState.Success)?.data?.displayName ?: initialDisplayName
        viewModelScope.launch {
            repository.orgStore.reinstateBlockedMember(_activeOrgId.value, blocked, actor)
                .onSuccess { _actionMessage.value = "User ${blocked.userId} reinstated by Admin." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Only Org Admin can reinstate" }
        }
    }

    // --- SOS & Separation Alerts ---

    fun triggerSosAlert(description: String, alertType: String = "sos", priority: String = "critical") {
        val orgId = _activeOrgId.value
        val missionId = _activeMissionId.value
        if (orgId.isBlank() || missionId.isBlank()) {
            _actionMessage.value = "Select or join an active Mission before sending SOS."
            return
        }
        val fix = locationFixState.value as? LocationFixState.Available
        val profile = (userProfileState.value as? UiState.Success)?.data
        val alert = SosAlert(
            orgId = orgId,
            missionId = missionId,
            senderUid = currentUserId,
            senderName = profile?.displayName?.ifBlank { initialDisplayName } ?: initialDisplayName,
            teamId = _activeTeamId.value,
            alertType = alertType,
            priority = priority,
            hasLocation = fix != null,
            latitude = fix?.latitude ?: 0.0,
            longitude = fix?.longitude ?: 0.0,
            accuracyMeters = fix?.accuracyMeters?.toDouble() ?: 0.0,
            locationTimestampMillis = fix?.timestampMillis ?: System.currentTimeMillis(),
            description = description.ifBlank { "Immediate responder SOS assistance requested" },
        )

        viewModelScope.launch {
            if (networkMonitor.refreshNow() == NetworkState.OFFLINE) {
                syncManager.enqueuePendingOperation(
                    orgId = orgId,
                    entityType = "create_sos",
                    payload = JSONObject().apply {
                        put("missionId", missionId)
                        put("senderName", alert.senderName)
                        put("teamId", alert.teamId)
                        put("alertType", alert.alertType)
                        put("priority", alert.priority)
                        put("hasLocation", alert.hasLocation)
                        put("latitude", alert.latitude)
                        put("longitude", alert.longitude)
                        put("accuracyMeters", alert.accuracyMeters)
                        put("locationTimestampMillis", alert.locationTimestampMillis)
                        put("description", alert.description)
                    },
                )
                _actionMessage.value = "Offline: SOS queued for immediate transmission upon reconnect."
            } else {
                repository.createSosAlert(alert)
                    .onSuccess { _actionMessage.value = "SOS alert transmitted to mission responders." }
                    .onFailure { err ->
                        _actionMessage.value = err.localizedMessage ?: "SOS dispatch failed"
                    }
            }
        }
    }

    fun transitionSosAlert(alert: SosAlert, nextStatus: SosAlertStatus, cancelReason: String = "") {
        val orgId = _activeOrgId.value
        val missionId = _activeMissionId.value
        val actor = (userProfileState.value as? UiState.Success)?.data?.displayName ?: initialDisplayName
        viewModelScope.launch {
            if (nextStatus == SosAlertStatus.CANCELLED && networkMonitor.refreshNow() == NetworkState.OFFLINE) {
                syncManager.enqueuePendingOperation(
                    orgId = orgId,
                    entityType = "cancel_sos",
                    payload = JSONObject().apply {
                        put("missionId", missionId)
                        put("alertId", alert.alertId)
                        put("cancelReason", cancelReason.ifBlank { "Cancelled offline by responder" })
                    },
                )
                _actionMessage.value = "Offline: SOS cancellation queued."
            } else {
                repository.transitionSosAlert(orgId, missionId, alert.alertId, nextStatus, actor, cancelReason)
                    .onSuccess { _actionMessage.value = "Alert updated to ${nextStatus.label}." }
                    .onFailure { _actionMessage.value = it.localizedMessage ?: "SOS update rejected" }
            }
        }
    }

    // --- Edge Gateway & Sensor Integration ---

    fun registerGatewayAndSaveConfig(name: String, endpointUri: String, transport: GatewayTransportKind, bearerToken: String) {
        val orgId = _activeOrgId.value
        viewModelScope.launch {
            preferences.setHardwareGatewayConfig(endpointUri, transport.value)
            if (bearerToken.isNotBlank()) tokenStorage.saveGatewayToken(bearerToken)
            if (orgId.isNotBlank()) {
                repository.upsertGatewayDevice(
                    GatewayDevice(
                        orgId = orgId,
                        name = name.ifBlank { "Field Sensor Hub" },
                        transportType = transport.value,
                        endpointUri = endpointUri.trim(),
                        status = "unverified",
                        lastHeartbeatMillis = System.currentTimeMillis(),
                    )
                )
            }
            _actionMessage.value = "Gateway endpoint & Keystore encrypted token saved."
        }
    }

    fun pollConfiguredGateway() {
        val cfg = settings.value
        val orgId = _activeOrgId.value
        val missionId = _activeMissionId.value
        if (orgId.isBlank() || missionId.isBlank()) {
            _actionMessage.value = "Join an active Mission first."
            return
        }
        val kind = GatewayTransportKind.entries.firstOrNull { it.value == cfg.gatewayTransportType }
            ?: GatewayTransportKind.HTTP_TLS
        val gwConfig = GatewayTransportConfig(
            transportKind = kind,
            endpointUri = cfg.hardwareGatewayUrl,
            orgId = orgId,
            missionId = missionId,
            bearerToken = tokenStorage.getGatewayToken(),
        )
        viewModelScope.launch {
            val client = when (kind) {
                GatewayTransportKind.HTTP_TLS -> httpGateway
                GatewayTransportKind.WEBSOCKET_TLS -> wsGateway
                GatewayTransportKind.MQTT_TLS -> mqttGateway
            }
            if (kind == GatewayTransportKind.WEBSOCKET_TLS) {
                client.connect(gwConfig)
                    .onSuccess {
                        _gatewayStatusText.value = "WebSocket TLS Stream Active"
                        _actionMessage.value = "Connected to WebSocket Edge Gateway."
                    }
                    .onFailure {
                        _gatewayStatusText.value = "WebSocket Error: ${it.localizedMessage}"
                        _actionMessage.value = it.localizedMessage
                    }
                return@launch
            }
            client.pollOnce(gwConfig, currentUserId)
                .onSuccess { events ->
                    events.forEach { repository.ingestValidatedSensorEvent(it) }
                    _gatewayStatusText.value = if (events.isEmpty()) "Connected • 0 new events" else "Ingested ${events.size} sensor event(s)"
                    _actionMessage.value = _gatewayStatusText.value
                }
                .onFailure {
                    _gatewayStatusText.value = it.localizedMessage ?: "Gateway unavailable"
                    _actionMessage.value = _gatewayStatusText.value
                }
        }
    }

    fun validateAndIngestGatewayJson(rawJson: String) {
        val orgId = _activeOrgId.value
        val missionId = _activeMissionId.value
        if (orgId.isBlank() || missionId.isBlank()) {
            _actionMessage.value = "Active Mission required before ingesting JSON payload."
            return
        }
        viewModelScope.launch {
            val results = sensorParser.parseBatchJsonEvents(rawJson, orgId, missionId, currentUserId)
            var accepted = 0
            var firstError: String? = null
            for (r in results) {
                r.onSuccess { ev ->
                    if (repository.ingestValidatedSensorEvent(ev).isSuccess) accepted++
                }.onFailure {
                    if (firstError == null) firstError = it.localizedMessage
                }
            }
            _actionMessage.value = if (accepted > 0) {
                "Validated & ingested $accepted SensorEvent(s)."
            } else {
                firstError ?: "No valid SensorEvent v1.0.0 entries found."
            }
        }
    }

    fun logDetectionObservation(event: SensorEvent, notes: String, outcome: String) {
        val orgId = _activeOrgId.value
        val missionId = _activeMissionId.value
        viewModelScope.launch {
            if (networkMonitor.refreshNow() == NetworkState.OFFLINE) {
                syncManager.enqueuePendingOperation(
                    orgId = orgId,
                    entityType = "sensor_observation",
                    payload = JSONObject().apply {
                        put("missionId", missionId)
                        put("sensorEventId", event.eventId)
                        put("sensorId", event.sensorId)
                        put("notes", notes)
                        put("outcome", outcome)
                    },
                )
                _actionMessage.value = "Offline: field observation queued."
            } else {
                repository.logDetectionObservation(
                    DetectionObservation(
                        orgId = orgId,
                        missionId = missionId,
                        sensorEventId = event.eventId,
                        sensorId = event.sensorId,
                        notes = notes,
                        outcome = outcome,
                    )
                ).onSuccess { _actionMessage.value = "Observation saved." }
                    .onFailure { _actionMessage.value = it.localizedMessage ?: "Observation failed" }
            }
        }
    }

    // --- Location, Radar, Map, Offline & Settings ---

    fun updateVoluntaryLocationSharing(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setLocationConsentEnabled(enabled)
            repository.updateUserProfileWorkspace(locationSharingEnabled = enabled)
            if (enabled) {
                locationController.startTracking(useForegroundService = true)
            } else {
                locationController.stopTracking()
            }
        }
    }

    fun updateProfileDetails(displayName: String, callsign: String, phoneOptional: String) {
        viewModelScope.launch {
            repository.updateUserProfileWorkspace(
                displayName = displayName,
                callsign = callsign,
                phoneOptional = phoneOptional,
            ).onSuccess { _actionMessage.value = "Responder profile updated." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Profile update failed" }
        }
    }

    fun setDevDemoMode(enabled: Boolean) {
        viewModelScope.launch { preferences.setDevDemoMode(enabled) }
    }

    fun setRadarRangeMeters(range: Double) {
        viewModelScope.launch { preferences.setRadarRangeMeters(range) }
    }

    fun setNorthUpMode(northUp: Boolean) {
        viewModelScope.launch { preferences.setNorthUpMode(northUp) }
    }

    fun setDarkThemeEnabled(dark: Boolean) {
        viewModelScope.launch { preferences.setDarkThemeEnabled(dark) }
    }

    fun setLanguageCode(code: String) {
        viewModelScope.launch { preferences.setLanguageCode(code) }
    }

    fun setOfflineMapMode(enabled: Boolean) {
        viewModelScope.launch { preferences.setOfflineMapMode(enabled) }
    }

    fun dismissBatteryOptimizationPrompt() {
        viewModelScope.launch { preferences.setBatteryOptPromptDismissed(true) }
    }

    fun updateMapLayers(newLayers: MapLayerConfig) {
        _mapLayers.value = newLayers
    }

    fun searchMapPlaces(query: String) {
        viewModelScope.launch {
            val allowNet = !settings.value.offlineMapMode && networkMonitor.refreshNow() != NetworkState.OFFLINE
            _mapSearchResults.value = tileManager.searchLocation(query, allowNet)
        }
    }

    fun clearMapSearch() {
        _mapSearchResults.value = emptyList()
    }

    fun startOfflineRegionDownload(region: OfflineMapRegionEntity, layerKind: String = "topo") {
        tileManager.startRegionDownload(viewModelScope, region, layerKind, settings.value.customTileServerUrl)
    }

    fun cancelOfflineRegionDownload(regionId: String) {
        tileManager.cancelRegionDownload(regionId)
    }

    fun deleteOfflineRegion(regionId: String) {
        viewModelScope.launch { tileManager.deleteRegionCache(regionId) }
    }

    fun flushOfflineQueueNow() {
        viewModelScope.launch {
            val (synced, failed) = syncManager.flushPendingQueue(forceIgnoreBackoff = true)
            _actionMessage.value = "Sync complete: $synced delivered, $failed remaining."
        }
    }

    fun registerSensorDevice(device: SensorDevice) {
        viewModelScope.launch {
            repository.saveSensorDevice(device)
                .onSuccess { _actionMessage.value = "Sensor station ${device.displayName} saved." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to save sensor device" }
        }
    }

    fun syncPushRegistration() {
        val orgId = _activeOrgId.value
        viewModelScope.launch {
            com.example.data.firebase.SurakshaMessagingService
                .registerCurrentDeviceToken(appContext, orgId, settings.value.notificationsEnabled)
                .onSuccess { _actionMessage.value = "FCM emergency dispatch token synced." }
                .onFailure { _actionMessage.value = "Push token sync skipped: ${it.localizedMessage}" }
        }
    }

    private fun readBatteryPercent(): Int {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 100
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct else 100
    }

    private fun CachedMemberLocationEntity.toModel() = MemberLocation(
        id = userId,
        userId = userId,
        orgId = orgId,
        missionId = missionId,
        displayName = displayName,
        callsign = callsign,
        role = role,
        sharingEnabled = sharingEnabled,
        hasValidLocation = hasValidLocation,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        headingDegrees = headingDegrees,
        headingReliable = headingReliable,
        batteryPercent = batteryPercent,
        connectionStatus = "offline",
        locationTimestampMillis = locationTimestampMillis,
        unavailableReason = "Offline cached fix",
    )

    private fun CachedSensorEventEntity.toModel() = SensorEvent(
        id = eventId,
        eventId = eventId,
        orgId = orgId,
        missionId = missionId,
        sensorId = sensorId,
        sensorType = sensorType,
        timestampUtcMillis = timestampUtcMillis,
        detectionType = detectionType,
        estimatedRangeMeters = estimatedRangeMeters,
        estimatedAzimuthDegrees = estimatedAzimuthDegrees,
        estimatedLatitude = estimatedLatitude,
        estimatedLongitude = estimatedLongitude,
        confidence = confidence,
        signalQuality = signalQuality,
        sourceDevice = sourceDevice,
        dataValidity = dataValidity,
        status = status,
    )
}
