package com.frostr.igloo

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.frostr.igloo.debug.NIP55DebugLogger
import com.frostr.igloo.bridges.StorageBridge
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.util.UUID

// Permission data class is defined in IglooContentProvider.kt

/**
 * Invisible NIP-55 Intent Handler
 *
 * Lightweight activity that processes NIP-55 intents using direct Intent-based communication.
 * Replaces the complex HTTP IPC + background service architecture with simple startActivityForResult.
 *
 * New Architecture:
 * 1. Receives NIP-55 intent from external apps
 * 2. Parses and validates the NIP-55 request
 * 3. Forwards directly to MainActivity with startActivityForResult
 * 4. Receives result and returns to calling app
 * 5. No HTTP IPC, no background services, no ContentProvider
 */
class InvisibleNIP55Handler : Activity() {
    companion object {
        private const val TAG = "InvisibleNIP55Handler"
        private const val REQUEST_TIMEOUT_MS = 30000L // 30 seconds timeout
    }

    private val gson = Gson()
    private val startTime = System.currentTimeMillis()
    private lateinit var originalRequest: NIP55Request
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var isCompleted = false
    private var replyReceiver: BroadcastReceiver? = null
    private val uniqueReplyAction = "com.frostr.igloo.NIP55_REPLY_" + UUID.randomUUID().toString()
    private var isBound = false

