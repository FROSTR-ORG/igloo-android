package com.frostr.igloo.health

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.frostr.igloo.NIP55Request
import com.frostr.igloo.NIP55Result
import com.frostr.igloo.debug.NIP55Metrics
import com.frostr.igloo.debug.NIP55TraceContext
import com.frostr.igloo.util.NIP55Deduplicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Central coordinator for NIP-55 request routing with health-based decisions.
 *
 * Key insight: WebView cannot survive in background (Android throttles it within seconds).
 * This manager accepts that health goes stale after 5 seconds and enables focus-switch
 * to Igloo whenever health becomes stale.
 *
 * Responsibilities:
 * - Health state tracking (isHealthy with 5-second timeout)
 * - Singleton Intent handler coordination (only one active at a time)
 * - Request queuing when unhealthy (pending queue)
 * - In-flight deduplication (multiple callers share one execution)
 * - Result caching (5s TTL, max 100 entries)
 * - Rate limiting (20 requests/second per calling app)
 * - Batching for sign_event (100ms window)
 *
 * This replaces NIP55RequestQueue with additional health management.
 */
object IglooHealthManager {

    private const val TAG = "IglooHealthManager"

    // ========== Configuration ==========

    private const val HEALTH_TIMEOUT_MS = 10_000L          // 10 second health timeout
    private const val CACHE_TTL_MS = 10_000L               // 10 second cache TTL (match health timeout)
    private const val BATCH_WINDOW_MS = 100L               // 100ms batch window for sign_event
    private const val MAX_QUEUE_SIZE = 50                  // Max pending requests when unhealthy
    private const val MAX_CACHE_SIZE = 100                 // Max cached results
    private const val MAX_REQUESTS_PER_APP_PER_SECOND = 20 // Rate limit

    // ========== Health State ==========

    /**
     * Whether the WebView/PWA is currently healthy and ready for signing.
     * Becomes false after 5 seconds of inactivity (no successful signing).
     * When false, requests are queued and a focus-switch to Igloo is triggered.
     */
    @Volatile
    var isHealthy: Boolean = false
        private set

    /**
     * The currently active Intent handler (if any).
     * Only one handler should be active at a time - others queue and finish.
     */
    @Volatile
    var activeHandler: Activity? = null
        private set

    /**
     * Whether a wakeup Intent has been sent while unhealthy.
     * Reset when system becomes healthy again.
     * This ensures only ONE wakeup is triggered for a batch of requests.
     */
    @Volatile
    private var wakeupSent: Boolean = false

    /**
     * Application context for sending wakeup Intents.
     */
    @Volatile
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var healthTimeoutRunnable: Runnable? = null

    // Testability overrides
    @VisibleForTesting
    var healthTimeoutMsOverride: Long? = null

    @VisibleForTesting
    var cacheTimeoutMsOverride: Long? = null

    private val effectiveHealthTimeout: Long
        get() = healthTimeoutMsOverride ?: HEALTH_TIMEOUT_MS

    private val effectiveCacheTimeout: Long
        get() = cacheTimeoutMsOverride ?: CACHE_TTL_MS

    /**
     * Initialize with application context for sending wakeup Intents.
     * Should be called from Application.onCreate() or MainActivity.onCreate().
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        Log.d(TAG, "Initialized with context: ${appContext?.packageName}")
    }

    // ========== Request Executor ==========

    /**
     * Interface for executing NIP-55 requests.
     * Allows injection of mock executors for testing.
     */
    interface RequestExecutor {
        suspend fun execute(request: NIP55Request): NIP55Result
    }

    /**
     * Injectable executor for request processing.
     * In production, this uses AsyncBridge via MainActivity.
     * In tests, this can be mocked.
     */
    @Volatile
    var requestExecutor: RequestExecutor? = null

    // ========== Data Structures ==========

