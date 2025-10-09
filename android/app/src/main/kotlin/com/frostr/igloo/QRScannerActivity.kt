package com.frostr.igloo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen QR Scanner Activity
 *
 * Displays a camera preview and scans for QR codes using ML Kit.
 * Returns the scanned data back to the caller.
 */
class QRScannerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "QRScannerActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 101
        const val EXTRA_RESULT = "qr_result"
    }

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private var hasScanned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create UI programmatically
        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Camera preview
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(previewView)

        // Status text overlay
        statusText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.topMargin = 100
                it.leftMargin = 50
                it.rightMargin = 50
            }
            text = "Point camera at QR code"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x88000000.toInt())
            setPadding(20, 20, 20, 20)
        }
        rootLayout.addView(statusText)

        setContentView(rootLayout)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check camera permission
        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                statusText.text = "Camera permission denied"
                Log.e(TAG, "Camera permission denied")
                finishWithError("Camera permission denied")
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraPreview()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                finishWithError("Failed to initialize camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraPreview() {
        val provider = cameraProvider ?: return

        try {
            // Unbind all use cases
            provider.unbindAll()

            // Create preview use case
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            // Create image analysis use case for barcode scanning
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val scanner = BarcodeScanning.getClient()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (!hasScanned) {
                    processImageProxy(imageProxy, scanner)
                } else {
                    imageProxy.close()
                }
            }

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Bind use cases to lifecycle
            provider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )

            Log.d(TAG, "Camera preview bound successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera preview", e)
            finishWithError("Failed to start camera: ${e.message}")
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner) {
        val mediaImage = imageProxy.image

        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty() && !hasScanned) {
                        val barcode = barcodes[0]
                        val rawValue = barcode.rawValue

                        if (rawValue != null) {
                            hasScanned = true
                            Log.d(TAG, "QR code scanned: ${rawValue.take(50)}...")
                            finishWithSuccess(rawValue)
                        }
                    }
                    imageProxy.close()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scanning failed", e)
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun finishWithSuccess(data: String) {
        runOnUiThread {
            statusText.text = "QR code scanned!"
            val resultIntent = Intent().apply {
                putExtra(EXTRA_RESULT, data)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun finishWithError(error: String) {
        runOnUiThread {
            statusText.text = "Error: $error"
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
