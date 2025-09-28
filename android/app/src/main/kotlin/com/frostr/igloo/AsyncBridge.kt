package com.frostr.igloo

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * NIP-55 result data structure
 */
data class NIP55Result(
    val ok: Boolean,
    val type: String,
    val id: String,
    val result: String?,
    val reason: String?
)

/**
 * Modern Async Bridge for NIP-55 Communication
 *
 * Uses androidx.webkit.WebMessageListener for secure, efficient communication
 * between Android and PWA layers. Replaces legacy polling-based approach.
 */
class AsyncBridge(private val webView: WebView) {

    companion object {
        private const val TAG = "AsyncBridge"
        private const val BRIDGE_NAME = "androidBridge"
        private const val DEFAULT_TIMEOUT_MS = 30000L // 30 seconds
    }

    // Thread-safe map to track pending requests
    private val continuations: MutableMap<String, CancellableContinuation<NIP55Result>> = ConcurrentHashMap()

    /**
     * Initialize the async bridge with WebMessageListener
     */
    fun initialize() {
        Log.d(TAG, "Initializing AsyncBridge with WebMessageListener")

        try {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                setOf("*"), // For localhost/file:// - restrict in production
                this::handleWebMessage
            )
            Log.d(TAG, "AsyncBridge initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AsyncBridge", e)
            throw e
        }
    }

    /**
     * Call NIP-55 async method and await result
     */
    suspend fun callNip55Async(type: String, id: String, host: String, params: Map<String, Any>? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS): NIP55Result {
        Log.d(TAG, "Calling NIP-55 async: $type ($id)")

        return withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val requestId = UUID.randomUUID().toString()
                continuations[requestId] = cont

                // Build the JavaScript call
                val requestJson = buildRequestJson(id, type, host, params)
                val script = buildJavaScript(requestId, requestJson)

                // Execute the script
                webView.evaluateJavascript(script, null)

                // Handle cancellation
                cont.invokeOnCancellation {
                    Log.d(TAG, "Request cancelled: $id")
                    continuations.remove(id)
                }
            }
        }
    }

    /**
     * Handle incoming web messages from JavaScript
     */
    private fun handleWebMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: android.net.Uri,
        isMainFrame: Boolean,
        replyProxy: androidx.webkit.JavaScriptReplyProxy
    ) {
        val data = message.data ?: return
        Log.d(TAG, "Received web message: $data")

        try {
            val json = JSONObject(data)

            // Validate message structure
            if (!validateMessage(json)) {
                Log.w(TAG, "Invalid message structure: $data")
                return
            }

            val id = json.getString("id")
            val continuation = continuations.remove(id)

            if (continuation == null) {
                Log.w(TAG, "No continuation found for request: $id")
                return
            }

            when (json.getString("type")) {
                "result" -> {
                    val resultValue = json.getString("value")
                    Log.d(TAG, "Request completed successfully: $id")
                    // Parse the result as a NIP55Result object
                    val resultJson = JSONObject(resultValue)
                    val nip55Result = NIP55Result(
                        ok = resultJson.optBoolean("ok", true),
                        type = resultJson.optString("type", "result"),
                        id = resultJson.optString("id", id),
                        result = resultJson.optString("result"),
                        reason = null
                    )
                    continuation.resume(nip55Result)
                }
                "error" -> {
                    val errorValue = json.getString("value")
                    Log.w(TAG, "Request failed: $id - $errorValue")
                    val errorResult = NIP55Result(
                        ok = false,
                        type = "error",
                        id = id,
                        result = null,
                        reason = errorValue
                    )
                    continuation.resume(errorResult)
                }
                else -> {
                    Log.w(TAG, "Unknown response type: ${json.getString("type")}")
                    continuation.resumeWithException(Exception("Invalid response type"))
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse web message", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling web message", e)
        }
    }

    /**
     * Validate incoming message structure
     */
    private fun validateMessage(json: JSONObject): Boolean {
        return json.has("id") &&
               json.has("type") &&
               json.has("value") &&
               (json.getString("type") == "result" || json.getString("type") == "error")
    }

    /**
     * Build JSON string for the request
     */
    private fun buildRequestJson(id: String, type: String, host: String, params: Map<String, Any>?): String {
        Log.d(TAG, "Building request JSON - type: $type, params: $params")
        return JSONObject().apply {
            put("id", id)
            put("type", type)
            put("host", host)

            // Handle different request types according to NIP55WindowAPI spec
            params?.let { params ->
                when (type) {
                    "sign_event" -> {
                        // For sign_event, the event should be at the top level, not in params
                        if (params.containsKey("event")) {
                            val eventValue = params["event"]
                            Log.d(TAG, "Event value type: ${eventValue?.javaClass?.simpleName}, value: $eventValue")
                            // Parse the JSON string into a JSONObject if it's a string
                            when (eventValue) {
                                is String -> {
                                    try {
                                        val eventJson = JSONObject(eventValue)
                                        put("event", eventJson)
                                        Log.d(TAG, "Parsed event JSON successfully")
                                    } catch (e: JSONException) {
                                        Log.e(TAG, "Failed to parse event JSON: $eventValue", e)
                                        put("event", eventValue) // fallback to string
                                    }
                                }
                                is JSONObject -> put("event", eventValue)
                                else -> put("event", eventValue)
                            }
                        }
                    }
                    "nip04_encrypt", "nip04_decrypt" -> {
                        // For NIP-04 operations, extract direct fields
                        if (params.containsKey("pubkey")) put("pubkey", params["pubkey"])
                        if (params.containsKey("plaintext")) put("plaintext", params["plaintext"])
                        if (params.containsKey("ciphertext")) put("ciphertext", params["ciphertext"])
                    }
                    "nip44_encrypt", "nip44_decrypt" -> {
                        // For NIP-44 operations, extract direct fields
                        if (params.containsKey("pubkey")) put("pubkey", params["pubkey"])
                        if (params.containsKey("plaintext")) put("plaintext", params["plaintext"])
                        if (params.containsKey("ciphertext")) put("ciphertext", params["ciphertext"])
                    }
                    "get_public_key" -> {
                        // For get_public_key, handle permissions specially
                        if (params.containsKey("permissions")) {
                            val permissionsValue = params["permissions"]
                            when (permissionsValue) {
                                is String -> {
                                    try {
                                        // Parse the JSON string into an array
                                        val permissionsArray = org.json.JSONArray(permissionsValue)
                                        put("permissions", permissionsArray)
                                        Log.d(TAG, "Parsed permissions JSON array successfully")
                                    } catch (e: JSONException) {
                                        Log.e(TAG, "Failed to parse permissions JSON: $permissionsValue", e)
                                        put("permissions", permissionsValue) // fallback to string
                                    }
                                }
                                else -> put("permissions", permissionsValue)
                            }
                        }
                    }
                    else -> {
                        // For other types, use params object
                        put("params", JSONObject(params))
                    }
                }
            }
        }.toString().also { result ->
            Log.d(TAG, "Final request JSON: $result")
        }
    }

    /**
     * Build JavaScript code to call window.nostr.nip55
     */
    private fun buildJavaScript(id: String, requestJson: String): String {
        return """
            (async function() {
                try {
                    // Parse the request
                    const request = JSON.parse('${requestJson.replace("'", "\\'")}');

                    // Call the NIP-55 interface
                    if (!window.nostr || !window.nostr.nip55) {
                        throw new Error('NIP-55 interface not available');
                    }

                    console.log('AsyncBridge calling window.nostr.nip55:', request);
                    const result = await window.nostr.nip55(request);
                    console.log('AsyncBridge received result:', result);

                    // Send success response
                    window.androidBridge.postMessage(JSON.stringify({
                        id: '$id',
                        type: 'result',
                        value: JSON.stringify(result)
                    }));

                } catch (error) {
                    console.error('AsyncBridge error:', error);

                    // Send error response
                    window.androidBridge.postMessage(JSON.stringify({
                        id: '$id',
                        type: 'error',
                        value: error.message || 'Unknown error'
                    }));
                }
            })();
        """.trimIndent()
    }

    /**
     * Clean up any pending requests (call on activity destroy)
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up AsyncBridge - ${continuations.size} pending requests")

        // Cancel all pending requests
        continuations.values.forEach { continuation ->
            if (continuation.isActive) {
                continuation.cancel()
            }
        }
        continuations.clear()
    }
}

