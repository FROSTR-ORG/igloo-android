package com.frostr.igloo.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for RequestTracer utility.
 *
 * Uses Robolectric to support Android Log calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RequestTracerTest {

    @Test
    fun `generates unique trace IDs`() {
        val id1 = RequestTracer.generateTraceId()
        val id2 = RequestTracer.generateTraceId()
        val id3 = RequestTracer.generateTraceId()

        assertThat(id1).isNotEqualTo(id2)
        assertThat(id2).isNotEqualTo(id3)
        assertThat(id1).isNotEqualTo(id3)
    }

    @Test
    fun `trace ID format is valid`() {
        val traceId = RequestTracer.generateTraceId()

        // Should match pattern: trace-{timestamp}-{random}
        assertThat(traceId).startsWith("trace-")
        assertThat(traceId).containsMatch("trace-\\d+-[a-z]+")
    }

    @Test
    fun `span ID format is valid`() {
        val spanId = RequestTracer.generateSpanId()

        // Should match pattern: span-{timestamp}-{random}
        assertThat(spanId).startsWith("span-")
        assertThat(spanId).containsMatch("span-\\d+-[a-z]+")
    }

    @Test
    fun `span IDs are unique`() {
        val id1 = RequestTracer.generateSpanId()
        val id2 = RequestTracer.generateSpanId()

        assertThat(id1).isNotEqualTo(id2)
    }

    @Test
    fun `serialization round-trips correctly`() {
        val context = RequestTracer.createTrace(
            component = "TestComponent",
            process = "TestProcess"
        )

        val serialized = RequestTracer.serializeTraceContext(context)
        val deserialized = RequestTracer.deserializeTraceContext(serialized)

        assertThat(deserialized).isNotNull()
        assertThat(deserialized!!.traceId).isEqualTo(context.traceId)
        assertThat(deserialized.spanId).isEqualTo(context.spanId)
        assertThat(deserialized.component).isEqualTo(context.component)
        assertThat(deserialized.process).isEqualTo(context.process)
    }

    @Test
    fun `parent span linking works`() {
        val parentContext = RequestTracer.createTrace(
            component = "ParentComponent",
            process = "ParentProcess"
        )

        val childContext = RequestTracer.continueTrace(
            traceId = parentContext.traceId,
            component = "ChildComponent",
            process = "ChildProcess",
            parentSpanId = parentContext.spanId
        )

        assertThat(childContext.traceId).isEqualTo(parentContext.traceId)
        assertThat(childContext.parentSpanId).isEqualTo(parentContext.spanId)
        assertThat(childContext.spanId).isNotEqualTo(parentContext.spanId)
    }

    @Test
    fun `timing records start and end`() {
        val context = RequestTracer.createTrace(
            component = "TimingTest",
            process = "Test"
        )

        RequestTracer.startSpan(context, "START_OPERATION")

        // Simulate some work
        Thread.sleep(10)

        RequestTracer.endSpan(context, "END_OPERATION", mapOf("result" to "success"))

        val events = RequestTracer.getTrace(context.traceId)
        assertThat(events).isNotEmpty()
        assertThat(events.any { it.event == "START_OPERATION" }).isTrue()
        assertThat(events.any { it.event == "END_OPERATION" }).isTrue()
    }

    @Test
    fun `logTrace records events`() {
        val context = RequestTracer.createTrace(
            component = "LogTest",
            process = "Test"
        )

        RequestTracer.logTrace(context, "TEST_EVENT", mapOf("key" to "value"))

        val events = RequestTracer.getTrace(context.traceId)
        assertThat(events).isNotEmpty()
        assertThat(events.any { it.event == "TEST_EVENT" }).isTrue()
    }

    @Test
    fun `parseTraceId extracts from string`() {
        val source = "Request started with trace-1234567890-abcdefghi in progress"
        val traceId = RequestTracer.parseTraceId(source)

        assertThat(traceId).isEqualTo("trace-1234567890-abcdefghi")
    }

    @Test
    fun `parseTraceId returns null for invalid input`() {
        val source = "No trace ID here"
        val traceId = RequestTracer.parseTraceId(source)

        assertThat(traceId).isNull()
    }

    @Test
    fun `parseTraceId extracts from map`() {
        val source = mapOf("traceId" to "trace-123-abc", "other" to "value")
        val traceId = RequestTracer.parseTraceId(source)

        assertThat(traceId).isEqualTo("trace-123-abc")
    }

    @Test
    fun `getTraceSummary returns summary for existing trace`() {
        val context = RequestTracer.createTrace(
            component = "SummaryTest",
            process = "Test"
        )

        RequestTracer.logTrace(context, "EVENT_1")
        RequestTracer.logTrace(context, "EVENT_2")

        val summary = RequestTracer.getTraceSummary(context.traceId)

        assertThat(summary).isNotNull()
        assertThat(summary!!.traceId).isEqualTo(context.traceId)
        assertThat(summary.events.size).isAtLeast(2)
    }

    @Test
    fun `getTraceSummary returns null for unknown trace`() {
        val summary = RequestTracer.getTraceSummary("unknown-trace-id")

        assertThat(summary).isNull()
    }

    @Test
    fun `continueTrace preserves trace ID across components`() {
        val originalContext = RequestTracer.createTrace(
            component = "Component1",
            process = "Process1"
        )

        val continuedContext1 = RequestTracer.continueTrace(
            traceId = originalContext.traceId,
            component = "Component2",
            process = "Process2"
        )

        val continuedContext2 = RequestTracer.continueTrace(
            traceId = originalContext.traceId,
            component = "Component3",
            process = "Process3"
        )

        // All should share the same trace ID
        assertThat(continuedContext1.traceId).isEqualTo(originalContext.traceId)
        assertThat(continuedContext2.traceId).isEqualTo(originalContext.traceId)

        // But have different span IDs
        assertThat(continuedContext1.spanId).isNotEqualTo(originalContext.spanId)
        assertThat(continuedContext2.spanId).isNotEqualTo(originalContext.spanId)
        assertThat(continuedContext1.spanId).isNotEqualTo(continuedContext2.spanId)
    }

    @Test
    fun `error events are recorded with error field`() {
        val context = RequestTracer.createTrace(
            component = "ErrorTest",
            process = "Test"
        )

        RequestTracer.endSpan(context, "ERROR_EVENT", error = "Something went wrong")

        val events = RequestTracer.getTrace(context.traceId)
        val errorEvent = events.find { it.event == "ERROR_EVENT" }

        assertThat(errorEvent).isNotNull()
        assertThat(errorEvent!!.error).isEqualTo("Something went wrong")
    }

    @Test
    fun `cleanup removes old traces`() {
        // Create many traces to trigger cleanup
        for (i in 1..150) {
            RequestTracer.createTrace("Component$i", "Process")
        }

        RequestTracer.cleanup()

        // Should have cleaned up to around 100 traces
        val allTraces = RequestTracer.exportTraces()
        assertThat(allTraces.size).isAtMost(100)
    }
}
