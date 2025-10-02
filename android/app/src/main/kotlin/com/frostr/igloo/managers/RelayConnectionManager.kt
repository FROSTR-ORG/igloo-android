package com.frostr.igloo.managers

import android.content.Context
import android.util.Log
import com.frostr.igloo.models.AppState
import com.frostr.igloo.models.ConnectionHealth
import com.frostr.igloo.models.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * RelayConnectionManager - Manages WebSocket connections to multiple relays
 *
 * Features:
 * - One WebSocket per relay URL
 * - Automatic reconnection with exponential backoff
 * - Health monitoring
 * - Dynamic ping intervals
 * - Battery-aware connection management
 *
 * Architecture: Uses OkHttp WebSocket client for robust connection handling
 */
class RelayConnectionManager(
    private val context: Context,
    private var okHttpClient: OkHttpClient,
    private val batteryPowerManager: BatteryPowerManager,
    private val networkManager: NetworkManager,
    private val onMessageReceived: (String, String) -> Unit,
    private val onConnectionEstablished: (String) -> Unit,
    private val onConnectionFailed: (String, String) -> Unit
) {

    companion object {
        private const val TAG = "RelayConnectionManager"
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val BASE_RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
    }

    // Per-relay connection tracking
    private val relayConnections = ConcurrentHashMap<String, RelayConnection>()
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Connect to a relay
     */
    suspend fun connectToRelay(
        relayUrl: String,
        onConnected: (() -> Unit)? = null
    ) {
        // Check if already connected
        val existing = relayConnections[relayUrl]
        if (existing != null && existing.state == ConnectionState.CONNECTED) {
            Log.d(TAG, "Already connected to $relayUrl")
            onConnected?.invoke()
            return
        }

        Log.d(TAG, "Connecting to relay: $relayUrl")

        // Create new relay connection
        val connection = RelayConnection(
            relayUrl = relayUrl,
            okHttpClient = okHttpClient,
            batteryPowerManager = batteryPowerManager,
            networkManager = networkManager,
            onMessageReceived = { message ->
                onMessageReceived(message, relayUrl)
            },
            onConnected = {
                onConnectionEstablished(relayUrl)
                onConnected?.invoke()
            },
            onFailed = { error ->
                onConnectionFailed(relayUrl, error)
            }
        )

        relayConnections[relayUrl] = connection
        connection.connect()
    }

    /**
     * Send message to a specific relay
     */
    fun sendMessage(relayUrl: String, message: String): Boolean {
        val connection = relayConnections[relayUrl]
        if (connection == null) {
            Log.w(TAG, "No connection to $relayUrl")
            return false
        }

        return connection.sendMessage(message)
    }

    /**
     * Get connected relays
     */
    fun getConnectedRelays(): List<String> {
        return relayConnections
            .filter { it.value.state == ConnectionState.CONNECTED }
            .map { it.key }
    }

    /**
     * Get connection health for all relays
     */
    fun getConnectionHealth(): Map<String, ConnectionHealth> {
        return relayConnections.mapValues { (_, connection) ->
            ConnectionHealth(
                state = connection.state,
                subscriptionConfirmed = connection.hasConfirmedSubscription,
                lastMessageAge = System.currentTimeMillis() - connection.lastMessageTime,
                reconnectAttempts = connection.reconnectAttempts
            )
        }
    }

    /**
     * Reconnect to a specific relay
     */
    fun reconnect(relayUrl: String) {
        connectionScope.launch {
            val connection = relayConnections[relayUrl]
            if (connection != null) {
                Log.d(TAG, "Reconnecting to $relayUrl")
                connection.reconnect()
            } else {
                Log.w(TAG, "No connection to reconnect: $relayUrl")
            }
        }
    }

    /**
     * Update ping interval for all connections
     * Creates new OkHttpClient if interval changed significantly
     */
    fun updatePingInterval(newInterval: Long) {
        val currentInterval = getCurrentPingInterval()

        // Only recreate client if change is significant (≥30s difference)
        if (kotlin.math.abs(newInterval - currentInterval) >= 30L) {
            Log.d(TAG, "Updating ping interval: ${currentInterval}s → ${newInterval}s (recreating client)")

            val newClient = createOkHttpClient(newInterval)
            val oldClient = okHttpClient
            okHttpClient = newClient

            // Update all connections with new client
            relayConnections.values.forEach { connection ->
                connection.updateOkHttpClient(newClient)
            }

            // Old client will be GC'd when all connections are updated
        } else {
            Log.d(TAG, "Ping interval change not significant (${currentInterval}s → ${newInterval}s), skipping client recreation")
        }
    }

    /**
     * Get current ping interval from OkHttpClient
     */
    private fun getCurrentPingInterval(): Long {
        return okHttpClient.pingIntervalMillis / 1000L
    }

    /**
     * Create optimized OkHttpClient with specific ping interval
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
     * Disconnect all relays
     */
    fun disconnectAll() {
        Log.d(TAG, "Disconnecting all relays")
        relayConnections.values.forEach { it.disconnect() }
        relayConnections.clear()
    }

    /**
     * Individual relay connection
     */
    private inner class RelayConnection(
        val relayUrl: String,
        private var okHttpClient: OkHttpClient,
        private val batteryPowerManager: BatteryPowerManager,
        private val networkManager: NetworkManager,
        private val onMessageReceived: (String) -> Unit,
        private val onConnected: () -> Unit,
        private val onFailed: (String) -> Unit
    ) {
        var state: ConnectionState = ConnectionState.DISCONNECTED
        var webSocket: WebSocket? = null
        var reconnectAttempts: Int = 0
        var lastMessageTime: Long = 0L
        var hasConfirmedSubscription: Boolean = false

        private val shortUrl = relayUrl.replace("wss://", "").replace("ws://", "").take(20)

        fun connect() {
            if (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED) {
                Log.d(TAG, "[$shortUrl] Already connecting/connected")
                return
            }

            state = ConnectionState.CONNECTING
            Log.d(TAG, "[$shortUrl] Connecting... (attempt ${reconnectAttempts + 1})")

            // Acquire wake lock for connection establishment
            val wakeLockAcquired = batteryPowerManager.acquireSmartWakeLock(
                operation = "connect_$shortUrl",
                estimatedDuration = 15000L,
                importance = BatteryPowerManager.WakeLockImportance.HIGH
            )

            try {
                val request = Request.Builder()
                    .url(relayUrl)
                    .build()

                webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(TAG, "[$shortUrl] ✓ Connected")
                        state = ConnectionState.CONNECTED
                        reconnectAttempts = 0
                        lastMessageTime = System.currentTimeMillis()

                        // Release wake lock on success
                        if (wakeLockAcquired) {
                            batteryPowerManager.releaseWakeLock()
                        }

                        onConnected()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        lastMessageTime = System.currentTimeMillis()

                        // Check for subscription confirmation (EOSE)
                        if (text.contains("\"EOSE\"")) {
                            hasConfirmedSubscription = true
                        }

                        onMessageReceived(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "[$shortUrl] Closing: $code - $reason")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "[$shortUrl] Closed: $code - $reason")
                        state = ConnectionState.DISCONNECTED

                        // Schedule reconnection if should retry
                        if (shouldAttemptReconnect()) {
                            scheduleReconnection()
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "[$shortUrl] ✗ Connection failed", t)
                        state = ConnectionState.FAILED
                        reconnectAttempts++

                        // Release wake lock on failure
                        if (wakeLockAcquired) {
                            batteryPowerManager.releaseWakeLock()
                        }

                        onFailed(t.message ?: "Unknown error")

                        // Schedule reconnection with backoff
                        if (shouldAttemptReconnect()) {
                            scheduleReconnection()
                        } else {
                            Log.w(TAG, "[$shortUrl] Max reconnect attempts reached or conditions not met")
                        }
                    }
                })

            } catch (e: Exception) {
                Log.e(TAG, "[$shortUrl] Failed to initiate connection", e)
                state = ConnectionState.FAILED

                if (wakeLockAcquired) {
                    batteryPowerManager.releaseWakeLock()
                }

                onFailed(e.message ?: "Connection initiation failed")
            }
        }

        fun reconnect() {
            Log.d(TAG, "[$shortUrl] Reconnecting...")
            disconnect()
            connect()
        }

        fun disconnect() {
            webSocket?.close(1000, "Disconnecting")
            webSocket = null
            state = ConnectionState.DISCONNECTED
        }

        fun sendMessage(message: String): Boolean {
            val ws = webSocket
            if (ws == null || state != ConnectionState.CONNECTED) {
                Log.w(TAG, "[$shortUrl] Cannot send message - not connected")
                return false
            }

            return try {
                ws.send(message)
                true
            } catch (e: Exception) {
                Log.e(TAG, "[$shortUrl] Failed to send message", e)
                false
            }
        }

        fun updateOkHttpClient(newClient: OkHttpClient) {
            okHttpClient = newClient
            // Existing connections will continue with old client
            // New connections will use new client
        }

        private fun shouldAttemptReconnect(): Boolean {
            // Max attempts exceeded
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.w(TAG, "[$shortUrl] Max reconnect attempts exceeded")
                return false
            }

            // Network unavailable
            if (!networkManager.isNetworkAvailable()) {
                Log.w(TAG, "[$shortUrl] Network unavailable, skipping reconnect")
                return false
            }

            // Conservative on low battery
            val batteryLevel = batteryPowerManager.getCurrentBatteryLevel()
            val appState = batteryPowerManager.getCurrentAppState()

            return when {
                batteryLevel <= 15 && reconnectAttempts >= 2 -> {
                    Log.w(TAG, "[$shortUrl] Low battery, limiting reconnects")
                    false
                }
                appState == AppState.RESTRICTED && reconnectAttempts >= 2 -> {
                    Log.w(TAG, "[$shortUrl] Restricted state, limiting reconnects")
                    false
                }
                appState == AppState.DOZE && reconnectAttempts >= 3 -> {
                    Log.w(TAG, "[$shortUrl] Doze mode, limiting reconnects")
                    false
                }
                else -> true
            }
        }

        private fun scheduleReconnection() {
            val delay = calculateReconnectDelay()
            Log.d(TAG, "[$shortUrl] Scheduling reconnection in ${delay}ms")

            connectionScope.launch {
                delay(delay)
                if (state != ConnectionState.CONNECTED) {
                    connect()
                }
            }
        }

        private fun calculateReconnectDelay(): Long {
            val baseDelay = minOf(
                BASE_RECONNECT_DELAY_MS * reconnectAttempts,
                MAX_RECONNECT_DELAY_MS
            )

            // Adjust based on network and app state
            return networkManager.calculateOptimalReconnectDelay(
                baseDelay,
                batteryPowerManager.getCurrentAppState()
            )
        }
    }
}
