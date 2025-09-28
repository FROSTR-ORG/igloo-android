package com.frostr.igloo.debug

import android.content.Intent
import android.util.Log
import com.frostr.igloo.debug.DebugConfig
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * NIP-55 Debug Logger
 *
 * Comprehensive logging system for the NIP-55 pipeline with categorized logging methods.
 * All logging is automatically removed in production builds via ProGuard rules.
 *
 * Categories:
 * - Flow: Request lifecycle tracking
 * - Intent: Intent creation, forwarding, and result handling
 * - IPC: Inter-process communication monitoring
 * - PWA Bridge: JavaScript interface interactions
 * - Permission: Permission checks and rule management
 * - Signing: Cryptographic operations
 * - Timing: Performance measurements
 * - Error: Exception and error tracking
 */
object NIP55DebugLogger {

    private const val TAG_PREFIX = "NIP55_DEBUG"
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Flow state tracking
    private val activeFlows = mutableMapOf<String, Long>()

    /**
     * Log NIP-55 flow start
     */
    fun logFlowStart(requestId: String, callingApp: String, requestType: String) {
        if (!DebugConfig.FLOW_LOGGING) return

        activeFlows[requestId] = System.currentTimeMillis()
        val timestamp = dateFormat.format(Date())

        Log.i("$TAG_PREFIX:FLOW", "┌─── FLOW START [$timestamp] ───")
        Log.i("$TAG_PREFIX:FLOW", "│ Request ID: $requestId")
        Log.i("$TAG_PREFIX:FLOW", "│ Calling App: $callingApp")
        Log.i("$TAG_PREFIX:FLOW", "│ Request Type: $requestType")
        Log.i("$TAG_PREFIX:FLOW", "└─────────────────────────────────")
    }

    /**
     * Log NIP-55 flow end
     */
    fun logFlowEnd(requestId: String, success: Boolean, result: String) {
        if (!DebugConfig.FLOW_LOGGING) return

        val startTime = activeFlows.remove(requestId)
        val duration = if (startTime != null) System.currentTimeMillis() - startTime else -1
        val timestamp = dateFormat.format(Date())
        val status = if (success) "SUCCESS" else "FAILED"

        Log.i("$TAG_PREFIX:FLOW", "┌─── FLOW END [$timestamp] ───")
        Log.i("$TAG_PREFIX:FLOW", "│ Request ID: $requestId")
        Log.i("$TAG_PREFIX:FLOW", "│ Status: $status")
        Log.i("$TAG_PREFIX:FLOW", "│ Result: $result")
        if (duration >= 0) {
            Log.i("$TAG_PREFIX:FLOW", "│ Duration: ${duration}ms")
        }
        Log.i("$TAG_PREFIX:FLOW", "└─────────────────────────────────")
    }

    /**
     * Log intent operations
     */
    fun logIntent(operation: String, intent: Intent, context: Map<String, Any> = emptyMap()) {
        if (!DebugConfig.INTENT_LOGGING) return

        val timestamp = dateFormat.format(Date())

        Log.d("$TAG_PREFIX:INTENT", "┌─── INTENT $operation [$timestamp] ───")
        Log.d("$TAG_PREFIX:INTENT", "│ Action: ${intent.action ?: "null"}")
        Log.d("$TAG_PREFIX:INTENT", "│ Data: ${intent.data ?: "null"}")
        Log.d("$TAG_PREFIX:INTENT", "│ Component: ${intent.component?.className ?: "null"}")
        Log.d("$TAG_PREFIX:INTENT", "│ Flags: 0x${Integer.toHexString(intent.flags)}")

        // Log extras
        val extras = intent.extras
        if (extras != null && !extras.isEmpty) {
            Log.d("$TAG_PREFIX:INTENT", "│ Extras:")
            for (key in extras.keySet()) {
                val value = extras.get(key)
                val valueStr = when {
                    value is String && value.length > 100 -> "${value.take(100)}..."
                    else -> value.toString()
                }
                Log.d("$TAG_PREFIX:INTENT", "│   $key: $valueStr")
            }
        }

        // Log context information
        context.forEach { (key, value) ->
            Log.d("$TAG_PREFIX:INTENT", "│ $key: $value")
        }

        Log.d("$TAG_PREFIX:INTENT", "└─────────────────────────────────")
    }

