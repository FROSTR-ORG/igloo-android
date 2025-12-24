package com.frostr.igloo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.webkit.WebView
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import com.frostr.igloo.health.IglooHealthManager
import com.frostr.igloo.util.JavaScriptEscaper
import com.frostr.igloo.debug.NIP55TraceContext
import com.frostr.igloo.debug.NIP55Checkpoints
import com.frostr.igloo.debug.NIP55Errors
import com.frostr.igloo.debug.NIP55Metrics

/**
 * NIP-55 ContentProvider for background signing operations
 *
 * Uses health-based routing: If IglooHealthManager.isHealthy is true,
 * process the request via the health manager. Otherwise, return null
 * to trigger the Intent flow fallback.
 *
 * This is simpler than the Intent handler because:
 * - ContentProvider.query() blocks until result is available
 * - We don't need to launch MainActivity (Intent handler does that)
 * - We just check health and either process or return null
 */
class NIP55ContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "NIP55ContentProvider"
        private const val TIMEOUT_MS = 30000L
        private const val WAKE_LOCK_TAG = "igloo:content_provider_signing"

        // Authority patterns for NIP-55 operations
        private const val AUTHORITY_GET_PUBLIC_KEY = "GET_PUBLIC_KEY"
        private const val AUTHORITY_SIGN_EVENT = "SIGN_EVENT"
        private const val AUTHORITY_NIP04_ENCRYPT = "NIP04_ENCRYPT"
        private const val AUTHORITY_NIP04_DECRYPT = "NIP04_DECRYPT"
        private const val AUTHORITY_NIP44_ENCRYPT = "NIP44_ENCRYPT"
        private const val AUTHORITY_NIP44_DECRYPT = "NIP44_DECRYPT"
        private const val AUTHORITY_DECRYPT_ZAP_EVENT = "DECRYPT_ZAP_EVENT"

        // Track active signing operations for wake lock management
        private val activeOperations = AtomicInteger(0)
        @Volatile
        private var wakeLock: PowerManager.WakeLock? = null
        private val wakeLockSync = Any()

        // Shared result bridge instance
        @Volatile
        private var sharedResultBridge: NIP55ResultBridge? = null

        @JvmStatic
        fun getSharedResultBridge(): NIP55ResultBridge {
            return sharedResultBridge ?: synchronized(this) {
                sharedResultBridge ?: NIP55ResultBridge().also {
                    sharedResultBridge = it
                    Log.d(TAG, "Created shared NIP55ResultBridge")
                }
            }
        }

        fun acquireWakeLock(context: Context) {
            synchronized(wakeLockSync) {
                val count = activeOperations.incrementAndGet()
                Log.d(TAG, "Acquiring wake lock (active operations: $count)")

                if (wakeLock == null) {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        WAKE_LOCK_TAG
                    ).apply {
                        setReferenceCounted(false)
                    }
                }

                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(60 * 1000L)
                    NIP55Metrics.recordWakeLockAcquisition()
                    Log.d(TAG, "Wake lock acquired")
                }
            }
        }

        fun releaseWakeLock() {
            synchronized(wakeLockSync) {
                val count = activeOperations.decrementAndGet()
                Log.d(TAG, "Releasing wake lock (active operations: $count)")

                if (count <= 0) {
                    activeOperations.set(0)
                    wakeLock?.let {
                        if (it.isHeld) {
                            it.release()
                            Log.d(TAG, "Wake lock released")
                        }
                    }
                }
            }
        }

    }

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultBridge get() = getSharedResultBridge()

    /**
     * JavaScript interface for receiving large NIP-55 operation results
     */
    class NIP55ResultBridge {
        private val resultMap = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val latchMap = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()

        @android.webkit.JavascriptInterface
        fun setResult(resultKey: String, jsonResult: String) {
            Log.d("NIP55ContentProvider", "ResultBridge received result for $resultKey (${jsonResult.length} chars)")
            resultMap[resultKey] = jsonResult
            latchMap[resultKey]?.countDown()
        }

        fun waitForResult(resultKey: String, timeoutMs: Long): String? {
            val existingResult = resultMap.remove(resultKey)
            if (existingResult != null) {
                Log.d("NIP55ContentProvider", "Result already available for $resultKey")
                return existingResult
            }

            val latch = CountDownLatch(1)
            latchMap[resultKey] = latch

            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                val result = resultMap.remove(resultKey)
                latchMap.remove(resultKey)
                return result
            }

            latchMap.remove(resultKey)
            return null
        }

        fun cleanup(resultKey: String) {
            resultMap.remove(resultKey)
            latchMap.remove(resultKey)
        }
    }

    override fun onCreate(): Boolean {
        Log.d(TAG, "NIP55ContentProvider initialized")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        // HEALTH CHECK: If system is unhealthy, return null for Intent fallback
        // We can't start MainActivity from ContentProvider (background activity start blocked)
        // so we let Amethyst's Intent fallback handle waking up the app
        if (!IglooHealthManager.isHealthy) {
            Log.d(TAG, "System unhealthy - returning null to trigger Intent fallback")
            return null
        }

        val startTime = System.currentTimeMillis()

        val requestId = try {
            projection?.firstOrNull()?.let { firstArg ->
                if (firstArg.startsWith("{")) {
                    val json = com.google.gson.JsonParser.parseString(firstArg).asJsonObject
                    json.get("id")?.asString ?: UUID.randomUUID().toString()
                } else {
                    UUID.randomUUID().toString()
                }
            } ?: UUID.randomUUID().toString()
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }

        val operationType = parseOperationType(uri)
        val callerPackage = callingPackage ?: "unknown"

        val trace = NIP55TraceContext.create(
            requestId = requestId,
            operationType = operationType ?: "unknown",
            callingApp = callerPackage,
            entryPoint = NIP55TraceContext.EntryPoint.CONTENT_PROVIDER
        )

        context?.let { acquireWakeLock(it) }

        try {
            if (operationType == null) {
                trace.error(NIP55Errors.PARSE, "Invalid URI authority: ${uri.authority}")
                trace.complete(success = false)
                return null  // Intent fallback
            }

            if (callingPackage == null) {
                trace.error(NIP55Errors.PARSE, "No calling package")
                trace.complete(success = false)
                return null  // Intent fallback
            }

            Log.d(TAG, "ContentProvider query: $operationType from $callingPackage")
            NIP55Metrics.recordRequest(operationType, "CONTENT_PROVIDER", callerPackage)

            val args = projection ?: arrayOf()

            // Note: Audit logging moved to MainActivity executor to only log after deduplication

            // Create NIP55Request
            val request = createNIP55Request(requestId, operationType, args, callerPackage)

            // Check WebView availability
            trace.checkpoint(NIP55Checkpoints.WEBVIEW_CHECK)
            val webView = MainActivity.getWebViewInstance()

            if (webView == null) {
                Log.d(TAG, "No WebView available - returning null for Intent fallback")
                trace.checkpoint(NIP55Checkpoints.WEBVIEW_CHECK, "available" to false)
                trace.complete(success = false)
                NIP55Metrics.recordWebViewUnavailable()
                return null
            }
            trace.checkpoint(NIP55Checkpoints.WEBVIEW_READY, "source" to "persistent")

            // Check permissions - return null for Intent fallback (shows permission dialog)
            trace.checkpoint(NIP55Checkpoints.PERMISSION_CHECK)
            if (!hasAutomaticPermission(webView, callerPackage, operationType, args)) {
                trace.checkpoint(NIP55Checkpoints.PERMISSION_CHECK, "allowed" to false)
                trace.complete(success = false)
                return null  // Intent flow will show permission dialog
            }
            trace.checkpoint(NIP55Checkpoints.PERMISSION_CHECK, "allowed" to true)

            // Submit to IglooHealthManager and wait for result
            trace.checkpoint(NIP55Checkpoints.BRIDGE_SENT)
            val resultHolder = AtomicReference<NIP55Result?>(null)
            val latch = CountDownLatch(1)

            val accepted = IglooHealthManager.submit(request) { result ->
                resultHolder.set(result)
                latch.countDown()
            }

            if (!accepted) {
                trace.error(NIP55Errors.UNKNOWN, "Request rejected by health manager")
                trace.complete(success = false)
                return null  // Intent fallback
            }

            // Wait for result
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                trace.error(NIP55Errors.TIMEOUT, "Timeout waiting for result")
                trace.complete(success = false)
                return null  // Intent fallback
            }

            val result = resultHolder.get()
            trace.checkpoint(NIP55Checkpoints.BRIDGE_RESPONSE, "has_result" to (result != null))

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "ContentProvider query completed in ${duration}ms")

            if (result == null) {
                trace.error(NIP55Errors.BRIDGE, "Null result")
                trace.complete(success = false)
                return null  // Intent fallback
            }

            val success = result.ok
            trace.complete(success = success, resultSize = result.result?.length)

            if (success) {
                NIP55Metrics.recordSuccess(duration)
            } else {
                NIP55Metrics.recordFailure(result.reason ?: "Unknown error")
            }

            // If node is locked, offline, or needs permission, return null for Intent fallback
            if (!result.ok) {
                val reason = result.reason ?: "Operation failed"
                if (reason.contains("locked", ignoreCase = true) ||
                    reason.contains("not ready", ignoreCase = true) ||
                    reason.contains("offline", ignoreCase = true) ||
                    reason.contains("permission", ignoreCase = true)) {
                    Log.d(TAG, "Needs Intent fallback: $reason")
                    return null  // Intent flow will handle unlock/reconnect/permission dialog
                }
                // For permanent errors, return rejected cursor
                return createRejectedCursor(reason)
            }

            return createResultCursor(result.result ?: "", operationType)

        } catch (e: Exception) {
            trace.error(NIP55Errors.UNKNOWN, e.message ?: "Unknown exception", e)
            trace.complete(success = false)
            NIP55Metrics.recordFailure(e.message ?: "Unknown exception")
            Log.e(TAG, "Error in ContentProvider query", e)
            return null  // Intent fallback
        } finally {
            releaseWakeLock()
        }
    }

    private fun createNIP55Request(
        requestId: String,
        operationType: String,
        args: Array<String>,
        callerPackage: String
    ): NIP55Request {
        val params = mutableMapOf<String, String>()

        when (operationType) {
            "sign_event" -> {
                args.getOrNull(0)?.let { params["event"] = it }
            }
            "nip04_encrypt", "nip44_encrypt" -> {
                args.getOrNull(0)?.let { params["plaintext"] = it }
                args.getOrNull(1)?.let { params["pubkey"] = it }
            }
            "nip04_decrypt", "nip44_decrypt" -> {
                args.getOrNull(0)?.let { params["ciphertext"] = it }
                args.getOrNull(1)?.let { params["pubkey"] = it }
            }
            "decrypt_zap_event" -> {
                args.getOrNull(0)?.let { params["event"] = it }
            }
        }

        return NIP55Request(
            id = requestId,
            type = operationType,
            params = params,
            callingApp = callerPackage
        )
    }

    private fun parseOperationType(uri: Uri): String? {
        val authority = uri.authority ?: return null
        val packageName = context?.packageName ?: "com.frostr.igloo"

        return when {
            authority == "$packageName.$AUTHORITY_GET_PUBLIC_KEY" -> "get_public_key"
            authority == "$packageName.$AUTHORITY_SIGN_EVENT" -> "sign_event"
            authority == "$packageName.$AUTHORITY_NIP04_ENCRYPT" -> "nip04_encrypt"
            authority == "$packageName.$AUTHORITY_NIP04_DECRYPT" -> "nip04_decrypt"
            authority == "$packageName.$AUTHORITY_NIP44_ENCRYPT" -> "nip44_encrypt"
            authority == "$packageName.$AUTHORITY_NIP44_DECRYPT" -> "nip44_decrypt"
            authority == "$packageName.$AUTHORITY_DECRYPT_ZAP_EVENT" -> "decrypt_zap_event"
            else -> null
        }
    }

    private fun hasAutomaticPermission(
        webView: WebView,
        callingPackage: String,
        operationType: String,
        args: Array<String>
    ): Boolean {
        val latch = CountDownLatch(1)
        val hasPermission = AtomicReference(false)

        var eventKind: Int? = null
        if (operationType == "sign_event" && args.isNotEmpty()) {
            try {
                val eventJson = args[0]
                val eventMap = gson.fromJson(eventJson, Map::class.java)
                eventKind = (eventMap["kind"] as? Double)?.toInt()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract event kind", e)
            }
        }

        val checkPermissionJs = buildString {
            append("(function() {")
            append("  try {")
            append("    const rawPerms = localStorage.getItem('nip55_permissions_v2');")
            append("    if (!rawPerms) return false;")
            append("    const storage = JSON.parse(rawPerms);")
            append("    const permissions = storage.permissions || storage;")
            append("    if (!Array.isArray(permissions)) return false;")
            append("    const appId = '${callingPackage.replace("'", "\\'")}';")
            append("    const type = '${operationType.replace("'", "\\'")}';")

            if (eventKind != null) {
                append("    const kindSpecific = permissions.find(p => ")
                append("      p.appId === appId && p.type === type && p.kind === $eventKind && p.allowed")
                append("    );")
                append("    if (kindSpecific) return true;")
                append("    const wildcard = permissions.find(p => ")
                append("      p.appId === appId && p.type === type && p.kind == null && p.allowed")
                append("    );")
                append("    return !!wildcard;")
            } else {
                append("    const perm = permissions.find(p => ")
                append("      p.appId === appId && p.type === type && p.kind == null && p.allowed")
                append("    );")
                append("    return !!perm;")
            }

            append("  } catch (e) { return false; }")
            append("})()")
        }

        mainHandler.post {
            webView.evaluateJavascript(checkPermissionJs) { result ->
                hasPermission.set(result == "true")
                latch.countDown()
            }
        }

        latch.await(5000, TimeUnit.MILLISECONDS)
        return hasPermission.get()
    }

    private fun createResultCursor(jsonResult: String, operationType: String): Cursor {
        return try {
            when (operationType) {
                "sign_event" -> {
                    // PWA returns the signed event directly with "sig" field
                    // jsonResult IS the event JSON, signature is in "sig" field
                    val resultMap = gson.fromJson(jsonResult, Map::class.java)
                    val cursor = MatrixCursor(arrayOf("signature", "event"))
                    val signature = resultMap["sig"]?.toString() ?: ""
                    // The whole result is the event, not a nested "event" field
                    cursor.addRow(arrayOf(signature, jsonResult))
                    Log.d(TAG, "Created sign_event cursor: sig=${signature.take(20)}..., event=${jsonResult.take(50)}...")
                    cursor
                }
                "nip04_decrypt", "nip44_decrypt", "nip04_encrypt", "nip44_encrypt", "decrypt_zap_event" -> {
                    // For decrypt/encrypt operations, jsonResult is the plaintext/ciphertext string directly
                    // (not a JSON object), so use it as-is
                    val cursor = MatrixCursor(arrayOf("signature", "event", "result"))
                    cursor.addRow(arrayOf(jsonResult, jsonResult, jsonResult))
                    Log.d(TAG, "Created decrypt/encrypt cursor: result=${jsonResult.take(30)}...")
                    cursor
                }
                else -> {
                    val resultMap = gson.fromJson(jsonResult, Map::class.java)
                    val cursor = MatrixCursor(arrayOf("result"))
                    cursor.addRow(arrayOf(resultMap["result"]?.toString() ?: ""))
                    cursor
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create result cursor", e)
            createRejectedCursor("Failed to parse result")
        }
    }

    private fun createRejectedCursor(reason: String = "Permission denied"): Cursor {
        val cursor = MatrixCursor(arrayOf("rejected"))
        cursor.addRow(arrayOf(reason))
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}
