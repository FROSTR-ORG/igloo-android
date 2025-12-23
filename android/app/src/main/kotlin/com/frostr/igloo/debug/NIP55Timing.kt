package com.frostr.igloo.debug

import android.util.Log

/**
 * Utility for timing operations and logging based on performance thresholds.
 *
 * Provides:
 * - Threshold-based logging (normal/slow/very slow)
 * - Inline timing blocks for clean code
 * - Suspend function timing for coroutines
 *
 * Usage:
 *   // Simple duration logging
 *   val start = System.currentTimeMillis()
 *   // ... operation ...
 *   NIP55Timing.logDuration(TAG, traceId, "operation_name", start)
 *
 *   // Inline timing block
 *   val result = NIP55Timing.timed(TAG, traceId, "operation_name") {
 *       performOperation()
 *   }
 *
 *   // With trace context
 *   val result = NIP55Timing.timedCheckpoint(trace, "BRIDGE_SENT", "BRIDGE_RESPONSE") {
 *       asyncBridge.callNip55Async(...)
 *   }
 */
object NIP55Timing {

    /**
     * Threshold for logging at INFO level (operation is getting slow)
     */
    const val WARN_THRESHOLD_MS = 1000L

    /**
     * Threshold for logging at WARN level (operation is very slow)
     */
    const val ERROR_THRESHOLD_MS = 5000L

    /**
     * Log duration with appropriate level based on thresholds.
     *
     * @param tag Log tag to use
     * @param traceId 8-character trace ID for correlation
     * @param operation Name of the operation being timed
     * @param startTime Start time from System.currentTimeMillis()
     * @param extraData Optional extra data to include in log
     */
    fun logDuration(
        tag: String,
        traceId: String,
        operation: String,
        startTime: Long,
        vararg extraData: Pair<String, Any?>
    ) {
        if (!DebugConfig.TIMING_LOGGING) return

        val duration = System.currentTimeMillis() - startTime
        val level = durationToLevel(duration)

        val suffix = when {
            duration > ERROR_THRESHOLD_MS -> " (VERY SLOW)"
            duration > WARN_THRESHOLD_MS -> " (SLOW)"
            else -> ""
        }

        val extraStr = if (extraData.isNotEmpty()) {
            " " + extraData.joinToString(" ") { "${it.first}=${formatValue(it.second)}" }
        } else ""

        Log.println(level, tag, "[$traceId] $operation took ${duration}ms$suffix$extraStr")
    }

    /**
     * Log duration without a trace ID (for standalone timing).
     */
    fun logDuration(tag: String, operation: String, startTime: Long) {
        if (!DebugConfig.TIMING_LOGGING) return

        val duration = System.currentTimeMillis() - startTime
        val level = durationToLevel(duration)

        val suffix = when {
            duration > ERROR_THRESHOLD_MS -> " (VERY SLOW)"
            duration > WARN_THRESHOLD_MS -> " (SLOW)"
            else -> ""
        }

        Log.println(level, tag, "$operation took ${duration}ms$suffix")
    }

