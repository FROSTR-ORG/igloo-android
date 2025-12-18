package com.frostr.igloo.bridges

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Igloo WebView Client with Polyfill Injection
 *
 * This client intercepts page loading to inject polyfills before PWA JavaScript executes,
 * implements custom protocol handling for security, and manages asset serving.
 */
class IglooWebViewClient(private val context: Context) : WebViewClient() {

    companion object {
        private const val TAG = "IglooWebViewClient"
        private const val CUSTOM_SCHEME = "igloo"
        private const val PWA_HOST = "app"
    }

    private var polyfillsInjected = false

    /**
     * Intercept resource requests to implement custom protocol and security
     */
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        Log.d(TAG, "Resource request: $url")

        return when {
            // Handle custom igloopwa:// protocol
            url.startsWith("$CUSTOM_SCHEME://") -> {
                handleCustomProtocol(url, request)
            }

            // Allow localhost requests for testing
            url.startsWith("http://localhost:") -> {
                Log.d(TAG, "Allowing localhost request: $url")
                super.shouldInterceptRequest(view, request)
            }

            // Block external HTTP/HTTPS requests for security
            url.startsWith("http://") -> {
                Log.w(TAG, "Blocking external HTTP request: $url")
                createBlockedResponse()
            }

            url.startsWith("https://") -> {
                Log.w(TAG, "Blocking external HTTPS request: $url")
                createBlockedResponse()
            }

            // Allow other requests (file://, data:, etc.)
            else -> {
                super.shouldInterceptRequest(view, request)
            }
        }
    }

    /**
     * Handle custom protocol requests (igloopwa://app/...)
     */
    private fun handleCustomProtocol(
        url: String,
        request: WebResourceRequest
    ): WebResourceResponse? {
        try {
            val uri = Uri.parse(url)

            if (uri.scheme != CUSTOM_SCHEME || uri.host != PWA_HOST) {
                Log.w(TAG, "Invalid custom protocol URL: $url")
                return createErrorResponse("Invalid protocol")
            }

            val path = uri.path?.removePrefix("/") ?: "index.html"
            Log.d(TAG, "Serving asset: $path")

            return serveAsset(path)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling custom protocol: $url", e)
            return createErrorResponse("Protocol error: ${e.message}")
        }
    }

    /**
     * Serve assets from the PWA bundle
     */
    private fun serveAsset(path: String): WebResourceResponse? {
        try {
            // Determine asset path - map to actual PWA assets
            val assetPath = when {
                path == "" || path == "index.html" -> "index.html"
                path == "test" || path == "test.html" -> "test-polyfills.html"
                path == "camera" || path == "camera.html" -> "test-camera.html"
                path.startsWith("app.js") -> "app.js"
                path.startsWith("sw.js") -> "sw.js"
                path.startsWith("styles/") -> path
                path.startsWith("manifest.json") -> "manifest.json"
                else -> path
            }

            Log.d(TAG, "Loading asset: $assetPath")

            // Load asset from PWA dist directory
            // Note: In real implementation, we'd serve from the actual PWA dist folder
            // For now, we'll create a simple response
            val mimeType = getMimeType(assetPath)
            val content = createAssetContent(assetPath)

            return WebResourceResponse(
                mimeType,
                "UTF-8",
                200,
                "OK",
                mapOf(
                    "Access-Control-Allow-Origin" to "$CUSTOM_SCHEME://$PWA_HOST",
                    "Cache-Control" to "no-cache"
                ),
                content
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error serving asset: $path", e)
            return createErrorResponse("Asset not found: $path")
        }
    }

    /**
     * Get MIME type for asset
     */
    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".ico") -> "image/x-icon"
            else -> "text/plain"
        }
    }

    /**
     * Create asset content by loading from the assets directory
     * For index.html, inject polyfills inline before app.js loads
     */
    private fun createAssetContent(path: String): InputStream {
        return try {
            // Try to load the asset from the assets directory
            Log.d(TAG, "Attempting to load asset from assets directory: $path")

            // Special handling for index.html - inject polyfills inline
            if (path == "index.html") {
                return injectPolyfillsIntoHtml()
            }

            context.assets.open(path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load asset from assets directory: $path", e)
            // Return a simple error message as fallback
            ByteArrayInputStream("Asset not found: $path".toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Load index.html and inject polyfills inline before app.js loads
     */
    private fun injectPolyfillsIntoHtml(): InputStream {
        return try {
            // Read the original index.html
            val htmlContent = context.assets.open("index.html").bufferedReader().use { it.readText() }

            Log.d(TAG, "Injecting polyfills inline into index.html")

            // Create inline polyfill script
            val polyfillScript = """
                <script>
                ${getStoragePolyfill()}
                ${getWebSocketPolyfill()}
                ${getCameraPolyfill()}
                console.log('All polyfills loaded inline before app.js');
                </script>
            """.trimIndent()

            // Inject polyfills before the closing </head> tag or before first <script> tag
            val modifiedHtml = if (htmlContent.contains("</head>")) {
                htmlContent.replace("</head>", "$polyfillScript\n  </head>")
            } else {
                htmlContent.replace("<script", "$polyfillScript\n    <script")
            }

            Log.d(TAG, "Polyfills injected inline successfully")
            ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject polyfills into HTML", e)
            // Fallback to original HTML
            context.assets.open("index.html")
        }
    }

    /**
     * Load polyfill from external JS file in assets/polyfills/
     */
    private fun loadPolyfillFromAsset(filename: String): String {
        return try {
            context.assets.open("polyfills/$filename").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load polyfill: $filename", e)
            "console.error('Failed to load polyfill: $filename');"
        }
    }

    /**
     * Get Storage polyfill code from external file
     */
    private fun getStoragePolyfill(): String = loadPolyfillFromAsset("storage.js")

    /**
     * Get WebSocket polyfill code from external file
     */
    private fun getWebSocketPolyfill(): String = loadPolyfillFromAsset("websocket.js")

    /**
     * Get Camera polyfill code from external file
     */
    private fun getCameraPolyfill(): String = loadPolyfillFromAsset("camera.js")

    /**
     * Create blocked response for security
     */
    private fun createBlockedResponse(): WebResourceResponse {
        val content = "Request blocked for security"
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            mapOf("Content-Security-Policy" to "default-src 'none'"),
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        )
    }

    /**
     * Create error response
     */
    private fun createErrorResponse(message: String): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(message.toByteArray(Charsets.UTF_8))
        )
    }

    /**
     * Page started loading - polyfills are now injected inline in HTML
     */
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        Log.d(TAG, "Page started: $url (polyfills are inline)")
        super.onPageStarted(view, url, favicon)
    }

    /**
     * Page finished loading
     */
    override fun onPageFinished(view: WebView, url: String) {
        Log.d(TAG, "Page finished: $url")

        if (url.startsWith("$CUSTOM_SCHEME://")) {
            // Notify that secure loading is complete
            view.evaluateJavascript(
                "console.log('Secure PWA loaded successfully');"
            ) { result ->
                Log.d(TAG, "Secure loading notification sent: $result")
            }
        }

        super.onPageFinished(view, url)
    }

    /**
     * Handle page loading errors
     */
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String
    ) {
        Log.e(TAG, "Page loading error: $errorCode - $description for URL: $failingUrl")
        super.onReceivedError(view, errorCode, description, failingUrl)
    }

    /**
     * Reset polyfill injection state (for testing)
     */
    fun resetPolyfillState() {
        polyfillsInjected = false
    }
}