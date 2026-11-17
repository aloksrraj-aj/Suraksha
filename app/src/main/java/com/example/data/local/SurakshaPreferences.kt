package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.surakshaDataStore by preferencesDataStore(name = "suraksha_settings")

data class SurakshaSettings(
    val devDemoModeEnabled: Boolean = false,
    val offlineMapMode: Boolean = false,
    val darkThemeEnabled: Boolean = true,
    val languageCode: String = "en",
    val activeOrgId: String = "",
    val activeTeamId: String = "",
    val activeMissionId: String = "",
    val hardwareGatewayUrl: String = "",
    val gatewayTransportType: String = "http_tls",
    val customTileServerUrl: String = "",
    val locationConsentEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val radarRangeMeters: Double = 100.0,
    val northUpMode: Boolean = true,
    val batteryOptPromptDismissed: Boolean = false,
)

class SurakshaPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val devDemoModeKey = booleanPreferencesKey("dev_demo_mode_enabled")
    private val offlineModeKey = booleanPreferencesKey("offline_map_mode")
    private val darkThemeKey = booleanPreferencesKey("dark_theme_enabled")
    private val languageCodeKey = stringPreferencesKey("language_code")
    private val activeOrgIdKey = stringPreferencesKey("active_org_id")
    private val activeTeamIdKey = stringPreferencesKey("active_team_id")
    private val activeMissionIdKey = stringPreferencesKey("active_mission_id")
    private val hardwareGatewayUrlKey = stringPreferencesKey("hardware_gateway_url")
    private val gatewayTransportKey = stringPreferencesKey("gateway_transport_type")
    private val customTileServerUrlKey = stringPreferencesKey("custom_tile_server_url")
    private val locationConsentKey = booleanPreferencesKey("location_consent_enabled")
    private val notificationsEnabledKey = booleanPreferencesKey("notifications_enabled")
    private val radarRangeMetersKey = doublePreferencesKey("radar_range_meters")
    private val northUpModeKey = booleanPreferencesKey("north_up_mode")
    private val batteryOptDismissedKey = booleanPreferencesKey("battery_opt_prompt_dismissed")

    val settingsFlow: Flow<SurakshaSettings> = appContext.surakshaDataStore.data.map { prefs ->
        SurakshaSettings(
            devDemoModeEnabled = prefs[devDemoModeKey] ?: false,
            offlineMapMode = prefs[offlineModeKey] ?: false,
            darkThemeEnabled = prefs[darkThemeKey] ?: true,
            languageCode = prefs[languageCodeKey] ?: "en",
            activeOrgId = prefs[activeOrgIdKey] ?: "",
            activeTeamId = prefs[activeTeamIdKey] ?: "",
            activeMissionId = prefs[activeMissionIdKey] ?: "",
            hardwareGatewayUrl = prefs[hardwareGatewayUrlKey] ?: "",
            gatewayTransportType = prefs[gatewayTransportKey] ?: "http_tls",
            customTileServerUrl = prefs[customTileServerUrlKey] ?: "",
            locationConsentEnabled = prefs[locationConsentKey] ?: false,
            notificationsEnabled = prefs[notificationsEnabledKey] ?: true,
            radarRangeMeters = prefs[radarRangeMetersKey] ?: 100.0,
            northUpMode = prefs[northUpModeKey] ?: true,
            batteryOptPromptDismissed = prefs[batteryOptDismissedKey] ?: false,
        )
    }

    suspend fun setDevDemoMode(enabled: Boolean) {
        appContext.surakshaDataStore.edit { it[devDemoModeKey] = enabled }
    }

    suspend fun setOfflineMapMode(enabled: Boolean) {
        appContext.surakshaDataStore.edit { it[offlineModeKey] = enabled }
    }

    suspend fun setDarkThemeEnabled(dark: Boolean) {
        appContext.surakshaDataStore.edit { it[darkThemeKey] = dark }
    }

    suspend fun setLanguageCode(code: String) {
        val normalized = if (code == "hi") "hi" else "en"
        appContext.surakshaDataStore.edit { it[languageCodeKey] = normalized }
    }

    suspend fun setActiveWorkspace(orgId: String, teamId: String, missionId: String) {
        appContext.surakshaDataStore.edit {
            it[activeOrgIdKey] = orgId
            it[activeTeamIdKey] = teamId
            it[activeMissionIdKey] = missionId
        }
    }

    suspend fun setHardwareGatewayConfig(url: String, transportType: String) {
        appContext.surakshaDataStore.edit {
            it[hardwareGatewayUrlKey] = url.trim()
            it[gatewayTransportKey] = transportType
        }
    }

    suspend fun setCustomTileServerUrl(url: String) {
        appContext.surakshaDataStore.edit { it[customTileServerUrlKey] = url.trim() }
    }

    suspend fun setLocationConsentEnabled(enabled: Boolean) {
        persistLocationConsentSync(appContext, enabled)
        appContext.surakshaDataStore.edit { it[locationConsentKey] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        appContext.surakshaDataStore.edit { it[notificationsEnabledKey] = enabled }
    }

    suspend fun setRadarRangeMeters(rangeMeters: Double) {
        appContext.surakshaDataStore.edit { it[radarRangeMetersKey] = rangeMeters.coerceIn(25.0, 2000.0) }
    }

    suspend fun setNorthUpMode(northUp: Boolean) {
        appContext.surakshaDataStore.edit { it[northUpModeKey] = northUp }
    }

    suspend fun setBatteryOptPromptDismissed(dismissed: Boolean) {
        appContext.surakshaDataStore.edit { it[batteryOptDismissedKey] = dismissed }
    }

    companion object {
        private const val SYNC_PREFS = "suraksha_location_consent_cache"
        private const val SYNC_KEY = "location_consent_active"

        fun persistLocationConsentSync(context: Context, enabled: Boolean) {
            context.applicationContext
                .getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(SYNC_KEY, enabled)
                .apply()
        }

        fun isLocationConsentPersisted(context: Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                .getBoolean(SYNC_KEY, false)
        }
    }
}
