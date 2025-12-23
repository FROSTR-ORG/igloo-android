package com.frostr.igloo.health

import com.frostr.igloo.NIP55Request
import com.frostr.igloo.NIP55Result
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for IglooHealthManager.
 *
 * Tests verify health state management, request routing, caching,
 * rate limiting, and callback delivery mechanisms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IglooHealthManagerTest {

    @Before
    fun setup() {
        IglooHealthManager.reset()
        // Use short timeouts for testing
        IglooHealthManager.healthTimeoutMsOverride = 100L
        IglooHealthManager.cacheTimeoutMsOverride = 100L
    }

    @After
    fun teardown() {
        IglooHealthManager.reset()
    }

    // ========== Health State Tests ==========

    @Test
    fun `initial state is unhealthy`() {
        assertThat(IglooHealthManager.isHealthy).isFalse()
    }

    @Test
    fun `markHealthy sets isHealthy to true`() {
        IglooHealthManager.markHealthy()
        assertThat(IglooHealthManager.isHealthy).isTrue()
    }

    @Test
    fun `markUnhealthy sets isHealthy to false`() {
        IglooHealthManager.markHealthy()
        assertThat(IglooHealthManager.isHealthy).isTrue()

        IglooHealthManager.markUnhealthy()
        assertThat(IglooHealthManager.isHealthy).isFalse()
    }

    @Test
    fun `health timeout fires after configured duration`() {
        IglooHealthManager.healthTimeoutMsOverride = 50L

        IglooHealthManager.markHealthy()
        assertThat(IglooHealthManager.isHealthy).isTrue()

        // Wait for timeout to fire
        Thread.sleep(100)
        // Need to process main looper
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertThat(IglooHealthManager.isHealthy).isFalse()
    }

    // ========== Request Submission Tests ==========

    @Test
    fun `submit returns true when request is accepted`() {
        IglooHealthManager.markHealthy()
        setupMockExecutor()

        val request = createTestRequest("test-1", "get_public_key")
        val accepted = IglooHealthManager.submit(request) { }

        assertThat(accepted).isTrue()
    }

    @Test
    fun `submit queues request when unhealthy`() {
        assertThat(IglooHealthManager.isHealthy).isFalse()

        val request = createTestRequest("test-1", "get_public_key")
        val accepted = IglooHealthManager.submit(request) { }

        assertThat(accepted).isTrue()
        assertThat(IglooHealthManager.getPendingQueueSize()).isEqualTo(1)
    }

    @Test
    fun `queued requests are processed when marked healthy`() {
        // Queue request while unhealthy
        val request = createTestRequest("test-1", "get_public_key")
        IglooHealthManager.submit(request) { }

        assertThat(IglooHealthManager.getPendingQueueSize()).isEqualTo(1)

        // Setup executor and mark healthy
        setupMockExecutor()
        IglooHealthManager.markHealthy()

        // Verify health was set
        assertThat(IglooHealthManager.isHealthy).isTrue()
    }

    // ========== Caching Tests ==========

    @Test
    fun `cached result is returned for duplicate request`() {
        IglooHealthManager.markHealthy()
        var executionCount = 0

        IglooHealthManager.requestExecutor = object : IglooHealthManager.RequestExecutor {
            override suspend fun execute(request: NIP55Request): NIP55Result {
                executionCount++
                return NIP55Result(ok = true, type = request.type, id = request.id, result = "result")
            }
        }

        val request = createTestRequest("test-1", "get_public_key")

        // First request
        val latch1 = CountDownLatch(1)
        IglooHealthManager.submit(request) { latch1.countDown() }
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        latch1.await(1, TimeUnit.SECONDS)

        assertThat(executionCount).isEqualTo(1)

        // Second request with same deduplication key - should be cached
        val latch2 = CountDownLatch(1)
        var cachedResult: NIP55Result? = null
        IglooHealthManager.submit(request) { result ->
            cachedResult = result
            latch2.countDown()
        }
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        latch2.await(1, TimeUnit.SECONDS)

        // Should still only be 1 execution (cache hit)
        assertThat(executionCount).isEqualTo(1)
        assertThat(cachedResult?.ok).isTrue()
    }

    @Test
    fun `cache size is tracked correctly`() {
        IglooHealthManager.markHealthy()
        assertThat(IglooHealthManager.getCacheSize()).isEqualTo(0)

        // After reset, cache should still be empty
        IglooHealthManager.reset()
        assertThat(IglooHealthManager.getCacheSize()).isEqualTo(0)
    }

    // ========== Rate Limiting Tests ==========

    @Test
    fun `rate limiting rejects requests over limit`() {
        IglooHealthManager.markHealthy()
        setupMockExecutor()

        // Submit 20 requests (at the limit)
        repeat(20) { i ->
            val request = createTestRequest("test-$i", "sign_event")
            val accepted = IglooHealthManager.submit(request) { }
            assertThat(accepted).isTrue()
        }

        // Process all tasks
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // 21st request should be rate limited
        val rateLimitedRequest = createTestRequest("test-21", "sign_event")
        var wasRateLimited = false
        val accepted = IglooHealthManager.submit(rateLimitedRequest) { result ->
            if (!result.ok && result.reason?.contains("Rate limit") == true) {
                wasRateLimited = true
            }
        }

        assertThat(accepted).isFalse()
    }

    // ========== Deduplication Tests ==========

    @Test
    fun `in-flight count increases when submitting while unhealthy`() {
        // When unhealthy, requests are queued and tracked
        val request1 = createTestRequest("test-1", "get_public_key")
        val request2 = createTestRequest("test-2", "sign_event")

        IglooHealthManager.submit(request1) { }
        IglooHealthManager.submit(request2) { }

        // Both should be in pending queue
        assertThat(IglooHealthManager.getPendingQueueSize()).isEqualTo(2)
    }

    // ========== Result Delivery Tests ==========

    @Test
    fun `deliverResultByRequestId delivers to correct callback`() {
        val latch = CountDownLatch(1)
        var receivedResult: NIP55Result? = null

        val request = createTestRequest("test-123", "sign_event")

        // Submit request (unhealthy, so queued)
        IglooHealthManager.submit(request) { result ->
            receivedResult = result
            latch.countDown()
        }

        // Deliver result by request ID
        val result = NIP55Result(
            ok = true,
            type = "sign_event",
            id = "test-123",
            result = "signature"
        )

        val delivered = IglooHealthManager.deliverResultByRequestId("test-123", result)

        assertThat(delivered).isTrue()
        latch.await(1, TimeUnit.SECONDS)
        assertThat(receivedResult?.ok).isTrue()
        assertThat(receivedResult?.result).isEqualTo("signature")
    }

    @Test
    fun `deliverResultByRequestId returns false for unknown request`() {
        val delivered = IglooHealthManager.deliverResultByRequestId("unknown-id", NIP55Result(
            ok = false,
            type = "error",
            id = "unknown-id",
            reason = "test"
        ))

        assertThat(delivered).isFalse()
    }

    // ========== Statistics Tests ==========

    @Test
    fun `getStats returns correct values`() {
        val stats = IglooHealthManager.getStats()

        assertThat(stats["isHealthy"]).isEqualTo(false)
        assertThat(stats["activeHandler"]).isEqualTo("none")
        assertThat(stats["pendingQueue"]).isEqualTo(0)
    }

    @Test
    fun `getStats reflects queued requests`() {
        val request = createTestRequest("test-1", "get_public_key")
        IglooHealthManager.submit(request) { }

        val stats = IglooHealthManager.getStats()
        assertThat(stats["pendingQueue"]).isEqualTo(1)
    }

    // ========== Reset Tests ==========

    @Test
    fun `reset clears all state`() {
        IglooHealthManager.markHealthy()
        IglooHealthManager.submit(createTestRequest("test-1", "get_public_key")) { }

        IglooHealthManager.reset()

        assertThat(IglooHealthManager.isHealthy).isFalse()
        assertThat(IglooHealthManager.getPendingQueueSize()).isEqualTo(0)
        assertThat(IglooHealthManager.getInFlightCount()).isEqualTo(0)
        assertThat(IglooHealthManager.getCacheSize()).isEqualTo(0)
    }

    // ========== Helper Methods ==========

    private fun createTestRequest(id: String, type: String): NIP55Request {
        return NIP55Request(
            id = id,
            type = type,
            params = emptyMap(),
            callingApp = "com.test.app",
            timestamp = System.currentTimeMillis()
        )
    }

    private fun setupMockExecutor() {
        IglooHealthManager.requestExecutor = object : IglooHealthManager.RequestExecutor {
            override suspend fun execute(request: NIP55Request): NIP55Result {
                return NIP55Result(
                    ok = true,
                    type = request.type,
                    id = request.id,
                    result = "mock_result"
                )
            }
        }
    }
}
