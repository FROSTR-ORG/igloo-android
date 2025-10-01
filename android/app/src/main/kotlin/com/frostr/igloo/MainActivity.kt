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
        private const val CUSTOM_SCHEME = "igloopwa"
        private const val PWA_HOST = "app"
        private const val SECURE_PWA_URL = "$CUSTOM_SCHEME://$PWA_HOST/index.html"
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

        if (isBackgroundServiceRequest) {
            Log.d(TAG, "Background service request - continuing setup in background")
        }

        when (intent.action) {
            "com.frostr.igloo.NIP55_SIGNING" -> {
                NIP55DebugLogger.logIntent("MAIN_RECEIVED", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString(),
                    "backgroundServiceRequest" to isBackgroundServiceRequest
                ))
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

        val request = try {
            gson.fromJson(requestJson, NIP55Request::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse NIP-55 request JSON", e)
            NIP55DebugLogger.logError("PARSE_REQUEST", e)
            sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                putExtra("error", "Invalid request format: ${e.message}")
            })
            return
        }

        processNIP55Request(request, callingApp, replyPendingIntent)
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
        val params = mapOf(
            "pubkey" to (intent.getStringExtra("pubkey") ?: ""),
            "plaintext" to (intent.getStringExtra("plaintext") ?: ""),
            "ciphertext" to (intent.getStringExtra("ciphertext") ?: ""),
            "current_user" to (intent.getStringExtra("current_user") ?: "")
        ).filterValues { it.isNotEmpty() }

        val eventData = intent.getStringExtra("event")?.let { eventJson ->
            try {
                gson.fromJson(eventJson, Map::class.java) as? Map<String, Any>
            } catch (e: Exception) {
                null
            }
        }

        val request = NIP55Request(
            id = requestId,
            type = operationType,
            params = params,
            callingApp = callingApp,
            timestamp = System.currentTimeMillis(),
            event = eventData
        )

        processNIP55Request(request, callingApp, replyPendingIntent)
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
                            putExtra("result_data", result.result)
                            putExtra("result_code", RESULT_OK)
                        })
                    } else {
                        Log.d(TAG, "Setting RESULT_CANCELED for NIP-55 request: ${request.id} - ${result.reason}")
                        sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
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

        webView = WebView(this)
        setContentView(webView)

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
        webView.addJavascriptInterface(storageBridge, "SecureStorageBridge")
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
     * Load the secure PWA from assets or development server
     */
    private fun loadSecurePWA() {
        // For development, load from localhost:3000
        val developmentUrl = "http://localhost:3000"
        Log.d(TAG, "Loading PWA from development server: $developmentUrl")

        try {
            webView.loadUrl(developmentUrl)
            Log.d(TAG, "Development PWA load initiated")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load development PWA", e)
            // Fallback to secure PWA
            try {
                Log.d(TAG, "Falling back to secure PWA: $SECURE_PWA_URL")
                webView.loadUrl(SECURE_PWA_URL)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Failed to load fallback PWA", fallbackError)
                loadErrorPage("Failed to load PWA: ${e.message}")
            }
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
            "com.frostr.igloo.NIP55_SIGNING" -> {
                NIP55DebugLogger.logIntent("MAIN_RECEIVED_NEW", intent, mapOf(
                    "process" to "main",
                    "launchMode" to intent.flags.toString(),
                    "backgroundServiceRequest" to isBackgroundServiceRequest
                ))
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
     * Send reply via direct broadcast to InvisibleNIP55Handler
     */
    private fun sendReply(replyPendingIntent: PendingIntent?, resultCode: Int, resultData: Intent) {
        // Get the broadcast action from the intent that started this NIP-55 handling
        val broadcastAction = intent?.getStringExtra("reply_broadcast_action")
            ?: throw IllegalStateException("No broadcast action provided - reply_broadcast_action missing from intent")

        Log.d(TAG, "Sending reply via direct broadcast: $broadcastAction with result code: $resultCode")

        // Create broadcast intent with the correct action and data
        val broadcastIntent = Intent(broadcastAction).apply {
            // Copy all extras from resultData to the broadcast intent
            putExtras(resultData)
            // Add the result code as an extra for the broadcast receiver
            putExtra("result_code", resultCode)
            // Set package to restrict to our app for security
            setPackage(packageName)
        }

        // Send broadcast directly with the data included
        sendBroadcast(broadcastIntent)

        Log.d(TAG, "Direct broadcast sent successfully")

        // If this is a background service request, also notify the background signing service
        val isBackgroundServiceRequest = intent?.getBooleanExtra("background_service_request", false) ?: false
        if (isBackgroundServiceRequest) {
            Log.d(TAG, "Notifying background signing service of completion")

            val serviceCompletionIntent = Intent(Nip55BackgroundSigningService.ACTION_SIGNING_COMPLETE).apply {
                putExtra("success", resultCode == RESULT_OK)
                putExtra("request_id", resultData.getStringExtra("id") ?: "unknown")
                if (resultCode == RESULT_OK) {
                    putExtra("result_data", resultData.getStringExtra("result_data"))
                } else {
                    putExtra("error", resultData.getStringExtra("error"))
                }
                setPackage(packageName)
            }

            sendBroadcast(serviceCompletionIntent)
            Log.d(TAG, "Background signing service completion notification sent")
        }
    }
}