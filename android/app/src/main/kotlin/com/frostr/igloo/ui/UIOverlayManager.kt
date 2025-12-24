package com.frostr.igloo.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import com.frostr.igloo.R
import com.frostr.igloo.debug.DebugConfig
import com.frostr.igloo.debug.MetricsActivity

/**
 * Manages UI overlays for MainActivity.
 *
 * Handles:
 * - Signing overlay (shown during fast NIP-55 signing)
 * - Splash screen (shown during PWA loading)
 * - Debug button (shown in debug builds for metrics access)
 *
 * Usage:
 * ```kotlin
 * val overlayManager = UIOverlayManager(activity)
 * overlayManager.setupSigningOverlay()
 * overlayManager.showSigningOverlay()
 * overlayManager.hideSigningOverlay()
 * ```
 */
class UIOverlayManager(private val activity: Activity) {

    companion object {
        private const val TAG = "UIOverlayManager"
    }

    private var signingOverlay: View? = null
    private var splashScreen: View? = null
    private var debugButton: View? = null

    // ==================== Signing Overlay ====================

    /**
     * Setup signing overlay (splash screen for fast signing)
     */
    fun setupSigningOverlay() {
        if (signingOverlay == null) {
            val inflater = LayoutInflater.from(activity)
            signingOverlay = inflater.inflate(R.layout.signing_overlay, null)
        }
    }

    /**
     * Show signing overlay (hides PWA during fast signing)
     */
    fun showSigningOverlay() {
        signingOverlay?.let { overlay ->
            if (overlay.parent == null) {
                val rootView = activity.window.decorView as ViewGroup
                rootView.addView(overlay)
                Log.d(TAG, "Signing overlay shown")
            }
        }
    }

    /**
     * Hide signing overlay (reveals PWA)
     */
    fun hideSigningOverlay() {
        signingOverlay?.let { overlay ->
            val parent = overlay.parent as? ViewGroup
            parent?.removeView(overlay)
            Log.d(TAG, "Signing overlay hidden")
        }
    }

    // ==================== Splash Screen ====================

    /**
     * Set the splash screen view reference (from layout)
     */
    fun setSplashScreen(view: View?) {
        splashScreen = view
    }

    /**
     * Show splash screen
     */
    fun showSplashScreen() {
        splashScreen?.visibility = View.VISIBLE
        Log.d(TAG, "Splash screen displayed")
    }

    /**
     * Hide the splash screen with a smooth fade-out animation
     */
    fun hideSplashScreen() {
        splashScreen?.let { splash ->
            Log.d(TAG, "Hiding splash screen with fade animation")
            splash.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    splash.visibility = View.GONE
                    Log.d(TAG, "Splash screen hidden")
                }
                .start()
        }
    }

    // ==================== Debug Button ====================

    /**
     * Setup debug metrics button (debug builds only)
     */
    fun setupDebugButton() {
        if (!DebugConfig.isDebugBuild()) return

        debugButton = ImageButton(activity).apply {
            setBackgroundResource(R.drawable.debug_button_background)
            setImageResource(R.drawable.ic_bug)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(12, 12, 12, 12)
            contentDescription = "Diagnostic Info"
            setOnClickListener {
                activity.startActivity(Intent(activity, MetricsActivity::class.java))
            }
        }
        Log.d(TAG, "Debug button created")
    }

    /**
     * Show debug button (debug builds only)
     */
    fun showDebugButton() {
        if (!DebugConfig.isDebugBuild()) return

        debugButton?.let { button ->
            if (button.parent == null) {
                val density = activity.resources.displayMetrics.density
                val buttonSize = (56 * density).toInt()
                val margin = (16 * density).toInt()

                val params = FrameLayout.LayoutParams(
                    buttonSize,
                    buttonSize
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    marginEnd = margin
                    bottomMargin = margin
                }

                val rootView = activity.window.decorView as ViewGroup
                rootView.addView(button, params)
                Log.d(TAG, "Debug button shown")
            }
        }
    }

    /**
     * Hide debug button
     */
    fun hideDebugButton() {
        debugButton?.let { button ->
            val parent = button.parent as? ViewGroup
            parent?.removeView(button)
            Log.d(TAG, "Debug button hidden")
        }
    }

    // ==================== Cleanup ====================

    /**
     * Clean up all overlay resources
     */
    fun cleanup() {
        hideSigningOverlay()
        hideDebugButton()
        signingOverlay = null
        splashScreen = null
        debugButton = null
    }
}
