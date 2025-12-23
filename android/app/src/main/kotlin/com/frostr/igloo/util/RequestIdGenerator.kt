package com.frostr.igloo.util

/**
 * Utility for generating unique NIP-55 request IDs.
 *
 * Format: nip55_{timestamp}_{random_suffix}
 * - timestamp: System.currentTimeMillis() for ordering
 * - random_suffix: 4-digit random number (1000-9999) for uniqueness
 *
 * The first 8 characters of the request ID serve as a trace ID
 * for correlating logs across the NIP-55 pipeline.
 */
object RequestIdGenerator {

    private const val PREFIX = "nip55"
    private val SUFFIX_RANGE = 1000..9999

    /**
     * Generate a unique request ID for NIP-55 operations.
     *
     * @return A unique request ID in format "nip55_{timestamp}_{suffix}"
     */
    fun generate(): String {
        val timestamp = System.currentTimeMillis()
        val suffix = SUFFIX_RANGE.random()
        return "${PREFIX}_${timestamp}_$suffix"
    }

    /**
     * Extract the trace ID (first 8 characters) from a request ID.
     *
     * @param requestId The full request ID
     * @return The trace ID for logging correlation
     */
    fun extractTraceId(requestId: String): String {
        return requestId.take(8)
    }
}
