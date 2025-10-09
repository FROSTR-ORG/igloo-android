package com.frostr.igloo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    }

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Request deduplication cache: eventId -> (result, timestamp)
    private val resultCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Cursor?, Long>>()
    private val CACHE_TTL_MS = 5000L // Cache results for 5 seconds

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

            // Extract event ID for deduplication (for sign_event operations)
            val eventId = if (operationType == "sign_event" && args.isNotEmpty()) {
                try {
                    val eventJson = args[0]
                    val eventMap = gson.fromJson(eventJson, Map::class.java)
                    eventMap["id"]?.toString()
                } catch (e: Exception) {
                    null
                }
            } else null

            // Check cache for duplicate requests
            if (eventId != null) {
                val cached = resultCache[eventId]
                if (cached != null) {
                    val (cachedResult, timestamp) = cached
                    if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                        Log.d(TAG, "Returning cached result for event $eventId")
                        return cachedResult
                    } else {
                        // Clean up expired entry
                        resultCache.remove(eventId)
                    }
                }
            }

            // Check if MainActivity WebView is available
            val webView = MainActivity.getWebViewInstance()
            if (webView == null) {
                Log.d(TAG, "MainActivity WebView not available - falling back to Intent flow")
                return null // Amethyst will use foreground Intent flow
            }

            // Check permissions from storage
            if (!hasAutomaticPermission(webView, callingPackage, operationType, args)) {
                Log.i(TAG, "No automatic permission for $callingPackage:$operationType")
                return createRejectedCursor("Permission denied")
            }

            // Execute signing operation synchronously via WebView
            val result = executeNIP55Operation(webView, operationType, args, callingPackage)

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "ContentProvider query completed in ${duration}ms")

            val resultCursor = when {
                result == null -> null // Operation failed
                result.contains("\"error\"") -> {
                    val errorMsg = extractError(result)
                    Log.w(TAG, "Operation failed: $errorMsg")
                    createRejectedCursor(errorMsg)
                }
                else -> createResultCursor(result, operationType)
            }

            // Cache the result for deduplication
            if (eventId != null && resultCursor != null) {
                resultCache[eventId] = Pair(resultCursor, System.currentTimeMillis())
                // Clean up old cache entries (keep cache size reasonable)
                if (resultCache.size > 100) {
                    val now = System.currentTimeMillis()
                    resultCache.entries.removeIf { now - it.value.second > CACHE_TTL_MS }
                }
            }

            return resultCursor

        } catch (e: Exception) {
            Log.e(TAG, "Error in ContentProvider query", e)
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
            return null
        }

        // Poll for result
        val result = AtomicReference<String?>()
        val pollStartTime = System.currentTimeMillis()
        val pollInterval = 50L // 50ms between polls for faster response

        while (System.currentTimeMillis() - pollStartTime < TIMEOUT_MS) {
            val pollLatch = CountDownLatch(1)

            mainHandler.post {
                webView.evaluateJavascript("window.$resultKey") { pollResult ->
                    val cleanResult = pollResult?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"")

                    if (cleanResult != null && cleanResult != "undefined" && cleanResult != "null") {
                        Log.d(TAG, "Result received (${cleanResult.length} chars)")
                        result.set(cleanResult)
                    }
                    pollLatch.countDown()
                }
            }

            pollLatch.await(pollInterval, TimeUnit.MILLISECONDS)

            if (result.get() != null) {
                // Clean up the global variable
                mainHandler.post {
                    webView.evaluateJavascript("delete window.$resultKey", null)
                }
                return result.get()
            }

            Thread.sleep(pollInterval)
        }

        Log.w(TAG, "Timeout waiting for WebView result")
        return null
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
                        window.$resultKey = JSON.stringify({ error: 'NIP-55 bridge not ready' });
                        return 'SYNC_ERROR';
                    }

                    const request = $baseRequest$requestParams};

                    window.nostr.nip55(request).then(result => {
                        console.log('ContentProvider result:', JSON.stringify(result).substring(0, 200));

                        if (result.ok) {
                            ${buildResultExtraction(operationType, resultKey)}
                        } else {
                            window.$resultKey = JSON.stringify({ error: result.reason || 'Operation failed' });
                        }
                    }).catch(e => {
                        console.error('ContentProvider JS error:', e);
                        window.$resultKey = JSON.stringify({ error: e.message });
                    });

                    return 'PENDING';
                } catch (e) {
                    console.error('ContentProvider JS error:', e);
                    window.$resultKey = JSON.stringify({ error: e.message });
                    return 'SYNC_ERROR';
                }
            })()
        """.trimIndent()
    }

    /**
     * Build result extraction JavaScript based on operation type
     */
    private fun buildResultExtraction(operationType: String, resultKey: String): String {
        return when (operationType) {
            "get_public_key" -> """
                window.$resultKey = JSON.stringify({ result: result.pubkey });
            """.trimIndent()

            "sign_event" -> """
                window.$resultKey = JSON.stringify({
                    signature: result.result.sig,
                    event: result.result
                });
            """.trimIndent()

            else -> """
                window.$resultKey = JSON.stringify({ result: result.result });
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
            val resultMap = gson.fromJson(jsonResult, Map::class.java)

            when (operationType) {
                "sign_event" -> {
                    val cursor = MatrixCursor(arrayOf("result", "event"))
                    val signature = resultMap["signature"]?.toString() ?: ""
                    // Convert event object back to JSON string for NIP-55 compliance
                    val eventJson = gson.toJson(resultMap["event"])
                    cursor.addRow(arrayOf(signature, eventJson))
                    cursor
                }
                else -> {
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

    // ContentProvider boilerplate - not used for NIP-55
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}
