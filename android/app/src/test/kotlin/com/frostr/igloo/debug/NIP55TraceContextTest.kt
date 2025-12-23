package com.frostr.igloo.debug

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for NIP55TraceContext.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NIP55TraceContextTest {

    @Test
    fun `extractTraceId returns first 8 characters for long IDs`() {
        val traceId = NIP55TraceContext.extractTraceId("abcd1234efgh5678")
        assertThat(traceId).isEqualTo("abcd1234")
    }

    @Test
    fun `extractTraceId pads short IDs with zeros`() {
        val traceId = NIP55TraceContext.extractTraceId("abc")
        assertThat(traceId).isEqualTo("abc00000")
    }

    @Test
    fun `extractTraceId handles empty string`() {
        val traceId = NIP55TraceContext.extractTraceId("")
        assertThat(traceId).isEqualTo("00000000")
    }

    @Test
    fun `extractTraceId handles exactly 8 characters`() {
        val traceId = NIP55TraceContext.extractTraceId("12345678")
        assertThat(traceId).isEqualTo("12345678")
    }

    @Test
    fun `create returns trace with correct properties`() {
        val trace = NIP55TraceContext.create(
            requestId = "req12345678",
            operationType = "sign_event",
            callingApp = "com.test.app",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        assertThat(trace.traceId).isEqualTo("req12345")
        assertThat(trace.requestId).isEqualTo("req12345678")
        assertThat(trace.operationType).isEqualTo("sign_event")
        assertThat(trace.callingApp).isEqualTo("com.test.app")
        assertThat(trace.entryPoint).isEqualTo(NIP55TraceContext.EntryPoint.INTENT)
    }

    @Test
    fun `create adds RECEIVED checkpoint automatically`() {
        val trace = NIP55TraceContext.create(
            requestId = "test_req",
            operationType = "get_public_key",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.CONTENT_PROVIDER
        )

        assertThat(trace.hasCheckpoint("RECEIVED")).isTrue()
        assertThat(trace.getCheckpoints()).isNotEmpty()
    }

    @Test
    fun `checkpoint records with data`() {
        val trace = NIP55TraceContext.create(
            requestId = "test_req",
            operationType = "sign_event",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        trace.checkpoint("PARSED", "eventId" to "abc123", "kind" to 1)

        assertThat(trace.hasCheckpoint("PARSED")).isTrue()
        assertThat(trace.getCheckpoints().size).isEqualTo(2) // RECEIVED + PARSED
    }

    @Test
    fun `elapsed returns time since creation`() {
        val trace = NIP55TraceContext.create(
            requestId = "test",
            operationType = "test",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        // Elapsed should be >= 0
        assertThat(trace.elapsed()).isAtLeast(0)
    }

    @Test
    fun `lastCheckpoint returns last added checkpoint`() {
        val trace = NIP55TraceContext.create(
            requestId = "test",
            operationType = "test",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        assertThat(trace.lastCheckpoint()).isEqualTo("RECEIVED")

        trace.checkpoint("PARSED")
        assertThat(trace.lastCheckpoint()).isEqualTo("PARSED")

        trace.checkpoint("COMPLETED")
        assertThat(trace.lastCheckpoint()).isEqualTo("COMPLETED")
    }

    @Test
    fun `durationBetween returns time between checkpoints`() {
        val trace = NIP55TraceContext.create(
            requestId = "test",
            operationType = "test",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        trace.checkpoint("PARSED")
        Thread.sleep(10) // Small delay to ensure measurable difference
        trace.checkpoint("COMPLETED")

        val duration = trace.durationBetween("PARSED", "COMPLETED")
        assertThat(duration).isNotNull()
        assertThat(duration).isAtLeast(0)
    }

    @Test
    fun `durationBetween returns null for missing checkpoints`() {
        val trace = NIP55TraceContext.create(
            requestId = "test",
            operationType = "test",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        val duration = trace.durationBetween("NONEXISTENT", "ALSO_NONEXISTENT")
        assertThat(duration).isNull()
    }

    @Test
    fun `hasCheckpoint returns correct values`() {
        val trace = NIP55TraceContext.create(
            requestId = "test",
            operationType = "test",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        assertThat(trace.hasCheckpoint("RECEIVED")).isTrue()
        assertThat(trace.hasCheckpoint("NONEXISTENT")).isFalse()
    }

    @Test
    fun `getCheckpoints returns immutable copy`() {
        val trace = NIP55TraceContext.create(
            requestId = "test",
            operationType = "test",
            callingApp = "com.test",
            entryPoint = NIP55TraceContext.EntryPoint.INTENT
        )

        val checkpoints1 = trace.getCheckpoints()
        trace.checkpoint("ADDITIONAL")
        val checkpoints2 = trace.getCheckpoints()

        // Lists should be different references
        assertThat(checkpoints1.size).isLessThan(checkpoints2.size)
    }

    @Test
    fun `EntryPoint enum has both values`() {
        assertThat(NIP55TraceContext.EntryPoint.INTENT.name).isEqualTo("INTENT")
        assertThat(NIP55TraceContext.EntryPoint.CONTENT_PROVIDER.name).isEqualTo("CONTENT_PROVIDER")
    }

    @Test
    fun `NIP55Checkpoints constants are defined`() {
        assertThat(NIP55Checkpoints.RECEIVED).isEqualTo("RECEIVED")
        assertThat(NIP55Checkpoints.PARSED).isEqualTo("PARSED")
        assertThat(NIP55Checkpoints.DEDUPE_CHECK).isEqualTo("DEDUPE_CHECK")
        assertThat(NIP55Checkpoints.PERMISSION_CHECK).isEqualTo("PERMISSION_CHECK")
        assertThat(NIP55Checkpoints.QUEUED).isEqualTo("QUEUED")
        assertThat(NIP55Checkpoints.BRIDGE_SENT).isEqualTo("BRIDGE_SENT")
        assertThat(NIP55Checkpoints.BRIDGE_RESPONSE).isEqualTo("BRIDGE_RESPONSE")
        assertThat(NIP55Checkpoints.COMPLETED).isEqualTo("COMPLETED")
    }

    @Test
    fun `NIP55Errors constants are defined`() {
        assertThat(NIP55Errors.PARSE).isEqualTo("PARSE")
        assertThat(NIP55Errors.PERMISSION).isEqualTo("PERMISSION")
        assertThat(NIP55Errors.DEDUPE).isEqualTo("DEDUPE")
        assertThat(NIP55Errors.TIMEOUT).isEqualTo("TIMEOUT")
        assertThat(NIP55Errors.BRIDGE).isEqualTo("BRIDGE")
        assertThat(NIP55Errors.BIFROST).isEqualTo("BIFROST")
        assertThat(NIP55Errors.UNKNOWN).isEqualTo("UNKNOWN")
    }
}
