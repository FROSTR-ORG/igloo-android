package com.frostr.igloo.services

import android.util.Log
import com.frostr.igloo.bridges.StorageBridge
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Centralized permission checking for NIP-55 requests.
 *
 * Replaces duplicate permission logic in:
 * - InvisibleNIP55Handler.kt:checkPermission()
 * - NIP55ContentProvider.kt:hasAutomaticPermission() (partial - still uses WebView JS)
 *
 * Permissions are stored in localStorage under key "nip55_permissions_v2" with format:
 * {
 *   "permissions": [
 *     { "appId": "app.package", "type": "sign_event", "kind": 1, "allowed": true, "timestamp": 123 },
 *     { "appId": "app.package", "type": "nip04_encrypt", "kind": null, "allowed": true, "timestamp": 123 }
 *   ]
 * }
 */
class PermissionChecker(
    private val storageBridge: StorageBridge
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "PermissionChecker"
        private const val PERMISSIONS_KEY = "nip55_permissions_v2"
    }

    /**
     * Check permission for a NIP-55 request.
     *
     * @param appId The calling app's package name
     * @param operationType The NIP-55 operation type (e.g., "sign_event", "nip04_encrypt")
     * @param eventKind Optional event kind for sign_event requests
     * @return PermissionResult indicating allowed, denied, or prompt required
     */
    fun checkPermission(
        appId: String,
        operationType: String,
        eventKind: Int? = null
    ): PermissionResult {
        return try {
            val permissionsJson = storageBridge.getItem("local", PERMISSIONS_KEY)
                ?: return PermissionResult.PromptRequired.also {
                    Log.d(TAG, "No permissions found, prompt required")
                }

            val storage = gson.fromJson(permissionsJson, PermissionStorage::class.java)
            val permissions = storage.permissions

            // For sign_event with kind, check kind-specific permission first
            if (operationType == "sign_event" && eventKind != null) {
                // Check kind-specific permission
                val kindSpecific = permissions.find { p ->
                    p.appId == appId &&
                    p.type == operationType &&
                    p.kind == eventKind
                }

                if (kindSpecific != null) {
                    Log.d(TAG, "Found kind-specific permission: $appId:$operationType:$eventKind = ${kindSpecific.allowed}")
                    return if (kindSpecific.allowed) {
                        PermissionResult.Allowed(kindSpecific.timestamp)
                    } else {
                        PermissionResult.Denied(kindSpecific.timestamp)
                    }
                }

                // Fall back to wildcard permission (kind = null)
                val wildcard = permissions.find { p ->
                    p.appId == appId &&
                    p.type == operationType &&
                    p.kind == null
                }

                if (wildcard != null) {
                    Log.d(TAG, "Found wildcard permission: $appId:$operationType = ${wildcard.allowed}")
                    return if (wildcard.allowed) {
                        PermissionResult.Allowed(wildcard.timestamp)
                    } else {
                        PermissionResult.Denied(wildcard.timestamp)
                    }
                }
            } else {
                // For non-sign_event or sign_event without kind, simple lookup
                val permission = permissions.find { p ->
                    p.appId == appId &&
                    p.type == operationType &&
                    p.kind == null
                }

                if (permission != null) {
                    Log.d(TAG, "Found permission: $appId:$operationType = ${permission.allowed}")
                    return if (permission.allowed) {
                        PermissionResult.Allowed(permission.timestamp)
                    } else {
                        PermissionResult.Denied(permission.timestamp)
                    }
                }
            }

            Log.d(TAG, "No matching permission found for $appId:$operationType, prompt required")
            PermissionResult.PromptRequired

        } catch (e: Exception) {
            Log.e(TAG, "Error checking permission", e)
            PermissionResult.PromptRequired
        }
    }

    /**
     * Extract event kind from a NIP-55 sign_event request.
     *
     * @param eventJson The JSON string of the event
     * @return The event kind, or null if not found or invalid
     */
    fun extractEventKind(eventJson: String?): Int? {
        if (eventJson == null) return null

        return try {
            val eventMap = gson.fromJson<Map<String, Any>>(eventJson, object : TypeToken<Map<String, Any>>() {}.type)
            (eventMap["kind"] as? Double)?.toInt()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract event kind from request", e)
            null
        }
    }

    /**
     * Convert PermissionResult to the legacy string format used by InvisibleNIP55Handler.
     */
    fun toStatusString(result: PermissionResult): String {
        return when (result) {
            is PermissionResult.Allowed -> "allowed"
            is PermissionResult.Denied -> "denied"
            is PermissionResult.PromptRequired -> "prompt_required"
        }
    }
}

/**
 * Result of a permission check.
 */
sealed class PermissionResult {
    data class Allowed(val timestamp: Long) : PermissionResult()
    data class Denied(val timestamp: Long) : PermissionResult()
    object PromptRequired : PermissionResult()
}

/**
 * Storage wrapper for permissions JSON.
 */
data class PermissionStorage(
    val permissions: List<StoredPermission>
)

/**
 * Individual permission entry.
 */
data class StoredPermission(
    val appId: String,
    val type: String,
    val kind: Int?,
    val allowed: Boolean,
    val timestamp: Long
)
