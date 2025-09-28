# Session Storage Persistence Implementation Plan

## Overview
Implement session storage backup/restore functionality to maintain auto-unlock capability after app restarts while preserving Web Storage API semantics and security.

## Implementation Steps

### Phase 1: StorageBridge Enhancement
**File**: `app/src/main/kotlin/com/frostr/igloo/bridges/StorageBridge.kt`

#### Step 1.1: Add Configuration Constants
```kotlin
companion object {
    // ... existing constants ...
    private const val SESSION_BACKUP_KEY = "__igloo_session_backup"
    private const val SESSION_BACKUP_TIMESTAMP_KEY = "__igloo_session_backup_timestamp"
    private const val BACKUP_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
}
```

#### Step 1.2: Add Backup Method
```kotlin
/**
 * Backup session storage to persistent local storage
 * Called during app pause to preserve session data across process termination
 */
fun backupSessionStorage() {
    Log.d(TAG, "Backing up session storage")

    try {
        val sessionData = sessionStoragePrefs.all
        if (sessionData.isNotEmpty()) {
            val backupJson = gson.toJson(sessionData)
            val timestamp = System.currentTimeMillis()

            localStoragePrefs.edit()
                .putString(SESSION_BACKUP_KEY, backupJson)
                .putLong(SESSION_BACKUP_TIMESTAMP_KEY, timestamp)
                .apply()

            Log.d(TAG, "Session storage backed up successfully (${sessionData.size} items)")
        } else {
            Log.d(TAG, "No session data to backup")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to backup session storage", e)
    }
}
```

#### Step 1.3: Add Restore Method
```kotlin
/**
 * Restore session storage from persistent backup
 * Called during app startup to restore session data after process termination
 * @return true if backup was restored, false if no valid backup found
 */
fun restoreSessionStorage(): Boolean {
    Log.d(TAG, "Attempting to restore session storage from backup")

    try {
        val backupJson = localStoragePrefs.getString(SESSION_BACKUP_KEY, null)
        val timestamp = localStoragePrefs.getLong(SESSION_BACKUP_TIMESTAMP_KEY, 0)

        if (backupJson == null) {
            Log.d(TAG, "No session backup found")
            return false
        }

        if (!isRecentBackup(timestamp)) {
            Log.d(TAG, "Session backup expired, cleaning up")
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

        Log.d(TAG, "Session storage restored successfully (${sessionData.size} items)")
        return true

    } catch (e: Exception) {
        Log.e(TAG, "Failed to restore session storage", e)
        clearSessionBackup() // Clean up corrupted backup
        return false
    }
}
```

#### Step 1.4: Add Helper Methods
```kotlin
/**
 * Check if backup timestamp is within acceptable window
 */
private fun isRecentBackup(timestamp: Long): Boolean {
    val age = System.currentTimeMillis() - timestamp
    val isRecent = age <= BACKUP_TIMEOUT_MS

    Log.d(TAG, "Backup age: ${age / 1000}s (timeout: ${BACKUP_TIMEOUT_MS / 1000}s) - Recent: $isRecent")
    return isRecent
}

/**
 * Clear session backup data from local storage
 */
fun clearSessionBackup() {
    Log.d(TAG, "Clearing session backup")

    try {
        localStoragePrefs.edit()
            .remove(SESSION_BACKUP_KEY)
            .remove(SESSION_BACKUP_TIMESTAMP_KEY)
            .apply()

        Log.d(TAG, "Session backup cleared successfully")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to clear session backup", e)
    }
}
```

#### Step 1.5: Enhance Clear Method
```kotlin
/**
 * Enhanced clear method to also clear backups on explicit logout
 */
fun clearSessionStorageAndBackup() {
    clearSessionStorage() // Existing method
    clearSessionBackup()  // New method
    Log.d(TAG, "Session storage and backup cleared")
}
```

### Phase 2: MainActivity Integration
**File**: `app/src/main/kotlin/com/frostr/igloo/MainActivity.kt`

