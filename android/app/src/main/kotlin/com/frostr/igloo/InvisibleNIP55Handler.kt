package com.frostr.igloo

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.frostr.igloo.bridges.StorageBridge
import com.frostr.igloo.NIP55PermissionDialog.PermissionStorage
import com.frostr.igloo.util.NIP55Deduplicator

/**
 * Invisible NIP-55 Intent Handler - Minimal Routing Layer
 *
 * This is a thin routing layer that:
 * 1. Receives NIP-55 intents from external apps via nostrsigner:// URI scheme
 * 2. Parses and validates the request
 * 3. Checks permissions and determines if user prompt is needed
 * 4. Routes to MainActivity for signing execution
 * 5. Returns result to calling app via setResult()
 *
 * The heavy lifting (PWA loading, crypto operations) happens in MainActivity.
 * This handler is just responsible for the IPC boundary between external apps and MainActivity.
 */
class InvisibleNIP55Handler : Activity() {
    companion object {
        private const val TAG = "InvisibleNIP55Handler"
        private const val REQUEST_TIMEOUT_MS = 30000L // 30 seconds
        private const val SERVICE_BIND_TIMEOUT_MS = 5000L // 5 seconds
        private const val PROMPT_RESULT_TIMEOUT_MS = 60000L // 60 seconds for user interaction
        private const val BATCH_DELAY_MS = 150L // Wait 150ms to collect concurrent requests for batching
    }

    // Data class to track completed results
    private data class CompletedResult(
        val requestId: String,
        val requestType: String,
        val callingApp: String,
        val result: String?,
        val error: String?
    )

    private val gson = Gson()
    private val handlerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val timeoutHandler = Handler(Looper.getMainLooper())

    private var isCompleted = false
    private lateinit var originalRequest: NIP55Request

    // Request queue - allows multiple concurrent requests without state corruption
    private val pendingRequests = mutableListOf<NIP55Request>()
    private var isProcessingRequest = false
    private var isMainActivityLaunched = false  // Track if MainActivity is already launched for this queue

