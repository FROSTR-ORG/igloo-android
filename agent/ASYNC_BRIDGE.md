To set up an asynchronous bridge where your Android app can call an async method in the web app (running in a WebView), await the resolution, and receive the result, use the modern approach with `androidx.webkit` for secure communication. This leverages `WebViewCompat.addWebMessageListener` to handle responses from JavaScript (safer than the older `addJavascriptInterface` due to reduced risk of exposing arbitrary native methods) and `evaluateJavascript` to initiate calls. Combine this with Kotlin coroutines for awaitable behavior on the Android side.

### Prerequisites
- Add the dependency to your `app/build.gradle`:
  ```
  implementation "androidx.webkit:webkit:1.11.0"  // Or the latest version
  ```
- Ensure your WebView is configured with JavaScript enabled:
  ```kotlin
  webView.settings.javaScriptEnabled = true
  ```

### Step 1: Set Up the WebMessageListener in Android
This listener receives messages posted from JavaScript to the native side. Parse the incoming data to handle results or errors, using a map to track continuations for awaiting.

```kotlin
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebMessageListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.UUID
import kotlin.collections.set
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Global map to track request IDs and their continuations (thread-safe if needed)
private val continuations: MutableMap<String, CancellableContinuation<String>> = HashMap()

// Add the listener once during WebView setup
WebViewCompat.addWebMessageListener(
    webView,
    "androidBridge",  // JS object name (window.androidBridge)
    setOf("*")  // Allowed origins; restrict to your domain in production
) { _, message, _, _, _ ->
    val data = message?.data ?: return@addWebMessageListener
    try {
        val json = JSONObject(data)
        val id = json.getString("id")
        val continuation = continuations.remove(id) ?: return@addWebMessageListener
        when (json.getString("type")) {
            "result" -> continuation.resume(json.getString("value"))
            "error" -> continuation.resumeWithException(Exception(json.getString("value")))
            else -> continuation.resumeWithException(Exception("Invalid response type"))
        }
    } catch (e: Exception) {
        // Handle parsing errors
    }
}
```

### Step 2: Define the Awaitable Call Function in Android
Create a suspend function to call the async JS method. It generates a unique ID, executes a script to invoke the method, and awaits the response via the continuation map.

```kotlin
suspend fun callJsAsync(methodName: String, argJson: String): String = suspendCancellableCoroutine { cont ->
    val id = UUID.randomUUID().toString()
    continuations[id] = cont
    val script = """
        (async function() {
            try {
                const arg = JSON.parse('$argJson');
                const result = await window['$methodName'](arg);
                window.androidBridge.postMessage(JSON.stringify({
                    id: '$id',
                    type: 'result',
                    value: JSON.stringify(result)
                }));
            } catch (e) {
                window.androidBridge.postMessage(JSON.stringify({
                    id: '$id',
                    type: 'error',
                    value: e.message
                }));
            }
        })();
    """.trimIndent()
    webView.evaluateJavascript(script, null)
    cont.invokeOnCancellation {
        // Optional: Clean up if cancelled
        continuations.remove(id)
    }
}
```

- **Usage Example** (from a coroutine scope, e.g., lifecycleScope.launch):
  ```kotlin
  try {
      val arg = JSONObject().apply { put("key", "value") }.toString()
      val result = callJsAsync("myAsyncMethod", arg)
      // Handle result (as string; parse if JSON)
  } catch (e: Exception) {
      // Handle error
  }
  ```

### Step 3: Implement the Async Method in the Web App (JavaScript)
Expose your async methods on the global `window` object so they can be invoked via the injected script. No additional setup is needed for `androidBridge`—it's automatically available once the listener is added.

```javascript
// Example async method in your web app
window.myAsyncMethod = async function(arg) {
    // arg is the parsed object from Android
    // Perform async work, e.g., fetch or Promise-based operation
    const response = await fetch('https://example.com/api', { method: 'POST', body: JSON.stringify(arg) });
    const data = await response.json();
    return data;  // This will be stringified and sent back
};
```

### Key Considerations and Best Practices
- **Security**: `WebMessageCompat` limits communication to string messages, avoiding the risks of `addJavascriptInterface` (e.g., reflection attacks on unannotated methods in older APIs). Restrict origins (e.g., `setOf("https://your-domain.com")`) in production to prevent cross-origin issues.
- **Error Handling**: Always handle exceptions in both sides. Use JSON for complex data to ensure serialization.
- **Performance**: `evaluateJavascript` is async and non-blocking. For high-frequency calls, batch requests if possible.
- **Compatibility**: Requires API 21+ for full `androidx.webkit` support. Test on emulators/devices with varying WebView versions.
- **Debugging**: Enable WebView debugging with `WebView.setWebContentsDebuggingEnabled(true)` and inspect in Chrome DevTools (chrome://inspect).
- **Alternatives if Needed**: If you prefer a library for abstraction, consider JsBridge (GitHub: lzyzsd/JsBridge), which wraps similar patterns but adds overhead. Stick to native APIs for lighter weight.

This setup provides a clean, awaitable API in Android while keeping the bridge asynchronous and secure.