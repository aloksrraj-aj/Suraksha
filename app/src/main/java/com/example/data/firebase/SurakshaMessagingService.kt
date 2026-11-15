package com.example.data.firebase

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SurakshaMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM device token")
        serviceScope.launch {
            syncDeviceTokenToFirestore(applicationContext, token = token, orgId = "")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "SURAKSHA SOS DISPATCH"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "Incoming disaster alert (${message.data["teamCode"] ?: "FIELD"})"
        val incidentId = message.data["incidentId"].orEmpty()

        showSosNotification(title, body, incidentId)
    }

    private fun showSosNotification(title: String, body: String, incidentId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SOS_CHANNEL_ID,
                "Suraksha Critical SOS Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "High-priority SOS and rescue team dispatch notifications."
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("sos_incident_id", incidentId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            incidentId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, SOS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(incidentId.hashCode(), notification)
    }

    companion object {
        private const val TAG = "SurakshaMessagingSvc"
        const val SOS_CHANNEL_ID = "suraksha_sos_channel"

        suspend fun registerCurrentDeviceToken(
            context: Context,
            orgId: String,
            notificationsEnabled: Boolean = true,
        ): Result<String> {
            return try {
                val token = FirebaseMessaging.getInstance().token.await()
                syncDeviceTokenToFirestore(
                    context = context,
                    token = token,
                    orgId = orgId,
                    notificationsEnabled = notificationsEnabled,
                )
                Result.success(token)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to obtain/register FCM token: ${e.message}")
                Result.failure(e)
            }
        }

        suspend fun syncDeviceTokenToFirestore(
            context: Context,
            token: String,
            orgId: String,
            notificationsEnabled: Boolean = true,
        ) {
            val uid = Firebase.auth.currentUser?.uid ?: return
            if (token.length < 10) return
            val appCtx = context.applicationContext
            val androidId = runCatching {
                Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty().replace(Regex("[^a-zA-Z0-9_-]"), "").take(48)
            val deviceId = if (androidId.isNotBlank()) "android_$androidId" else "android_default"

            val db = FirebaseFirestore.getInstance(
                appCtx.getString(R.string.firestore_database_id)
            )
            db.collection("users").document(uid)
                .collection("devices").document(deviceId)
                .set(
                    mapOf(
                        "userId" to uid,
                        "deviceId" to deviceId,
                        "fcmToken" to token,
                        "orgId" to orgId,
                        "notificationsEnabled" to notificationsEnabled,
                        "platform" to "android",
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
        }
    }
}
