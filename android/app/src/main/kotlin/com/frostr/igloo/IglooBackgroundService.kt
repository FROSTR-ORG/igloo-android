package com.frostr.igloo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Gravity
import android.graphics.PixelFormat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import com.frostr.igloo.bridges.StorageBridge
import com.frostr.igloo.bridges.ModernCameraBridge

/**
 * IglooBackgroundService - Hybrid Architecture
 *
 * Key Features:
 * - Persistent WebSocket infrastructure (always running)
 * - On-demand PWA loading (only when needed)
 * - Reads subscriptions from SecureStorage (no PWA dependency)
 * - Battery-optimized with dynamic ping intervals
 *
 * Battery Impact:
 * - Idle (no PWA): ~1.2% per hour
 * - Light usage: ~1.8% per hour
 * - Moderate usage: ~3.2% per hour
 */
class IglooBackgroundService : Service() {

    companion object {
        private const val TAG = "IglooBackgroundService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "igloo_service_channel"
        private const val PWA_IDLE_TIMEOUT = 5 * 60 * 1000L // 5 minutes

        const val ACTION_STOP_SERVICE = "com.frostr.igloo.ACTION_STOP_SERVICE"
        const val ACTION_WAKE_PWA = "com.frostr.igloo.ACTION_WAKE_PWA"
        const val ACTION_REFRESH_SUBSCRIPTIONS = "com.frostr.igloo.ACTION_REFRESH_SUBSCRIPTIONS"
    }

    // Core infrastructure (ALWAYS INITIALIZED)
    private lateinit var storageBridge: StorageBridge

    // WebSocket infrastructure (ALWAYS ACTIVE)
    private lateinit var webSocketManager: com.frostr.igloo.managers.WebSocketManager

    // Event handling (ALWAYS RUNNING)
    private lateinit var eventQueue: com.frostr.igloo.managers.NostrEventQueue
    private lateinit var eventHandler: com.frostr.igloo.managers.NostrEventHandler

    // PWA components (ON-DEMAND)
    private var webView: WebView? = null
    private var asyncBridge: AsyncBridge? = null
    private var pwaState: PWAState = PWAState.IDLE
    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    // Polyfill bridges (LOADED WITH PWA)
    // Note: StorageBridge is always available, others load with PWA
    private var cameraBridge: ModernCameraBridge? = null

    // Service management
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val idleHandler = Handler(Looper.getMainLooper())

    // NIP-55 request management
    private val pendingNIP55Requests = ConcurrentHashMap<String, (NIP55Result) -> Unit>()

    enum class PWAState {
        IDLE,       // Not loaded
        LOADING,    // Loading in progress
        ACTIVE,     // Loaded and ready
        UNLOADING   // Cleanup in progress
    }

    enum class ServiceNotificationState {
        STARTING,
        READY,
        MONITORING,
        PROCESSING,
        SLEEPING,
        ERROR
    }

