package com.frostr.igloo.debug

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for NIP55Timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NIP55TimingTest {

    @Test
    fun `WARN_THRESHOLD_MS is 1000ms`() {
        assertThat(NIP55Timing.WARN_THRESHOLD_MS).isEqualTo(1000L)
    }

    @Test
    fun `ERROR_THRESHOLD_MS is 5000ms`() {
        assertThat(NIP55Timing.ERROR_THRESHOLD_MS).isEqualTo(5000L)
    }

    @Test
    fun `isSlow returns false for fast operations`() {
        assertThat(NIP55Timing.isSlow(500)).isFalse()
        assertThat(NIP55Timing.isSlow(999)).isFalse()
    }

    @Test
    fun `isSlow returns true for slow operations`() {
        assertThat(NIP55Timing.isSlow(1001)).isTrue()
        assertThat(NIP55Timing.isSlow(2000)).isTrue()
    }

    @Test
    fun `isVerySlow returns false for normal operations`() {
        assertThat(NIP55Timing.isVerySlow(1000)).isFalse()
        assertThat(NIP55Timing.isVerySlow(4999)).isFalse()
    }

    @Test
    fun `isVerySlow returns true for very slow operations`() {
        assertThat(NIP55Timing.isVerySlow(5001)).isTrue()
        assertThat(NIP55Timing.isVerySlow(10000)).isTrue()
    }

    @Test
    fun `stopwatch elapsed returns positive value`() {
        val stopwatch = NIP55Timing.stopwatch()
        Thread.sleep(10)
        val elapsed = stopwatch.elapsed()
        assertThat(elapsed).isAtLeast(10)
    }

    @Test
    fun `stopwatch lap returns time since last lap`() {
        val stopwatch = NIP55Timing.stopwatch()
        Thread.sleep(10)
        val lap1 = stopwatch.lap()
        assertThat(lap1).isAtLeast(10)

        Thread.sleep(5)
        val lap2 = stopwatch.lap()
        // lap2 should be less than lap1 since it measures time since last lap
        assertThat(lap2).isAtLeast(5)
    }

    @Test
    fun `timed block executes and returns result`() {
        val result = NIP55Timing.timed("TestTag", "test_trace", "test_op") {
            "test_result"
        }
        assertThat(result).isEqualTo("test_result")
    }

    @Test
    fun `timed block without traceId executes and returns result`() {
        val result = NIP55Timing.timed("TestTag", "test_op") {
            42
        }
        assertThat(result).isEqualTo(42)
    }

    @Test
    fun `timed block propagates exceptions`() {
        try {
            NIP55Timing.timed("TestTag", "test_op") {
                throw IllegalStateException("Test exception")
            }
            assertThat(false).isTrue() // Should not reach here
        } catch (e: IllegalStateException) {
            assertThat(e.message).isEqualTo("Test exception")
        }
    }

    @Test
    fun `timedCheckpoint records both checkpoints`() {
        val trace = NIP55TraceContext.create(
            requestId = "test",
            operationType = "test",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        NIP55Timing.timedCheckpoint(trace, "START", "END") {
            Thread.sleep(5)
            "result"
        }

        assertThat(trace.hasCheckpoint("START")).isTrue()
        assertThat(trace.hasCheckpoint("END")).isTrue()
    }

    @Test
    fun `timedWithResult records success checkpoint`() {
        val trace = NIP55TraceContext.create(
            requestId = "test",
            operationType = "test",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        val result = NIP55Timing.timedWithResult(trace, "OP_START", "OP_SUCCESS", "OP") {
            "success_value"
        }

        assertThat(result).isEqualTo("success_value")
        assertThat(trace.hasCheckpoint("OP_START")).isTrue()
        assertThat(trace.hasCheckpoint("OP_SUCCESS")).isTrue()
    }

    @Test
    fun `timedWithResult records error on exception`() {
        val trace = NIP55TraceContext.create(
            requestId = "test",
            operationType = "test",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        try {
            NIP55Timing.timedWithResult(trace, "OP_START", "OP_SUCCESS", "OP") {
                throw RuntimeException("Operation failed")
            }
        } catch (e: RuntimeException) {
            // Expected
        }

        assertThat(trace.hasCheckpoint("OP_START")).isTrue()
        // OP_SUCCESS should not be set since we threw an exception
        assertThat(trace.hasCheckpoint("OP_SUCCESS")).isFalse()
    }

    @Test
    fun `trace timed extension works correctly`() {
        val trace = NIP55TraceContext.create(
            requestId = "test",
            operationType = "test",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        val result = trace.timed("MY_OP") {
            Thread.sleep(5)
            "extension_result"
        }

        assertThat(result).isEqualTo("extension_result")
        assertThat(trace.hasCheckpoint("MY_OP")).isTrue()
    }
}
