package com.example.data.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.SurakshaPreferences

/**
 * Receives BOOT_COMPLETED / MY_PACKAGE_REPLACED to notify the responder if voluntary mission
 * location sharing was previously active before reboot. Never silently restarts background GPS tracking
 * without explicit user foreground confirmation.
 */
class SurakshaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val appCtx = context.applicationContext
        if (!SurakshaPreferences.isLocationConsentPersisted(appCtx)) {
            return
        }

        // Clear persisted active flag on cold reboot so tracking resumes ONLY on explicit user confirmation
        SurakshaPreferences.persistLocationConsentSync(appCtx, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifPerm = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifPerm) return
        }

        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Suraksha Mission Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }

        val launchIntent = PendingIntent.getActivity(
            appCtx,
            0,
            Intent(appCtx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(appCtx.getString(R.string.app_name))
            .setContentText("Device restarted. Open Suraksha to resume voluntary mission location sharing if needed.")
            .setAutoCancel(true)
            .setContentIntent(launchIntent)
            .build()

        nm.notify(REMINDER_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "suraksha_mission_reminders"
        private const val REMINDER_NOTIFICATION_ID = 4099
    }
}
