package com.example

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.ai.SurakshaAiRepository
import com.example.data.firebase.configureAppCheck
import com.example.data.local.OfflineCacheRepository
import com.example.data.local.SurakshaLocalDatabase
import com.example.data.local.SurakshaPreferences
import com.example.data.local.SurakshaSettings
import com.example.data.location.SurakshaLocationController
import com.example.data.map.OsmTileManager
import com.example.data.model.SurakshaSection
import com.example.data.network.NetworkMonitor
import com.example.data.repository.SurakshaRepository
import com.example.data.sync.OfflineSyncManager
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.signOutResponder
import com.example.ui.screens.EnvironmentMappingScreen
import com.example.ui.screens.HumanPresenceScreen
import com.example.ui.screens.LiveMapScreen
import com.example.ui.screens.OverviewScreen
import com.example.ui.screens.RescueRegistryScreen
import com.example.ui.screens.RescueTeamsScreen
import com.example.ui.screens.SensorStatusScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.SurakshaTheme
import com.example.ui.viewmodel.SurakshaViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            val prefs = remember { SurakshaPreferences(applicationContext) }
            val rootSettings by prefs.settingsFlow.collectAsStateWithLifecycle(initialValue = SurakshaSettings())
            SurakshaTheme(darkTheme = rootSettings.darkThemeEnabled) {
                SurakshaRootApp()
            }
        }
    }
}

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@Composable
fun SurakshaRootApp(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            SurakshaAuthenticatedShell(
                currentUserId = user.uid,
                displayName = user.displayName ?: "Suraksha Responder",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurakshaAuthenticatedShell(
    currentUserId: String,
    displayName: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: SurakshaViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                val localDb = SurakshaLocalDatabase.getInstance(app)
                val dao = localDb.localDao()
                val networkMonitor = NetworkMonitor(app)
                val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val syncManager = OfflineSyncManager(dao, networkMonitor, syncScope)
                val repository = SurakshaRepository(db, Firebase.auth)
                SurakshaViewModel(
                    appContext = app,
                    repository = repository,
                    offlineCache = OfflineCacheRepository(dao),
                    tileManager = OsmTileManager(app, dao),
                    networkMonitor = networkMonitor,
                    syncManager = syncManager,
                    locationController = SurakshaLocationController(app),
                    preferences = SurakshaPreferences(app),
                    aiRepository = SurakshaAiRepository(),
                    currentUserId = currentUserId,
                    initialDisplayName = displayName,
                )
            }
        }
    )

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val activeOrgId by viewModel.activeOrgId.collectAsStateWithLifecycle()
    val currentRole by viewModel.currentMemberRole.collectAsStateWithLifecycle()
    val profileState by viewModel.userProfileState.collectAsStateWithLifecycle()
    val orgState by viewModel.currentOrganization.collectAsStateWithLifecycle()
    val membersState by viewModel.orgMembersState.collectAsStateWithLifecycle()
    val invitesState by viewModel.orgInvitesState.collectAsStateWithLifecycle()
    val teamsState by viewModel.rescueTeamsState.collectAsStateWithLifecycle()
    val locationsState by viewModel.rescueLocationsState.collectAsStateWithLifecycle()
    val signalsState by viewModel.presenceSignalsState.collectAsStateWithLifecycle()
    val registryState by viewModel.rescueRegistryState.collectAsStateWithLifecycle()
    val incidentsState by viewModel.sosIncidentsState.collectAsStateWithLifecycle()
    val offlineRegions by viewModel.offlineRegions.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val totalCachedTiles by viewModel.totalCachedTileCount.collectAsStateWithLifecycle()
    val totalCachedBytes by viewModel.totalCachedBytes.collectAsStateWithLifecycle()
    val pendingSyncOps by viewModel.pendingSyncOps.collectAsStateWithLifecycle()
    val cachedSensorLogs by viewModel.cachedSensorLogs.collectAsStateWithLifecycle()
    val slamSessions by viewModel.slamSessions.collectAsStateWithLifecycle()
    val mapLayers by viewModel.mapLayers.collectAsStateWithLifecycle()
    val mapSearchResults by viewModel.mapSearchResults.collectAsStateWithLifecycle()
    val deviceLocation by viewModel.locationFixState.collectAsStateWithLifecycle()
    val selectedScenario by viewModel.selectedScenario.collectAsStateWithLifecycle()
    val sweepProgress by viewModel.sweepProgress.collectAsStateWithLifecycle()
    val isScanning by viewModel.isSlamScanning.collectAsStateWithLifecycle()
    val slamFrame by viewModel.slamFrame.collectAsStateWithLifecycle()
    val sensorAdapters by viewModel.sensorAdapters.collectAsStateWithLifecycle()
    val aiTriageState by viewModel.aiTriageState.collectAsStateWithLifecycle()
    val aiChatAnswer by viewModel.aiChatAnswer.collectAsStateWithLifecycle()
    val aiChatLoading by viewModel.aiChatLoading.collectAsStateWithLifecycle()
    val exportedReportText by viewModel.exportedReportText.collectAsStateWithLifecycle()
    val dashboardFilter by viewModel.dashboardFilter.collectAsStateWithLifecycle()
    val analytics by viewModel.analytics.collectAsStateWithLifecycle()
    val statusMessage by viewModel.actionStatusMessage.collectAsStateWithLifecycle()

    val localizedContext = remember(context, settings.languageCode) {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(settings.languageCode))
        }
        context.createConfigurationContext(config)
    }

    var currentSection by rememberSaveable { mutableStateOf(SurakshaSection.OVERVIEW) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    if (currentSection != SurakshaSection.OVERVIEW) {
        BackHandler { currentSection = SurakshaSection.OVERVIEW }
    }

    val primaryBottomTabs = remember {
        listOf(
            SurakshaSection.OVERVIEW,
            SurakshaSection.LIVE_MAP,
            SurakshaSection.ENV_MAPPING,
            SurakshaSection.HUMAN_PRESENCE,
            SurakshaSection.RESCUE_REGISTRY,
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Column {
                            Text("PROJECT SURAKSHA", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = currentRole.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    SurakshaSection.entries.forEach { section ->
                        NavigationDrawerItem(
                            icon = { Icon(sectionIcon(section), contentDescription = null) },
                            label = { Text(sectionLocalizedTitle(localizedContext, section)) },
                            selected = currentSection == section,
                            onClick = {
                                currentSection = section
                                scope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.testTag("drawer_nav_${section.route}"),
                        )
                    }
                }
            }
        },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 700.dp

            Row(modifier = Modifier.fillMaxSize()) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        Spacer(Modifier.weight(0.1f))
                        SurakshaSection.entries.forEach { section ->
                            val title = sectionLocalizedTitle(localizedContext, section)
                            NavigationRailItem(
                                selected = currentSection == section,
                                onClick = { currentSection = section },
                                icon = { Icon(sectionIcon(section), contentDescription = title) },
                                label = { Text(title, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets.navigationBars,
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            navigationIcon = {
                                IconButton(
                                    onClick = { scope.launch { drawerState.open() } },
                                    modifier = Modifier.testTag("open_drawer_button"),
                                ) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Open all 8 navigation sections")
                                }
                            },
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = sectionLocalizedTitle(localizedContext, currentSection),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    if (settings.demoModeEnabled) {
                                        Surface(
                                            color = StatusEstimated.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, StatusEstimated.copy(alpha = 0.6f)),
                                        ) {
                                            Text(
                                                text = "DEMO",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StatusEstimated,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = { currentSection = SurakshaSection.RESCUE_TEAMS },
                                    modifier = Modifier.testTag("topbar_teams_button"),
                                ) {
                                    Icon(Icons.Filled.Groups, contentDescription = "Rescue Teams", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(
                                    onClick = { currentSection = SurakshaSection.SENSOR_STATUS },
                                    modifier = Modifier.testTag("topbar_sensors_button"),
                                ) {
                                    Icon(Icons.Filled.Sensors, contentDescription = "Sensor Status", tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                    },
                    bottomBar = {
                        if (!useRail) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets.navigationBars,
                            ) {
                                primaryBottomTabs.forEach { section ->
                                    val label = sectionLocalizedTitle(localizedContext, section)
                                    NavigationBarItem(
                                        selected = currentSection == section,
                                        onClick = { currentSection = section },
                                        icon = { Icon(sectionIcon(section), contentDescription = label) },
                                        label = { Text(label, maxLines = 1) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                        ),
                                        modifier = Modifier.testTag("bottom_nav_${section.route}"),
                                    )
                                }
                            }
                        }
                    },
                ) { innerPadding ->
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        when (currentSection) {
                            SurakshaSection.OVERVIEW -> OverviewScreen(
                                settings = settings,
                                networkState = networkState,
                                pendingSyncCount = pendingSyncOps.size,
                                organizationState = orgState,
                                currentRole = currentRole,
                                analytics = analytics,
                                filter = dashboardFilter,
                                teamsState = teamsState,
                                locationsState = locationsState,
                                signalsState = signalsState,
                                registryState = registryState,
                                incidentsState = incidentsState,
                                adapters = sensorAdapters,
                                deviceLocation = deviceLocation,
                                tileManager = viewModel.tileManager,
                                aiTriageState = aiTriageState,
                                aiChatAnswer = aiChatAnswer,
                                aiChatLoading = aiChatLoading,
                                exportedReportText = exportedReportText,
                                onUpdateFilter = viewModel::updateDashboardFilter,
                                onRetrySync = viewModel::flushOfflineQueueNow,
                                onRunAiTriage = viewModel::runAiTriage,
                                onAskAiQuestion = viewModel::askAiQuestion,
                                onExportCsvReport = viewModel::exportOperationalCsvReport,
                                onClearExportedReport = viewModel::clearExportedReport,
                                onNavigateSection = { currentSection = it },
                            )
                            SurakshaSection.LIVE_MAP -> LiveMapScreen(
                                settings = settings,
                                networkState = networkState,
                                currentRole = currentRole,
                                locationsState = locationsState,
                                teamsState = teamsState,
                                signalsState = signalsState,
                                registryState = registryState,
                                layers = mapLayers,
                                offlineRegions = offlineRegions,
                                downloadProgress = downloadProgress,
                                totalCachedTiles = totalCachedTiles,
                                totalCachedBytes = totalCachedBytes,
                                deviceLocation = deviceLocation,
                                tileManager = viewModel.tileManager,
                                searchResults = mapSearchResults,
                                onSearchPlaces = viewModel::searchMapPlaces,
                                onClearSearch = viewModel::clearMapSearch,
                                onUpdateLayers = viewModel::updateMapLayers,
                                onToggleOfflineMode = viewModel::setOfflineMapMode,
                                onStartRegionDownload = viewModel::startOfflineRegionDownload,
                                onCancelRegionDownload = viewModel::cancelOfflineRegionDownload,
                                onDeleteRegionCache = viewModel::deleteOfflineRegion,
                                onRegisterLocation = viewModel::registerDisasterLocation,
                            )
                            SurakshaSection.ENV_MAPPING -> EnvironmentMappingScreen(
                                settings = settings,
                                networkState = networkState,
                                slamFrame = slamFrame,
                                selectedScenario = selectedScenario,
                                sweepProgress = sweepProgress,
                                isScanning = isScanning,
                                slamSessions = slamSessions,
                                onSelectScenario = viewModel::selectEnvScenario,
                                onSweepProgressChange = viewModel::setSweepProgress,
                                onToggleScanning = viewModel::toggleSlamScanning,
                                onSaveAndExport = viewModel::saveAndExportSlamSession,
                            )
                            SurakshaSection.HUMAN_PRESENCE -> HumanPresenceScreen(
                                settings = settings,
                                networkState = networkState,
                                currentRole = currentRole,
                                signalsState = signalsState,
                                onReviewSignal = viewModel::reviewPresenceSignal,
                                onAddSignal = viewModel::logPresenceObservation,
                            )
                            SurakshaSection.RESCUE_TEAMS -> {
                                val repo = remember(currentUserId) { SurakshaRepository(context) }
                                RescueTeamsScreen(
                                    settings = settings,
                                    networkState = networkState,
                                    currentRole = currentRole,
                                    profileState = profileState,
                                    teamsState = teamsState,
                                    incidentsState = incidentsState,
                                    deviceLocation = deviceLocation,
                                    observeAuditLogs = { incidentId ->
                                        repo.observeSosAuditLogs(activeOrgId, incidentId)
                                    },
                                    onTogglePersonalLocationSharing = viewModel::updateVoluntaryLocationSharing,
                                    onToggleTeamLocationSharing = viewModel::toggleTeamLocationSharing,
                                    onRegisterTeam = viewModel::registerRescueTeam,
                                    onCreateSosIncident = viewModel::createSosIncident,
                                    onTransitionSos = viewModel::transitionSosIncident,
                                )
                            }
                            SurakshaSection.RESCUE_REGISTRY -> RescueRegistryScreen(
                                settings = settings,
                                networkState = networkState,
                                currentRole = currentRole,
                                registryState = registryState,
                                findDuplicateMatch = viewModel::findDuplicateRegistryMatch,
                                onTogglePiiReveal = viewModel::setRevealSensitivePii,
                                onCreateEntry = viewModel::createRegistryEntry,
                                onAdvanceEntry = viewModel::advanceRegistryEntry,
                            )
                            SurakshaSection.SENSOR_STATUS -> SensorStatusScreen(
                                settings = settings,
                                networkState = networkState,
                                adapters = sensorAdapters,
                                cachedLogs = cachedSensorLogs,
                                onSaveGatewayUrl = viewModel::setHardwareGatewayUrl,
                                onPollAdapter = viewModel::pollSensorAdapter,
                            )
                            SurakshaSection.SETTINGS -> SettingsScreen(
                                settings = settings,
                                networkState = networkState,
                                currentRole = currentRole,
                                profileState = profileState,
                                organizationState = orgState,
                                membersState = membersState,
                                invitesState = invitesState,
                                offlineRegions = offlineRegions,
                                pendingSyncOps = pendingSyncOps,
                                onToggleDemoMode = viewModel::setDemoMode,
                                onToggleOfflineMode = viewModel::setOfflineMapMode,
                                onTogglePiiReveal = viewModel::setRevealSensitivePii,
                                onToggleDarkTheme = viewModel::setDarkThemeEnabled,
                                onSetLanguage = viewModel::setLanguageCode,
                                onSeedDemoData = viewModel::seedDemoDataset,
                                onCreateOrganization = viewModel::createOrganization,
                                onJoinOrganization = viewModel::joinOrganization,
                                onGenerateInvite = viewModel::generateOrgInvite,
                                onUpdateMemberRole = viewModel::updateMemberRole,
                                onMigrateLegacyData = viewModel::migrateLegacyData,
                                onFlushSyncQueue = viewModel::flushOfflineQueueNow,
                                onSignOut = {
                                    viewModel.updateVoluntaryLocationSharing(false)
                                    signOutResponder(
                                        credentialManager = credentialManager,
                                        scope = scope,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun sectionIcon(section: SurakshaSection): ImageVector = when (section) {
    SurakshaSection.OVERVIEW -> Icons.Filled.Dashboard
    SurakshaSection.LIVE_MAP -> Icons.Filled.Map
    SurakshaSection.ENV_MAPPING -> Icons.Filled.Radar
    SurakshaSection.HUMAN_PRESENCE -> Icons.Filled.PersonSearch
    SurakshaSection.RESCUE_TEAMS -> Icons.Filled.Groups
    SurakshaSection.RESCUE_REGISTRY -> Icons.AutoMirrored.Filled.Assignment
    SurakshaSection.SENSOR_STATUS -> Icons.Filled.Sensors
    SurakshaSection.SETTINGS -> Icons.Filled.Settings
}

private fun sectionLocalizedTitle(localizedContext: Context, section: SurakshaSection): String {
    val resId = when (section) {
        SurakshaSection.OVERVIEW -> R.string.nav_overview
        SurakshaSection.LIVE_MAP -> R.string.nav_live_map
        SurakshaSection.ENV_MAPPING -> R.string.nav_env_map
        SurakshaSection.HUMAN_PRESENCE -> R.string.nav_presence
        SurakshaSection.RESCUE_TEAMS -> R.string.nav_teams
        SurakshaSection.RESCUE_REGISTRY -> R.string.nav_registry
        SurakshaSection.SENSOR_STATUS -> R.string.nav_sensors
        SurakshaSection.SETTINGS -> R.string.nav_settings
    }
    return localizedContext.getString(resId)
}
