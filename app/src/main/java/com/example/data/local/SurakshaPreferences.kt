package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.surakshaDataStore by preferencesDataStore(name = "suraksha_settings")

data class SurakshaSettings(
    val demoModeEnabled: Boolean = true,
    val offlineMapMode: Boolean = false,
    val revealSensitivePii: Boolean = false,
)

class SurakshaPreferences(private val context: Context) {
    private val demoModeKey = booleanPreferencesKey("demo_mode_enabled")
    private val offlineModeKey = booleanPreferencesKey("offline_map_mode")
    private val revealPiiKey = booleanPreferencesKey("reveal_sensitive_pii")

    val settingsFlow: Flow<SurakshaSettings> = context.surakshaDataStore.data.map { prefs ->
        SurakshaSettings(
            demoModeEnabled = prefs[demoModeKey] ?: true,
            offlineMapMode = prefs[offlineModeKey] ?: false,
            revealSensitivePii = prefs[revealPiiKey] ?: false,
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
}
