package com.frostr.igloo.debug

import android.util.Log

/**
 * Lightweight trace context for tracking a single NIP-55 request through the pipeline.
 *
 * This provides a simple, single-line logging format that's easy to grep and analyze:
 *
 *   D/NIP55Trace: [a1b2c3d4] CHECKPOINT_NAME key=value key2=value2 (elapsed_ms)
 *
 * Usage:
 *   val trace = NIP55TraceContext.create(requestId, "sign_event", callingApp, EntryPoint.INTENT)
 *   trace.checkpoint("PARSED", "eventId" to eventId, "kind" to kind)
 *   // ... later
 *   trace.complete(success = true)
 *
 * Filter logs with:
 *   adb logcat -s "NIP55Trace:*"
 *   adb logcat -s "NIP55Trace:*" | grep "a1b2c3d4"
 */
data class NIP55TraceContext(
    val traceId: String,
    val requestId: String,
    val operationType: String,
    val callingApp: String,
    val entryPoint: EntryPoint,
    private val startTime: Long = System.currentTimeMillis(),
    private val checkpoints: MutableList<Checkpoint> = mutableListOf()
) {
    /**
     * Entry point for the NIP-55 request
     */
    enum class EntryPoint {
        INTENT,           // Via nostrsigner: URI intent
        CONTENT_PROVIDER  // Via contentResolver.query()
    }

    /**
     * Recorded checkpoint in the trace
     */
    data class Checkpoint(
        val name: String,
        val timestamp: Long,
        val data: Map<String, Any?> = emptyMap()
    )

    companion object {
        private const val TAG = "NIP55Trace"

        /**
         * Create a new trace context for a request.
         * Automatically logs the RECEIVED checkpoint.
         */
        fun create(
            requestId: String,
            operationType: String,
            callingApp: String,
            entryPoint: EntryPoint
        ): NIP55TraceContext {
            val traceId = extractTraceId(requestId)
            val trace = NIP55TraceContext(
                traceId = traceId,
                requestId = requestId,
                operationType = operationType,
                callingApp = callingApp,
                entryPoint = entryPoint
            )
            trace.checkpoint("RECEIVED",
                "entry" to entryPoint.name,
                "type" to operationType,
                "caller" to callingApp.substringAfterLast('.')
            )
            return trace
        }

        /**
         * Extract 8-character trace ID from request ID.
         * Uses first 8 chars if available, otherwise generates a short hash.
         */
        fun extractTraceId(requestId: String): String {
            return if (requestId.length >= 8) {
                requestId.take(8)
            } else {
                requestId.padEnd(8, '0')
            }
        }

        /**
         * Log a standalone checkpoint without a trace context.
         * Useful for logging from components that don't have access to the full context.
         */
        fun log(traceId: String, checkpoint: String, vararg data: Pair<String, Any?>) {
            val dataStr = if (data.isNotEmpty()) {
                data.joinToString(" ") { "${it.first}=${formatValue(it.second)}" }
            } else ""
            Log.d(TAG, "[$traceId] $checkpoint $dataStr")
        }

        /**
         * Log an error without a trace context.
         */
        fun logError(traceId: String, errorType: String, message: String) {
            Log.e(TAG, "[$traceId] ERROR_$errorType $message")
        }

        private fun formatValue(value: Any?): String = when (value) {
            null -> "null"
            is String -> if (value.length > 32) "${value.take(32)}..." else value
            is ByteArray -> "${value.size}bytes"
            else -> value.toString()
        }
    }

    /**
     * Log a checkpoint with optional key-value data.
     *
     * Standard checkpoints:
     *   RECEIVED, PARSED, DEDUPE_CHECK, PERMISSION_CHECK, QUEUED,
     *   BRIDGE_SENT, PWA_RECEIVED, BIFROST_SENT, BIFROST_RESPONSE,
     *   PWA_COMPLETE, BRIDGE_RESPONSE, RESULT_SENT, COMPLETED
     */
    fun checkpoint(name: String, vararg data: Pair<String, Any?>) {
        if (!DebugConfig.NIP55_LOGGING) return

        val now = System.currentTimeMillis()
        val checkpoint = Checkpoint(name, now, data.toMap())
        checkpoints.add(checkpoint)

        val elapsed = now - startTime
        val dataStr = if (data.isNotEmpty()) {
            data.joinToString(" ") { "${it.first}=${formatValue(it.second)}" }
        } else ""

        Log.d(TAG, "[$traceId] $name $dataStr (${elapsed}ms)")
    }

    /**
     * Log an error checkpoint.
     *
     * Standard error types:
     *   ERROR_PARSE, ERROR_PERMISSION, ERROR_DEDUPE, ERROR_TIMEOUT,
     *   ERROR_BRIDGE, ERROR_BIFROST, ERROR_UNKNOWN
     */
    fun error(type: String, message: String, exception: Throwable? = null) {
        if (!DebugConfig.ERROR_LOGGING) return

        val elapsed = elapsed()
        Log.e(TAG, "[$traceId] ERROR_$type $message (${elapsed}ms)")

        exception?.let { e ->
            Log.e(TAG, "[$traceId] Exception: ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(3).forEach { frame ->
                Log.e(TAG, "[$traceId]   at ${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}")
            }
        }
    }

    /**
     * Log completion of the request.
     * Uses appropriate log level based on duration and success.
     */
    fun complete(success: Boolean, resultSize: Int? = null) {
        if (!DebugConfig.NIP55_LOGGING) return

        val totalDuration = elapsed()
        val level = when {
            !success -> Log.ERROR
            totalDuration > 5000 -> Log.WARN
            totalDuration > 1000 -> Log.INFO
            else -> Log.DEBUG
        }

        val statusStr = if (success) "success=true" else "success=false"
        val resultStr = resultSize?.let { " result_size=$it" } ?: ""
        val slowStr = when {
            totalDuration > 5000 -> " (VERY SLOW)"
            totalDuration > 1000 -> " (SLOW)"
            else -> ""
        }

        Log.println(level, TAG,
            "[$traceId] COMPLETED $statusStr$resultStr total_duration=${totalDuration}ms$slowStr"
        )
    }

    /**
     * Get elapsed time since trace started (in milliseconds).
     */
    fun elapsed(): Long = System.currentTimeMillis() - startTime

    /**
     * Get the last checkpoint name, if any.
     */
    fun lastCheckpoint(): String? = checkpoints.lastOrNull()?.name

    /**
     * Get duration between two named checkpoints.
     * Returns null if either checkpoint is not found.
     */
    fun durationBetween(from: String, to: String): Long? {
        val fromCheckpoint = checkpoints.find { it.name == from }
        val toCheckpoint = checkpoints.find { it.name == to }
        return if (fromCheckpoint != null && toCheckpoint != null) {
            toCheckpoint.timestamp - fromCheckpoint.timestamp
        } else null
    }

    /**
     * Check if a checkpoint has been recorded.
     */
    fun hasCheckpoint(name: String): Boolean = checkpoints.any { it.name == name }

    /**
     * Get all recorded checkpoints (for debugging).
     */
    fun getCheckpoints(): List<Checkpoint> = checkpoints.toList()

    private fun formatValue(value: Any?): String = Companion.formatValue(value)
}

