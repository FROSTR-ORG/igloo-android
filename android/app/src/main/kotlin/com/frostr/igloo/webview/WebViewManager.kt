package com.frostr.igloo.webview

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import com.frostr.igloo.bridges.BridgeFactory
import com.frostr.igloo.bridges.BridgeSet
import com.frostr.igloo.bridges.IglooWebViewClient
import com.frostr.igloo.NIP55ContentProvider

/**
 * Manages WebView creation, configuration, and bridge registration.
 *
 * Extracted from MainActivity to:
 * 1. Reduce MainActivity size
 * 2. Share code with IglooForegroundService
 * 3. Enable unit testing of WebView setup
 *
 * Usage:
 * ```kotlin
 * val factory = BridgeFactory(context)
 * val manager = WebViewManager(context, factory, BridgeFactory.BridgeContext.MAIN_ACTIVITY, activity)
 * manager.setReadyListener(this)
 * val webView = manager.initialize()
 * manager.loadPWA()
 * ```
 */
class WebViewManager(
    private val context: Context,
    private val bridgeFactory: BridgeFactory,
    private val bridgeContext: BridgeFactory.BridgeContext,
    private val activity: Activity? = null
) {
    companion object {
        private const val TAG = "WebViewManager"
        private const val CUSTOM_SCHEME = "igloo"
        private const val PWA_HOST = "app"
        private const val SECURE_PWA_URL = "$CUSTOM_SCHEME://$PWA_HOST/index.html"
    }

    private var webView: WebView? = null
    private var bridges: BridgeSet? = null
    private var isReady = false

    /**
     * Listener for WebView lifecycle events.
     */
    interface WebViewReadyListener {
        /** Called when the PWA has fully loaded */
        fun onWebViewReady()

        /** Called when loading progress changes */
        fun onWebViewLoadProgress(progress: Int)

        /** Called for console messages from the PWA */
        fun onConsoleMessage(level: String, message: String, source: String, line: Int)
    }

    private var listener: WebViewReadyListener? = null

    /**
     * Set the ready listener for lifecycle callbacks.
     */
    fun setReadyListener(listener: WebViewReadyListener?) {
        this.listener = listener
    }

    /**
     * Create and configure the WebView with all bridges registered.
     *
     * @return The configured WebView instance
     */
    fun initialize(): WebView {
        Log.d(TAG, "Initializing WebView for ${bridgeContext.name}")

        val wv = WebView(context).apply {
            configureSettings(settings)
            webChromeClient = createWebChromeClient()
            webViewClient = IglooWebViewClient(context)
        }

        webView = wv
        configureRendererPriority(wv)
        registerBridges(wv)

        Log.d(TAG, "WebView initialized successfully for ${bridgeContext.name}")
        return wv
    }

    /**
     * Configure WebView settings.
     */
    private fun configureSettings(settings: WebSettings) {
        settings.apply {
            // Enable required features
            javaScriptEnabled = true
            domStorageEnabled = false  // Use StorageBridge for secure storage

            // Security settings
            allowFileAccess = true
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = false  // WebViewClient handles network blocking

            // Disable built-in storage (use secure bridges)
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE

            // Render priority based on context
            if (bridgeContext == BridgeFactory.BridgeContext.FOREGROUND_SERVICE) {
                @Suppress("DEPRECATION")
                setRenderPriority(WebSettings.RenderPriority.LOW)
            }
        }

        Log.d(TAG, "WebView settings configured")
    }

    /**
     * Configure WebView renderer priority for background support.
     */
    private fun configureRendererPriority(webView: WebView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val priority = when (bridgeContext) {
                BridgeFactory.BridgeContext.MAIN_ACTIVITY -> {
                    // Keep renderer alive for NIP-55 signing
                    WebView.RENDERER_PRIORITY_IMPORTANT
                }
                BridgeFactory.BridgeContext.FOREGROUND_SERVICE -> {
                    // Lower priority to reduce resource usage
                    WebView.RENDERER_PRIORITY_BOUND
                }
            }
            webView.setRendererPriorityPolicy(priority, false)
            Log.d(TAG, "WebView renderer priority set to $priority")
        }
    }

    /**
     * Create WebChromeClient for console logging and progress tracking.
     */
    private fun createWebChromeClient(): WebChromeClient {
        val logPrefix = when (bridgeContext) {
            BridgeFactory.BridgeContext.MAIN_ACTIVITY -> "SecurePWA"
            BridgeFactory.BridgeContext.FOREGROUND_SERVICE -> "ServicePWA"
        }

        return object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val level = consoleMessage.messageLevel().name
                val message = consoleMessage.message()
                val source = consoleMessage.sourceId()
                val line = consoleMessage.lineNumber()

                // Notify listener
                listener?.onConsoleMessage(level, message, source, line)

                // Log to Android logcat
                val logMessage = "[$logPrefix-$level] $message ($source:$line)"
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
                listener?.onWebViewLoadProgress(newProgress)

                if (newProgress == 100 && !isReady) {
                    isReady = true
                    Log.d(TAG, "PWA fully loaded")
                    listener?.onWebViewReady()
                }
            }
        }
    }

    /**
     * Register all bridges with the WebView.
     */
    private fun registerBridges(webView: WebView) {
        Log.d(TAG, "Registering bridges for ${bridgeContext.name}")

        bridges = bridgeFactory.createBridges(webView, bridgeContext, activity)

        bridges?.let { b ->
            // Storage bridge
            webView.addJavascriptInterface(b.storageBridge, "StorageBridge")

            // WebSocket bridge
            webView.addJavascriptInterface(b.webSocketBridge, "WebSocketBridge")

            // QR Scanner bridge (MainActivity only)
            b.qrScannerBridge?.let {
                webView.addJavascriptInterface(it, "QRScannerBridge")
            }

            // Unified Signing bridge
            webView.addJavascriptInterface(b.signingBridge, "UnifiedSigningBridge")

            // NIP-55 Result Bridge (shared instance for ContentProvider)
            val nip55ResultBridge = NIP55ContentProvider.getSharedResultBridge()
            webView.addJavascriptInterface(nip55ResultBridge, "Android_NIP55ResultBridge")

            // Restore session storage from backup if available
            if (b.storageBridge.restoreSessionStorage()) {
                Log.d(TAG, "Session storage restored from backup")
            }
        }

        Log.d(TAG, "Bridges registered successfully")
    }

    /**
     * Load the PWA from bundled assets.
     */
    fun loadPWA() {
        Log.d(TAG, "Loading PWA from: $SECURE_PWA_URL")
        webView?.loadUrl(SECURE_PWA_URL)
    }

    /**
     * Get the WebView instance.
     */
    fun getWebView(): WebView? = webView

    /**
     * Get the BridgeSet.
     */
    fun getBridges(): BridgeSet? = bridges

    /**
     * Check if the PWA is fully loaded and ready.
     */
    fun isReady(): Boolean = isReady

    /**
     * Clean up all resources.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up WebViewManager")

        bridges?.cleanup()
        bridges = null

        webView?.let { wv ->
            wv.stopLoading()
            wv.destroy()
        }
        webView = null

        isReady = false
        listener = null
    }
}
