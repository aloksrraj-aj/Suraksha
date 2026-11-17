package com.example.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.local.SurakshaSettings
import com.example.data.location.CompassReliability
import com.example.data.location.HeadingState
import com.example.data.model.BlockedMembership
import com.example.data.model.EnvironmentMode
import com.example.data.model.OrgMember
import com.example.data.model.OrgRole
import com.example.data.model.RadarCategory
import com.example.data.model.RadarTarget
import com.example.data.network.NetworkState
import com.example.data.repository.UiState
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.components.HybridRadarCanvas
import com.example.ui.components.NoLiveSensorDataBanner
import com.example.ui.screens.SettingsProfileScreen
import com.example.ui.screens.TeamAdminScreen
import com.example.ui.theme.SurakshaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SurakshaUiComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun banners_displayDevDemoOfflinePendingSyncAndNoLiveSensorData() {
        composeRule.setContent {
            SurakshaTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Column {
                    DemoAndOfflineStatusBanner(
                        isDemoMode = true,
                        isOfflineMode = true,
                        networkState = NetworkState.OFFLINE,
                        pendingSyncCount = 3,
                        languageCode = "en",
                    )
                    NoLiveSensorDataBanner(languageCode = "en")
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("DEV DEMO MODE • TEST FIXTURES ONLY").assertIsDisplayed()
        composeRule.onNodeWithText("OFFLINE MODE • CACHED DATA").assertIsDisplayed()
        composeRule.onNodeWithText("3 pending write(s) queued for sync").assertIsDisplayed()
        composeRule.onNodeWithText("NO LIVE SENSOR DATA — CONNECT A COMPATIBLE SENSOR").assertIsDisplayed()
    }

    @Test
    fun hybridRadarCanvas_displaysOutOfRangeAndMissingDirectionBadges() {
        val targets = listOf(
            RadarTarget(
                id = "t1",
                category = RadarCategory.YELLOW_UNCLASSIFIED_PRESENCE,
                title = "UNCLASSIFIED POSSIBLE HUMAN PRESENCE",
                subtitle = "UWB-1",
                distanceMeters = 350.0,
                bearingDegrees = 45.0,
                hasDirection = true,
            ),
            RadarTarget(
                id = "t2",
                category = RadarCategory.GREY_STALE_OR_UNCERTAIN,
                title = "UNCLASSIFIED POSSIBLE HUMAN PRESENCE",
                subtitle = "Acoustic-1",
                distanceMeters = 25.0,
                bearingDegrees = null,
                hasDirection = false,
            ),
        )
        composeRule.setContent {
            SurakshaTheme(darkTheme = true) {
                HybridRadarCanvas(
                    targets = targets,
                    radarRangeMeters = 100.0,
                    environmentMode = EnvironmentMode.OPEN_AREA,
                    headingState = HeadingState(0.0, CompassReliability.NO_COMPASS_HARDWARE),
                    northUpMode = true,
                    selectedTargetId = null,
                    onTargetSelected = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("hybrid_radar_canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("radar_overflow_badge").assertIsDisplayed()
        composeRule.onNodeWithText("1 target(s) outside range").assertIsDisplayed()
        composeRule.onNodeWithText("1 missing direction").assertIsDisplayed()
    }

    @Test
    fun teamAdminScreen_rendersRoleControlsAndReinstatesBlockedMember() {
        var reinstatedUid = ""
        composeRule.setContent {
            SurakshaTheme(darkTheme = true) {
                TeamAdminScreen(
                    currentUserId = "admin_1",
                    currentRole = OrgRole.ORGANIZATION_ADMIN,
                    isMembershipRevoked = false,
                    organizationState = UiState.Success(null),
                    membersState = UiState.Success(
                        listOf(
                            OrgMember(
                                userId = "op_2",
                                orgId = "org_1",
                                displayName = "Sensor Op",
                                callsign = "SEN-2",
                                role = OrgRole.SENSOR_OPERATOR.value,
                            )
                        )
                    ),
                    blockedMembersState = UiState.Success(
                        listOf(
                            BlockedMembership(
                                userId = "blocked_9",
                                orgId = "org_1",
                                reason = "Left assignment",
                            )
                        )
                    ),
                    invitationsState = UiState.Success(emptyList()),
                    teamsState = UiState.Success(emptyList()),
                    auditLogsState = UiState.Success(emptyList()),
                    onToggleAllowMemberInvites = {},
                    onCreateInvitation = { _, _ -> },
                    onApproveOrRejectInvite = { _, _ -> },
                    onUpdateMemberRole = { _, _ -> },
                    onRemoveAndBlockMember = { _, _ -> },
                    onReinstateBlockedMember = { reinstatedUid = it.userId },
                    onCreateTeam = { _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("create_invite_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("reinstate_member_blocked_9").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("blocked_9", reinstatedUid)
    }

    @Test
    fun settingsProfileScreen_themeToggleAndLanguageSelectionWorkCorrectly() {
        var toggledDarkTheme = false
        var selectedLanguage = "en"

        composeRule.setContent {
            SurakshaTheme(darkTheme = false) {
                SettingsProfileScreen(
                    settings = SurakshaSettings(
                        devDemoModeEnabled = false,
                        offlineMapMode = false,
                        darkThemeEnabled = false,
                        languageCode = "en",
                    ),
                    networkState = NetworkState.ONLINE_UNMETERED,
                    currentRole = OrgRole.ORGANIZATION_ADMIN,
                    profileState = UiState.Success(null),
                    offlineRegions = emptyList(),
                    downloadProgress = emptyMap(),
                    totalCachedTiles = 0,
                    totalCachedBytes = 0L,
                    pendingSyncOps = emptyList(),
                    syncHistory = emptyList(),
                    onUpdateProfile = { _, _, _ -> },
                    onToggleDarkTheme = { toggledDarkTheme = it },
                    onSetLanguage = { selectedLanguage = it },
                    onToggleOfflineMapMode = {},
                    onStartRegionDownload = {},
                    onCancelRegionDownload = {},
                    onDeleteRegionCache = {},
                    onFlushSyncQueue = {},
                    onSyncPushToken = {},
                    onSignOut = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Appearance & Localization").assertIsDisplayed()
        composeRule.onNodeWithTag("theme_toggle_switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(toggledDarkTheme)

        composeRule.onNodeWithText("हिन्दी").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("hi", selectedLanguage)
    }

    @Test
    fun localizationResources_englishAndHindi_resolveProperly() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val hiConfig = Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("hi"))
        }
        val hiContext = baseContext.createConfigurationContext(hiConfig)

        assertEquals("Suraksha", baseContext.getString(R.string.app_name))
        assertEquals("सुरक्षा", hiContext.getString(R.string.app_name))
        assertEquals("Send Emergency SOS", baseContext.getString(R.string.sos_button_label))
        assertEquals("आपातकालीन SOS भेजें", hiContext.getString(R.string.sos_button_label))
        assertEquals("NO LIVE SENSOR DATA — CONNECT A COMPATIBLE SENSOR", baseContext.getString(R.string.no_live_sensor_data))
        assertEquals("कोई लाइव सेंसर डेटा नहीं — संगत सेंसर कनेक्ट करें", hiContext.getString(R.string.no_live_sensor_data))
    }
}
