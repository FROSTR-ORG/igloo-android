package com.frostr.igloo.bridges

import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.frostr.igloo.bridges.interfaces.IWebSocketBridge
import java.util.UUID

/**
 * WebSocket Bridge - Secure replacement for native WebSocket API
 *
 * This bridge provides a secure WebSocket implementation using OkHttp
 * while maintaining 100% API compatibility with the native WebSocket API.
 * The PWA remains completely unaware of this implementation.
 *
 * Extends BridgeBase for common functionality.
 */
class WebSocketBridge(webView: WebView) : BridgeBase(webView), IWebSocketBridge {

    companion object {
        private const val CONNECTION_TIMEOUT = 10L // seconds
        private const val PING_INTERVAL = 30L // seconds
    }

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
     * @return Connection ID string (UUID)
     */
    @JavascriptInterface
    override fun createWebSocket(url: String, protocols: String): String {
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

            logInfo("WebSocket connection created: $url (ID: $connectionId)")
            // Return raw connectionId string for polyfill
            return connectionId

        } catch (e: Exception) {
            logError("Failed to create WebSocket connection", e)
            throw RuntimeException("Failed to create WebSocket: ${e.message}", e)
        }
    }

    /**
     * Sends a message through the WebSocket connection
     * Polyfill-compatible alias
     */
    @JavascriptInterface
    override fun send(connectionId: String, message: String) {
        val connection = connections[connectionId]
        if (connection == null) {
            logWarn("Cannot send - connection not found: $connectionId")
            return
        }

        try {
            when (connection.state) {
                WebSocketState.OPEN -> {
                    connection.webSocket.send(message)
                    logDebug("Message sent on connection: $connectionId")
                }
                WebSocketState.CONNECTING -> {
                    // Queue message for when connection opens
                    connection.queuedMessages.add(message)
                    logDebug("Message queued for connection: $connectionId")
                }
                else -> {
                    logWarn("Cannot send - connection not open: $connectionId (state: ${connection.state})")
                }
            }
        } catch (e: Exception) {
            logError("Error sending message on connection: $connectionId", e)
        }
    }

    /**
     * Closes a WebSocket connection
     * Polyfill-compatible alias
     */
    @JavascriptInterface
    override fun close(connectionId: String, code: Int, reason: String) {
        logDebug("Closing WebSocket connection: $connectionId (code: $code, reason: $reason)")

        val connection = connections[connectionId]
        if (connection == null) {
            logWarn("Cannot close - connection not found: $connectionId")
            return
        }

        try {
            connection.state = WebSocketState.CLOSING
            connection.webSocket.close(code, reason)
            logDebug("WebSocket close initiated for connection: $connectionId")
        } catch (e: Exception) {
            logError("Error closing WebSocket connection: $connectionId", e)
        }
    }

    /**
     * Gets the current state of a WebSocket connection
     * @param connectionId Connection ID
     * @return Connection state information
     */
    @JavascriptInterface
    override fun getConnectionState(connectionId: String): String {
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
     * Notify JavaScript of WebSocket events via polyfill callback
     */
    private fun notifyJavaScript(event: String, connectionId: String, data: String? = null) {
        val eventData = toJson(WebSocketEvent(
            type = event,
            connectionId = connectionId,
            data = data,
            timestamp = System.currentTimeMillis()
        ))

        // Use BridgeBase event notification (handles escaping and thread safety)
        notifyEvent("__handleWebSocketEvent", eventData)
    }

    /**
     * WebSocket listener implementation
     */
    private inner class WebSocketListenerImpl(
        private val connectionId: String,
        private val url: String
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            logDebug("WebSocket opened: $connectionId")

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
            logDebug("WebSocket message received: $connectionId")
            notifyJavaScript("message", connectionId, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            logDebug("WebSocket closing: $connectionId (code: $code, reason: $reason)")

            val connection = connections[connectionId]
            if (connection != null) {
                connection.state = WebSocketState.CLOSING
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            logDebug("WebSocket closed: $connectionId (code: $code, reason: $reason)")

            connections[connectionId]?.let { connection ->
                connection.state = WebSocketState.CLOSED
            }

            val closeData = toJson(WebSocketCloseEvent(
                code = code,
                reason = reason,
                wasClean = code == 1000
            ))

            notifyJavaScript("close", connectionId, closeData)

            // Clean up connection
            connections.remove(connectionId)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            logError("WebSocket failure: $connectionId", t)

            connections[connectionId]?.let { connection ->
                connection.state = WebSocketState.CLOSED
            }

            val errorData = toJson(WebSocketErrorEvent(
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
    override fun cleanup() {
        logDebug("Cleaning up WebSocket connections")

        connections.values.forEach { connection ->
            try {
                connection.webSocket.close(1001, "Application closing")
            } catch (e: Exception) {
                logWarn("Error closing connection during cleanup")
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