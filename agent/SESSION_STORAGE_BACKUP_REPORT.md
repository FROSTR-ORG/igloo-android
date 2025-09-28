# Session Storage Backup/Restore Analysis Report

## Executive Summary

The Igloo PWA is experiencing auto-unlock failures after app restarts because the Android application correctly clears session storage in the `onDestroy()` lifecycle method, but lacks a mechanism to backup and restore session data needed for auto-unlocking functionality.

## Problem Analysis

### Root Cause Identified

**File**: `MainActivity.kt:498`
```kotlin
// Clean up session storage (as per Web Storage API behavior)
if (::storageBridge.isInitialized) {
    storageBridge.clearSessionStorage()
}
```

**File**: `StorageBridge.kt:282-292`
```kotlin
fun clearSessionStorage() {
    Log.d(TAG, "Clearing session storage on app restart")
    try {
        sessionStoragePrefs.edit().clear().apply()
        Log.d(TAG, "Session storage cleared successfully")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to clear session storage", e)
    }
}
```

This behavior is **technically correct** according to the Web Storage API specification, which states that sessionStorage should be cleared when the browsing context is destroyed.

### Auto-Unlock Mechanism Details

The PWA implements auto-unlocking using a session storage key:

**File**: `useBifrost.ts:158`
```typescript
// Store password in sessionStorage for auto-unlock after URI refresh
sessionStorage.setItem('igloo_session_password', password)
```

**File**: `node.tsx:54-56`
```typescript
// First check sessionStorage
const password = sessionStorage.getItem('igloo_session_password')
// If the password is found, unlock the node.
if (password) node.unlock(password)
```

**File**: `useBifrost.ts:120` (cleanup)
```typescript
// Clear session password on logout
sessionStorage.removeItem('igloo_session_password')
```

### Current Session Storage Implementation

The `StorageBridge.kt` uses EncryptedSharedPreferences with AES256-GCM encryption:
- **Local Storage**: `igloo_local_storage` (persists across app restarts)
- **Session Storage**: `igloo_session_storage` (cleared on `onDestroy()`)

## Impact Assessment

### When Auto-Unlock Fails
1. **App Process Termination**: Android kills the app process (common on memory pressure)
2. **Manual App Closure**: User manually closes the app via recent apps
3. **System Restarts**: Device reboot or system updates
4. **Intent-Based Launches**: NIP-55 signing requests that trigger new app instances

### When Auto-Unlock Works
1. **App Backgrounding**: User switches apps but process remains alive
2. **Screen Rotation**: Activity recreation but process persists
3. **Quick App Switching**: App remains in memory

## Technical Solutions

### Option 1: Session Storage Persistence (Recommended)

Modify the StorageBridge to backup session storage to secure persistent storage during app lifecycle events, then restore it on app startup.

**Implementation Strategy**:
```kotlin
// New methods in StorageBridge.kt
fun backupSessionStorage() {
    // Backup session storage to a special "session_backup" key in local storage
    val sessionData = sessionStoragePrefs.all
    val backupJson = gson.toJson(sessionData)
    localStoragePrefs.edit()
        .putString("__igloo_session_backup", backupJson)
        .putLong("__igloo_session_backup_timestamp", System.currentTimeMillis())
        .apply()
}

fun restoreSessionStorage(): Boolean {
    // Check if backup exists and is recent (e.g., within 30 minutes)
    val backupJson = localStoragePrefs.getString("__igloo_session_backup", null)
    val timestamp = localStoragePrefs.getLong("__igloo_session_backup_timestamp", 0)

    if (backupJson != null && isRecentBackup(timestamp)) {
        // Restore session data
        val sessionData = gson.fromJson<Map<String, Any>>(backupJson, Map::class.java)
        val editor = sessionStoragePrefs.edit()
        sessionData.forEach { (key, value) ->
            editor.putString(key, value.toString())
        }
        editor.apply()
        return true
    }
    return false
}

private fun isRecentBackup(timestamp: Long): Boolean {
    return (System.currentTimeMillis() - timestamp) < (30 * 60 * 1000) // 30 minutes
}
```

**Integration Points**:
- `onPause()`: Call `backupSessionStorage()`
- `onCreate()`: Call `restoreSessionStorage()` before initializing WebView
- `onDestroy()`: Keep existing `clearSessionStorage()` for cleanup

