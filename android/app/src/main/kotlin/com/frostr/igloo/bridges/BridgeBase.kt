package com.frostr.igloo.bridges

import android.util.Log
import android.webkit.WebView
import com.frostr.igloo.util.JavaScriptEscaper
import com.google.gson.Gson

/**
 * Base class for all JavaScript bridges.
 *
 * Provides common functionality:
 * - Gson serialization
 * - JavaScript execution with proper escaping
 * - Callback notification
 * - Logging with consistent TAG
 * - Cleanup lifecycle
 *
 * All bridges should extend this class to benefit from:
 * - Consistent JavaScript escaping (prevents XSS)
 * - Thread-safe WebView execution
 * - Standardized logging format
 */
abstract class BridgeBase(
    protected val webView: WebView
) {
    protected val gson = Gson()
    protected val TAG: String = this.javaClass.simpleName

    /**
     * Execute JavaScript on the WebView (main thread safe).
     *
     * @param script The JavaScript code to execute
     * @param callback Optional callback to receive the result
     */
    protected fun executeScript(script: String, callback: ((String?) -> Unit)? = null) {
        webView.post {
            webView.evaluateJavascript(script) { result ->
                callback?.invoke(result)
                if (result != null && result != "null" && result.isNotEmpty()) {
                    Log.d(TAG, "Script result: ${result.take(100)}")
                }
            }
        }
    }

    /**
     * Notify JavaScript callback with string arguments.
     *
     * All arguments are properly escaped for safe injection.
     *
     * @param bridgeName The JavaScript bridge object name (e.g., "SigningBridge")
     * @param methodName The method to call (e.g., "handleCallback")
     * @param args Arguments to pass (will be escaped and quoted)
     */
    protected fun notifyCallback(
        bridgeName: String,
        methodName: String,
        vararg args: String
    ) {
        val escapedArgs = args.joinToString(", ") { JavaScriptEscaper.quote(it) }
        val script = "window.$bridgeName && window.$bridgeName.$methodName($escapedArgs)"
        executeScript(script)
    }

    /**
     * Notify JavaScript callback with a callback ID and JSON data.
     *
     * @param bridgeName The JavaScript bridge object name
     * @param methodName The method to call
     * @param callbackId The callback identifier (will be escaped)
     * @param jsonData JSON data to pass (not escaped - already JSON)
     */
    protected fun notifyCallbackJson(
        bridgeName: String,
        methodName: String,
        callbackId: String,
        jsonData: String
    ) {
        val script = buildString {
            append("window.$bridgeName && window.$bridgeName.$methodName(")
            append(JavaScriptEscaper.quote(callbackId))
            append(", ")
            append(jsonData) // Already JSON, don't double-escape
            append(")")
        }
        executeScript(script)
    }

    /**
     * Notify JavaScript with an event object.
     *
     * @param handlerName The global event handler name (e.g., "__handleWebSocketEvent")
     * @param eventJson JSON event data (will be escaped as string)
     */
    protected fun notifyEvent(handlerName: String, eventJson: String) {
        val escapedData = JavaScriptEscaper.escape(eventJson)
        val script = "window.$handlerName && window.$handlerName('$escapedData')"
        executeScript(script)
    }

    /**
     * Execute a callback stored in a JavaScript callbacks object.
     *
     * Pattern: window.Callbacks[callbackId].resolve(data)
     *
     * @param callbacksObject The name of the callbacks object
     * @param callbackId The callback ID
     * @param method Either "resolve" or "reject"
     * @param data The data to pass (will be escaped)
     * @param cleanup Whether to delete the callback after invocation
     */
    protected fun executeCallback(
        callbacksObject: String,
        callbackId: String,
        method: String,
        data: String,
        cleanup: Boolean = true
    ) {
        val escapedCallbackId = JavaScriptEscaper.escape(callbackId)
        val escapedData = JavaScriptEscaper.escape(data)

        val script = buildString {
            append("if (window.$callbacksObject && window.$callbacksObject['$escapedCallbackId']) {")
            append("window.$callbacksObject['$escapedCallbackId'].$method('$escapedData');")
            if (cleanup) {
                append("delete window.$callbacksObject['$escapedCallbackId'];")
            }
            append("}")
        }
        executeScript(script)
    }

    /**
     * Cleanup resources. Called when bridge is no longer needed.
     *
     * Subclasses should override this to release their specific resources.
     */
    abstract fun cleanup()

    /**
     * Log debug message with consistent formatting.
     */
    protected fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    /**
     * Log info message with consistent formatting.
     */
    protected fun logInfo(message: String) {
        Log.i(TAG, message)
    }

    /**
     * Log warning message with consistent formatting.
     */
    protected fun logWarn(message: String) {
        Log.w(TAG, message)
    }

    /**
     * Log error with optional exception.
     */
    protected fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }

    /**
     * Log a trace message for debugging request flows.
     *
     * @param method The method name
     * @param action START, END, or a specific action
     * @param details Additional details to log
     */
    protected fun logTrace(method: String, action: String, details: String = "") {
        val detailsSuffix = if (details.isNotEmpty()) ": $details" else ""
        Log.d(TAG, "[TRACE] $method $action$detailsSuffix")
    }

    /**
     * Serialize an object to JSON string.
     */
    protected fun toJson(obj: Any): String = gson.toJson(obj)

    /**
     * Deserialize a JSON string to an object.
     */
    protected inline fun <reified T> fromJson(json: String): T = gson.fromJson(json, T::class.java)
}
