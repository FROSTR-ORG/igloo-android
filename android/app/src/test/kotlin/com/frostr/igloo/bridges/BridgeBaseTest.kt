package com.frostr.igloo.bridges

import android.webkit.WebView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import com.google.common.truth.Truth.assertThat

/**
 * Unit tests for BridgeBase abstract class.
 *
 * Uses a concrete test implementation to verify base class behavior.
 */
@RunWith(MockitoJUnitRunner::class)
class BridgeBaseTest {

    @Mock
    private lateinit var mockWebView: WebView

    private lateinit var testBridge: TestBridge

    /**
     * Concrete implementation for testing
     */
    class TestBridge(webView: WebView) : BridgeBase(webView) {
        var cleanupCalled = false

        override fun cleanup() {
            cleanupCalled = true
        }

        // Expose protected methods for testing
        fun testExecuteScript(script: String, callback: ((String?) -> Unit)? = null) {
            executeScript(script, callback)
        }

        fun testNotifyCallback(bridgeName: String, methodName: String, vararg args: String) {
            notifyCallback(bridgeName, methodName, *args)
        }

        fun testNotifyCallbackJson(bridgeName: String, methodName: String, callbackId: String, jsonData: String) {
            notifyCallbackJson(bridgeName, methodName, callbackId, jsonData)
        }

        fun testNotifyEvent(handlerName: String, eventJson: String) {
            notifyEvent(handlerName, eventJson)
        }

        fun testExecuteCallback(callbacksObject: String, callbackId: String, method: String, data: String, cleanup: Boolean = true) {
            executeCallback(callbacksObject, callbackId, method, data, cleanup)
        }

        fun testToJson(obj: Any): String = toJson(obj)

        fun getTag(): String = TAG
    }

    @Before
    fun setUp() {
        // Configure mock to execute posted runnables immediately
        doAnswer { invocation ->
            val runnable = invocation.getArgument<Runnable>(0)
            runnable.run()
            true
        }.`when`(mockWebView).post(any())

        testBridge = TestBridge(mockWebView)
    }

    @Test
    fun `TAG is set to class simple name`() {
        assertThat(testBridge.getTag()).isEqualTo("TestBridge")
    }

    @Test
    fun `executeScript posts to WebView and evaluates JavaScript`() {
        val script = "console.log('test')"

        testBridge.testExecuteScript(script)

        verify(mockWebView).post(any())
        verify(mockWebView).evaluateJavascript(eq(script), any())
    }

    @Test
    fun `notifyCallback builds correct JavaScript with escaped args`() {
        val scriptCaptor = argumentCaptor<String>()

        testBridge.testNotifyCallback("TestBridge", "handleResult", "hello", "world")

        verify(mockWebView).evaluateJavascript(scriptCaptor.capture(), any())
        val script = scriptCaptor.firstValue

        assertThat(script).contains("window.TestBridge")
        assertThat(script).contains("handleResult")
        assertThat(script).contains("'hello'")
        assertThat(script).contains("'world'")
    }

    @Test
    fun `notifyCallback escapes special characters in args`() {
        val scriptCaptor = argumentCaptor<String>()

        testBridge.testNotifyCallback("Bridge", "method", "hello'world")

        verify(mockWebView).evaluateJavascript(scriptCaptor.capture(), any())
        val script = scriptCaptor.firstValue

        // Single quote should be escaped
        assertThat(script).contains("\\'")
        assertThat(script).doesNotContain("hello'world")
    }

    @Test
    fun `notifyCallbackJson includes callback ID and JSON data`() {
        val scriptCaptor = argumentCaptor<String>()
        val jsonData = """{"result":"success"}"""

        testBridge.testNotifyCallbackJson("Bridge", "callback", "cb123", jsonData)

        verify(mockWebView).evaluateJavascript(scriptCaptor.capture(), any())
        val script = scriptCaptor.firstValue

        assertThat(script).contains("window.Bridge")
        assertThat(script).contains("callback")
        assertThat(script).contains("'cb123'")
        assertThat(script).contains(jsonData)
    }

