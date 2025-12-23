package com.frostr.igloo.bridges.interfaces

import com.frostr.igloo.bridges.StorageResult

/**
 * Interface for storage bridge operations.
 *
 * Enables testability by allowing mock implementations of the storage layer.
 * Implementations provide encrypted storage that replaces web localStorage/sessionStorage.
 */
interface IStorageBridge {

    // JavaScript-accessible methods (via @JavascriptInterface in implementations)

    /**
     * Set an item in storage.
     * @param storageType "local" or "session"
     * @param key Storage key
     * @param value Storage value
     * @return JSON result with success/error status
     */
    fun setItem(storageType: String, key: String, value: String): String

    /**
     * Get an item from storage.
     * @param storageType "local" or "session"
     * @param key Storage key
     * @return Item value or null if not found
     */
    fun getItem(storageType: String, key: String): String?

    /**
     * Remove an item from storage.
     * @param storageType "local" or "session"
     * @param key Storage key
     * @return JSON result with success/error status
     */
    fun removeItem(storageType: String, key: String): String

    /**
     * Clear all items from storage.
     * @param storageType "local" or "session"
     * @return JSON result with success/error status
     */
    fun clear(storageType: String): String

    /**
     * Get key at index (for Storage.key() method).
     * @param storageType "local" or "session"
     * @param index Key index
     * @return Key name or null
     */
    fun key(storageType: String, index: Int): String?

    /**
     * Get the number of items in storage.
     * @param storageType "local" or "session"
     * @return Number of items
     */
    fun length(storageType: String): Int

    /**
     * Get all keys from storage.
     * @param storageType "local" or "session"
     * @return JSON array of keys
     */
    fun getAllKeys(storageType: String): String

    /**
     * Get storage usage information.
     * @param storageType "local" or "session"
     * @return Storage usage info JSON
     */
    fun getStorageInfo(storageType: String): String

    /**
     * Clear session storage and its backup.
     * @return JSON result with success/error status
     */
    fun clearSessionStorageAndBackup(): String

    // Android-only methods (not exposed to JavaScript)

    /**
     * Get an item from storage with explicit result type.
     * Unlike getItem() which returns null for both "not found" and "error",
     * this method returns a StorageResult that distinguishes between states.
     *
     * @param storageType "local" or "session"
     * @param key Storage key
     * @return StorageResult with the value, not found, or error
     */
    fun getItemResult(storageType: String, key: String): StorageResult<String>

    /**
     * Backup session storage to persistent local storage.
     * Called during app pause to preserve session data across process termination.
     */
    fun backupSessionStorage()

    /**
     * Restore session storage from persistent backup.
     * Called during app startup to restore session data after process termination.
     * @return true if backup was restored, false if no valid backup found
     */
    fun restoreSessionStorage(): Boolean

    /**
     * Clear session storage (called on app restart).
     */
    fun clearSessionStorage()

    /**
     * Clear session backup data from local storage.
     */
    fun clearSessionBackup()
}
