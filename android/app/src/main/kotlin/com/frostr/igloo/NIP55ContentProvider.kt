package com.frostr.igloo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.frostr.igloo.util.NIP55Deduplicator

/**
 * NIP-55 ContentProvider for background signing operations
 *
 * Implements the ContentProvider mechanism specified in NIP-55 for automatic
 * background signing. This provider directly accesses the MainActivity's WebView
 * to perform signing operations synchronously without launching activities.
 *
 * Architecture:
 * - Amethyst calls contentResolver.query() with signing request
 * - ContentProvider checks if MainActivity WebView is available
 * - If available: Calls WebView JavaScript synchronously and blocks until result
 * - If unavailable: Returns null (Amethyst falls back to Intent flow)
 *
 * This approach allows Amethyst to process 35 concurrent AUTH requests without
 * blocking the UI, since each ContentProvider query runs in its own thread.
 */
class NIP55ContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "NIP55ContentProvider"
        private const val TIMEOUT_MS = 30000L

        // Authority patterns for NIP-55 operations
        private const val AUTHORITY_GET_PUBLIC_KEY = "GET_PUBLIC_KEY"
        private const val AUTHORITY_SIGN_EVENT = "SIGN_EVENT"
        private const val AUTHORITY_NIP04_ENCRYPT = "NIP04_ENCRYPT"
        private const val AUTHORITY_NIP04_DECRYPT = "NIP04_DECRYPT"
        private const val AUTHORITY_NIP44_ENCRYPT = "NIP44_ENCRYPT"
        private const val AUTHORITY_NIP44_DECRYPT = "NIP44_DECRYPT"
        private const val AUTHORITY_DECRYPT_ZAP_EVENT = "DECRYPT_ZAP_EVENT"

        // Shared result bridge instance (registered with WebView in MainActivity)
        @Volatile
        private var sharedResultBridge: NIP55ResultBridge? = null

        /**
         * Get or create the shared result bridge instance
         * This should be registered with WebView in MainActivity
         */
        @JvmStatic
        fun getSharedResultBridge(): NIP55ResultBridge {
            return sharedResultBridge ?: synchronized(this) {
                sharedResultBridge ?: NIP55ResultBridge().also {
                    sharedResultBridge = it
                    Log.d(TAG, "Created shared NIP55ResultBridge")
                }
            }
        }
    }

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Request deduplication: track in-flight requests and cache results
    private val pendingRequests = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
    private val resultCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Cursor?, Long>>()
    private val CACHE_TTL_MS = 5000L // Cache results for 5 seconds

    // JavaScript interface for large result handling (uses shared instance)
    private val resultBridge get() = getSharedResultBridge()

    /**
     * JavaScript interface for receiving large NIP-55 operation results
     * This bypasses evaluateJavascript callback size limitations by allowing
     * JavaScript to push results to Kotlin directly via @JavascriptInterface
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
            // Check if result already arrived (race condition: setResult called before waitForResult)
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

    /**
     * Check if WebView is available, return null immediately if not
     *
     * We don't try to start MainActivity from ContentProvider because Android
     * blocks background activity starts. Instead, we return null immediately
     * to trigger the Intent flow fallback (InvisibleNIP55Handler) which CAN
     * start MainActivity since it's launched by a foreground app (Amethyst).
     */
    private fun startMainActivityAndWaitForWebView(): WebView? {
        // Just return null immediately - don't try to start MainActivity
        // The Intent flow (InvisibleNIP55Handler) will handle launching MainActivity
        Log.d(TAG, "WebView not available - returning null to trigger Intent flow")
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val startTime = System.currentTimeMillis()

        try {
            // Parse operation type from URI authority
            val operationType = parseOperationType(uri)
            if (operationType == null) {
                Log.w(TAG, "Invalid URI authority: ${uri.authority}")
                return null
            }

            // Get calling package for permission checks
            val callingPackage = callingPackage
            if (callingPackage == null) {
                Log.w(TAG, "No calling package for query")
                return null
            }

            Log.d(TAG, "ContentProvider query: $operationType from $callingPackage")

            // Amethyst passes parameters in projection array
            val args = projection ?: arrayOf()

            Log.d(TAG, "Query args: ${args.joinToString(", ")}")

            // Generate content-based deduplication key
            val dedupeKey = getDeduplicationKey(callingPackage, operationType, args)
            Log.d(TAG, "✓ Request received: $operationType (dedupe_key=$dedupeKey)")

            // Check cache for completed requests
            val cached = resultCache[dedupeKey]
            if (cached != null) {
                val (cachedResult, timestamp) = cached
                if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                    Log.d(TAG, "✓ Returning cached result (dedupe_key=$dedupeKey)")
                    return cachedResult
                } else {
                    // Clean up expired entry
                    resultCache.remove(dedupeKey)
                }
            }

            // Check if this exact request is already being processed
            val existingLatch = pendingRequests.putIfAbsent(dedupeKey, CountDownLatch(1))
            if (existingLatch != null) {
                Log.d(TAG, "✗ Duplicate request blocked - waiting for in-flight request (dedupe_key=$dedupeKey)")
                // Wait for the in-flight request to complete
                if (existingLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    // Return cached result from completed request
                    val completedResult = resultCache[dedupeKey]
                    if (completedResult != null && System.currentTimeMillis() - completedResult.second < CACHE_TTL_MS) {
                        Log.d(TAG, "✓ Returning result from completed duplicate (dedupe_key=$dedupeKey)")
                        return completedResult.first
                    }
                }
                Log.w(TAG, "✗ Timeout waiting for duplicate request (dedupe_key=$dedupeKey)")
                return null
            }

            // Check if MainActivity WebView is available
            var webView = MainActivity.getWebViewInstance()
            if (webView == null) {
                // WebView not available - try to start MainActivity and wait for it
                webView = startMainActivityAndWaitForWebView()

                if (webView == null) {
                    Log.d(TAG, "WebView still not available after starting MainActivity - falling back to Intent flow")
                    // Cleanup pending request tracker before returning null
                    pendingRequests[dedupeKey]?.countDown()
                    pendingRequests.remove(dedupeKey)
                    return null // Amethyst will use foreground Intent flow
                }
            }

            // Check permissions from storage
            if (!hasAutomaticPermission(webView, callingPackage, operationType, args)) {
                Log.i(TAG, "No automatic permission for $callingPackage:$operationType - falling back to Intent flow")
                // Cleanup pending request tracker before returning null
                pendingRequests[dedupeKey]?.countDown()
                pendingRequests.remove(dedupeKey)
                // Return null to signal Amethyst to use foreground Intent flow for permission prompt
                // This matches Amber's behavior - null means "no saved permission, please prompt user"
                return null
            }

            // Execute signing operation synchronously via WebView
            val result = executeNIP55Operation(webView, operationType, args, callingPackage)

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "ContentProvider query completed in ${duration}ms")

            val resultCursor = when {
                result == null -> null // Operation failed
                result.contains("\"error\"") -> {
                    val errorMsg = extractError(result)
                    Log.w(TAG, "Operation failed: $errorMsg (full result: $result)")
                    createRejectedCursor(errorMsg)
                }
                else -> createResultCursor(result, operationType)
            }

            // Cache the result for deduplication
            if (resultCursor != null) {
                resultCache[dedupeKey] = Pair(resultCursor, System.currentTimeMillis())
                Log.d(TAG, "✓ Cached result (dedupe_key=$dedupeKey)")
                // Clean up old cache entries (keep cache size reasonable)
                if (resultCache.size > 100) {
                    val now = System.currentTimeMillis()
                    resultCache.entries.removeIf { now - it.value.second > CACHE_TTL_MS }
                }
            }

            // Signal completion and cleanup pending request tracker
            pendingRequests[dedupeKey]?.countDown()
            pendingRequests.remove(dedupeKey)

            return resultCursor

        } catch (e: Exception) {
            Log.e(TAG, "Error in ContentProvider query", e)
            // Cleanup pending request tracker on exception
            try {
                val dedupeKey = getDeduplicationKey(callingPackage ?: "", parseOperationType(uri) ?: "", projection ?: arrayOf())
                pendingRequests[dedupeKey]?.countDown()
                pendingRequests.remove(dedupeKey)
            } catch (cleanupError: Exception) {
                Log.w(TAG, "Failed to cleanup pending request", cleanupError)
            }
            return null
        }
    }

    /**
     * Parse operation type from URI authority
     * Format: content://com.frostr.igloo.OPERATION
     */
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

    /**
     * Check if calling package has automatic permission for operation
     * Queries the WebView's permission storage synchronously
     */
    private fun hasAutomaticPermission(
        webView: WebView,
        callingPackage: String,
        operationType: String,
        args: Array<String>
    ): Boolean {
        val latch = CountDownLatch(1)
        val hasPermission = AtomicReference(false)

        // Extract event kind for sign_event requests
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

        // Call WebView JavaScript to check permissions
        val checkPermissionJs = buildString {
            append("(function() {")
            append("  try {")
            append("    const rawPerms = localStorage.getItem('nip55_permissions_v2');")
            append("    if (!rawPerms) return false;")
            append("    const storage = JSON.parse(rawPerms);")
            append("    const permissions = storage.permissions || storage;")
            append("    if (!Array.isArray(permissions)) {")
            append("      console.error('Permissions is not an array:', typeof permissions);")
            append("      return false;")
            append("    }")
            append("    const appId = '${callingPackage.replace("'", "\\'")}';")
            append("    const type = '${operationType.replace("'", "\\'")}';")

            if (eventKind != null) {
                // Check kind-specific permission first, then wildcard
                append("    const kindSpecific = permissions.find(p => ")
                append("      p.appId === appId && p.type === type && p.kind === $eventKind && p.allowed")
                append("    );")
                append("    if (kindSpecific) return true;")
                append("    const wildcard = permissions.find(p => ")
                append("      p.appId === appId && p.type === type && p.kind == null && p.allowed")
                append("    );")
                append("    return !!wildcard;")
            } else {
                // Simple permission check
                append("    const perm = permissions.find(p => ")
                append("      p.appId === appId && p.type === type && p.kind == null && p.allowed")
                append("    );")
                append("    return !!perm;")
            }

            append("  } catch (e) {")
            append("    console.error('Permission check error:', e);")
            append("    return false;")
            append("  }")
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

    /**
     * Execute NIP-55 operation synchronously via WebView JavaScript
     * Uses JavascriptInterface bridge to bypass evaluateJavascript size limits
     */
    private fun executeNIP55Operation(
        webView: WebView,
        operationType: String,
        args: Array<String>,
        callingPackage: String
    ): String? {
        val resultKey = "__nip55_result_${UUID.randomUUID().toString().replace("-", "")}"
        val jsCode = buildNIP55JavaScriptCall(operationType, args, callingPackage, resultKey)

        Log.d(TAG, "Executing JavaScript for $operationType with result key: $resultKey")

        // Start the async operation
        val startLatch = CountDownLatch(1)
        mainHandler.post {
            webView.evaluateJavascript(jsCode) { jsResult ->
                Log.d(TAG, "Async operation started: $jsResult")
                startLatch.countDown()
            }
        }

        if (!startLatch.await(5000, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Timeout starting async operation")
            resultBridge.cleanup(resultKey)
            return null
        }

        // Wait for JavaScript to call resultBridge.setResult() via JavascriptInterface
        val result = resultBridge.waitForResult(resultKey, TIMEOUT_MS)

        if (result == null) {
            Log.w(TAG, "Timeout waiting for WebView result")
        }

        return result
    }

    /**
     * Build JavaScript call for NIP-55 operation using window.nostr.nip55 API
     */
    private fun buildNIP55JavaScriptCall(operationType: String, args: Array<String>, callingPackage: String, resultKey: String): String {
        val requestId = UUID.randomUUID().toString()

        // Helper to escape strings for JavaScript
        fun String.escapeJs(): String {
            return this.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        }

        val baseRequest = """
            {
                id: '$requestId',
                type: '$operationType',
                host: '${callingPackage.escapeJs()}'
        """.trimIndent()

        val requestParams = when (operationType) {
            "get_public_key" -> ""

            "sign_event" -> {
                val eventJson = args.getOrNull(0)?.escapeJs() ?: "{}"
                """, event: JSON.parse("$eventJson")"""
            }

            "nip04_encrypt", "nip44_encrypt" -> {
                val plaintext = args.getOrNull(0)?.escapeJs() ?: ""
                val pubkey = args.getOrNull(1)?.escapeJs() ?: ""
                """, plaintext: "$plaintext", pubkey: "$pubkey""""
            }

            "nip04_decrypt", "nip44_decrypt" -> {
                val ciphertext = args.getOrNull(0)?.escapeJs() ?: ""
                val pubkey = args.getOrNull(1)?.escapeJs() ?: ""
                """, ciphertext: "$ciphertext", pubkey: "$pubkey""""
            }

            "decrypt_zap_event" -> {
                val eventJson = args.getOrNull(0)?.escapeJs() ?: "{}"
                """, event: JSON.parse("$eventJson")"""
            }

            else -> ""
        }

        return """
            (function() {
                try {
                    if (!window.nostr?.nip55) {
                        const errorData = JSON.stringify({ error: 'NIP-55 bridge not ready' });
                        if (window.Android_NIP55ResultBridge) {
                            window.Android_NIP55ResultBridge.setResult('$resultKey', errorData);
                        }
                        return 'SYNC_ERROR';
                    }

                    const request = $baseRequest$requestParams};

                    window.nostr.nip55(request).then(result => {
                        console.log('ContentProvider result:', JSON.stringify(result).substring(0, 200));

                        if (result.ok) {
                            ${buildResultExtraction(operationType, resultKey)}
                        } else {
                            const errorData = JSON.stringify({ error: result.reason || 'Operation failed' });
                            if (window.Android_NIP55ResultBridge) {
                                window.Android_NIP55ResultBridge.setResult('$resultKey', errorData);
                            }
                        }
                    }).catch(e => {
                        console.error('ContentProvider JS error:', e);
                        const errorData = JSON.stringify({ error: e.message });
                        if (window.Android_NIP55ResultBridge) {
                            window.Android_NIP55ResultBridge.setResult('$resultKey', errorData);
                        }
                    });

                    return 'PENDING';
                } catch (e) {
                    console.error('ContentProvider JS error:', e);
                    const errorData = JSON.stringify({ error: e.message });
                    if (window.Android_NIP55ResultBridge) {
                        window.Android_NIP55ResultBridge.setResult('$resultKey', errorData);
                    }
                    return 'SYNC_ERROR';
                }
            })()
        """.trimIndent()
    }

    /**
     * Build result extraction JavaScript based on operation type
     * Uses JavascriptInterface bridge to bypass evaluateJavascript size limits
     */
    private fun buildResultExtraction(operationType: String, resultKey: String): String {
        return when (operationType) {
            "get_public_key" -> """
                const resultData = JSON.stringify({ result: result.result });
                if (window.Android_NIP55ResultBridge) {
                    window.Android_NIP55ResultBridge.setResult('$resultKey', resultData);
                }
            """.trimIndent()

            "sign_event" -> """
                const resultData = JSON.stringify({
                    signature: result.result.sig,
                    event: result.result
                });
                if (window.Android_NIP55ResultBridge) {
                    window.Android_NIP55ResultBridge.setResult('$resultKey', resultData);
                }
            """.trimIndent()

            else -> """
                const resultData = JSON.stringify({ result: result.result });
                if (window.Android_NIP55ResultBridge) {
                    window.Android_NIP55ResultBridge.setResult('$resultKey', resultData);
                }
            """.trimIndent()
        }
    }

    /**
     * Extract error message from JSON result
     */
    private fun extractError(jsonResult: String): String {
        return try {
            val resultMap = gson.fromJson(jsonResult, Map::class.java)
            resultMap["error"]?.toString() ?: "Unknown error"
        } catch (e: Exception) {
            "Unknown error"
        }
    }

    /**
     * Create result cursor for successful operations
     */
    private fun createResultCursor(jsonResult: String, operationType: String): Cursor {
        return try {
            when (operationType) {
                "sign_event" -> {
                    // sign_event returns JSON with signature and event
                    val resultMap = gson.fromJson(jsonResult, Map::class.java)
                    val cursor = MatrixCursor(arrayOf("result", "event"))
                    val signature = resultMap["signature"]?.toString() ?: ""
                    // Convert event object back to JSON string for NIP-55 compliance
                    val eventJson = gson.toJson(resultMap["event"])
                    cursor.addRow(arrayOf(signature, eventJson))
                    cursor
                }
                "nip04_decrypt", "nip44_decrypt", "nip04_encrypt", "nip44_encrypt", "decrypt_zap_event" -> {
                    // Decrypt/encrypt operations return plain text in the result field
                    // Match Amber's format: 3 columns with result repeated
                    Log.d(TAG, "Decrypt operation - jsonResult length: ${jsonResult.length} chars")

                    // Parse JSON and extract the plaintext result field
                    val resultMap = gson.fromJson(jsonResult, Map::class.java)
                    val plaintext = resultMap["result"]?.toString() ?: ""
                    Log.d(TAG, "Decrypt operation - plaintext length: ${plaintext.length} chars")

                    val cursor = MatrixCursor(arrayOf("signature", "event", "result"))
                    cursor.addRow(arrayOf(plaintext, plaintext, plaintext))
                    cursor
                }
                else -> {
                    // get_public_key and other operations return JSON with result field
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

    /**
     * Create rejected cursor for denied/failed operations
     */
    private fun createRejectedCursor(reason: String = "Permission denied"): Cursor {
        val cursor = MatrixCursor(arrayOf("rejected"))
        cursor.addRow(arrayOf(reason))
        return cursor
    }

    /**
     * Generate a deduplication key for a request based on operation content
     * Delegates to shared NIP55Deduplicator utility
     */
    private fun getDeduplicationKey(callingPackage: String, operationType: String, args: Array<String>): String {
        return NIP55Deduplicator.getDeduplicationKey(callingPackage, operationType, args)
    }

    // ContentProvider boilerplate - not used for NIP-55
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}
