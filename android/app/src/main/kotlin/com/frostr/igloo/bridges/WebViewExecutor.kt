package com.frostr.igloo.bridges

import android.webkit.WebView

/**
 * Interface for executing JavaScript on a WebView.
 *
 * This abstraction enables:
 * - Unit testing bridges without a real WebView
 * - Mocking JavaScript execution for verification
 * - Decoupling bridges from Android framework
 *
 * Usage in production:
 * ```kotlin
 * val executor = RealWebViewExecutor(webView)
 * executor.evaluateJavascript("console.log('test')") { result ->
 *     println("Got: $result")
 * }
 * ```
 *
 * Usage in tests:
 * ```kotlin
 * val mockExecutor = MockWebViewExecutor()
 * mockExecutor.nextResult = "success"
 * bridge.doSomething()
 * assertThat(mockExecutor.executedScripts).contains("expected script")
 * ```
 */
interface WebViewExecutor {
    /**
     * Execute JavaScript and return result via callback.
     *
     * @param script The JavaScript code to execute
     * @param callback Optional callback to receive the result
     */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?)

    /**
     * Post a runnable to be executed on the WebView's thread.
     *
     * @param runnable The runnable to execute
     */
    fun post(runnable: Runnable)

    /**
     * Check if the executor is ready to accept commands.
     */
    fun isReady(): Boolean
}

/**
 * Production implementation wrapping a real WebView.
 */
class RealWebViewExecutor(private val webView: WebView) : WebViewExecutor {

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        webView.evaluateJavascript(script) { result -> callback?.invoke(result) }
    }

    override fun post(runnable: Runnable) {
        webView.post(runnable)
    }

    override fun isReady(): Boolean {
        return true // Real WebView is always ready once constructed
    }
}

/**
 * Test implementation that records calls for verification.
 *
 * This mock allows tests to:
 * - Verify which scripts were executed
 * - Verify which runnables were posted
 * - Control the return value from evaluateJavascript
 * - Optionally execute posted runnables immediately
 */
class MockWebViewExecutor : WebViewExecutor {
    /** All scripts that were executed */
    val executedScripts = mutableListOf<String>()

    /** All runnables that were posted */
    val postedRunnables = mutableListOf<Runnable>()

    /** The result to return from the next evaluateJavascript call */
    var nextResult: String? = null

    /** Whether to immediately execute posted runnables */
    var executeImmediately: Boolean = true

    /** Whether the executor is ready */
    var ready: Boolean = true

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        executedScripts.add(script)
        callback?.invoke(nextResult)
    }

    override fun post(runnable: Runnable) {
        postedRunnables.add(runnable)
        if (executeImmediately) {
            runnable.run()
        }
    }

    override fun isReady(): Boolean = ready

    /**
     * Clear all recorded data.
     */
    fun reset() {
        executedScripts.clear()
        postedRunnables.clear()
        nextResult = null
    }

    /**
     * Execute all pending runnables (when executeImmediately is false).
     */
    fun executePendingRunnables() {
        postedRunnables.toList().forEach { it.run() }
    }

    /**
     * Check if a script containing the given substring was executed.
     */
    fun hasExecutedScriptContaining(substring: String): Boolean {
        return executedScripts.any { it.contains(substring) }
    }

    /**
     * Get the last executed script.
     */
    fun lastScript(): String? = executedScripts.lastOrNull()
}

/**
 * Factory for creating WebViewExecutor instances.
 *
 * This allows dependency injection of the executor type.
 */
object WebViewExecutorFactory {
    /**
     * Create a production executor wrapping a WebView.
     */
    fun create(webView: WebView): WebViewExecutor = RealWebViewExecutor(webView)

    /**
     * Create a mock executor for testing.
     */
    fun createMock(): MockWebViewExecutor = MockWebViewExecutor()
}