    /**
     * Pending request when unhealthy - waiting for WebView to become available.
     */
    data class PendingRequest(
        val request: NIP55Request,
        val dedupeKey: String,
        val callback: (NIP55Result) -> Unit,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Cached result with timestamp for TTL checking.
     */
    data class CachedResult(
        val result: NIP55Result,
        val timestamp: Long
    )

    /**
     * Rate limit tracking per calling app.
     */
    data class RateLimitEntry(
        var windowStart: Long,
        var requestCount: Int
    )

    /**
     * Batched sign_event request.
     */
    data class BatchedRequest(
        val request: NIP55Request,
        val dedupeKey: String,
        val callback: (NIP55Result) -> Unit
    )

    // Pending queue (requests waiting for WebView when unhealthy)
    private val pendingQueue = ConcurrentLinkedQueue<PendingRequest>()

    // In-flight requests: dedupeKey -> list of callbacks waiting for same request
    private val inFlightRequests = ConcurrentHashMap<String, MutableList<(NIP55Result) -> Unit>>()

    // Request ID to dedupeKey mapping for delivering results by request ID (e.g., from permission dialogs)
    private val requestIdToDedupeKey = ConcurrentHashMap<String, String>()

    // Result cache: dedupeKey -> cached result with timestamp
    private val resultCache = ConcurrentHashMap<String, CachedResult>()

    // Rate limiting: callingApp -> rate limit entry
    private val rateLimiters = ConcurrentHashMap<String, RateLimitEntry>()

    // Batching for sign_event
    private val signEventBatch = ConcurrentLinkedQueue<BatchedRequest>()
    private var batchTimer: Runnable? = null
    @Volatile
    private var batchScheduled = false
    private val inFlightSigningCount = java.util.concurrent.atomic.AtomicInteger(0)

    // Coroutine scope for async operations
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ========== Health Management ==========

    /**
     * Mark the system as healthy. Called when PWA/WebView is ready.
     * Schedules the health timeout and processes any queued requests.
     */
    fun markHealthy() {
        Log.d(TAG, "Marking healthy, pending queue size: ${pendingQueue.size}")
        isHealthy = true
        wakeupSent = false  // Reset so next unhealthy period triggers new wakeup
        scheduleHealthTimeout()
        processPendingQueue()
        NIP55Metrics.recordServiceStart()
    }

    /**
     * Mark the system as unhealthy. Called when health timeout fires
     * or when WebView becomes unavailable.
     */
    fun markUnhealthy() {
        Log.d(TAG, "Marking unhealthy")
        isHealthy = false
        cancelHealthTimeout()
        NIP55Metrics.recordServiceStop("health_timeout")
    }

    /**
     * Reset the health timeout (called after each successful signing).
     * This keeps the system healthy as long as signing is active.
     */
    fun resetHealthTimeout() {
        if (isHealthy) {
            scheduleHealthTimeout()
        }
    }

    /**
     * Callback for when bootstrap processing completes.
     */
    @Volatile
    private var bootstrapCompleteCallback: (() -> Unit)? = null

    /**
     * Try to execute ONE request from the queue.
     * Called when MainActivity is woken up and ready to process.
     *
     * This is the "bootstrap" process for cold starts:
     * - Try one request at a time
     * - If success: markHealthy() enables full queue processing
     * - If failure: automatically tries the next one
     *
     * Only after a successful request do we trust the system for batch processing.
     *
     * @param onComplete Optional callback invoked when bootstrap is done (success or queue exhausted)
     */
    fun tryProcessOneFromQueue(onComplete: (() -> Unit)? = null) {
        Log.d(TAG, "Trying to process ONE from queue (healthy=$isHealthy, queue size=${pendingQueue.size})")

        // Store callback for when processing completes
        if (onComplete != null) {
            bootstrapCompleteCallback = onComplete
        }

        if (pendingQueue.isEmpty()) {
            Log.d(TAG, "No queued requests to process")
            // Invoke callback immediately if nothing to process
            bootstrapCompleteCallback?.invoke()
            bootstrapCompleteCallback = null
            return
        }

        // Take ONE request from the queue
        val pending = pendingQueue.poll() ?: run {
            bootstrapCompleteCallback?.invoke()
            bootstrapCompleteCallback = null
            return
        }

        Log.d(TAG, "Processing single queued request: ${pending.request.type} (id=${pending.request.id})")

        // Execute this one request
        executeRequestWithRetry(pending)
    }

    /**
     * Execute a request, and if it fails, try the next one from the queue.
     * On success, mark healthy which enables normal queue processing.
     */
    private fun executeRequestWithRetry(pending: PendingRequest) {
        scope.launch {
            val traceId = NIP55TraceContext.extractTraceId(pending.request.id)
            val startTime = System.currentTimeMillis()

            try {
                val executor = requestExecutor
                if (executor == null) {
                    Log.w(TAG, "No executor available - retrying next from queue")
                    NIP55Metrics.recordWebViewUnavailable()
                    // Deliver error for this request
                    pending.callback(NIP55Result(
                        ok = false,
                        type = pending.request.type,
                        id = pending.request.id,
                        reason = "Signer not available"
                    ))
                    // Try next one (will invoke callback if queue exhausted)
                    tryProcessOneFromQueue()
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    executor.execute(pending.request)
                }

                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "Bootstrap request completed: ok=${result.ok}, duration=${duration}ms")

                // Cache and deliver result
                cacheResult(pending.dedupeKey, result)
                deliverResult(pending.dedupeKey, result)

                if (result.ok) {
                    // SUCCESS! Now mark healthy and process remaining queue
                    Log.d(TAG, "Bootstrap successful - marking healthy and processing queue")
                    NIP55Metrics.recordSuccess(duration)

                    // Invoke bootstrap complete callback BEFORE markHealthy
                    // This allows the caller to move to background while queue processes
                    bootstrapCompleteCallback?.invoke()
                    bootstrapCompleteCallback = null

                    markHealthy()  // This will process the rest of the queue
                } else {
                    // Failed - try next one from queue
                    Log.d(TAG, "Bootstrap request failed: ${result.reason} - trying next")
                    NIP55Metrics.recordFailure(result.reason ?: "unknown")
                    tryProcessOneFromQueue()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap request error", e)
                // Deliver error
                pending.callback(NIP55Result(
                    ok = false,
                    type = pending.request.type,
                    id = pending.request.id,
                    reason = e.message ?: "Execution failed"
                ))
                NIP55Metrics.recordFailure(e.message ?: "exception")
                // Try next one (will invoke callback if queue exhausted)
                tryProcessOneFromQueue()
            }
        }
    }

    private fun scheduleHealthTimeout() {
        cancelHealthTimeout()
        healthTimeoutRunnable = Runnable {
            Log.d(TAG, "Health timeout fired after ${effectiveHealthTimeout}ms")
            markUnhealthy()
        }
        mainHandler.postDelayed(healthTimeoutRunnable!!, effectiveHealthTimeout)
    }

    private fun cancelHealthTimeout() {
        healthTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        healthTimeoutRunnable = null
    }

    /**
     * Trigger a wakeup Intent to MainActivity if not already sent.
     * This ensures only ONE wakeup is triggered for a batch of requests.
     */
    private fun triggerWakeupIfNeeded() {
        if (wakeupSent) {
            Log.d(TAG, "Wakeup already sent, skipping")
            return
        }

        val context = appContext
        if (context == null) {
            Log.w(TAG, "No context available for wakeup Intent")
            return
        }

        synchronized(this) {
            // Double-check after acquiring lock
            if (wakeupSent) return
            wakeupSent = true
        }

        Log.d(TAG, "Triggering wakeup Intent to MainActivity (queue size: ${pendingQueue.size})")

        try {
            val intent = Intent(context, com.frostr.igloo.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("nip55_wakeup", true)
            }
            context.startActivity(intent)
            NIP55Metrics.recordWakeupIntent()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send wakeup Intent", e)
            wakeupSent = false  // Reset so we can try again
        }
    }

    // ========== Handler Singleton ==========

    /**
     * Try to become the active Intent handler.
     * Only one handler should be active at a time.
     *
     * @return true if this handler is now active, false if another is already active
     */
    @Synchronized
    fun tryBecomeActiveHandler(handler: Activity): Boolean {
        if (activeHandler != null) {
            Log.d(TAG, "Another handler is already active: ${activeHandler?.javaClass?.simpleName}")
            return false
        }
        activeHandler = handler
        Log.d(TAG, "Handler became active: ${handler.javaClass.simpleName}")
        return true
    }

    /**
     * Release the active handler slot.
     * Only the current active handler can release itself.
     */
    @Synchronized
    fun releaseActiveHandler(handler: Activity) {
        if (activeHandler == handler) {
            Log.d(TAG, "Handler released: ${handler.javaClass.simpleName}")
            activeHandler = null
        }
    }

    /**
     * Clear any stale active handler reference.
     * Called when MainActivity starts to clear references from previous app sessions.
     * The activeHandler reference becomes stale when the app is killed/restarted.
     */
    @Synchronized
    fun clearStaleActiveHandler() {
        if (activeHandler != null) {
            Log.d(TAG, "Clearing stale active handler: ${activeHandler?.javaClass?.simpleName}")
            activeHandler = null
        }
    }

    // ========== Request Submission ==========

    /**
     * Submit a NIP-55 request for processing.
     *
     * Handles:
     * - Cache lookup (return immediately if cached)
     * - In-flight deduplication (merge with existing request)
     * - Rate limiting (reject if over limit)
     * - Queue management (queue if unhealthy, process if healthy)
     *
     * @param request The NIP-55 request
     * @param callback Callback for result delivery
     * @return true if request was accepted, false if rejected
     */
    fun submit(
        request: NIP55Request,
        callback: (NIP55Result) -> Unit
    ): Boolean {
        val traceId = NIP55TraceContext.extractTraceId(request.id)

        // Generate dedup key
        val dedupeKey = NIP55Deduplicator.getDeduplicationKey(
            request.callingApp,
            request.type,
            request.params.mapValues { it.value },
            request.id
        )

        NIP55TraceContext.log(traceId, "HEALTH_SUBMIT",
            "type" to request.type,
            "healthy" to isHealthy,
            "dedupeKey" to dedupeKey.take(24))

        // 1. Check rate limit
        if (!checkRateLimit(request.callingApp)) {
            NIP55TraceContext.log(traceId, "HEALTH_RATE_LIMITED")
            NIP55Metrics.recordRateLimited()
            callback(NIP55Result(
                ok = false,
                type = request.type,
                id = request.id,
                reason = "Rate limit exceeded"
            ))
            return false
        }

        // 2. Check cache
        getCachedResult(dedupeKey)?.let { cached ->
            NIP55TraceContext.log(traceId, "HEALTH_CACHE_HIT")
            NIP55Metrics.recordCacheHit()
            callback(cached)
            return true
        }

        // 3. Check for in-flight duplicate
        synchronized(inFlightRequests) {
            inFlightRequests[dedupeKey]?.let { callbacks ->
                NIP55TraceContext.log(traceId, "HEALTH_DUPLICATE_MERGED",
                    "waiters" to callbacks.size)
                NIP55Metrics.recordDuplicateBlocked()
                callbacks.add(callback)
                return true
            }
        }

        // 4. Check backpressure (only when unhealthy)
        if (!isHealthy && pendingQueue.size >= MAX_QUEUE_SIZE) {
            NIP55TraceContext.log(traceId, "HEALTH_QUEUE_FULL",
                "size" to pendingQueue.size)
            callback(NIP55Result(
                ok = false,
                type = request.type,
                id = request.id,
                reason = "Signer queue full"
            ))
            return false
        }

        // 5. Register as in-flight
        synchronized(inFlightRequests) {
            inFlightRequests[dedupeKey] = mutableListOf(callback)
            // Store request ID mapping for permission dialog result delivery
            requestIdToDedupeKey[request.id] = dedupeKey
        }

        // 6. Queue or process based on health
        if (!isHealthy) {
            // Queue for later when healthy
            pendingQueue.offer(PendingRequest(request, dedupeKey, callback))
            NIP55TraceContext.log(traceId, "HEALTH_QUEUED",
                "queueSize" to pendingQueue.size)

            // Trigger ONE wakeup Intent for this batch of requests
            triggerWakeupIfNeeded()
            return true
        }

        // 7. Healthy - process (with batching for sign_event)
        if (request.type == "sign_event") {
            addToBatch(request, dedupeKey, callback)
            NIP55TraceContext.log(traceId, "HEALTH_BATCHED")
        } else {
            executeRequest(request, dedupeKey)
            NIP55TraceContext.log(traceId, "HEALTH_IMMEDIATE")
        }

        return true
    }

    // ========== Queue Processing ==========

    private fun processPendingQueue() {
        if (!isHealthy) return

        mainHandler.post {
            Log.d(TAG, "Processing pending queue: ${pendingQueue.size} requests")

            while (isHealthy && pendingQueue.isNotEmpty()) {
                val pending = pendingQueue.poll() ?: break
                val traceId = NIP55TraceContext.extractTraceId(pending.request.id)

                // Skip if already has cached result
                getCachedResult(pending.dedupeKey)?.let { cached ->
                    NIP55TraceContext.log(traceId, "HEALTH_QUEUE_CACHE_HIT")
                    deliverResult(pending.dedupeKey, cached)
                    return@let
                }

                if (pending.request.type == "sign_event") {
                    addToBatch(pending.request, pending.dedupeKey, pending.callback)
                } else {
                    executeRequest(pending.request, pending.dedupeKey)
                }
            }

            // Flush any accumulated batch
            flushBatch()
        }
    }

    // ========== Batching (sign_event only) ==========

    private fun addToBatch(
        request: NIP55Request,
        dedupeKey: String,
        callback: (NIP55Result) -> Unit
    ) {
        signEventBatch.offer(BatchedRequest(request, dedupeKey, callback))
        scheduleBatchFlush()
    }

    private fun scheduleBatchFlush() {
        if (batchScheduled) return

        synchronized(this) {
            if (batchScheduled) return
            batchScheduled = true

            batchTimer = Runnable {
                batchScheduled = false
                batchTimer = null
                // If signing is in progress, don't flush yet - let more requests accumulate
                // executeRequest will trigger flush when it completes
                if (inFlightSigningCount.get() == 0) {
                    flushBatch()
                }
            }
            mainHandler.postDelayed(batchTimer!!, BATCH_WINDOW_MS)
        }
    }

    private fun flushBatch() {
        val batch = mutableListOf<BatchedRequest>()
        while (signEventBatch.isNotEmpty()) {
            signEventBatch.poll()?.let { batch.add(it) }
        }

        if (batch.isEmpty()) return

        Log.d(TAG, "Flushing batch of ${batch.size} sign_event requests")

        scope.launch {
            for (batched in batch) {
                executeRequest(batched.request, batched.dedupeKey)
            }
        }
    }

    /**
     * Called when a signing request completes.
     * Flushes any accumulated requests if this was the last in-flight signing.
     */
    private fun onSigningComplete() {
        val remaining = inFlightSigningCount.decrementAndGet()
        if (remaining == 0 && signEventBatch.isNotEmpty()) {
            // All signings done, flush accumulated requests
            mainHandler.post { flushBatch() }
        }
    }

    // ========== Request Execution ==========

    private fun executeRequest(request: NIP55Request, dedupeKey: String) {
        inFlightSigningCount.incrementAndGet()

        scope.launch {
            val traceId = NIP55TraceContext.extractTraceId(request.id)
            val startTime = System.currentTimeMillis()

            try {
                NIP55TraceContext.log(traceId, "HEALTH_EXECUTE_START")

                val executor = requestExecutor
                if (executor == null) {
                    NIP55TraceContext.logError(traceId, "HEALTH_EXECUTE", "No executor available")
                    NIP55Metrics.recordWebViewUnavailable()
                    deliverResult(dedupeKey, NIP55Result(
                        ok = false,
                        type = request.type,
                        id = request.id,
                        reason = "Signer not available"
                    ))
                    return@launch
                }

                // Execute on IO dispatcher to avoid blocking main thread
                val result = withContext(Dispatchers.IO) {
                    executor.execute(request)
                }

                val duration = System.currentTimeMillis() - startTime
                NIP55TraceContext.log(traceId, "HEALTH_EXECUTE_COMPLETE",
                    "ok" to result.ok,
                    "duration_ms" to duration)

                // Cache and deliver result
                cacheResult(dedupeKey, result)
                deliverResult(dedupeKey, result)

                // Mark healthy and reset timeout on success
                if (result.ok) {
                    markHealthy()  // This also schedules the timeout
                    NIP55Metrics.recordSuccess(duration)
                } else {
                    NIP55Metrics.recordFailure(result.reason ?: "unknown")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error executing request", e)
                NIP55TraceContext.logError(traceId, "HEALTH_EXECUTE", e.message ?: "Unknown error")

                val errorResult = NIP55Result(
                    ok = false,
                    type = request.type,
                    id = request.id,
                    reason = e.message ?: "Execution failed"
                )
                deliverResult(dedupeKey, errorResult)
                NIP55Metrics.recordFailure(e.message ?: "exception")
            } finally {
                onSigningComplete()
            }
        }
    }

    // ========== Result Delivery ==========

    private fun deliverResult(dedupeKey: String, result: NIP55Result) {
        val callbacks = synchronized(inFlightRequests) {
            inFlightRequests.remove(dedupeKey)
        } ?: return

        // Clean up request ID mapping
        val iterator = requestIdToDedupeKey.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value == dedupeKey) {
                iterator.remove()
            }
        }

        Log.d(TAG, "Delivering result to ${callbacks.size} callback(s)")

        for (callback in callbacks) {
            try {
                callback(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error delivering result to callback", e)
            }
        }
    }

    /**
     * Deliver a result by request ID.
     * Used by permission dialogs and unlock flows that don't have the dedupeKey.
     *
     * @param requestId The request ID
     * @param result The result to deliver
     * @return true if callbacks were found and invoked, false otherwise
     */
    fun deliverResultByRequestId(requestId: String, result: NIP55Result): Boolean {
        val dedupeKey = requestIdToDedupeKey[requestId]
        if (dedupeKey == null) {
            Log.w(TAG, "No dedupeKey found for request ID: $requestId")
            return false
        }

        Log.d(TAG, "Delivering result by request ID: $requestId -> dedupeKey: ${dedupeKey.take(24)}")
        deliverResult(dedupeKey, result)
        return true
    }

    // ========== Caching ==========

    private fun getCachedResult(dedupeKey: String): NIP55Result? {
        val cached = resultCache[dedupeKey] ?: return null
        val age = System.currentTimeMillis() - cached.timestamp

        if (age > effectiveCacheTimeout) {
            resultCache.remove(dedupeKey)
            return null
        }

        return cached.result
    }

    private fun cacheResult(dedupeKey: String, result: NIP55Result) {
        // Don't cache transient errors
        if (!result.ok && result.reason?.let { reason ->
            reason.contains("locked", ignoreCase = true) ||
            reason.contains("not ready", ignoreCase = true) ||
            reason.contains("offline", ignoreCase = true)
        } == true) {
            Log.d(TAG, "Not caching transient error: ${result.reason}")
            return
        }

        // Cleanup if cache too large
        if (resultCache.size >= MAX_CACHE_SIZE) {
            cleanCache()
        }

        resultCache[dedupeKey] = CachedResult(result, System.currentTimeMillis())
    }

    private fun cleanCache() {
        val now = System.currentTimeMillis()
        val iterator = resultCache.entries.iterator()
        var cleaned = 0

        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.timestamp > effectiveCacheTimeout) {
                iterator.remove()
                cleaned++
            }
        }

