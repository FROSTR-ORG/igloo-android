package com.frostr.igloo

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.frostr.igloo.bridges.StorageBridge
import com.frostr.igloo.health.IglooHealthManager
import com.frostr.igloo.services.NIP55HandlerService
import com.frostr.igloo.util.AuditLogger
import com.frostr.igloo.util.RequestIdGenerator
import com.frostr.igloo.debug.NIP55TraceContext
import com.frostr.igloo.debug.NIP55Checkpoints
import com.frostr.igloo.debug.NIP55Errors
import com.frostr.igloo.debug.NIP55Metrics

/**
 * Invisible NIP-55 Intent Handler - Health-Based Routing
 *
 * This handler routes NIP-55 requests based on the health state of the WebView:
 *
 * 1. If healthy (WebView ready): Submit to IglooHealthManager, wait for callback, return result
 * 2. If unhealthy (WebView not ready): Become active handler, start protective service,
 *    launch MainActivity to wake up WebView, then process queued requests
 *
 * Key insight: WebView cannot survive in background (Android throttles it within seconds).
 * Instead of polling and waiting, we use health-based routing with focus-switch.
 *
 * The singleton handler pattern ensures only ONE handler launches MainActivity at a time.
 * Other handlers queue their requests and finish immediately.
 */
class InvisibleNIP55Handler : Activity() {
    companion object {
        private const val TAG = "InvisibleNIP55Handler"
        private const val PROMPT_RESULT_TIMEOUT_MS = 60000L // 60 seconds for user interaction

        // Instance tracking for debugging concurrent handlers
        @Volatile
        private var instanceCounter = 0
        @Volatile
        private var activeInstances = mutableSetOf<Int>()

        // Cached StorageBridge to avoid slow EncryptedSharedPreferences init on every request
        @Volatile
        private var cachedStorageBridge: StorageBridge? = null

        @Synchronized
        private fun getStorageBridge(context: Context): StorageBridge {
            return cachedStorageBridge ?: StorageBridge(context.applicationContext).also {
                cachedStorageBridge = it
                Log.d(TAG, "Created cached StorageBridge")
            }
        }

        @Synchronized
        private fun registerInstance(): Int {
            val id = ++instanceCounter
            activeInstances.add(id)
            Log.d(TAG, ">>> HANDLER CREATED: instance #$id (active: ${activeInstances.size})")
            return id
        }

        @Synchronized
        private fun unregisterInstance(id: Int) {
            activeInstances.remove(id)
            Log.d(TAG, "<<< HANDLER DESTROYED: instance #$id (remaining: ${activeInstances.size})")
        }
    }

    // Unique ID for this handler instance
    private var instanceId: Int = 0

    // Whether this handler is the active one (responsible for launching MainActivity)
    private var isActiveHandler = false

    private val gson = Gson()
    private val timeoutHandler = Handler(Looper.getMainLooper())

    private var isCompleted = false
    private lateinit var originalRequest: NIP55Request

    // Trace context for request tracking
    private var traceContext: NIP55TraceContext? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register this instance for tracking
        instanceId = registerInstance()

        // Pre-initialize StorageBridge in background if not cached (avoids delay in checkPermission)
        if (cachedStorageBridge == null) {
            Thread {
                getStorageBridge(applicationContext)
            }.start()
        }