    // ServiceConnection for keep-alive service (optional binding for lifecycle sync)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Keep-alive service connected - process stays alive")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Keep-alive service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NIP55DebugLogger.logIntent("RECEIVED", intent, mapOf(
            "callingActivity" to (callingActivity?.className ?: "unknown"),
            "taskId" to taskId.toString(),
            "process" to "native_handler"
        ))

        try {
            originalRequest = parseNIP55Request(intent)

            NIP55DebugLogger.logFlowStart(
                originalRequest.id,
                originalRequest.callingApp,
                originalRequest.type
            )

            // Check permissions upfront for unified permission checking
            Log.d(TAG, "=== UNIFIED PERMISSION CHECKING START ===")
            val permissionStatus = checkPermission(originalRequest)
            Log.d(TAG, "Permission status for ${originalRequest.callingApp}:${originalRequest.type} = $permissionStatus")
            Log.d(TAG, "=== UNIFIED PERMISSION CHECKING END ===")

            when (permissionStatus) {
                "denied" -> {
                    // Permission denied - return error immediately
                    Log.d(TAG, "Permission denied for ${originalRequest.callingApp}:${originalRequest.type}")
                    returnError("Permission denied")
                    return
                }
                "allowed" -> {
                    // Permission allowed - start background signing service in :main process
                    Log.d(TAG, "Permission allowed for ${originalRequest.callingApp}:${originalRequest.type} - starting background signing service")
                    startBackgroundSigningService()
                    return
                }
                "prompt_required" -> {
                    // No permission - requires user prompt
                    Log.d(TAG, "No permission found for ${originalRequest.callingApp}:${originalRequest.type} - user prompt required")
                }
            }

            // Start foreground service to keep process alive during NIP-55 operation
            val serviceIntent = Intent(this, Nip55KeepAliveService::class.java)
            startService(serviceIntent)
            // Optional bind for tighter lifecycle control
            bindService(serviceIntent, connection, BIND_AUTO_CREATE)
            isBound = true
            Log.d(TAG, "Started keep-alive service for request: ${originalRequest.id}")

            // Register BroadcastReceiver for reply
            val filter = IntentFilter(uniqueReplyAction)
            replyReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.d(TAG, "Received reply broadcast")
                    isCompleted = true
                    timeoutHandler.removeCallbacksAndMessages(null)


                    val resultCode = intent?.getIntExtra("result_code", RESULT_CANCELED) ?: RESULT_CANCELED
                    val resultData = intent?.getStringExtra("result_data")
                    val error = intent?.getStringExtra("error")

                    if (resultCode == RESULT_OK && resultData != null) {
                        Log.d(TAG, "Received successful result from MainActivity")
                        returnResult(resultData)
                    } else {
                        Log.d(TAG, "Received error result from MainActivity: $error")
                        returnError(error ?: "Request failed")
                    }

                    cleanupAndFinish()
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(replyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(replyReceiver, filter)
            }

            // Create PendingIntent for reply
            val replyIntent = Intent(uniqueReplyAction).apply {
                setPackage(packageName) // Restrict to our app for security
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Forward to MainActivity with PendingIntent
            val cleanIntent = Intent(this, MainActivity::class.java).apply {
                action = "com.frostr.igloo.NIP55_SIGNING"
                putExtra("nip55_request", gson.toJson(originalRequest))
                putExtra("calling_app", originalRequest.callingApp)
                putExtra("reply_pending_intent", replyPendingIntent)
                putExtra("reply_broadcast_action", uniqueReplyAction) // Pass broadcast action to MainActivity

                // Check if this is a Content Resolver request (background) vs Intent request (manual)
                val isContentResolver = intent.getBooleanExtra("is_content_resolver", false)
                if (isContentResolver) {
                    putExtra("is_content_resolver", true)
                }

                // Set appropriate flags based on permission status and request type
                // Only bring app to foreground when user prompt is actually needed
                val needsUserInteraction = (permissionStatus == "prompt_required")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                       Intent.FLAG_ACTIVITY_CLEAR_TOP or
                       Intent.FLAG_ACTIVITY_SINGLE_TOP or
                       if (needsUserInteraction) {
                           // User interaction needed: Come to foreground
                           0
                       } else {
                           // Auto-approval or denial: Stay in background
                           Intent.FLAG_ACTIVITY_NO_USER_ACTION
                       }
            }

            NIP55DebugLogger.logIntent("FORWARDING", cleanIntent, mapOf(
                "originalFlags" to "0x${Integer.toHexString(intent.flags)}",
                "cleanFlags" to "0x${Integer.toHexString(cleanIntent.flags)}",
                "processIsolation" to "native_handler -> main",
                "replyAction" to uniqueReplyAction,
                "permissionStatus" to permissionStatus,
                "needsUserInteraction" to (permissionStatus == "prompt_required")
            ))

            startActivity(cleanIntent)

            // Set up timeout handler
            timeoutHandler.postDelayed({
                if (!isCompleted) {

                    NIP55DebugLogger.logError("REQUEST_TIMEOUT", Exception("Request timed out after ${REQUEST_TIMEOUT_MS}ms"))
                    NIP55DebugLogger.logFlowEnd(originalRequest.id, false, "timeout")
                    returnError("Request timeout")
                    cleanupAndFinish()
                }
            }, REQUEST_TIMEOUT_MS)

        } catch (e: Exception) {
            NIP55DebugLogger.logError("PARSE_INTENT", e)
            returnError("Failed to parse intent: ${e.message}")
            finish()
        }
    }


    /**
     * Parse NIP-55 request from intent following official NIP-55 specification
     */
    private fun parseNIP55Request(intent: Intent): NIP55Request {
        // Log the received intent for debugging
        NIP55DebugLogger.logIntent("PARSING", intent, mapOf(
            "hasData" to (intent.data != null),
            "scheme" to (intent.data?.scheme ?: "null"),
            "uriString" to (intent.data?.toString() ?: "null"),
            "hasTypeExtra" to intent.hasExtra("type"),
            "typeExtra" to (intent.getStringExtra("type") ?: "null")
        ))

        val uri = intent.data
        if (uri == null) {
            val error = "Intent data is null - no URI provided"
            NIP55DebugLogger.logError("INVALID_URI", Exception(error))
            throw IllegalArgumentException(error)
        }

        // Validate NIP-55 URI scheme
        if (uri.scheme != "nostrsigner") {
            val error = "Invalid URI scheme: '${uri.scheme}' (expected 'nostrsigner')"
            NIP55DebugLogger.logError("INVALID_SCHEME", Exception(error))
            throw IllegalArgumentException(error)
        }

        // Extract type from Intent extras (NIP-55 spec compliant)
        val type = intent.getStringExtra("type")
        if (type.isNullOrEmpty()) {
            val error = "Missing required 'type' parameter in Intent extras"
            NIP55DebugLogger.logError("MISSING_TYPE", Exception(error))
            throw IllegalArgumentException(error)
        }

        // Validate type is supported
        if (!isValidNIP55Type(type)) {
            val error = "Unsupported NIP-55 type: '$type'"
            NIP55DebugLogger.logError("UNSUPPORTED_TYPE", Exception(error))
            throw IllegalArgumentException(error)
        }

        // Extract request ID from Intent extras
        val requestId = intent.getStringExtra("id") ?: generateRequestId()

        // Extract calling app info
        val callingApp = intent.getStringExtra("calling_package")
            ?: callingActivity?.packageName
            ?: "com.adb.shell.test"  // Meaningful identifier for manual testing

        // Parse and validate parameters based on type using NIP-55 spec format
        val params = try {
            parseNIP55RequestParams(intent, uri, type)
        } catch (e: Exception) {
            val error = "Failed to parse request parameters for type '$type': ${e.message}"
            NIP55DebugLogger.logError("INVALID_PARAMS", Exception(error, e))
            throw IllegalArgumentException(error)
        }

        // Validate required parameters are present
        validateRequiredParams(type, params)

        val request = NIP55Request(
            id = requestId,
            type = type,
            params = params,
            callingApp = callingApp,
            timestamp = System.currentTimeMillis()
        )

        NIP55DebugLogger.logIntent("PARSED_SUCCESS", intent, mapOf(
            "requestId" to requestId,
            "type" to type,
            "callingApp" to callingApp,
            "paramCount" to params.size,
            "uriData" to (uri.toString()),
            "uriSchemeSpecificPart" to (uri.schemeSpecificPart ?: "null")
        ))

        return request
    }

    /**
     * Parse request parameters following NIP-55 specification
     * - get_public_key: Uri.parse("nostrsigner:") + Intent extras
     * - sign_event: Uri.parse("nostrsigner:$eventJson") + Intent extras
     * - encrypt/decrypt: Uri.parse("nostrsigner:$content") + Intent extras
     */
    private fun parseNIP55RequestParams(intent: Intent, uri: Uri, type: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        when (type) {
            "get_public_key" -> {
                // NIP-55: Uri.parse("nostrsigner:") with optional permissions in extras
                intent.getStringExtra("permissions")?.let { permissions ->
                    params["permissions"] = permissions
                }
                // For get_public_key, URI should just be "nostrsigner:" with no additional content
            }

            "sign_event" -> {
                // NIP-55: Uri.parse("nostrsigner:$eventJson") + extras
                val uriContent = uri.schemeSpecificPart
                if (!uriContent.isNullOrEmpty()) {
                    // Event JSON is in the URI data itself
                    try {
                        gson.fromJson(uriContent, Map::class.java)
                        params["event"] = uriContent
                    } catch (e: JsonSyntaxException) {
                        throw IllegalArgumentException("Invalid JSON in URI data: $uriContent")
                    }
                }

                // Additional parameters from Intent extras
                intent.getStringExtra("current_user")?.let { currentUser ->
                    params["current_user"] = currentUser
                }
            }

            "nip04_encrypt", "nip44_encrypt" -> {
                // NIP-55: Uri.parse("nostrsigner:$plaintext") + extras
                val uriContent = uri.schemeSpecificPart
                if (!uriContent.isNullOrEmpty()) {
                    params["plaintext"] = uriContent
                }

                // Required pubkey from Intent extras
                intent.getStringExtra("pubkey")?.let { pubkey ->
                    validatePublicKey(pubkey)
                    params["pubkey"] = pubkey
                }

                intent.getStringExtra("current_user")?.let { currentUser ->
                    params["current_user"] = currentUser
                }
            }

            "nip04_decrypt", "nip44_decrypt" -> {
                // NIP-55: Uri.parse("nostrsigner:$encryptedText") + extras
                val uriContent = uri.schemeSpecificPart
                if (!uriContent.isNullOrEmpty()) {
                    params["ciphertext"] = uriContent
                }

                // Required pubkey from Intent extras
                intent.getStringExtra("pubkey")?.let { pubkey ->
                    validatePublicKey(pubkey)
                    params["pubkey"] = pubkey
                }

                intent.getStringExtra("current_user")?.let { currentUser ->
                    params["current_user"] = currentUser
                }
            }

            "decrypt_zap_event" -> {
                // NIP-55: Uri.parse("nostrsigner:$eventJson") + extras
                val uriContent = uri.schemeSpecificPart
                if (!uriContent.isNullOrEmpty()) {
                    try {
                        gson.fromJson(uriContent, Map::class.java)
                        params["event"] = uriContent
                    } catch (e: JsonSyntaxException) {
                        throw IllegalArgumentException("Invalid JSON in URI data: $uriContent")
                    }
                }

                intent.getStringExtra("current_user")?.let { currentUser ->
                    params["current_user"] = currentUser
                }
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
     * Return successful result to calling app following NIP-55 specification
     */
    private fun returnResult(result: String) {
        val resultIntent = Intent().apply {
            // Try multiple field names that different NIP-55 implementations might expect
            putExtra("result", result)
            putExtra("signature", result)  // Some apps might expect this field name
            putExtra("pubkey", result)     // For get_public_key specifically
            putExtra("id", originalRequest.id)
            putExtra("package", packageName)

            // For sign_event, also include the signed event JSON if available
            if (originalRequest.type == "sign_event") {
                // TODO: Include signed event JSON in "event" field when available
                putExtra("event", originalRequest.params["event"] ?: "")
            }
        }

        // Log all extras being sent for debugging
        Log.d(TAG, "=== RETURNING RESULT TO AMETHYST ===")
        Log.d(TAG, "Request type: ${originalRequest.type}")
        Log.d(TAG, "Request ID: ${originalRequest.id}")
        Log.d(TAG, "Result: $result")
        Log.d(TAG, "Intent extras:")
        resultIntent.extras?.let { extras ->
            for (key in extras.keySet()) {
                Log.d(TAG, "  $key = ${extras.get(key)}")
            }
        }
        Log.d(TAG, "Setting RESULT_OK")

        setResult(RESULT_OK, resultIntent)
        Log.d(TAG, "Returned result to calling app: ${result.take(20)}...")

        // Delay focus return to allow setResult to complete properly
        Handler(Looper.getMainLooper()).postDelayed({
            returnFocusToCallingApp()
            finish()
        }, 100)
    }

    /**
     * Return error result to calling app
     */
    private fun returnError(error: String) {
        val resultIntent = Intent().apply {
            putExtra("error", error)
            if (::originalRequest.isInitialized) {
                putExtra("id", originalRequest.id)
            } else {
                putExtra("id", "unknown")
            }
        }
        setResult(RESULT_CANCELED, resultIntent)

        NIP55DebugLogger.logError("RETURNING_ERROR", Exception(error), mapOf(
            "requestId" to if (::originalRequest.isInitialized) originalRequest.id else "unknown",
            "errorMessage" to error
        ))

        Log.d(TAG, "Returned error result: $error")

        // Finish the activity to clean up the native_handler process
        finish()
    }

    /**
     * Centralized cleanup and finish method
     * Handles service cleanup, broadcast receiver cleanup, and activity finish
     */
    private fun cleanupAndFinish() {
        // Stop keep-alive service
        val serviceIntent = Intent(this, Nip55KeepAliveService::class.java)
        if (isBound) {
            try {
                unbindService(connection)
                isBound = false
                Log.d(TAG, "Unbound from keep-alive service")
            } catch (e: IllegalArgumentException) {
                // Service was not bound, ignore
                Log.w(TAG, "Service was not bound during cleanup")
            }
        }
        stopService(serviceIntent)
        Log.d(TAG, "Stopped keep-alive service")

        // Clean up BroadcastReceiver
        replyReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Unregistered broadcast receiver")
            } catch (e: IllegalArgumentException) {
                // Receiver was already unregistered, ignore
                Log.w(TAG, "Receiver was already unregistered during cleanup")
            }
        }

        // Finish activity and clean up the native_handler process
        finish()
    }

    /**
     * Validate if the NIP-55 type is supported
     */
    private fun isValidNIP55Type(type: String): Boolean {
        val supportedTypes = setOf(
            "get_public_key",
            "sign_event",
            "nip04_encrypt",
            "nip04_decrypt",
            "nip44_encrypt",
            "nip44_decrypt",
            "decrypt_zap_event"
        )
        return type in supportedTypes
    }

    /**
     * Validate required parameters are present for the given type
     */
    private fun validateRequiredParams(type: String, params: Map<String, String>) {
        when (type) {
            "get_public_key" -> {
                // No required parameters for get_public_key
            }
            "sign_event" -> {
                if (!params.containsKey("event")) {
                    throw IllegalArgumentException("Missing required 'event' parameter for sign_event")
                }
            }
            "nip04_encrypt", "nip44_encrypt" -> {
                if (!params.containsKey("pubkey")) {
                    throw IllegalArgumentException("Missing required 'pubkey' parameter for $type")
                }
                if (!params.containsKey("plaintext")) {
                    throw IllegalArgumentException("Missing required 'plaintext' parameter for $type")
                }
            }
            "nip04_decrypt", "nip44_decrypt" -> {
                if (!params.containsKey("pubkey")) {
                    throw IllegalArgumentException("Missing required 'pubkey' parameter for $type")
                }
                if (!params.containsKey("ciphertext")) {
                    throw IllegalArgumentException("Missing required 'ciphertext' parameter for $type")
                }
            }
            "decrypt_zap_event" -> {
                if (!params.containsKey("event")) {
                    throw IllegalArgumentException("Missing required 'event' parameter for decrypt_zap_event")
                }
            }
        }
    }

    /**
     * Validate public key format (hex, 64 characters)
     */
    private fun validatePublicKey(pubkey: String) {
        if (pubkey.length != 64) {
            throw IllegalArgumentException("Invalid public key length: ${pubkey.length} (expected 64)")
        }
        if (!pubkey.matches(Regex("^[0-9a-fA-F]+$"))) {
            throw IllegalArgumentException("Invalid public key format: must be hexadecimal")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up timeout handler to prevent memory leaks
        timeoutHandler.removeCallbacksAndMessages(null)

        // Fallback cleanup if not already completed
        if (!isCompleted) {
            cleanupAndFinish()
        }

        if (::originalRequest.isInitialized) {
            NIP55DebugLogger.logIntent("HANDLER_DESTROYED", intent, mapOf(
                "requestId" to originalRequest.id,
                "completed" to isCompleted
            ))
        }
    }

    /**
     * Check permission status for the request using StorageBridge
     * Returns "allowed", "denied", or "prompt_required"
     */
    private fun checkPermission(request: NIP55Request): String {
        return try {
            val storageBridge = StorageBridge(this)
            val permissionsJson = storageBridge.getItem("local", "nip55_permissions")

            if (permissionsJson == null) {
                Log.d(TAG, "No permissions found in storage")
                return "prompt_required"
            }

            Log.d(TAG, "Raw permissions JSON: $permissionsJson")

            val permissionListType = object : com.google.gson.reflect.TypeToken<Array<Permission>>() {}.type
            val permissions: Array<Permission> = gson.fromJson(permissionsJson, permissionListType)

            Log.d(TAG, "Parsed ${permissions.size} permissions")
            permissions.forEach { p ->
                Log.d(TAG, "Permission: appId='${p.appId}' type='${p.type}' allowed=${p.allowed}")
            }

            Log.d(TAG, "Looking for: appId='${request.callingApp}' type='${request.type}'")

            // Find permission for this app + operation
            val permission = permissions.find { p ->
                p.appId == request.callingApp && p.type == request.type
            }

            if (permission != null) {
                if (permission.allowed) {
                    Log.d(TAG, "Permission found: ${request.callingApp}:${request.type} = allowed")
                    "allowed"
                } else {
                    Log.d(TAG, "Permission found: ${request.callingApp}:${request.type} = denied")
                    "denied"
                }
            } else {
                Log.d(TAG, "No permission found for ${request.callingApp}:${request.type}")
                "prompt_required"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check permission", e)
            "prompt_required"
        }
    }

    /**
     * Start background signing service for auto-approved requests
     * This forwards the request to the :main process without bringing MainActivity to foreground
     */
    private fun startBackgroundSigningService() {
        Log.d(TAG, "Starting background signing service for auto-approved request")

        // Create PendingIntent for reply
        val replyIntent = Intent(uniqueReplyAction).apply {
            setPackage(packageName) // Restrict to our app for security
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create intent to start the background signing service in :main process
        val serviceIntent = Intent(this, Nip55BackgroundSigningService::class.java).apply {
            putExtra(Nip55BackgroundSigningService.EXTRA_NIP55_REQUEST, gson.toJson(originalRequest))
            putExtra(Nip55BackgroundSigningService.EXTRA_CALLING_APP, originalRequest.callingApp)
            putExtra(Nip55BackgroundSigningService.EXTRA_REPLY_PENDING_INTENT, replyPendingIntent)
            putExtra(Nip55BackgroundSigningService.EXTRA_REPLY_BROADCAST_ACTION, uniqueReplyAction)
        }

        // Register receiver for completion notification from the service
        val filter = IntentFilter(Nip55BackgroundSigningService.ACTION_SIGNING_COMPLETE)
        replyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "Received signing completion from background service")
                isCompleted = true
                timeoutHandler.removeCallbacksAndMessages(null)

                val success = intent?.getBooleanExtra("success", false) ?: false
                val resultData = intent?.getStringExtra("result_data")
                val error = intent?.getStringExtra("error")

                if (success && resultData != null) {
                    Log.d(TAG, "Background signing completed successfully")
                    returnResult(resultData)
                } else {
                    Log.d(TAG, "Background signing failed: $error")
                    returnError(error ?: "Background signing failed")
                }

                cleanupAndFinish()
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(replyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(replyReceiver, filter)
        }

        Log.d(TAG, "Registered receiver for background signing completion")

        // Start the background signing service
        try {
            startForegroundService(serviceIntent)
            Log.d(TAG, "Successfully started background signing service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start background signing service", e)
            returnError("Failed to start background signing service: ${e.message}")
            cleanupAndFinish()
            return
        }

        // Set up timeout handler for the background service
        timeoutHandler.postDelayed({
            if (!isCompleted) {
                Log.e(TAG, "Background signing service timed out after ${REQUEST_TIMEOUT_MS}ms")
                returnError("Request timeout")
                cleanupAndFinish()
            }
        }, REQUEST_TIMEOUT_MS)
    }

    /**
     * Return focus to the calling app after completing NIP-55 operation
     * This ensures the user is automatically returned to the requesting app (e.g., Amethyst)
     */
    private fun returnFocusToCallingApp() {
        try {
            val callingPackage = originalRequest.callingApp
            Log.d(TAG, "Attempting to return focus to calling app: $callingPackage")

            // Create an intent to launch the calling app's main activity
            val packageManager = packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage(callingPackage)

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(launchIntent)
                Log.d(TAG, "Successfully returned focus to: $callingPackage")
            } else {
                Log.w(TAG, "Could not find launch intent for calling package: $callingPackage")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to return focus to calling app: ${e.message}")
        }
    }
}

/**
 * NIP-55 request data structure
 */
data class NIP55Request(
    val id: String,
    val type: String,
    val params: Map<String, String>,
    val callingApp: String,
    val timestamp: Long,
    val event: Map<String, Any>? = null,
    val pubkey: String? = params["pubkey"],
    val plaintext: String? = params["plaintext"],
    val ciphertext: String? = params["ciphertext"]
)