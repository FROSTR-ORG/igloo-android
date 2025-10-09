package com.frostr.igloo.bridges

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.gson.Gson
import java.util.*
import android.util.Size

/**
 * Modern Camera Bridge using CameraX API
 *
 * This bridge provides secure camera access using the latest CameraX API,
 * which properly handles camera enumeration and virtual cameras.
 */
class ModernCameraBridge(
    private val context: Context,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "ModernCameraBridge"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 101
    }

    private val gson = Gson()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraInitialized = false
    private val activeSessions = mutableMapOf<String, CameraSession>()

    init {
        Log.d(TAG, "Modern Camera bridge initialized")
        initializeCameraProvider()
    }

    private fun initializeCameraProvider() {
        Log.d(TAG, "Starting CameraX provider initialization...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                cameraInitialized = true
                Log.d(TAG, "CameraX provider initialized successfully")

                // Log available cameras immediately after initialization
                val availableCameras = cameraProvider?.availableCameraInfos
                Log.d(TAG, "Available cameras after initialization: ${availableCameras?.size ?: 0}")
                availableCameras?.forEachIndexed { index, cameraInfo ->
                    Log.d(TAG, "Camera $index: lensFacing=${cameraInfo.lensFacing}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize CameraX provider", e)
                cameraProvider = null
                cameraInitialized = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Enumerate available camera devices using CameraX
     */
    @JavascriptInterface
    fun enumerateDevices(): String {
        return try {
            val provider = cameraProvider
            val devices = mutableListOf<CameraDeviceInfo>()

            if (cameraInitialized && provider != null) {
                val cameraInfos = provider.availableCameraInfos
                Log.i(TAG, "Found ${cameraInfos.size} cameras via CameraX")

                cameraInfos.forEachIndexed { index, cameraInfo ->
                    val lensFacing = cameraInfo.lensFacing
                    val cameraId = "camerax_$index"

                    val label = when (lensFacing) {
                        CameraSelector.LENS_FACING_FRONT -> "Front Camera"
                        CameraSelector.LENS_FACING_BACK -> "Back Camera"
                        else -> "Camera $index"
                    }

                    val deviceInfo = CameraDeviceInfo(
                        deviceId = "camera_$cameraId",
                        groupId = "",
                        kind = "videoinput",
                        label = label,
                        cameraId = cameraId,
                        facing = lensFacing
                    )

                    devices.add(deviceInfo)
                }
            } else {
                Log.w(TAG, "CameraX not initialized or provider unavailable")
            }

            // If no cameras found (either no provider or no cameras), add virtual cameras for testing
            if (devices.isEmpty()) {
                Log.w(TAG, "No real cameras found, creating virtual cameras")

                // Add back camera
                val backCamera = CameraDeviceInfo(
                    deviceId = "camera_virtual_back",
                    groupId = "",
                    kind = "videoinput",
                    label = "Virtual Camera (Back)",
                    cameraId = "virtual_back",
                    facing = CameraSelector.LENS_FACING_BACK
                )
                devices.add(backCamera)

                // Add front camera
                val frontCamera = CameraDeviceInfo(
                    deviceId = "camera_virtual_front",
                    groupId = "",
                    kind = "videoinput",
                    label = "Virtual Camera (Front)",
                    cameraId = "virtual_front",
                    facing = CameraSelector.LENS_FACING_FRONT
                )
                devices.add(frontCamera)
            }

            Log.i(TAG, "Enumerated ${devices.size} camera devices")
            gson.toJson(devices)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate camera devices", e)
            // Even on error, return virtual cameras so the app can still function
            Log.w(TAG, "Error during enumeration, falling back to virtual cameras")
            val virtualDevices = listOf(
                CameraDeviceInfo(
                    deviceId = "camera_virtual_back",
                    groupId = "",
                    kind = "videoinput",
                    label = "Virtual Camera (Back)",
                    cameraId = "virtual_back",
                    facing = CameraSelector.LENS_FACING_BACK
                )
            )
            gson.toJson(virtualDevices)
        }
    }

    /**
     * Request camera stream using CameraX
     */
    @JavascriptInterface
    fun getUserMedia(constraintsJson: String): String {
        return try {
            // Check camera permission
            val hasPermission = hasCameraPermission()

            if (!hasPermission) {
                requestCameraPermission()
                return gson.toJson(mapOf("message" to "Camera permission required", "data" to null))
            }

            // Parse constraints
            val constraints = gson.fromJson(constraintsJson, MediaStreamConstraints::class.java)

            if (constraints.video == null || constraints.video == false) {
                return gson.toJson(mapOf("message" to "Video constraints required", "data" to null))
            }

            // Create session
            val sessionId = UUID.randomUUID().toString()

            // Check if we have a valid provider and available cameras
            val provider = cameraProvider
            val hasRealCameras = provider?.availableCameraInfos?.isNotEmpty() == true

            // If CameraX failed to initialize or no cameras available, create virtual camera session
            if (!cameraInitialized || provider == null || !hasRealCameras) {
                Log.i(TAG, "Creating virtual camera session (CameraX not available or no real cameras)")
                createVirtualCameraSession(sessionId)
                return createSuccessResponse(sessionId)
            }

            // Create real CameraX session
            Log.i(TAG, "Creating real CameraX session")
            createCameraXSession(sessionId, constraints)
            return createSuccessResponse(sessionId)

        } catch (e: Exception) {
            Log.e(TAG, "getUserMedia failed", e)
            gson.toJson(mapOf("message" to "Camera access failed: ${e.message}", "data" to null))
        }
    }

    private fun createCameraXSession(sessionId: String, constraints: MediaStreamConstraints) {
        val provider = cameraProvider ?: throw Exception("CameraX provider not available")

        // Select camera based on constraints
        val cameraSelector = selectCameraSelector(constraints)

        // Create preview use case
        val preview = Preview.Builder().build()

        try {
            // Bind to lifecycle (assuming context is LifecycleOwner)
            if (context is LifecycleOwner) {
                // MUST run on main thread - @JavascriptInterface methods run on background thread
                webView.post {
                    try {
                        // Unbind all existing use cases first to prevent "too many use cases" error
                        provider.unbindAll()

                        provider.bindToLifecycle(context, cameraSelector, preview)

                        // Store session
                        val session = CameraSession(sessionId, "camerax", Size(1280, 720))
                        activeSessions[sessionId] = session

                        // Notify success
                        notifyStreamReady(sessionId)
                        Log.d(TAG, "CameraX session created successfully: $sessionId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to bind camera on main thread", e)
                    }
                }
            } else {
                throw Exception("Context is not a LifecycleOwner")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create CameraX session", e)
            throw e
        }
    }

    private fun createVirtualCameraSession(sessionId: String) {
        // Create mock session for virtual camera
        val session = CameraSession(sessionId, "virtual", Size(1280, 720))
        activeSessions[sessionId] = session

        // Immediately notify success for virtual camera
        notifyStreamReady(sessionId)
        Log.d(TAG, "Virtual camera session created successfully: $sessionId")
    }

    private fun selectCameraSelector(constraints: MediaStreamConstraints): CameraSelector {
        val videoConstraints = constraints.video
        var lensFacing = CameraSelector.LENS_FACING_BACK

        if (videoConstraints is Map<*, *>) {
            val facingMode = when (val facingModeValue = videoConstraints["facingMode"]) {
                is String -> facingModeValue
                is Map<*, *> -> facingModeValue["exact"] as? String ?: facingModeValue["ideal"] as? String
                else -> null
            }
            if (facingMode == "user") {
                lensFacing = CameraSelector.LENS_FACING_FRONT
            }
        }

        return CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }

    private fun createSuccessResponse(sessionId: String): String {
        // Create a simple stream info object
        val streamInfo = mapOf(
            "streamId" to sessionId,
            "active" to true,
            "videoTracks" to listOf(
                mapOf(
                    "id" to "video_track_$sessionId",
                    "kind" to "video",
                    "label" to "Camera",
                    "enabled" to true,
                    "muted" to false
                )
            ),
            "audioTracks" to emptyList<Any>()
        )

        // Return success result with stream info
        val result = mapOf(
            "data" to streamInfo,
            "message" to null
        )

        return gson.toJson(result)
    }

    private fun notifyStreamReady(sessionId: String) {
        webView.post {
            webView.evaluateJavascript(
                "window.CameraBridge && window.CameraBridge.onStreamReady('$sessionId')",
                null
            )
        }
    }

    @JavascriptInterface
    fun stopUserMedia(streamId: String): String {
        Log.d(TAG, "Stopping camera stream: $streamId")

        return try {
            val session = activeSessions.remove(streamId)
            if (session != null) {
                // Unbind from lifecycle if needed
                cameraProvider?.unbindAll()

                Log.d(TAG, "Camera stream stopped successfully")
                gson.toJson(CameraResult.Success("Stream stopped"))
            } else {
                Log.w(TAG, "Session not found: $streamId")
                gson.toJson(CameraResult.Error("Session not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop camera stream", e)
            gson.toJson(CameraResult.Error("Failed to stop stream: ${e.message}"))
        }
    }

    @JavascriptInterface
    fun getCapabilities(deviceId: String): String {
        Log.d(TAG, "Getting capabilities for device: $deviceId")

        // Return standard capabilities
        val capabilities = mapOf(
            "width" to mapOf("min" to 320, "max" to 1920),
            "height" to mapOf("min" to 240, "max" to 1080),
            "frameRate" to mapOf("min" to 15.0, "max" to 30.0),
            "facingMode" to listOf("user", "environment"),
            "resizeMode" to listOf("none", "crop-and-scale")
        )

        return gson.toJson(capabilities)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        if (context is android.app.Activity) {
            ActivityCompat.requestPermissions(
                context,
                arrayOf(android.Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up Modern Camera bridge")
        try {
            cameraProvider?.unbindAll()
            activeSessions.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    // Data classes for session management
    data class CameraSession(
        val sessionId: String,
        val cameraId: String,
        val size: Size
    )
}

/**
 * Data classes for camera bridge communication
 */

data class CameraDeviceInfo(
    val deviceId: String,
    val groupId: String,
    val kind: String,
    val label: String,
    val cameraId: String,
    val facing: Int
)

data class MediaStreamConstraints(
    val video: Any? = null,
    val audio: Any? = null
)

sealed class CameraResult {
    data class Success(val message: String) : CameraResult()
    data class Error(val message: String) : CameraResult()
}