### Option 2: Auto-Unlock Timeout Extension

Add a timeout-based auto-unlock mechanism that persists credentials in local storage with expiration.

**Implementation Strategy**:
```typescript
// In useBifrost.ts
const setSessionPassword = (password: string) => {
    // Store in session storage (existing behavior)
    sessionStorage.setItem('igloo_session_password', password)

    // Also store with timeout in local storage as backup
    const autoUnlockData = {
        password: password,
        expires: Date.now() + (30 * 60 * 1000) // 30 minutes
    }
    localStorage.setItem('igloo_auto_unlock_backup', JSON.stringify(autoUnlockData))
}

const getSessionPassword = (): string | null => {
    // First check session storage (existing behavior)
    let password = sessionStorage.getItem('igloo_session_password')
    if (password) return password

    // Fallback to local storage backup if recent
    const backupData = localStorage.getItem('igloo_auto_unlock_backup')
    if (backupData) {
        const parsed = JSON.parse(backupData)
        if (Date.now() < parsed.expires) {
            // Restore to session storage for subsequent checks
            sessionStorage.setItem('igloo_session_password', parsed.password)
            return parsed.password
        } else {
            // Expired, clean up
            localStorage.removeItem('igloo_auto_unlock_backup')
        }
    }

    return null
}
```

### Option 3: Selective Session Storage Persistence

Only persist specific session storage keys that are critical for auto-unlock.

**Implementation Strategy**:
```kotlin
// In StorageBridge.kt
companion object {
    private val PERSISTENT_SESSION_KEYS = setOf(
        "igloo_session_password"
    )
}

fun backupCriticalSessionData() {
    val criticalData = mutableMapOf<String, String>()
    PERSISTENT_SESSION_KEYS.forEach { key ->
        sessionStoragePrefs.getString(key, null)?.let { value ->
            criticalData[key] = value
        }
    }

    if (criticalData.isNotEmpty()) {
        val backupJson = gson.toJson(criticalData)
        localStoragePrefs.edit()
            .putString("__igloo_critical_session_backup", backupJson)
            .putLong("__igloo_critical_session_timestamp", System.currentTimeMillis())
            .apply()
    }
}
```

## Security Considerations

### Encryption Maintained
All solutions maintain the existing AES256-GCM encryption provided by EncryptedSharedPreferences.

### Timeout Protection
Backup data should have reasonable expiration (recommended: 30 minutes) to prevent indefinite persistence of sensitive credentials.

### Cleanup on Logout
Ensure all backup mechanisms are cleared when the user explicitly logs out via `useBifrost.clear()`.

## Recommended Implementation

**Option 1 (Session Storage Persistence)** is recommended because:

1. **Transparency**: PWA code remains unchanged - maintains web compatibility
2. **Completeness**: Preserves all session storage, not just auto-unlock credentials
3. **Standards Compliance**: Maintains Web Storage API semantics while adding Android-specific persistence
4. **Minimal Risk**: Uses existing secure storage infrastructure

## Implementation Steps

1. **Add backup methods** to `StorageBridge.kt`
2. **Modify MainActivity lifecycle methods**:
   - `onPause()`: Backup session storage
   - `onCreate()`: Restore session storage if recent backup exists
3. **Add configuration options** for backup timeout (default 30 minutes)
4. **Test scenarios**:
   - Normal app backgrounding (should work unchanged)
   - App process termination and restart (should auto-unlock)
   - Manual logout (should clear all data including backups)
   - Long periods (should timeout and require re-authentication)

## Testing Validation

### Test Cases
1. **App Kill Test**: Force-stop app, relaunch, verify auto-unlock works
2. **Intent Launch Test**: Use NIP-55 intent to launch app, verify auto-unlock works
3. **Timeout Test**: Wait >30 minutes, verify auto-unlock fails and requires re-login
4. **Logout Test**: Explicit logout, verify backup is cleared and auto-unlock fails
5. **Background Test**: Normal app backgrounding, verify existing behavior unchanged

### Monitoring
Add logging to track backup/restore operations:
- Backup creation timestamps
- Restore success/failure
- Timeout expirations
- Manual cleanup events

---

*Analysis completed - September 27, 2025*