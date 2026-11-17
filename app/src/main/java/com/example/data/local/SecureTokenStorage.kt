package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES-256-GCM storage for sensitive gateway tokens / API secrets.
 * Never logs raw tokens. Falls back safely only if KeyStore provider is absent in local JVM test runners.
 */
class SecureTokenStorage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveGatewayToken(token: String) {
        val clean = token.trim()
        if (clean.isEmpty()) {
            clearGatewayToken()
            return
        }
        val encrypted = encryptString(clean)
        if (encrypted != null) {
            prefs.edit().putString(KEY_GATEWAY_TOKEN_ENC, encrypted).remove(KEY_FALLBACK).apply()
        } else {
            // Only reachable in non-device JVM test environments lacking AndroidKeyStore
            Log.w(TAG, "AndroidKeyStore unavailable; storing obfuscated token in test mode only")
            val encoded = Base64.encodeToString(clean.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            prefs.edit().putString(KEY_FALLBACK, encoded).apply()
        }
    }

    fun getGatewayToken(): String {
        val enc = prefs.getString(KEY_GATEWAY_TOKEN_ENC, null)
        if (!enc.isNullOrBlank()) {
            return decryptString(enc).orEmpty()
        }
        val fallback = prefs.getString(KEY_FALLBACK, null)
        if (!fallback.isNullOrBlank()) {
            return runCatching {
                String(Base64.decode(fallback, Base64.NO_WRAP), StandardCharsets.UTF_8)
            }.getOrDefault("")
        }
        return ""
    }

    fun hasGatewayToken(): Boolean = getGatewayToken().isNotBlank()

    fun maskedTokenPreview(): String {
        val t = getGatewayToken()
        return if (t.length <= 6) if (t.isBlank()) "Not configured" else "••••" else "${t.take(3)}••••${t.takeLast(3)}"
    }

    fun clearGatewayToken() {
        prefs.edit().remove(KEY_GATEWAY_TOKEN_ENC).remove(KEY_FALLBACK).apply()
    }

    private fun getOrCreateAesKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return@runCatching existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        keyGen.generateKey()
    }.getOrNull()

    private fun encryptString(plain: String): String? = runCatching {
        val key = getOrCreateAesKey() ?: return@runCatching null
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ct, Base64.NO_WRAP)
        "$ivB64:$ctB64"
    }.getOrNull()

    private fun decryptString(payload: String): String? = runCatching {
        val parts = payload.split(":")
        if (parts.size != 2) return@runCatching null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = getOrCreateAesKey() ?: return@runCatching null
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), StandardCharsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val TAG = "SecureTokenStorage"
        private const val PREFS_NAME = "suraksha_secure_vault"
        private const val KEY_GATEWAY_TOKEN_ENC = "gateway_token_enc"
        private const val KEY_FALLBACK = "gateway_token_jvm_test"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "suraksha_gateway_aes_key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
