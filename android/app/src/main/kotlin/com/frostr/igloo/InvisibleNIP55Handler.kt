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

/**
 * Invisible NIP-55 Intent Handler - Minimal Routing Layer
 *
 * This is now a thin routing layer that:
 * 1. Receives NIP-55 intents from external apps
 * 2. Parses and validates the request
 * 3. Binds to IglooBackgroundService
 * 4. Forwards request to service for processing
 * 5. Returns result to calling app
 *
 * The heavy lifting (PWA loading, crypto operations) happens in IglooBackgroundService.
 * This handler is just responsible for the IPC boundary between external apps and our service.
 */
class InvisibleNIP55Handler : Activity() {
    companion object {
        private const val TAG = "InvisibleNIP55Handler"
        private const val REQUEST_TIMEOUT_MS = 30000L // 30 seconds
        private const val SERVICE_BIND_TIMEOUT_MS = 5000L // 5 seconds
        private const val PROMPT_RESULT_TIMEOUT_MS = 60000L // 60 seconds for user interaction
    }

    private val gson = Gson()
    private val handlerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val timeoutHandler = Handler(Looper.getMainLooper())

    private var isCompleted = false
    private lateinit var originalRequest: NIP55Request

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "NIP-55 request received from external app")

        try {
            // Parse NIP-55 request
            originalRequest = parseNIP55Request(intent)

            Log.d(TAG, "Parsed NIP-55 request: ${originalRequest.type} from ${originalRequest.callingApp}")

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
            Log.e(TAG, "Failed to parse NIP-55 request", e)
            returnError("Failed to parse request: ${e.message}")
            finish()
        }
    }


    /**
     * Return successful result to calling app
     */
    private fun returnResult(result: String) {
        if (isCompleted) return
        isCompleted = true

        val resultIntent = Intent().apply {
            // Match Amber's implementation exactly - set all extras for compatibility
            putExtra("signature", result)
            putExtra("result", result)
            putExtra("id", originalRequest.id)

            // For sign_event, include the SIGNED event (not unsigned)
            // Amethyst checks 'event' field first, so it must contain the signed event
            if (originalRequest.type == "sign_event") {
                putExtra("event", result) // Use signed event from result
            }

            // For get_public_key, include package name
            if (originalRequest.type == "get_public_key") {
                putExtra("package", packageName)
            }
        }

        Log.d(TAG, "Returning RESULT_OK to calling app:")
        Log.d(TAG, "  - Request type: ${originalRequest.type}")
        Log.d(TAG, "  - Result value: $result")
        Log.d(TAG, "  - Calling app: ${originalRequest.callingApp}")
        Log.d(TAG, "  - Result code: ${android.app.Activity.RESULT_OK}")
        Log.d(TAG, "  - Intent extras:")
        resultIntent.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                Log.d(TAG, "      $key = ${bundle.get(key)}")
            }
        }
        setResult(RESULT_OK, resultIntent)

        // Android automatically returns to calling activity when we finish()
        // DO NOT manually launch the calling app - it interferes with result delivery
        finish()
    }

    /**
     * Return error result to calling app
     */
    private fun returnError(error: String) {
        if (isCompleted) return
        isCompleted = true

        val resultIntent = Intent().apply {
            putExtra("error", error)
            putExtra("id", if (::originalRequest.isInitialized) originalRequest.id else "unknown")
        }

        Log.d(TAG, "Returning RESULT_CANCELED: $error")
        setResult(RESULT_CANCELED, resultIntent)
        finish()
    }

    /**
     * Cleanup resources
     */
    private fun cleanup() {
        timeoutHandler.removeCallbacksAndMessages(null)
        handlerScope.cancel()
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
            action = "com.frostr.igloo.SHOW_PERMISSION_DIALOG"
            putExtra("app_id", originalRequest.callingApp)
            putExtra("permissions_json", permissionsJson)
            putExtra("is_bulk", true)
            putExtra("request_id", originalRequest.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Register callback for dialog result
        PendingNIP55ResultRegistry.registerCallback(originalRequest.id, object : PendingNIP55ResultRegistry.ResultCallback {
            override fun onResult(result: NIP55Result) {
                if (result.ok) {
                    // Permission was approved - now get the public key
                    Log.d(TAG, "Permission approved - proceeding with get_public_key")

                    // Launch MainActivity to execute get_public_key
                    // Permissions are now saved, so this will proceed normally
                    val mainIntent = Intent(this@InvisibleNIP55Handler, MainActivity::class.java).apply {
                        action = "com.frostr.igloo.NIP55_SIGNING"
                        putExtra("nip55_request_id", originalRequest.id)
                        putExtra("nip55_request_type", originalRequest.type)
                        putExtra("nip55_request_calling_app", originalRequest.callingApp)
                        putExtra("nip55_request_params", gson.toJson(originalRequest.params))
                        putExtra("nip55_show_prompt", false)
                        // Pass the reply intent from the original request
                        val replyIntent = intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
                        putExtra("reply_pending_intent", replyIntent)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    startActivity(mainIntent)
                    finish()
                } else {
                    returnError(result.reason ?: "User denied permissions")
                    cleanup()
                }
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
            action = "com.frostr.igloo.SHOW_PERMISSION_DIALOG"
            putExtra("app_id", originalRequest.callingApp)
            putExtra("request_type", originalRequest.type)
            eventKind?.let { putExtra("event_kind", it) }
            putExtra("is_bulk", false)
            putExtra("request_id", originalRequest.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Register callback for dialog result
        PendingNIP55ResultRegistry.registerCallback(originalRequest.id, object : PendingNIP55ResultRegistry.ResultCallback {
            override fun onResult(result: NIP55Result) {
                if (result.ok) {
                    // Permission was approved - now execute the operation
                    Log.d(TAG, "Permission approved - proceeding with ${originalRequest.type}")

                    // Launch MainActivity to execute the operation
                    // Permissions are now saved, so this will proceed normally
                    val mainIntent = Intent(this@InvisibleNIP55Handler, MainActivity::class.java).apply {
                        action = "com.frostr.igloo.NIP55_SIGNING"
                        putExtra("nip55_request_id", originalRequest.id)
                        putExtra("nip55_request_type", originalRequest.type)
                        putExtra("nip55_request_calling_app", originalRequest.callingApp)
                        putExtra("nip55_request_params", gson.toJson(originalRequest.params))
                        putExtra("nip55_show_prompt", false)
                        // Pass the reply intent from the original request
                        val replyIntent = intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
                        putExtra("reply_pending_intent", replyIntent)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    startActivity(mainIntent)
                    finish()
                } else {
                    returnError(result.reason ?: "User denied permission")
                    cleanup()
                }
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
    }

    /**
     * Launch MainActivity for fast signing (no prompt, permission already allowed)
     * Optimized for speed with minimal visual disruption
     */
    private fun launchMainActivityForFastSigning() {
        Log.d(TAG, "Launching MainActivity for fast signing (no prompt)")

        // Register callback to receive result from MainActivity
        PendingNIP55ResultRegistry.registerCallback(originalRequest.id, object : PendingNIP55ResultRegistry.ResultCallback {
            override fun onResult(result: NIP55Result) {
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
        })

        // Set timeout for signing operation
        timeoutHandler.postDelayed({
            PendingNIP55ResultRegistry.cancelCallback(originalRequest.id)
            if (!isCompleted) {
                Log.e(TAG, "Fast signing timeout")
                returnError("Signing operation timeout")
                cleanup()
            }
        }, REQUEST_TIMEOUT_MS)

        // Launch MainActivity with optimized flags for fast signing
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.frostr.igloo.NIP55_SIGNING"
            putExtra("nip55_request_id", originalRequest.id)
            putExtra("nip55_request_type", originalRequest.type)
            putExtra("nip55_request_calling_app", originalRequest.callingApp)
            putExtra("nip55_request_params", gson.toJson(originalRequest.params))
            putExtra("nip55_permission_status", "allowed")
            putExtra("nip55_show_prompt", false) // Skip prompt UI

            // Proper flags for singleTask activity: reuse existing instance
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        startActivity(mainIntent)
        Log.d(TAG, "MainActivity launched for fast signing")
    }

    /**
     * Launch MainActivity to show permission prompt and wait for result via registry
     */
    private fun launchMainActivityForPrompt() {
        Log.d(TAG, "Launching MainActivity for prompt using result registry")

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
            action = "com.frostr.igloo.NIP55_SIGNING"
            putExtra("nip55_request_id", originalRequest.id)
            putExtra("nip55_request_type", originalRequest.type)
            putExtra("nip55_request_calling_app", originalRequest.callingApp)
            putExtra("nip55_request_params", gson.toJson(originalRequest.params))
            putExtra("nip55_permission_status", permissionStatus)
            putExtra("nip55_show_prompt", permissionStatus == "prompt_required")

            // Proper flags for singleTask activity: reuse existing instance
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                intent.getStringExtra("pubkey")?.let {
                    validatePublicKey(it)
                    params["pubkey"] = it
                }
                intent.getStringExtra("current_user")?.let { params["current_user"] = it }
            }

            "nip04_decrypt", "nip44_decrypt" -> {
                uri.schemeSpecificPart?.let { params["ciphertext"] = it }
                intent.getStringExtra("pubkey")?.let {
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

            val permissionListType = object : com.google.gson.reflect.TypeToken<Array<Permission>>() {}.type
            val permissions: Array<Permission> = gson.fromJson<Array<Permission>>(permissionsJson, permissionListType)

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

    // NOTE: Background service signing code removed
    // Background signing isn't possible anyway, so IglooBackgroundService was deleted
    // All signing now happens through MainActivity with user present

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

// NIP55Request, NIP55Result, and Permission data classes are defined in IglooBackgroundService.kt