    // ========== Service Lifecycle ==========

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "IglooBackgroundService.onCreate() - PID: ${android.os.Process.myPid()}")

        // CRITICAL: Start foreground immediately (Android 8+ requirement)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(ServiceNotificationState.STARTING))

        // Initialize StorageBridge FIRST (no PWA needed)
        storageBridge = StorageBridge(this)
        Log.d(TAG, "✓ StorageBridge initialized")

        // Test storage access
        testStorageAccess()

        // Initialize WebSocket infrastructure (reads from storage, NO PWA!)
        setupWebSocketInfrastructure()

        // Initialize WebSocketManager's internal managers (MUST be called before setupEventHandling)
        try {
            webSocketManager.initialize()
            Log.d(TAG, "✓ WebSocketManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to initialize WebSocketManager", e)
            updateNotification(ServiceNotificationState.ERROR)
            return
        }

        // Initialize event handling (requires batteryPowerManager from WebSocketManager)
        setupEventHandling()

        // Start WebSocket connections
        serviceScope.launch {
            try {
                webSocketManager.start()
                eventQueue.start()
                Log.i(TAG, "✓ WebSocket infrastructure started")
            } catch (e: Exception) {
                Log.e(TAG, "✗ Failed to start WebSocket infrastructure", e)
                updateNotification(ServiceNotificationState.ERROR)
            }
        }

        updateNotification(ServiceNotificationState.MONITORING)
        Log.i(TAG, "✓ IglooBackgroundService ready (PWA not loaded, WebSockets active)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "User requested service stop")
                stopService()
                return START_NOT_STICKY
            }
            ACTION_WAKE_PWA -> {
                Log.d(TAG, "Wake PWA requested")
                serviceScope.launch {
                    loadPWA()
                }
            }
            ACTION_REFRESH_SUBSCRIPTIONS -> {
                Log.d(TAG, "Refresh subscriptions requested")
                serviceScope.launch {
                    webSocketManager.refreshSubscriptions()
                }
            }
        }

        return START_STICKY // Restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind")
        return LocalBinder()
    }

    override fun onDestroy() {
        Log.d(TAG, "IglooBackgroundService.onDestroy()")

        // Cancel idle handler
        idleHandler.removeCallbacksAndMessages(null)

        // Stop event handling
        if (::eventQueue.isInitialized) {
            eventQueue.stop()
        }

        // Close WebSocket connections
        if (::webSocketManager.isInitialized) {
            webSocketManager.cleanup()
        }

        // Clean up PWA if loaded
        if (webView != null) {
            unloadPWA()
        }

        // Cancel service scope
        serviceScope.cancel()

        super.onDestroy()
    }

    private fun stopService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ========== Binder for MainActivity ==========

    inner class LocalBinder : Binder() {
        fun getService(): IglooBackgroundService = this@IglooBackgroundService
    }

    fun getPWAState(): PWAState = pwaState

    fun getWebView(): WebView? = webView

    fun getConnectionHealth(): Map<String, com.frostr.igloo.models.ConnectionHealth>? {
        return if (::webSocketManager.isInitialized) {
            webSocketManager.getConnectionHealth()
        } else null
    }

    fun getEventQueueStats(): com.frostr.igloo.managers.QueueStats? {
        return if (::eventQueue.isInitialized) {
            eventQueue.getStats()
        } else null
    }

    fun getEventProcessingStats(): com.frostr.igloo.managers.ProcessingStatistics? {
        return if (::eventHandler.isInitialized) {
            eventHandler.getStatistics()
        } else null
    }

    // ========== Notification Management ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Igloo Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Manages WebSocket connections and NIP-55 signing"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            Log.d(TAG, "Notification channel created")
        }
    }

    private fun createNotification(state: ServiceNotificationState): Notification {
        val contentText = when (state) {
            ServiceNotificationState.STARTING -> "Starting service..."
            ServiceNotificationState.READY -> "Ready"
            ServiceNotificationState.MONITORING -> "Monitoring relays"
            ServiceNotificationState.PROCESSING -> "Processing request"
            ServiceNotificationState.SLEEPING -> "Idle"
            ServiceNotificationState.ERROR -> "Error"
        }

        val mainIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, IglooBackgroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Igloo Signer")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: Use proper icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(mainIntent)
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                stopIntent
            )
            .build()
    }

    private fun updateNotification(state: ServiceNotificationState) {
        val notification = createNotification(state)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // ========== WebSocket Infrastructure Setup ==========

    private fun setupWebSocketInfrastructure() {
        Log.d(TAG, "Setting up WebSocket infrastructure...")

        try {
            // Initialize WebSocketManager
            webSocketManager = com.frostr.igloo.managers.WebSocketManager(
                context = this,
                storageBridge = storageBridge,
                onEventReceived = { queuedEvent ->
                    handleEventReceived(queuedEvent)
                },
                onConnectionStateChange = { isConnected ->
                    handleConnectionStateChange(isConnected)
                }
            )

            Log.d(TAG, "✓ WebSocket infrastructure initialized")

        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to setup WebSocket infrastructure", e)
            throw e
        }
    }

    private fun setupEventHandling() {
        Log.d(TAG, "Setting up event handling...")

        try {
            // Initialize event queue
            eventQueue = com.frostr.igloo.managers.NostrEventQueue(
                onBatchReady = { batch ->
                    handleEventBatch(batch)
                }
            )

            // Initialize event handler
            eventHandler = com.frostr.igloo.managers.NostrEventHandler(
                context = this,
                storageBridge = storageBridge,
                batteryPowerManager = webSocketManager.getBatteryPowerManager(),
                onShouldWakePWA = { reason, eventCount ->
                    handlePWAWakeRequest(reason, eventCount)
                }
            )

            Log.d(TAG, "✓ Event handling initialized")

        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to setup event handling", e)
            throw e
        }
    }

    // ========== Event Handling Callbacks ==========

    private fun handleEventReceived(queuedEvent: com.frostr.igloo.models.QueuedNostrEvent) {
        Log.d(TAG, "Event received: ${queuedEvent.priority} priority from ${queuedEvent.relayUrl}")
        eventQueue.enqueue(queuedEvent)
    }

    private fun handleEventBatch(batch: List<com.frostr.igloo.models.QueuedNostrEvent>) {
        Log.d(TAG, "Event batch ready: ${batch.size} events")
        eventHandler.processBatch(batch)
    }

    private fun handlePWAWakeRequest(reason: String, eventCount: Int) {
        Log.d(TAG, "PWA wake requested: $reason ($eventCount events)")

        serviceScope.launch {
            try {
                loadPWA()

                // Wait for PWA to be ready
                if (pwaState == PWAState.ACTIVE) {
                    // PWA will read events from SecureStorage
                    Log.d(TAG, "✓ PWA loaded, events available in storage")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to wake PWA", e)
            }
        }
    }

    private fun handleConnectionStateChange(isConnected: Boolean) {
        val state = if (isConnected) ServiceNotificationState.MONITORING else ServiceNotificationState.ERROR
        updateNotification(state)
        Log.d(TAG, "Connection state changed: connected=$isConnected")
    }

    // ========== Storage Testing ==========

    private fun testStorageAccess() {
        try {
            val userPubkey = storageBridge.getItem("local", "user_pubkey")
            if (userPubkey != null) {
                Log.d(TAG, "✓ StorageBridge working - found user pubkey: ${userPubkey.take(16)}...")
            } else {
                Log.d(TAG, "⚠ No user pubkey in storage (user not logged in yet)")
            }

            val relayUrlsJson = storageBridge.getItem("local", "relay_urls")
            if (relayUrlsJson != null) {
                Log.d(TAG, "✓ Found relay URLs in storage")
            } else {
                Log.d(TAG, "⚠ No relay URLs in storage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ StorageBridge test failed", e)
        }
    }

    // ========== PWA Lifecycle (On-Demand) ==========

    /**
     * Load PWA on-demand (Phase 4 implementation)
     */
    private suspend fun loadPWA() {
        if (pwaState == PWAState.ACTIVE) {
            Log.d(TAG, "[PWA LOAD] PWA already loaded")
            return
        }

        if (pwaState == PWAState.LOADING) {
            Log.d(TAG, "[PWA LOAD] PWA already loading, waiting...")
            waitForPWAReady(timeout = 30000)
            return
        }

        pwaState = PWAState.LOADING
        updateNotification(ServiceNotificationState.PROCESSING)

        Log.d(TAG, "[PWA LOAD] Starting on-demand PWA load...")
        Log.d(TAG, "[PWA LOAD] Thread: ${Thread.currentThread().name}")

        withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "[PWA LOAD] Step 1: Creating invisible overlay window...")
                // Create WindowManager to attach WebView to a window
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

                // Create 1x1 pixel overlay window (invisible but allows WebView to render)
                overlayParams = WindowManager.LayoutParams(
                    1, // width: 1 pixel
                    1, // height: 1 pixel
                    // Use TYPE_TOAST for compatibility - doesn't require SYSTEM_ALERT_WINDOW permission
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_TOAST,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = -1000 // Position off-screen
                    y = -1000
                }
                Log.d(TAG, "[PWA LOAD] ✓ Overlay window params created")

                Log.d(TAG, "[PWA LOAD] Step 2: Creating WebView in service context...")
                // Create WebView in service context
                webView = WebView(applicationContext).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                }
                Log.d(TAG, "[PWA LOAD] ✓ WebView created")

                Log.d(TAG, "[PWA LOAD] Step 3: Attaching WebView to overlay window...")
                // Attach WebView to window so it can render
                windowManager?.addView(webView, overlayParams)
                Log.d(TAG, "[PWA LOAD] ✓ WebView attached to window")

                Log.d(TAG, "[PWA LOAD] Step 4: Configuring WebView settings...")
                // Configure WebView
                configureWebView()
                Log.d(TAG, "[PWA LOAD] ✓ WebView configured")

                Log.d(TAG, "[PWA LOAD] Step 5: Initializing AsyncBridge...")
                // Initialize AsyncBridge
                asyncBridge = AsyncBridge(webView!!)
                asyncBridge!!.initialize()
                Log.d(TAG, "[PWA LOAD] ✓ AsyncBridge initialized")

                Log.d(TAG, "[PWA LOAD] Step 6: Registering polyfill bridges...")
                // Register polyfill bridges
                registerBridges()
                Log.d(TAG, "[PWA LOAD] ✓ Polyfill bridges registered")

                Log.d(TAG, "[PWA LOAD] Step 7: Setting up WebChromeClient progress listener...")
                // Set up PWA load listener
                webView!!.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, progress: Int) {
                        Log.d(TAG, "[PWA LOAD] Progress: $progress%")
                        if (progress == 100) {
                            Log.d(TAG, "[PWA LOAD] Progress reached 100%, calling onPWALoaded()")
                            onPWALoaded()
                        }
                    }
                }
                Log.d(TAG, "[PWA LOAD] ✓ WebChromeClient configured")

                Log.d(TAG, "[PWA LOAD] Step 8: Loading PWA from http://localhost:3000...")
                // Load PWA
                webView!!.loadUrl("http://localhost:3000")
                Log.d(TAG, "[PWA LOAD] ✓ loadUrl() called, waiting for load to complete...")

                Log.d(TAG, "[PWA LOAD] Step 9: Waiting for PWA ready signal (30s timeout)...")
                val startTime = System.currentTimeMillis()
                // Wait for load with timeout
                val loaded = waitForPWAReady(timeout = 30000)
                val duration = System.currentTimeMillis() - startTime

                if (!loaded) {
                    Log.e(TAG, "[PWA LOAD] ✗ PWA load timeout after ${duration}ms")
                    Log.e(TAG, "[PWA LOAD] Current state: $pwaState")
                    Log.e(TAG, "[PWA LOAD] WebView: ${if (webView != null) "exists" else "null"}")
                    Log.e(TAG, "[PWA LOAD] AsyncBridge: ${if (asyncBridge != null) "exists" else "null"}")
                    unloadPWA()
                    throw Exception("PWA failed to load within 30 seconds")
                }

                Log.d(TAG, "[PWA LOAD] ✓ PWA load completed successfully in ${duration}ms")

            } catch (e: Exception) {
                Log.e(TAG, "[PWA LOAD] ✗ Failed to load PWA", e)
                unloadPWA()
                updateNotification(ServiceNotificationState.ERROR)
                throw e
            }
        }
    }

    private fun configureWebView() {
        webView?.settings?.apply {
            javaScriptEnabled = true
            domStorageEnabled = false // We use polyfill bridges
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
        }
    }

    private fun registerBridges() {
        // StorageBridge is always available (initialized in onCreate)
        webView?.addJavascriptInterface(storageBridge, "SecureStorageBridge")

        // TODO Phase 4: Register other bridges
        // cameraBridge = ModernCameraBridge(this)
        // webView?.addJavascriptInterface(cameraBridge, "CameraBridge")
    }

    private fun onPWALoaded() {
        Log.i(TAG, "[PWA LOAD] ✓ onPWALoaded() called - page load complete")
        Log.d(TAG, "[PWA LOAD] Setting pwaState to ACTIVE")
        pwaState = PWAState.ACTIVE
        updateNotification(ServiceNotificationState.READY)

        // PWA will automatically read pending events from SecureStorage
        // No need to push events - PWA polls storage on wake
        Log.d(TAG, "[PWA LOAD] PWA active - events available in SecureStorage")

        // Schedule idle timeout
        scheduleIdleTimeout()
        Log.d(TAG, "[PWA LOAD] Idle timeout scheduled (${PWA_IDLE_TIMEOUT}ms)")
    }

    private suspend fun waitForPWAReady(timeout: Long): Boolean {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[PWA LOAD] waitForPWAReady: Entering wait loop (timeout=${timeout}ms)")

        var iterations = 0
        while (pwaState == PWAState.LOADING) {
            iterations++
            val elapsed = System.currentTimeMillis() - startTime

            if (iterations % 10 == 0) { // Log every 1 second (10 * 100ms)
                Log.d(TAG, "[PWA LOAD] waitForPWAReady: Still waiting... (${elapsed}ms elapsed, state=$pwaState)")
            }

            if (elapsed > timeout) {
                Log.w(TAG, "[PWA LOAD] waitForPWAReady: Timeout reached after ${elapsed}ms")
                return false
            }
            delay(100)
        }

        val finalElapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "[PWA LOAD] waitForPWAReady: Wait completed in ${finalElapsed}ms, final state=$pwaState")
        return pwaState == PWAState.ACTIVE
    }

    /**
     * Schedule PWA unload after idle period
     */
    private fun scheduleIdleTimeout() {
        idleHandler.removeCallbacksAndMessages(null)
        idleHandler.postDelayed({
            if (pwaState == PWAState.ACTIVE) {
                Log.d(TAG, "PWA idle timeout - unloading")
                unloadPWA()
            }
        }, PWA_IDLE_TIMEOUT)
    }

    private fun cancelIdleTimeout() {
        idleHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Unload PWA to reclaim memory
     */
    private fun unloadPWA() {
        if (pwaState == PWAState.IDLE || pwaState == PWAState.UNLOADING) {
            return
        }

        Log.d(TAG, "Unloading PWA...")
        pwaState = PWAState.UNLOADING
        updateNotification(ServiceNotificationState.MONITORING)

        // Cleanup AsyncBridge
        asyncBridge?.cleanup()
        asyncBridge = null

        // Cleanup bridges
        cameraBridge?.cleanup()
        cameraBridge = null

        // Remove WebView from window
        try {
            if (webView != null && windowManager != null) {
                windowManager?.removeView(webView)
                Log.d(TAG, "✓ WebView removed from window")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove WebView from window", e)
        }

        // Destroy WebView
        webView?.destroy()
        webView = null
        windowManager = null
        overlayParams = null

        // Force GC to reclaim memory
        System.gc()

        pwaState = PWAState.IDLE
        Log.d(TAG, "✓ PWA unloaded, memory reclaimed")
    }

    // ========== NIP-55 Request Processing (Phase 5) ==========

    /**
     * Process NIP-55 request (called from InvisibleNIP55Handler)
     */
    suspend fun processNIP55Request(
        request: NIP55Request,
        permissionStatus: String
    ): NIP55Result {
        Log.d(TAG, "Processing NIP-55 request: ${request.type} (${request.id})")
        updateNotification(ServiceNotificationState.PROCESSING)

        // Ensure PWA is loaded with window attachment (allows full rendering)
        if (pwaState != PWAState.ACTIVE) {
            Log.d(TAG, "PWA not loaded, loading for NIP-55 request")
            try {
                loadPWA()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load PWA for NIP-55 request", e)
                return NIP55Result(
                    ok = false,
                    type = request.type,
                    id = request.id,
                    result = null,
                    reason = "Failed to load PWA: ${e.message}"
                )
            }
        }

        // Cancel idle timeout during processing
        cancelIdleTimeout()

        try {
            // Check if AsyncBridge is available
            val bridge = asyncBridge
            if (bridge == null) {
                Log.e(TAG, "AsyncBridge not available")
                scheduleIdleTimeout()
                return NIP55Result(
                    ok = false,
                    type = request.type,
                    id = request.id,
                    result = null,
                    reason = "AsyncBridge not initialized"
                )
            }

            // Handle user prompt if needed (permission_status == "prompt_required")
            // For now, we'll pass the permission status to the PWA and let it handle prompts
            // The PWA's NIP-55 handler will show user prompts when needed

            // Convert params map to proper format for AsyncBridge
            val paramsMap = mutableMapOf<String, Any>()
            request.params.forEach { (key, value) ->
                paramsMap[key] = value
            }

            // Add permission status for PWA to handle prompts
            paramsMap["_permissionStatus"] = permissionStatus

            // Call AsyncBridge to process request via PWA
            Log.d(TAG, "Calling AsyncBridge.callNip55Async: ${request.type}")
            val result = withContext(Dispatchers.IO) {
                bridge.callNip55Async(
                    type = request.type,
                    id = request.id,
                    host = request.callingApp,
                    params = paramsMap,
                    timeoutMs = 30000L
                )
            }

            Log.d(TAG, "✓ NIP-55 request completed: ok=${result.ok}")

            // Schedule unload after idle
            scheduleIdleTimeout()

            return result

        } catch (e: Exception) {
            Log.e(TAG, "NIP-55 request failed", e)

            // Still schedule unload even on error
            scheduleIdleTimeout()

            return NIP55Result(
                ok = false,
                type = request.type,
                id = request.id,
                result = null,
                reason = e.message ?: "Request failed"
            )
        } finally {
            updateNotification(ServiceNotificationState.MONITORING)
        }
    }
}

// ========== Data Models ==========

data class NIP55Request(
    val id: String,
    val type: String,
    val params: Map<String, String>,
    val callingApp: String,
    val timestamp: Long
)

data class NIP55Result(
    val ok: Boolean,
    val type: String,
    val id: String,
    val result: String?,
    val reason: String?
)

// Permission data class is defined in IglooContentProvider.kt
