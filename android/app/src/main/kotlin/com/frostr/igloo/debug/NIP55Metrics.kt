package com.frostr.igloo.debug

import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * NIP-55 Metrics Collector
 *
 * Thread-safe singleton for collecting in-memory metrics about NIP-55 pipeline operations.
 * Metrics are reset on app restart. Debug builds only.
 */
object NIP55Metrics {
    private const val TAG = "NIP55Metrics"
    private const val MAX_RECENT_DURATIONS = 100
    private const val SLOW_REQUEST_THRESHOLD_MS = 1000L

    // Request counters
    private val requestsByType = ConcurrentHashMap<String, AtomicLong>()
    private val requestsByEntryPoint = ConcurrentHashMap<String, AtomicLong>()
    private val requestsByCaller = ConcurrentHashMap<String, AtomicLong>()
    private val totalRequests = AtomicLong(0)

    // Success/failure
    private val successCount = AtomicLong(0)
    private val failureCount = AtomicLong(0)
    private val errorsByType = ConcurrentHashMap<String, AtomicLong>()

    // Timing (rolling window)
    private val recentDurations = Collections.synchronizedList(mutableListOf<Long>())
    private val slowRequestCount = AtomicLong(0)

    // Deduplication and rate limiting
    private val cacheHits = AtomicLong(0)
    private val duplicatesBlocked = AtomicLong(0)
    private val rateLimited = AtomicLong(0)

    // Permission
    private val permissionPromptsShown = AtomicLong(0)
    private val permissionApprovals = AtomicLong(0)
    private val permissionDenials = AtomicLong(0)

    // Resource
    private val wakeLockAcquisitions = AtomicLong(0)
    private val webViewUnavailableCount = AtomicLong(0)

    // Service metrics
    private val serviceStartCount = AtomicLong(0)
    private val serviceStopsByReason = ConcurrentHashMap<String, AtomicLong>()
    private val serviceRequestCount = AtomicLong(0)
    @Volatile
    private var serviceStartTime: Long? = null

    // Session info
    private var sessionStartTime = System.currentTimeMillis()

    /**
     * Record a new request
     */
    fun recordRequest(type: String, entryPoint: String, caller: String) {
        if (!DebugConfig.isDebugBuild()) return

        totalRequests.incrementAndGet()
        requestsByType.getOrPut(type) { AtomicLong(0) }.incrementAndGet()
        requestsByEntryPoint.getOrPut(entryPoint) { AtomicLong(0) }.incrementAndGet()

        // Normalize caller to package name only
        val normalizedCaller = caller.substringBefore("/")
        requestsByCaller.getOrPut(normalizedCaller) { AtomicLong(0) }.incrementAndGet()

        Log.d(TAG, "Request recorded: type=$type, entry=$entryPoint, caller=$normalizedCaller")
    }

    /**
     * Record a successful operation with duration
     */
    fun recordSuccess(durationMs: Long) {
        if (!DebugConfig.isDebugBuild()) return

        successCount.incrementAndGet()

        // Track duration in rolling window
        synchronized(recentDurations) {
            recentDurations.add(durationMs)
            while (recentDurations.size > MAX_RECENT_DURATIONS) {
                recentDurations.removeAt(0)
            }
        }

        // Track slow requests
        if (durationMs > SLOW_REQUEST_THRESHOLD_MS) {
            slowRequestCount.incrementAndGet()
        }

        Log.d(TAG, "Success recorded: duration=${durationMs}ms")
    }

    /**
     * Record a failed operation
     */
    fun recordFailure(errorType: String) {
        if (!DebugConfig.isDebugBuild()) return

        failureCount.incrementAndGet()
        errorsByType.getOrPut(errorType) { AtomicLong(0) }.incrementAndGet()

        Log.d(TAG, "Failure recorded: type=$errorType")
    }

    /**
     * Record a cache hit (result returned from cache)
     */
    fun recordCacheHit() {
        if (!DebugConfig.isDebugBuild()) return
        cacheHits.incrementAndGet()
        Log.d(TAG, "Cache hit recorded")
    }

