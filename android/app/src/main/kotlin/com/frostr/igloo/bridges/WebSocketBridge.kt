package com.frostr.igloo.bridges

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * WebSocket Bridge - Secure replacement for native WebSocket API
 *
 * This bridge provides a secure WebSocket implementation using OkHttp
 * while maintaining 100% API compatibility with the native WebSocket API.
 * The PWA remains completely unaware of this implementation.
 */
class WebSocketBridge(private val webView: WebView) {

    companion object {
        private const val TAG = "WebSocketBridge"
        private const val CONNECTION_TIMEOUT = 10L // seconds
        private const val PING_INTERVAL = 30L // seconds
    }

    private val gson = Gson()
    private val connections = ConcurrentHashMap<String, WebSocketConnection>()

    // OkHttp client optimized for WebSocket connections
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No read timeout for WebSockets
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL, TimeUnit.SECONDS)
        .build()

    /**
     * Creates a new WebSocket connection
     * @param url WebSocket URL (ws:// or wss://)
     * @param protocols Optional protocols (comma-separated)
     * @return Connection ID for subsequent operations
     */
    @JavascriptInterface
    fun createWebSocket(url: String, protocols: String = ""): String {
        try {
            val connectionId = UUID.randomUUID().toString()
            val protocolList = if (protocols.isNotEmpty()) {
                protocols.split(",").map { it.trim() }
            } else {
                emptyList()
            }

            val request = Request.Builder()
                .url(url)
                .apply {
                    protocolList.forEach { protocol ->
                        addHeader("Sec-WebSocket-Protocol", protocol)
                    }
                }
                .build()

            val listener = WebSocketListenerImpl(connectionId, url)
            val webSocket = client.newWebSocket(request, listener)

            val connection = WebSocketConnection(
                id = connectionId,
                url = url,
                webSocket = webSocket,
                protocols = protocolList,
                state = WebSocketState.CONNECTING
            )

            connections[connectionId] = connection

            Log.i(TAG, "WebSocket connection created: $url")
            return gson.toJson(WebSocketResult.Success(connectionId))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection", e)
            return gson.toJson(WebSocketResult.Error("Failed to create connection: ${e.message}"))
        }
    }

    /**
     * Sends a message through the WebSocket connection
     * @param connectionId Connection ID from createWebSocket
     * @param message Message to send
     * @return Success/error result
     */
    @JavascriptInterface
    fun sendMessage(connectionId: String, message: String): String {
        val connection = connections[connectionId]
        if (connection == null) {
            return gson.toJson(WebSocketResult.Error("Connection not found"))
        }

        return try {
            when (connection.state) {
                WebSocketState.OPEN -> {
                    val success = connection.webSocket.send(message)
                    if (success) {
                        gson.toJson(WebSocketResult.Success("Message sent"))
                    } else {
                        gson.toJson(WebSocketResult.Error("Message queue full"))
                    }
                }
                WebSocketState.CONNECTING -> {
                    // Queue message for when connection opens
                    connection.queuedMessages.add(message)
                    gson.toJson(WebSocketResult.Success("Message queued"))
                }
                else -> {
                    gson.toJson(WebSocketResult.Error("Connection not open"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            gson.toJson(WebSocketResult.Error("Send failed: ${e.message}"))
        }
    }

    /**
     * Closes a WebSocket connection
     * @param connectionId Connection ID
     * @param code Close code (default 1000 = normal closure)
     * @param reason Close reason
     * @return Success/error result
     */
    @JavascriptInterface
    fun closeWebSocket(connectionId: String, code: Int = 1000, reason: String = ""): String {
        Log.d(TAG, "Closing WebSocket connection: $connectionId")

        val connection = connections[connectionId]
        if (connection == null) {
            Log.w(TAG, "Connection not found: $connectionId")
            return gson.toJson(WebSocketResult.Error("Connection not found"))
        }

        return try {
            connection.state = WebSocketState.CLOSING
            val success = connection.webSocket.close(code, reason)

            if (success) {
                Log.d(TAG, "WebSocket close initiated")
                gson.toJson(WebSocketResult.Success("Close initiated"))
            } else {
                Log.w(TAG, "Failed to close WebSocket")
                gson.toJson(WebSocketResult.Error("Close failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket", e)
            gson.toJson(WebSocketResult.Error("Close failed: ${e.message}"))
        }
    }

    /**
     * Gets the current state of a WebSocket connection
     * @param connectionId Connection ID
     * @return Connection state information
     */
    @JavascriptInterface
    fun getConnectionState(connectionId: String): String {
        val connection = connections[connectionId]
        return if (connection != null) {
            gson.toJson(WebSocketStateInfo(
                connectionId = connectionId,
                url = connection.url,
                state = connection.state.name,
                protocols = connection.protocols
            ))
        } else {
            gson.toJson(WebSocketResult.Error("Connection not found"))
        }
    }

    /**
     * Notify JavaScript of WebSocket events
     * This method is called from the WebSocket listener
     */
    private fun notifyJavaScript(event: String, connectionId: String, data: String? = null) {
        val eventData = gson.toJson(WebSocketEvent(
            type = event,
            connectionId = connectionId,
            data = data,
            timestamp = System.currentTimeMillis()
        ))

        // Properly escape JSON for JavaScript injection
        val escapedEventData = eventData
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        // Call JavaScript event handler on UI thread
        webView.post {
            webView.evaluateJavascript(
                "window.WebSocketBridge && window.WebSocketBridge.handleEvent('$escapedEventData')",
                null
            )
        }
    }

    /**
     * WebSocket listener implementation
     */
    private inner class WebSocketListenerImpl(
        private val connectionId: String,
        private val url: String
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened: $connectionId")

            val connection = connections[connectionId]
            if (connection != null) {
                connection.state = WebSocketState.OPEN

                // Send any queued messages
                connection.queuedMessages.forEach { message ->
                    webSocket.send(message)
                }
                connection.queuedMessages.clear()

                // Determine negotiated protocol
                val negotiatedProtocol = response.header("Sec-WebSocket-Protocol")

                notifyJavaScript("open", connectionId, negotiatedProtocol)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "WebSocket message received: $connectionId")
            notifyJavaScript("message", connectionId, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $connectionId (code: $code, reason: $reason)")

            val connection = connections[connectionId]
            if (connection != null) {
                connection.state = WebSocketState.CLOSING
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $connectionId (code: $code, reason: $reason)")

            connections[connectionId]?.let { connection ->
                connection.state = WebSocketState.CLOSED
            }

            val closeData = gson.toJson(WebSocketCloseEvent(
                code = code,
                reason = reason,
                wasClean = code == 1000
            ))

            notifyJavaScript("close", connectionId, closeData)

            // Clean up connection
            connections.remove(connectionId)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: $connectionId", t)

            connections[connectionId]?.let { connection ->
                connection.state = WebSocketState.CLOSED
            }

            val errorData = gson.toJson(WebSocketErrorEvent(
                message = t.message ?: "Unknown error",
                code = response?.code ?: 0
            ))

            notifyJavaScript("error", connectionId, errorData)

            // Clean up connection
            connections.remove(connectionId)
        }
    }

    /**
     * Clean up all connections (call on activity destroy)
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up WebSocket connections")

        connections.values.forEach { connection ->
            try {
                connection.webSocket.close(1001, "Application closing")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing connection during cleanup", e)
            }
        }

        connections.clear()
        client.dispatcher.executorService.shutdown()
    }
}

/**
 * Data classes for WebSocket bridge communication
 */

data class WebSocketConnection(
    val id: String,
    val url: String,
    val webSocket: WebSocket,
    val protocols: List<String>,
    var state: WebSocketState,
    val queuedMessages: MutableList<String> = mutableListOf()
)

enum class WebSocketState {
    CONNECTING, OPEN, CLOSING, CLOSED
}

sealed class WebSocketResult {
    data class Success(val data: String) : WebSocketResult()
    data class Error(val message: String) : WebSocketResult()
}

data class WebSocketStateInfo(
    val connectionId: String,
    val url: String,
    val state: String,
    val protocols: List<String>
)

data class WebSocketEvent(
    val type: String,
    val connectionId: String,
    val data: String?,
    val timestamp: Long
)

data class WebSocketCloseEvent(
    val code: Int,
    val reason: String,
    val wasClean: Boolean
)

data class WebSocketErrorEvent(
    val message: String,
    val code: Int
)