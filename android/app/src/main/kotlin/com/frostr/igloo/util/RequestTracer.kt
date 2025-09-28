package com.frostr.igloo.util

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cross-process request tracing utility for NIP-55 pipeline debugging
 *
 * This class provides consistent tracing across all Android components
 * and coordinates with PWA tracing through IPC communication.
 */
object RequestTracer {
    private const val TAG = "RequestTracer"
    private val traces = ConcurrentHashMap<String, MutableList<TraceEvent>>()
    private val spans = ConcurrentHashMap<String, Long>() // spanId -> startTime

    data class TraceEvent(
        val traceId: String,
        val spanId: String,
        val component: String,
        val process: String,
        val event: String,
        val data: Map<String, Any>? = null,
        val timestamp: Long,
        val duration: Long? = null,
        val error: String? = null
    )

    data class TraceContext(
        val traceId: String,
        val component: String,
        val process: String,
        val timestamp: Long,
        val parentSpanId: String? = null,
        val spanId: String
    )

    /**
     * Generate a unique trace ID for a new request
     */
    fun generateTraceId(): String {
        return "trace-${System.currentTimeMillis()}-${generateRandomId()}"
    }

    /**
     * Generate a unique span ID for a trace segment
     */
    fun generateSpanId(): String {
        return "span-${System.currentTimeMillis()}-${generateRandomId()}"
    }

    private fun generateRandomId(): String {
        return (1..9).map { ('a'..'z').random() }.joinToString("")
    }

    /**
     * Create a new trace context
     */
    fun createTrace(component: String, process: String = "Android", parentSpanId: String? = null): TraceContext {
        val traceId = generateTraceId()
        val spanId = generateSpanId()
        val timestamp = System.currentTimeMillis()

        val context = TraceContext(
            traceId = traceId,
            component = component,
            process = process,
            timestamp = timestamp,
            parentSpanId = parentSpanId,
            spanId = spanId
        )

        // Initialize trace storage
        traces.putIfAbsent(traceId, mutableListOf())

        logTrace(context, "TRACE_START", mapOf("parentSpanId" to (parentSpanId ?: "")))
        return context
    }

    /**
     * Continue an existing trace with a new span
     */
    fun continueTrace(traceId: String, component: String, process: String = "Android", parentSpanId: String? = null): TraceContext {
        val spanId = generateSpanId()
        val timestamp = System.currentTimeMillis()

        val context = TraceContext(
            traceId = traceId,
            component = component,
            process = process,
            timestamp = timestamp,
            parentSpanId = parentSpanId,
            spanId = spanId
        )

        logTrace(context, "SPAN_START", mapOf("parentSpanId" to (parentSpanId ?: "")))
        return context
    }

    /**
     * Start a timed span
     */
    fun startSpan(context: TraceContext, event: String, data: Map<String, Any>? = null) {
        spans[context.spanId] = System.currentTimeMillis()
        logTrace(context, event, data)
    }

    /**
     * End a timed span
     */
    fun endSpan(context: TraceContext, event: String, data: Map<String, Any>? = null, error: String? = null) {
        val startTime = spans[context.spanId]
        val duration = startTime?.let { System.currentTimeMillis() - it }

        logTrace(context, event, data, duration, error)
        spans.remove(context.spanId)
    }

