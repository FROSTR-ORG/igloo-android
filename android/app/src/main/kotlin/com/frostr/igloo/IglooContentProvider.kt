package com.frostr.igloo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.app.PendingIntent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.content.UriMatcher
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.frostr.igloo.bridges.StorageBridge

/**
 * Permission data structure matching PWA storage format
 */
data class Permission(
    val appId: String,
    val type: String,
    val allowed: Boolean,
    val timestamp: Long
)

/**
 * NIP-55 Content Resolver for Igloo App
 *
 * Handles automatic background signing operations via Content Resolver API.
 * Implements query-specific state management to handle concurrent requests safely.
 * Integrates with existing Intent Handler Pipeline in :main process.
 */
class IglooContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "IglooContentProvider"
        private const val TIMEOUT_MS = 30000L
        private const val MAX_QUERIES_PER_HOUR = 100

        // Supported NIP-55 operations
        private val SUPPORTED_OPERATIONS = setOf(
            "GET_PUBLIC_KEY", "SIGN_EVENT",
            "NIP04_ENCRYPT", "NIP04_DECRYPT",
            "NIP44_ENCRYPT", "NIP44_DECRYPT",
            "DECRYPT_ZAP_EVENT"
        )
    }

    // Authority is set dynamically from context
    private lateinit var authority: String

    // URI matcher initialized in onCreate
    private lateinit var uriMatcher: UriMatcher

    // Query-specific state management for concurrent operations
    data class QueryState(
        val queryId: String,
        val latch: CountDownLatch = CountDownLatch(1),
        var resultData: Bundle? = null,
        var error: String? = null,
        val receiver: BroadcastReceiver,
        val startTime: Long = System.currentTimeMillis()
    )

    // Concurrent query tracking
    private val pendingQueries = ConcurrentHashMap<String, QueryState>()

    // Rate limiting per calling package
    private val rateLimiter = ConcurrentHashMap<String, AtomicInteger>()

    private val gson = Gson()

    override fun onCreate(): Boolean {
        // Initialize authority from context
        authority = "${context?.packageName}.signing"

        // Initialize URI matcher with dynamic authority
        uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "GET_PUBLIC_KEY", 1)
            addURI(authority, "SIGN_EVENT", 2)
            addURI(authority, "NIP04_ENCRYPT", 3)
            addURI(authority, "NIP04_DECRYPT", 4)
            addURI(authority, "NIP44_ENCRYPT", 5)
            addURI(authority, "NIP44_DECRYPT", 6)
            addURI(authority, "DECRYPT_ZAP_EVENT", 7)
        }

        Log.i(TAG, "NIP-55 Content Provider initialized with authority: $authority")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {

        val queryId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "Content Resolver query started: $queryId for URI: $uri")

        try {
            // Parse operation type from URI
            val operationType = parseOperationType(uri)
            if (operationType == null) {
                Log.w(TAG, "Invalid URI: $uri")
                return null
            }

            // Get calling package for permission and rate limit checks
            val callingPackage = callingPackage ?: run {
                Log.w(TAG, "No calling package for query $queryId")
                return null
            }

            // Rate limiting check
            if (!checkRateLimit(callingPackage)) {
                Log.w(TAG, "Rate limit exceeded for $callingPackage")
                return createRejectedCursor("Rate limit exceeded")
            }

            // Validate request parameters
            if (!validateNIP55Request(operationType, selectionArgs)) {
                Log.w(TAG, "Invalid request parameters for $operationType")
                return null
            }

            // Check automatic permissions from storage
            if (!hasAutomaticPermission(callingPackage, operationType)) {
                Log.i(TAG, "No automatic permission for $callingPackage:$operationType")
                return createRejectedCursor("No automatic permission granted")
            }

            // Create unique broadcast action for this query
            val uniqueAction = "com.frostr.igloo.NIP55_REPLY_$queryId"

            // Create query-specific state with receiver
            val queryState = QueryState(
                queryId = queryId,
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val state = pendingQueries[queryId] ?: return

                        Log.d(TAG, "Received reply for query $queryId")

                        state.resultData = intent?.extras
                        if (state.resultData == null) {
                            state.error = "Processing failed"
                        }

                        // Clean up receiver and signal completion
                        try {
                            context?.unregisterReceiver(this)
                        } catch (e: IllegalArgumentException) {
                            // Receiver already unregistered
                        }

                        pendingQueries.remove(queryId)
                        state.latch.countDown()
                    }
                }
            )

            pendingQueries[queryId] = queryState

            // Register receiver for this specific query
            val filter = IntentFilter(uniqueAction)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context?.registerReceiver(queryState.receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context?.registerReceiver(queryState.receiver, filter)
            }

            // Forward to main process via existing Intent pipeline
            forwardToMainProcess(operationType, selectionArgs, callingPackage, uniqueAction)

            // Block waiting for response with timeout
            if (!queryState.latch.await(TIMEOUT_MS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timeout on query $queryId after ${TIMEOUT_MS}ms")
                cleanup(queryId)

                // Log metrics for monitoring
                logQueryMetrics(operationType, callingPackage,
                    System.currentTimeMillis() - startTime, true)

                return null // Per NIP-55: return null when unavailable
            }

            val state = pendingQueries[queryId]
            val duration = System.currentTimeMillis() - startTime

            // Log successful completion
            logQueryMetrics(operationType, callingPackage, duration, false)

            val errorMessage = state?.error
            if (errorMessage != null) {
                Log.d(TAG, "Query $queryId completed with error: $errorMessage")
                return createRejectedCursor(errorMessage)
            }

            Log.d(TAG, "Query $queryId completed successfully in ${duration}ms")
            return createResultCursor(state?.resultData, operationType)

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in query $queryId", e)
            cleanup(queryId)
            return null

        } finally {
            // Ensure cleanup even if exception occurs
            cleanup(queryId)
        }
    }

    /**
     * Parse operation type from URI path
     */
    private fun parseOperationType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            1 -> "get_public_key"
            2 -> "sign_event"
            3 -> "nip04_encrypt"
            4 -> "nip04_decrypt"
            5 -> "nip44_encrypt"
            6 -> "nip44_decrypt"
            7 -> "decrypt_zap_event"
            else -> null
        }
    }

    /**
     * Validate NIP-55 request parameters
     */
    private fun validateNIP55Request(operationType: String, args: Array<String>?): Boolean {
        return when (operationType) {
            "get_public_key" -> true // No parameters required

            "sign_event" -> {
                // Require event JSON in first argument
                args?.getOrNull(0)?.let { eventJson ->
                    try {
                        gson.fromJson(eventJson, Map::class.java)
                        true
                    } catch (e: JsonSyntaxException) {
                        Log.w(TAG, "Invalid event JSON: $eventJson")
                        false
                    }
                } ?: false
            }

            "nip04_encrypt", "nip44_encrypt" -> {
                // Require plaintext and pubkey
                val plaintext = args?.getOrNull(0)
                val pubkey = args?.getOrNull(1)
                !plaintext.isNullOrEmpty() && isValidPubkey(pubkey)
            }

            "nip04_decrypt", "nip44_decrypt" -> {
                // Require ciphertext and pubkey
                val ciphertext = args?.getOrNull(0)
                val pubkey = args?.getOrNull(1)
                !ciphertext.isNullOrEmpty() && isValidPubkey(pubkey)
            }

            "decrypt_zap_event" -> {
                // Require event JSON
                args?.getOrNull(0)?.let { eventJson ->
                    try {
                        gson.fromJson(eventJson, Map::class.java)
                        true
                    } catch (e: JsonSyntaxException) {
                        false
                    }
                } ?: false
            }

            else -> false
        }
    }

    /**
     * Validate public key format (64 character hex)
     */
    private fun isValidPubkey(pubkey: String?): Boolean {
        return pubkey?.length == 64 && pubkey.matches(Regex("^[0-9a-fA-F]+$"))
    }

    /**
     * Check if calling package has automatic permission for operation
     * TODO: Integrate with actual settings storage
     */
    private fun hasAutomaticPermission(callingPackage: String, operationType: String): Boolean {
        return try {
            // Use StorageBridge to access same encrypted storage as PWA
            val storageBridge = StorageBridge(context!!)
            val permissionsJson = storageBridge.getItem("local", "nip55_permissions")

            if (permissionsJson != null) {
                // Parse JSON array of permissions
                val permissions = gson.fromJson(permissionsJson, Array<Permission>::class.java)

                // Check if this app has permission for this operation
                val hasPermission = permissions.any {
                    it.appId == callingPackage &&
                    it.type == operationType &&
                    it.allowed == true
                }

                Log.d(TAG, "Permission check for $callingPackage:$operationType = $hasPermission")
                hasPermission
            } else {
                Log.d(TAG, "No permissions found in storage")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check permissions for $callingPackage:$operationType", e)
            false
        }
    }

    /**
     * Rate limiting check per calling package
     */
    private fun checkRateLimit(callingPackage: String): Boolean {
        val currentHour = System.currentTimeMillis() / (1000 * 60 * 60)
        val key = "$callingPackage:$currentHour"

        val count = rateLimiter.computeIfAbsent(key) { AtomicInteger(0) }
        return count.incrementAndGet() <= MAX_QUERIES_PER_HOUR
    }

    /**
     * Forward request to MainActivity via Intent pipeline
     */
    private fun forwardToMainProcess(
        operationType: String,
        args: Array<String>?,
        callingPackage: String,
        replyAction: String
    ) {
        // Create PendingIntent for reply
        val replyIntent = Intent(replyAction).apply {
            setPackage(context?.packageName)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context, 0, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build request extras in NIP-55 format
        val requestExtras = Bundle().apply {
            putString("type", operationType)
            putString("calling_package", callingPackage)
            putString("id", UUID.randomUUID().toString())

            when (operationType) {
                "sign_event" -> {
                    putString("event", args?.getOrNull(0) ?: "")
                    putString("current_user", args?.getOrNull(2) ?: "")
                }
                "nip04_encrypt", "nip44_encrypt" -> {
                    putString("plaintext", args?.getOrNull(0) ?: "")
                    putString("pubkey", args?.getOrNull(1) ?: "")
                    putString("current_user", args?.getOrNull(2) ?: "")
                }
                "nip04_decrypt", "nip44_decrypt" -> {
                    putString("ciphertext", args?.getOrNull(0) ?: "")
                    putString("pubkey", args?.getOrNull(1) ?: "")
                    putString("current_user", args?.getOrNull(2) ?: "")
                }
                "decrypt_zap_event" -> {
                    putString("event", args?.getOrNull(0) ?: "")
                    putString("current_user", args?.getOrNull(2) ?: "")
                }
                // get_public_key requires no additional parameters
            }
        }

        // Create intent to MainActivity with sanitized flags
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.frostr.igloo.NIP55_SIGNING"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                   Intent.FLAG_ACTIVITY_CLEAR_TOP or
                   Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtras(requestExtras)
            putExtra("reply_pending_intent", replyPendingIntent)
            putExtra("reply_broadcast_action", replyAction)
            putExtra("is_content_resolver", true) // Mark as Content Resolver request
        }

        Log.d(TAG, "Forwarding $operationType to MainActivity via Intent pipeline")
        context?.startActivity(mainIntent)
    }

    /**
     * Create result cursor for successful operations
     */
    private fun createResultCursor(data: Bundle?, operationType: String): Cursor {
        val columns = if (operationType == "sign_event") {
            arrayOf("result", "event")
        } else {
            arrayOf("result")
        }

        val cursor = MatrixCursor(columns)

        if (data != null) {
            val row = when (operationType) {
                "sign_event" -> arrayOf(
                    data.getString("result") ?: data.getString("signature"),
                    data.getString("event")
                )
                else -> arrayOf(data.getString("result"))
            }
            cursor.addRow(row)
        }

        return cursor
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
     * Clean up query state and receiver
     */
    private fun cleanup(queryId: String) {
        pendingQueries[queryId]?.let { state ->
            try {
                context?.unregisterReceiver(state.receiver)
            } catch (e: IllegalArgumentException) {
                // Receiver already unregistered
            }
        }
        pendingQueries.remove(queryId)
    }

    /**
     * Log query metrics for monitoring
     */
    private fun logQueryMetrics(
        operationType: String,
        callingPackage: String,
        duration: Long,
        timedOut: Boolean
    ) {
        val status = if (timedOut) "TIMEOUT" else "SUCCESS"
        Log.i(TAG, "NIP55_METRIC: $operationType from $callingPackage - ${duration}ms - $status")

        // Track concurrent queries
        Log.d(TAG, "Active queries: ${pendingQueries.size}")
    }

    // ContentProvider boilerplate - not used for NIP-55
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}