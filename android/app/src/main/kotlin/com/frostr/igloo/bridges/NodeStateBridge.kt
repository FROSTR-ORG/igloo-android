package com.frostr.igloo.bridges

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.frostr.igloo.MainActivity
import com.frostr.igloo.health.IglooHealthManager

/**
 * JavaScript bridge for managing node state and health.
 *
 * This bridge allows the PWA to signal node state changes to the health manager.
 * When the node is online, the system is marked healthy to enable NIP-55 signing.
 * When the node goes offline, the system is marked unhealthy.
 *
 * Usage from JavaScript:
 * ```javascript
 * NodeStateBridge.onNodeOnline()   // Mark system healthy
 * NodeStateBridge.onNodeOffline()  // Mark system unhealthy
 * NodeStateBridge.isServiceRunning() // Check health state
 * ```
 */
class NodeStateBridge(
    private val context: Context,
    private val webViewProvider: (() -> WebView?)? = null
) {

    companion object {
        private const val TAG = "NodeStateBridge"
    }

    /**
     * Called by PWA when bifrost node goes online (unlocked).
     * Stores the WebView for background signing and marks system healthy.
     */
    @JavascriptInterface
    fun onNodeOnline() {
        Log.d(TAG, "Node online - marking system healthy")

        // Store WebView in persistent holder for background signing
        webViewProvider?.invoke()?.let { webView ->
            MainActivity.setPersistentWebView(webView)
            Log.d(TAG, "WebView stored in persistent holder")
        }

        // Mark system healthy (starts health timeout)
        IglooHealthManager.markHealthy()

        // If this was a cold start, move to background now that everything is ready
        MainActivity.onNodeReadyForBackground()
    }

    /**
     * Called by PWA when bifrost node is locked or disconnected.
     * Clears the persistent WebView and marks system unhealthy.
     */
    @JavascriptInterface
    fun onNodeOffline() {
        Log.d(TAG, "Node offline - marking system unhealthy")

        // Only clear WebView if system was healthy
        if (IglooHealthManager.isHealthy) {
            Log.d(TAG, "System was healthy - clearing persistent WebView")
            MainActivity.clearPersistentWebView()
            IglooHealthManager.markUnhealthy()
        } else {
            Log.d(TAG, "System already unhealthy - skipping cleanup (likely initial startup)")
        }
    }

    /**
     * Check if system is currently healthy and ready for signing.
     * @return true if system is healthy
     */
    @JavascriptInterface
    fun isServiceRunning(): Boolean {
        return IglooHealthManager.isHealthy
    }
}
