package com.frostr.igloo.bridges.interfaces

/**
 * Interface for WebSocket bridge operations.
 *
 * Enables testability by allowing mock implementations of the WebSocket layer.
 * Implementations provide a secure WebSocket implementation using OkHttp
 * that replaces the native WebSocket API.
 */
interface IWebSocketBridge {

    /**
     * Creates a new WebSocket connection.
     * @param url WebSocket URL (ws:// or wss://)
     * @param protocols Optional protocols (comma-separated)
     * @return Connection ID string (UUID)
     */
    fun createWebSocket(url: String, protocols: String = ""): String

    /**
     * Sends a message through the WebSocket connection.
     * @param connectionId Connection ID returned from createWebSocket
     * @param message Message to send
     */
    fun send(connectionId: String, message: String)

    /**
     * Closes a WebSocket connection.
     * @param connectionId Connection ID
     * @param code Close code (default 1000)
     * @param reason Close reason
     */
    fun close(connectionId: String, code: Int = 1000, reason: String = "")

    /**
     * Gets the current state of a WebSocket connection.
     * @param connectionId Connection ID
     * @return Connection state information as JSON
     */
    fun getConnectionState(connectionId: String): String

    /**
     * Clean up all connections.
     * Should be called when the bridge is being destroyed.
     */
    fun cleanup()
}
