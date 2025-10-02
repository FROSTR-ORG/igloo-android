package com.frostr.igloo

import android.app.Activity
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
                    // Use background service for headless signing
                    // WebView is now attached to invisible 1x1 pixel overlay window for proper rendering
                    Log.d(TAG, "Permission allowed - using IglooBackgroundService for headless signing")
                    bindToBackgroundServiceAndSign()
                    return
                }
                "prompt_required" -> {
                    // Launch MainActivity for user prompt
                    Log.d(TAG, "Permission prompt required - launching MainActivity")
                    launchMainActivityForPrompt()
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

            // For sign_event, include the event
            if (originalRequest.type == "sign_event") {
                putExtra("event", originalRequest.params["event"] ?: "")
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

        // Launch MainActivity using startActivityForResult to keep it in same task
        // This prevents Android from creating a new task for cross-app launches
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.frostr.igloo.NIP55_SIGNING"
            putExtra("nip55_request_id", originalRequest.id)
            putExtra("nip55_request_type", originalRequest.type)
            putExtra("nip55_request_calling_app", originalRequest.callingApp)
            putExtra("nip55_request_params", gson.toJson(originalRequest.params))
            putExtra("nip55_permission_status", permissionStatus)
            putExtra("nip55_show_prompt", permissionStatus == "prompt_required")
        }

        @Suppress("DEPRECATION")
        startActivityForResult(mainIntent, 0)
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
     * Check permission status for the request
     * Returns "allowed", "denied", or "prompt_required"
     */
    private fun checkPermission(request: NIP55Request): String {
        return try {
            val storageBridge = StorageBridge(this)
            val permissionsJson = storageBridge.getItem("local", "nip55_permissions")
                ?: return "prompt_required"

            val permissionListType = object : com.google.gson.reflect.TypeToken<Array<Permission>>() {}.type
            val permissions: Array<Permission> = gson.fromJson<Array<Permission>>(permissionsJson, permissionListType)

            val permission = permissions.find { p ->
                p.appId == request.callingApp && p.type == request.type
            }

            when {
                permission == null -> "prompt_required"
                permission.allowed -> "allowed"
                else -> "denied"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check permission", e)
            "prompt_required"
        }
    }

    // ========== Background Service Binding ==========

    private var backgroundService: IglooBackgroundService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "Connected to IglooBackgroundService")
            val localBinder = binder as? IglooBackgroundService.LocalBinder
            backgroundService = localBinder?.getService()
            isServiceBound = true

            // Process the request now that we're connected
            processRequestViaBackgroundService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Disconnected from IglooBackgroundService")
            backgroundService = null
            isServiceBound = false
        }
    }

    /**
     * Bind to IglooBackgroundService and process request
     */
    private fun bindToBackgroundServiceAndSign() {
        try {
            val serviceIntent = Intent(this, IglooBackgroundService::class.java)
            val bound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

            if (!bound) {
                Log.e(TAG, "Failed to bind to IglooBackgroundService")
                returnError("Background service unavailable")
                return
            }

            // Set timeout for service binding
            timeoutHandler.postDelayed({
                if (!isServiceBound) {
                    Log.e(TAG, "Service binding timeout")
                    unbindService(serviceConnection)
                    returnError("Background service timeout")
                }
            }, SERVICE_BIND_TIMEOUT_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to background service", e)
            returnError("Failed to bind to background service: ${e.message}")
        }
    }

    /**
     * Process NIP-55 request via background service
     */
    private fun processRequestViaBackgroundService() {
        Log.d(TAG, "Processing request via IglooBackgroundService")

        handlerScope.launch {
            try {
                val service = backgroundService
                if (service == null) {
                    Log.e(TAG, "Background service reference lost")
                    returnError("Background service unavailable")
                    return@launch
                }

                // Call background service to process the request
                val result = service.processNIP55Request(
                    request = originalRequest,
                    permissionStatus = "allowed"
                )

                Log.d(TAG, "Background service result: ok=${result.ok}")

                if (result.ok && result.result != null) {
                    // Result is already a String from AsyncBridge
                    returnResult(result.result)
                } else {
                    val errorMsg = result.reason ?: "Unknown error"
                    Log.e(TAG, "Background service returned error: $errorMsg")
                    returnError(errorMsg)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Background service request failed", e)
                returnError("Background service error: ${e.message}")
            } finally {
                if (isServiceBound) {
                    unbindService(serviceConnection)
                    isServiceBound = false
                }
            }
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

// NIP55Request, NIP55Result, and Permission data classes are defined in IglooBackgroundService.kt
