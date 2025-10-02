package com.frostr.igloo.managers

import android.content.Context
import android.util.Log
import com.frostr.igloo.bridges.StorageBridge
import com.frostr.igloo.models.*
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * WebSocketManager - High-level coordinator for relay connections
 *
 * This is the main entry point for the WebSocket infrastructure.
 * It orchestrates all other managers and provides a simple API for
 * IglooBackgroundService to use.
 *
 * Key Responsibilities:
 * - Initialize and coordinate all manager components
 * - Load subscriptions from SecureStorage (without PWA!)
 * - Connect to relays and establish subscriptions
 * - Handle incoming events and route them appropriately
 * - Manage low-power modes and battery optimization
 * - Provide health monitoring and diagnostics
 *
 * Battery Impact: This manager enables the hybrid architecture that
 * achieves 1.2% per hour idle battery consumption.
 */
class WebSocketManager(
    private val context: Context,
    private val storageBridge: StorageBridge,
    private val onEventReceived: (QueuedNostrEvent) -> Unit,
    private val onConnectionStateChange: (Boolean) -> Unit
) {

    companion object {
        private const val TAG = "WebSocketManager"
        private const val SUBSCRIPTION_REFRESH_INTERVAL_MS = 3600000L // 1 hour
    }

    // Manager components
    private lateinit var batteryPowerManager: BatteryPowerManager
    private lateinit var networkManager: NetworkManager
    private lateinit var relayConnectionManager: RelayConnectionManager
    private lateinit var subscriptionManager: SubscriptionManager

    // Gson for JSON parsing
    private val gson = Gson()

    // State
    private var isInitialized = false
    private var isConnected = false
    private var currentSubscriptions: List<NostrSubscription> = emptyList()
    private var currentConfig: SubscriptionConfig? = null

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize the WebSocket infrastructure
     * This sets up all managers and prepares for connection
     */
    fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }

        Log.d(TAG, "Initializing WebSocketManager...")

        try {
            // Initialize BatteryPowerManager first (others depend on it)
            batteryPowerManager = BatteryPowerManager(
                context = context,
                onAppStateChange = { newState, duration ->
                    handleAppStateChange(newState, duration)
                },
                onPingIntervalChange = {
                    handlePingIntervalChange()
                }
            )
            batteryPowerManager.initialize()
            Log.d(TAG, "✓ BatteryPowerManager initialized")

            // Initialize NetworkManager
            networkManager = NetworkManager(
                context = context,
                onNetworkStateChange = { isAvailable ->
                    handleNetworkStateChange(isAvailable)
                },
                onNetworkQualityChange = { quality ->
                    Log.d(TAG, "Network quality changed: $quality")
                }
            )
            networkManager.initialize()
            Log.d(TAG, "✓ NetworkManager initialized")

            // Create initial OkHttpClient with current ping interval
            val initialPingInterval = batteryPowerManager.getCurrentPingInterval()
            val okHttpClient = createOkHttpClient(initialPingInterval)

            // Initialize RelayConnectionManager
            relayConnectionManager = RelayConnectionManager(
                context = context,
                okHttpClient = okHttpClient,
                batteryPowerManager = batteryPowerManager,
                networkManager = networkManager,
                onMessageReceived = { message, relayUrl ->
                    handleRelayMessage(message, relayUrl)
                },
                onConnectionEstablished = { relayUrl ->
                    handleConnectionEstablished(relayUrl)
                },
                onConnectionFailed = { relayUrl, error ->
                    handleConnectionFailed(relayUrl, error)
                }
            )
            Log.d(TAG, "✓ RelayConnectionManager initialized")

            // Initialize SubscriptionManager
            subscriptionManager = SubscriptionManager(
                context = context,
                storageBridge = storageBridge
            )
            Log.d(TAG, "✓ SubscriptionManager initialized")

            isInitialized = true
            Log.d(TAG, "✓ WebSocketManager initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to initialize WebSocketManager", e)
            throw e
        }
    }

    /**
     * Start WebSocket connections
     * This loads subscriptions from SecureStorage and connects to relays
     */
    suspend fun start() {
        if (!isInitialized) {
            Log.e(TAG, "Cannot start - not initialized")
            return
        }

        if (isConnected) {
            Log.w(TAG, "Already started")
            return
        }

        Log.d(TAG, "Starting WebSocket connections...")

        try {
            // Load subscription config from SecureStorage (WITHOUT PWA!)
            currentConfig = subscriptionManager.loadSubscriptionConfig()
            if (currentConfig == null) {
                Log.w(TAG, "No subscription config found - user may not be logged in")
                return
            }

            // Generate subscription filters
            currentSubscriptions = subscriptionManager.generateSubscriptionFilters(currentConfig!!)
            Log.d(TAG, "Generated ${currentSubscriptions.size} subscriptions")

            // Connect to all relays
            connectToRelays()

            // Schedule periodic subscription refresh
            scheduleSubscriptionRefresh()

            isConnected = true
            onConnectionStateChange(true)

            Log.d(TAG, "✓ WebSocket connections started")

        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to start WebSocket connections", e)
            onConnectionStateChange(false)
        }
    }

    /**
     * Stop WebSocket connections
     */
    fun stop() {
        if (!isConnected) {
            Log.w(TAG, "Not connected")
            return
        }

        Log.d(TAG, "Stopping WebSocket connections...")

        try {
            relayConnectionManager.disconnectAll()
            currentSubscriptions = emptyList()
            isConnected = false
            onConnectionStateChange(false)

            Log.d(TAG, "✓ WebSocket connections stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket connections", e)
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up WebSocketManager...")

        stop()

        try {
            if (isInitialized) {
                batteryPowerManager.cleanup()
                networkManager.cleanup()
            }
            Log.d(TAG, "✓ WebSocketManager cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Refresh subscriptions
     * Called when user updates subscription preferences in PWA
     */
    suspend fun refreshSubscriptions() {
        Log.d(TAG, "Refreshing subscriptions...")

        try {
            // Reload config from SecureStorage
            val newConfig = subscriptionManager.loadSubscriptionConfig()
            if (newConfig == null) {
                Log.w(TAG, "No subscription config found during refresh")
                return
            }

            // Close existing subscriptions on all relays
            val connectedRelays = relayConnectionManager.getConnectedRelays()
            connectedRelays.forEach { relayUrl ->
                currentSubscriptions.forEach { sub ->
                    val closeMessage = """["CLOSE","${sub.id}"]"""
                    relayConnectionManager.sendMessage(relayUrl, closeMessage)
                }
            }

            // Generate new subscriptions
            currentConfig = newConfig
            currentSubscriptions = subscriptionManager.generateSubscriptionFilters(newConfig)

            // Send new subscriptions to all connected relays
            subscribeToRelays()

            Log.d(TAG, "✓ Subscriptions refreshed (${currentSubscriptions.size} active)")

        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to refresh subscriptions", e)
        }
    }

    /**
     * Enter low-power mode
     * Called when app enters doze or battery is critically low
     */
    fun enterLowPowerMode() {
        Log.d(TAG, "Entering low-power mode...")

        try {
            // Update app state to trigger longer ping intervals
            batteryPowerManager.updateAppState(AppState.DOZE)

            // Optionally close feed subscriptions to reduce data
            val config = currentConfig
            if (config != null && config.preferences.subscribeToFeed) {
                managerScope.launch {
                    val feedSubId = "feed_${config.userPubkey.take(8)}"
                    val connectedRelays = relayConnectionManager.getConnectedRelays()
                    connectedRelays.forEach { relayUrl ->
                        val closeMessage = """["CLOSE","$feedSubId"]"""
                        relayConnectionManager.sendMessage(relayUrl, closeMessage)
                    }
                    Log.d(TAG, "Closed feed subscriptions in low-power mode")
                }
            }

            Log.d(TAG, "✓ Entered low-power mode")

        } catch (e: Exception) {
            Log.e(TAG, "Error entering low-power mode", e)
        }
    }

    /**
     * Exit low-power mode
     * Called when app returns to foreground or battery improves
     */
    fun exitLowPowerMode() {
        Log.d(TAG, "Exiting low-power mode...")

        try {
            // Update app state to trigger shorter ping intervals
            batteryPowerManager.updateAppState(AppState.FOREGROUND)

            // Refresh subscriptions to restore feed if needed
            managerScope.launch {
                refreshSubscriptions()
            }

            Log.d(TAG, "✓ Exited low-power mode")

        } catch (e: Exception) {
            Log.e(TAG, "Error exiting low-power mode", e)
        }
    }

    /**
     * Get connection health for all relays
     */
    fun getConnectionHealth(): Map<String, ConnectionHealth> {
        return relayConnectionManager.getConnectionHealth()
    }

    /**
     * Get BatteryPowerManager instance
     * Used by NostrEventHandler for battery-aware decisions
     */
    fun getBatteryPowerManager(): BatteryPowerManager {
        return batteryPowerManager
    }

    /**
     * Send message to specific relay
     */
    fun sendToRelay(relayUrl: String, message: String): Boolean {
        return relayConnectionManager.sendMessage(relayUrl, message)
    }

    /**
     * Send message to all connected relays
     */
    fun broadcastToRelays(message: String): Int {
        val connectedRelays = relayConnectionManager.getConnectedRelays()
        var successCount = 0

        connectedRelays.forEach { relayUrl ->
            if (relayConnectionManager.sendMessage(relayUrl, message)) {
                successCount++
            }
        }

        return successCount
    }

    // ============================================================================
    // Private Helper Methods
    // ============================================================================

    /**
     * Connect to all relays in config
     */
    private suspend fun connectToRelays() {
        val config = currentConfig ?: return

        Log.d(TAG, "Connecting to ${config.relayUrls.size} relays...")

        config.relayUrls.forEach { relayUrl ->
            managerScope.launch {
                relayConnectionManager.connectToRelay(relayUrl) {
                    // Once connected, send subscriptions
                    subscribeToRelay(relayUrl)
                }
            }
        }
    }

    /**
     * Subscribe to a specific relay
     */
    private fun subscribeToRelay(relayUrl: String) {
        Log.d(TAG, "Subscribing to $relayUrl with ${currentSubscriptions.size} subscriptions")

        currentSubscriptions.forEach { subscription ->
            val reqMessage = subscription.toREQMessage()
            val success = relayConnectionManager.sendMessage(relayUrl, reqMessage)
            if (success) {
                Log.d(TAG, "✓ Sent subscription ${subscription.id} to $relayUrl")
            } else {
                Log.w(TAG, "✗ Failed to send subscription ${subscription.id} to $relayUrl")
            }
        }
    }

    /**
     * Subscribe to all connected relays
     */
    private fun subscribeToRelays() {
        val connectedRelays = relayConnectionManager.getConnectedRelays()
        connectedRelays.forEach { relayUrl ->
            subscribeToRelay(relayUrl)
        }
    }

    /**
     * Schedule periodic subscription refresh
     */
    private fun scheduleSubscriptionRefresh() {
        managerScope.launch {
            while (isConnected) {
                delay(SUBSCRIPTION_REFRESH_INTERVAL_MS)
                if (isConnected) {
                    Log.d(TAG, "Periodic subscription refresh")
                    refreshSubscriptions()
                }
            }
        }
    }

    /**
     * Handle incoming relay message
     */
    private fun handleRelayMessage(message: String, relayUrl: String) {
        try {
            // Parse Nostr message: ["EVENT", <subscription_id>, <event>]
            if (message.startsWith("[\"EVENT\"")) {
                val event = parseNostrEvent(message)
                if (event != null) {
                    val priority = determineEventPriority(event)
                    val queuedEvent = QueuedNostrEvent(
                        event = event,
                        relayUrl = relayUrl,
                        priority = priority
                    )

                    // Update last seen timestamp for deduplication
                    val subscriptionType = getSubscriptionType(event)
                    if (subscriptionType != null) {
                        subscriptionManager.updateLastSeenTimestamp(
                            subscriptionType,
                            event.created_at
                        )
                    }

                    // Forward to event handler
                    onEventReceived(queuedEvent)
                }
            }
            // Handle EOSE (End of Stored Events)
            else if (message.contains("\"EOSE\"")) {
                Log.d(TAG, "Received EOSE from $relayUrl")
            }
            // Handle NOTICE
            else if (message.contains("\"NOTICE\"")) {
                Log.d(TAG, "Notice from $relayUrl: $message")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling relay message from $relayUrl", e)
        }
    }

    /**
     * Handle connection established
     */
    private fun handleConnectionEstablished(relayUrl: String) {
        Log.d(TAG, "✓ Connected to $relayUrl")

        // Check if all relays are connected
        val connectedRelays = relayConnectionManager.getConnectedRelays()
        val totalRelays = currentConfig?.relayUrls?.size ?: 0

        if (connectedRelays.size == totalRelays) {
            Log.d(TAG, "✓ All relays connected ($totalRelays/$totalRelays)")
        } else {
            Log.d(TAG, "Relay progress: ${connectedRelays.size}/$totalRelays connected")
        }
    }

    /**
     * Handle connection failed
     */
    private fun handleConnectionFailed(relayUrl: String, error: String) {
        Log.w(TAG, "✗ Connection failed to $relayUrl: $error")
    }

    /**
     * Handle app state change
     */
    private fun handleAppStateChange(newState: AppState, duration: Long) {
        Log.d(TAG, "App state changed: $newState")

        when (newState) {
            AppState.DOZE, AppState.RARE, AppState.RESTRICTED -> {
                // Enter low-power mode for restricted states
                enterLowPowerMode()
            }
            AppState.FOREGROUND -> {
                // Exit low-power mode when returning to foreground
                exitLowPowerMode()
            }
            AppState.BACKGROUND -> {
                // Normal background operation
                Log.d(TAG, "Normal background operation")
            }
        }
    }

    /**
     * Handle ping interval change
     */
    private fun handlePingIntervalChange() {
        val newInterval = batteryPowerManager.getCurrentPingInterval()
        Log.d(TAG, "Ping interval changed to ${newInterval}s")

        // Update RelayConnectionManager with new interval
        relayConnectionManager.updatePingInterval(newInterval)
    }

    /**
     * Handle network state change
     */
    private fun handleNetworkStateChange(isAvailable: Boolean) {
        Log.d(TAG, "Network state changed: available=$isAvailable")

        if (isAvailable && isConnected) {
            // Network restored - reconnect to failed relays
            managerScope.launch {
                val health = relayConnectionManager.getConnectionHealth()
                health.filter { it.value.state == ConnectionState.FAILED }.forEach { (relayUrl, _) ->
                    Log.d(TAG, "Reconnecting to $relayUrl after network restoration")
                    relayConnectionManager.reconnect(relayUrl)
                }
            }
        }
    }

    /**
     * Create OkHttpClient with specific ping interval
     */
    private fun createOkHttpClient(pingIntervalSeconds: Long): OkHttpClient {
        return OkHttpClient.Builder()
            .pingInterval(pingIntervalSeconds, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Parse Nostr event from relay message
     * Expected format: ["EVENT", <subscription_id>, <event_object>]
     */
    private fun parseNostrEvent(message: String): NostrEvent? {
        return try {
            // Parse outer array: ["EVENT", sub_id, event]
            val array = gson.fromJson(message, Array::class.java)

            if (array.size < 3 || array[0] != "EVENT") {
                Log.w(TAG, "Invalid event message format")
                return null
            }

            // Extract event object (third element)
            val eventJson = gson.toJson(array[2])
            val event = gson.fromJson(eventJson, NostrEvent::class.java)

            Log.d(TAG, "✓ Parsed event: kind=${event.kind}, id=${event.id.take(8)}...")
            event

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Nostr event", e)
            null
        }
    }

    /**
     * Determine event priority
     */
    private fun determineEventPriority(event: NostrEvent): EventPriority {
        return when (event.kind) {
            4 -> EventPriority.HIGH      // DMs
            9735 -> EventPriority.HIGH   // Zaps
            1 -> {
                // Check if it's a mention
                val config = currentConfig
                if (config != null) {
                    val isMention = event.tags.any { tag ->
                        tag.size >= 2 && tag[0] == "p" && tag[1] == config.userPubkey
                    }
                    if (isMention) EventPriority.HIGH else EventPriority.NORMAL
                } else {
                    EventPriority.NORMAL
                }
            }
            else -> EventPriority.LOW
        }
    }

    /**
     * Get subscription type from event
     */
    private fun getSubscriptionType(event: NostrEvent): String? {
        return when (event.kind) {
            4 -> "dms"
            9735 -> "zaps"
            1 -> {
                val config = currentConfig
                if (config != null) {
                    val isMention = event.tags.any { tag ->
                        tag.size >= 2 && tag[0] == "p" && tag[1] == config.userPubkey
                    }
                    if (isMention) "mentions" else "feed"
                } else {
                    null
                }
            }
            else -> null
        }
    }
}
