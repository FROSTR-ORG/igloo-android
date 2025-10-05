package com.frostr.igloo

import androidx.appcompat.app.AppCompatActivity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.ConsoleMessage
import com.frostr.igloo.bridges.IglooWebViewClient
import com.frostr.igloo.bridges.WebSocketBridge
import com.frostr.igloo.bridges.StorageBridge
import com.frostr.igloo.bridges.ModernCameraBridge
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
 * Content Resolver request data class for queue management
 */
data class ContentResolverRequest(
    val intent: Intent,
    val requestId: String
)

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

        // Content Resolver request queue (shared across activity instances)
        private val contentResolverQueue = ConcurrentLinkedQueue<ContentResolverRequest>()
        private val isProcessingQueue = AtomicBoolean(false)

        // Reference to active MainActivity instance
        @Volatile
        private var activeInstance: MainActivity? = null

        /**
         * Add Content Resolver request to queue and trigger processing
         */
        fun enqueueContentResolverRequest(intent: Intent, requestId: String, context: android.content.Context) {
            val request = ContentResolverRequest(intent, requestId)
            contentResolverQueue.offer(request)
            Log.d(TAG, "Content Resolver request queued: $requestId (queue size: ${contentResolverQueue.size})")

            // If there's an active instance, notify it to process queue
            if (activeInstance != null) {
                Log.d(TAG, "Active MainActivity instance found, triggering queue processing")
                activeInstance?.processContentResolverQueue()
            } else {
                // No active instance - need to launch MainActivity to process queue
                Log.d(TAG, "No active MainActivity instance, launching activity to process queue")
                context.startActivity(intent)
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var asyncBridge: AsyncBridge
    private lateinit var webSocketBridge: WebSocketBridge
    private lateinit var storageBridge: StorageBridge
    private lateinit var cameraBridge: ModernCameraBridge
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
                val isContentResolver = intent.getBooleanExtra("is_content_resolver", false)

                NIP55DebugLogger.logIntent("MAIN_RECEIVED", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString(),
                    "backgroundServiceRequest" to isBackgroundServiceRequest,
                    "isContentResolver" to isContentResolver
                ))

                if (isContentResolver) {
                    // Content Resolver request - already queued, just initialize and process
                    Log.d(TAG, "Content Resolver request - initializing WebView and processing queue")
                    initializeSecureWebView()
                    loadSecurePWA()
                    processContentResolverQueue()
                } else {
                    // Direct Intent request - traditional flow
                    val showPrompt = intent.getBooleanExtra("nip55_show_prompt", false)
                    if (!showPrompt) {
                        Log.d(TAG, "Fast signing detected - showing splash overlay")
                        setupSigningOverlay()
                        showSigningOverlay()
                    }
                    handleNIP55Request()
                }
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
     * Handle NIP-55 signing request from both InvisibleNIP55Handler and ContentProvider
     */
    private fun handleNIP55Request() {
        Log.d(TAG, "=== HANDLING NIP-55 REQUEST (AsyncBridge) ===")

        try {
            val replyPendingIntent = intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
            val isContentResolver = intent.getBooleanExtra("is_content_resolver", false)

            if (isContentResolver) {
                Log.d(TAG, "Processing Content Resolver request")
                handleContentResolverRequest(replyPendingIntent)
            } else {
                Log.d(TAG, "Processing traditional NIP-55 request")
                handleTraditionalNIP55Request(replyPendingIntent)
            }

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
     * Handle Content Resolver NIP-55 request (structured parameters)
     */
    private fun handleContentResolverRequest(replyPendingIntent: PendingIntent?) {
        val operationType = intent.getStringExtra("type")
        val callingApp = intent.getStringExtra("calling_package") ?: "unknown"
        val requestId = intent.getStringExtra("id") ?: java.util.UUID.randomUUID().toString()

        if (operationType == null) {
            Log.e(TAG, "No operation type found in Content Resolver request")
            sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                putExtra("error", "No operation type")
            })
            return
        }

        Log.d(TAG, "Processing Content Resolver request: $operationType from: $callingApp")

        // Convert Content Resolver parameters to NIP55Request format
        val params = mutableMapOf<String, String>()

        // Add event parameter if present
        intent.getStringExtra("event")?.let { params["event"] = it }

        // Add other parameters if present
        intent.getStringExtra("pubkey")?.let { params["pubkey"] = it }
        intent.getStringExtra("plaintext")?.let { params["plaintext"] = it }
        intent.getStringExtra("ciphertext")?.let { params["ciphertext"] = it }
        intent.getStringExtra("current_user")?.let { params["current_user"] = it }

        val request = NIP55Request(
            id = requestId,
            type = operationType,
            params = params.toMap(),
            callingApp = callingApp,
            timestamp = System.currentTimeMillis()
        )

        processNIP55Request(request, callingApp, replyPendingIntent)
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
     * Handle approved permission - re-check permission and execute signing operation
     */
    private fun handleApprovedPermission(requestId: String) {
        Log.d(TAG, "Permission approved - delivering result to registry")

        // Permission was saved by the dialog, now signal approval to InvisibleNIP55Handler
        val result = NIP55Result(
            ok = true,
            type = "permission_approved",
            id = requestId,
            result = null,
            reason = null
        )

        val delivered = PendingNIP55ResultRegistry.deliverResult(requestId, result)
        if (delivered) {
            Log.d(TAG, "✓ Permission approval delivered via registry")
        } else {
            Log.e(TAG, "✗ Failed to deliver permission approval - no callback registered")
        }

        finish()
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

        finish()
    }

    /**
     * Common NIP-55 request processing for both traditional and Content Resolver requests
     */
    private fun processNIP55Request(request: NIP55Request, callingApp: String, replyPendingIntent: PendingIntent?) {
        NIP55DebugLogger.logFlowStart(request.id, callingApp, request.type)

        // Log request type for debugging
        val isContentResolver = intent.getBooleanExtra("is_content_resolver", false)
        if (isContentResolver) {
            Log.d(TAG, "Processing Content Resolver request: ${request.type}")
        } else {
            Log.d(TAG, "Processing traditional NIP-55 request: ${request.type}")
        }

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

        // Modern Camera bridge
        cameraBridge = ModernCameraBridge(this, webView)
        webView.addJavascriptInterface(cameraBridge, "CameraBridge")
        Log.d(TAG, "Modern Camera bridge registered")

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
                    Log.d(TAG, "Fast signing detected in onNewIntent - showing splash overlay")
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
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "=== SECURE ACTIVITY LIFECYCLE: onResume ===")

        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "=== SECURE ACTIVITY LIFECYCLE: onPause ===")

        // Backup session storage before app may be terminated
        if (::storageBridge.isInitialized) {
            storageBridge.backupSessionStorage()
        }

        webView.onPause()
        webView.pauseTimers()
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

        // Clean up camera bridge
        if (::cameraBridge.isInitialized) {
            cameraBridge.cleanup()
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
     * Process Content Resolver request queue sequentially
     */
    private fun processContentResolverQueue() {
        // Check if already processing
        if (!isProcessingQueue.compareAndSet(false, true)) {
            Log.d(TAG, "Queue already being processed")
            return
        }

        activityScope.launch {
            try {
                Log.d(TAG, "Starting Content Resolver queue processing (queue size: ${contentResolverQueue.size})")

                while (contentResolverQueue.isNotEmpty()) {
                    val request = contentResolverQueue.poll() ?: break

                    Log.d(TAG, "Processing queued Content Resolver request: ${request.requestId}")

                    try {
                        // Wait for WebView to be ready
                        while (!isSecurePWALoaded) {
                            Log.d(TAG, "Waiting for WebView to be ready...")
                            delay(100)
                        }

                        // Process this request using the existing Intent data
                        val replyPendingIntent = request.intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
                        val isContentResolver = request.intent.getBooleanExtra("is_content_resolver", false)

                        if (isContentResolver) {
                            // Temporarily update intent for processing
                            val oldIntent = intent
                            intent = request.intent

                            handleContentResolverRequest(replyPendingIntent)

                            // Restore original intent
                            intent = oldIntent
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing queued request ${request.requestId}", e)
                        // Send error reply
                        val replyPendingIntent = request.intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
                        val broadcastAction = request.intent.getStringExtra("reply_broadcast_action")

                        if (broadcastAction != null) {
                            val errorIntent = Intent(broadcastAction).apply {
                                setPackage(packageName)
                                putExtra("error", "Processing failed: ${e.message}")
                            }
                            sendBroadcast(errorIntent)
                        }
                    }

                    // Small delay between requests to avoid overwhelming the WebView
                    delay(50)
                }

                Log.d(TAG, "Content Resolver queue processing complete")

            } catch (e: Exception) {
                Log.e(TAG, "Critical error in queue processing", e)
            } finally {
                isProcessingQueue.set(false)

                // Check if more requests were added during processing
                if (contentResolverQueue.isNotEmpty()) {
                    Log.d(TAG, "More requests queued, restarting processing")
                    processContentResolverQueue()
                }
            }
        }
    }

    /**
     * Send reply via PendingNIP55ResultRegistry (cross-task safe) or Broadcast (for ContentProvider)
     */
    private fun sendReply(replyPendingIntent: PendingIntent?, resultCode: Int, resultData: Intent) {
        // Get request ID to identify which callback to invoke
        val requestId = resultData.getStringExtra("id")

        // Check if this is a ContentProvider request
        val broadcastAction = intent.getStringExtra("reply_broadcast_action")

        if (broadcastAction != null) {
            // ContentProvider request - send broadcast
            Log.d(TAG, "Sending broadcast reply for ContentProvider: $broadcastAction")
            val broadcastIntent = Intent(broadcastAction).apply {
                setPackage(packageName)
                putExtras(resultData)
            }
            sendBroadcast(broadcastIntent)
            hideSigningOverlay()
            // Do NOT call finish() or moveTaskToBack() - keep MainActivity alive for concurrent requests
            // Just hide the overlay and let the activity stay in the background
            return
        }

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

        // Hide signing overlay before finishing
        hideSigningOverlay()

        finish()
    }
}