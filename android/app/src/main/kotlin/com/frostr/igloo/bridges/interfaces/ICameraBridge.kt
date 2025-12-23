package com.frostr.igloo.bridges.interfaces

/**
 * Interface for camera bridge operations.
 *
 * Enables testability by allowing mock implementations of the camera layer.
 * Implementations provide secure camera access using CameraX API,
 * which properly handles camera enumeration and virtual cameras.
 */
interface ICameraBridge {

    /**
     * Enumerate available camera devices.
     * @return JSON array of camera device info
     */
    fun enumerateDevices(): String

    /**
     * Request camera stream.
     * @param constraintsJson JSON with video/audio constraints
     * @return JSON response with stream info or error
     */
    fun getUserMedia(constraintsJson: String): String

    /**
     * Stop camera stream.
     * @param streamId Stream ID to stop
     * @return JSON response with success/error status
     */
    fun stopUserMedia(streamId: String): String

    /**
     * Get camera capabilities.
     * @param deviceId Device ID to query
     * @return JSON object with capability ranges
     */
    fun getCapabilities(deviceId: String): String

    /**
     * Clean up camera resources.
     * Should be called when the bridge is being destroyed.
     */
    fun cleanup()
}
