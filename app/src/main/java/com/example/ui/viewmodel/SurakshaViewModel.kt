package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.SurakshaAiRepository
import com.example.data.hardware.EnvScenario
import com.example.data.hardware.SensorAdapterDescriptor
import com.example.data.hardware.SensorArchitectureCatalog
import com.example.data.hardware.SlamEnvironmentFrame
import com.example.data.local.CachedSensorLogEntity
import com.example.data.local.OfflineCacheRepository
import com.example.data.local.OfflineMapRegionEntity
import com.example.data.local.SurakshaPreferences
import com.example.data.local.SurakshaSettings
import com.example.data.model.AiTriageReport
import com.example.data.model.MapLayerConfig
import com.example.data.model.PresenceSignal
import com.example.data.model.RescueLocation
import com.example.data.model.RescueRegistryEntry
import com.example.data.model.RescueTeam
import com.example.data.model.TeamAlert
import com.example.data.model.UserProfile
import com.example.data.repository.SurakshaRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

class SurakshaViewModel(
    private val repository: SurakshaRepository,
    private val offlineCache: OfflineCacheRepository,
    private val preferences: SurakshaPreferences,
    private val aiRepository: SurakshaAiRepository,
    private val currentUserId: String,
    private val initialDisplayName: String,
) : ViewModel() {

    val settings: StateFlow<SurakshaSettings> = preferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), SurakshaSettings())

    val userProfileState: StateFlow<DataUiState<UserProfile?>> =
        repository.observeUserProfile(currentUserId).toUiState("profile")

    val rescueTeamsState: StateFlow<DataUiState<List<RescueTeam>>> =
        repository.observeRescueTeams(currentUserId).toUiState("rescue teams")

    val rescueLocationsState: StateFlow<DataUiState<List<RescueLocation>>> =
        repository.observeRescueLocations(currentUserId).toUiState("rescue locations")

    val presenceSignalsState: StateFlow<DataUiState<List<PresenceSignal>>> =
        repository.observePresenceSignals(currentUserId).toUiState("presence signals")

    val rescueRegistryState: StateFlow<DataUiState<List<RescueRegistryEntry>>> =
        repository.observeRescueRegistry(currentUserId).toUiState("rescue registry")

    val teamAlertsState: StateFlow<DataUiState<List<TeamAlert>>> =
        repository.observeTeamAlerts(currentUserId).toUiState("team alerts")

    val offlineRegions: StateFlow<List<OfflineMapRegionEntity>> = offlineCache.offlineRegions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val cachedSensorLogs: StateFlow<List<CachedSensorLogEntity>> = offlineCache.recentSensorLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _mapLayers = MutableStateFlow(MapLayerConfig())
    val mapLayers: StateFlow<MapLayerConfig> = _mapLayers.asStateFlow()

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

    private val _adapters = MutableStateFlow(SensorArchitectureCatalog.buildAdapters())
    val sensorAdapters: StateFlow<List<SensorAdapterDescriptor>> = _adapters
        .map { list -> list.map { it.descriptor } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _aiTriageState = MutableStateFlow<AiTriageUiState>(AiTriageUiState.Idle)
    val aiTriageState: StateFlow<AiTriageUiState> = _aiTriageState.asStateFlow()

    private val _actionStatusMessage = MutableStateFlow<String?>(null)
    val actionStatusMessage: StateFlow<String?> = _actionStatusMessage.asStateFlow()

    init {
        viewModelScope.launch {
            offlineCache.ensureDefaultRegionsSeeded()
            repository.ensureUserProfile(displayName = initialDisplayName)
            repository.seedDemoDatasetIfEmpty()
        }
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

    fun updateMapLayers(transform: (MapLayerConfig) -> MapLayerConfig) {
        _mapLayers.value = transform(_mapLayers.value)
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

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch { preferences.setDemoMode(enabled) }
    }

    fun setOfflineMapMode(enabled: Boolean) {
        viewModelScope.launch { preferences.setOfflineMapMode(enabled) }
    }

    fun setRevealSensitivePii(reveal: Boolean) {
        viewModelScope.launch { preferences.setRevealSensitivePii(reveal) }
    }

    fun toggleOfflineRegion(regionId: String, download: Boolean) {
        viewModelScope.launch {
            offlineCache.toggleRegionDownload(regionId, download)
            _actionStatusMessage.value = if (download) {
                "Offline map package cached to local SQLite."
            } else {
                "Offline map package removed from local cache."
            }
        }
    }

    fun seedDemoDataset() {
        viewModelScope.launch {
            val res = repository.seedDemoDatasetForce()
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Synthetic Demo Mode records added." },
                onFailure = { it.localizedMessage ?: "Failed to seed demo data." }
            )
        }
    }

    fun updateVoluntaryLocationSharing(enabled: Boolean) {
        viewModelScope.launch {
            val res = repository.updateLocationSharingPreference(enabled)
            _actionStatusMessage.value = res.fold(
                onSuccess = {
                    if (enabled) "Voluntary location sharing enabled." else "Location sharing disabled."
                },
                onFailure = { it.localizedMessage ?: "Could not update location sharing." }
            )
        }
    }

    fun registerRescueTeam(
        teamCode: String,
        teamName: String,
        memberCount: Long,
        activeCount: Long,
        status: String,
        locationSharingOptIn: Boolean,
        latitude: Double,
        longitude: Double,
    ) {
        viewModelScope.launch {
            val res = repository.createRescueTeam(
                RescueTeam(
                    userId = currentUserId,
                    teamCode = teamCode,
                    teamName = teamName,
                    memberCount = memberCount,
                    activeCount = activeCount,
                    status = status,
                    locationSharingOptIn = locationSharingOptIn,
                    latitude = if (locationSharingOptIn) latitude else 0.0,
                    longitude = if (locationSharingOptIn) longitude else 0.0,
                    batteryPercent = 96L,
                    connectionStatus = "online",
                    isSynthetic = settings.value.demoModeEnabled,
                )
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Team $teamCode registered." },
                onFailure = { it.localizedMessage ?: "Failed to register team." }
            )
        }
    }

    fun toggleTeamLocationSharing(team: RescueTeam, optIn: Boolean, lat: Double = 28.6139, lon: Double = 77.2090) {
        viewModelScope.launch {
            val updated = team.copy(
                locationSharingOptIn = optIn,
                latitude = if (optIn) (if (team.latitude != 0.0) team.latitude else lat) else 0.0,
                longitude = if (optIn) (if (team.longitude != 0.0) team.longitude else lon) else 0.0,
            )
            val res = repository.updateRescueTeam(updated)
            _actionStatusMessage.value = res.fold(
                onSuccess = {
                    if (optIn) "${team.teamCode} voluntary GPS sharing enabled." else "${team.teamCode} location sharing paused."
                },
                onFailure = { it.localizedMessage ?: "Failed to update team sharing." }
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
        viewModelScope.launch {
            val res = repository.createRescueLocation(
                RescueLocation(
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
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Disaster site '$name' registered." },
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
        viewModelScope.launch {
            val res = repository.createPresenceSignal(
                PresenceSignal(
                    userId = currentUserId,
                    locationId = "sector_field",
                    sectorLabel = sectorLabel,
                    signalType = signalType,
                    estimatedMinCount = minCount,
                    estimatedMaxCount = maxCount,
                    confidencePercent = confidence,
                    verificationStatus = "estimated",
                    dataSource = dataSource,
                    isSynthetic = settings.value.demoModeEnabled,
                )
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Estimated presence signal logged (requires verification)." },
                onFailure = { it.localizedMessage ?: "Failed to log presence signal." }
            )
        }
    }

    fun updatePresenceVerification(signal: PresenceSignal, newStatus: String) {
        viewModelScope.launch {
            val updatedConfidence = when (newStatus) {
                "verified" -> maxOf(signal.confidencePercent, 92L)
                "outdated" -> minOf(signal.confidencePercent, 35L)
                else -> signal.confidencePercent
            }
            val updatedSource = when (newStatus) {
                "verified" -> "Field Team Confirmed (${signal.dataSource.take(35)})"
                "outdated" -> "Stale Observation (${signal.dataSource.take(35)})"
                else -> signal.dataSource
            }
            val res = repository.updatePresenceSignalVerification(
                signalId = signal.id,
                newStatus = newStatus,
                newConfidence = updatedConfidence,
                dataSource = updatedSource,
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Signal marked $newStatus." },
                onFailure = { it.localizedMessage ?: "Failed to update signal." }
            )
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
    ) {
        viewModelScope.launch {
            val res = repository.createRegistryEntry(
                RescueRegistryEntry(
                    userId = currentUserId,
                    category = category,
                    title = title,
                    locationName = locationName,
                    details = details,
                    sensitiveContactMasked = sensitiveContact,
                    rescueStatus = "open",
                    verificationStatus = verificationStatus,
                    informationSource = informationSource,
                    verificationNotes = "Logged by authenticated responder.",
                    isSynthetic = settings.value.demoModeEnabled,
                )
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Registry record saved." },
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
        viewModelScope.launch {
            val res = repository.updateRegistryEntry(
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

    fun sendEmergencyDispatch(teamCode: String, priority: String, message: String, channel: String) {
        viewModelScope.launch {
            val res = repository.sendTeamAlert(
                TeamAlert(
                    userId = currentUserId,
                    teamCode = teamCode,
                    priority = priority,
                    message = message,
                    channel = channel,
                    isSynthetic = settings.value.demoModeEnabled,
                )
            )
            _actionStatusMessage.value = res.fold(
                onSuccess = { "Dispatch broadcast transmitted on $channel." },
                onFailure = { it.localizedMessage ?: "Failed to send dispatch." }
            )
        }
    }

    fun captureAdapterTelemetry(adapter: SensorAdapterDescriptor) {
        viewModelScope.launch {
            offlineCache.recordSensorSnapshot(
                nodeId = adapter.nodeId,
                sensorType = adapter.sensorType,
                summary = adapter.samplePayloadJson,
                confidencePercent = adapter.confidencePercent,
                isSynthetic = true,
            )
            _actionStatusMessage.value = "Captured ${adapter.platformName} packet to SQLite offline log."
        }
    }

    fun runAiTriage() {
        val locations = (rescueLocationsState.value as? DataUiState.Success)?.data ?: emptyList()
        val signals = (presenceSignalsState.value as? DataUiState.Success)?.data ?: emptyList()
        val teams = (rescueTeamsState.value as? DataUiState.Success)?.data ?: emptyList()
        val registry = (rescueRegistryState.value as? DataUiState.Success)?.data ?: emptyList()

        viewModelScope.launch {
            _aiTriageState.value = AiTriageUiState.Loading
            val result = aiRepository.generateSituationTriage(
                locations = locations,
                signals = signals,
                teams = teams,
                registry = registry,
                isDemoMode = settings.value.demoModeEnabled,
            )
            _aiTriageState.value = result.fold(
                onSuccess = { AiTriageUiState.Success(it) },
                onFailure = { AiTriageUiState.Error(it.localizedMessage ?: "AI triage failed.") }
            )
        }
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

    private companion object {
        const val TAG = "SurakshaVM"
    }
}