        Log.d(TAG, "NIP-55 request received (onCreate) [instance #$instanceId]")
        processNIP55Intent(intent)
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)

        if (newIntent == null) {
            Log.w(TAG, "onNewIntent called with null intent [instance #$instanceId]")
            return
        }

        Log.d(TAG, "NIP-55 request received (onNewIntent) [instance #$instanceId]")

        // Reset state for new request
        isCompleted = false
        setIntent(newIntent)
        processNIP55Intent(newIntent)
    }

    private fun processNIP55Intent(intent: Intent) {
        try {
            // Parse NIP-55 request
            originalRequest = parseNIP55Request(intent)

            // Create trace context for this request
            traceContext = NIP55TraceContext.create(
                requestId = originalRequest.id,
                operationType = originalRequest.type,
                callingApp = originalRequest.callingApp,
                entryPoint = NIP55TraceContext.EntryPoint.INTENT
            )

            Log.d(TAG, "Received: ${originalRequest.type} (id=${originalRequest.id}) from ${originalRequest.callingApp}")

            // Record metrics
            NIP55Metrics.recordRequest(originalRequest.type, "INTENT", originalRequest.callingApp)

            // Audit log the request
            auditLogRequest(originalRequest)

            // Special handling for get_public_key with bulk permissions
            if (originalRequest.type == "get_public_key" && originalRequest.params.containsKey("permissions")) {
                Log.d(TAG, "get_public_key with bulk permissions - showing native dialog")
                traceContext?.checkpoint(NIP55Checkpoints.PERMISSION_CHECK, "result" to "bulk_prompt")
                showBulkPermissionDialog()
                return
            }

            // Check permissions
            val permissionStatus = checkPermission(originalRequest)
            traceContext?.checkpoint(NIP55Checkpoints.PERMISSION_CHECK, "result" to permissionStatus)
            Log.d(TAG, "Permission status: $permissionStatus")

            when (permissionStatus) {
                "denied" -> {
                    Log.d(TAG, "Permission denied - returning error")
                    traceContext?.error(NIP55Errors.PERMISSION, "Permission denied by user rule")
                    returnError("Permission denied")
                }
                "allowed" -> {
                    // Permission granted - submit via health-based routing
                    Log.d(TAG, "Permission allowed - submitting via health manager")
                    submitViaHealthManager()
                }
                "prompt_required" -> {
                    // Show native permission dialog
                    Log.d(TAG, "Permission prompt required - showing native dialog")
                    showSinglePermissionDialog()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse NIP-55 request", e)
            returnError("Failed to parse request: ${e.message}")
            finish()
        }
    }

    /**
     * Submit request via IglooHealthManager with health-based routing.
     *
     * Flow:
     * 1. If healthy: Request is processed immediately via AsyncBridge
     * 2. If unhealthy: Request is queued, and we may trigger MainActivity wakeup
     */
    private fun submitViaHealthManager() {
        val healthManager = IglooHealthManager

        // Check health state
        if (healthManager.isHealthy) {
            // WebView is ready - submit and wait for callback
            Log.d(TAG, "System healthy - submitting directly")
            traceContext?.checkpoint("HEALTH_SUBMIT", "healthy" to true)

            submitToHealthManager()
            return
        }

        // System is unhealthy - need to wake up Igloo
        Log.d(TAG, "System unhealthy - attempting to become active handler")
        traceContext?.checkpoint("HEALTH_SUBMIT", "healthy" to false)

        // Try to become the active handler (only one should launch MainActivity)
        if (healthManager.tryBecomeActiveHandler(this)) {
            // We are the active handler - start protective service and launch MainActivity
            isActiveHandler = true
            Log.d(TAG, "Became active handler - starting service and waking Igloo")

            // Start protective service to prevent being killed while waiting
            NIP55HandlerService.start(this)

            // Submit our request (will be queued since unhealthy)
            submitToHealthManager()

            // Launch MainActivity to wake up WebView
            launchMainActivityForWakeup()

        } else {
            // Another handler is already active - just submit and wait
            Log.d(TAG, "Another handler is active - submitting and waiting")

            submitToHealthManager()
        }
    }

    /**
     * Submit request to IglooHealthManager.
     */
    private fun submitToHealthManager() {
        traceContext?.checkpoint(NIP55Checkpoints.QUEUED)

        val accepted = IglooHealthManager.submit(originalRequest) { result ->
            Log.d(TAG, "Health manager callback: ok=${result.ok}")

            if (result.ok && result.result != null) {
                returnResult(result.result)
            } else {
                val reason = result.reason ?: "Signing failed"

                // If node is locked, launch MainActivity to show unlock UI
                if (reason.contains("locked", ignoreCase = true)) {
                    Log.d(TAG, "Node is locked - launching MainActivity for unlock")
                    launchMainActivityForUnlock()
                } else {
                    returnError(reason)
                }
            }
        }

        if (!accepted) {
            Log.e(TAG, "Request rejected by health manager")
            // Callback already invoked with error, cleanup will happen in return methods
        }
    }

    /**
     * Launch MainActivity to wake up WebView.
     * Used when health is stale and we need to re-establish the WebView.
     */
    private fun launchMainActivityForWakeup() {
        Log.d(TAG, "Launching MainActivity for wakeup")

        val wakeupIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("nip55_wakeup", true)
            putExtra("signing_request_type", originalRequest.type)
            putExtra("signing_calling_app", originalRequest.callingApp)
        }
        startActivity(wakeupIntent)
    }

    /**
     * Return successful result to calling app
     */
    private fun returnResult(result: String) {
        if (isCompleted) return
        isCompleted = true

        // Complete trace
        traceContext?.let { trace ->
            trace.checkpoint(NIP55Checkpoints.RESULT_SENT, "success" to true)
            trace.complete(success = true, resultSize = result.length)
        }

        Log.d(TAG, "Returning RESULT_OK to calling app")

        val resultIntent = Intent().apply {
            putExtra("id", originalRequest.id)
            putExtra("package", packageName)

            when (originalRequest.type) {
                "sign_event" -> {
                    putExtra("result", result)
                    putExtra("signature", result)
                    putExtra("event", result)
                }
                else -> {
                    putExtra("result", result)
                }
            }
        }

        setResult(RESULT_OK, resultIntent)
        finishAndReturnFocus()
    }

    /**
     * Return error result to calling app
     */
    private fun returnError(error: String) {
        if (isCompleted) return
        isCompleted = true

        // Complete trace
        if (::originalRequest.isInitialized) {
            traceContext?.let { trace ->
                trace.checkpoint(NIP55Checkpoints.RESULT_SENT, "success" to false, "error" to error)
                trace.complete(success = false)
            }
        }

        Log.d(TAG, "Returning RESULT_CANCELED: $error")

        val resultIntent = Intent().apply {
            putExtra("error", error)
            putExtra("id", if (::originalRequest.isInitialized) originalRequest.id else "unknown")
        }

        setResult(RESULT_CANCELED, resultIntent)
        finishAndReturnFocus()
    }

    /**
     * Finish activity and return focus appropriately
     */
    private fun finishAndReturnFocus() {
        cleanup()

        // Check if user is actively in Igloo - if so, don't return to calling app
        if (MainActivity.userActiveInIgloo) {
            Log.d(TAG, "User is active in Igloo - not returning focus to calling app")
            moveTaskToBack(true)
        } else {
            finish()
        }
    }

    /**
     * Audit log the request
     */
    private fun auditLogRequest(request: NIP55Request) {
        when (request.type) {
            "sign_event" -> {
                request.params["event"]?.let { eventJson ->
                    AuditLogger.logSignEvent(
                        context = this,
                        callingApp = request.callingApp,
                        eventJson = eventJson,
                        requestId = request.id
                    )
                }
            }
            "nip04_encrypt", "nip04_decrypt", "nip44_encrypt", "nip44_decrypt" -> {
                request.params["pubkey"]?.let { pubkey ->
                    AuditLogger.logCryptoOperation(
                        context = this,
                        callingApp = request.callingApp,
                        operationType = request.type,
                        pubkey = pubkey,
                        requestId = request.id
                    )
                }
            }
        }
    }

    /**
     * Cleanup resources
     */
    private fun cleanup() {
        timeoutHandler.removeCallbacksAndMessages(null)

        // Release active handler slot and stop service if we were active
        if (isActiveHandler) {
            IglooHealthManager.releaseActiveHandler(this)
            NIP55HandlerService.stop(this)
            isActiveHandler = false
        }
    }

    override fun onDestroy() {
        unregisterInstance(instanceId)
        cleanup()
        super.onDestroy()
    }

    // ========== Permission Dialogs ==========

    /**
     * Show native dialog for bulk permission request (get_public_key with permissions array)
     */
    private fun showBulkPermissionDialog() {
        Log.d(TAG, "Showing native bulk permission dialog")

        val permissionsJson = originalRequest.params["permissions"] ?: run {
            returnError("Missing permissions parameter")
            return
        }

        val dialogIntent = Intent(this, MainActivity::class.java).apply {
            action = "$packageName.SHOW_PERMISSION_DIALOG"
            putExtra("app_id", originalRequest.callingApp)
            putExtra("permissions_json", permissionsJson)
            putExtra("is_bulk", true)
            putExtra("request_id", originalRequest.id)
            putExtra("nip55_request_id", originalRequest.id)
            putExtra("nip55_request_type", originalRequest.type)
            putExtra("nip55_request_params", gson.toJson(originalRequest.params))
            putExtra("nip55_request_calling_app", originalRequest.callingApp)
            val replyIntent = intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
            putExtra("reply_pending_intent", replyIntent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        registerPermissionCallback()
        startActivity(dialogIntent)
    }

    /**
     * Show native dialog for single permission request
     */
    private fun showSinglePermissionDialog() {
        Log.d(TAG, "Showing native single permission dialog")

        val eventKind = if (originalRequest.type == "sign_event" && originalRequest.params.containsKey("event")) {
            try {
                val eventJson = originalRequest.params["event"]
                val eventMap = gson.fromJson(eventJson, Map::class.java)
                (eventMap["kind"] as? Double)?.toInt()
            } catch (e: Exception) {
                null
            }
        } else null

        val dialogIntent = Intent(this, MainActivity::class.java).apply {
            action = "$packageName.SHOW_PERMISSION_DIALOG"
            putExtra("app_id", originalRequest.callingApp)
            putExtra("request_type", originalRequest.type)
            eventKind?.let { putExtra("event_kind", it) }
            putExtra("is_bulk", false)
            putExtra("request_id", originalRequest.id)
            putExtra("nip55_request_id", originalRequest.id)
            putExtra("nip55_request_type", originalRequest.type)
            putExtra("nip55_request_params", gson.toJson(originalRequest.params))
            putExtra("nip55_request_calling_app", originalRequest.callingApp)
            val replyIntent = intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
            putExtra("reply_pending_intent", replyIntent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        registerPermissionCallback()
        startActivity(dialogIntent)
    }

    /**
     * Register callback for permission dialog result.
     * Submits the request to IglooHealthManager so results can be delivered back.
     */
    private fun registerPermissionCallback() {
        // Submit the request to register the callback - MainActivity will process it after permission is granted
        val accepted = IglooHealthManager.submit(originalRequest) { result ->
            Log.d(TAG, "Permission dialog callback: ok=${result.ok}")

            if (result.ok && result.result != null) {
                returnResult(result.result)
            } else {
                val reason = result.reason ?: "Permission request failed"
                returnError(reason)
            }
        }

        if (!accepted) {
            Log.e(TAG, "Request rejected by health manager for permission dialog")
        }

        // Set timeout for user interaction
        timeoutHandler.postDelayed({
            if (!isCompleted) {
                returnError("Permission dialog timeout")
            }
        }, PROMPT_RESULT_TIMEOUT_MS)
    }

    /**
     * Launch MainActivity to show unlock UI when node is locked.
     */
    private fun launchMainActivityForUnlock() {
        Log.d(TAG, "Launching MainActivity for unlock with pending request")

        val unlockIntent = Intent(this, MainActivity::class.java).apply {
            action = "$packageName.UNLOCK_AND_SIGN"
            putExtra("nip55_request_id", originalRequest.id)
            putExtra("nip55_request_type", originalRequest.type)
            putExtra("nip55_request_params", gson.toJson(originalRequest.params))
            putExtra("nip55_request_calling_app", originalRequest.callingApp)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(unlockIntent)

        // Set timeout - give user time to unlock
        timeoutHandler.postDelayed({
            if (!isCompleted) {
                returnError("Unlock timeout - please try again")
            }
        }, PROMPT_RESULT_TIMEOUT_MS)
    }

    // ========== Helper Methods ==========

    /**
     * Extract event kind from a NIP-55 request (for sign_event requests)
     */
    private fun extractEventKind(request: NIP55Request): Int? {
        if (request.type != "sign_event") return null

        return try {
            val eventJson = request.params["event"] ?: return null
            val eventMap = gson.fromJson(eventJson, Map::class.java)
            (eventMap["kind"] as? Double)?.toInt()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract event kind", e)
            null
        }
    }

    // ========== NIP-55 Request Parsing ==========

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

        val requestId = intent.getStringExtra("id") ?: RequestIdGenerator.generate()
        val callingApp = intent.getStringExtra("calling_package")
            ?: callingActivity?.packageName
            ?: referrer?.host
            ?: getCallingPackageFromBinder()
            ?: "unknown"

        if (callingApp == "unknown") {
            Log.w(TAG, "Could not determine calling app - callingActivity=${callingActivity}, referrer=${referrer}")
        }

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
                (intent.getStringExtra("pubkey") ?: intent.getStringExtra("pubKey"))?.let {
                    validatePublicKey(it)
                    params["pubkey"] = it
                }
                intent.getStringExtra("current_user")?.let { params["current_user"] = it }
            }

            "nip04_decrypt", "nip44_decrypt" -> {
                uri.schemeSpecificPart?.let { params["ciphertext"] = it }
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

    private fun validatePublicKey(pubkey: String) {
        if (pubkey.length != 64 || !pubkey.matches(Regex("^[0-9a-fA-F]+$"))) {
            throw IllegalArgumentException("Invalid public key format")
        }
    }

    // ========== Permission Checking ==========

    private fun checkPermission(request: NIP55Request): String {
        val storageBridge = getStorageBridge(this)
        val checker = com.frostr.igloo.services.PermissionChecker(storageBridge)

        val eventKind = if (request.type == "sign_event" && request.params.containsKey("event")) {
            checker.extractEventKind(request.params["event"])
        } else {
            null
        }

        val result = checker.checkPermission(request.callingApp, request.type, eventKind)
        return checker.toStatusString(result)
    }

    private fun getCallingPackageFromBinder(): String? {
        return try {
            val callingUid = android.os.Binder.getCallingUid()
            if (callingUid == android.os.Process.myUid()) {
                null
            } else {
                val packages = packageManager.getPackagesForUid(callingUid)
                packages?.firstOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get calling package from Binder", e)
            null
        }
    }
}
