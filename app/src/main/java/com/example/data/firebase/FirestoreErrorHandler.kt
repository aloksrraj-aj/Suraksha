package com.example.data.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun redactPiiForLog(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    var sanitized = raw
        .replace(Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"), "[REDACTED_EMAIL]")
        .replace(Regex("(\\+?\\d{1,3}[\\s-]?)?\\d{3,5}[\\s-]?\\d{4,6}"), "[REDACTED_PHONE]")
    return sanitized.take(240)
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?,
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = try {
        FirebaseAuth.getInstance()
    } catch (_: Exception) {
        null
    }
    val currentUser = auth?.currentUser

    // Never log raw email addresses or PII in Logcat
    val providerIds = currentUser?.providerData?.map { it.providerId }.orEmpty()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid?.take(12)?.plus("…") ?: "unauthenticated")
        put("isSignedIn", currentUser != null)
        put("emailVerified", currentUser?.isEmailVerified ?: false)
        put("providers", JSONArray(providerIds))
    }

    val errorInfoJson = JSONObject().apply {
        put("errorCode", (rootError as? FirebaseFirestoreException)?.code?.name ?: rootError.javaClass.simpleName)
        put("error", redactPiiForLog(rootError.message ?: rootError.toString()))
        put("operationType", operationType.value)
        put("path", redactPiiForLog(path))
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}
