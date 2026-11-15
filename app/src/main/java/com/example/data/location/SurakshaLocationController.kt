package com.example.data.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LocationFixState {
    data class OptedOut(
        val reason: String = "Voluntary GPS sharing is disabled",
    ) : LocationFixState

    data class PermissionDenied(
        val reason: String = "Location permission not granted",
    ) : LocationFixState

    data class GpsDisabled(
        val reason: String = "Location provider disabled on device",
    ) : LocationFixState

    data object Searching : LocationFixState

    data class Available(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val speedMps: Float,
        val timestampMillis: Long,
        val intervalMillis: Long,
        val batteryPercent: Int,
    ) : LocationFixState

    data class Unavailable(
        val reason: String,
    ) : LocationFixState
}

object SurakshaLocationBus {
    private val _state = MutableStateFlow<LocationFixState>(LocationFixState.OptedOut())
    val state: StateFlow<LocationFixState> = _state.asStateFlow()

    internal fun emit(newState: LocationFixState) {
        _state.value = newState
    }

    fun freshnessLabel(nowMillis: Long = System.currentTimeMillis()): String {
        val current = _state.value
        return when (current) {
            is LocationFixState.Available -> {
                val ageSec = ((nowMillis - current.timestampMillis).coerceAtLeast(0L)) / 1000L
                when {
                    ageSec <= 30L -> "Live (${ageSec}s ago, ±${current.accuracyMeters.toInt()}m)"
                    ageSec <= 120L -> "Recent (${ageSec}s ago, ±${current.accuracyMeters.toInt()}m)"
                    else -> "Stale (${ageSec / 60L}m ago, ±${current.accuracyMeters.toInt()}m)"
                }
            }
            is LocationFixState.Searching -> "Acquiring GPS fix..."
            is LocationFixState.PermissionDenied -> "Location unavailable (Permission denied)"
            is LocationFixState.GpsDisabled -> "Location unavailable (GPS disabled)"
            is LocationFixState.OptedOut -> "Location unavailable (Opted out)"
            is LocationFixState.Unavailable -> "Location unavailable (${current.reason})"
        }
    }
}

class SurakshaLocationController(private val context: Context) {
    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private var inlineCallback: LocationCallback? = null

    val locationState: StateFlow<LocationFixState> = SurakshaLocationBus.state

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isDeviceLocationEnabled(): Boolean {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    fun startTracking(useForegroundService: Boolean = true) {
        if (!hasLocationPermission()) {
            SurakshaLocationBus.emit(LocationFixState.PermissionDenied())
            return
        }
        if (!isDeviceLocationEnabled()) {
            SurakshaLocationBus.emit(LocationFixState.GpsDisabled())
            return
        }

        SurakshaLocationBus.emit(LocationFixState.Searching)

        if (useForegroundService) {
            try {
                val intent = Intent(appContext, SurakshaLocationService::class.java).apply {
                    action = SurakshaLocationService.ACTION_START
                }
                ContextCompat.startForegroundService(appContext, intent)
                return
            } catch (_: Exception) {
                // Fall back to direct fused client updates if foreground start restricted
            }
        }
        startInlineUpdates()
    }

    fun stopTracking() {
        stopInlineUpdates()
        try {
            val intent = Intent(appContext, SurakshaLocationService::class.java).apply {
                action = SurakshaLocationService.ACTION_STOP
            }
            appContext.startService(intent)
        } catch (_: Exception) {
            // Safe ignore if service is not running
        }
        SurakshaLocationBus.emit(LocationFixState.OptedOut())
    }

    @Suppress("MissingPermission")
    private fun startInlineUpdates() {
        if (!hasLocationPermission()) return
        stopInlineUpdates()
        val battery = readBatteryPercent(appContext)
        val (intervalMs, priority) = computeBatteryAwarePolicy(battery)
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis((intervalMs / 2L).coerceAtLeast(5_000L))
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (!isValidCoordinate(loc.latitude, loc.longitude)) return
                SurakshaLocationBus.emit(
                    LocationFixState.Available(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracyMeters = loc.accuracy,
                        speedMps = loc.speed,
                        timestampMillis = loc.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        intervalMillis = intervalMs,
                        batteryPercent = readBatteryPercent(appContext),
                    )
                )
            }
        }
        inlineCallback = callback
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun stopInlineUpdates() {
        inlineCallback?.let { fusedClient.removeLocationUpdates(it) }
        inlineCallback = null
    }

    companion object {
        fun computeBatteryAwarePolicy(batteryPercent: Int): Pair<Long, Int> {
            return when {
                batteryPercent >= 50 -> 10_000L to Priority.PRIORITY_HIGH_ACCURACY
                batteryPercent >= 20 -> 25_000L to Priority.PRIORITY_BALANCED_POWER_ACCURACY
                else -> 60_000L to Priority.PRIORITY_LOW_POWER
            }
        }

        fun isValidCoordinate(lat: Double, lon: Double): Boolean {
            return !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0
        }

        fun readBatteryPercent(context: Context): Int {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 100
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return if (pct in 0..100) pct else 100
        }
    }
}
