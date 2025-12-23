package com.frostr.igloo.di

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.frostr.igloo.bridges.BridgeFactory
import com.frostr.igloo.bridges.StorageBridge
import com.frostr.igloo.health.IglooHealthManager
import com.frostr.igloo.services.PermissionChecker

/**
 * Simple dependency container for Igloo.
 *
 * Provides singleton instances and factory methods for testability.
 * Uses lazy initialization to defer creation until first use.
 *
 * ## Singleton Management
 *
 * This container manages application-scoped singletons. Components that
 * need cross-process communication (like NIP-55 bridges) remain as Kotlin
 * objects for process isolation compatibility.
 *
 * ## Static Singletons (intentionally not managed here)
 *
 * The following remain as static singletons for cross-component/cross-process
 * communication:
 * - IglooHealthManager - Health-based request routing (singleton)
 * - NIP55Deduplicator - Stateless utility
 * - NIP55Metrics - Debug metrics collection
 *
 * Usage:
 * ```kotlin
 * val container = AppContainer.getInstance(context)
 * val storageBridge = container.storageBridge
 * val factory = container.createBridgeFactory()
 * ```
 */
class AppContainer(private val context: Context) {

    // ==================== Core Singletons ====================

    val storageBridge: StorageBridge by lazy { StorageBridge(context) }

    val permissionChecker: PermissionChecker by lazy {
        PermissionChecker(storageBridge)
    }

    // ==================== NIP-55 Components ====================

    /**
     * Get the health manager for NIP-55 request routing.
     * Note: IglooHealthManager is a Kotlin object (singleton).
     */
    val healthManager: IglooHealthManager
        get() = IglooHealthManager

    // ==================== Factories ====================

    /**
     * Create a new BridgeFactory instance.
     */
    fun createBridgeFactory(): BridgeFactory = BridgeFactory(context)

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        @Volatile
        private var testInstance: AppContainer? = null

        /**
         * Get the singleton instance.
         *
         * @param context Application context (will use applicationContext)
         * @return The singleton AppContainer instance
         */
        fun getInstance(context: Context): AppContainer {
            // Test instance takes priority if set
            testInstance?.let { return it }

            // Double-checked locking for thread safety
            return instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * Set a test instance (for unit testing).
         *
         * When set, getInstance() will return this instance instead of creating
         * a new one. Call with null to clear the test instance.
         *
         * @param container The test container or null to clear
         */
        @VisibleForTesting
        fun setTestInstance(container: AppContainer?) {
            testInstance = container
        }

        /**
         * Clear all instances (for testing cleanup).
         *
         * Should be called in test teardown to ensure clean state.
         */
        @VisibleForTesting
        fun reset() {
            testInstance = null
            instance = null
        }
    }
}
