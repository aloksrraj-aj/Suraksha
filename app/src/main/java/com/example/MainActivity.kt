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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.example.data.firebase.configureAppCheck
import com.example.data.local.OfflineCacheRepository
import com.example.data.local.SecureTokenStorage
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
import com.example.ui.screens.MissionsDashboardScreen
import com.example.ui.screens.RadarMapScreen
import com.example.ui.screens.SensorsHubScreen
import com.example.ui.screens.SettingsProfileScreen
import com.example.ui.screens.TeamAdminScreen
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
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                email = user.email.orEmpty(),
                photoUrl = user.photoUrl?.toString().orEmpty(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurakshaAuthenticatedShell(
    currentUserId: String,
    displayName: String,
    email: String,
    photoUrl: String,
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
                    tokenStorage = SecureTokenStorage(app),
                    currentUserId = currentUserId,
                    initialDisplayName = displayName,
                    initialEmail = email,
                    initialPhotoUrl = photoUrl,
                )
            }
        }
    )

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val activeOrgId by viewModel.activeOrgId.collectAsStateWithLifecycle()
    val currentRole by viewModel.currentRole.collectAsStateWithLifecycle()
    val isMembershipRevoked by viewModel.isMembershipRevoked.collectAsStateWithLifecycle()
    val profileState by viewModel.userProfileState.collectAsStateWithLifecycle()
    val orgState by viewModel.currentOrganization.collectAsStateWithLifecycle()
    val joinedOrgs by viewModel.joinedOrgs.collectAsStateWithLifecycle()
    val membersState by viewModel.orgMembersState.collectAsStateWithLifecycle()
    val blockedMembersState by viewModel.blockedMembersState.collectAsStateWithLifecycle()
    val invitationsState by viewModel.invitationsState.collectAsStateWithLifecycle()
    val teamsState by viewModel.teamsState.collectAsStateWithLifecycle()
    val missionsState by viewModel.missionsState.collectAsStateWithLifecycle()
    val activeMission by viewModel.activeMission.collectAsStateWithLifecycle()
    val auditLogsState by viewModel.auditLogsState.collectAsStateWithLifecycle()
    val effectiveMemberLocations by viewModel.effectiveMemberLocations.collectAsStateWithLifecycle()
    val effectiveSensorDevices by viewModel.effectiveSensorDevices.collectAsStateWithLifecycle()
    val effectiveSensorEvents by viewModel.effectiveSensorEvents.collectAsStateWithLifecycle()
    val radarTargets by viewModel.radarTargets.collectAsStateWithLifecycle()
    val sosAlertsState by viewModel.sosAlertsState.collectAsStateWithLifecycle()
    val separationWarnings by viewModel.separationWarnings.collectAsStateWithLifecycle()
    val locationFixState by viewModel.locationFixState.collectAsStateWithLifecycle()
    val headingState by viewModel.headingState.collectAsStateWithLifecycle()
    val mapLayers by viewModel.mapLayers.collectAsStateWithLifecycle()
    val mapSearchResults by viewModel.mapSearchResults.collectAsStateWithLifecycle()
    val offlineRegions by viewModel.offlineRegions.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val totalCachedTiles by viewModel.totalCachedTileCount.collectAsStateWithLifecycle()
    val totalCachedBytes by viewModel.totalCachedBytes.collectAsStateWithLifecycle()
    val pendingSyncOps by viewModel.pendingSyncOps.collectAsStateWithLifecycle()
    val syncHistory by viewModel.syncHistory.collectAsStateWithLifecycle()
    val gatewayStatusText by viewModel.gatewayStatusText.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()

    val localizedContext = remember(context, settings.languageCode) {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(settings.languageCode))
        }
        context.createConfigurationContext(config)
    }

    var currentSection by rememberSaveable { mutableStateOf(SurakshaSection.MISSIONS) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(activeOrgId) {
        if (activeOrgId.isNotBlank()) {
            viewModel.syncPushRegistration()
        }
    }

    LaunchedEffect(actionMessage) {
        actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionMessage()
        }
    }

    if (currentSection != SurakshaSection.MISSIONS) {
        BackHandler { currentSection = SurakshaSection.MISSIONS }
    }

    val primaryTabs = remember {
        listOf(
            SurakshaSection.MISSIONS,
            SurakshaSection.RADAR,
            SurakshaSection.SENSORS,
            SurakshaSection.TEAM,
        )
    }

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
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = sectionLocalizedTitle(localizedContext, currentSection),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                if (settings.devDemoModeEnabled) {
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
                                onClick = { currentSection = SurakshaSection.SETTINGS },
                                modifier = Modifier.testTag("topbar_settings_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Settings and Profile",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
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
                            primaryTabs.forEach { section ->
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
                        SurakshaSection.MISSIONS -> MissionsDashboardScreen(
                            settings = settings,
                            networkState = networkState,
                            pendingSyncCount = pendingSyncOps.size,
                            currentRole = currentRole,
                            isMembershipRevoked = isMembershipRevoked,
                            organizationState = orgState,
                            joinedOrgs = joinedOrgs,
                            teamsState = teamsState,
                            missionsState = missionsState,
                            activeMission = activeMission,
                            memberLocations = effectiveMemberLocations,
                            sensorDevices = effectiveSensorDevices,
                            sensorEvents = effectiveSensorEvents,
                            sosAlertsState = sosAlertsState,
                            separationWarnings = separationWarnings,
                            locationFixState = locationFixState,
                            onCreateOrganization = viewModel::createOrganization,
                            onJoinOrganization = viewModel::joinOrganization,
                            onSelectWorkspace = viewModel::selectWorkspace,
                            onCreateMission = viewModel::createMission,
                            onUpdateMissionSettings = viewModel::updateMissionSettings,
                            onToggleLocationSharing = viewModel::updateVoluntaryLocationSharing,
                            onDismissBatteryPrompt = viewModel::dismissBatteryOptimizationPrompt,
                            onTriggerSos = { desc -> viewModel.triggerSosAlert(desc) },
                            onOpenRadar = { currentSection = SurakshaSection.RADAR },
                            onFlushSync = viewModel::flushOfflineQueueNow,
                        )

                        SurakshaSection.RADAR -> RadarMapScreen(
                            settings = settings,
                            networkState = networkState,
                            currentUserId = currentUserId,
                            activeMission = activeMission,
                            radarTargets = radarTargets,
                            memberLocations = effectiveMemberLocations,
                            sensorDevices = effectiveSensorDevices,
                            sensorEvents = effectiveSensorEvents,
                            sosAlertsState = sosAlertsState,
                            headingState = headingState,
                            deviceLocation = locationFixState,
                            mapLayers = mapLayers,
                            mapSearchResults = mapSearchResults,
                            tileManager = viewModel.tileManager,
                            onSetRadarRange = viewModel::setRadarRangeMeters,
                            onSetNorthUp = viewModel::setNorthUpMode,
                            onUpdateMapLayers = viewModel::updateMapLayers,
                            onSearchPlaces = viewModel::searchMapPlaces,
                            onClearSearch = viewModel::clearMapSearch,
                            onLogObservation = viewModel::logDetectionObservation,
                            onTriggerSos = { desc -> viewModel.triggerSosAlert(desc) },
                            onTransitionSos = viewModel::transitionSosAlert,
                        )

                        SurakshaSection.SENSORS -> SensorsHubScreen(
                            settings = settings,
                            networkState = networkState,
                            currentRole = currentRole,
                            hardwareCatalog = viewModel.hardwareCatalog,
                            sensorDevices = effectiveSensorDevices,
                            sensorEvents = effectiveSensorEvents,
                            gatewayStatusText = gatewayStatusText,
                            maskedGatewayToken = viewModel.tokenStorage.maskedTokenPreview(),
                            onSaveGatewayConfig = viewModel::registerGatewayAndSaveConfig,
                            onPollGateway = viewModel::pollConfiguredGateway,
                            onValidateAndIngestJson = viewModel::validateAndIngestGatewayJson,
                            onToggleDevDemoMode = viewModel::setDevDemoMode,
                        )

                        SurakshaSection.TEAM -> TeamAdminScreen(
                            currentUserId = currentUserId,
                            currentRole = currentRole,
                            isMembershipRevoked = isMembershipRevoked,
                            organizationState = orgState,
                            membersState = membersState,
                            blockedMembersState = blockedMembersState,
                            invitationsState = invitationsState,
                            teamsState = teamsState,
                            auditLogsState = auditLogsState,
                            onToggleAllowMemberInvites = viewModel::toggleAllowMemberInvites,
                            onCreateInvitation = viewModel::createInvitation,
                            onApproveOrRejectInvite = viewModel::approveOrRejectInvitation,
                            onUpdateMemberRole = viewModel::updateMemberRole,
                            onRemoveAndBlockMember = viewModel::removeAndBlockMember,
                            onReinstateBlockedMember = viewModel::reinstateBlockedMember,
                            onCreateTeam = viewModel::createTeam,
                        )

                        SurakshaSection.SETTINGS -> SettingsProfileScreen(
                            settings = settings,
                            networkState = networkState,
                            currentRole = currentRole,
                            profileState = profileState,
                            offlineRegions = offlineRegions,
                            downloadProgress = downloadProgress,
                            totalCachedTiles = totalCachedTiles,
                            totalCachedBytes = totalCachedBytes,
                            pendingSyncOps = pendingSyncOps,
                            syncHistory = syncHistory,
                            onUpdateProfile = viewModel::updateProfileDetails,
                            onToggleDarkTheme = viewModel::setDarkThemeEnabled,
                            onSetLanguage = viewModel::setLanguageCode,
                            onToggleOfflineMapMode = viewModel::setOfflineMapMode,
                            onStartRegionDownload = { reg -> viewModel.startOfflineRegionDownload(reg) },
                            onCancelRegionDownload = viewModel::cancelOfflineRegionDownload,
                            onDeleteRegionCache = viewModel::deleteOfflineRegion,
                            onFlushSyncQueue = viewModel::flushOfflineQueueNow,
                            onSyncPushToken = viewModel::syncPushRegistration,
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

private fun sectionIcon(section: SurakshaSection): ImageVector = when (section) {
    SurakshaSection.MISSIONS -> Icons.Filled.Dashboard
    SurakshaSection.RADAR -> Icons.Filled.Radar
    SurakshaSection.SENSORS -> Icons.Filled.Sensors
    SurakshaSection.TEAM -> Icons.Filled.Groups
    SurakshaSection.SETTINGS -> Icons.Filled.Settings
}

private fun sectionLocalizedTitle(localizedContext: Context, section: SurakshaSection): String {
    val resId = when (section) {
        SurakshaSection.MISSIONS -> R.string.nav_missions
        SurakshaSection.RADAR -> R.string.nav_radar
        SurakshaSection.SENSORS -> R.string.nav_sensors
        SurakshaSection.TEAM -> R.string.nav_team
        SurakshaSection.SETTINGS -> R.string.nav_settings
    }
    return localizedContext.getString(resId)
}