    /**
     * Log a trace event
     */
    fun logTrace(context: TraceContext, event: String, data: Map<String, Any>? = null, duration: Long? = null, error: String? = null) {
        val traceEvent = TraceEvent(
            traceId = context.traceId,
            spanId = context.spanId,
            component = context.component,
            process = context.process,
            event = event,
            data = data,
            timestamp = System.currentTimeMillis(),
            duration = duration,
            error = error
        )

        // Store the trace event
        traces.getOrPut(context.traceId) { mutableListOf() }.add(traceEvent)

        // Log with structured format for easy parsing
        val durationStr = duration?.let { " (${it}ms)" } ?: ""
        val errorStr = error?.let { " ERROR: $it" } ?: ""
        val dataStr = data?.let { " ${JSONObject(it)}" } ?: ""

        val logMessage = "[${context.traceId}] ${context.process}::${context.component} $event$durationStr$errorStr$dataStr"

        // Also emit to logcat with special formatting for easy grepping
        val consoleMsg = "[TRACE-${context.traceId}] ${context.process}::${context.component}: $event$durationStr$errorStr$dataStr"

        if (error != null) {
            Log.e(TAG, consoleMsg)
        } else {
            Log.i(TAG, consoleMsg)
        }
    }

    /**
     * Parse trace ID from various sources (URL params, headers, JSON, etc.)
     */
    fun parseTraceId(source: Any?): String? {
        return when (source) {
            is String -> {
                val regex = Regex("trace-\\d+-[a-z0-9]+")
                regex.find(source)?.value
            }
            is JSONObject -> {
                source.optString("traceId").takeIf { it.isNotEmpty() }
                    ?: source.optString("trace_id").takeIf { it.isNotEmpty() }
                    ?: source.optString("x-trace-id").takeIf { it.isNotEmpty() }
            }
            is Map<*, *> -> {
                (source["traceId"] ?: source["trace_id"] ?: source["x-trace-id"]) as? String
            }
            else -> null
        }
    }

    /**
     * Format trace context for IPC communication
     */
    fun serializeTraceContext(context: TraceContext): String {
        return JSONObject().apply {
            put("traceId", context.traceId)
            put("spanId", context.spanId)
            put("parentSpanId", context.parentSpanId)
            put("component", context.component)
            put("process", context.process)
        }.toString()
    }

    /**
     * Parse trace context from IPC communication
     */
    fun deserializeTraceContext(serialized: String): TraceContext? {
        return try {
            val json = JSONObject(serialized)
            TraceContext(
                traceId = json.getString("traceId"),
                spanId = json.getString("spanId"),
                component = json.getString("component"),
                process = json.getString("process"),
                timestamp = System.currentTimeMillis(),
                parentSpanId = json.optString("parentSpanId").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse trace context: $serialized", e)
            null
        }
    }

    /**
     * Get all events for a trace
     */
    fun getTrace(traceId: String): List<TraceEvent> {
        return traces[traceId]?.toList() ?: emptyList()
    }

    /**
     * Get trace summary with timing information
     */
    fun getTraceSummary(traceId: String): TraceSummary? {
        val events = getTrace(traceId)
        if (events.isEmpty()) return null

        val processes = events.map { it.process }.toSet()
        val components = events.map { it.component }.toSet()
        val errors = events.filter { it.error != null }

        val totalDuration = if (events.size > 1) {
            val start = events.minOfOrNull { it.timestamp } ?: 0
            val end = events.maxOfOrNull { it.timestamp } ?: 0
            end - start
        } else 0

        return TraceSummary(
            traceId = traceId,
            events = events,
            totalDuration = totalDuration,
            processCount = processes.size,
            componentCount = components.size,
            errorCount = errors.size
        )
    }

    data class TraceSummary(
        val traceId: String,
        val events: List<TraceEvent>,
        val totalDuration: Long,
        val processCount: Int,
        val componentCount: Int,
        val errorCount: Int
    )

    /**
     * Clean up old traces (keep last 100 traces)
     */
    fun cleanup() {
        if (traces.size > 100) {
            val traceIds = traces.keys.toList()
            val toDelete = traceIds.take(traceIds.size - 100)
            toDelete.forEach { traces.remove(it) }
            Log.i(TAG, "Cleaned up ${toDelete.size} old traces")
        }
    }

    /**
     * Export traces for debugging or external analysis
     */
    fun exportTraces(): Map<String, List<TraceEvent>> {
        return traces.mapValues { it.value.toList() }
    }
}