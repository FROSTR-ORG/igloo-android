package com.frostr.igloo

/**
 * NIP-55 Data Models
 *
 * Shared data classes for NIP-55 request/response handling
 */

/**
 * NIP-55 Request from external apps
 */
data class NIP55Request(
    val id: String,
    val type: String,
    val params: Map<String, String> = emptyMap(),
    val callingApp: String,
    val replyIntent: android.app.PendingIntent? = null
)

/**
 * NIP-55 Result to send back to external apps
 */
data class NIP55Result(
    val ok: Boolean,
    val type: String,
    val result: String? = null,
    val error: String? = null
)