    // Batch result processing - collect results and return them all at once
    private val completedResults = mutableListOf<CompletedResult>()
    private var batchTimer: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "NIP-55 request received from external app (onCreate)")
        processNIP55Intent(intent)
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)

        if (newIntent == null) {
            Log.w(TAG, "onNewIntent called with null intent")
            return
        }

        Log.d(TAG, "NIP-55 request received from external app (onNewIntent)")
        Log.d(TAG, "State before reset: isProcessingRequest=$isProcessingRequest, isCompleted=$isCompleted, queueSize=${pendingRequests.size}")

        // Only reset isProcessingRequest if queue is empty (fresh batch of requests)
        // If queue has items, we're in the middle of processing - don't reset
        if (pendingRequests.isEmpty()) {
            isProcessingRequest = false
            isCompleted = false
            Log.d(TAG, "State reset for new batch of requests")
        } else {
            // Queue is active - only reset completion flag for new request
            isCompleted = false
            Log.d(TAG, "Queue is active - not resetting isProcessingRequest")
        }

        // Update the activity's intent
        setIntent(newIntent)
        processNIP55Intent(newIntent)
    }

    private fun processNIP55Intent(intent: Intent) {
        try {
            // Parse NIP-55 request
            val newRequest = parseNIP55Request(intent)

            Log.d(TAG, "Received NIP-55 request: ${newRequest.type} (id=${newRequest.id}) from ${newRequest.callingApp}")

            // Comprehensive deduplication by operation content
            val newRequestKey = getDeduplicationKey(newRequest)
            val isDuplicate = pendingRequests.any { existingRequest ->
                getDeduplicationKey(existingRequest) == newRequestKey
            }

            if (isDuplicate) {
                Log.w(TAG, "✗ Ignoring duplicate request: ${newRequest.type} (dedupe_key=$newRequestKey)")
                return
            }

            // Add to queue
            pendingRequests.add(newRequest)
            Log.d(TAG, "✓ Request queued: ${newRequest.type} (dedupe_key=$newRequestKey)")
            Log.d(TAG, "Queue size: ${pendingRequests.size}")

            // Process next request if not already processing one
            processNextRequest()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse NIP-55 request", e)
            returnError("Failed to parse request: ${e.message}")
            finish()
        }
    }

    /**
     * Generate a deduplication key for a request based on operation content
     * Delegates to shared NIP55Deduplicator utility
     */
    private fun getDeduplicationKey(request: NIP55Request): String {
        return NIP55Deduplicator.getDeduplicationKey(
            callingApp = request.callingApp,
            operationType = request.type,
            params = request.params,
            fallbackId = request.id
        )
    }

    /**
     * Process the next request in the queue
     */
    private fun processNextRequest() {
        // Don't start processing if already processing or no requests in queue
        if (isProcessingRequest) {
            Log.d(TAG, "processNextRequest: Already processing a request, skipping")
            return
        }
        if (pendingRequests.isEmpty()) {
            Log.d(TAG, "processNextRequest: Queue is empty, nothing to process")
            return
        }

        // Mark as processing
        isProcessingRequest = true

        // Get the first request from queue
        originalRequest = pendingRequests.first()
        Log.d(TAG, "Processing queued request: ${originalRequest.type} (id=${originalRequest.id})")

        try {
            // Special handling for get_public_key with bulk permissions
            if (originalRequest.type == "get_public_key" && originalRequest.params.containsKey("permissions")) {
                Log.d(TAG, "get_public_key with bulk permissions - showing native dialog")
                showBulkPermissionDialog()
                return
            }

            // Check permissions
            val permissionStatus = checkPermission(originalRequest)
            Log.d(TAG, "Permission status: $permissionStatus")

            when (permissionStatus) {
                "denied" -> {
                    Log.d(TAG, "Permission denied - returning error")
                    returnError("Permission denied")
                    return
                }
                "allowed" -> {
                    // Permission already granted - execute signing directly via MainActivity
                    Log.d(TAG, "Permission allowed - delegating to MainActivity for fast signing")

                    launchMainActivityForFastSigning()
                    return
                }
                "prompt_required" -> {
                    // Show native permission dialog
                    Log.d(TAG, "Permission prompt required - showing native dialog")
                    showSinglePermissionDialog()
                    return
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process request", e)
            returnError("Failed to process request: ${e.message}")
        }
    }


    /**
     * Return successful result to calling app (with batching support)
     */
    private fun returnResult(result: String) {
        // Add to completed results
        completedResults.add(CompletedResult(
            requestId = originalRequest.id,
            requestType = originalRequest.type,
            callingApp = originalRequest.callingApp,
            result = result,
            error = null
        ))

        // Remove completed request from queue
        pendingRequests.removeAll { it.id == originalRequest.id }
        isProcessingRequest = false
        Log.d(TAG, "✓ Fast signing completed")
        Log.d(TAG, "Request completed. Pending: ${pendingRequests.size}, Completed: ${completedResults.size}")

        // Schedule batch return or process next request
        scheduleBatchReturn()
    }

    /**
     * Return error result to calling app (with batching support)
     */
    private fun returnError(error: String) {
        // Add to completed results
        completedResults.add(CompletedResult(
            requestId = if (::originalRequest.isInitialized) originalRequest.id else "unknown",
            requestType = if (::originalRequest.isInitialized) originalRequest.type else "unknown",
            callingApp = if (::originalRequest.isInitialized) originalRequest.callingApp else "unknown",
            result = null,
            error = error
        ))

        // Remove completed request from queue
        if (::originalRequest.isInitialized) {
            pendingRequests.removeAll { it.id == originalRequest.id }
        }
        isProcessingRequest = false
        Log.d(TAG, "✗ Fast signing failed: $error")
        Log.d(TAG, "Request failed. Pending: ${pendingRequests.size}, Completed: ${completedResults.size}")

        // Schedule batch return or process next request
        scheduleBatchReturn()
    }

    /**
     * Schedule batch return with delay to collect concurrent requests
     */
    private fun scheduleBatchReturn() {
        // Cancel any existing timer
        batchTimer?.let { timeoutHandler.removeCallbacks(it) }

        // Check if there are more pending requests
        if (pendingRequests.isNotEmpty()) {
            Log.d(TAG, "Processing next request in queue (${pendingRequests.size} remaining)")
            // Reset completion flag and process next request
            isCompleted = false
            processNextRequest()

            // Schedule batch return after delay to collect more results
            batchTimer = Runnable {
                returnBatchResults()
            }
            timeoutHandler.postDelayed(batchTimer!!, BATCH_DELAY_MS)
        } else {
            // No more pending requests - return all accumulated results now
            Log.d(TAG, "Queue empty - returning batch of ${completedResults.size} results")
            returnBatchResults()
        }
    }

    /**
     * Return all accumulated results as a batch to calling app
     */
    private fun returnBatchResults() {
        if (isCompleted) return
        isCompleted = true

        // Cancel batch timer if still scheduled
        batchTimer?.let { timeoutHandler.removeCallbacks(it) }

        if (completedResults.isEmpty()) {
            Log.w(TAG, "No results to return")
            finish()
            return
        }

        Log.d(TAG, "Returning RESULT_OK to calling app with ${completedResults.size} results")

        // Build results JSON array like Amber does
        val resultsArray = org.json.JSONArray()
        var hasError = false

        for (completed in completedResults) {
            val resultObj = org.json.JSONObject().apply {
                put("id", completed.requestId)
                if (completed.error != null) {
                    put("error", completed.error)
                    hasError = true
                } else if (completed.result != null) {
                    // Return correct fields per NIP-55 spec based on request type
                    when (completed.requestType) {
                        "sign_event" -> {
                            // sign_event: return 'result' (signature) and 'event' (signed event JSON)
                            put("result", completed.result)
                            put("signature", completed.result)
                            put("event", completed.result)
                        }
                        else -> {
                            // get_public_key and others: return only 'result'
                            put("result", completed.result)
                        }
                    }
                }
            }
            resultsArray.put(resultObj)
        }

        // Create result intent
        val resultIntent = Intent().apply {
            // Use "results" array for batch (Amber-compatible format)
            if (completedResults.size > 1) {
                putExtra("results", resultsArray.toString())
                Log.d(TAG, "Batch return format: results array with ${completedResults.size} items")
            } else {
                // Single result - use individual fields for compatibility
                val single = completedResults[0]
                putExtra("id", single.requestId)
                // Return Igloo's package name (not the calling app's) so Amethyst knows which signer app to use for future requests
                putExtra("package", packageName)
                if (single.error != null) {
                    putExtra("error", single.error)
                } else if (single.result != null) {
                    // Return correct fields per NIP-55 spec based on request type
                    when (single.requestType) {
                        "sign_event" -> {
                            // sign_event: return 'result' (signature) and 'event' (signed event JSON)
                            putExtra("result", single.result)
                            putExtra("signature", single.result)
                            putExtra("event", single.result)
                        }
                        else -> {
                            // get_public_key and others: return only 'result'
                            putExtra("result", single.result)
                        }
                    }
                }
                Log.d(TAG, "Single result format: individual fields (type=${single.requestType}, package=$packageName)")
            }
        }

        setResult(if (hasError) RESULT_CANCELED else RESULT_OK, resultIntent)
        isMainActivityLaunched = false  // Reset for next batch of requests
        finish()
    }

    /**
     * Legacy single-result return (kept for non-batched code paths)
     */
    private fun returnSingleResultLegacy(error: String) {
        if (isCompleted) return
        isCompleted = true

        // Direct Intent request - reply via setResult
        val resultIntent = Intent().apply {
            putExtra("error", error)
            putExtra("id", if (::originalRequest.isInitialized) originalRequest.id else "unknown")
        }

        Log.d(TAG, "Returning RESULT_CANCELED: $error")
        setResult(RESULT_CANCELED, resultIntent)

        // Check if there are more requests in queue
        if (pendingRequests.isNotEmpty()) {
            Log.d(TAG, "Processing next request in queue after error (${pendingRequests.size} remaining)")
            // Reset completion flag and process next request
            isCompleted = false
            processNextRequest()
        } else {
            // No more requests - finish and return to calling app
            Log.d(TAG, "Queue empty after error - finishing InvisibleNIP55Handler")
            isMainActivityLaunched = false  // Reset for next batch of requests
            finish()
        }
    }

    /**
     * Cleanup resources
     */
    private fun cleanup() {
        timeoutHandler.removeCallbacksAndMessages(null)
        handlerScope.cancel()
        // Stop foreground service
        NIP55SigningService.stop(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    /**
     * Show native dialog for bulk permission request (get_public_key with permissions array)
     */
    private fun showBulkPermissionDialog() {
        Log.d(TAG, "Showing native bulk permission dialog")

        val permissionsJson = originalRequest.params["permissions"] ?: run {
            returnError("Missing permissions parameter")
            return
        }

        // Must launch MainActivity to show the dialog (Activity requirement)
        val dialogIntent = Intent(this, MainActivity::class.java).apply {
            action = "$packageName.SHOW_PERMISSION_DIALOG"
            putExtra("app_id", originalRequest.callingApp)
            putExtra("permissions_json", permissionsJson)
            putExtra("is_bulk", true)
            putExtra("request_id", originalRequest.id)
            // Include full request data so MainActivity can execute signing after approval
            putExtra("nip55_request_id", originalRequest.id)
            putExtra("nip55_request_type", originalRequest.type)
            putExtra("nip55_request_params", gson.toJson(originalRequest.params))
            putExtra("nip55_request_calling_app", originalRequest.callingApp)
            // Pass the reply intent
            val replyIntent = intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
            putExtra("reply_pending_intent", replyIntent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Register callback for signing result (MainActivity will execute signing after permission approval)
        PendingNIP55ResultRegistry.registerCallback(originalRequest.id, object : PendingNIP55ResultRegistry.ResultCallback {
            override fun onResult(result: NIP55Result) {
                // MainActivity already executed the signing operation or user denied permission
                // Just forward the result to Amethyst
                if (result.ok) {
                    Log.d(TAG, "Signing completed successfully after permission approval")
                    returnResult(result.result ?: "")
                } else {
                    Log.d(TAG, "Signing failed or permission denied: ${result.reason}")
                    returnError(result.reason ?: "User denied permission")
                }
                cleanup()
            }
        })

        // Set timeout
        timeoutHandler.postDelayed({
            PendingNIP55ResultRegistry.cancelCallback(originalRequest.id)
            if (!isCompleted) {
                returnError("Permission dialog timeout")
                cleanup()
            }
        }, PROMPT_RESULT_TIMEOUT_MS)

        startActivity(dialogIntent)
        // Stay alive to receive callback and return result to Amethyst
        // No need to call moveTaskToBack - MainActivity will automatically come to foreground
        // and when we finish(), Android will automatically return to the calling app
    }

    /**
     * Show native dialog for single permission request
     */
    private fun showSinglePermissionDialog() {
        Log.d(TAG, "Showing native single permission dialog")

        // Extract event kind if this is a sign_event request
        val eventKind = if (originalRequest.type == "sign_event" && originalRequest.params.containsKey("event")) {
            try {
                val eventJson = originalRequest.params["event"]
                val eventMap = gson.fromJson(eventJson, Map::class.java)
                (eventMap["kind"] as? Double)?.toInt()
            } catch (e: Exception) {
                null
            }
        } else null

        // Must launch MainActivity to show the dialog
        val dialogIntent = Intent(this, MainActivity::class.java).apply {
            action = "$packageName.SHOW_PERMISSION_DIALOG"
            putExtra("app_id", originalRequest.callingApp)
            putExtra("request_type", originalRequest.type)
            eventKind?.let { putExtra("event_kind", it) }
            putExtra("is_bulk", false)
            putExtra("request_id", originalRequest.id)
            // Include full request data so MainActivity can execute signing after approval
            putExtra("nip55_request_id", originalRequest.id)
            putExtra("nip55_request_type", originalRequest.type)
            putExtra("nip55_request_params", gson.toJson(originalRequest.params))
            putExtra("nip55_request_calling_app", originalRequest.callingApp)
            // Pass the reply intent
            val replyIntent = intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
            putExtra("reply_pending_intent", replyIntent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Register callback for signing result (MainActivity will execute signing after permission approval)
        PendingNIP55ResultRegistry.registerCallback(originalRequest.id, object : PendingNIP55ResultRegistry.ResultCallback {
            override fun onResult(result: NIP55Result) {
                // MainActivity already executed the signing operation or user denied permission
                // Just forward the result to Amethyst
                if (result.ok) {
                    Log.d(TAG, "Signing completed successfully after permission approval")
                    returnResult(result.result ?: "")
                } else {
                    Log.d(TAG, "Signing failed or permission denied: ${result.reason}")
                    returnError(result.reason ?: "User denied permission")
                }
                cleanup()
            }
        })

        // Set timeout
        timeoutHandler.postDelayed({
            PendingNIP55ResultRegistry.cancelCallback(originalRequest.id)
            if (!isCompleted) {
                returnError("Permission dialog timeout")
                cleanup()
            }
        }, PROMPT_RESULT_TIMEOUT_MS)

        startActivity(dialogIntent)
        // Stay alive to receive callback and return result to Amethyst
        // No need to call moveTaskToBack - MainActivity will automatically come to foreground
        // and when we finish(), Android will automatically return to the calling app
    }

    /**
     * Send request to MainActivity via singleton bridge
     * For background signing, MainActivity stays in background and signs invisibly
     *
     * If MainActivity isn't running (WebView not available), launch it to foreground first
     */
    private fun launchMainActivityForFastSigning() {
        Log.d(TAG, "Sending request to MainActivity via singleton bridge")

        // Check if MainActivity WebView is available
        val webViewAvailable = MainActivity.getWebViewInstance() != null

        if (!webViewAvailable) {
            Log.d(TAG, "MainActivity WebView not available - launching MainActivity to foreground first")
            // Start foreground service to keep process alive
            NIP55SigningService.start(this)
            isMainActivityLaunched = true
        }

        // Set timeout for signing operation
        timeoutHandler.postDelayed({
            PendingNIP55ResultRegistry.cancelCallback(originalRequest.id)
            if (!isCompleted) {
                Log.e(TAG, "Fast signing timeout")
                returnError("Signing operation timeout")
                cleanup()
            }
        }, REQUEST_TIMEOUT_MS)

        // Send request via bridge
        NIP55RequestBridge.sendRequest(originalRequest) { result ->
            Log.d(TAG, "Received fast signing result: ok=${result.ok}")

            if (result.ok && result.result != null) {
                Log.d(TAG, "✓ Fast signing completed")
                returnResult(result.result)
            } else {
                Log.d(TAG, "✗ Fast signing failed: ${result.reason}")
                returnError(result.reason ?: "Signing failed")
            }

            cleanup()
        }

        // If WebView wasn't available, launch MainActivity to foreground
        // The bridge will deliver the request once MainActivity is ready
        if (!webViewAvailable) {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // Signal that this is a background signing request - show signing overlay
                putExtra("background_signing_request", true)
                putExtra("signing_request_type", originalRequest.type)
                putExtra("signing_calling_app", originalRequest.callingApp)
            }
            startActivity(mainIntent)
            Log.d(TAG, "MainActivity launched to foreground for signing - bridge will deliver request when ready")
        }
    }

    /**
     * Bring MainActivity to foreground (will process queued requests via bridge when it resumes)
     */
    private fun launchMainActivityToForeground() {
        Log.d(TAG, "Bringing MainActivity to foreground")

        // Start foreground service
        if (!isMainActivityLaunched) {
            NIP55SigningService.start(this)
            isMainActivityLaunched = true
        }

        // Simple intent to bring MainActivity to foreground
        // No extras needed - requests are already queued in the bridge
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        startActivity(mainIntent)
        Log.d(TAG, "MainActivity launch requested - bridge will deliver queued requests on resume")
    }

    /**
     * Launch MainActivity to show permission prompt and wait for result via registry
     */
    private fun launchMainActivityForPrompt() {
        Log.d(TAG, "Launching MainActivity for prompt using result registry")

        // Start foreground service to keep process alive while waiting for result
        NIP55SigningService.start(this)

        // Register callback to receive result from MainActivity (cross-task safe)
        PendingNIP55ResultRegistry.registerCallback(originalRequest.id, object : PendingNIP55ResultRegistry.ResultCallback {
            override fun onResult(result: NIP55Result) {
                Log.d(TAG, "Received result from registry: ok=${result.ok}")

                if (result.ok && result.result != null) {
                    Log.d(TAG, "✓ Prompt approved, returning result to calling app")
                    returnResult(result.result)
                } else {
                    Log.d(TAG, "✗ Prompt denied: ${result.reason}")
                    returnError(result.reason ?: "User denied permission")
                }

                cleanup()
            }
        })

        // Set timeout for user interaction
        timeoutHandler.postDelayed({
            // Cancel the pending callback if it hasn't been invoked
            PendingNIP55ResultRegistry.cancelCallback(originalRequest.id)
            if (!isCompleted) {
                Log.e(TAG, "Prompt timeout - no response from user")
                returnError("User prompt timeout")
                cleanup()
            }
        }, PROMPT_RESULT_TIMEOUT_MS)

        // Get permission status to pass to MainActivity
        val permissionStatus = checkPermission(originalRequest)

        // Launch MainActivity with proper flags for singleTask
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            action = "$packageName.NIP55_SIGNING"
            putExtra("nip55_request_id", originalRequest.id)
            putExtra("nip55_request_type", originalRequest.type)
            putExtra("nip55_request_calling_app", originalRequest.callingApp)
            putExtra("nip55_request_params", gson.toJson(originalRequest.params))
            putExtra("nip55_permission_status", permissionStatus)
            putExtra("nip55_show_prompt", permissionStatus == "prompt_required")

            // For launching from transparent activity in another task:
            // - FLAG_ACTIVITY_NEW_TASK: Required to start activity from non-activity context
            // - FLAG_ACTIVITY_CLEAR_TOP: Ensures existing MainActivity instance receives onNewIntent
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        startActivity(mainIntent)
        Log.d(TAG, "MainActivity launched, waiting for result via registry...")
    }

    // ========== NIP-55 Request Parsing ==========

    /**
     * Parse NIP-55 request from intent
     */
    private fun parseNIP55Request(intent: Intent): NIP55Request {
        val uri = intent.data
            ?: throw IllegalArgumentException("Intent data is null - no URI provided")

        if (uri.scheme != "nostrsigner") {
            throw IllegalArgumentException("Invalid URI scheme: '${uri.scheme}' (expected 'nostrsigner')")
        }

        val type = intent.getStringExtra("type")
            ?: throw IllegalArgumentException("Missing required 'type' parameter")

        if (!isValidNIP55Type(type)) {
            throw IllegalArgumentException("Unsupported NIP-55 type: '$type'")
        }

        val requestId = intent.getStringExtra("id") ?: generateRequestId()
        val callingApp = intent.getStringExtra("calling_package")
            ?: callingActivity?.packageName
            ?: "unknown"

        val params = parseNIP55RequestParams(intent, uri, type)
        validateRequiredParams(type, params)

        return NIP55Request(
            id = requestId,
            type = type,
            params = params,
            callingApp = callingApp,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Parse request parameters based on type
     */
    private fun parseNIP55RequestParams(intent: Intent, uri: Uri, type: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        when (type) {
            "get_public_key" -> {
                intent.getStringExtra("permissions")?.let { params["permissions"] = it }
            }

            "sign_event" -> {
                uri.schemeSpecificPart?.let { uriContent ->
                    if (uriContent.isNotEmpty()) {
                        try {
                            gson.fromJson(uriContent, Map::class.java)
                            params["event"] = uriContent
                        } catch (e: JsonSyntaxException) {
                            throw IllegalArgumentException("Invalid JSON in URI data")
                        }
                    }
                }
                intent.getStringExtra("current_user")?.let { params["current_user"] = it }
            }

            "nip04_encrypt", "nip44_encrypt" -> {
                uri.schemeSpecificPart?.let { params["plaintext"] = it }
                // Try both "pubkey" and "pubKey" (Amethyst uses camelCase)
                (intent.getStringExtra("pubkey") ?: intent.getStringExtra("pubKey"))?.let {
                    validatePublicKey(it)
                    params["pubkey"] = it
                }
                intent.getStringExtra("current_user")?.let { params["current_user"] = it }
            }

            "nip04_decrypt", "nip44_decrypt" -> {
                uri.schemeSpecificPart?.let { params["ciphertext"] = it }
                // Try both "pubkey" and "pubKey" (Amethyst uses camelCase)
                (intent.getStringExtra("pubkey") ?: intent.getStringExtra("pubKey"))?.let {
                    validatePublicKey(it)
                    params["pubkey"] = it
                }
                intent.getStringExtra("current_user")?.let { params["current_user"] = it }
            }

            "decrypt_zap_event" -> {
                uri.schemeSpecificPart?.let { uriContent ->
                    if (uriContent.isNotEmpty()) {
                        try {
                            gson.fromJson(uriContent, Map::class.java)
                            params["event"] = uriContent
                        } catch (e: JsonSyntaxException) {
                            throw IllegalArgumentException("Invalid JSON in URI data")
                        }
                    }
                }
                intent.getStringExtra("current_user")?.let { params["current_user"] = it }
            }
        }

        return params
    }

    /**
     * Generate unique request ID
     */
    private fun generateRequestId(): String {
        return "nip55_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }

    /**
     * Validate NIP-55 type
     */
    private fun isValidNIP55Type(type: String): Boolean {
        return type in setOf(
            "get_public_key",
            "sign_event",
            "nip04_encrypt",
            "nip04_decrypt",
            "nip44_encrypt",
            "nip44_decrypt",
            "decrypt_zap_event"
        )
    }

    /**
     * Validate required parameters
     */
    private fun validateRequiredParams(type: String, params: Map<String, String>) {
        when (type) {
            "sign_event" -> {
                if (!params.containsKey("event")) {
                    throw IllegalArgumentException("Missing required 'event' parameter")
                }
            }
            "nip04_encrypt", "nip44_encrypt" -> {
                if (!params.containsKey("pubkey") || !params.containsKey("plaintext")) {
                    throw IllegalArgumentException("Missing required parameters for $type")
                }
            }
            "nip04_decrypt", "nip44_decrypt" -> {
                if (!params.containsKey("pubkey") || !params.containsKey("ciphertext")) {
                    throw IllegalArgumentException("Missing required parameters for $type")
                }
            }
            "decrypt_zap_event" -> {
                if (!params.containsKey("event")) {
                    throw IllegalArgumentException("Missing required 'event' parameter")
                }
            }
        }
    }

    /**
     * Validate public key format
     */
    private fun validatePublicKey(pubkey: String) {
        if (pubkey.length != 64 || !pubkey.matches(Regex("^[0-9a-fA-F]+$"))) {
            throw IllegalArgumentException("Invalid public key format")
        }
    }

    // ========== Permission Checking ==========

    /**
     * Check permission status for the request with event kind support
     * Returns "allowed", "denied", or "prompt_required"
     *
     * Implements kind-aware matching:
     * - For sign_event: Check kind-specific permission first, then wildcard
     * - For other operations: Simple type matching
     */
    private fun checkPermission(request: NIP55Request): String {
        return try {
            val storageBridge = StorageBridge(this)
            val permissionsJson = storageBridge.getItem("local", "nip55_permissions_v2")
                ?: return "prompt_required"

            // Parse JSON storage wrapper and extract permissions array
            val storage = gson.fromJson(permissionsJson, PermissionStorage::class.java)
            val permissions = storage.permissions

            // Extract event kind for sign_event requests
            var eventKind: Int? = null
            if (request.type == "sign_event" && request.params.containsKey("event")) {
                try {
                    val eventJson = request.params["event"]
                    val eventMap = gson.fromJson<Map<String, Any>>(eventJson, Map::class.java)
                    eventKind = (eventMap["kind"] as? Double)?.toInt()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract event kind from request", e)
                }
            }

            // For sign_event with kind, check kind-specific permission first
            if (request.type == "sign_event" && eventKind != null) {
                val kindSpecific = permissions.find { p ->
                    p.appId == request.callingApp &&
                    p.type == request.type &&
                    p.kind == eventKind
                }

                if (kindSpecific != null) {
                    Log.d(TAG, "Found kind-specific permission: ${request.callingApp}:${request.type}:$eventKind = ${kindSpecific.allowed}")
                    return if (kindSpecific.allowed) "allowed" else "denied"
                }

                // Fall back to wildcard permission
                val wildcard = permissions.find { p ->
                    p.appId == request.callingApp &&
                    p.type == request.type &&
                    p.kind == null
                }

                if (wildcard != null) {
                    Log.d(TAG, "Found wildcard permission: ${request.callingApp}:${request.type} = ${wildcard.allowed}")
                    return if (wildcard.allowed) "allowed" else "denied"
                }
            } else {
                // For non-sign_event or sign_event without kind, simple lookup
                val permission = permissions.find { p ->
                    p.appId == request.callingApp &&
                    p.type == request.type &&
                    p.kind == null
                }

                if (permission != null) {
                    Log.d(TAG, "Found permission: ${request.callingApp}:${request.type} = ${permission.allowed}")
                    return if (permission.allowed) "allowed" else "denied"
                }
            }

            "prompt_required"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check permission", e)
            "prompt_required"
        }
    }


    // ========== Focus Management ==========

    /**
     * Return focus to calling app
     */
    private fun returnFocusToCallingApp() {
        try {
            val callingPackage = originalRequest.callingApp
            val launchIntent = packageManager.getLaunchIntentForPackage(callingPackage)

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(launchIntent)
                Log.d(TAG, "✓ Returned focus to: $callingPackage")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to return focus to calling app", e)
        }
    }
}

// NIP55Request, NIP55Result, and Permission data classes are defined in MainActivity.kt