    /**
     * Log IPC operations
     */
    fun logIPC(operation: String, endpoint: String, context: Map<String, Any> = emptyMap()) {
        if (!DebugConfig.IPC_LOGGING) return

        val timestamp = dateFormat.format(Date())

        Log.d("$TAG_PREFIX:IPC", "┌─── IPC $operation [$timestamp] ───")
        Log.d("$TAG_PREFIX:IPC", "│ Endpoint: $endpoint")

        context.forEach { (key, value) ->
            Log.d("$TAG_PREFIX:IPC", "│ $key: $value")
        }

        Log.d("$TAG_PREFIX:IPC", "└─────────────────────────────────")
    }

    /**
     * Log PWA bridge operations
     */
    fun logPWABridge(operation: String, method: String, context: Map<String, Any> = emptyMap()) {
        if (!DebugConfig.PWA_BRIDGE_LOGGING) return

        val timestamp = dateFormat.format(Date())

        Log.d("$TAG_PREFIX:PWA", "┌─── PWA BRIDGE $operation [$timestamp] ───")
        Log.d("$TAG_PREFIX:PWA", "│ Method: $method")

        context.forEach { (key, value) ->
            Log.d("$TAG_PREFIX:PWA", "│ $key: $value")
        }

        Log.d("$TAG_PREFIX:PWA", "└─────────────────────────────────")
    }

    /**
     * Log permission operations
     */
    fun logPermission(operation: String, appId: String, requestType: String, status: String, context: Map<String, Any> = emptyMap()) {
        if (!DebugConfig.PERMISSION_LOGGING) return

        val timestamp = dateFormat.format(Date())

        Log.d("$TAG_PREFIX:PERM", "┌─── PERMISSION $operation [$timestamp] ───")
        Log.d("$TAG_PREFIX:PERM", "│ App ID: $appId")
        Log.d("$TAG_PREFIX:PERM", "│ Request Type: $requestType")
        Log.d("$TAG_PREFIX:PERM", "│ Status: $status")

        context.forEach { (key, value) ->
            Log.d("$TAG_PREFIX:PERM", "│ $key: $value")
        }

        Log.d("$TAG_PREFIX:PERM", "└─────────────────────────────────")
    }

    /**
     * Log signing operations
     */
    fun logSigning(operation: String, requestId: String, context: Map<String, Any> = emptyMap()) {
        if (!DebugConfig.SIGNING_LOGGING) return

        val timestamp = dateFormat.format(Date())

        Log.d("$TAG_PREFIX:SIGN", "┌─── SIGNING $operation [$timestamp] ───")
        Log.d("$TAG_PREFIX:SIGN", "│ Request ID: $requestId")

        context.forEach { (key, value) ->
            Log.d("$TAG_PREFIX:SIGN", "│ $key: $value")
        }

        Log.d("$TAG_PREFIX:SIGN", "└─────────────────────────────────")
    }

    /**
     * Log timing measurements
     */
    fun logTiming(operation: String, durationMs: Long, context: Map<String, Any> = emptyMap()) {
        if (!DebugConfig.TIMING_LOGGING) return

        val timestamp = dateFormat.format(Date())
        val level = when {
            durationMs > 5000 -> "W" // Warn if over 5 seconds
            durationMs > 1000 -> "I" // Info if over 1 second
            else -> "D" // Debug for normal times
        }

        Log.println(when (level) {
            "W" -> Log.WARN
            "I" -> Log.INFO
            else -> Log.DEBUG
        }, "$TAG_PREFIX:TIMING", "┌─── TIMING $operation [$timestamp] ───")

        Log.println(when (level) {
            "W" -> Log.WARN
            "I" -> Log.INFO
            else -> Log.DEBUG
        }, "$TAG_PREFIX:TIMING", "│ Duration: ${durationMs}ms")

        context.forEach { (key, value) ->
            Log.println(when (level) {
                "W" -> Log.WARN
                "I" -> Log.INFO
                else -> Log.DEBUG
            }, "$TAG_PREFIX:TIMING", "│ $key: $value")
        }

        Log.println(when (level) {
            "W" -> Log.WARN
            "I" -> Log.INFO
            else -> Log.DEBUG
        }, "$TAG_PREFIX:TIMING", "└─────────────────────────────────")
    }

