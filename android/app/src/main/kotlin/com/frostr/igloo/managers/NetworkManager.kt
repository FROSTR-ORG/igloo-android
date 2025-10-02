package com.frostr.igloo.managers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.frostr.igloo.models.AppState

/**
 * NetworkManager - Network state and quality monitoring
 *
 * Features:
 * - Network availability tracking
 * - Network quality determination (WiFi vs cellular)
 * - Adaptive reconnection delays based on network type
 * - Callbacks for state changes
 *
 * Battery Impact: Helps avoid unnecessary reconnections on poor networks
 */
class NetworkManager(
    private val context: Context,
    private val onNetworkStateChange: (Boolean) -> Unit,
    private val onNetworkQualityChange: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "NetworkManager"
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var isNetworkAvailable: Boolean = false
    private var currentNetworkType: String = "none"
    private var networkQuality: String = "none"

    /**
     * Initialize network monitoring
     */
    fun initialize() {
        Log.d(TAG, "Initializing NetworkManager")

        // Check initial state
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        isNetworkAvailable = capabilities != null
        currentNetworkType = determineNetworkType(capabilities)
        networkQuality = getNetworkQuality(capabilities)

        Log.d(TAG, "Initial network: available=$isNetworkAvailable, type=$currentNetworkType, quality=$networkQuality")

        // Register network callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            Log.d(TAG, "✓ NetworkManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Cleanup
     */
    fun cleanup() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Network callback
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available")
            isNetworkAvailable = true
            onNetworkStateChange(true)
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
            isNetworkAvailable = false
            currentNetworkType = "none"
            networkQuality = "none"
            onNetworkStateChange(false)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val newType = determineNetworkType(networkCapabilities)
            val newQuality = getNetworkQuality(networkCapabilities)

            if (newType != currentNetworkType || newQuality != networkQuality) {
                Log.d(TAG, "Network changed: type=$currentNetworkType→$newType, quality=$networkQuality→$newQuality")

                currentNetworkType = newType
                networkQuality = newQuality

                onNetworkQualityChange?.invoke(newQuality)
            }
        }
    }

    /**
     * Determine network type (WiFi, cellular, ethernet, etc.)
     */
    private fun determineNetworkType(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "none"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    /**
     * Get network quality (high, medium, low, none)
     */
    fun getNetworkQuality(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "none"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val isUnmetered = capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                )
                if (isUnmetered) "high" else "medium"
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val isUnmetered = capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                )
                if (isUnmetered) "high" else "low"
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "high"
            else -> "medium"
        }
    }

    /**
     * Calculate optimal reconnect delay based on network and app state
     *
     * This adapts reconnection timing to avoid hammering poor networks
     * and respect doze mode constraints.
     */
    fun calculateOptimalReconnectDelay(
        baseDelay: Long,
        currentAppState: AppState
    ): Long {
        var adjustedDelay = baseDelay

        // Adjust based on network type and quality
        when (currentNetworkType) {
            "cellular" -> {
                adjustedDelay = when (networkQuality) {
                    "low" -> (baseDelay * 2.0).toLong()   // Double for poor cellular
                    "medium" -> (baseDelay * 1.5).toLong() // 50% longer
                    else -> baseDelay
                }
            }
            "wifi" -> {
                adjustedDelay = when (networkQuality) {
                    "high" -> (baseDelay * 0.8).toLong()  // Faster on good WiFi
                    else -> baseDelay
                }
            }
            "none" -> {
                // No network - use much longer delay
                adjustedDelay = baseDelay * 4
            }
        }

        // Adjust based on app state
        adjustedDelay = when (currentAppState) {
            AppState.FOREGROUND -> adjustedDelay
            AppState.BACKGROUND -> (adjustedDelay * 1.5).toLong()
            AppState.DOZE -> (adjustedDelay * 3.0).toLong()
            AppState.RARE -> (adjustedDelay * 2.5).toLong()
            AppState.RESTRICTED -> (adjustedDelay * 4.0).toLong()
        }

        return adjustedDelay
    }

    // Public accessors
    fun isNetworkAvailable(): Boolean = isNetworkAvailable
    fun getCurrentNetworkType(): String = currentNetworkType
    fun getNetworkQuality(): String = networkQuality
}
