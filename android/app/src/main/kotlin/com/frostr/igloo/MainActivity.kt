package com.frostr.igloo

import androidx.appcompat.app.AppCompatActivity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import com.frostr.igloo.bridges.BridgeFactory
import com.frostr.igloo.bridges.BridgeSet
import com.frostr.igloo.bridges.NodeStateBridge
import com.frostr.igloo.health.IglooHealthManager
import com.frostr.igloo.debug.NIP55DebugLogger
import com.frostr.igloo.util.AuditLogger
import com.frostr.igloo.di.AppContainer
import com.frostr.igloo.ui.UIOverlayManager
import com.frostr.igloo.webview.WebViewManager
import com.google.gson.Gson
import kotlinx.coroutines.*

/**
 * Main Activity with Polyfill Integration
 *
 * This activity serves the PWA from assets with custom protocol while maintaining full functionality.
 * Provides secure architecture with transparent polyfills for WebSocket, Storage, Camera, and Signing operations.
 *
 * Uses Phase 4 infrastructure (WebViewManager, BridgeFactory, AppContainer) for WebView setup.
 */
class MainActivity : AppCompatActivity(), WebViewManager.WebViewReadyListener {

    companion object {
        private const val TAG = "SecureIglooWrapper"

        // Reference to active MainActivity instance
        @Volatile
        private var activeInstance: MainActivity? = null

        /**
         * Static WebView holder - survives activity destruction when foreground service is running.
         * This allows NIP-55 signing to continue using the same WebView/bifrost connection
         * even when the Activity UI is destroyed.
         */
        @Volatile
        private var persistentWebView: WebView? = null

        /**
         * Static WebViewManager holder - keeps bridges (WebSocket, Storage, etc.) alive
         * when the Activity is destroyed but service is running.
         */
        @Volatile
        private var persistentWebViewManager: WebViewManager? = null

        /**
         * Flag indicating the persistent WebView is ready for signing
         */
        @Volatile
        private var isPersistentWebViewReady: Boolean = false

        /**
         * Flag indicating user is actively in Igloo (manually switched here)
         * When true, NIP-55 handlers should not automatically return focus to calling apps
         */
        @Volatile
        var userActiveInIgloo: Boolean = false
            private set

        /**
         * Set by MainActivity when user manually brings it to foreground
         */
        fun setUserActive(active: Boolean) {
            userActiveInIgloo = active
            Log.d(TAG, "User active in Igloo: $active")
        }

        /**
         * Get WebView instance for NIP-55 signing.
         * Returns the persistent WebView if available (when service is running),
         * otherwise returns the active instance's WebView.
         */
        @JvmStatic
        fun getWebViewInstance(): WebView? {
            // First try persistent WebView (survives activity destruction)
            persistentWebView?.let { return it }
            // Fall back to active instance
            val instance = activeInstance ?: return null
            return instance.webViewManager?.getWebView()
        }

        /**
         * Check if signing is ready (WebView + PWA + bifrost all initialized)
         */
        @JvmStatic
        fun isSigningReady(): Boolean {
            // Check persistent WebView first
            if (isPersistentWebViewReady && persistentWebView != null) {
                return true
            }
            // Fall back to active instance
            val instance = activeInstance ?: return false
            return instance.isSigningReady
        }

        /**
         * Clear the persistent WebView and manager (called when node is locked or service stops)
         */
        @JvmStatic
        fun clearPersistentWebView() {
            Log.d(TAG, "Clearing persistent WebView and manager")
            persistentWebViewManager?.cleanup()
            persistentWebViewManager = null
            persistentWebView = null
            isPersistentWebViewReady = false
        }

        /**
         * Set the persistent WebView (called when node goes online)
         */
        @JvmStatic
        fun setPersistentWebView(webView: WebView) {
            Log.d(TAG, "Setting persistent WebView for background signing")
            persistentWebView = webView
            isPersistentWebViewReady = true
        }

        /**
         * Set the persistent WebViewManager (called from onDestroy when service is running)
         */
        @JvmStatic
        fun setPersistentWebViewManager(manager: WebViewManager) {
            Log.d(TAG, "Setting persistent WebViewManager for background signing")
            persistentWebViewManager = manager
        }

        /**
         * Called when node is online and ready for background operation.
         * If this was a cold start, moves the activity to background.
         */
        @JvmStatic
        fun onNodeReadyForBackground() {
            val activity = activeInstance
            if (activity == null) {
                Log.w(TAG, "onNodeReadyForBackground: No active instance")
                return
            }

            if (activity.isColdStart) {
                Log.d(TAG, "Cold start complete - moving to background")
                activity.isColdStart = false  // Reset flag
                // Move to background after a short delay to ensure everything is stable
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (activeInstance != null) {
                        Log.d(TAG, "Moving activity to background")
                        activity.moveTaskToBack(true)
                    }
                }, 500)  // 500ms delay for stability
            }
        }
    }

    // Phase 4 infrastructure
    private var webViewManager: WebViewManager? = null
    private lateinit var container: AppContainer

    // Convenience accessors for bridges
    private val bridges: BridgeSet?
        get() = webViewManager?.getBridges()

    private var isSigningReady = false
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val gson = Gson()
    private lateinit var overlayManager: UIOverlayManager
    private var isNormalLaunch = false  // Track if this is a normal user launch (not NIP-55)
    private var isColdStart = false  // Track if this is a cold start from NIP-55 request

    // Pending unlock request - processed after user unlocks the node
    private var pendingUnlockRequest: PendingUnlockRequest? = null

    private data class PendingUnlockRequest(
        val requestId: String,
        val requestType: String,
        val params: Map<String, String>,
        val callingApp: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // IMMEDIATELY move to background if this is a service request - before any UI setup
        val isBackgroundServiceRequest = intent.getBooleanExtra("background_service_request", false)
        if (isBackgroundServiceRequest) {
            Log.d(TAG, "Background service request detected - moving to background immediately")
            moveTaskToBack(true)
        }

        super.onCreate(savedInstanceState)
        Log.d(TAG, "=== SECURE ACTIVITY LIFECYCLE: onCreate ===")
        Log.d(TAG, "Process ID: ${android.os.Process.myPid()}")

        // Clear WebView cache on startup to prevent stale JavaScript during development
        try {
            val cacheDir = cacheDir
            val webViewCacheDir = java.io.File(cacheDir, "webviewCache")
            if (webViewCacheDir.exists()) {
                webViewCacheDir.deleteRecursively()
                Log.d(TAG, "Cleared WebView cache directory")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear WebView cache: ${e.message}")
        }

        // Register this instance as active
        activeInstance = this

        // Initialize IglooHealthManager with context for wakeup Intents
        IglooHealthManager.init(this)

        // Clear any stale active handler from a previous app session
        // The activeHandler reference becomes stale when app is killed/restarted
        IglooHealthManager.clearStaleActiveHandler()

        if (isBackgroundServiceRequest) {
            Log.d(TAG, "Background service request - continuing setup in background")
        }

        // NOTE: IglooBackgroundService was removed (no background signing)
        // startIglooBackgroundService()

        when (intent.action) {
            "$packageName.UNLOCK_AND_SIGN" -> {
                // Unlock request from InvisibleNIP55Handler when node is locked
                Log.d(TAG, "Unlock and sign request - initializing WebView for unlock")
                NIP55DebugLogger.logIntent("UNLOCK_AND_SIGN", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString()
                ))
                // Store the pending request - it will be processed after unlock
                storePendingUnlockRequest(intent)
                initializeSecureWebView()
                loadSecurePWA()
            }
            "$packageName.SHOW_PERMISSION_DIALOG" -> {
                // Permission dialog request from InvisibleNIP55Handler
                Log.d(TAG, "Permission dialog request - showing native dialog")
                // Dismiss keyboard immediately - it may be open from the calling app
                dismissKeyboard()
                NIP55DebugLogger.logIntent("PERMISSION_DIALOG", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString()
                ))
                handlePermissionDialogRequest()
            }
            "$packageName.NIP55_SIGNING" -> {
                NIP55DebugLogger.logIntent("MAIN_RECEIVED", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString(),
                    "backgroundServiceRequest" to isBackgroundServiceRequest
                ))

                // Direct Intent request - traditional flow
                val showPrompt = intent.getBooleanExtra("nip55_show_prompt", false)
                if (!showPrompt) {
                    Log.d(TAG, "Fast signing detected - showing signing overlay")
                    if (::overlayManager.isInitialized) {
                        overlayManager.setupSigningOverlay()
                        overlayManager.showSigningOverlay()
                    }
                }

                Log.d(TAG, "Direct Intent signing - initializing WebView and handling request")
                initializeSecureWebView()
                loadSecurePWA()
                handleNIP55Request()
            }
            else -> {
                // Check if this is a background signing request (from InvisibleNIP55Handler)
                val isBackgroundSigningRequest = intent.getBooleanExtra("background_signing_request", false)
                if (isBackgroundSigningRequest) {
                    val signingType = intent.getStringExtra("signing_request_type") ?: "unknown"
                    val callingApp = intent.getStringExtra("signing_calling_app") ?: "unknown"
                    isColdStart = intent.getBooleanExtra("cold_start", false)
                    Log.d(TAG, "Background signing startup - type=$signingType, app=$callingApp, coldStart=$isColdStart")
                    NIP55DebugLogger.logPWABridge("BACKGROUND_SIGNING_STARTUP", "initializePWA")
                } else {
                    NIP55DebugLogger.logPWABridge("NORMAL_STARTUP", "initializePWA")
                    isNormalLaunch = true  // Mark as normal user launch for welcome dialog
                    // Battery optimization check disabled - testing foreground service solution
                    // checkBatteryOptimization()
                }
                initializeSecureWebView()

                // Show signing overlay for background signing requests (cold start)
                if (isBackgroundSigningRequest && ::overlayManager.isInitialized) {
                    Log.d(TAG, "Cold start background signing - showing signing overlay")
                    overlayManager.setupSigningOverlay()
                    overlayManager.showSigningOverlay()
                }

                loadSecurePWA()
                handleIntent(intent)
            }
        }
    }

    // NOTE: IglooBackgroundService was removed (no background signing needed)
    // Background signing isn't possible anyway, so this service was deleted

    /**
     * Handle NIP-55 signing request from InvisibleNIP55Handler
     */
    private fun handleNIP55Request() {
        Log.d(TAG, "=== HANDLING NIP-55 REQUEST (AsyncBridge) ===")

        try {
            val replyPendingIntent = getParcelableExtraCompat(intent, "reply_pending_intent", PendingIntent::class.java)
            Log.d(TAG, "Processing traditional NIP-55 request")
            handleTraditionalNIP55Request(replyPendingIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in NIP-55 request handling", e)
            NIP55DebugLogger.logError("CRITICAL_HANDLE", e)

            val replyPendingIntent = getParcelableExtraCompat(intent, "reply_pending_intent", PendingIntent::class.java)
            sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                putExtra("error", "Critical error: ${e.message}")
                putExtra("result_code", RESULT_CANCELED)
            })
        }
    }

    /**
     * Type-safe getParcelableExtra that works across API levels.
     */
    private fun <T : android.os.Parcelable> getParcelableExtraCompat(
        intent: Intent,
        key: String,
        clazz: Class<T>
    ): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, clazz)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }
    }

    /**
     * Handle traditional NIP-55 request (from InvisibleNIP55Handler)
     */
    private fun handleTraditionalNIP55Request(replyPendingIntent: PendingIntent?) {
        // Check for new format (individual extras from invisible handler)
        val requestId = intent.getStringExtra("nip55_request_id")
        val requestType = intent.getStringExtra("nip55_request_type")
        val showPrompt = intent.getBooleanExtra("nip55_show_prompt", false)

        val request = if (requestId != null && requestType != null) {
            // New format from invisible handler
            val callingApp = intent.getStringExtra("nip55_request_calling_app") ?: "unknown"
            val paramsJson = intent.getStringExtra("nip55_request_params") ?: "{}"

            val params = try {
                gson.fromJson(paramsJson, Map::class.java) as? Map<String, String> ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }

            Log.d(TAG, "Processing NIP-55 prompt request from: $callingApp (show_prompt=$showPrompt)")

            NIP55Request(
                id = requestId,
                type = requestType,
                params = params,
                callingApp = callingApp,
                timestamp = System.currentTimeMillis()
            )
        } else {
            // Legacy format (JSON string)
            val requestJson = intent.getStringExtra("nip55_request")
            val callingApp = intent.getStringExtra("calling_app") ?: "unknown"

            if (requestJson == null) {
                Log.e(TAG, "No NIP-55 request data found in intent")
                NIP55DebugLogger.logError("MISSING_REQUEST", Exception("No request data in intent"))
                sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                    putExtra("error", "No request data")
                })
                return
            }

            Log.d(TAG, "Processing traditional NIP-55 request from: $callingApp")

            try {
                gson.fromJson(requestJson, NIP55Request::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse NIP-55 request JSON", e)
                NIP55DebugLogger.logError("PARSE_REQUEST", e)
                sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                    putExtra("error", "Invalid request format: ${e.message}")
                })
                return
            }
        }

        processNIP55Request(request, request.callingApp, replyPendingIntent)
    }

    /**
     * Handle permission dialog request from InvisibleNIP55Handler
     */
    private fun handlePermissionDialogRequest() {
        val appId = intent.getStringExtra("app_id") ?: "unknown"
        val requestId = intent.getStringExtra("request_id") ?: return
        val isBulk = intent.getBooleanExtra("is_bulk", false)

        Log.d(TAG, "Handling permission dialog request: appId=$appId, bulk=$isBulk, requestId=$requestId")

        // Dismiss any open keyboard to ensure the permission dialog is fully visible
        dismissKeyboard()

        // Create appropriate dialog variant
        val dialog = if (isBulk) {
            val permissionsJson = intent.getStringExtra("permissions_json") ?: "[]"
            NIP55PermissionDialog.newInstanceBulk(appId, permissionsJson)
        } else {
            val requestType = intent.getStringExtra("request_type") ?: return
            val eventKind = if (intent.hasExtra("event_kind")) {
                intent.getIntExtra("event_kind", -1)
            } else null

            NIP55PermissionDialog.newInstance(appId, requestType, eventKind)
        }

        // Set callback for dialog result
        dialog.setCallback(object : NIP55PermissionDialog.PermissionCallback {
            override fun onApproved() {
                Log.d(TAG, "Permission approved - executing signing operation")
                handleApprovedPermission(requestId)
            }

            override fun onDenied() {
                Log.d(TAG, "Permission denied - returning error")
                handleDeniedPermission(requestId)
            }

            override fun onCancelled() {
                Log.d(TAG, "Permission dialog cancelled - returning error immediately")
                handleCancelledPermission(requestId)
            }
        })

        // Show dialog
        dialog.show(supportFragmentManager, "NIP55PermissionDialog")
    }

    /**
     * Handle approved permission - execute signing operation immediately
     */
    private fun handleApprovedPermission(requestId: String) {
        Log.d(TAG, "Permission approved - executing signing operation immediately")

        // Permission was saved by the dialog
        // Now we need to initialize WebView and execute the actual signing operation

        // Initialize WebView if not already done (permission dialog doesn't initialize it)
        if (webViewManager == null) {
            Log.d(TAG, "Initializing WebView for signing after permission approval")
            initializeSecureWebView()
            loadSecurePWA()
        }

        // Show signing overlay for the operation
        if (::overlayManager.isInitialized) {
            overlayManager.setupSigningOverlay()
            overlayManager.showSigningOverlay()
        }

        // Execute the signing operation using existing flow
        // The Intent already contains all necessary request data from InvisibleNIP55Handler
        handleNIP55Request()
    }

    /**
     * Handle denied permission - return error
     */
    private fun handleDeniedPermission(requestId: String) {
        val result = NIP55Result(
            ok = false,
            type = "permission_denied",
            id = requestId,
            result = null,
            reason = "User denied permission"
        )

        val delivered = IglooHealthManager.deliverResultByRequestId(requestId, result)
        if (delivered) {
            Log.d(TAG, "✓ Permission denial delivered via health manager")
        } else {
            Log.w(TAG, "✗ No callback registered for request: $requestId")
        }

        // Return focus to the calling app (Amethyst) instead of just going to background
        returnFocusToCallingApp()
    }

    /**
     * Handle cancelled permission dialog - return error immediately
     * This is called when user dismisses the dialog without making a choice
     * (back button, touch outside, keyboard close, etc.)
     */
    private fun handleCancelledPermission(requestId: String) {
        val result = NIP55Result(
            ok = false,
            type = "permission_cancelled",
            id = requestId,
            result = null,
            reason = "User cancelled permission dialog"
        )

        val delivered = IglooHealthManager.deliverResultByRequestId(requestId, result)
        if (delivered) {
            Log.d(TAG, "✓ Permission cancellation delivered via health manager")
        } else {
            Log.w(TAG, "✗ No callback registered for request: $requestId")
        }

        // Return focus to the calling app (Amethyst) instead of just going to background
        returnFocusToCallingApp()
    }

    // ========== Unlock Request Handling ==========

    /**
     * Store a pending unlock request from InvisibleNIP55Handler.
     * The request will be processed after the user unlocks the node.
     */
    private fun storePendingUnlockRequest(intent: Intent) {
        val requestId = intent.getStringExtra("nip55_request_id")
        val requestType = intent.getStringExtra("nip55_request_type")
        val paramsJson = intent.getStringExtra("nip55_request_params") ?: "{}"
        val callingApp = intent.getStringExtra("nip55_request_calling_app") ?: "unknown"

        if (requestId == null || requestType == null) {
            Log.e(TAG, "Invalid unlock request - missing required fields")
            return
        }

        val params = try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(paramsJson, Map::class.java) as? Map<String, String> ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse request params", e)
            emptyMap()
        }

        pendingUnlockRequest = PendingUnlockRequest(
            requestId = requestId,
            requestType = requestType,
            params = params,
            callingApp = callingApp
        )

        Log.d(TAG, "Stored pending unlock request: type=$requestType, id=$requestId, caller=$callingApp")

        // Start polling for unlock (check every second if bridge is ready)
        startUnlockPolling()
    }

    /**
     * Start polling to check if the bridge becomes ready (indicating node is unlocked)
     */
    private fun startUnlockPolling() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val pollInterval = 1000L // 1 second
        val maxPolls = 60 // 60 seconds max

        var pollCount = 0

        val pollRunnable = object : Runnable {
            override fun run() {
                pollCount++

                if (pendingUnlockRequest == null) {
                    Log.d(TAG, "Pending unlock request cancelled")
                    return
                }

                if (pollCount > maxPolls) {
                    Log.d(TAG, "Unlock polling timed out")
                    val pending = pendingUnlockRequest
                    if (pending != null) {
                        IglooHealthManager.deliverResultByRequestId(pending.requestId, NIP55Result(
                            ok = false,
                            type = pending.requestType,
                            id = pending.requestId,
                            reason = "Unlock timeout - please try again"
                        ))
                        pendingUnlockRequest = null
                    }
                    return
                }

                // Check if WebView is ready and try to execute
                val webView = webViewManager?.getWebView()
                if (webView != null && isSigningReady) {
                    Log.d(TAG, "Bridge appears ready - attempting to process pending unlock request")
                    processPendingUnlockRequest()
                    // Result is async - if still locked, pendingUnlockRequest will remain set
                    // Keep polling in case still locked
                    if (pendingUnlockRequest != null) {
                        handler.postDelayed(this, pollInterval)
                    }
                } else {
                    // Keep polling
                    handler.postDelayed(this, pollInterval)
                }
            }
        }

        handler.postDelayed(pollRunnable, pollInterval)
    }

    /**
     * Process the pending unlock request now that the node should be unlocked
     * Returns true if processing completed (success or permanent failure),
     * false if still locked (should keep polling)
     */
    private fun processPendingUnlockRequest(): Boolean {
        val pending = pendingUnlockRequest ?: return true

        Log.d(TAG, "Attempting to process pending unlock request: ${pending.requestType}")

        // Create NIP55Request from pending data
        val request = NIP55Request(
            id = pending.requestId,
            type = pending.requestType,
            params = pending.params,
            callingApp = pending.callingApp,
            timestamp = System.currentTimeMillis()
        )

        // Use IglooHealthManager to execute the request
        IglooHealthManager.submit(request) { result ->
            Log.d(TAG, "Pending unlock request result: ok=${result.ok}, reason=${result.reason}")

            // If still locked, don't deliver result yet - keep polling
            if (!result.ok && result.reason?.contains("locked", ignoreCase = true) == true) {
                Log.d(TAG, "Node still locked - will keep polling")
                // Don't clear pendingUnlockRequest - keep polling
                return@submit
            }

            // Clear pending request - we're done
            pendingUnlockRequest = null

            // Deliver the result via health manager (to original callback)
            IglooHealthManager.deliverResultByRequestId(pending.requestId, result)

            // Return focus to calling app if successful
            if (result.ok) {
                returnFocusToCallingApp()
            }
        }

        return false // Result is async, return false to potentially keep polling
    }

    /**
     * Dismiss any open soft keyboard
     * Called when activity comes to foreground and before showing permission dialogs
     * Uses multiple strategies to ensure keyboard is hidden
     */
    private fun dismissKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            // Strategy 1: Hide from current focus
            currentFocus?.let { view ->
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                Log.d(TAG, "Keyboard dismissed from current focus")
            }

            // Strategy 2: Hide from decor view (works even if no focus)
            window.decorView.let { view ->
                imm.hideSoftInputFromWindow(view.windowToken, 0)
            }

            // Strategy 3: Delayed dismissal in case window isn't ready yet
            window.decorView.postDelayed({
                try {
                    currentFocus?.let { view ->
                        imm.hideSoftInputFromWindow(view.windowToken, 0)
                    }
                    window.decorView.let { view ->
                        imm.hideSoftInputFromWindow(view.windowToken, 0)
                    }
                } catch (e: Exception) {
                    // Ignore delayed dismissal errors
                }
            }, 100)

            Log.d(TAG, "Keyboard dismissal requested")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dismiss keyboard: ${e.message}")
        }
    }

    /**
     * Check if battery optimization is disabled for this app.
     * If not, prompt the user to disable it to allow background signing.
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(TAG, "Battery optimization is enabled - requesting exemption")

                // Show dialog explaining why we need this
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }

                try {
                    startActivity(intent)
                    Log.d(TAG, "Battery optimization exemption dialog shown")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to show battery optimization dialog: ${e.message}")
                    // Fall back to opening battery settings manually
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to open battery settings: ${e2.message}")
                    }
                }
            } else {
                Log.d(TAG, "Battery optimization already disabled for this app")
            }
        }
    }

    /**
     * Return focus to the calling app after handling a permission request
     * This ensures we go back to Amethyst instead of the home screen
     */
    private fun returnFocusToCallingApp() {
        try {
            // Get the calling app from the intent
            val callingApp = intent.getStringExtra("app_id")
                ?: intent.getStringExtra("nip55_request_calling_app")
                ?: intent.getStringExtra("signing_calling_app")

            if (callingApp != null && callingApp != "unknown") {
                val launchIntent = packageManager.getLaunchIntentForPackage(callingApp)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    startActivity(launchIntent)
                    Log.d(TAG, "✓ Returned focus to calling app: $callingApp")
                    return
                } else {
                    Log.w(TAG, "Could not get launch intent for: $callingApp")
                }
            } else {
                Log.w(TAG, "No calling app info available, falling back to moveTaskToBack")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to return focus to calling app: ${e.message}")
        }

        // Fallback: just move to background
        moveTaskToBack(true)
    }

    /**
     * Common NIP-55 request processing for traditional Intent-based requests
     */
    private fun processNIP55Request(request: NIP55Request, callingApp: String, replyPendingIntent: PendingIntent?) {
        NIP55DebugLogger.logFlowStart(request.id, callingApp, request.type)

        Log.d(TAG, "Processing NIP-55 request: ${request.type}")

        // Initialize WebView and AsyncBridge if not already done
        if (webViewManager == null) {
            Log.d(TAG, "Initializing WebView for NIP-55 processing")
            initializeSecureWebView()
            loadSecurePWA()
        }

        // Process request using AsyncBridge
        activityScope.launch {
            // Wait for PWA to be ready
            var waitCount = 0
            val maxWait = 30 // 30 seconds max wait
            while (webViewManager?.isReady() != true && waitCount < maxWait) {
                delay(1000)
                waitCount++
                Log.d(TAG, "Waiting for PWA to load... ($waitCount/$maxWait)")
            }

            if (webViewManager?.isReady() != true) {
                Log.e(TAG, "PWA failed to load within timeout")
                NIP55DebugLogger.logError("PWA_LOAD_TIMEOUT", Exception("PWA not loaded after ${maxWait}s"))
                sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                    putExtra("error", "PWA failed to load")
                    putExtra("id", request.id)
                })
                return@launch
            }

            // Use AsyncBridge for direct NIP-55 communication
            try {
                Log.d(TAG, "Calling AsyncBridge for NIP-55 request: ${request.id}")

                val asyncBridge = bridges?.asyncBridge
                if (asyncBridge == null) {
                    Log.e(TAG, "AsyncBridge not available")
                    sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                        putExtra("error", "AsyncBridge not available")
                        putExtra("id", request.id)
                    })
                    return@launch
                }

                // Call async bridge and await result
                val result = asyncBridge.callNip55Async(request.type, request.id, request.callingApp, request.params)

                Log.d(TAG, "AsyncBridge result received - success: ${result.ok}")

                // Send result back to calling app
                withContext(Dispatchers.Main) {
                    if (result.ok && result.result != null) {
                        Log.d(TAG, "Setting RESULT_OK for NIP-55 request: ${request.id}")
                        sendReply(replyPendingIntent, RESULT_OK, Intent().apply {
                            putExtra("id", request.id)  // Required by sendReply
                            putExtra("result", result.result)  // Changed from "result_data"
                            putExtra("result_code", RESULT_OK)

                            // For sign_event, also include event field (signed event)
                            if (request.type == "sign_event") {
                                putExtra("event", result.result)
                            }
                        })
                    } else {
                        Log.d(TAG, "Setting RESULT_CANCELED for NIP-55 request: ${request.id} - ${result.reason}")
                        sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                            putExtra("id", request.id)  // Required by sendReply
                            putExtra("error", result.reason ?: "Request failed")
                            putExtra("result_code", RESULT_CANCELED)
                        })
                    }
                    // Do NOT call finish() here - preserve MainActivity process
                }

            } catch (e: Exception) {
                Log.e(TAG, "AsyncBridge request failed", e)
                NIP55DebugLogger.logError("ASYNC_BRIDGE_FAILED", e)

                withContext(Dispatchers.Main) {
                    sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                        putExtra("id", request.id)  // Required by sendReply
                        putExtra("error", "Request failed: ${e.message}")
                        putExtra("result_code", RESULT_CANCELED)
                    })
                    // Do NOT call finish() here - preserve MainActivity process
                }
            }
        }
    }


    /**
     * Initialize WebView using Phase 4 infrastructure (WebViewManager, BridgeFactory)
     */
    private fun initializeSecureWebView() {
        Log.d(TAG, "Initializing secure WebView via WebViewManager...")

        // Set content view to our layout with splash screen
        setContentView(R.layout.activity_main)

        // Initialize overlay manager
        overlayManager = UIOverlayManager(this)

        // Get references to views
        val webViewContainer = findViewById<android.widget.FrameLayout>(R.id.webview_container)
        val splashScreen = findViewById<android.view.View>(R.id.splash_screen)
        overlayManager.setSplashScreen(splashScreen)
        overlayManager.showSplashScreen()

        // Check if we have a persistent WebViewManager from background signing
        val existingManager = persistentWebViewManager
        val existingWebView = persistentWebView

        if (existingManager != null && existingWebView != null && isPersistentWebViewReady) {
            Log.d(TAG, "Reusing persistent WebView from background signing")

            // Reuse existing WebViewManager and WebView
            webViewManager = existingManager

            // Remove from old parent if still attached (shouldn't happen but be safe)
            (existingWebView.parent as? android.view.ViewGroup)?.removeView(existingWebView)

            // Re-attach WebView to container
            webViewContainer.addView(existingWebView, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ))

            // Mark as ready immediately since PWA is already loaded
            isSigningReady = true

            // Hide splash screen immediately
            overlayManager.hideSplashScreen()
            overlayManager.setupDebugButton()
            overlayManager.showDebugButton()

            Log.d(TAG, "Reattached persistent WebView - already ready for signing")
            return
        }

        // No persistent WebView - create new one
        Log.d(TAG, "Creating new WebView")

        // Initialize AppContainer and WebViewManager
        container = AppContainer.getInstance(this)
        val factory = container.createBridgeFactory()

        webViewManager = WebViewManager(
            this,
            factory,
            BridgeFactory.BridgeContext.MAIN_ACTIVITY,
            this
        ).apply {
            setReadyListener(this@MainActivity)
        }

        // Initialize WebView and add to container
        val webView = webViewManager!!.initialize()
        webViewContainer.addView(webView, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Register activity-specific NodeStateBridge with WebView provider for persistence
        val nodeStateBridge = NodeStateBridge(this) { webViewManager?.getWebView() }
        webView.addJavascriptInterface(nodeStateBridge, "NodeStateBridge")
        Log.d(TAG, "Node State Bridge registered")

        // Enable debugging in development
        WebView.setWebContentsDebuggingEnabled(true)
        Log.d(TAG, "WebView debugging enabled for development")

        Log.d(TAG, "Secure WebView initialized successfully via WebViewManager")
    }

    /**
     * Load the secure PWA from bundled assets
     */
    private fun loadSecurePWA() {
        Log.d(TAG, "Loading PWA via WebViewManager...")

        try {
            webViewManager?.loadPWA()
            Log.d(TAG, "Bundled PWA load initiated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled PWA", e)
            loadErrorPage("Failed to load PWA: ${e.message}")
        }
    }

    /**
     * Handle NIP-55 intents in secure mode
     */
    private fun handleIntent(intent: Intent) {
        Log.d(TAG, "=== SECURE INTENT HANDLING ===")

        val data = intent.data
        Log.d(TAG, "Intent action: ${intent.action}")
        Log.d(TAG, "Intent data: ${data?.toString() ?: "null"}")

        // Log intent flags for debugging
        val flags = intent.flags
        Log.d(TAG, "Intent flags: ${Integer.toHexString(flags)}")

        if (data != null) {
            val scheme = data.scheme
            Log.d(TAG, "Intent scheme: $scheme")
            Log.d(TAG, "Non-NIP55 intent, loading normal PWA")
        } else {
            Log.d(TAG, "No intent data, loading normal PWA")
        }
    }





    // ==================== WebViewReadyListener Implementation ====================

    /**
     * Called when the PWA has fully loaded (from WebViewManager)
     */
    override fun onWebViewReady() {
        Log.d(TAG, "WebViewManager: PWA ready")
        onSecurePWAReady()
    }

    /**
     * Called when loading progress changes (from WebViewManager)
     */
    override fun onWebViewLoadProgress(progress: Int) {
        Log.d(TAG, "Secure PWA loading progress: $progress%")
    }

    /**
     * Called for console messages from the PWA (from WebViewManager)
     */
    override fun onConsoleMessage(level: String, message: String, source: String, line: Int) {
        val logMessage = "[SecurePWA-$level] $message ($source:$line)"
        when (level) {
            "ERROR" -> Log.e(TAG, logMessage)
            "WARNING" -> Log.w(TAG, logMessage)
            "DEBUG" -> Log.d(TAG, logMessage)
            else -> Log.i(TAG, logMessage)
        }
    }

    // ==================== PWA Ready Logic ====================

    /**
     * Called when secure PWA is ready
     */
    private fun onSecurePWAReady() {
        Log.d(TAG, "Secure PWA ready - setting up additional functionality")

        // Direct Intent-based communication ready
        isSigningReady = true
        Log.i(TAG, "Direct Intent-based NIP-55 communication ready")

        // Set up IglooHealthManager executor to use our AsyncBridge
        setupHealthManagerExecutor()

        // Handle nip55_wakeup intent - try to process ONE request from queue
        // Health will be marked true only when a request succeeds
        if (intent?.getBooleanExtra("nip55_wakeup", false) == true) {
            Log.d(TAG, "nip55_wakeup intent detected - trying to process queue")
            IglooHealthManager.tryProcessOneFromQueue {
                // Callback invoked when bootstrap signing completes (or queue empty)
                Log.d(TAG, "Bootstrap complete - moving to background")
                moveTaskToBack(true)
            }
        }
        // Note: Health starts false and only becomes true after successful signing

        // Inject any additional setup JavaScript
        val setupScript = """
            console.log('SecurePWA: Ready for operation');

            // Add global error handler
            window.addEventListener('error', function(e) {
                console.error('SecurePWA Error:', e.error);
            });

            // Add unhandled promise rejection handler
            window.addEventListener('unhandledrejection', function(e) {
                console.error('SecurePWA Unhandled Promise Rejection:', e.reason);
            });

            // Notify that secure mode is active
            window.SECURE_MODE = true;
            console.log('SecurePWA: Secure mode activated');

            // Notify that external signing is available
            window.EXTERNAL_SIGNING_READY = true;
            console.log('SecurePWA: External signing bridge ready');
        """.trimIndent()

        webViewManager?.getWebView()?.evaluateJavascript(setupScript) { result ->
            Log.d(TAG, "PWA setup script result: $result")
        }

        // Hide splash screen with fade animation and show debug button
        if (::overlayManager.isInitialized) {
            overlayManager.hideSplashScreen()
            overlayManager.setupDebugButton()
            overlayManager.showDebugButton()
        }

        // Show welcome dialog on first normal launch
        if (isNormalLaunch) {
            showWelcomeDialogIfNeeded()
        }
    }

    /**
     * Set up the IglooHealthManager's request executor to use our AsyncBridge.
     * This allows the health manager to execute signing requests.
     */
    private fun setupHealthManagerExecutor() {
        IglooHealthManager.requestExecutor = object : IglooHealthManager.RequestExecutor {
            override suspend fun execute(request: NIP55Request): NIP55Result {
                val asyncBridge = bridges?.asyncBridge
                if (asyncBridge == null) {
                    Log.e(TAG, "AsyncBridge not available for IglooHealthManager")
                    return NIP55Result(
                        ok = false,
                        type = request.type,
                        id = request.id,
                        reason = "AsyncBridge not available"
                    )
                }

                return try {
                    Log.d(TAG, "IglooHealthManager executing request: ${request.type} (${request.id})")

                    // Audit log only requests that make it to AsyncBridge (after deduplication)
                    when (request.type) {
                        "sign_event" -> {
                            request.params["event"]?.let { eventJson ->
                                AuditLogger.logSignEvent(
                                    this@MainActivity,
                                    request.callingApp,
                                    eventJson,
                                    request.id
                                )
                            }
                        }
                        "nip04_encrypt", "nip04_decrypt", "nip44_encrypt", "nip44_decrypt" -> {
                            request.params["pubkey"]?.let { pubkey ->
                                AuditLogger.logCryptoOperation(
                                    this@MainActivity,
                                    request.callingApp,
                                    request.type,
                                    pubkey,
                                    request.id
                                )
                            }
                        }
                    }

                    asyncBridge.callNip55Async(
                        request.type,
                        request.id,
                        request.callingApp,
                        request.params.mapValues { it.value as Any }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "IglooHealthManager request failed", e)
                    NIP55Result(
                        ok = false,
                        type = request.type,
                        id = request.id,
                        reason = e.message ?: "Execution failed"
                    )
                }
            }
        }
        Log.d(TAG, "IglooHealthManager executor configured")
    }

    /**
     * Show welcome dialog if the user hasn't dismissed it permanently
     */
    private fun showWelcomeDialogIfNeeded() {
        try {
            val storageBridge = bridges?.storageBridge ?: return
            if (WelcomeDialog.shouldShow(storageBridge)) {
                Log.d(TAG, "Showing welcome dialog")
                val dialog = WelcomeDialog.newInstance()
                dialog.show(supportFragmentManager, "WelcomeDialog")
            } else {
                Log.d(TAG, "Welcome dialog already dismissed by user")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show welcome dialog", e)
        }
    }

    /**
     * Load error page
     */
    private fun loadErrorPage(errorMessage: String) {
        Log.e(TAG, "Loading error page: $errorMessage")

        val errorHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Secure PWA Error</title>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: Arial, sans-serif; padding: 20px; text-align: center; }
                    .error { color: #d32f2f; margin: 20px 0; }
                    .retry { background: #1976d2; color: white; padding: 10px 20px; border: none; border-radius: 4px; }
                </style>
            </head>
            <body>
                <h1>Secure PWA Error</h1>
                <div class="error">$errorMessage</div>
                <button class="retry" onclick="location.reload()">Retry</button>
            </body>
            </html>
        """.trimIndent()

        webViewManager?.getWebView()?.loadDataWithBaseURL(
            "igloo://app/error.html",
            errorHtml,
            "text/html",
            "UTF-8",
            null
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "=== SECURE ACTIVITY: onNewIntent ===")

        setIntent(intent)

        // Mark that we received a fresh NIP-55 intent (checked in onResume)
        receivedFreshNIP55Intent = true

        // Check if this is a background service request
        val isBackgroundServiceRequest = intent.getBooleanExtra("background_service_request", false)
        if (isBackgroundServiceRequest) {
            Log.d(TAG, "Background service request detected in onNewIntent - processing in background")
        }

        // Handle nip55_wakeup intent - try to process ONE request from queue
        // Only process if signing is ready (executor set up), otherwise onSecurePWAReady will handle it
        if (intent.getBooleanExtra("nip55_wakeup", false)) {
            // Show signing overlay for background signing requests
            val isBackgroundSigningRequest = intent.getBooleanExtra("background_signing_request", false)
            if (isBackgroundSigningRequest && ::overlayManager.isInitialized) {
                val signingType = intent.getStringExtra("signing_request_type") ?: "unknown"
                val callingApp = intent.getStringExtra("signing_calling_app") ?: "unknown"
                Log.d(TAG, "nip55_wakeup with background signing - showing overlay (type=$signingType, app=$callingApp)")
                overlayManager.setupSigningOverlay()
                overlayManager.showSigningOverlay()
            }

            if (isSigningReady) {
                Log.d(TAG, "nip55_wakeup intent received in onNewIntent - processing queue (signing ready)")
                IglooHealthManager.tryProcessOneFromQueue {
                    // Callback invoked when bootstrap signing completes (or queue empty)
                    Log.d(TAG, "Bootstrap complete (onNewIntent) - moving to background")
                    moveTaskToBack(true)
                }
            } else {
                Log.d(TAG, "nip55_wakeup intent received but signing not ready - will process in onSecurePWAReady")
                // Don't return early - let the activity continue loading
                // Queue will be processed when onSecurePWAReady() is called
            }
            return
        }

        when (intent.action) {
            "$packageName.UNLOCK_AND_SIGN" -> {
                // Unlock request from InvisibleNIP55Handler when node is locked
                Log.d(TAG, "Unlock and sign request in onNewIntent")
                NIP55DebugLogger.logIntent("UNLOCK_AND_SIGN_NEW", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString()
                ))
                storePendingUnlockRequest(intent)
                // WebView should already be initialized, just wait for unlock
            }
            "$packageName.SHOW_PERMISSION_DIALOG" -> {
                // Permission dialog request from InvisibleNIP55Handler
                Log.d(TAG, "Permission dialog request in onNewIntent - showing native dialog")
                // Dismiss keyboard immediately - it may be open from the calling app
                dismissKeyboard()
                NIP55DebugLogger.logIntent("PERMISSION_DIALOG_NEW", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString()
                ))
                handlePermissionDialogRequest()
            }
            "$packageName.NIP55_SIGNING" -> {
                NIP55DebugLogger.logIntent("MAIN_RECEIVED_NEW", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString(),
                    "backgroundServiceRequest" to isBackgroundServiceRequest
                ))

                // Show signing overlay for fast signing (no prompt)
                val showPrompt = intent.getBooleanExtra("nip55_show_prompt", false)
                if (!showPrompt) {
                    Log.d(TAG, "Fast signing detected in onNewIntent - showing signing overlay")
                    if (::overlayManager.isInitialized) {
                        overlayManager.setupSigningOverlay()
                        overlayManager.showSigningOverlay()
                    }
                }

                handleNIP55Request()
            }
            else -> {
                // Check if this is a background signing request (from InvisibleNIP55Handler)
                val isBackgroundSigningRequest = intent.getBooleanExtra("background_signing_request", false)
                if (isBackgroundSigningRequest) {
                    val signingType = intent.getStringExtra("signing_request_type") ?: "unknown"
                    val callingApp = intent.getStringExtra("signing_calling_app") ?: "unknown"
                    Log.d(TAG, "Background signing in onNewIntent - showing signing overlay (type=$signingType, app=$callingApp)")
                    if (::overlayManager.isInitialized) {
                        overlayManager.setupSigningOverlay()
                        overlayManager.showSigningOverlay()
                    }
                }
                handleIntent(intent)
            }
        }
    }

    override fun onBackPressed() {
        val webView = webViewManager?.getWebView()
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else if (IglooHealthManager.isHealthy) {
            // System is healthy - move to background instead of finishing
            // This keeps the WebView alive for background signing
            Log.d(TAG, "System healthy - moving to background instead of finishing")
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Forward to QR scanner bridge
        bridges?.qrScannerBridge?.handleActivityResult(requestCode, resultCode, data)
    }

    // Flag to track if we just received a fresh NIP-55 intent (set in onNewIntent, cleared in onResume)
    private var receivedFreshNIP55Intent = false

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "=== SECURE ACTIVITY LIFECYCLE: onResume ===")

        // Reset health timeout when activity resumes (user is interacting)
        if (IglooHealthManager.isHealthy) {
            IglooHealthManager.resetHealthTimeout()
        }

        // Check if there are pending signing requests
        val hasPendingRequests = IglooHealthManager.hasPendingRequests()

        // Determine if this is a fresh NIP-55 flow or manual user switch
        // receivedFreshNIP55Intent is set in onNewIntent and cleared here
        val isFreshNIP55Flow = receivedFreshNIP55Intent
        receivedFreshNIP55Intent = false  // Clear for next onResume

        if (!isFreshNIP55Flow && !hasPendingRequests) {
            // User manually switched to Igloo and no signing in progress
            // Hide signing overlay and show normal PWA interface
            setUserActive(true)
            Log.d(TAG, "User manually brought Igloo to foreground (no pending requests) - hiding signing overlay")
            if (::overlayManager.isInitialized) {
                overlayManager.hideSigningOverlay()
            }
            // Clear any stale NIP-55 intent extras
            intent?.removeExtra("nip55_wakeup")
            intent?.removeExtra("background_signing_request")
            intent?.removeExtra("fresh_nip55_request")
        } else {
            Log.d(TAG, "Resumed during NIP-55 flow (fresh=$isFreshNIP55Flow) or with pending requests ($hasPendingRequests) - keeping overlay")
        }

        // Dismiss any open keyboard immediately when coming to foreground
        // This ensures keyboard from other apps (like Amethyst) doesn't block our UI
        dismissKeyboard()

        webViewManager?.getWebView()?.onResume()
        // Don't call resumeTimers() since we never pause them
        // This allows JavaScript to continue running in background for signing
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "=== SECURE ACTIVITY LIFECYCLE: onPause ===")

        // Clear user active flag - user is leaving Igloo
        setUserActive(false)

        // Backup session storage before app may be terminated
        bridges?.storageBridge?.backupSessionStorage()

        // DON'T call webView.onPause() - this can pause JavaScript execution
        // We need JavaScript to keep running for background signing
    }

    override fun onDestroy() {
        Log.d(TAG, "=== SECURE ACTIVITY LIFECYCLE: onDestroy ===")

        // Clear active instance if this is the active one
        if (activeInstance == this) {
            activeInstance = null
            Log.d(TAG, "Cleared active MainActivity instance")
        }

        // When system is healthy, DON'T destroy the WebView - preserve it for reattachment
        // Android may destroy the activity for memory, but we keep WebView alive in static holder
        if (IglooHealthManager.isHealthy) {
            Log.w(TAG, "MainActivity destroyed while healthy - preserving WebView for reattachment")

            // Detach WebView from this activity's layout but keep reference
            webViewManager?.getWebView()?.let { wv ->
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                Log.d(TAG, "WebView detached from activity layout (preserved in static holder)")
            }

            // DON'T clear persistentWebView or persistentWebViewManager - keep them alive
            // Just clear the instance-specific manager reference
            webViewManager = null
        } else {
            // System not healthy - clean up everything
            Log.d(TAG, "System not healthy - cleaning up WebView resources")
            clearPersistentWebView()
            webViewManager?.cleanup()
            webViewManager = null
        }

        // Cancel activity scope
        activityScope.cancel()

        super.onDestroy()
    }

    /**
     * Send reply via IglooHealthManager (cross-task safe)
     */
    private fun sendReply(replyPendingIntent: PendingIntent?, resultCode: Int, resultData: Intent) {
        // Get request ID to identify which callback to invoke
        val requestId = resultData.getStringExtra("id")

        if (requestId != null) {
            // Create NIP55Result from resultData
            val result = if (resultCode == RESULT_OK) {
                NIP55Result(
                    ok = true,
                    type = "result",
                    id = requestId,
                    result = resultData.getStringExtra("result"),
                    reason = null
                )
            } else {
                NIP55Result(
                    ok = false,
                    type = "error",
                    id = requestId,
                    result = null,
                    reason = resultData.getStringExtra("error") ?: "User denied permission"
                )
            }

            Log.d(TAG, "Delivering result to health manager for request: $requestId (ok=${result.ok})")

            // Deliver result via health manager (works across task boundaries)
            val delivered = IglooHealthManager.deliverResultByRequestId(requestId, result)

            if (delivered) {
                Log.d(TAG, "✓ Result delivered successfully via health manager")
                // Reset health timeout after successful signing
                IglooHealthManager.resetHealthTimeout()
            } else {
                Log.w(TAG, "✗ No callback registered for request: $requestId")
            }
        } else {
            Log.w(TAG, "No request ID in resultData - cannot deliver result")
        }

        // Hide signing overlay before returning to calling app
        if (::overlayManager.isInitialized) {
            overlayManager.hideSigningOverlay()
        }

        // Return focus to the calling app (Amethyst) instead of just going to background
        returnFocusToCallingApp()
    }
}