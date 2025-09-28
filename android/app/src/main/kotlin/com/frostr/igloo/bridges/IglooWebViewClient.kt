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
        private const val CUSTOM_SCHEME = "igloopwa"
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
     * Create asset content (placeholder implementation)
     * In production, this would read from actual PWA dist files
     */
    private fun createAssetContent(path: String): InputStream {
        return when (path) {
            "test-polyfills.html" -> {
                // For now, return a simple placeholder
                // TODO: Load actual test file from assets when WebView context is available
                ByteArrayInputStream("Test polyfills page placeholder".toByteArray(Charsets.UTF_8))
            }
            "test-camera.html" -> {
                // For now, return a simple placeholder
                // TODO: Load actual camera test file from assets when WebView context is available
                ByteArrayInputStream("Camera test page placeholder".toByteArray(Charsets.UTF_8))
            }
            "index.html" -> {
                ByteArrayInputStream(createSecureIndexHtml().toByteArray(Charsets.UTF_8))
            }
            "app.js" -> {
                ByteArrayInputStream("// PWA application JavaScript would be loaded here".toByteArray(Charsets.UTF_8))
            }
            "sw.js" -> {
                ByteArrayInputStream("// Service Worker JavaScript would be loaded here".toByteArray(Charsets.UTF_8))
            }
            "manifest.json" -> {
                val manifest = """{"name":"Igloo PWA","short_name":"Igloo","start_url":"/","display":"standalone"}"""
                ByteArrayInputStream(manifest.toByteArray(Charsets.UTF_8))
            }
            else -> {
                ByteArrayInputStream("// Asset: $path".toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * Create secure index.html with polyfill injection
     */
    private fun createSecureIndexHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Igloo PWA</title>
                <link rel="manifest" href="manifest.json">
            </head>
            <body>
                <div id="root">Loading...</div>

                <!-- Polyfill injection will happen via onPageStarted -->
                <script>
                    console.log('Secure PWA loading...');
                    // Main PWA script will be injected after polyfills
                </script>
            </body>
            </html>
        """.trimIndent()
    }

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
     * Inject polyfills when page starts loading
     */
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        Log.d(TAG, "Page started: $url")

        if (url.startsWith("$CUSTOM_SCHEME://") || url.startsWith("http://localhost:")) {
            Log.d(TAG, "Injecting polyfills for: $url")
            injectPolyfills(view)
        }

        super.onPageStarted(view, url, favicon)
    }

    /**
     * Inject polyfills before PWA JavaScript executes
     */
    private fun injectPolyfills(webView: WebView) {
        try {
            // Inject WebSocket polyfill
            val webSocketPolyfill = loadPolyfillScript("websocket-polyfill.js")
            webView.evaluateJavascript(webSocketPolyfill) { result ->
                Log.d(TAG, "WebSocket polyfill injected: $result")
            }

            // Inject Storage polyfill
            val storagePolyfill = loadPolyfillScript("storage-polyfill.js")
            webView.evaluateJavascript(storagePolyfill) { result ->
                Log.d(TAG, "Storage polyfill injected: $result")
            }

            // Inject Camera polyfill
            val cameraPolyfill = loadPolyfillScript("camera-polyfill.js")
            webView.evaluateJavascript(cameraPolyfill) { result ->
                Log.d(TAG, "Camera polyfill injected: $result")
            }

            // Note: No signing polyfill needed - PWA provides signing to Android, not vice versa

            Log.d(TAG, "All polyfills injected successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject polyfills", e)
        }
    }

    /**
     * Load polyfill script from assets
     */
    private fun loadPolyfillScript(filename: String): String {
        return try {
            // Read the actual polyfill from assets
            val assetPath = "polyfills/$filename"

            val inputStream = context.assets.open(assetPath)
            val polyfillCode = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()

            Log.d(TAG, "Loaded polyfill from assets: $filename (${polyfillCode.length} bytes)")
            polyfillCode

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load polyfill script from assets: $filename", e)
            "console.error('Failed to load polyfill: $filename - ${e.message}');"
        }
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