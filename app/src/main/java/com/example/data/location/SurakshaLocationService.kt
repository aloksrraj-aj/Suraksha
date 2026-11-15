package com.example.data.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices

class SurakshaLocationService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopUpdatesAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val notification = buildNotification("Active continuous rescue GPS tracking (voluntary)")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                startContinuousUpdates()
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    @Suppress("MissingPermission")
    private fun startContinuousUpdates() {
        val hasPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPerm) {
            SurakshaLocationBus.emit(LocationFixState.PermissionDenied())
            stopUpdatesAndSelf()
            return
        }

        locationCallback?.let { fusedClient.removeLocationUpdates(it) }

        val battery = SurakshaLocationController.readBatteryPercent(this)
        val (intervalMs, priority) = SurakshaLocationController.computeBatteryAwarePolicy(battery)
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis((intervalMs / 2L).coerceAtLeast(5_000L))
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (!SurakshaLocationController.isValidCoordinate(loc.latitude, loc.longitude)) return
                SurakshaLocationBus.emit(
                    LocationFixState.Available(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracyMeters = loc.accuracy,
                        speedMps = loc.speed,
                        timestampMillis = loc.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        intervalMillis = intervalMs,
                        batteryPercent = SurakshaLocationController.readBatteryPercent(this@SurakshaLocationService),
                    )
                )
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable && SurakshaLocationBus.state.value !is LocationFixState.Available) {
                    SurakshaLocationBus.emit(LocationFixState.Unavailable("Waiting for GNSS or network fix"))
                }
            }
        }
        locationCallback = callback
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun stopUpdatesAndSelf() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        SurakshaLocationBus.emit(LocationFixState.OptedOut())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Suraksha Active Field GPS",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent notice when responder opts in to sharing continuous field GPS coordinates."
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SurakshaLocationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Stop GPS Sharing", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.suraksha.location.START"
        const val ACTION_STOP = "com.example.suraksha.location.STOP"
        private const val CHANNEL_ID = "suraksha_location_channel"
        private const val NOTIFICATION_ID = 4012
    }
}
