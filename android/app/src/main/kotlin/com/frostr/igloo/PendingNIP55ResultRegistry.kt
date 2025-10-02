package com.frostr.igloo

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-Task Result Registry for NIP-55 Requests
 *
 * Problem: InvisibleNIP55Handler runs in external app's task (e.g., Amethyst),
 * while MainActivity runs in Igloo's task. startActivityForResult() doesn't work
 * across task boundaries.
 *
 * Solution: Singleton registry that both activities can access. MainActivity writes
 * results, InvisibleNIP55Handler polls/waits for results.
 *
 * Thread-safe and works across any task boundary within the same process.
 */
object PendingNIP55ResultRegistry {
    private const val TAG = "NIP55ResultRegistry"

    // Map of request ID to result callback
    private val pendingCallbacks = ConcurrentHashMap<String, ResultCallback>()

    /**
     * Register a callback for a specific request ID
     */
    fun registerCallback(requestId: String, callback: ResultCallback) {
        Log.d(TAG, "Registering callback for request: $requestId")
        pendingCallbacks[requestId] = callback
    }

    /**
     * Deliver a result for a specific request ID
     * Returns true if callback was found and invoked, false otherwise
     */
    fun deliverResult(requestId: String, result: NIP55Result): Boolean {
        Log.d(TAG, "Attempting to deliver result for request: $requestId")
        val callback = pendingCallbacks.remove(requestId)

        return if (callback != null) {
            Log.d(TAG, "✓ Callback found, invoking...")
            try {
                callback.onResult(result)
                true
            } catch (e: Exception) {
                Log.e(TAG, "✗ Callback threw exception", e)
                false
            }
        } else {
            Log.w(TAG, "✗ No callback registered for request: $requestId")
            false
        }
    }

    /**
     * Cancel a pending callback (e.g., on timeout)
     */
    fun cancelCallback(requestId: String) {
        Log.d(TAG, "Cancelling callback for request: $requestId")
        pendingCallbacks.remove(requestId)
    }

    /**
     * Get count of pending callbacks (for debugging)
     */
    fun getPendingCount(): Int = pendingCallbacks.size

    /**
     * Callback interface for result delivery
     */
    interface ResultCallback {
        fun onResult(result: NIP55Result)
    }
}
