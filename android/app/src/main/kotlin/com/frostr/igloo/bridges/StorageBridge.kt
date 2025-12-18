package com.frostr.igloo.bridges

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Storage Bridge - Encrypted replacement for localStorage/sessionStorage
 *
 * This bridge provides secure, encrypted storage using Android's EncryptedSharedPreferences
 * while maintaining 100% API compatibility with the Web Storage API.
 * The PWA remains completely unaware of this implementation.
 */
class StorageBridge(private val context: Context) {

    companion object {
        private const val TAG = "StorageBridge"
        private const val LOCAL_STORAGE_PREFS = "igloo_local_storage"
        private const val SESSION_STORAGE_PREFS = "igloo_session_storage"
        private const val STORAGE_QUOTA_BYTES = 10 * 1024 * 1024 // 10MB quota
        private const val SESSION_BACKUP_KEY = "__igloo_session_backup"
        private const val SESSION_BACKUP_TIMESTAMP_KEY = "__igloo_session_backup_timestamp"
        private const val BACKUP_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
    }

    private val gson = Gson()
    private val masterKeyAlias: String

    // Encrypted SharedPreferences instances
    private val localStoragePrefs: SharedPreferences
    private val sessionStoragePrefs: SharedPreferences

    init {
        try {
            // Create master key alias for encryption (uses AES256-GCM)
            masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

            // Create encrypted SharedPreferences for localStorage
            localStoragePrefs = EncryptedSharedPreferences.create(
                LOCAL_STORAGE_PREFS,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            // Create encrypted SharedPreferences for sessionStorage
            sessionStoragePrefs = EncryptedSharedPreferences.create(
                SESSION_STORAGE_PREFS,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize secure storage bridge", e)
            throw IllegalStateException("Secure storage initialization failed", e)
        }
    }

    /**
     * Set an item in storage (localStorage or sessionStorage)
     * @param storageType "local" or "session"
     * @param key Storage key
     * @param value Storage value
     * @return Success/error result
     */
    @JavascriptInterface
    fun setItem(storageType: String, key: String, value: String): String {
        return try {
            // Validate parameters
            if (key.isEmpty()) {
                return gson.toJson(StorageResult.Error("Key cannot be empty"))
            }

            // Check storage quota
            val prefs = getPreferences(storageType)
            if (!checkStorageQuota(prefs, key, value)) {
                return gson.toJson(StorageResult.Error("Storage quota exceeded"))
            }

            // Store the value
            prefs.edit().putString(key, value).apply()

            gson.toJson(StorageResult.Success("Item stored"))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to set item in storage", e)
            gson.toJson(StorageResult.Error("Storage failed: ${e.message}"))
        }
    }

    /**
     * Get an item from storage
     * @param storageType "local" or "session"
     * @param key Storage key
     * @return Item value or null
     */
    @JavascriptInterface
    fun getItem(storageType: String, key: String): String? {
        return try {
            val prefs = getPreferences(storageType)
            prefs.getString(key, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get item from storage", e)
            null
        }
    }

    /**
     * Remove an item from storage
     * @param storageType "local" or "session"
     * @param key Storage key
     * @return Success/error result
     */
    @JavascriptInterface
    fun removeItem(storageType: String, key: String): String {
        return try {
            val prefs = getPreferences(storageType)
            prefs.edit().remove(key).apply()
            gson.toJson(StorageResult.Success("Item removed"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove item from storage", e)
            gson.toJson(StorageResult.Error("Remove failed: ${e.message}"))
        }
    }

    /**
     * Clear all items from storage
     * @param storageType "local" or "session"
     * @return Success/error result
     */
    @JavascriptInterface
    fun clear(storageType: String): String {
        return try {
            val prefs = getPreferences(storageType)
            prefs.edit().clear().apply()
            gson.toJson(StorageResult.Success("Storage cleared"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear storage", e)
            gson.toJson(StorageResult.Error("Clear failed: ${e.message}"))
        }
    }

    /**
     * Get key at index (for Storage.key() method)
     * @param storageType "local" or "session"
     * @param index Key index
     * @return Key name or null
     */
    @JavascriptInterface
    fun key(storageType: String, index: Int): String? {
        return try {
            val prefs = getPreferences(storageType)
            val keys = prefs.all.keys.toList().sorted()
            if (index >= 0 && index < keys.size) keys[index] else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get key at index", e)
            null
        }
    }

    /**
     * Get the number of items in storage
     * @param storageType "local" or "session"
     * @return Number of items
     */
    @JavascriptInterface
    fun length(storageType: String): Int {
        return try {
            val prefs = getPreferences(storageType)
            prefs.all.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage length", e)
            0
        }
    }

    /**
     * Get all keys from storage (for debugging/admin purposes)
     * @param storageType "local" or "session"
     * @return JSON array of keys
     */
    @JavascriptInterface
    fun getAllKeys(storageType: String): String {
        return try {
            val prefs = getPreferences(storageType)
            val keys = prefs.all.keys.toList().sorted()
            gson.toJson(keys)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all keys", e)
            gson.toJson(emptyList<String>())
        }
    }

    /**
     * Get storage usage information
     * @param storageType "local" or "session"
     * @return Storage usage info JSON
     */
    @JavascriptInterface
    fun getStorageInfo(storageType: String): String {
        return try {
            val prefs = getPreferences(storageType)
            val allData = prefs.all

            var totalBytes = 0
            allData.forEach { (key, value) ->
                totalBytes += key.toByteArray(Charsets.UTF_8).size
                totalBytes += (value?.toString() ?: "").toByteArray(Charsets.UTF_8).size
            }

            val storageInfo = StorageInfo(
                itemCount = allData.size,
                totalBytes = totalBytes,
                quotaBytes = STORAGE_QUOTA_BYTES,
                usagePercent = (totalBytes.toDouble() / STORAGE_QUOTA_BYTES * 100).toInt()
            )

            gson.toJson(storageInfo)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage info", e)
            gson.toJson(StorageInfo(0, 0, STORAGE_QUOTA_BYTES, 0))
        }
    }

    /**
     * Clear session storage (called on app restart)
     */
    fun clearSessionStorage() {
        try {
            sessionStoragePrefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear session storage", e)
        }
    }

    /**
     * Backup session storage to persistent local storage
     * Called during app pause to preserve session data across process termination
     */
    fun backupSessionStorage() {
        try {
            val sessionData = sessionStoragePrefs.all
            if (sessionData.isNotEmpty()) {
                val backupJson = gson.toJson(sessionData)
                val timestamp = System.currentTimeMillis()
                localStoragePrefs.edit()
                    .putString(SESSION_BACKUP_KEY, backupJson)
                    .putLong(SESSION_BACKUP_TIMESTAMP_KEY, timestamp)
                    .apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup session storage", e)
        }
    }

    /**
     * Restore session storage from persistent backup
     * Called during app startup to restore session data after process termination
     * @return true if backup was restored, false if no valid backup found
     */
    fun restoreSessionStorage(): Boolean {
        try {
            val backupJson = localStoragePrefs.getString(SESSION_BACKUP_KEY, null)
            val timestamp = localStoragePrefs.getLong(SESSION_BACKUP_TIMESTAMP_KEY, 0)

            if (backupJson == null) return false

            if (!isRecentBackup(timestamp)) {
                clearSessionBackup()
                return false
            }

            // Parse and restore session data
            val sessionData = gson.fromJson<Map<String, Any>>(backupJson,
                object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type)

            val editor = sessionStoragePrefs.edit()
            sessionData.forEach { (key, value) ->
                editor.putString(key, value.toString())
            }
            editor.apply()
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore session storage", e)
            clearSessionBackup()
            return false
        }
    }

    /**
     * Check if backup timestamp is within acceptable window
     */
    private fun isRecentBackup(timestamp: Long): Boolean {
        val age = System.currentTimeMillis() - timestamp
        return age <= BACKUP_TIMEOUT_MS
    }

    /**
     * Clear session backup data from local storage
     */
    fun clearSessionBackup() {
        try {
            localStoragePrefs.edit()
                .remove(SESSION_BACKUP_KEY)
                .remove(SESSION_BACKUP_TIMESTAMP_KEY)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear session backup", e)
        }
    }

    /**
     * Enhanced clear method to also clear backups on explicit logout
     */
    @JavascriptInterface
    fun clearSessionStorageAndBackup(): String {
        return try {
            clearSessionStorage()
            clearSessionBackup()
            gson.toJson(StorageResult.Success("Session storage and backup cleared"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear session storage and backup", e)
            gson.toJson(StorageResult.Error("Clear failed: ${e.message}"))
        }
    }

    /**
     * Get the appropriate SharedPreferences instance
     */
    private fun getPreferences(storageType: String): SharedPreferences {
        return when (storageType.lowercase()) {
            "local" -> localStoragePrefs
            "session" -> sessionStoragePrefs
            else -> throw IllegalArgumentException("Invalid storage type: $storageType")
        }
    }

    /**
     * Check if storing a value would exceed storage quota
     */
    private fun checkStorageQuota(prefs: SharedPreferences, key: String, value: String): Boolean {
        try {
            val allData = prefs.all.toMutableMap()

            // Simulate storing the new value
            allData[key] = value

            var totalBytes = 0
            allData.forEach { (k, v) ->
                totalBytes += k.toByteArray(Charsets.UTF_8).size
                totalBytes += (v?.toString() ?: "").toByteArray(Charsets.UTF_8).size
            }

            val withinQuota = totalBytes <= STORAGE_QUOTA_BYTES

            if (!withinQuota) {
                Log.w(TAG, "Storage quota would be exceeded: ${totalBytes}/${STORAGE_QUOTA_BYTES} bytes")
            }

            return withinQuota

        } catch (e: Exception) {
            Log.e(TAG, "Error checking storage quota", e)
            return false
        }
    }

    /**
     * Generate storage event data for cross-tab communication
     * Note: In our single-WebView scenario, this is mainly for completeness
     */
    fun generateStorageEvent(
        storageType: String,
        key: String?,
        oldValue: String?,
        newValue: String?
    ): String {
        val storageEvent = StorageEvent(
            key = key,
            oldValue = oldValue,
            newValue = newValue,
            url = "igloopwa://app/",
            storageArea = storageType
        )

        return gson.toJson(storageEvent)
    }
}

/**
 * Data classes for storage bridge communication
 */

sealed class StorageResult {
    data class Success(val message: String) : StorageResult()
    data class Error(val message: String) : StorageResult()
}

data class StorageInfo(
    val itemCount: Int,
    val totalBytes: Int,
    val quotaBytes: Int,
    val usagePercent: Int
)

data class StorageEvent(
    val key: String?,
    val oldValue: String?,
    val newValue: String?,
    val url: String,
    val storageArea: String
)