    /**
     * Inline timing block that logs duration after execution.
     *
     * Example:
     *   val result = NIP55Timing.timed(TAG, traceId, "processRequest") {
     *       doExpensiveWork()
     *   }
     */
    inline fun <T> timed(
        tag: String,
        traceId: String,
        operation: String,
        block: () -> T
    ): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            logDuration(tag, traceId, operation, start)
        }
    }

    /**
     * Inline timing block without trace ID.
     */
    inline fun <T> timed(
        tag: String,
        operation: String,
        block: () -> T
    ): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            logDuration(tag, operation, start)
        }
    }

    /**
     * Timing block that logs start and end checkpoints to a trace context.
     *
     * Example:
     *   val result = NIP55Timing.timedCheckpoint(trace, "BRIDGE_SENT", "BRIDGE_RESPONSE") {
     *       asyncBridge.callNip55Async(type, id, host, params)
     *   }
     */
    inline fun <T> timedCheckpoint(
        trace: NIP55TraceContext,
        startCheckpoint: String,
        endCheckpoint: String,
        block: () -> T
    ): T {
        trace.checkpoint(startCheckpoint)
        return try {
            block()
        } finally {
            trace.checkpoint(endCheckpoint)
        }
    }

    /**
     * Timing block that logs start checkpoint and handles success/error end checkpoints.
     *
     * Example:
     *   val result = NIP55Timing.timedWithResult(trace, "BIFROST_SENT", "BIFROST_RESPONSE", "BIFROST") {
     *       bifrostNode.sign(eventId)
     *   }
     */
    inline fun <T> timedWithResult(
        trace: NIP55TraceContext,
        startCheckpoint: String,
        successCheckpoint: String,
        errorType: String,
        block: () -> T
    ): T {
        trace.checkpoint(startCheckpoint)
        return try {
            val result = block()
            trace.checkpoint(successCheckpoint, "success" to true)
            result
        } catch (e: Exception) {
            trace.error(errorType, e.message ?: "Unknown error", e)
            throw e
        }
    }

    /**
     * Suspend timing block for coroutines.
     *
     * Example:
     *   val result = NIP55Timing.timedSuspend(TAG, traceId, "asyncOperation") {
     *       suspendingFunction()
     *   }
     */
    suspend inline fun <T> timedSuspend(
        tag: String,
        traceId: String,
        operation: String,
        crossinline block: suspend () -> T
    ): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            logDuration(tag, traceId, operation, start)
        }
    }

    /**
     * Create a simple stopwatch for manual timing control.
     *
     * Example:
     *   val stopwatch = NIP55Timing.stopwatch()
     *   // ... operations ...
     *   val elapsed = stopwatch.elapsed()
     *   stopwatch.log(TAG, traceId, "operation")
     */
    fun stopwatch(): Stopwatch = Stopwatch()

    /**
     * Simple stopwatch for manual timing.
     */
    class Stopwatch {
        private val startTime = System.currentTimeMillis()
        private var lapTime = startTime

        /**
         * Get elapsed time since creation (in milliseconds).
         */
        fun elapsed(): Long = System.currentTimeMillis() - startTime

        /**
         * Get time since last lap (or creation if no lap yet).
         */
        fun lap(): Long {
            val now = System.currentTimeMillis()
            val elapsed = now - lapTime
            lapTime = now
            return elapsed
        }

        /**
         * Log the elapsed time.
         */
        fun log(tag: String, traceId: String, operation: String) {
            logDuration(tag, traceId, operation, startTime)
        }

        /**
         * Log the lap time.
         */
        fun logLap(tag: String, traceId: String, operation: String) {
            val lapElapsed = lap()
            if (!DebugConfig.TIMING_LOGGING) return

            val level = durationToLevel(lapElapsed)
            Log.println(level, tag, "[$traceId] $operation lap: ${lapElapsed}ms")
        }
    }

    /**
     * Check if a duration exceeds the warning threshold.
     */
    fun isSlow(durationMs: Long): Boolean = durationMs > WARN_THRESHOLD_MS

    /**
     * Check if a duration exceeds the error threshold.
     */
    fun isVerySlow(durationMs: Long): Boolean = durationMs > ERROR_THRESHOLD_MS

    /**
     * Get appropriate log level for a duration.
     */
    private fun durationToLevel(durationMs: Long): Int = when {
        durationMs > ERROR_THRESHOLD_MS -> Log.WARN
        durationMs > WARN_THRESHOLD_MS -> Log.INFO
        else -> Log.DEBUG
    }

    private fun formatValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> if (value.length > 32) "${value.take(32)}..." else value
        else -> value.toString()
    }
}

/**
 * Extension function to time a block and log to a trace context.
 *
 * Example:
 *   val result = trace.timed("operation") { doWork() }
 */
inline fun <T> NIP55TraceContext.timed(
    operation: String,
    block: () -> T
): T {
    val start = System.currentTimeMillis()
    return try {
        block()
    } finally {
        val duration = System.currentTimeMillis() - start
        checkpoint(operation, "duration_ms" to duration)
    }
}
