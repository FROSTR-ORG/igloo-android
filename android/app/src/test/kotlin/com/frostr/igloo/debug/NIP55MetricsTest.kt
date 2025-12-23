package com.frostr.igloo.debug

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unit tests for NIP55Metrics collector.
 *
 * Uses Robolectric to support Android Log calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NIP55MetricsTest {

    @Before
    fun resetMetrics() {
        NIP55Metrics.reset()
    }

    // === Counter tests ===

    @Test
    fun `recordRequest increments total requests`() {
        NIP55Metrics.recordRequest("sign_event", "intent", "com.example.app")
        NIP55Metrics.recordRequest("sign_event", "intent", "com.example.app")

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.totalRequests).isEqualTo(2)
    }

    @Test
    fun `recordRequest tracks by type`() {
        NIP55Metrics.recordRequest("sign_event", "intent", "app")
        NIP55Metrics.recordRequest("sign_event", "intent", "app")
        NIP55Metrics.recordRequest("get_public_key", "intent", "app")

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.requestsByType["sign_event"]).isEqualTo(2)
        assertThat(snapshot.requestsByType["get_public_key"]).isEqualTo(1)
    }

    @Test
    fun `recordRequest tracks by entry point`() {
        NIP55Metrics.recordRequest("sign_event", "intent", "app")
        NIP55Metrics.recordRequest("sign_event", "content_provider", "app")
        NIP55Metrics.recordRequest("sign_event", "intent", "app")

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.requestsByEntryPoint["intent"]).isEqualTo(2)
        assertThat(snapshot.requestsByEntryPoint["content_provider"]).isEqualTo(1)
    }

    @Test
    fun `recordRequest tracks by caller and normalizes package name`() {
        NIP55Metrics.recordRequest("sign_event", "intent", "com.example.app/Activity")
        NIP55Metrics.recordRequest("sign_event", "intent", "com.example.app/OtherActivity")
        NIP55Metrics.recordRequest("sign_event", "intent", "com.other.app")

        val snapshot = NIP55Metrics.getSnapshot()
        // Should normalize to package name only (strip activity)
        assertThat(snapshot.requestsByCaller["com.example.app"]).isEqualTo(2)
        assertThat(snapshot.requestsByCaller["com.other.app"]).isEqualTo(1)
    }

    // === Success/failure tests ===

    @Test
    fun `recordSuccess increments success count`() {
        NIP55Metrics.recordSuccess(100)
        NIP55Metrics.recordSuccess(200)

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.successCount).isEqualTo(2)
    }

    @Test
    fun `recordSuccess tracks duration`() {
        NIP55Metrics.recordSuccess(100)
        NIP55Metrics.recordSuccess(200)
        NIP55Metrics.recordSuccess(300)

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.avgDurationMs).isEqualTo(200) // (100+200+300)/3
    }

    @Test
    fun `recordFailure increments failure count`() {
        NIP55Metrics.recordFailure("timeout")
        NIP55Metrics.recordFailure("permission_denied")

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.failureCount).isEqualTo(2)
    }

    @Test
    fun `recordFailure tracks error type`() {
        NIP55Metrics.recordFailure("timeout")
        NIP55Metrics.recordFailure("timeout")
        NIP55Metrics.recordFailure("permission_denied")

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.errorsByType["timeout"]).isEqualTo(2)
        assertThat(snapshot.errorsByType["permission_denied"]).isEqualTo(1)
    }

    // === Timing tests ===

    @Test
    fun `getSnapshot calculates average duration`() {
        NIP55Metrics.recordSuccess(100)
        NIP55Metrics.recordSuccess(200)
        NIP55Metrics.recordSuccess(300)
        NIP55Metrics.recordSuccess(400)

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.avgDurationMs).isEqualTo(250) // (100+200+300+400)/4
    }

    @Test
    fun `getSnapshot calculates p95 duration`() {
        // Add 100 samples: 1, 2, 3, ... 100
        for (i in 1..100) {
            NIP55Metrics.recordSuccess(i.toLong())
        }

        val snapshot = NIP55Metrics.getSnapshot()
        // p95 should be around 95 (95th percentile of 1-100)
        assertThat(snapshot.p95DurationMs).isAtLeast(90)
        assertThat(snapshot.p95DurationMs).isAtMost(100)
    }

    @Test
    fun `slow requests are tracked`() {
        NIP55Metrics.recordSuccess(500)  // Fast
        NIP55Metrics.recordSuccess(1500) // Slow (>1000ms)
        NIP55Metrics.recordSuccess(2000) // Slow

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.slowRequestCount).isEqualTo(2)
    }

    // === Snapshot tests ===

    @Test
    fun `getSnapshot returns immutable copy`() {
        NIP55Metrics.recordRequest("sign_event", "intent", "app")
        val snapshot1 = NIP55Metrics.getSnapshot()

        NIP55Metrics.recordRequest("sign_event", "intent", "app")
        val snapshot2 = NIP55Metrics.getSnapshot()

        // First snapshot should not change
        assertThat(snapshot1.totalRequests).isEqualTo(1)
        assertThat(snapshot2.totalRequests).isEqualTo(2)
    }

    @Test
    fun `reset clears all counters`() {
        NIP55Metrics.recordRequest("sign_event", "intent", "app")
        NIP55Metrics.recordSuccess(100)
        NIP55Metrics.recordFailure("error")
        NIP55Metrics.recordCacheHit()
        NIP55Metrics.recordDuplicateBlocked()

        NIP55Metrics.reset()

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.totalRequests).isEqualTo(0)
        assertThat(snapshot.successCount).isEqualTo(0)
        assertThat(snapshot.failureCount).isEqualTo(0)
        assertThat(snapshot.cacheHits).isEqualTo(0)
        assertThat(snapshot.duplicatesBlocked).isEqualTo(0)
    }

    // === Thread safety tests ===

    @Test
    fun `concurrent recording is thread-safe`() {
        val threadCount = 10
        val iterationsPerThread = 100
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    for (j in 0 until iterationsPerThread) {
                        NIP55Metrics.recordRequest("sign_event", "intent", "app$i")
                        NIP55Metrics.recordSuccess(10)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        val snapshot = NIP55Metrics.getSnapshot()
        // All recordings should be captured
        assertThat(snapshot.totalRequests).isEqualTo((threadCount * iterationsPerThread).toLong())
        assertThat(snapshot.successCount).isEqualTo((threadCount * iterationsPerThread).toLong())
    }

    // === Additional metric tests ===

    @Test
    fun `recordCacheHit increments cache hit count`() {
        NIP55Metrics.recordCacheHit()
        NIP55Metrics.recordCacheHit()

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.cacheHits).isEqualTo(2)
    }

    @Test
    fun `recordDuplicateBlocked increments blocked count`() {
        NIP55Metrics.recordDuplicateBlocked()

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.duplicatesBlocked).isEqualTo(1)
    }

    @Test
    fun `recordPermissionPrompt increments prompt count`() {
        NIP55Metrics.recordPermissionPrompt()
        NIP55Metrics.recordPermissionPrompt()

        val snapshot = NIP55Metrics.getSnapshot()
        assertThat(snapshot.permissionPromptsShown).isEqualTo(2)
    }

    @Test
    fun `recordPermissionDecision tracks approval rate`() {
        NIP55Metrics.recordPermissionDecision(approved = true)
        NIP55Metrics.recordPermissionDecision(approved = true)
        NIP55Metrics.recordPermissionDecision(approved = false)

        val snapshot = NIP55Metrics.getSnapshot()
        // 2 approved out of 3 = 66.67%
        assertThat(snapshot.permissionApprovalRate).isWithin(0.1).of(66.67)
    }

    @Test
    fun `success rate is calculated correctly`() {
        NIP55Metrics.recordSuccess(100)
        NIP55Metrics.recordSuccess(100)
        NIP55Metrics.recordSuccess(100)
        NIP55Metrics.recordFailure("error")

        val snapshot = NIP55Metrics.getSnapshot()
        // 3 success out of 4 = 75%
        assertThat(snapshot.successRate).isWithin(0.1).of(75.0)
    }

    @Test
    fun `formatSessionDuration formats correctly`() {
        val snapshot = NIP55Metrics.getSnapshot()
        val duration = snapshot.formatSessionDuration()
        // Should be a valid duration string (e.g., "0s", "1m 30s", etc.)
        assertThat(duration).isNotEmpty()
    }
}