/**
 * Standard checkpoint names for the NIP-55 pipeline.
 * Use these constants for consistency across the codebase.
 */
object NIP55Checkpoints {
    // Request lifecycle
    const val RECEIVED = "RECEIVED"
    const val PARSED = "PARSED"
    const val DEDUPE_CHECK = "DEDUPE_CHECK"
    const val PERMISSION_CHECK = "PERMISSION_CHECK"
    const val QUEUED = "QUEUED"

    // Bridge communication
    const val BRIDGE_SENT = "BRIDGE_SENT"
    const val BRIDGE_RESPONSE = "BRIDGE_RESPONSE"

    // PWA layer
    const val PWA_RECEIVED = "PWA_RECEIVED"
    const val PWA_COMPLETE = "PWA_COMPLETE"

    // Bifrost network
    const val BIFROST_SENT = "BIFROST_SENT"
    const val BIFROST_RESPONSE = "BIFROST_RESPONSE"

    // Result handling
    const val RESULT_SENT = "RESULT_SENT"
    const val COMPLETED = "COMPLETED"

    // WebView
    const val WEBVIEW_CHECK = "WEBVIEW_CHECK"
    const val WEBVIEW_READY = "WEBVIEW_READY"

    // Service
    const val SERVICE_START = "SERVICE_START"
    const val SERVICE_READY = "SERVICE_READY"
}

/**
 * Standard error types for the NIP-55 pipeline.
 */
object NIP55Errors {
    const val PARSE = "PARSE"
    const val PERMISSION = "PERMISSION"
    const val DEDUPE = "DEDUPE"
    const val TIMEOUT = "TIMEOUT"
    const val BRIDGE = "BRIDGE"
    const val BIFROST = "BIFROST"
    const val WEBVIEW = "WEBVIEW"
    const val UNKNOWN = "UNKNOWN"
}