    @Test
    fun `notifyEvent escapes event JSON`() {
        val scriptCaptor = argumentCaptor<String>()
        val eventJson = """{"type":"message","data":"hello"}"""

        testBridge.testNotifyEvent("__handleEvent", eventJson)

        verify(mockWebView).evaluateJavascript(scriptCaptor.capture(), any())
        val script = scriptCaptor.firstValue

        assertThat(script).contains("window.__handleEvent")
    }

    @Test
    fun `executeCallback builds resolve script correctly`() {
        val scriptCaptor = argumentCaptor<String>()

        testBridge.testExecuteCallback("Callbacks", "cb1", "resolve", "success")

        verify(mockWebView).evaluateJavascript(scriptCaptor.capture(), any())
        val script = scriptCaptor.firstValue

        assertThat(script).contains("window.Callbacks")
        assertThat(script).contains("['cb1']")
        assertThat(script).contains(".resolve(")
        assertThat(script).contains("'success'")
        assertThat(script).contains("delete window.Callbacks['cb1']")
    }

    @Test
    fun `executeCallback builds reject script correctly`() {
        val scriptCaptor = argumentCaptor<String>()

        testBridge.testExecuteCallback("Callbacks", "cb1", "reject", "error")

        verify(mockWebView).evaluateJavascript(scriptCaptor.capture(), any())
        val script = scriptCaptor.firstValue

        assertThat(script).contains(".reject(")
        assertThat(script).contains("'error'")
    }

    @Test
    fun `executeCallback without cleanup does not delete callback`() {
        val scriptCaptor = argumentCaptor<String>()

        testBridge.testExecuteCallback("Callbacks", "cb1", "resolve", "data", cleanup = false)

        verify(mockWebView).evaluateJavascript(scriptCaptor.capture(), any())
        val script = scriptCaptor.firstValue

        assertThat(script).doesNotContain("delete")
    }

    @Test
    fun `executeCallback escapes callback ID with special characters`() {
        val scriptCaptor = argumentCaptor<String>()

        testBridge.testExecuteCallback("Callbacks", "cb'1", "resolve", "data")

        verify(mockWebView).evaluateJavascript(scriptCaptor.capture(), any())
        val script = scriptCaptor.firstValue

        // Should escape the single quote
        assertThat(script).contains("\\'")
    }

    @Test
    fun `cleanup can be called`() {
        testBridge.cleanup()

        assertThat(testBridge.cleanupCalled).isTrue()
    }

    @Test
    fun `toJson serializes objects correctly`() {
        data class TestData(val name: String, val value: Int)

        val json = testBridge.testToJson(TestData("test", 42))

        assertThat(json).contains("\"name\":\"test\"")
        assertThat(json).contains("\"value\":42")
    }

    @Test
    fun `toJson handles maps correctly`() {
        val map = mapOf("key" to "value", "number" to 123)

        val json = testBridge.testToJson(map)

        assertThat(json).contains("\"key\":\"value\"")
        assertThat(json).contains("\"number\":123")
    }

    @Test
    fun `notifyCallback with no args builds correct script`() {
        val scriptCaptor = argumentCaptor<String>()

        testBridge.testNotifyCallback("Bridge", "noArgsMethod")

        verify(mockWebView).evaluateJavascript(scriptCaptor.capture(), any())
        val script = scriptCaptor.firstValue

        assertThat(script).isEqualTo("window.Bridge && window.Bridge.noArgsMethod()")
    }

    @Test
    fun `notifyCallback escapes newlines in args`() {
        val scriptCaptor = argumentCaptor<String>()

        testBridge.testNotifyCallback("Bridge", "method", "line1\nline2")

        verify(mockWebView).evaluateJavascript(scriptCaptor.capture(), any())
        val script = scriptCaptor.firstValue

        assertThat(script).contains("\\n")
        assertThat(script).doesNotContain("\n")
    }
}