    /**
     * Record a duplicate request blocked
     */
    fun recordDuplicateBlocked() {
        if (!DebugConfig.isDebugBuild()) return
        duplicatesBlocked.incrementAndGet()
        Log.d(TAG, "Duplicate blocked")
    }

    /**
     * Record a rate limited request
     */
    fun recordRateLimited() {
        if (!DebugConfig.isDebugBuild()) return
        rateLimited.incrementAndGet()
        Log.d(TAG, "Rate limited")
    }

    /**
     * Record a permission prompt shown to user
     */
    fun recordPermissionPrompt() {
        if (!DebugConfig.isDebugBuild()) return
        permissionPromptsShown.incrementAndGet()
        Log.d(TAG, "Permission prompt shown")
    }

    /**
     * Record user's permission decision
     */
    fun recordPermissionDecision(approved: Boolean) {
        if (!DebugConfig.isDebugBuild()) return
        if (approved) {
            permissionApprovals.incrementAndGet()
        } else {
            permissionDenials.incrementAndGet()
        }
        Log.d(TAG, "Permission decision: approved=$approved")
    }

    /**
     * Record wake lock acquisition
     */
    fun recordWakeLockAcquisition() {
        if (!DebugConfig.isDebugBuild()) return
        wakeLockAcquisitions.incrementAndGet()
    }

    /**
     * Record WebView unavailable event
     */
    fun recordWebViewUnavailable() {
        if (!DebugConfig.isDebugBuild()) return
        webViewUnavailableCount.incrementAndGet()
        Log.d(TAG, "WebView unavailable")
    }

    /**
     * Record wakeup Intent sent to MainActivity
     */
    fun recordWakeupIntent() {
        if (!DebugConfig.isDebugBuild()) return
        Log.d(TAG, "Wakeup Intent sent")
    }

    /**
     * Record service start
     */
    fun recordServiceStart() {
        if (!DebugConfig.isDebugBuild()) return
        serviceStartCount.incrementAndGet()
        serviceStartTime = System.currentTimeMillis()
        Log.d(TAG, "Service started")
    }

    /**
     * Record service stop with reason
     */
    fun recordServiceStop(reason: String) {
        if (!DebugConfig.isDebugBuild()) return
        serviceStopsByReason.getOrPut(reason) { AtomicLong(0) }.incrementAndGet()
        val uptime = getServiceUptime()
        serviceStartTime = null
        Log.d(TAG, "Service stopped: reason=$reason, uptime=${uptime}ms")
    }

    /**
     * Record a request handled by the service
     */
    fun recordServiceRequest() {
        if (!DebugConfig.isDebugBuild()) return
        serviceRequestCount.incrementAndGet()
        Log.d(TAG, "Service request recorded")
    }

    /**
     * Get current service uptime in milliseconds.
     * Returns 0 if service is not running.
     */
    fun getServiceUptime(): Long {
        val startTime = serviceStartTime ?: return 0L
        return System.currentTimeMillis() - startTime
    }

    /**
     * Check if service is currently tracked as running
     */
    fun isServiceTrackedAsRunning(): Boolean = serviceStartTime != null

