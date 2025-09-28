package com.frostr.igloo.bridges

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.frostr.igloo.MainActivity
import com.google.gson.Gson
import com.frostr.igloo.bridges.SigningRequest
import com.frostr.igloo.bridges.SigningResult
import com.frostr.igloo.bridges.RequestContext
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified Signing Bridge - JavaScript interface for both PWA and NIP-55 signing
 *
 * Provides a unified JavaScript interface for the PWA to handle both internal signing
 * requests and external NIP-55 intents through the same permission and signing system.
 */
class UnifiedSigningBridge(
    private val context: Context,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "UnifiedSigningBridge"
        private const val PWA_CALLER_ID = "com.frostr.igloo.pwa"
    }

    private val gson = Gson()
    private val bridgeScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Dependencies
    private lateinit var signingService: UnifiedSigningService

    // JavaScript callback management
    private val pendingCallbacks = ConcurrentHashMap<String, String>()

    init {
        Log.i(TAG, "UnifiedSigningBridge initialized")
    }

    /**
     * Initialize the bridge with required services
     */
    fun initialize(signingService: UnifiedSigningService) {
        this.signingService = signingService
        Log.i(TAG, "Bridge services initialized")
    }


    /**
     * Handle signing request from PWA JavaScript
     */
    @JavascriptInterface
    fun signEvent(eventJson: String, callbackId: String): String {
        Log.i(TAG, "PWA signing request received with callbackId: $callbackId")

        return try {
            // Use the callbackId as the request ID directly - no transformations
            val requestId = callbackId
            pendingCallbacks[requestId] = callbackId

            Log.d(TAG, "Using consistent request ID: $requestId")

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
            gson.toJson(mapOf(
                "requestId" to requestId,
                "status" to "processing"
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process PWA signing request", e)
            notifyPWACallback(callbackId, null, "Failed to process request: ${e.message}")
            gson.toJson(mapOf("error" to "Request failed"))
        }
    }

    /**
     * Get public key from PWA JavaScript
     */
    @JavascriptInterface
    fun getPublicKey(callbackId: String): String {
        Log.i(TAG, "PWA public key request received with callbackId: $callbackId")

        return try {
            // Use the callbackId as the request ID directly - no transformations
            val requestId = callbackId
            pendingCallbacks[requestId] = callbackId

            Log.d(TAG, "Using consistent request ID: $requestId")

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

            gson.toJson(mapOf(
                "requestId" to requestId,
                "status" to "processing"
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process PWA public key request", e)
            notifyPWACallback(callbackId, null, "Failed to get public key: ${e.message}")
            gson.toJson(mapOf("error" to "Request failed"))
        }
    }


    /**
     * NIP-04 encryption from PWA JavaScript
     */
    @JavascriptInterface
    fun nip04Encrypt(plaintext: String, pubkey: String, callbackId: String): String {
        Log.i(TAG, "PWA NIP-04 encryption request received with callbackId: $callbackId")

        return try {
            // Use the callbackId as the request ID directly - no transformations
            val requestId = callbackId
            pendingCallbacks[requestId] = callbackId

            Log.d(TAG, "Using consistent request ID: $requestId")

            val payload = gson.toJson(mapOf(
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

            gson.toJson(mapOf(
                "requestId" to requestId,
                "status" to "processing"
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process PWA NIP-04 encryption", e)
            notifyPWACallback(callbackId, null, "Encryption failed: ${e.message}")
            gson.toJson(mapOf("error" to "Request failed"))
        }
    }

    /**
     * NIP-04 decryption from PWA JavaScript
     */
    @JavascriptInterface
    fun nip04Decrypt(ciphertext: String, pubkey: String, callbackId: String): String {
        Log.i(TAG, "PWA NIP-04 decryption request received with callbackId: $callbackId")

        return try {
            // Use the callbackId as the request ID directly - no transformations
            val requestId = callbackId
            pendingCallbacks[requestId] = callbackId

            Log.d(TAG, "Using consistent request ID: $requestId")

            val payload = gson.toJson(mapOf(
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

            gson.toJson(mapOf(
                "requestId" to requestId,
                "status" to "processing"
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process PWA NIP-04 decryption", e)
            notifyPWACallback(callbackId, null, "Decryption failed: ${e.message}")
            gson.toJson(mapOf("error" to "Request failed"))
        }
    }


    /**
     * Handle NIP-55 result directly from PWA Promise
     */
    @JavascriptInterface
    fun handleNIP55Result(resultJson: String): String {
        Log.i(TAG, "Direct NIP-55 result received: $resultJson")

        return try {
            val result = gson.fromJson(resultJson, Map::class.java)
            val success = result["ok"] as? Boolean ?: false
            val resultData = result["result"] as? String
            val error = result["reason"] as? String
            val requestId = result["id"] as? String ?: "unknown"

            Log.d(TAG, "Parsed NIP-55 result - success: $success, id: $requestId")

            // Return result to MainActivity directly
            bridgeScope.launch(Dispatchers.Main) {
                val activity = context as? MainActivity
                if (activity != null) {
                    if (success && resultData != null) {
                        Log.d(TAG, "Setting RESULT_OK for NIP-55 request: $requestId")
                        activity.setResult(Activity.RESULT_OK, Intent().apply {
                            putExtra("result", resultData)
                            putExtra("id", requestId)
                        })
                    } else {
                        Log.d(TAG, "Setting RESULT_CANCELED for NIP-55 request: $requestId")
                        activity.setResult(Activity.RESULT_CANCELED, Intent().apply {
                            putExtra("error", error ?: "Request failed")
                            putExtra("id", requestId)
                        })
                    }
                    activity.finish()
                }
            }

            gson.toJson(mapOf("status" to "processed"))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle direct NIP-55 result", e)

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

            gson.toJson(mapOf("error" to "Failed to process result"))
        }
    }

    /**
     * Handle user response to a pending signing request
     */
    @JavascriptInterface
    fun approveSigningRequest(requestId: String, approved: Boolean, signature: String?): String {
        Log.i(TAG, "User response received for request: $requestId, approved: $approved")

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

            gson.toJson(mapOf("status" to "processed"))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process user response", e)
            gson.toJson(mapOf("error" to "Failed to process response"))
        }
    }

    /**
     * Handle user response from PWA JavaScript (called by PWA when user approves/denies)
     */
    @JavascriptInterface
    fun handleUserResponse(requestId: String, approved: Boolean, result: String?): String {
        Log.i(TAG, "PWA user response received for request: $requestId, approved: $approved")

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
            gson.toJson(mapOf("status" to "processed"))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process PWA user response", e)
            gson.toJson(mapOf("error" to "Failed to process response"))
        }
    }

    /**
     * Get pending signing requests for UI display
     */
    @JavascriptInterface
    fun getPendingRequests(): String {
        return try {
            val pendingRequests = signingService.getPendingRequests()
            gson.toJson(pendingRequests)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get pending requests", e)
            gson.toJson(emptyList<SigningRequest>())
        }
    }

    /**
     * Cancel a pending signing request
     */
    @JavascriptInterface
    fun cancelSigningRequest(requestId: String): String {
        Log.i(TAG, "Cancelling signing request: $requestId")

        return try {
            bridgeScope.launch {
                val cancelled = signingService.cancelRequest(requestId)

                // Clean up callback if this was a PWA request
                val callbackId = pendingCallbacks.remove(requestId)
                if (callbackId != null) {
                    notifyPWACallback(callbackId, null, "Request cancelled")
                }
            }

            gson.toJson(mapOf("status" to "cancelled"))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel request", e)
            gson.toJson(mapOf("error" to "Failed to cancel request"))
        }
    }


    // Private helper methods

    private suspend fun handlePWASigningRequest(request: SigningRequest) {
        try {
            val requestContext = PWARequestContext(webView)
            val result = signingService.handleSigningRequest(request, requestContext)

            val callbackId = request.metadata["callbackId"]
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
                    is SigningResult.Pending -> {
                        // For pending requests, the callback will be handled when user responds
                        Log.d(TAG, "Request pending, callback will be handled later")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling PWA signing request", e)
            val callbackId = request.metadata["callbackId"]
            if (callbackId != null) {
                notifyPWACallback(callbackId, null, "Request processing failed: ${e.message}")
            }
        }
    }

    private fun notifyPWACallback(callbackId: String, signature: String?, error: String?) {
        webView.post {
            val resultJson = if (error != null) {
                gson.toJson(mapOf("error" to error))
            } else {
                gson.toJson(mapOf("signature" to signature))
            }

            Log.d(TAG, "Notifying PWA callback for ID: $callbackId")

            // Normal PWA callback
            val script = "window.SigningBridge && window.SigningBridge.handleCallback('$callbackId', $resultJson)"
            webView.evaluateJavascript(script, null)
        }
    }



    /**
     * Clean up resources
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up UnifiedSigningBridge")
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


