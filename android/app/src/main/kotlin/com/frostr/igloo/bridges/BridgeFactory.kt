package com.frostr.igloo.bridges

import android.app.Activity
import android.content.Context
import android.webkit.WebView
import com.frostr.igloo.AsyncBridge

/**
 * Factory for creating bridge instances.
 *
 * Centralizes bridge creation with context-aware decisions about which bridges
 * to create. MAIN_ACTIVITY context includes all bridges, while FOREGROUND_SERVICE
 * context excludes UI-dependent bridges like camera and QR scanner.
 *
 * Usage:
 * ```kotlin
 * val factory = BridgeFactory(context)
 * val bridges = factory.createBridges(webView, BridgeContext.MAIN_ACTIVITY, activity)
 * ```
 */
class BridgeFactory(private val context: Context) {

    /**
     * Bridge creation context - determines which bridges to create.
     */
    enum class BridgeContext {
        /** Full UI context with camera, QR scanner, and dialogs */
        MAIN_ACTIVITY,

        /** Headless context for background signing (minimal bridges) */
        FOREGROUND_SERVICE
    }

    /**
     * Create a StorageBridge instance.
     */
    fun createStorageBridge(): StorageBridge = StorageBridge(context)

    /**
     * Create a WebSocketBridge instance.
     */
    fun createWebSocketBridge(webView: WebView): WebSocketBridge =
        WebSocketBridge(webView)

    /**
     * Create an AsyncBridge instance.
     */
    fun createAsyncBridge(webView: WebView): AsyncBridge =
        AsyncBridge(webView).apply { initialize() }

    /**
     * Create a UnifiedSigningService instance.
     */
    fun createSigningService(webView: WebView): UnifiedSigningService =
        UnifiedSigningService(context, webView)

    /**
     * Create a UnifiedSigningBridge instance.
     */
    fun createSigningBridge(
        webView: WebView,
        signingService: UnifiedSigningService
    ): UnifiedSigningBridge =
        UnifiedSigningBridge(context, webView).apply {
            initialize(signingService)
        }

    /**
     * Create a QRScannerBridge instance.
     * Requires an Activity for camera permissions and UI.
     */
    fun createQRScannerBridge(
        activity: Activity,
        webView: WebView
    ): QRScannerBridge = QRScannerBridge(activity, webView)

    /**
     * Create a ModernCameraBridge instance.
     */
    fun createCameraBridge(webView: WebView): ModernCameraBridge =
        ModernCameraBridge(context, webView)

    /**
     * Create all bridges for a given context.
     *
     * @param webView The WebView instance for bridges
     * @param bridgeContext The context (MAIN_ACTIVITY or FOREGROUND_SERVICE)
     * @param activity Required for MAIN_ACTIVITY context (camera/QR features)
     * @return BridgeSet containing all created bridges
     */
    fun createBridges(
        webView: WebView,
        bridgeContext: BridgeContext,
        activity: Activity? = null
    ): BridgeSet {
        val storageBridge = createStorageBridge()
        val webSocketBridge = createWebSocketBridge(webView)
        val asyncBridge = createAsyncBridge(webView)

        val signingService = createSigningService(webView)
        val signingBridge = createSigningBridge(webView, signingService)

        // Only create UI-dependent bridges for MAIN_ACTIVITY context
        val qrScannerBridge = if (bridgeContext == BridgeContext.MAIN_ACTIVITY && activity != null) {
            createQRScannerBridge(activity, webView)
        } else null

        val cameraBridge = if (bridgeContext == BridgeContext.MAIN_ACTIVITY) {
            createCameraBridge(webView)
        } else null

        return BridgeSet(
            storageBridge = storageBridge,
            webSocketBridge = webSocketBridge,
            asyncBridge = asyncBridge,
            signingService = signingService,
            signingBridge = signingBridge,
            qrScannerBridge = qrScannerBridge,
            cameraBridge = cameraBridge
        )
    }
}

/**
 * Container for all created bridges.
 *
 * Provides unified access to all bridge instances and a cleanup method
 * that properly disposes of all resources.
 */
data class BridgeSet(
    val storageBridge: StorageBridge,
    val webSocketBridge: WebSocketBridge,
    val asyncBridge: AsyncBridge,
    val signingService: UnifiedSigningService,
    val signingBridge: UnifiedSigningBridge,
    val qrScannerBridge: QRScannerBridge?,
    val cameraBridge: ModernCameraBridge?
) {
    /**
     * Clean up all bridge resources.
     * Should be called when the bridges are no longer needed.
     */
    fun cleanup() {
        webSocketBridge.cleanup()
        signingBridge.cleanup()
        signingService.cleanup()
        qrScannerBridge?.cleanup()
        cameraBridge?.cleanup()
        storageBridge.clearSessionStorage()
    }
}
