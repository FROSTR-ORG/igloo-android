package com.frostr.igloo.bridges

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.frostr.igloo.bridges.interfaces.ISigningBridge
import com.frostr.igloo.MainActivity
import com.frostr.igloo.debug.NIP55TraceContext
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified Signing Bridge - JavaScript interface for both PWA and NIP-55 signing
 *
 * Provides a unified JavaScript interface for the PWA to handle both internal signing
 * requests and external NIP-55 intents through the same permission and signing system.
 *
 * Extends BridgeBase for common functionality.
 */
class UnifiedSigningBridge(
    private val context: Context,
    webView: WebView
) : BridgeBase(webView), ISigningBridge {

    companion object {
        private const val PWA_CALLER_ID = "com.frostr.igloo.pwa"
    }

    private val bridgeScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Dependencies
    private lateinit var signingService: UnifiedSigningService

    // JavaScript callback management
    private val pendingCallbacks = ConcurrentHashMap<String, String>()

    init {
        logInfo("UnifiedSigningBridge initialized")
    }

    /**
     * Initialize the bridge with required services
     */
    fun initialize(signingService: UnifiedSigningService) {
        this.signingService = signingService
        logInfo("Bridge services initialized")
    }


    /**
     * Handle signing request from PWA JavaScript
     */
    @JavascriptInterface
    override fun signEvent(eventJson: String, callbackId: String): String {
        val traceId = NIP55TraceContext.extractTraceId(callbackId)
        logInfo("PWA signing request received with callbackId: $callbackId")
        NIP55TraceContext.log(traceId, "PWA_SIGN_REQUEST",
            "type" to "sign_event",
            "payload_size" to eventJson.length)

        return try {
            // Use the callbackId as the request ID directly - no transformations
            val requestId = callbackId
            pendingCallbacks[requestId] = callbackId

            logDebug("Using consistent request ID: $requestId")

            // Create signing request from PWA context
            val signingRequest = SigningRequest(
                id = requestId,
                type = "sign_event",
                payload = eventJson,
                callingApp = PWA_CALLER_ID,
                timestamp = System.currentTimeMillis(),
                metadata = mapOf(
                    "source" to "pwa",
                    "callbackId" to callbackId
                )
            )

            // Process request asynchronously
            bridgeScope.launch {
                handlePWASigningRequest(signingRequest)
            }

            // Return immediate response with request ID
            toJson(mapOf(
                "requestId" to requestId,
                "status" to "processing"
            ))

        } catch (e: Exception) {
            logError("Failed to process PWA signing request", e)
            notifyPWACallback(callbackId, null, "Failed to process request: ${e.message}")
            toJson(mapOf("error" to "Request failed"))
        }
    }

    /**
     * Get public key from PWA JavaScript
     */
    @JavascriptInterface
    override fun getPublicKey(callbackId: String): String {
        logInfo("PWA public key request received with callbackId: $callbackId")

        return try {
            // Use the callbackId as the request ID directly - no transformations
            val requestId = callbackId
            pendingCallbacks[requestId] = callbackId

            logDebug("Using consistent request ID: $requestId")

            val signingRequest = SigningRequest(
                id = requestId,
                type = "get_public_key",
                payload = "",
                callingApp = PWA_CALLER_ID,
                timestamp = System.currentTimeMillis(),
                metadata = mapOf(
                    "source" to "pwa",
                    "callbackId" to callbackId
                )
            )

            bridgeScope.launch {
                handlePWASigningRequest(signingRequest)
            }

            toJson(mapOf(
                "requestId" to requestId,
                "status" to "processing"
            ))

        } catch (e: Exception) {
            logError("Failed to process PWA public key request", e)
            notifyPWACallback(callbackId, null, "Failed to get public key: ${e.message}")
            toJson(mapOf("error" to "Request failed"))
        }
    }


    /**
     * NIP-04 encryption from PWA JavaScript
     */
    @JavascriptInterface
    override fun nip04Encrypt(plaintext: String, pubkey: String, callbackId: String): String {
        logInfo( "PWA NIP-04 encryption request received with callbackId: $callbackId")

        return try {
            // Use the callbackId as the request ID directly - no transformations
            val requestId = callbackId
            pendingCallbacks[requestId] = callbackId

            logDebug( "Using consistent request ID: $requestId")

            val payload = toJson(mapOf(
                "plaintext" to plaintext,
                "pubkey" to pubkey
            ))

            val signingRequest = SigningRequest(
                id = requestId,
                type = "nip04_encrypt",
                payload = payload,
                callingApp = PWA_CALLER_ID,
                timestamp = System.currentTimeMillis(),
                metadata = mapOf(
                    "source" to "pwa",
                    "callbackId" to callbackId
                )
            )

            bridgeScope.launch {
                handlePWASigningRequest(signingRequest)
            }

            toJson(mapOf(
                "requestId" to requestId,
                "status" to "processing"
            ))

        } catch (e: Exception) {
            logError( "Failed to process PWA NIP-04 encryption", e)
            notifyPWACallback(callbackId, null, "Encryption failed: ${e.message}")
            toJson(mapOf("error" to "Request failed"))
        }
    }

    /**
     * NIP-04 decryption from PWA JavaScript
     */
    @JavascriptInterface
    override fun nip04Decrypt(ciphertext: String, pubkey: String, callbackId: String): String {
        logInfo( "PWA NIP-04 decryption request received with callbackId: $callbackId")

        return try {
            // Use the callbackId as the request ID directly - no transformations
            val requestId = callbackId
            pendingCallbacks[requestId] = callbackId

            logDebug( "Using consistent request ID: $requestId")

            val payload = toJson(mapOf(
                "ciphertext" to ciphertext,
                "pubkey" to pubkey
            ))

            val signingRequest = SigningRequest(
                id = requestId,
                type = "nip04_decrypt",
                payload = payload,
                callingApp = PWA_CALLER_ID,
                timestamp = System.currentTimeMillis(),
                metadata = mapOf(
                    "source" to "pwa",
                    "callbackId" to callbackId
                )
            )

            bridgeScope.launch {
                handlePWASigningRequest(signingRequest)
            }

            toJson(mapOf(
                "requestId" to requestId,
                "status" to "processing"
            ))

        } catch (e: Exception) {
            logError( "Failed to process PWA NIP-04 decryption", e)
            notifyPWACallback(callbackId, null, "Decryption failed: ${e.message}")
            toJson(mapOf("error" to "Request failed"))
        }
    }


    /**
     * Handle NIP-55 result directly from PWA Promise
     */
    @JavascriptInterface
    override fun handleNIP55Result(resultJson: String): String {
        logInfo( "Direct NIP-55 result received: $resultJson")

        return try {
            val result = gson.fromJson(resultJson, Map::class.java)
            val success = result["ok"] as? Boolean ?: false
            val resultData = result["result"] as? String
            val error = result["reason"] as? String
            val requestId = result["id"] as? String ?: "unknown"

            val traceId = NIP55TraceContext.extractTraceId(requestId)
            NIP55TraceContext.log(traceId, "NIP55_RESULT_RECEIVED",
                "success" to success,
                "has_result" to (resultData != null),
                "error" to error?.take(30))
            logDebug( "Parsed NIP-55 result - success: $success, id: $requestId")

            // Return result to MainActivity directly
            bridgeScope.launch(Dispatchers.Main) {
                val activity = context as? MainActivity
                if (activity != null) {
                    if (success && resultData != null) {
                        logDebug( "Setting RESULT_OK for NIP-55 request: $requestId")
                        activity.setResult(Activity.RESULT_OK, Intent().apply {
                            putExtra("result", resultData)
                            putExtra("id", requestId)
                        })
                    } else {
                        logDebug( "Setting RESULT_CANCELED for NIP-55 request: $requestId")
                        activity.setResult(Activity.RESULT_CANCELED, Intent().apply {
                            putExtra("error", error ?: "Request failed")
                            putExtra("id", requestId)
                        })
                    }
                    activity.finish()
                }
            }

            toJson(mapOf("status" to "processed"))

        } catch (e: Exception) {
            logError( "Failed to handle direct NIP-55 result", e)

            // Fallback - finish with error
            bridgeScope.launch(Dispatchers.Main) {
                val activity = context as? MainActivity
                if (activity != null) {
                    activity.setResult(Activity.RESULT_CANCELED, Intent().apply {
                        putExtra("error", "Failed to process result: ${e.message}")
                        putExtra("id", "unknown")
                    })
                    activity.finish()
                }
            }

            toJson(mapOf("error" to "Failed to process result"))
        }
    }

    /**
     * Handle user response to a pending signing request
     */
    @JavascriptInterface
    override fun approveSigningRequest(requestId: String, approved: Boolean, signature: String?): String {
        logInfo( "User response received for request: $requestId, approved: $approved")

        return try {
            bridgeScope.launch {
                val result = signingService.handleUserResponse(requestId, approved, signature)

                // Check if this was a PWA request that needs callback notification
                val callbackId = pendingCallbacks.remove(requestId)
                if (callbackId != null) {
                    when (result) {
                        is SigningResult.Success -> {
                            notifyPWACallback(callbackId, result.signature, null)
                        }
                        is SigningResult.Denied -> {
                            notifyPWACallback(callbackId, null, result.reason)
                        }
                        is SigningResult.Error -> {
                            notifyPWACallback(callbackId, null, result.message)
                        }
                        else -> {
                            notifyPWACallback(callbackId, null, "Unknown result")
                        }
                    }
                }
            }

            toJson(mapOf("status" to "processed"))

        } catch (e: Exception) {
            logError( "Failed to process user response", e)
            toJson(mapOf("error" to "Failed to process response"))
        }
    }

    /**
     * Handle user response from PWA JavaScript (called by PWA when user approves/denies)
     */
    @JavascriptInterface
    override fun handleUserResponse(requestId: String, approved: Boolean, result: String?): String {
        logInfo( "PWA user response received for request: $requestId, approved: $approved")

        return try {
            // Handle all requests through the signing service
            bridgeScope.launch {
                val signingResult = signingService.handleUserResponse(requestId, approved, result)

                val callbackId = pendingCallbacks.remove(requestId)
                if (callbackId != null) {
                    when (signingResult) {
                        is SigningResult.Success -> {
                            notifyPWACallback(callbackId, signingResult.signature, null)
                        }
                        is SigningResult.Denied -> {
                            notifyPWACallback(callbackId, null, signingResult.reason)
                        }
                        is SigningResult.Error -> {
                            notifyPWACallback(callbackId, null, signingResult.message)
                        }
                        else -> {
                            notifyPWACallback(callbackId, null, "Unknown result")
                        }
                    }
                }
            }
            toJson(mapOf("status" to "processed"))

        } catch (e: Exception) {
            logError( "Failed to process PWA user response", e)
            toJson(mapOf("error" to "Failed to process response"))
        }
    }

    /**
     * Get pending signing requests for UI display
     */
    @JavascriptInterface
    override fun getPendingRequests(): String {
        return try {
            val pendingRequests = signingService.getPendingRequests()
            toJson(pendingRequests)
        } catch (e: Exception) {
            logError( "Failed to get pending requests", e)
            toJson(emptyList<SigningRequest>())
        }
    }

    /**
     * Cancel a pending signing request
     */
    @JavascriptInterface
    override fun cancelSigningRequest(requestId: String): String {
        logInfo( "Cancelling signing request: $requestId")

        return try {
            bridgeScope.launch {
                val cancelled = signingService.cancelRequest(requestId)

                // Clean up callback if this was a PWA request
                val callbackId = pendingCallbacks.remove(requestId)
                if (callbackId != null) {
                    notifyPWACallback(callbackId, null, "Request cancelled")
                }
            }

            toJson(mapOf("status" to "cancelled"))

        } catch (e: Exception) {
            logError( "Failed to cancel request", e)
            toJson(mapOf("error" to "Failed to cancel request"))
        }
    }


    // Private helper methods

    private suspend fun handlePWASigningRequest(request: SigningRequest) {
        val traceId = NIP55TraceContext.extractTraceId(request.id)
        try {
            NIP55TraceContext.log(traceId, "PWA_SIGNING_START",
                "type" to request.type,
                "app" to request.callingApp.substringAfterLast('.'))

            val requestContext = PWARequestContext(webView)
            val result = signingService.handleSigningRequest(request, requestContext)

            val callbackId = request.metadata["callbackId"]
            if (callbackId != null) {
                when (result) {
                    is SigningResult.Success -> {
                        NIP55TraceContext.log(traceId, "PWA_SIGNING_SUCCESS")
                        notifyPWACallback(callbackId, result.signature, null)
                    }
                    is SigningResult.Denied -> {
                        NIP55TraceContext.log(traceId, "PWA_SIGNING_DENIED", "reason" to result.reason)
                        notifyPWACallback(callbackId, null, result.reason)
                    }
                    is SigningResult.Error -> {
                        NIP55TraceContext.log(traceId, "PWA_SIGNING_ERROR", "message" to result.message)
                        notifyPWACallback(callbackId, null, result.message)
                    }
                    is SigningResult.Pending -> {
                        NIP55TraceContext.log(traceId, "PWA_SIGNING_PENDING")
                        // For pending requests, the callback will be handled when user responds
                        logDebug( "Request pending, callback will be handled later")
                    }
                }
            }

        } catch (e: Exception) {
            NIP55TraceContext.logError(traceId, "PWA_SIGNING", e.message ?: "Unknown error")
            logError( "Error handling PWA signing request", e)
            val callbackId = request.metadata["callbackId"]
            if (callbackId != null) {
                notifyPWACallback(callbackId, null, "Request processing failed: ${e.message}")
            }
        }
    }

    private fun notifyPWACallback(callbackId: String, signature: String?, error: String?) {
        val resultJson = if (error != null) {
            toJson(mapOf("error" to error))
        } else {
            toJson(mapOf("signature" to signature))
        }

        logDebug("Notifying PWA callback for ID: $callbackId")

        // Use BridgeBase's notifyCallbackJson for proper escaping and thread safety
        notifyCallbackJson("SigningBridge", "handleCallback", callbackId, resultJson)
    }



    /**
     * Clean up resources
     */
    override fun cleanup() {
        logInfo("Cleaning up UnifiedSigningBridge")
        pendingCallbacks.clear()
        bridgeScope.cancel()
    }
}

/**
 * Request context implementations
 */

class PWARequestContext(private val webView: WebView) : RequestContext {

    override suspend fun onUserPromptRequired(request: SigningRequest) {
        withContext(Dispatchers.Main) {
            // Notify PWA that user prompt is required
            val requestJson = Gson().toJson(request)
            val script = "window.SigningBridge && window.SigningBridge.onUserPromptRequired($requestJson)"
            webView.evaluateJavascript(script, null)
        }
    }

    override suspend fun onSigningComplete(request: SigningRequest, result: SigningResult) {
        withContext(Dispatchers.Main) {
            // Notify PWA that signing is complete
            val resultJson = Gson().toJson(result)
            val script = "window.SigningBridge && window.SigningBridge.onSigningComplete('${request.id}', $resultJson)"
            webView.evaluateJavascript(script, null)
        }
    }
}


