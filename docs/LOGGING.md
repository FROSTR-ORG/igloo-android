# NIP-55 Pipeline Logging Implementation Guide

This document provides a comprehensive guide for implementing and using the NIP-55 pipeline logging system.

## Table of Contents

1. [Overview](#overview)
2. [Current Logging Infrastructure](#current-logging-infrastructure)
3. [Request Tracing System](#request-tracing-system)
4. [Implementation Guide](#implementation-guide)
5. [Debugging Guide](#debugging-guide)
6. [Logging Standards](#logging-standards)

---

## Overview

### Goals

1. **Full Request Traceability**: Track any NIP-55 request from entry to completion with a single trace ID
2. **Performance Visibility**: Identify bottlenecks with timing data at each checkpoint
3. **Error Diagnosis**: Quickly locate where requests fail or get stuck
4. **Minimal Overhead**: Logging should not impact signing performance

### Design Decisions

| Decision | Rationale |
|----------|-----------|
| Trace IDs in logcat only | Keeps PWA simple, Android handles all logging |
| 8-character trace IDs | Short enough to read, long enough to be unique per session |
| Checkpoint-based timing | Enables bottleneck detection between stages |
| Request ID as trace ID | Reuses existing unique identifier |

---

## Current Logging Infrastructure

### Existing Log Tags

| Tag | Component | Coverage |
|-----|-----------|----------|
| `InvisibleNIP55Handler` | Intent parsing, permission checking | Excellent (65+ calls) |
| `SecureIglooWrapper` | MainActivity lifecycle, WebView | Excellent (113+ calls) |
| `AsyncBridge` | JavaScript IPC | Very Good (40+ calls) |
| `NIP55ContentProvider` | Background signing | Good (36 calls) |
| `NIP55SigningService` | Foreground service | Very Good (20+ calls) |
| `NIP55RequestBridge` | Cross-task queuing | Good |
| `NIP55ResultRegistry` | Result callbacks | Good |
| `WebSocketBridge` | WebSocket connections | Good |
| `StorageBridge` | Encrypted storage | **Needs Work** (20%) |
| `ModernCameraBridge` | Camera/QR scanning | **Needs Work** (15%) |
| `UnifiedSigningBridge` | Signing interface | **Needs Work** (40%) |

### Debug Configuration

The existing debug infrastructure is in `debug/DebugConfig.kt`:

```kotlin
object DebugConfig {
    const val DEBUG_ENABLED = true
    const val NIP55_LOGGING = true
    const val INTENT_LOGGING = true
    const val IPC_LOGGING = true
    const val TIMING_LOGGING = true
    const val PERMISSION_LOGGING = true
    const val PWA_BRIDGE_LOGGING = true
    const val SIGNING_LOGGING = true
    const val ERROR_LOGGING = true
    const val FLOW_LOGGING = true
    const val VERBOSE_LOGGING = false  // Disabled for sensitive data
}
```

---

## Request Tracing System

### Trace ID Format

```
[XXXXXXXX] CHECKPOINT_NAME additional_data
```

- **XXXXXXXX**: First 8 characters of request ID (UUID)
- **CHECKPOINT_NAME**: Standardized checkpoint name (uppercase, underscore-separated)
- **additional_data**: Context-specific information

### Example Trace Output

```
D/NIP55Trace: [a1b2c3d4] RECEIVED entry=INTENT type=sign_event caller=com.vitorpamplona.amethyst
D/NIP55Trace: [a1b2c3d4] PARSED eventId=abc123... kind=1
D/NIP55Trace: [a1b2c3d4] DEDUPE_CHECK result=new
D/NIP55Trace: [a1b2c3d4] PERMISSION_CHECK result=allowed
D/NIP55Trace: [a1b2c3d4] QUEUED queue_size=1
D/NIP55Trace: [a1b2c3d4] BRIDGE_SENT
D/NIP55Trace: [a1b2c3d4] PWA_RECEIVED duration_to_pwa=45ms
D/NIP55Trace: [a1b2c3d4] BIFROST_SENT
D/NIP55Trace: [a1b2c3d4] BIFROST_RESPONSE duration=120ms
D/NIP55Trace: [a1b2c3d4] PWA_COMPLETE pwa_duration=180ms success=true
D/NIP55Trace: [a1b2c3d4] BRIDGE_RESPONSE
D/NIP55Trace: [a1b2c3d4] RESULT_SENT
D/NIP55Trace: [a1b2c3d4] COMPLETED total_duration=250ms success=true
```

### Checkpoint Definitions

| Checkpoint | Location | Description |
|------------|----------|-------------|
| `RECEIVED` | InvisibleNIP55Handler.onCreate/NIP55ContentProvider.query | Request first received |
| `PARSED` | After parseNIP55Request() | Request parameters extracted |
| `DEDUPE_CHECK` | After deduplication check | Duplicate detection result |
| `PERMISSION_CHECK` | After checkPermission() | Permission validation result |
| `QUEUED` | After adding to queue | Request queued for processing |
| `BRIDGE_SENT` | AsyncBridge.callNip55Async() | Sent to WebView |
| `PWA_RECEIVED` | executeAutoSigning() entry | JavaScript received request |
| `BIFROST_SENT` | BifrostSignDevice method entry | Sent to Bifrost network |
| `BIFROST_RESPONSE` | BifrostSignDevice method exit | Response from Bifrost |
| `PWA_COMPLETE` | executeAutoSigning() exit | JavaScript finished |
| `BRIDGE_RESPONSE` | AsyncBridge.handleWebMessage() | Response from WebView |
| `RESULT_SENT` | returnResult()/sendReply() | Result returned to caller |
| `COMPLETED` | Handler finish() | Full cycle complete |

### Error Checkpoints

| Checkpoint | Description |
|------------|-------------|
| `ERROR_PARSE` | Failed to parse request |
| `ERROR_PERMISSION` | Permission denied |
| `ERROR_DEDUPE` | Duplicate request blocked |
| `ERROR_TIMEOUT` | Operation timed out |
| `ERROR_BRIDGE` | Bridge communication failed |
| `ERROR_BIFROST` | Bifrost signing failed |
| `ERROR_UNKNOWN` | Unexpected error |

---

## Implementation Guide

### 1. NIP55TraceContext Class

Create `debug/NIP55TraceContext.kt`:

```kotlin
package com.frostr.igloo.debug

import android.util.Log

/**
 * Context for tracing a single NIP-55 request through the pipeline.
 *
 * Usage:
 *   val trace = NIP55TraceContext.create(requestId, "sign_event", callingApp, EntryPoint.INTENT)
 *   trace.checkpoint("PARSED", "eventId" to eventId, "kind" to kind)
 *   // ... later
 *   trace.complete(success = true)
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
    enum class EntryPoint { INTENT, CONTENT_PROVIDER }

    data class Checkpoint(
        val name: String,
        val timestamp: Long,
        val data: Map<String, Any?> = emptyMap()
    )

    companion object {
        private const val TAG = "NIP55Trace"

        fun create(
            requestId: String,
            operationType: String,
            callingApp: String,
            entryPoint: EntryPoint
        ): NIP55TraceContext {
            val traceId = requestId.take(8)
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
                "caller" to callingApp
            )
            return trace
        }

        /**
         * Extract trace ID from request ID for logging in components
         * that don't have access to the full trace context.
         */
        fun extractTraceId(requestId: String): String = requestId.take(8)
    }

    /**
     * Log a checkpoint with optional data.
     */
    fun checkpoint(name: String, vararg data: Pair<String, Any?>) {
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
     */
    fun error(type: String, message: String, exception: Throwable? = null) {
        val now = System.currentTimeMillis()
        val elapsed = now - startTime

        Log.e(TAG, "[$traceId] ERROR_$type $message (${elapsed}ms)")
        exception?.let {
            Log.e(TAG, "[$traceId] Exception: ${it.message}")
            it.stackTrace.take(5).forEach { frame ->
                Log.e(TAG, "[$traceId]   at $frame")
            }
        }
    }

    /**
     * Log completion of the request.
     */
    fun complete(success: Boolean, resultSize: Int? = null) {
        val totalDuration = System.currentTimeMillis() - startTime
        val level = when {
            !success -> Log.ERROR
            totalDuration > 5000 -> Log.WARN
            totalDuration > 1000 -> Log.INFO
            else -> Log.DEBUG
        }

        val resultStr = resultSize?.let { "result_size=$it " } ?: ""
        Log.println(level, TAG,
            "[$traceId] COMPLETED ${resultStr}total_duration=${totalDuration}ms success=$success"
        )
    }

    /**
     * Get duration since trace started.
     */
    fun elapsed(): Long = System.currentTimeMillis() - startTime

    /**
     * Get duration between two checkpoints.
     */
    fun durationBetween(from: String, to: String): Long? {
        val fromCheckpoint = checkpoints.find { it.name == from }
        val toCheckpoint = checkpoints.find { it.name == to }
        return if (fromCheckpoint != null && toCheckpoint != null) {
            toCheckpoint.timestamp - fromCheckpoint.timestamp
        } else null
    }

    private fun formatValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> if (value.length > 32) "${value.take(32)}..." else value
        else -> value.toString()
    }
}
```

### 2. NIP55Timing Utility

Create `debug/NIP55Timing.kt`:

```kotlin
package com.frostr.igloo.debug

import android.util.Log

/**
 * Utility for timing operations and logging based on thresholds.
 */
object NIP55Timing {
    const val WARN_THRESHOLD_MS = 1000L   // Log warning if > 1s
    const val ERROR_THRESHOLD_MS = 5000L  // Log error if > 5s

    /**
     * Log duration with appropriate level based on thresholds.
     */
    fun logDuration(tag: String, traceId: String, operation: String, startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        val level = when {
            duration > ERROR_THRESHOLD_MS -> Log.ERROR
            duration > WARN_THRESHOLD_MS -> Log.WARN
            else -> Log.DEBUG
        }
        val suffix = when {
            duration > ERROR_THRESHOLD_MS -> "(VERY SLOW)"
            duration > WARN_THRESHOLD_MS -> "(SLOW)"
            else -> ""
        }
        Log.println(level, tag, "[$traceId] $operation took ${duration}ms $suffix")
    }

    /**
     * Inline timing block that logs duration after execution.
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
}
```

### 3. Integration Points

#### InvisibleNIP55Handler.kt

```kotlin
// At request entry:
private fun processRequest(intent: Intent) {
    val requestId = generateRequestId()
    val trace = NIP55TraceContext.create(
        requestId = requestId,
        operationType = parseOperationType(intent),
        callingApp = intent.getStringExtra("package") ?: "unknown",
        entryPoint = NIP55TraceContext.EntryPoint.INTENT
    )

    try {
        val request = parseNIP55Request(intent)
        trace.checkpoint("PARSED",
            "eventId" to request.eventId?.take(16),
            "kind" to request.kind
        )

        val dedupeResult = checkDeduplication(request)
        trace.checkpoint("DEDUPE_CHECK", "result" to dedupeResult)
        if (dedupeResult == "duplicate") {
            trace.complete(success = false)
            return
        }

        val permission = checkPermission(request)
        trace.checkpoint("PERMISSION_CHECK", "result" to permission)

        when (permission) {
            "allowed" -> launchFastSigning(request, trace)
            "denied" -> {
                trace.error("PERMISSION", "Permission denied")
                trace.complete(success = false)
            }
            "prompt_required" -> showPermissionDialog(request, trace)
        }
    } catch (e: Exception) {
        trace.error("UNKNOWN", e.message ?: "Unknown error", e)
        trace.complete(success = false)
    }
}
```

#### AsyncBridge.kt

```kotlin
suspend fun callNip55Async(
    type: String,
    id: String,
    host: String,
    params: Map<String, String>
): NIP55Result {
    val traceId = NIP55TraceContext.extractTraceId(id)
    Log.d(TAG, "[$traceId] BRIDGE_SENT type=$type")

    return NIP55Timing.timed(TAG, traceId, "callNip55Async") {
        withTimeout(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                // ... existing code ...
            }
        }
    }
}

private fun handleWebMessage(message: WebMessageCompat) {
    val json = JSONObject(message.data)
    val id = json.optString("id", "")
    val traceId = NIP55TraceContext.extractTraceId(id)
    val pwaDuration = json.optLong("duration", -1)

    Log.d(TAG, "[$traceId] BRIDGE_RESPONSE pwa_duration=${pwaDuration}ms")
    // ... existing code ...
}
```

#### NIP55ContentProvider.kt

```kotlin
override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?
): Cursor? {
    val requestId = generateRequestId()
    val operationType = parseOperationType(uri)
    val callingApp = callingPackage ?: "unknown"

    val trace = NIP55TraceContext.create(
        requestId = requestId,
        operationType = operationType,
        callingApp = callingApp,
        entryPoint = NIP55TraceContext.EntryPoint.CONTENT_PROVIDER
    )

    try {
        // Check cache first
        val cacheKey = getDeduplicationKey(callingApp, operationType, selectionArgs)
        val cached = resultCache[cacheKey]
        if (cached != null && !cached.isExpired()) {
            trace.checkpoint("DEDUPE_CHECK", "result" to "cached")
            trace.complete(success = true)
            return cached.cursor
        }
        trace.checkpoint("DEDUPE_CHECK", "result" to "new")

        // Check WebView availability
        val webView = MainActivity.getWebViewInstance()
        if (webView == null) {
            trace.checkpoint("WEBVIEW_CHECK", "available" to false)
            trace.complete(success = false)
            return null  // Fallback to Intent
        }
        trace.checkpoint("WEBVIEW_CHECK", "available" to true)

        // Check permission
        if (!hasAutomaticPermission(callingApp, operationType)) {
            trace.checkpoint("PERMISSION_CHECK", "result" to "denied")
            trace.complete(success = false)
            return null  // Fallback to Intent for permission dialog
        }
        trace.checkpoint("PERMISSION_CHECK", "result" to "allowed")

        // Execute signing
        val result = executeNIP55Operation(webView, operationType, selectionArgs, trace)
        trace.complete(success = result != null, resultSize = result?.count)
        return result

    } catch (e: Exception) {
        trace.error("UNKNOWN", e.message ?: "Unknown error", e)
        trace.complete(success = false)
        return null
    }
}
```

### 4. PWA Duration Tracking

Minimal PWA modification to include timing data in responses:

```typescript
// src/lib/signer.ts
export async function executeAutoSigning(request: NIP55Request): Promise<NIP55Result> {
    const startTime = Date.now();

    try {
        // ... existing signing logic ...
        const result = await executeSigningOperation(signer, request);

        return {
            ...result,
            duration: Date.now() - startTime  // Add duration to response
        };
    } catch (error) {
        return {
            ok: false,
            type: request.type,
            id: request.id,
            reason: error instanceof Error ? error.message : 'Unknown error',
            duration: Date.now() - startTime
        };
    }
}
```

### 5. StorageBridge Logging

Add logging to `bridges/StorageBridge.kt`:

```kotlin
@JavascriptInterface
fun getItem(key: String): String? {
    val traceId = "storage"  // Or pass through from request
    Log.d(TAG, "[$traceId] getItem key=$key")

    return NIP55Timing.timed(TAG, traceId, "getItem($key)") {
        try {
            val result = secureStorage.getString(key, null)
            Log.d(TAG, "[$traceId] getItem key=$key found=${result != null}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "[$traceId] getItem key=$key error=${e.message}")
            null
        }
    }
}

@JavascriptInterface
fun setItem(key: String, value: String): Boolean {
    val traceId = "storage"
    Log.d(TAG, "[$traceId] setItem key=$key size=${value.length}")

    return NIP55Timing.timed(TAG, traceId, "setItem($key)") {
        try {
            secureStorage.edit().putString(key, value).commit().also { success ->
                Log.d(TAG, "[$traceId] setItem key=$key success=$success")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$traceId] setItem key=$key error=${e.message}")
            false
        }
    }
}

@JavascriptInterface
fun removeItem(key: String): Boolean {
    val traceId = "storage"
    Log.d(TAG, "[$traceId] removeItem key=$key")

    return try {
        secureStorage.edit().remove(key).commit().also { success ->
            Log.d(TAG, "[$traceId] removeItem key=$key success=$success")
        }
    } catch (e: Exception) {
        Log.e(TAG, "[$traceId] removeItem key=$key error=${e.message}")
        false
    }
}
```

### 6. UnifiedSigningBridge Logging

Add logging to `bridges/UnifiedSigningBridge.kt`:

```kotlin
@JavascriptInterface
fun signEvent(eventJson: String, callbackId: String) {
    val traceId = callbackId.take(8)
    Log.d(TAG, "[$traceId] signEvent received, event_size=${eventJson.length}")

    CoroutineScope(Dispatchers.Main).launch {
        NIP55Timing.timed(TAG, traceId, "signEvent") {
            try {
                val result = performSigning(eventJson)
                Log.d(TAG, "[$traceId] signEvent success, result_size=${result.length}")
                invokeCallback(callbackId, result)
            } catch (e: Exception) {
                Log.e(TAG, "[$traceId] signEvent error: ${e.message}")
                invokeErrorCallback(callbackId, e.message ?: "Unknown error")
            }
        }
    }
}
```

---

## Debugging Guide

### Quick Trace Lookup

Find all logs for a specific request:

```bash
# Get trace ID from any log line, then filter
adb logcat -s "NIP55Trace:*" | grep "a1b2c3d4"
```

### Full Pipeline Debug

Monitor the entire pipeline:

```bash
adb logcat -c && adb logcat -s \
    "NIP55Trace:*" \
    "InvisibleNIP55Handler:*" \
    "SecureIglooWrapper:*" \
    "AsyncBridge:*" \
    "NIP55ContentProvider:*"
```

### Performance Analysis

Find slow operations:

```bash
# Find operations over 1 second
adb logcat -s "NIP55Trace:*" | grep -E "\(SLOW\)|\(VERY SLOW\)"

# Find all COMPLETED logs with timing
adb logcat -s "NIP55Trace:*" | grep "COMPLETED"
```

### Error Tracking

Find all errors:

```bash
adb logcat -s "NIP55Trace:*" | grep "ERROR_"
```

### Request Type Analysis

Filter by operation type:

```bash
# Only sign_event requests
adb logcat -s "NIP55Trace:*" | grep "type=sign_event"

# Only encryption requests
adb logcat -s "NIP55Trace:*" | grep -E "type=nip04|type=nip44"
```

### Common Issues

#### Request Stuck at BRIDGE_SENT

```
D/NIP55Trace: [a1b2c3d4] BRIDGE_SENT
# No BRIDGE_RESPONSE
```

**Cause**: WebView JavaScript not responding
**Debug**:
```bash
adb logcat -s "chromium:*" | grep -i error
```

#### Request Stuck at PERMISSION_CHECK

```
D/NIP55Trace: [a1b2c3d4] PERMISSION_CHECK result=prompt_required
# No further checkpoints
```

**Cause**: Permission dialog not showing or user not responding
**Debug**: Check MainActivity logs for dialog issues

#### Duplicate Requests Blocked

```
D/NIP55Trace: [a1b2c3d4] DEDUPE_CHECK result=duplicate
```

**Cause**: Same event ID within 5-second window (expected behavior)
**Note**: This is normal for Amethyst retries

#### Very Slow Signing

```
W/NIP55Trace: [a1b2c3d4] COMPLETED total_duration=3500ms success=true (SLOW)
```

**Debug**: Look at checkpoint timings to find bottleneck:
```bash
adb logcat -s "NIP55Trace:*" | grep "a1b2c3d4"
```

---

## Logging Standards

### Log Levels

| Level | Use Case |
|-------|----------|
| `Log.v` | Verbose details (JSON payloads when VERBOSE_LOGGING enabled) |
| `Log.d` | Normal checkpoints, debug info |
| `Log.i` | Important state changes, operations > 1s |
| `Log.w` | Slow operations (> 1s), unexpected but recoverable |
| `Log.e` | Errors, exceptions, failed operations |

### Data Sanitization

**Never log**:
- Private keys or shares
- Full event content
- Plaintext messages
- Passwords or session tokens

**Safe to log**:
- Event IDs (truncated to 16 chars)
- Public keys
- Event kinds
- Operation types
- Timing data
- Success/failure status

### Truncation Rules

| Data Type | Max Length | Example |
|-----------|------------|---------|
| Event ID | 16 chars | `abc123def456...` |
| Public key | 16 chars | `npub1abc123...` |
| Error message | 100 chars | Truncate long stack traces |
| JSON payload | Never log | Use size instead |

### Tag Naming

- Use component name: `InvisibleNIP55Handler`, `AsyncBridge`
- Use `NIP55Trace` for trace system logs
- Prefix with subsystem if needed: `Storage`, `Camera`

---

## Implementation Checklist

### Phase 1: Core Tracing

- [ ] Create `debug/NIP55TraceContext.kt`
- [ ] Create `debug/NIP55Timing.kt`
- [ ] Integrate tracing in `InvisibleNIP55Handler.kt`
- [ ] Integrate tracing in `NIP55ContentProvider.kt`
- [ ] Add duration to PWA response

### Phase 2: Bridge Logging

- [ ] Add logging to `AsyncBridge.kt` with trace IDs
- [ ] Add logging to `StorageBridge.kt`
- [ ] Add logging to `UnifiedSigningBridge.kt`
- [ ] Add logging to `WebSocketBridge.kt` (reconnection events)

### Phase 3: Refinement

- [ ] Add performance threshold alerts
- [ ] Create debugging cheat sheet
- [ ] Test with real Amethyst traffic
- [ ] Document common error patterns

---

## See Also

- `NIP55_ARCHITECTURE.md` - Pipeline architecture documentation
- `debug/DebugConfig.kt` - Debug configuration flags
- `debug/NIP55DebugLogger.kt` - Existing debug logger
