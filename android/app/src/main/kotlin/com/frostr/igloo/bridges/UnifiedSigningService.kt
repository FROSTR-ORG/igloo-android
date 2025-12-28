package com.frostr.igloo.bridges

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Unified Signing Service - SIMPLIFIED FORWARDING MODE
 *
 * Handles both internal PWA and external NIP-55 signing requests by forwarding them
 * to the PWA unified permission system. This service validates request format and
 * forwards everything to the PWA for permission handling and signing.
 *
 * Permission logic is entirely handled by the PWA unified system.
 */
class UnifiedSigningService(
    private val context: Context,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "UnifiedSigningService"
        private const val MAX_CONCURRENT_REQUESTS = 10
        private const val REQUEST_TIMEOUT_MS = 15000L // 15 seconds - balance between fast failure and giving bifrost time
    }

    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Request management
    private val activeRequests = ConcurrentHashMap<String, SigningRequest>()
    private val requestQueue = ConcurrentLinkedQueue<SigningRequest>()
    private val pendingCallbacks = ConcurrentHashMap<String, CompletableDeferred<SigningResult>>()

    // Request processing
    private var isProcessingQueue = false

    init {
        Log.i(TAG, "UnifiedSigningService initialized")
        startRequestProcessor()
    }

    /**
     * Handle a signing request from any source (internal PWA or external intent)
     *
     * SIMPLIFIED FORWARDING: All permission checking is now handled by the PWA unified system.
     * Android layer only validates request format and forwards to PWA for processing.
     */
    suspend fun handleSigningRequest(
        request: SigningRequest,
        context: RequestContext
    ): SigningResult {
        Log.i(TAG, "Processing signing request: ${request.type} from ${request.callingApp}")

        return try {
            // Validate request format only
            val validationResult = validateRequest(request)
            if (validationResult is SigningResult.Error) {
                return validationResult
            }

            // Forward directly to PWA for permission handling and signing
            // PWA unified permission system handles all permission logic
            Log.i(TAG, "Forwarding request to PWA for permission handling and signing")
            performSigning(request)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing signing request", e)
            SigningResult.Error("Internal error: ${e.message}")
        }
    }

    /**
     * Process user response to a pending request
     * Note: This is now primarily for backwards compatibility since we forward directly to PWA
     */
    suspend fun handleUserResponse(requestId: String, approved: Boolean, signature: String? = null): SigningResult {
        Log.i(TAG, "Processing user response for request: $requestId, approved: $approved")
        Log.w(TAG, "Note: handleUserResponse is deprecated - requests are now forwarded directly to PWA")

        val request = activeRequests[requestId]
        if (request == null) {
            Log.w(TAG, "No active request found for ID: $requestId")
            return SigningResult.Error("Request not found")
        }

        val callback = pendingCallbacks.remove(requestId)
        activeRequests.remove(requestId)

        val result = if (approved) {
            if (signature != null) {
                // User approved and signature was provided
                SigningResult.Success(signature, autoApproved = false)
            } else {
                // User approved, now perform actual signing
                performSigning(request)
            }
        } else {
            SigningResult.Denied("User denied request")
        }

        // Complete the pending request
        callback?.complete(result)

        return result
    }

    /**
     * Get all pending requests (for UI display)
     */
    fun getPendingRequests(): List<SigningRequest> {
        return activeRequests.values.toList()
    }

    /**
     * Cancel a pending request
     */
    suspend fun cancelRequest(requestId: String): Boolean {
        Log.i(TAG, "Cancelling request: $requestId")

        val request = activeRequests.remove(requestId)
        val callback = pendingCallbacks.remove(requestId)

        if (callback != null) {
            callback.complete(SigningResult.Error("Request cancelled"))
            return true
        }

        return request != null
    }

    /**
     * Validate incoming signing request
     */
    private fun validateRequest(request: SigningRequest): SigningResult? {
        // Validate request ID
        if (request.id.isBlank()) {
            return SigningResult.Error("Invalid request ID")
        }

        // Validate request type
        if (request.type !in listOf("sign_event", "get_public_key", "nip04_encrypt", "nip04_decrypt")) {
            return SigningResult.Error("Unsupported request type: ${request.type}")
        }

        // Validate payload based on type
        when (request.type) {
            "sign_event" -> {
                try {
                    gson.fromJson(request.payload, Map::class.java)
                } catch (e: Exception) {
                    return SigningResult.Error("Invalid event payload")
                }
            }
            "nip04_encrypt", "nip04_decrypt" -> {
                if (request.payload.isBlank()) {
                    return SigningResult.Error("Empty payload for encryption/decryption")
                }
            }
        }

        // Validate calling app
        if (request.callingApp.isBlank()) {
            return SigningResult.Error("Invalid calling app")
        }

        return null // No validation errors
    }

    // REMOVED: All permission checking methods are deprecated
    // Permission logic is now handled entirely by the PWA unified system
    // Methods removed: checkPermissions(), processAutoApproval(), processUserPrompt()

    /**
     * Forward signing request to PWA's window.nostr.nip55 interface
     */
    private suspend fun performSigning(request: SigningRequest): SigningResult {
        return withContext(Dispatchers.IO) {
            try {
                // Build NIP-55 request object for PWA
                val nip55Request = buildNIP55Request(request)
                val requestJson = gson.toJson(nip55Request)

                Log.d(TAG, "Forwarding signing request to PWA: ${request.type}")
                Log.d(TAG, "🔍 Built NIP55 request map: $nip55Request")
                Log.d(TAG, "🔍 Serialized request JSON: $requestJson")

                // Call the PWA's signing interface via JavaScript
                val javascriptCall = """
                    (async function() {
                        try {
                            console.log('🔍 Android checking PWA nip55 interface...');
                            console.log('🌐 window exists:', typeof window !== 'undefined');
                            console.log('📊 window.nostr exists:', !!(window.nostr));
                            console.log('🔑 window.nostr.nip55 exists:', !!(window.nostr && window.nostr.nip55));
                            console.log('🏭 window.nostr.nip55 type:', typeof (window.nostr && window.nostr.nip55));

                            if (window.nostr && window.nostr.nip55) {
                                const request = $requestJson;
                                console.log('✅ Android calling PWA nip55 with:', request);
                                const result = await window.nostr.nip55(request);
                                console.log('✅ PWA nip55 result:', result);
                                return JSON.stringify({ success: true, result: result });
                            } else {
                                console.error('❌ PWA nip55 interface not available:', {
                                    windowExists: typeof window !== 'undefined',
                                    nostrExists: !!(window.nostr),
                                    nip55Exists: !!(window.nostr && window.nostr.nip55),
                                    nostrKeys: window.nostr ? Object.keys(window.nostr) : 'no nostr object'
                                });
                                return JSON.stringify({ success: false, error: 'PWA signing interface not available' });
                            }
                        } catch (error) {
                            console.error('❌ PWA signing error:', error);
                            return JSON.stringify({ success: false, error: error.message || error.toString() });
                        }
                    })();
                """.trimIndent()

                // Execute JavaScript and wait for result
                val resultDeferred = CompletableDeferred<String>()

                // This needs to be called from the main thread since we're accessing WebView
                withContext(Dispatchers.Main) {
                    executeJavaScriptWithCallback(javascriptCall, resultDeferred)
                }

                // Wait for JavaScript result with timeout
                val jsResult = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    resultDeferred.await()
                } ?: return@withContext SigningResult.Error("Request timeout waiting for PWA response")

                // Parse the result from PWA
                return@withContext parseSigningResult(jsResult, request.type)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward request to PWA", e)
                SigningResult.Error("Failed to communicate with PWA: ${e.message}")
            }
        }
    }

    /**
     * Build NIP-55 request object for the PWA
     */
    private fun buildNIP55Request(request: SigningRequest): Map<String, Any> {
        val nip55Request = mutableMapOf<String, Any>(
            "type" to request.type,
            "id" to request.id,
            "host" to request.callingApp
        )

        when (request.type) {
            "sign_event" -> {
                try {
                    val eventData = gson.fromJson(request.payload, Map::class.java)
                    nip55Request["event"] = eventData
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse event payload, using raw payload")
                    nip55Request["event"] = request.payload
                }
            }
            "nip04_encrypt", "nip44_encrypt" -> {
                try {
                    val encryptData = gson.fromJson(request.payload, Map::class.java)
                    nip55Request["plaintext"] = encryptData["plaintext"] ?: ""
                    nip55Request["pubkey"] = encryptData["pubkey"] ?: ""
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse encrypt payload")
                }
            }
            "nip04_decrypt", "nip44_decrypt" -> {
                try {
                    val decryptData = gson.fromJson(request.payload, Map::class.java)
                    nip55Request["ciphertext"] = decryptData["ciphertext"] ?: ""
                    nip55Request["pubkey"] = decryptData["pubkey"] ?: ""
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse decrypt payload")
                }
            }
            "decrypt_zap_event" -> {
                try {
                    val zapData = gson.fromJson(request.payload, Map::class.java)
                    nip55Request["event"] = zapData
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse zap event payload")
                }
            }
        }

        return nip55Request
    }

    /**
     * Parse the signing result from PWA
     */
    private fun parseSigningResult(jsResult: String, requestType: String): SigningResult {
        return try {
            val response = gson.fromJson(jsResult, Map::class.java)

            if (response["success"] == true) {
                val result = response["result"] as? Map<String, Any>
                val resultData = result?.get("result")

                when (resultData) {
                    is String -> SigningResult.Success(resultData, autoApproved = false)
                    is Map<*, *> -> {
                        // For sign_event, return the entire signed event
                        SigningResult.Success(gson.toJson(resultData), autoApproved = false)
                    }
                    else -> SigningResult.Success(resultData?.toString() ?: "", autoApproved = false)
                }
            } else {
                val error = response["error"]?.toString() ?: "Unknown error from PWA"
                SigningResult.Error(error)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PWA response", e)
            SigningResult.Error("Failed to parse PWA response: ${e.message}")
        }
    }

    /**
     * Execute JavaScript with callback
     */
    private fun executeJavaScriptWithCallback(javascript: String, callback: CompletableDeferred<String>) {
        try {
            webView.evaluateJavascript(javascript) { result ->
                Log.d(TAG, "JavaScript execution result: $result")

                // Handle the result - remove quotes if it's a JSON string
                val cleanResult = when {
                    result == "null" -> """{"success": false, "error": "PWA returned null"}"""
                    result.startsWith("\"") && result.endsWith("\"") -> {
                        // Remove surrounding quotes and unescape
                        result.substring(1, result.length - 1).replace("\\\"", "\"")
                    }
                    else -> result
                }

                callback.complete(cleanResult)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute JavaScript", e)
            callback.complete("""{"success": false, "error": "JavaScript execution failed: ${e.message}"}""")
        }
    }

    /**
     * Start the request processor for handling queued requests
     */
    private fun startRequestProcessor() {
        serviceScope.launch {
            while (isActive) {
                try {
                    processQueuedRequests()
                    delay(100) // Process queue every 100ms
                } catch (e: Exception) {
                    Log.e(TAG, "Error in request processor", e)
                }
            }
        }
    }

    /**
     * Process queued requests
     */
    private suspend fun processQueuedRequests() {
        if (isProcessingQueue || activeRequests.size >= MAX_CONCURRENT_REQUESTS) {
            return
        }

        isProcessingQueue = true

        try {
            val request = requestQueue.poll()
            if (request != null) {
                Log.d(TAG, "Processing queued request: ${request.id}")
                // Queue processing would go here for background requests
                // For now, we handle all requests immediately via handleSigningRequest
            }
        } finally {
            isProcessingQueue = false
        }
    }

    /**
     * Clean up service resources
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up UnifiedSigningService")

        // Cancel all pending requests
        pendingCallbacks.values.forEach { callback ->
            callback.complete(SigningResult.Error("Service shutting down"))
        }

        activeRequests.clear()
        pendingCallbacks.clear()
        requestQueue.clear()

        // Cancel service scope
        serviceScope.cancel()
    }
}

/**
 * Data classes for signing service
 */

data class SigningRequest(
    val id: String,
    val type: String,
    val payload: String,
    val callingApp: String,
    val timestamp: Long,
    val metadata: Map<String, String> = emptyMap()
)

sealed class SigningResult {
    data class Success(val signature: String, val autoApproved: Boolean) : SigningResult()
    data class Pending(val requestId: String) : SigningResult()
    data class Error(val message: String) : SigningResult()
    data class Denied(val reason: String) : SigningResult()
}

// REMOVED: PermissionResult enum - permission logic now handled by PWA unified system

/**
 * Request context for handling callbacks
 */
interface RequestContext {
    suspend fun onUserPromptRequired(request: SigningRequest)
    suspend fun onSigningComplete(request: SigningRequest, result: SigningResult)
}