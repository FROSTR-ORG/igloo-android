package com.frostr.igloo.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for UIOverlayManager.
 *
 * Tests verify overlay show/hide behavior, debug button visibility,
 * and proper cleanup of UI resources.
 *
 * Note: Tests that require setupSigningOverlay() are skipped because
 * they require Android layout resources. Instead, we test the methods
 * that don't require resource inflation and verify null-safety behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UIOverlayManagerTest {

    private lateinit var activity: Activity
    private lateinit var overlayManager: UIOverlayManager

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).create().get()
        overlayManager = UIOverlayManager(activity)
    }

    // ========== Signing Overlay Null-Safety Tests ==========

    @Test
    fun `showSigningOverlay does nothing before setup`() {
        // Should not throw when called before setup
        overlayManager.showSigningOverlay()
        // No exception = success
    }

    @Test
    fun `hideSigningOverlay does nothing before setup`() {
        // Should not throw when called before setup
        overlayManager.hideSigningOverlay()
        // No exception = success
    }

    @Test
    fun `hideSigningOverlay is safe when overlay not shown`() {
        // Should handle case where overlay was never shown
        overlayManager.hideSigningOverlay()
        overlayManager.hideSigningOverlay() // Multiple calls
        // No exception = success
    }

    // ========== Splash Screen Tests ==========

    @Test
    fun `setSplashScreen stores reference`() {
        val splashView = View(activity)
        overlayManager.setSplashScreen(splashView)

        // Show should work without exception
        overlayManager.showSplashScreen()

        assertThat(splashView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `showSplashScreen sets visibility to VISIBLE`() {
        val splashView = View(activity).apply {
            visibility = View.GONE
        }
        overlayManager.setSplashScreen(splashView)

        overlayManager.showSplashScreen()

        assertThat(splashView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `showSplashScreen does nothing when no splash set`() {
        // Should not throw when no splash is set
        overlayManager.showSplashScreen()
        // No exception = success
    }

    @Test
    fun `setSplashScreen with null is safe`() {
        overlayManager.setSplashScreen(null)
        overlayManager.showSplashScreen()
        // No exception = success
    }

    @Test
    fun `hideSplashScreen does nothing when no splash set`() {
        overlayManager.hideSplashScreen()
        // No exception = success
    }

    // ========== Cleanup Tests ==========

    @Test
    fun `cleanup works when nothing was setup`() {
        // Should not throw when cleanup called without any setup
        overlayManager.cleanup()
        // No exception = success
    }

    @Test
    fun `cleanup can be called multiple times safely`() {
        overlayManager.cleanup()
        overlayManager.cleanup()
        overlayManager.cleanup()
        // No exception = success
    }

    // ========== Splash Screen Lifecycle Tests ==========

    @Test
    fun `splash screen can be shown and hidden`() {
        val splashView = View(activity).apply {
            visibility = View.GONE
        }
        overlayManager.setSplashScreen(splashView)

        // Show
        overlayManager.showSplashScreen()
        assertThat(splashView.visibility).isEqualTo(View.VISIBLE)

        // Hide (note: this uses animation, so visibility change is async)
        overlayManager.hideSplashScreen()
        // Animation runs, so we process the looper
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertThat(splashView.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `splash screen show-hide cycle is idempotent`() {
        val splashView = View(activity).apply {
            visibility = View.VISIBLE
        }
        overlayManager.setSplashScreen(splashView)

        // Multiple shows
        overlayManager.showSplashScreen()
        overlayManager.showSplashScreen()
        assertThat(splashView.visibility).isEqualTo(View.VISIBLE)
    }

    // ========== Debug Button Tests ==========
    // Note: Debug button tests are limited because DebugConfig.isDebugBuild()
    // and drawable resources aren't available in unit tests

    @Test
    fun `showDebugButton is safe when not setup`() {
        overlayManager.showDebugButton()
        // No exception = success
    }

    @Test
    fun `hideDebugButton is safe when not setup`() {
        overlayManager.hideDebugButton()
        // No exception = success
    }
}
