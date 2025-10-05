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
     * Get Storage polyfill code - Clean rewrite with direct API
     */
    private fun getStoragePolyfill(): String {
        return """
            // Storage polyfill - Direct Android SecureStorageBridge integration
            (function() {
                console.log('[Polyfill] Initializing Storage API...');

                // Delete existing storage objects for clean replacement
                try {
                    delete window.localStorage;
                    delete window.sessionStorage;
                } catch (e) {
                    // Expected - properties may not be configurable
                }

                // Create localStorage implementation
                var localStorageImpl = {
                    getItem: function(key) {
                        try {
                            // Bridge returns raw string value or null
                            var value = window.StorageBridge.getItem('local', key);
                            return value; // Returns string or null directly
                        } catch (e) {
                            console.error('[Polyfill] localStorage.getItem error:', e);
                            return null;
                        }
                    },
                    setItem: function(key, value) {
                        try {
                            // Bridge expects (storageType, key, value)
                            window.StorageBridge.setItem('local', key, String(value));
                        } catch (e) {
                            console.error('[Polyfill] localStorage.setItem error:', e);
                            throw e;
                        }
                    },
                    removeItem: function(key) {
                        try {
                            window.StorageBridge.removeItem('local', key);
                        } catch (e) {
                            console.error('[Polyfill] localStorage.removeItem error:', e);
                        }
                    },
                    clear: function() {
                        try {
                            window.StorageBridge.clear('local');
                        } catch (e) {
                            console.error('[Polyfill] localStorage.clear error:', e);
                        }
                    },
                    key: function(index) {
                        try {
                            return window.StorageBridge.key('local', index);
                        } catch (e) {
                            console.error('[Polyfill] localStorage.key error:', e);
                            return null;
                        }
                    },
                    get length() {
                        try {
                            return window.StorageBridge.length('local') || 0;
                        } catch (e) {
                            console.error('[Polyfill] localStorage.length error:', e);
                            return 0;
                        }
                    }
                };

                // Create sessionStorage implementation
                var sessionStorageImpl = {
                    getItem: function(key) {
                        try {
                            var value = window.StorageBridge.getItem('session', key);
                            return value; // Returns string or null directly
                        } catch (e) {
                            console.error('[Polyfill] sessionStorage.getItem error:', e);
                            return null;
                        }
                    },
                    setItem: function(key, value) {
                        try {
                            window.StorageBridge.setItem('session', key, String(value));
                        } catch (e) {
                            console.error('[Polyfill] sessionStorage.setItem error:', e);
                            throw e;
                        }
                    },
                    removeItem: function(key) {
                        try {
                            window.StorageBridge.removeItem('session', key);
                        } catch (e) {
                            console.error('[Polyfill] sessionStorage.removeItem error:', e);
                        }
                    },
                    clear: function() {
                        try {
                            window.StorageBridge.clear('session');
                        } catch (e) {
                            console.error('[Polyfill] sessionStorage.clear error:', e);
                        }
                    },
                    key: function(index) {
                        try {
                            return window.StorageBridge.key('session', index);
                        } catch (e) {
                            console.error('[Polyfill] sessionStorage.key error:', e);
                            return null;
                        }
                    },
                    get length() {
                        try {
                            return window.StorageBridge.length('session') || 0;
                        } catch (e) {
                            console.error('[Polyfill] sessionStorage.length error:', e);
                            return 0;
                        }
                    }
                };

                // Install as non-configurable properties
                Object.defineProperty(window, 'localStorage', {
                    value: localStorageImpl,
                    writable: false,
                    configurable: false
                });

                Object.defineProperty(window, 'sessionStorage', {
                    value: sessionStorageImpl,
                    writable: false,
                    configurable: false
                });

                console.log('[Polyfill] Storage API initialized successfully');
            })();
        """.trimIndent()
    }

    /**
     * Get WebSocket polyfill code - Clean rewrite with direct API
     */
    private fun getWebSocketPolyfill(): String {
        return """
            // WebSocket polyfill - Direct Android WebSocketBridge integration
            (function() {
                console.log('[Polyfill] Initializing WebSocket API...');

                // Store connection callbacks globally
                window.__wsCallbacks = window.__wsCallbacks || {};

                // Handle events from Android bridge
                window.__handleWebSocketEvent = function(eventJson) {
                    try {
                        var event = JSON.parse(eventJson);
                        var callback = window.__wsCallbacks[event.connectionId];

                        if (!callback) {
                            console.warn('[Polyfill] No callback for connection:', event.connectionId);
                            return;
                        }

                        callback(event);
                    } catch (e) {
                        console.error('[Polyfill] WebSocket event handler error:', e);
                    }
                };

                // WebSocket polyfill constructor
                window.WebSocket = function(url, protocols) {
                    var self = this;

                    // WebSocket state constants
                    this.CONNECTING = 0;
                    this.OPEN = 1;
                    this.CLOSING = 2;
                    this.CLOSED = 3;

                    // WebSocket properties
                    this.url = url;
                    this.readyState = this.CONNECTING;
                    this.protocol = '';
                    this.extensions = '';
                    this.bufferedAmount = 0;

                    // Event handlers
                    this.onopen = null;
                    this.onmessage = null;
                    this.onerror = null;
                    this.onclose = null;

                    // Create connection via Android bridge
                    try {
                        var protocolsStr = Array.isArray(protocols) ? protocols.join(',') : (protocols || '');
                        var connectionId = window.WebSocketBridge.createWebSocket(url, protocolsStr);
                        this._connectionId = connectionId;

                        // Register callback for this connection
                        window.__wsCallbacks[connectionId] = function(event) {
                            if (event.type === 'open') {
                                self.readyState = self.OPEN;
                                self.protocol = event.protocol || '';
                                if (self.onopen) {
                                    self.onopen(new Event('open'));
                                }
                            } else if (event.type === 'message') {
                                if (self.onmessage) {
                                    var msgEvent = new MessageEvent('message', { data: event.data });
                                    self.onmessage(msgEvent);
                                }
                            } else if (event.type === 'error') {
                                self.readyState = self.CLOSED;
                                if (self.onerror) {
                                    var errorEvent = new Event('error');
                                    errorEvent.message = event.message || 'WebSocket error';
                                    self.onerror(errorEvent);
                                }
                            } else if (event.type === 'close') {
                                self.readyState = self.CLOSED;
                                if (self.onclose) {
                                    var closeEvent = new CloseEvent('close', {
                                        code: event.code || 1000,
                                        reason: event.reason || '',
                                        wasClean: event.wasClean !== false
                                    });
                                    self.onclose(closeEvent);
                                }
                                // Clean up callback
                                delete window.__wsCallbacks[connectionId];
                            }
                        };

                        console.log('[Polyfill] WebSocket created:', url, 'ID:', connectionId);
                    } catch (e) {
                        console.error('[Polyfill] Failed to create WebSocket:', e);
                        this.readyState = this.CLOSED;
                        throw e;
                    }
                };

                // WebSocket.prototype.send()
                window.WebSocket.prototype.send = function(data) {
                    if (this.readyState !== this.OPEN) {
                        throw new DOMException('WebSocket is not open: readyState ' + this.readyState + ' (OPEN=' + this.OPEN + ')', 'InvalidStateError');
                    }

                    try {
                        window.WebSocketBridge.send(this._connectionId, String(data));
                    } catch (e) {
                        console.error('[Polyfill] WebSocket send error:', e);
                        throw e;
                    }
                };

                // WebSocket.prototype.close()
                window.WebSocket.prototype.close = function(code, reason) {
                    if (this.readyState === this.CLOSING || this.readyState === this.CLOSED) {
                        return;
                    }

                    this.readyState = this.CLOSING;

                    try {
                        window.WebSocketBridge.close(this._connectionId, code || 1000, reason || '');
                    } catch (e) {
                        console.error('[Polyfill] WebSocket close error:', e);
                        this.readyState = this.CLOSED;
                    }
                };

                console.log('[Polyfill] WebSocket API initialized successfully');
            })();
        """.trimIndent()
    }

    /**
     * Get Camera polyfill code
     */
    private fun getCameraPolyfill(): String {
        return """
            // Camera polyfill - Direct Android CameraBridge integration
            (function() {
                console.log('[Polyfill] Initializing Camera API...');

                // Create mediaDevices polyfill
                if (!navigator.mediaDevices) {
                    navigator.mediaDevices = {};
                }

                navigator.mediaDevices.getUserMedia = function(constraints) {
                    return new Promise(function(resolve, reject) {
                        try {
                            console.log('[Polyfill] getUserMedia called with constraints:', constraints);

                            var constraintsJson = JSON.stringify(constraints || { video: true });
                            var resultJson = window.CameraBridge.getUserMedia(constraintsJson);
                            var result = JSON.parse(resultJson);

                            console.log('[Polyfill] getUserMedia result:', result);

                            // Bridge returns: {"data": streamInfo, "message": errorMessage}
                            if (result.data && result.data.streamId) {
                                // Create a mock MediaStream object
                                var stream = {
                                    id: result.data.streamId,
                                    active: result.data.active || true,
                                    getTracks: function() {
                                        return (result.data.videoTracks || []).concat(result.data.audioTracks || []);
                                    },
                                    getVideoTracks: function() {
                                        return result.data.videoTracks || [];
                                    },
                                    getAudioTracks: function() {
                                        return result.data.audioTracks || [];
                                    },
                                    addTrack: function() {},
                                    removeTrack: function() {},
                                    _streamId: result.data.streamId
                                };
                                console.log('[Polyfill] Camera stream created:', stream.id);
                                resolve(stream);
                            } else {
                                var errorMsg = result.message || 'Camera access denied';
                                console.error('[Polyfill] Camera access failed:', errorMsg);
                                reject(new Error(errorMsg));
                            }
                        } catch (e) {
                            console.error('[Polyfill] getUserMedia error:', e);
                            reject(e);
                        }
                    });
                };

                navigator.mediaDevices.enumerateDevices = function() {
                    return new Promise(function(resolve, reject) {
                        try {
                            var devicesJson = window.CameraBridge.enumerateDevices();
                            var devices = JSON.parse(devicesJson);

                            console.log('[Polyfill] Enumerated devices:', devices);

                            // Bridge returns raw array of devices
                            if (Array.isArray(devices)) {
                                resolve(devices);
                            } else {
                                console.error('[Polyfill] Invalid devices format:', devices);
                                reject(new Error('Invalid device enumeration response'));
                            }
                        } catch (e) {
                            console.error('[Polyfill] enumerateDevices error:', e);
                            reject(e);
                        }
                    });
                };

                console.log('[Polyfill] Camera API initialized');
            })();
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