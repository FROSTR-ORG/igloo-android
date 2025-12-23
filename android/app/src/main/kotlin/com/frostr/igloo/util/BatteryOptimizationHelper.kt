package com.frostr.igloo.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper for checking and requesting battery optimization exemptions.
 *
 * Background services like IglooForegroundService work best when the app
 * is exempt from battery optimization. This helper provides methods to
 * check the current status and create intents to request exemption.
 *
 * Usage:
 * ```kotlin
 * if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
 *     val intent = BatteryOptimizationHelper.createBatteryOptimizationIntent(context)
 *     startActivity(intent)
 * }
 * ```
 */
object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptimHelper"

    /**
     * Check if this app is exempt from battery optimization.
     *
     * @param context Application context
     * @return true if app is ignoring battery optimizations, false otherwise
     */
    @JvmStatic
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Battery optimization doesn't exist before API 23
            return true
        }

        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check battery optimization status", e)
            // Assume we're not ignoring if we can't check
            false
        }
    }

    /**
     * Create an intent to open battery optimization settings for this app.
     *
     * This opens the system dialog asking the user to exempt the app from
     * battery optimization. The user must manually approve this.
     *
     * @param context Application context
     * @return Intent to open battery optimization settings
     */
    @JvmStatic
    fun createBatteryOptimizationIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            // Fallback to general app settings for older devices
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    /**
     * Create an intent to open the battery optimization settings list.
     *
     * This opens the full list of apps with their battery optimization status,
     * useful if the direct request intent doesn't work on some devices.
     *
     * @return Intent to open battery optimization settings list
     */
    @JvmStatic
    fun createBatteryOptimizationListIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
    }

    /**
     * Get a user-friendly explanation of why battery optimization exemption is needed.
     *
     * @return Explanation string
     */
    @JvmStatic
    fun getExplanation(): String {
        return "Background signing requires exemption from battery optimization " +
               "to work reliably. Without this, Android may kill the signing " +
               "service to save battery, causing signing requests to fail."
    }

    /**
     * Check if we should prompt the user about battery optimization.
     *
     * This checks:
     * 1. Is the device running API 23+ (battery optimization exists)?
     * 2. Is the app currently subject to battery optimization?
     * 3. Has the user dismissed this prompt recently?
     *
     * @param context Application context
     * @param lastPromptTimeMs Timestamp of last prompt (0 if never prompted)
     * @param cooldownMs How long to wait before prompting again
     * @return true if we should prompt the user
     */
    @JvmStatic
    fun shouldPrompt(
        context: Context,
        lastPromptTimeMs: Long = 0,
        cooldownMs: Long = 24 * 60 * 60 * 1000L // 24 hours
    ): Boolean {
        // Don't prompt on older devices
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }

        // Already exempt
        if (isIgnoringBatteryOptimizations(context)) {
            return false
        }

        // Check cooldown
        if (lastPromptTimeMs > 0) {
            val elapsed = System.currentTimeMillis() - lastPromptTimeMs
            if (elapsed < cooldownMs) {
                Log.d(TAG, "Battery optimization prompt on cooldown (${elapsed}ms < ${cooldownMs}ms)")
                return false
            }
        }

        return true
    }
}