#### Step 2.1: Modify onCreate()
Add restoration logic after storage bridge initialization:
```kotlin
// After: storageBridge = StorageBridge(this)
// Add:
if (storageBridge.restoreSessionStorage()) {
    Log.d(TAG, "Session storage restored from backup")
} else {
    Log.d(TAG, "No session storage backup to restore")
}
```

#### Step 2.2: Modify onPause()
Add backup logic:
```kotlin
override fun onPause() {
    super.onPause()
    Log.d(TAG, "=== SECURE ACTIVITY LIFECYCLE: onPause ===")

    // Backup session storage before app may be terminated
    if (::storageBridge.isInitialized) {
        storageBridge.backupSessionStorage()
    }

    webView.onPause()
    webView.pauseTimers()
}
```

#### Step 2.3: Enhance onDestroy()
Keep existing session clearing but add logging:
```kotlin
// Keep existing: storageBridge.clearSessionStorage()
// But add: Log.d(TAG, "Session storage cleared (backup preserved)")
```

### Phase 3: PWA Integration Point
**File**: `src/hooks/useBifrost.ts`

#### Step 3.1: Enhance Logout Function
Update the clear() function to notify Android of explicit logout:
```typescript
const clear = () => {
    // Clear the node.
    setClient(null)
    // Clear session password on logout
    sessionStorage.removeItem('igloo_session_password')

    // Notify Android to clear backup on explicit logout
    if (window.androidBridge && window.androidBridge.clearSessionStorageAndBackup) {
        window.androidBridge.clearSessionStorageAndBackup()
    }

    if (!is_store_ready(settings.data)) {
        setStatus('init')
    } else {
        setStatus('locked')
    }
}
```

## Implementation Order

1. **StorageBridge Enhancement** (Steps 1.1-1.5)
2. **MainActivity Integration** (Steps 2.1-2.3)
3. **Build and Test Basic Functionality**
4. **PWA Integration** (Step 3.1)
5. **Comprehensive Testing**

## Testing Strategy

### Test Cases
1. **Normal Backgrounding**: Verify existing behavior unchanged
2. **Process Termination**: Force-stop app, verify auto-unlock works on restart
3. **Intent Launch**: Test NIP-55 intent launching with auto-unlock
4. **Backup Timeout**: Wait >30 minutes, verify auto-unlock fails
5. **Explicit Logout**: Verify backup cleared and auto-unlock fails
6. **Corrupted Backup**: Test graceful handling of invalid backup data

### Validation Commands
```bash
# Monitor backup/restore operations
adb logcat -s "StorageBridge:*" | grep -E "(backup|restore|session)"

# Force app termination test
adb shell am force-stop com.frostr.igloo.debug
adb shell am start -n com.frostr.igloo.debug/com.frostr.igloo.MainActivity

# Test NIP-55 intent with auto-unlock
adb shell am start \
    -a android.intent.action.VIEW \
    -d "nostrsigner:%7B%22content%22%3A%20%22test%22%2C%20%22kind%22%3A%201%7D" \
    --es type "sign_event" \
    --es id "test_auto_unlock" \
    -n com.frostr.igloo.debug/com.frostr.igloo.InvisibleNIP55Handler
```

## Security Considerations

- **Encryption Maintained**: All backup data uses existing EncryptedSharedPreferences
- **Timeout Protection**: 30-minute expiration prevents indefinite credential persistence
- **Explicit Cleanup**: Manual logout clears both session storage and backups
- **Error Handling**: Corrupted backups are safely cleaned up
- **Logging**: Comprehensive logging for debugging without exposing sensitive data

## Risk Mitigation

- **Backward Compatibility**: Existing behavior preserved for normal app usage
- **Graceful Degradation**: System continues working if backup/restore fails
- **Data Validation**: JSON parsing with proper error handling
- **Timeout Safety**: Automatic cleanup of expired backups

---

Ready to implement Phase 1 (StorageBridge Enhancement) first!