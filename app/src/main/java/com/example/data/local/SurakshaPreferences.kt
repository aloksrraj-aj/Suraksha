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
)

class SurakshaPreferences(private val context: Context) {
    private val demoModeKey = booleanPreferencesKey("demo_mode_enabled")
    private val offlineModeKey = booleanPreferencesKey("offline_map_mode")
    private val revealPiiKey = booleanPreferencesKey("reveal_sensitive_pii")
    private val darkThemeKey = booleanPreferencesKey("dark_theme_enabled")
    private val languageCodeKey = stringPreferencesKey("language_code")
    private val activeOrgIdKey = stringPreferencesKey("active_org_id")
    private val hardwareGatewayUrlKey = stringPreferencesKey("hardware_gateway_url")

    val settingsFlow: Flow<SurakshaSettings> = context.surakshaDataStore.data.map { prefs ->
        SurakshaSettings(
            demoModeEnabled = prefs[demoModeKey] ?: true,
            offlineMapMode = prefs[offlineModeKey] ?: false,
            revealSensitivePii = prefs[revealPiiKey] ?: false,
            darkThemeEnabled = prefs[darkThemeKey] ?: true,
            languageCode = prefs[languageCodeKey] ?: "en",
            activeOrgId = prefs[activeOrgIdKey] ?: "",
            hardwareGatewayUrl = prefs[hardwareGatewayUrlKey] ?: "",
        )
    }

    suspend fun setDemoMode(enabled: Boolean) {
        context.surakshaDataStore.edit { it[demoModeKey] = enabled }
    }

    suspend fun setOfflineMapMode(enabled: Boolean) {
        context.surakshaDataStore.edit { it[offlineModeKey] = enabled }
    }

    suspend fun setRevealSensitivePii(reveal: Boolean) {
        context.surakshaDataStore.edit { it[revealPiiKey] = reveal }
    }

    suspend fun setDarkThemeEnabled(dark: Boolean) {
        context.surakshaDataStore.edit { it[darkThemeKey] = dark }
    }

    suspend fun setLanguageCode(code: String) {
        val normalized = if (code == "hi") "hi" else "en"
        context.surakshaDataStore.edit { it[languageCodeKey] = normalized }
    }

    suspend fun setActiveOrgId(orgId: String) {
        context.surakshaDataStore.edit { it[activeOrgIdKey] = orgId }
    }

    suspend fun setHardwareGatewayUrl(url: String) {
        context.surakshaDataStore.edit { it[hardwareGatewayUrlKey] = url.trim() }
    }
}
