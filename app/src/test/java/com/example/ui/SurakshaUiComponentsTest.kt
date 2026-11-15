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
import com.example.data.model.OrgRole
import com.example.data.network.NetworkState
import com.example.ui.components.DemoAndOfflineStatusBanner
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.SurakshaTheme
import com.example.ui.viewmodel.DataUiState
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
    fun demoAndOfflineStatusBanner_displaysDemoOfflineAndPendingSyncQueueCount() {
        composeRule.setContent {
            SurakshaTheme(darkTheme = true) {
                DemoAndOfflineStatusBanner(
                    isDemoMode = true,
                    isOfflineMode = true,
                    networkState = NetworkState.OFFLINE,
                    pendingSyncCount = 3,
                    languageCode = "en",
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("DEMO MODE • SYNTHETIC DATA").assertIsDisplayed()
        composeRule.onNodeWithText("NO NETWORK").assertIsDisplayed()
        composeRule.onNodeWithText("3 pending write(s) queued for Firestore sync").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_lightThemeAndThemeToggleCallback_worksCorrectly() {
        var toggledDarkTheme = true
        var selectedLanguage = "en"

        composeRule.setContent {
            SurakshaTheme(darkTheme = false) {
                SettingsScreen(
                    settings = SurakshaSettings(
                        demoModeEnabled = false,
                        offlineMapMode = false,
                        darkThemeEnabled = false,
                        languageCode = "en",
                    ),
                    networkState = NetworkState.ONLINE_UNMETERED,
                    currentRole = OrgRole.ORG_ADMIN,
                    profileState = DataUiState.Success(null),
                    organizationState = DataUiState.Success(null),
                    membersState = DataUiState.Success(emptyList()),
                    invitesState = DataUiState.Success(emptyList()),
                    offlineRegions = emptyList(),
                    pendingSyncOps = emptyList(),
                    onToggleDemoMode = {},
                    onToggleOfflineMode = {},
                    onTogglePiiReveal = {},
                    onToggleDarkTheme = { toggledDarkTheme = it },
                    onSetLanguage = { selectedLanguage = it },
                    onSeedDemoData = {},
                    onCreateOrganization = { _, _, _ -> },
                    onJoinOrganization = { _, _, _ -> },
                    onGenerateInvite = { _, _ -> },
                    onUpdateMemberRole = { _, _ -> },
                    onMigrateLegacyData = {},
                    onFlushSyncQueue = {},
                    onSignOut = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Appearance, Language & Mode").assertIsDisplayed()
        composeRule.onNodeWithTag("create_invite_button").assertIsDisplayed()
        composeRule.onNodeWithTag("theme_toggle_switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(toggledDarkTheme)

        composeRule.onNodeWithTag("lang_chip_hi").performScrollTo().performClick()
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
        assertEquals("SOS अलर्ट भेजें", hiContext.getString(R.string.sos_button_label))
    }
}
