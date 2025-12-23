package com.frostr.igloo.webview

import android.app.Activity
import android.webkit.WebView
import com.frostr.igloo.bridges.BridgeFactory
import com.frostr.igloo.bridges.BridgeSet
import com.frostr.igloo.bridges.StorageBridge
import com.frostr.igloo.bridges.WebSocketBridge
import com.frostr.igloo.bridges.UnifiedSigningBridge
import com.frostr.igloo.bridges.UnifiedSigningService
import com.frostr.igloo.AsyncBridge
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for WebViewManager.
 *
 * Uses mocked BridgeFactory to avoid EncryptedSharedPreferences issues.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WebViewManagerTest {

    private lateinit var activity: Activity

    @Mock
    private lateinit var mockFactory: BridgeFactory

    @Mock
    private lateinit var mockBridgeSet: BridgeSet

    @Mock
    private lateinit var mockStorageBridge: StorageBridge

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        activity = Robolectric.buildActivity(Activity::class.java).create().get()

        // Setup mock BridgeSet
        `when`(mockBridgeSet.storageBridge).thenReturn(mockStorageBridge)
        `when`(mockStorageBridge.restoreSessionStorage()).thenReturn(false)
    }

    @Test
    fun `getWebView returns null before initialize`() {
        val manager = WebViewManager(
            activity,
            mockFactory,
            BridgeFactory.BridgeContext.MAIN_ACTIVITY,
            activity
        )

        assertThat(manager.getWebView()).isNull()
    }

    @Test
    fun `getBridges returns null before initialize`() {
        val manager = WebViewManager(
            activity,
            mockFactory,
            BridgeFactory.BridgeContext.MAIN_ACTIVITY,
            activity
        )

        assertThat(manager.getBridges()).isNull()
    }

    @Test
    fun `isReady returns false before initialize`() {
        val manager = WebViewManager(
            activity,
            mockFactory,
            BridgeFactory.BridgeContext.MAIN_ACTIVITY,
            activity
        )

        assertThat(manager.isReady()).isFalse()
    }

    @Test
    fun `setReadyListener with null does not throw`() {
        val manager = WebViewManager(
            activity,
            mockFactory,
            BridgeFactory.BridgeContext.MAIN_ACTIVITY,
            activity
        )

        manager.setReadyListener(object : WebViewManager.WebViewReadyListener {
            override fun onWebViewReady() {}
            override fun onWebViewLoadProgress(progress: Int) {}
            override fun onConsoleMessage(level: String, message: String, source: String, line: Int) {}
        })

        // Should not throw
        manager.setReadyListener(null)
    }

    @Test
    fun `cleanup can be called on uninitialized manager`() {
        val manager = WebViewManager(
            activity,
            mockFactory,
            BridgeFactory.BridgeContext.MAIN_ACTIVITY,
            activity
        )

        // Should not throw
        manager.cleanup()

        assertThat(manager.getWebView()).isNull()
        assertThat(manager.getBridges()).isNull()
    }

    @Test
    fun `cleanup can be called multiple times`() {
        val manager = WebViewManager(
            activity,
            mockFactory,
            BridgeFactory.BridgeContext.MAIN_ACTIVITY,
            activity
        )

        // Should not throw
        manager.cleanup()
        manager.cleanup()
        manager.cleanup()
    }

    @Test
    fun `WebViewReadyListener interface has expected methods`() {
        // Verify the interface contract
        val listener = object : WebViewManager.WebViewReadyListener {
            var readyCalled = false
            var progressValue = -1
            var consoleLevel: String? = null

            override fun onWebViewReady() {
                readyCalled = true
            }

            override fun onWebViewLoadProgress(progress: Int) {
                progressValue = progress
            }

            override fun onConsoleMessage(level: String, message: String, source: String, line: Int) {
                consoleLevel = level
            }
        }

        // Verify methods can be called
        listener.onWebViewReady()
        listener.onWebViewLoadProgress(50)
        listener.onConsoleMessage("INFO", "test", "source.js", 10)

        assertThat(listener.readyCalled).isTrue()
        assertThat(listener.progressValue).isEqualTo(50)
        assertThat(listener.consoleLevel).isEqualTo("INFO")
    }

    // Note: Tests that call initialize() require proper bridge mocking
    // which is complex due to addJavascriptInterface calls.
    // Full integration tests should be done via instrumented tests.
}
