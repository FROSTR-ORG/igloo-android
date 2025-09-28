package com.frostr.igloo.sync

import android.content.Context
import android.util.Log

/**
 * UnifiedPermissionSync - DISABLED
 *
 * Legacy permission synchronization system has been completely removed.
 * All permission handling is now managed by the PWA unified permission system.
 */
class UnifiedPermissionSync(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedPermissionSync"
    }

    init {
        Log.i(TAG, "UnifiedPermissionSync disabled - using PWA unified permissions")
    }

    // All methods stubbed out - legacy permission sync removed
    fun sync() {
        Log.d(TAG, "Sync called but disabled - using PWA unified permissions")
    }
}