    /**
     * Log errors and exceptions
     */
    fun logError(operation: String, error: Throwable, context: Map<String, Any> = emptyMap()) {
        if (!DebugConfig.ERROR_LOGGING) return

        val timestamp = dateFormat.format(Date())

        Log.e("$TAG_PREFIX:ERROR", "┌─── ERROR $operation [$timestamp] ───")
        Log.e("$TAG_PREFIX:ERROR", "│ Exception: ${error.javaClass.simpleName}")
        Log.e("$TAG_PREFIX:ERROR", "│ Message: ${error.message ?: "null"}")

        context.forEach { (key, value) ->
            Log.e("$TAG_PREFIX:ERROR", "│ $key: $value")
        }

        Log.e("$TAG_PREFIX:ERROR", "│ Stack Trace:")
        error.stackTrace.take(5).forEach { frame ->
            Log.e("$TAG_PREFIX:ERROR", "│   at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
        }

        Log.e("$TAG_PREFIX:ERROR", "└─────────────────────────────────")
    }

    /**
     * Log state transitions
     */
    fun logStateTransition(requestId: String, fromState: String, toState: String, context: Map<String, Any> = emptyMap()) {
        if (!DebugConfig.FLOW_LOGGING) return

        val timestamp = dateFormat.format(Date())

        Log.i("$TAG_PREFIX:STATE", "┌─── STATE TRANSITION [$timestamp] ───")
        Log.i("$TAG_PREFIX:STATE", "│ Request ID: $requestId")
        Log.i("$TAG_PREFIX:STATE", "│ Transition: $fromState → $toState")

        context.forEach { (key, value) ->
            Log.i("$TAG_PREFIX:STATE", "│ $key: $value")
        }

        Log.i("$TAG_PREFIX:STATE", "└─────────────────────────────────")
    }

    /**
     * Log JSON data with formatting
     */
    fun logJSON(operation: String, json: String, context: Map<String, Any> = emptyMap()) {
        if (!DebugConfig.VERBOSE_LOGGING) return

        val timestamp = dateFormat.format(Date())

        Log.v("$TAG_PREFIX:JSON", "┌─── JSON $operation [$timestamp] ───")

        context.forEach { (key, value) ->
            Log.v("$TAG_PREFIX:JSON", "│ $key: $value")
        }

        try {
            val jsonObject = JSONObject(json)
            val formatted = jsonObject.toString(2)
            formatted.lines().forEach { line ->
                Log.v("$TAG_PREFIX:JSON", "│ $line")
            }
        } catch (e: Exception) {
            Log.v("$TAG_PREFIX:JSON", "│ Raw: $json")
            Log.v("$TAG_PREFIX:JSON", "│ (Invalid JSON)")
        }

        Log.v("$TAG_PREFIX:JSON", "└─────────────────────────────────")
    }

    /**
     * Log summary statistics
     */
    fun logSummary() {
        if (!DebugConfig.FLOW_LOGGING) return

        val timestamp = dateFormat.format(Date())

        Log.i("$TAG_PREFIX:SUMMARY", "┌─── PIPELINE SUMMARY [$timestamp] ───")
        Log.i("$TAG_PREFIX:SUMMARY", "│ Active Flows: ${activeFlows.size}")

        if (activeFlows.isNotEmpty()) {
            Log.i("$TAG_PREFIX:SUMMARY", "│ Active Request IDs:")
            activeFlows.forEach { (requestId, startTime) ->
                val duration = System.currentTimeMillis() - startTime
                Log.i("$TAG_PREFIX:SUMMARY", "│   $requestId (${duration}ms)")
            }
        }

        Log.i("$TAG_PREFIX:SUMMARY", "└─────────────────────────────────")
    }
}