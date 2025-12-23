package com.frostr.igloo.bridges

import android.app.Activity
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.frostr.igloo.QRScannerActivity

/**
 * QR Scanner Bridge - Native Android QR/Barcode Scanning
 *
 * Launches a full-screen camera activity to scan QR codes.
 * Uses bundled ML Kit - no dynamic downloads required.
 *
 * Extends BridgeBase for common functionality.
 */
class QRScannerBridge(
    private val activity: Activity,
    webView: WebView
) : BridgeBase(webView) {

    companion object {
        const val QR_SCAN_REQUEST_CODE = 1001
    }

    private var pendingCallback: String? = null

    init {
        logDebug("QR Scanner bridge initialized")
    }

    /**
     * Scan a QR code using the native Android camera
     * Returns a promise-like callback ID that will be resolved with the result
     */
    @JavascriptInterface
    fun scanQRCode(callbackId: String) {
        logDebug("QR scan requested with callback: $callbackId")

        // Store callback for when scan completes
        pendingCallback = callbackId

        // Launch QR scanner activity on main thread
        activity.runOnUiThread {
            val intent = Intent(activity, QRScannerActivity::class.java)
            activity.startActivityForResult(intent, QR_SCAN_REQUEST_CODE)
            logDebug("QR scanner activity launched")
        }
    }

    /**
     * Handle the result from QRScannerActivity
     * This should be called from MainActivity.onActivityResult
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == QR_SCAN_REQUEST_CODE) {
            val callbackId = pendingCallback
            pendingCallback = null

            if (callbackId != null) {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val qrData = data.getStringExtra(QRScannerActivity.EXTRA_RESULT)
                    if (qrData != null) {
                        logDebug("QR scan successful: ${qrData.take(50)}...")
                        notifySuccess(callbackId, qrData)
                    } else {
                        logWarn("QR scan result was null")
                        notifyError(callbackId, "No QR data received")
                    }
                } else {
                    logDebug("QR scan cancelled or failed")
                    notifyError(callbackId, "Scan cancelled")
                }
            }
        }
    }

    private fun notifySuccess(callbackId: String, data: String) {
        executeCallback(
            callbacksObject = "QRScannerCallbacks",
            callbackId = callbackId,
            method = "resolve",
            data = data,
            cleanup = true
        )
    }

    private fun notifyError(callbackId: String, error: String) {
        executeCallback(
            callbacksObject = "QRScannerCallbacks",
            callbackId = callbackId,
            method = "reject",
            data = error,
            cleanup = true
        )
    }

    override fun cleanup() {
        logDebug("Cleaning up QR Scanner bridge")
        pendingCallback = null
    }
}
