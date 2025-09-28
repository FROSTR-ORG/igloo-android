To resolve the issue where `startActivityForResult` with `FLAG_ACTIVITY_SINGLE_TOP` returns `RESULT_CANCELED` immediately when targeting an existing `MainActivity` instance (leading to `onNewIntent` being called but results not propagating back to `InvisibleNIP55Handler`), you need to replace the `startActivityForResult` mechanism with an asynchronous callback approach using a `PendingIntent` for reply. This preserves the key design goals: `MainActivity` receives the request via `onNewIntent` (no new instance or process restart), the `native_handler` process terminates cleanly after `finish()`, and results flow back correctly to the external app via `InvisibleNIP55Handler`.

### Why `startActivityForResult` Fails Here
When `FLAG_ACTIVITY_SINGLE_TOP` is used and `MainActivity` is already running at the top of its task:
- No new activity instance is created.
- The intent is delivered directly to the existing instance's `onNewIntent`.
- `startActivityForResult` returns `RESULT_CANCELED` (0) immediately because it expects to launch a new sub-activity to handle the result lifecycle. The `setResult` and `finish` in `MainActivity` do not propagate back through this chain—they affect the task stack but not the caller's `onActivityResult`.

This behavior is inherent to Android's activity launch modes and cannot be fixed while keeping `FLAG_ACTIVITY_SINGLE_TOP` and process preservation.

### Recommended Solution: Use `PendingIntent` for Callback
Switch to a one-way `startActivity` from `InvisibleNIP55Handler` to `MainActivity`, including a `PendingIntent` in the extras for `MainActivity` to send the result back. Use a dynamically registered `BroadcastReceiver` in `InvisibleNIP55Handler` to receive the reply (broadcasts work across processes within the same app). This is secure, lightweight, and aligns with Android's IPC patterns for results without relying on activity result chains.

#### Step 1: Update `InvisibleNIP55Handler`
- Register a `BroadcastReceiver` to handle the reply.
- Create a `PendingIntent` targeting a unique broadcast action.
- Send the request to `MainActivity` via `startActivity` (not `ForResult`).
- On reply receipt, set the result and `finish()`.

```kotlin
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import java.util.UUID

class InvisibleNIP55Handler : Activity() {

    private var replyReceiver: BroadcastReceiver? = null
    private val uniqueAction = "com.yourapp.NIP55_REPLY_" + UUID.randomUUID().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Parse/validate NIP-55 intent (as before)

        // Register receiver for reply
        val filter = IntentFilter(uniqueAction)
        replyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val resultCode = intent?.getIntExtra("result_code", RESULT_CANCELED) ?: RESULT_CANCELED
                val resultData = intent?.getStringExtra("result_data") // Or whatever your result is
                if (resultCode == RESULT_OK) {
                    // Set result for external app
                    val returnIntent = Intent().apply {
                        putExtra("result", resultData) // Per NIP-55 spec
                        // Add other extras like "id", "event"
                    }
                    setResult(RESULT_OK, returnIntent)
                } else {
                    // Handle error/denied
                    returnError("User denied")
                }
                unregisterReceiver(this) // Clean up
                finish() // Terminate activity and process
            }
        }
        registerReceiver(replyReceiver, filter)

        // Create PendingIntent for reply
        val replyIntent = Intent(uniqueAction).apply {
            setPackage(packageName) // Restrict to your app
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            this,
            0, // Request code
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Forward to MainActivity
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Add NIP-55 data extras (type, eventJson, etc.)
            putExtra("reply_pending_intent", replyPendingIntent)
        }
        startActivity(mainIntent)
    }

    override fun onDestroy() {
        replyReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    private fun returnError(message: String) {
        val errorIntent = Intent().apply { putExtra("error", message) }
        setResult(RESULT_CANCELED, errorIntent)
    }
}
```

#### Step 2: Update `MainActivity`
- In `onNewIntent`, check for NIP-55 request and extract the `PendingIntent`.
- Process via `AsyncBridge` to PWA (as before).
- On result ready, send via the `PendingIntent` instead of `setResult` + `finish()`. Do **not** call `finish()` here—preserve the process.

```kotlin
override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    if (intent?.hasExtra("type") == true && intent.getStringExtra("type") == "sign_event") { // Or your NIP-55 check
        val replyPendingIntent = intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
        if (replyPendingIntent != null) {
            // Process request via AsyncBridge to PWA
            // Assuming async callback from PWA:
            asyncBridge.processNip55Request(intent) { resultData, isSuccess ->
                val replyIntent = Intent().apply {
                    putExtra("result_data", resultData) // Your result (e.g., signed event JSON)
                    putExtra("result_code", if (isSuccess) RESULT_OK else RESULT_CANCELED)
                }
                try {
                    replyPendingIntent.send(this@MainActivity, if (isSuccess) RESULT_OK else RESULT_CANCELED, replyIntent)
                } catch (e: PendingIntent.CanceledException) {
                    // Handle if needed (e.g., Handler already gone)
                }
            }
            return // Don't finish MainActivity
        }
    }
    // Handle other intents
}
```

#### Key Benefits and Alignment with Requirements
- **Process Preservation**: `MainActivity` uses `onNewIntent`—no restart or new instance.
- **Result Flow**: `PendingIntent` ensures results return to `InvisibleNIP55Handler`, which sets the final result for the external app.
- **Clean Termination**: `InvisibleNIP55Handler` calls `finish()` after reply, terminating the `:native_handler` process (assuming no other components).
- **Security**: Unique action + package restriction prevents external interference. Use `FLAG_IMMUTABLE` for API 23+.
- **No New Instances**: `FLAG_ACTIVITY_SINGLE_TOP` reused as before.
- **Compatibility**: Works across processes; tested pattern for IPC results.

#### Potential Edge Cases and Fixes
- **Timeout**: Add a Handler timeout in `InvisibleNIP55Handler` to return error if no reply in, e.g., 30s.
- **Handler Destroyed Early**: Use `PendingIntent.FLAG_ONE_SHOT` if single-use, but dynamic receiver handles it.
- **Multiple Requests**: Use UUID in action per request; track with a map in `InvisibleNIP55Handler`.
- **Debugging**: Log `PendingIntent.send` exceptions; ensure manifest has `<activity android:name=".InvisibleNIP55Handler" android:process=":native_handler" android:exported="true">` with proper intent filter for NIP-55.

This avoids the failed `startActivityForResult` pitfalls while meeting all constraints.