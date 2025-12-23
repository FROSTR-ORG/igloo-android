package com.frostr.igloo.bridges.interfaces

/**
 * Interface for unified signing bridge operations.
 *
 * Enables testability by allowing mock implementations of the signing layer.
 * Implementations handle both internal PWA signing requests and external NIP-55
 * intents through the same permission and signing system.
 */
interface ISigningBridge {

    /**
     * Handle signing request from PWA JavaScript.
     * @param eventJson JSON representation of the event to sign
     * @param callbackId Callback ID for async response
     * @return JSON response with requestId and status
     */
    fun signEvent(eventJson: String, callbackId: String): String

    /**
     * Get public key from PWA JavaScript.
     * @param callbackId Callback ID for async response
     * @return JSON response with requestId and status
     */
    fun getPublicKey(callbackId: String): String

    /**
     * NIP-04 encryption from PWA JavaScript.
     * @param plaintext Text to encrypt
     * @param pubkey Public key of recipient
     * @param callbackId Callback ID for async response
     * @return JSON response with requestId and status
     */
    fun nip04Encrypt(plaintext: String, pubkey: String, callbackId: String): String

    /**
     * NIP-04 decryption from PWA JavaScript.
     * @param ciphertext Encrypted text
     * @param pubkey Public key of sender
     * @param callbackId Callback ID for async response
     * @return JSON response with requestId and status
     */
    fun nip04Decrypt(ciphertext: String, pubkey: String, callbackId: String): String

    /**
     * Handle NIP-55 result directly from PWA Promise.
     * @param resultJson JSON with ok, result, reason, and id fields
     * @return JSON response with status
     */
    fun handleNIP55Result(resultJson: String): String

    /**
     * Handle user response to a pending signing request.
     * @param requestId Request ID
     * @param approved Whether user approved
     * @param signature Optional signature if pre-approved
     * @return JSON response with status
     */
    fun approveSigningRequest(requestId: String, approved: Boolean, signature: String?): String

    /**
     * Handle user response from PWA JavaScript.
     * @param requestId Request ID
     * @param approved Whether user approved
     * @param result Optional result data
     * @return JSON response with status
     */
    fun handleUserResponse(requestId: String, approved: Boolean, result: String?): String

    /**
     * Get pending signing requests for UI display.
     * @return JSON array of pending requests
     */
    fun getPendingRequests(): String

    /**
     * Cancel a pending signing request.
     * @param requestId Request ID to cancel
     * @return JSON response with status
     */
    fun cancelSigningRequest(requestId: String): String

    /**
     * Clean up resources.
     * Should be called when the bridge is being destroyed.
     */
    fun cleanup()
}
