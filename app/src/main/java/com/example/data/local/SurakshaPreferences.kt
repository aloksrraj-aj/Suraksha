package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.surakshaDataStore by preferencesDataStore(name = "suraksha_settings")

data class SurakshaSettings(
    val demoModeEnabled: Boolean = true,
    val offlineMapMode: Boolean = false,
    val revealSensitivePii: Boolean = false,
    val darkThemeEnabled: Boolean = true,
    val languageCode: String = "en",
    val activeOrgId: String = "",
    val hardwareGatewayUrl: String = "",
    val hardwareGatewayToken: String = "",
    val slamGatewayUrl: String = "",
    val customTileServerUrl: String = "",
    val locationConsentEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
)

class SurakshaPreferences(private val context: Context) {
    private val appContext = context.applicationContext
    private val demoModeKey = booleanPreferencesKey("demo_mode_enabled")
    private val offlineModeKey = booleanPreferencesKey("offline_map_mode")
    private val revealPiiKey = booleanPreferencesKey("reveal_sensitive_pii")
    private val darkThemeKey = booleanPreferencesKey("dark_theme_enabled")
    private val languageCodeKey = stringPreferencesKey("language_code")
    private val activeOrgIdKey = stringPreferencesKey("active_org_id")
    private val hardwareGatewayUrlKey = stringPreferencesKey("hardware_gateway_url")
    private val hardwareGatewayTokenKey = stringPreferencesKey("hardware_gateway_token")
    private val slamGatewayUrlKey = stringPreferencesKey("slam_gateway_url")
    private val customTileServerUrlKey = stringPreferencesKey("custom_tile_server_url")
    private val locationConsentKey = booleanPreferencesKey("location_consent_enabled")
    private val notificationsEnabledKey = booleanPreferencesKey("notifications_enabled")

    val settingsFlow: Flow<SurakshaSettings> = appContext.surakshaDataStore.data.map { prefs ->
        SurakshaSettings(
            demoModeEnabled = prefs[demoModeKey] ?: true,
            offlineMapMode = prefs[offlineModeKey] ?: false,
            revealSensitivePii = prefs[revealPiiKey] ?: false,
            darkThemeEnabled = prefs[darkThemeKey] ?: true,
            languageCode = prefs[languageCodeKey] ?: "en",
            activeOrgId = prefs[activeOrgIdKey] ?: "",
            hardwareGatewayUrl = prefs[hardwareGatewayUrlKey] ?: "",
            hardwareGatewayToken = prefs[hardwareGatewayTokenKey] ?: "",
            slamGatewayUrl = prefs[slamGatewayUrlKey] ?: "",
            customTileServerUrl = prefs[customTileServerUrlKey] ?: "",
            locationConsentEnabled = prefs[locationConsentKey] ?: false,
            notificationsEnabled = prefs[notificationsEnabledKey] ?: true,
        )
    }

    suspend fun setDemoMode(enabled: Boolean) {
        appContext.surakshaDataStore.edit { it[demoModeKey] = enabled }
    }

    suspend fun setOfflineMapMode(enabled: Boolean) {
        appContext.surakshaDataStore.edit { it[offlineModeKey] = enabled }
    }

    suspend fun setRevealSensitivePii(reveal: Boolean) {
        appContext.surakshaDataStore.edit { it[revealPiiKey] = reveal }
    }

    suspend fun setDarkThemeEnabled(dark: Boolean) {
        appContext.surakshaDataStore.edit { it[darkThemeKey] = dark }
    }

    suspend fun setLanguageCode(code: String) {
        val normalized = if (code == "hi") "hi" else "en"
        appContext.surakshaDataStore.edit { it[languageCodeKey] = normalized }
    }

    suspend fun setActiveOrgId(orgId: String) {
        appContext.surakshaDataStore.edit { it[activeOrgIdKey] = orgId }
    }

    suspend fun setHardwareGatewayUrl(url: String) {
        appContext.surakshaDataStore.edit { it[hardwareGatewayUrlKey] = url.trim() }
    }

    suspend fun setHardwareGatewayToken(token: String) {
        appContext.surakshaDataStore.edit { it[hardwareGatewayTokenKey] = token.trim() }
    }

    suspend fun setSlamGatewayUrl(url: String) {
        appContext.surakshaDataStore.edit { it[slamGatewayUrlKey] = url.trim() }
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