    /**
     * Get a snapshot of all metrics for display
     */
    fun getSnapshot(): MetricsSnapshot {
        val durations = synchronized(recentDurations) { recentDurations.toList() }
        val sortedDurations = durations.sorted()

        val avgDuration = if (durations.isNotEmpty()) durations.average().toLong() else 0L
        val p95Duration = if (sortedDurations.isNotEmpty()) {
            val index = (sortedDurations.size * 0.95).toInt().coerceAtMost(sortedDurations.size - 1)
            sortedDurations[index]
        } else 0L

        val totalSuccess = successCount.get()
        val totalFailure = failureCount.get()
        val successRate = if (totalSuccess + totalFailure > 0) {
            totalSuccess.toDouble() / (totalSuccess + totalFailure) * 100
        } else 0.0

        val totalApprovals = permissionApprovals.get()
        val totalDenials = permissionDenials.get()
        val approvalRate = if (totalApprovals + totalDenials > 0) {
            totalApprovals.toDouble() / (totalApprovals + totalDenials) * 100
        } else 0.0

        return MetricsSnapshot(
            sessionDurationMs = System.currentTimeMillis() - sessionStartTime,
            totalRequests = totalRequests.get(),
            requestsByType = requestsByType.mapValues { it.value.get() },
            requestsByEntryPoint = requestsByEntryPoint.mapValues { it.value.get() },
            requestsByCaller = requestsByCaller.mapValues { it.value.get() },
            successCount = totalSuccess,
            failureCount = totalFailure,
            successRate = successRate,
            errorsByType = errorsByType.mapValues { it.value.get() },
            avgDurationMs = avgDuration,
            p95DurationMs = p95Duration,
            slowRequestCount = slowRequestCount.get(),
            cacheHits = cacheHits.get(),
            duplicatesBlocked = duplicatesBlocked.get(),
            rateLimited = rateLimited.get(),
            permissionPromptsShown = permissionPromptsShown.get(),
            permissionApprovalRate = approvalRate,
            wakeLockAcquisitions = wakeLockAcquisitions.get(),
            webViewUnavailableCount = webViewUnavailableCount.get(),
            serviceStartCount = serviceStartCount.get(),
            serviceStopsByReason = serviceStopsByReason.mapValues { it.value.get() },
            serviceRequestCount = serviceRequestCount.get(),
            serviceUptimeMs = getServiceUptime()
        )
    }

    /**
     * Reset all metrics
     */
    fun reset() {
        Log.i(TAG, "Resetting all metrics")

        totalRequests.set(0)
        requestsByType.clear()
        requestsByEntryPoint.clear()
        requestsByCaller.clear()

        successCount.set(0)
        failureCount.set(0)
        errorsByType.clear()

        synchronized(recentDurations) { recentDurations.clear() }
        slowRequestCount.set(0)

        cacheHits.set(0)
        duplicatesBlocked.set(0)
        rateLimited.set(0)

        permissionPromptsShown.set(0)
        permissionApprovals.set(0)
        permissionDenials.set(0)

        wakeLockAcquisitions.set(0)
        webViewUnavailableCount.set(0)

        serviceStartCount.set(0)
        serviceStopsByReason.clear()
        serviceRequestCount.set(0)
        serviceStartTime = null

        sessionStartTime = System.currentTimeMillis()
    }
}

/**
 * Immutable snapshot of metrics for display
 */
data class MetricsSnapshot(
    val sessionDurationMs: Long,
    val totalRequests: Long,
    val requestsByType: Map<String, Long>,
    val requestsByEntryPoint: Map<String, Long>,
    val requestsByCaller: Map<String, Long>,
    val successCount: Long,
    val failureCount: Long,
    val successRate: Double,
    val errorsByType: Map<String, Long>,
    val avgDurationMs: Long,
    val p95DurationMs: Long,
    val slowRequestCount: Long,
    val cacheHits: Long,
    val duplicatesBlocked: Long,
    val rateLimited: Long,
    val permissionPromptsShown: Long,
    val permissionApprovalRate: Double,
    val wakeLockAcquisitions: Long,
    val webViewUnavailableCount: Long,
    val serviceStartCount: Long,
    val serviceStopsByReason: Map<String, Long>,
    val serviceRequestCount: Long,
    val serviceUptimeMs: Long
) {
    /**
     * Format session duration as human readable string
     */
    fun formatSessionDuration(): String {
        return formatDuration(sessionDurationMs)
    }

    /**
     * Format service uptime as human readable string
     */
    fun formatServiceUptime(): String {
        if (serviceUptimeMs == 0L) return "Not running"
        return formatDuration(serviceUptimeMs)
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m ${seconds % 60}s"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}