        if (cleaned > 0) {
            Log.d(TAG, "Cleaned $cleaned expired cache entries")
        }
    }

    // ========== Rate Limiting ==========

    private fun checkRateLimit(callingApp: String): Boolean {
        val now = System.currentTimeMillis()
        val entry = rateLimiters.getOrPut(callingApp) {
            RateLimitEntry(now, 0)
        }

        synchronized(entry) {
            // Reset window if expired (1 second window)
            if (now - entry.windowStart > 1000) {
                entry.windowStart = now
                entry.requestCount = 0
            }

            if (entry.requestCount >= MAX_REQUESTS_PER_APP_PER_SECOND) {
                return false
            }

            entry.requestCount++
            return true
        }
    }

    // ========== Statistics ==========

    /**
     * Get queue statistics for debugging.
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "isHealthy" to isHealthy,
            "activeHandler" to (activeHandler?.javaClass?.simpleName ?: "none"),
            "pendingQueue" to pendingQueue.size,
            "inFlightRequests" to inFlightRequests.size,
            "cachedResults" to resultCache.size,
            "batchPending" to signEventBatch.size,
            "batchScheduled" to batchScheduled
        )
    }

    // ========== Cleanup ==========

    /**
     * Reset all state. Used for testing and cleanup.
     */
    fun reset() {
        Log.d(TAG, "Resetting IglooHealthManager")

        isHealthy = false
        activeHandler = null
        wakeupSent = false

        cancelHealthTimeout()

        // Cancel batch timer
        batchTimer?.let { mainHandler.removeCallbacks(it) }
        batchTimer = null
        batchScheduled = false

        // Cancel and recreate coroutine scope
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        // Clear data structures
        pendingQueue.clear()
        inFlightRequests.clear()
        requestIdToDedupeKey.clear()
        resultCache.clear()
        rateLimiters.clear()
        signEventBatch.clear()
        inFlightSigningCount.set(0)

        // Clear testability overrides
        healthTimeoutMsOverride = null
        cacheTimeoutMsOverride = null
        requestExecutor = null

        // Clear bootstrap callback
        bootstrapCompleteCallback = null
    }

    // ========== Testing Helpers ==========

    @VisibleForTesting
    fun setHealthyForTesting(healthy: Boolean) {
        isHealthy = healthy
    }

    @VisibleForTesting
    fun getPendingQueueSize(): Int = pendingQueue.size

    @VisibleForTesting
    fun getInFlightCount(): Int = inFlightRequests.size

    @VisibleForTesting
    fun getCacheSize(): Int = resultCache.size
}
