package com.frostr.igloo.bridges

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.frostr.igloo.QRScannerActivity

/**
 * QR Scanner Bridge - Native Android QR/Barcode Scanning
 *
 * Launches a full-screen camera activity to scan QR codes.
 * Uses bundled ML Kit - no dynamic downloads required.
 */
class QRScannerBridge(
    private val activity: Activity,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "QRScannerBridge"
        const val QR_SCAN_REQUEST_CODE = 1001
    }

    private var pendingCallback: String? = null

    init {
        Log.d(TAG, "QR Scanner bridge initialized")
    }

    /**
     * Scan a QR code using the native Android camera
     * Returns a promise-like callback ID that will be resolved with the result
     */
    @JavascriptInterface
    fun scanQRCode(callbackId: String) {
        Log.d(TAG, "QR scan requested with callback: $callbackId")

        // Store callback for when scan completes
        pendingCallback = callbackId

        // Launch QR scanner activity on main thread
        activity.runOnUiThread {
            val intent = Intent(activity, QRScannerActivity::class.java)
            activity.startActivityForResult(intent, QR_SCAN_REQUEST_CODE)
            Log.d(TAG, "QR scanner activity launched")
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
                        Log.d(TAG, "QR scan successful: ${qrData.take(50)}...")
                        notifySuccess(callbackId, qrData)
                    } else {
                        Log.w(TAG, "QR scan result was null")
                        notifyError(callbackId, "No QR data received")
                    }
                } else {
                    Log.d(TAG, "QR scan cancelled or failed")
                    notifyError(callbackId, "Scan cancelled")
                }
            }
        }
    }

    private fun notifySuccess(callbackId: String, data: String) {
        webView.post {
            // Escape the data for safe JavaScript injection
            val escapedData = data
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")

            val script = """
                if (window.QRScannerCallbacks && window.QRScannerCallbacks['$callbackId']) {
                    window.QRScannerCallbacks['$callbackId'].resolve('$escapedData');
                    delete window.QRScannerCallbacks['$callbackId'];
                }
            """.trimIndent()

            webView.evaluateJavascript(script, null)
        }
    }

    private fun notifyError(callbackId: String, error: String) {
        webView.post {
            val escapedError = error
                .replace("\\", "\\\\")
                .replace("'", "\\'")

            val script = """
                if (window.QRScannerCallbacks && window.QRScannerCallbacks['$callbackId']) {
                    window.QRScannerCallbacks['$callbackId'].reject('$escapedError');
                    delete window.QRScannerCallbacks['$callbackId'];
                }
            """.trimIndent()

            webView.evaluateJavascript(script, null)
        }
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up QR Scanner bridge")
        pendingCallback = null
    }
}
