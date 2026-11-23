package com.example.myasr.context.provisioning.infrastructure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Securely persists the optional developer GitHub Personal Access Token (PAT)
 * inside `context.noBackupFilesDir` encrypted with an Android Keystore AES-256-GCM key.
 *
 * Fail-safe design:
 * - Never writes or logs raw tokens to Logcat.
 * - Never backs up secrets to Google Auto Backup (`noBackupFilesDir`).
 * - Any keystore/decryption/IO failure fails gracefully to `null` (unconfigured) without crashing.
 */
class GitHubTokenStore(private val context: Context) {

    companion object {
        private const val TAG = "GitHubTokenStore"
        private const val FILE_NAME = "github_actions_pat.dat"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jotinput_github_pat_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        private const val MODE_KEYSTORE_GCM = "keystore_gcm_v1"
        private const val MODE_PLAIN_FALLBACK = "plain_fallback_v1"

        fun maskToken(token: String?): String? {
            val trimmed = token?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("github_pat_") && trimmed.length > 16) {
                return "github_pat_••••${trimmed.takeLast(4)}"
            }
            if (trimmed.length > 8) {
                return "${trimmed.take(4)}••••${trimmed.takeLast(4)}"
            }
            return "••••"
        }
    }

    private val lock = Any()

    private fun getTokenFile(): File {
        val baseDir = runCatching { context.noBackupFilesDir }.getOrNull() ?: context.filesDir
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return File(baseDir, FILE_NAME)
    }

    fun hasToken(): Boolean = !getToken().isNullOrBlank()

    fun getMaskedPreview(): String? = maskToken(getToken())

    fun saveToken(rawToken: String): Boolean {
        val trimmed = rawToken.trim()
        if (trimmed.isEmpty()) {
            return false
        }
        synchronized(lock) {
            return try {
                val payload = encryptPayload(trimmed)
                val targetFile = getTokenFile()
                val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
                tempFile.writeText(payload.toString(), StandardCharsets.UTF_8)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    targetFile.writeText(payload.toString(), StandardCharsets.UTF_8)
                    tempFile.delete()
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save GitHub token safely: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    fun getToken(): String? {
        synchronized(lock) {
            val file = getTokenFile()
            if (!file.exists() || file.length() <= 0L) {
                return null
            }
            return try {
                val json = JSONObject(file.readText(StandardCharsets.UTF_8))
                val mode = json.optString("mode", "")
                when (mode) {
                    MODE_KEYSTORE_GCM -> {
                        val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
                        val cipherBytes = Base64.decode(json.getString("data"), Base64.NO_WRAP)
                        val key = getOrCreateKeyStoreKey() ?: return null
                        val cipher = Cipher.getInstance(TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                        val plainBytes = cipher.doFinal(cipherBytes)
                        String(plainBytes, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                    }
                    MODE_PLAIN_FALLBACK -> {
                        val rawBytes = Base64.decode(json.getString("data"), Base64.NO_WRAP)
                        String(rawBytes, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                    }
                    else -> null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read GitHub token (failing safe to unconfigured): ${e.javaClass.simpleName}")
                null
            }
        }
    }

    fun clearToken() {
        synchronized<Unit>(lock) {
            try {
                val file = getTokenFile()
                if (file.exists()) {
                    file.delete()
                }
                val tempFile = File(file.parentFile, "${file.name}.tmp")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                Unit
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear GitHub token: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun encryptPayload(plainToken: String): JSONObject {
        val plainBytes = plainToken.toByteArray(StandardCharsets.UTF_8)
        val key = getOrCreateKeyStoreKey()
        if (key != null) {
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val encrypted = cipher.doFinal(plainBytes)
                JSONObject().apply {
                    put("mode", MODE_KEYSTORE_GCM)
                    put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                }
            } catch (e: Exception) {
                Log.w(TAG, "AndroidKeyStore encryption failed; using fallback store: ${e.javaClass.simpleName}")
                buildFallbackPayload(plainBytes)
            }
        }
        return buildFallbackPayload(plainBytes)
    }

    private fun buildFallbackPayload(plainBytes: ByteArray): JSONObject {
        return JSONObject().apply {
            put("mode", MODE_PLAIN_FALLBACK)
            put("data", Base64.encodeToString(plainBytes, Base64.NO_WRAP))
        }
    }

    private fun getOrCreateKeyStoreKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                return existing
            }
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Throwable) {
            Log.w(TAG, "AndroidKeyStore unavailable (${e.javaClass.simpleName}); falling back cleanly")
            null
        }
    }
}
