package com.frostr.igloo

import androidx.appcompat.app.AppCompatActivity
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.ConsoleMessage
import androidx.core.app.NotificationCompat
import com.frostr.igloo.bridges.IglooWebViewClient
import com.frostr.igloo.bridges.WebSocketBridge
import com.frostr.igloo.bridges.StorageBridge
import com.frostr.igloo.bridges.QRScannerBridge
import com.frostr.igloo.bridges.UnifiedSigningBridge
import com.frostr.igloo.bridges.UnifiedSigningService
import com.frostr.igloo.debug.NIP55DebugLogger
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

// Import NIP55Request data class
import com.frostr.igloo.NIP55Request

/**
 * Main Activity with Polyfill Integration
 *
 * This activity serves the PWA from assets with custom protocol while maintaining full functionality.
 * Provides secure architecture with transparent polyfills for WebSocket, Storage, Camera, and Signing operations.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SecureIglooWrapper"
        private const val CUSTOM_SCHEME = "igloo"
        private const val PWA_HOST = "app"
        private const val SECURE_PWA_URL = "$CUSTOM_SCHEME://$PWA_HOST/index.html"

        // Reference to active MainActivity instance
        @Volatile
        private var activeInstance: MainActivity? = null

        /**
         * Get WebView instance for ContentProvider access
         * Returns null if MainActivity is not active or WebView is not initialized
         */
        @JvmStatic
        fun getWebViewInstance(): WebView? {
            val instance = activeInstance ?: return null
            return try {
                if (instance.isSecurePWALoaded) {
                    instance.webView
                } else {
                    null
                }
            } catch (e: UninitializedPropertyAccessException) {
                null
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var asyncBridge: AsyncBridge
    private lateinit var webSocketBridge: WebSocketBridge
    private lateinit var storageBridge: StorageBridge
    private lateinit var qrScannerBridge: QRScannerBridge
    private lateinit var signingBridge: UnifiedSigningBridge
    private lateinit var signingService: UnifiedSigningService
    private var isSecurePWALoaded = false
    private var isSigningReady = false
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val gson = Gson()
    private var signingOverlay: android.view.View? = null
    private var splashScreen: android.view.View? = null

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

        if (isBackgroundServiceRequest) {
            Log.d(TAG, "Background service request - continuing setup in background")
        }

        // NOTE: IglooBackgroundService was removed (no background signing)
        // startIglooBackgroundService()

        when (intent.action) {
            "com.frostr.igloo.SHOW_PERMISSION_DIALOG" -> {
                // Permission dialog request from InvisibleNIP55Handler
                Log.d(TAG, "Permission dialog request - showing native dialog")
                NIP55DebugLogger.logIntent("PERMISSION_DIALOG", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString()
                ))
                handlePermissionDialogRequest()
            }
            "com.frostr.igloo.NIP55_SIGNING" -> {
                NIP55DebugLogger.logIntent("MAIN_RECEIVED", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString(),
                    "backgroundServiceRequest" to isBackgroundServiceRequest
                ))

                // Direct Intent request - traditional flow
                val showPrompt = intent.getBooleanExtra("nip55_show_prompt", false)
                if (!showPrompt) {
                    Log.d(TAG, "Fast signing detected - showing signing overlay")
                    setupSigningOverlay()
                    showSigningOverlay()
                }

                Log.d(TAG, "Direct Intent signing - initializing WebView and handling request")
                initializeSecureWebView()
                loadSecurePWA()
                handleNIP55Request()
            }
            else -> {
                NIP55DebugLogger.logPWABridge("NORMAL_STARTUP", "initializePWA")
                initializeSecureWebView()
                loadSecurePWA()
                handleIntent(intent)
            }
        }
    }

    // NOTE: IglooBackgroundService was removed (no background signing needed)
    // Background signing isn't possible anyway, so this service was deleted

    /**
     * Setup signing overlay (splash screen for fast signing)
     */
    private fun setupSigningOverlay() {
        if (signingOverlay == null) {
            val inflater = android.view.LayoutInflater.from(this)
            signingOverlay = inflater.inflate(R.layout.signing_overlay, null)
        }
    }

    /**
     * Show signing overlay (hides PWA during fast signing)
     */
    private fun showSigningOverlay() {
        signingOverlay?.let { overlay ->
            if (overlay.parent == null) {
                val rootView = window.decorView as android.view.ViewGroup
                rootView.addView(overlay)
                Log.d(TAG, "✓ Signing overlay shown")
            }
        }
    }

    /**
     * Hide signing overlay (reveals PWA)
     */
    private fun hideSigningOverlay() {
        signingOverlay?.let { overlay ->
            val parent = overlay.parent as? android.view.ViewGroup
            parent?.removeView(overlay)
            Log.d(TAG, "✓ Signing overlay hidden")
        }
    }

    /**
     * Handle NIP-55 signing request from InvisibleNIP55Handler
     */
    private fun handleNIP55Request() {
        Log.d(TAG, "=== HANDLING NIP-55 REQUEST (AsyncBridge) ===")

        try {
            val replyPendingIntent = intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
            Log.d(TAG, "Processing traditional NIP-55 request")
            handleTraditionalNIP55Request(replyPendingIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in NIP-55 request handling", e)
            NIP55DebugLogger.logError("CRITICAL_HANDLE", e)

            val replyPendingIntent = intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
            sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                putExtra("error", "Critical error: ${e.message}")
                putExtra("result_code", RESULT_CANCELED)
            })
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
        if (!::webView.isInitialized) {
            Log.d(TAG, "Initializing WebView for signing after permission approval")
            initializeSecureWebView()
            loadSecurePWA()
        }

        // Show signing overlay for the operation
        setupSigningOverlay()
        showSigningOverlay()

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

        val delivered = PendingNIP55ResultRegistry.deliverResult(requestId, result)
        if (delivered) {
            Log.d(TAG, "✓ Permission denial delivered via registry")
        } else {
            Log.w(TAG, "✗ No callback registered for request: $requestId")
        }

        // Do NOT call finish() - keep MainActivity alive for subsequent NIP-55 requests
        // With singleTask launchMode, Android will reuse this instance instead of creating new ones
        moveTaskToBack(true)
    }

    /**
     * Common NIP-55 request processing for traditional Intent-based requests
     */
    private fun processNIP55Request(request: NIP55Request, callingApp: String, replyPendingIntent: PendingIntent?) {
        NIP55DebugLogger.logFlowStart(request.id, callingApp, request.type)

        Log.d(TAG, "Processing NIP-55 request: ${request.type}")

        // Initialize WebView and AsyncBridge if not already done
        if (!::webView.isInitialized) {
            Log.d(TAG, "Initializing WebView for NIP-55 processing")
            initializeSecureWebView()
            loadSecurePWA()
        }

        // Process request using AsyncBridge
        activityScope.launch {
            // Wait for PWA to be ready
            var waitCount = 0
            val maxWait = 30 // 30 seconds max wait
            while (!isSecurePWALoaded && waitCount < maxWait) {
                delay(1000)
                waitCount++
                Log.d(TAG, "Waiting for PWA to load... ($waitCount/$maxWait)")
            }

            if (!isSecurePWALoaded) {
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
     * Initialize WebView with secure configuration and polyfill bridges
     */
    private fun initializeSecureWebView() {
        Log.d(TAG, "Initializing secure WebView...")

        // Set content view to our layout with splash screen
        setContentView(R.layout.activity_main)

        // Get references to views
        val webViewContainer = findViewById<android.widget.FrameLayout>(R.id.webview_container)
        splashScreen = findViewById(R.id.splash_screen)

        // Create and add WebView to container
        webView = WebView(this)
        webViewContainer.addView(webView, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Show splash screen initially
        splashScreen?.visibility = android.view.View.VISIBLE
        Log.d(TAG, "Splash screen displayed")

        // Configure secure WebView settings
        configureSecureWebView()

        // Register secure bridges
        registerSecureBridges()

        // Set secure WebView client
        webView.webViewClient = IglooWebViewClient(this)

        // Set console logging client
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val level = consoleMessage.messageLevel().name
                val message = consoleMessage.message()
                val source = consoleMessage.sourceId()
                val line = consoleMessage.lineNumber()

                val logMessage = "[SecurePWA-$level] $message ($source:$line)"

                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, logMessage)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, logMessage)
                    ConsoleMessage.MessageLevel.DEBUG -> Log.d(TAG, logMessage)
                    else -> Log.i(TAG, logMessage)
                }
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                Log.d(TAG, "Secure PWA loading progress: $newProgress%")

                if (newProgress == 100 && !isSecurePWALoaded) {
                    Log.d(TAG, "Secure PWA fully loaded")
                    isSecurePWALoaded = true
                    onSecurePWAReady()
                }
            }
        }

        Log.d(TAG, "Secure WebView initialized successfully")
    }

    /**
     * Configure WebView with secure settings
     */
    private fun configureSecureWebView() {
        val webSettings = webView.settings

        // Enable required features
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = false // Disabled - we'll use our secure storage bridge

        // Security settings
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = false
        webSettings.allowFileAccessFromFileURLs = false
        webSettings.allowUniversalAccessFromFileURLs = false
        webSettings.blockNetworkLoads = false // We'll handle blocking in WebViewClient

        // Disable built-in storage (we'll use our secure bridges)
        webSettings.databaseEnabled = false
        webSettings.cacheMode = WebSettings.LOAD_NO_CACHE

        // Performance settings (renderPriority deprecated)
        // webSettings.renderPriority = WebSettings.RenderPriority.HIGH

        // Enable debugging in development
        if (true) { // BuildConfig.DEBUG replacement
            WebView.setWebContentsDebuggingEnabled(true)
            Log.d(TAG, "WebView debugging enabled for development")
        }

        Log.d(TAG, "Secure WebView settings configured")
    }

    /**
     * Register secure bridges for polyfill functionality
     */
    private fun registerSecureBridges() {
        Log.d(TAG, "Registering secure bridges...")

        // Initialize AsyncBridge for NIP-55 communication
        asyncBridge = AsyncBridge(webView)
        asyncBridge.initialize()
        Log.d(TAG, "AsyncBridge initialized")

        // WebSocket bridge
        webSocketBridge = WebSocketBridge(webView)
        webView.addJavascriptInterface(webSocketBridge, "WebSocketBridge")
        Log.d(TAG, "WebSocket bridge registered")

        // Storage bridge
        storageBridge = StorageBridge(this)
        webView.addJavascriptInterface(storageBridge, "StorageBridge")
        Log.d(TAG, "Storage bridge registered")

        // Restore session storage from backup if available
        if (storageBridge.restoreSessionStorage()) {
            Log.d(TAG, "Session storage restored from backup")
        } else {
            Log.d(TAG, "No session storage backup to restore")
        }

        // QR Scanner bridge - native Android QR scanning
        qrScannerBridge = QRScannerBridge(this, webView)
        webView.addJavascriptInterface(qrScannerBridge, "QRScannerBridge")
        Log.d(TAG, "QR Scanner bridge registered")

        // Initialize signing system - simplified forwarding mode
        signingService = UnifiedSigningService(this, webView)

        // Unified Signing bridge
        signingBridge = UnifiedSigningBridge(this, webView)
        signingBridge.initialize(signingService)
        webView.addJavascriptInterface(signingBridge, "UnifiedSigningBridge")
        Log.d(TAG, "Unified Signing bridge registered")

        // IPC server removed - using direct Intent-based communication
        Log.d(TAG, "Direct Intent-based communication enabled")

        Log.d(TAG, "All secure bridges registered successfully")
    }

    /**
     * Load the secure PWA from bundled assets
     */
    private fun loadSecurePWA() {
        // Load from bundled assets using custom protocol
        Log.d(TAG, "Loading PWA from bundled assets: $SECURE_PWA_URL")

        try {
            webView.loadUrl(SECURE_PWA_URL)
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





    /**
     * Called when secure PWA is ready
     */
    private fun onSecurePWAReady() {
        Log.d(TAG, "Secure PWA ready - setting up additional functionality")

        // Direct Intent-based communication ready
        isSigningReady = true
        Log.i(TAG, "Direct Intent-based NIP-55 communication ready")

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

        webView.evaluateJavascript(setupScript) { result ->
            Log.d(TAG, "PWA setup script result: $result")
        }

        // Hide splash screen with fade animation
        hideSplashScreen()
    }

    /**
     * Hide the splash screen with a smooth fade-out animation
     */
    private fun hideSplashScreen() {
        splashScreen?.let { splash ->
            Log.d(TAG, "Hiding splash screen with fade animation")
            splash.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    splash.visibility = android.view.View.GONE
                    Log.d(TAG, "Splash screen hidden")
                }
                .start()
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

        webView.loadDataWithBaseURL(
            SECURE_PWA_URL,
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

        // Check if this is a background service request
        val isBackgroundServiceRequest = intent.getBooleanExtra("background_service_request", false)
        if (isBackgroundServiceRequest) {
            Log.d(TAG, "Background service request detected in onNewIntent - processing in background")
        }

        when (intent.action) {
            "com.frostr.igloo.SHOW_PERMISSION_DIALOG" -> {
                // Permission dialog request from InvisibleNIP55Handler
                Log.d(TAG, "Permission dialog request in onNewIntent - showing native dialog")
                NIP55DebugLogger.logIntent("PERMISSION_DIALOG_NEW", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString()
                ))
                handlePermissionDialogRequest()
            }
            "com.frostr.igloo.NIP55_SIGNING" -> {
                NIP55DebugLogger.logIntent("MAIN_RECEIVED_NEW", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString(),
                    "backgroundServiceRequest" to isBackgroundServiceRequest
                ))

                // Show signing overlay for fast signing (no prompt)
                val showPrompt = intent.getBooleanExtra("nip55_show_prompt", false)
                if (!showPrompt) {
                    Log.d(TAG, "Fast signing detected in onNewIntent - showing signing overlay")
                    setupSigningOverlay()
                    showSigningOverlay()
                }

                handleNIP55Request()
            }
            else -> {
                handleIntent(intent)
            }
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Forward to QR scanner bridge
        if (::qrScannerBridge.isInitialized) {
            qrScannerBridge.handleActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "=== SECURE ACTIVITY LIFECYCLE: onResume ===")

        if (::webView.isInitialized) {
            webView.onResume()
            // Don't call resumeTimers() since we never pause them
            // This allows JavaScript to continue running in background for signing
        }

        // Register as listener for NIP-55 requests from InvisibleNIP55Handler
        NIP55RequestBridge.registerListener(object : NIP55RequestBridge.RequestListener {
            override fun onNIP55Request(request: NIP55Request) {
                Log.d(TAG, "Received NIP-55 request via bridge: ${request.type} (${request.id})")
                // Process the request (same as if it came via Intent)
                processNIP55Request(request, request.callingApp, null)
            }
        })
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "=== SECURE ACTIVITY LIFECYCLE: onPause ===")

        // DON'T unregister bridge listener - keep it active for background signing
        // NIP55RequestBridge.unregisterListener()

        // Backup session storage before app may be terminated
        if (::storageBridge.isInitialized) {
            storageBridge.backupSessionStorage()
        }

        // DON'T call webView.onPause() - this can pause JavaScript execution
        // We need JavaScript to keep running for background signing
        // if (::webView.isInitialized) {
        //     webView.onPause()
        // }
    }

    override fun onDestroy() {
        Log.d(TAG, "=== SECURE ACTIVITY LIFECYCLE: onDestroy ===")

        // Clear active instance if this is the active one
        if (activeInstance == this) {
            activeInstance = null
            Log.d(TAG, "Cleared active MainActivity instance")
        }

        // Clean up WebSocket bridge
        if (::webSocketBridge.isInitialized) {
            webSocketBridge.cleanup()
        }

        // Clean up session storage (as per Web Storage API behavior)
        if (::storageBridge.isInitialized) {
            storageBridge.clearSessionStorage()
        }

        // Clean up QR scanner bridge
        if (::qrScannerBridge.isInitialized) {
            qrScannerBridge.cleanup()
        }

        // Clean up signing bridge
        if (::signingBridge.isInitialized) {
            signingBridge.cleanup()
        }

        // Clean up signing service
        if (::signingService.isInitialized) {
            signingService.cleanup()
        }

        // IPC server removed - using direct Intent-based communication
        Log.d(TAG, "Direct Intent-based communication cleanup")

        // Cancel activity scope
        activityScope.cancel()

        super.onDestroy()
    }

    /**
     * Send reply via PendingNIP55ResultRegistry (cross-task safe)
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

            Log.d(TAG, "Delivering result to registry for request: $requestId (ok=${result.ok})")

            // Deliver result via registry (works across task boundaries)
            val delivered = PendingNIP55ResultRegistry.deliverResult(requestId, result)

            if (delivered) {
                Log.d(TAG, "✓ Result delivered successfully via registry")
            } else {
                Log.w(TAG, "✗ No callback registered for request: $requestId")
            }
        } else {
            Log.w(TAG, "No request ID in resultData - cannot deliver result")
        }

        // Hide signing overlay before returning to background
        hideSigningOverlay()

        // Do NOT call finish() - keep MainActivity alive for subsequent NIP-55 requests
        // With singleTask launchMode, Android will reuse this instance instead of creating new ones
        moveTaskToBack(true)
    }
}