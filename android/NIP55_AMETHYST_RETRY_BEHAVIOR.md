# Amethyst NIP-55 Retry Behavior Analysis

## Problem: 48+ Duplicate Requests in Queue

When testing background signing with Amethyst, we observed 48+ duplicate signing requests queuing up in `InvisibleNIP55Handler`, causing Amethyst to freeze.

## Root Cause

### Amethyst's Timeout Mechanism

From `IntentRequestManager.kt:45-143`:

```kotlin
class IntentRequestManager(
    val foregroundApprovalTimeout: Long = 30000,  // 30 second timeout
) {
    suspend fun <T : IResult> launchWaitAndParse(
        requestIntentBuilder: () -> Intent,
        parser: (intent: IntentResult) -> SignerResult.RequestAddressed<T>,
    ): SignerResult.RequestAddressed<T> =
        appLauncher?.let { launcher ->
            val requestIntent = requestIntentBuilder()
            val callId = RandomInstance.randomChars(32)  // NEW ID EVERY TIME

            requestIntent.putExtra("id", callId)
            requestIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

            val resultIntent = tryAndWait(foregroundApprovalTimeout) { ... }

            when (resultIntent) {
                null -> SignerResult.RequestAddressed.TimedOut()  // Timeout after 30s
                else -> parser(resultIntent)
            }
        }
}
```

**Key behavior:**
1. Amethyst sends signing request via Intent
2. Generates **new random request ID** for each attempt (line 119)
3. Waits 30 seconds for response
4. If no response: **times out** and returns `SignerResult.RequestAddressed.TimedOut()`
5. Calling code likely **retries** the signing request with a **NEW request ID**

### Why InvisibleNIP55Handler Never Responded

Previous implementation had a critical flaw:
1. `InvisibleNIP55Handler` receives signing request
2. Launches `MainActivity` to execute PWA signing
3. Calls `moveTaskToBack(true)` to move to background (correct)
4. **But:** `MainActivity` immediately goes to `onPause` (PWA can't execute signing while paused)
5. **Result:** No signing occurs, no result sent back to Amethyst
6. **Amethyst:** Times out after 30 seconds, retries with new request ID
7. **Loop:** Steps 1-6 repeat, creating 48+ duplicate requests

## Why Request ID Deduplication Failed

Initial implementation deduplicated by NIP-55 request ID:

```kotlin
val isDuplicate = pendingRequests.any { it.id == newRequest.id }
```

**Problem:** Amethyst generates a **new request ID** for every retry (line 119), even though the **event content** is identical.

**Result:** All 48 retry requests had different IDs, so they all passed deduplication and were added to queue.

## Solution: Event-Based Deduplication

For `sign_event` requests, deduplicate by **event ID** (from the event content) instead of request ID:

```kotlin
val isDuplicate = if (newRequest.type == "sign_event" && newRequest.params.containsKey("event")) {
    try {
        val eventJson = newRequest.params["event"]
        val eventMap = gson.fromJson(eventJson, Map::class.java)
        val eventId = eventMap["id"] as? String

        // Check if this event ID already exists in queue
        pendingRequests.any { req ->
            if (req.type == "sign_event" && req.params.containsKey("event")) {
                try {
                    val existingEventJson = req.params["event"]
                    val existingEventMap = gson.fromJson(existingEventJson, Map::class.java)
                    val existingEventId = existingEventMap["id"] as? String
                    existingEventId == eventId
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to extract event id for deduplication", e)
        false
    }
} else {
    // For non-sign_event, use request ID
    pendingRequests.any { it.id == newRequest.id }
}
```

**Why this works:**
- Nostr event IDs are **deterministic** (derived from event content hash)
- Even though Amethyst retries with new NIP-55 request IDs, the **event ID stays the same**
- Duplicate events are properly detected and rejected

## Expected Log Output

With event-based deduplication:

```
InvisibleNIP55Handler: NIP-55 request received from external app (onCreate)
InvisibleNIP55Handler: Processing NIP-55 request (type: sign_event, id: abc123)
InvisibleNIP55Handler: Added to queue. Current queue size: 1
InvisibleNIP55Handler: Starting request processing...

[30 seconds pass, Amethyst retries]

InvisibleNIP55Handler: NIP-55 request received from external app (onNewIntent)
InvisibleNIP55Handler: Ignoring duplicate request (event already in queue)
InvisibleNIP55Handler: Current queue size: 1  // <-- STILL 1, NOT 48
```

## Related Files

- `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/InvisibleNIP55Handler.kt`
- `/home/cscott/Repos/frostr/pwa/repos/amethyst/quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/api/foreground/IntentRequestManager.kt`
- `/home/cscott/Repos/frostr/pwa/android/NIP55_BACKGROUND_SIGNING_ANALYSIS.md`

## Testing

To verify the fix:
1. Build and install APK with event-based deduplication
2. Clear logs: `adb logcat -c`
3. Open Amethyst and trigger background signing
4. Monitor logs: Look for "Current queue size" - should stay at 1, not climb to 48
5. Verify deduplication: Look for "Ignoring duplicate request (event already in queue)"

---

**Last Updated:** 2025-10-05
