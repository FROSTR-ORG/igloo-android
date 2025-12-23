package com.frostr.igloo.bridges

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for WebViewExecutor implementations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WebViewExecutorTest {

    private lateinit var mockExecutor: MockWebViewExecutor

    @Before
    fun setUp() {
        mockExecutor = MockWebViewExecutor()
    }

    @Test
    fun `MockWebViewExecutor records executed scripts`() {
        mockExecutor.evaluateJavascript("console.log('test')", null)
        mockExecutor.evaluateJavascript("alert('hello')", null)

        assertThat(mockExecutor.executedScripts).hasSize(2)
        assertThat(mockExecutor.executedScripts[0]).isEqualTo("console.log('test')")
        assertThat(mockExecutor.executedScripts[1]).isEqualTo("alert('hello')")
    }

    @Test
    fun `MockWebViewExecutor returns configured result`() {
        mockExecutor.nextResult = "success"
        var receivedResult: String? = null

        mockExecutor.evaluateJavascript("test") { result ->
            receivedResult = result
        }

        assertThat(receivedResult).isEqualTo("success")
    }

    @Test
    fun `MockWebViewExecutor returns null by default`() {
        var receivedResult: String? = "not null"

        mockExecutor.evaluateJavascript("test") { result ->
            receivedResult = result
        }

        assertThat(receivedResult).isNull()
    }

    @Test
    fun `MockWebViewExecutor executes posted runnables immediately by default`() {
        var executed = false

        mockExecutor.post { executed = true }

        assertThat(executed).isTrue()
        assertThat(mockExecutor.postedRunnables).hasSize(1)
    }

    @Test
    fun `MockWebViewExecutor can defer runnable execution`() {
        mockExecutor.executeImmediately = false
        var executed = false

        mockExecutor.post { executed = true }

        assertThat(executed).isFalse()
        assertThat(mockExecutor.postedRunnables).hasSize(1)

        mockExecutor.executePendingRunnables()

        assertThat(executed).isTrue()
    }

    @Test
    fun `MockWebViewExecutor reset clears state`() {
        mockExecutor.evaluateJavascript("script1", null)
        mockExecutor.post { }
        mockExecutor.nextResult = "something"

        mockExecutor.reset()

        assertThat(mockExecutor.executedScripts).isEmpty()
        assertThat(mockExecutor.postedRunnables).isEmpty()
        assertThat(mockExecutor.nextResult).isNull()
    }

    @Test
    fun `MockWebViewExecutor hasExecutedScriptContaining works`() {
        mockExecutor.evaluateJavascript("window.Bridge.callback('abc')", null)

        assertThat(mockExecutor.hasExecutedScriptContaining("Bridge")).isTrue()
        assertThat(mockExecutor.hasExecutedScriptContaining("callback")).isTrue()
        assertThat(mockExecutor.hasExecutedScriptContaining("xyz")).isFalse()
    }

    @Test
    fun `MockWebViewExecutor lastScript returns last executed script`() {
        assertThat(mockExecutor.lastScript()).isNull()

        mockExecutor.evaluateJavascript("first", null)
        assertThat(mockExecutor.lastScript()).isEqualTo("first")

        mockExecutor.evaluateJavascript("second", null)
        assertThat(mockExecutor.lastScript()).isEqualTo("second")
    }

    @Test
    fun `MockWebViewExecutor isReady is configurable`() {
        assertThat(mockExecutor.isReady()).isTrue()

        mockExecutor.ready = false
        assertThat(mockExecutor.isReady()).isFalse()
    }

    @Test
    fun `WebViewExecutorFactory creates mock executor`() {
        val executor = WebViewExecutorFactory.createMock()

        assertThat(executor).isInstanceOf(MockWebViewExecutor::class.java)
    }

    @Test
    fun `MockWebViewExecutor handles null callback`() {
        // Should not throw
        mockExecutor.evaluateJavascript("test", null)

        assertThat(mockExecutor.executedScripts).hasSize(1)
    }

    @Test
    fun `MockWebViewExecutor multiple runnables execute in order`() {
        mockExecutor.executeImmediately = false
        val order = mutableListOf<Int>()

        mockExecutor.post { order.add(1) }
        mockExecutor.post { order.add(2) }
        mockExecutor.post { order.add(3) }

        assertThat(order).isEmpty()

        mockExecutor.executePendingRunnables()

        assertThat(order).isEqualTo(listOf(1, 2, 3))
    }
}
