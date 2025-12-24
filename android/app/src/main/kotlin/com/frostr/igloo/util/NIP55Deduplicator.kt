package com.frostr.igloo.util

import android.util.Log
import com.google.gson.Gson

/**
 * Shared deduplication utility for NIP-55 requests
 *
 * Used by both InvisibleNIP55Handler (Intent-based) and NIP55ContentProvider (ContentResolver-based)
 * to prevent duplicate requests from being processed.
 *
 * Deduplication strategies by operation type:
 * - sign_event: callingApp + type + event ID (from event JSON)
 * - nip04_decrypt/nip44_decrypt: callingApp + type + ciphertext.hashCode() + pubkey
 * - nip04_encrypt/nip44_encrypt: callingApp + type + plaintext.hashCode() + pubkey
 * - decrypt_zap_event: callingApp + type + event.hashCode()
 * - get_public_key: callingApp + type
 */
object NIP55Deduplicator {

    private const val TAG = "NIP55Deduplicator"
    private val gson = Gson()

    /**
     * Extract a tag value from an event map.
     * Tags are in the format: [["tagName", "value"], ...]
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractTagValue(eventMap: Map<String, Any>, tagName: String): String? {
        val tags = eventMap["tags"] as? List<List<Any>> ?: return null
        for (tag in tags) {
            if (tag.size >= 2 && tag[0] == tagName) {
                return tag[1].toString()
            }
        }
        return null
    }

    /**
     * Generate a deduplication key from a map of parameters (used by InvisibleNIP55Handler)
     *
     * @param callingApp The package name of the calling application
     * @param operationType The NIP-55 operation type (sign_event, nip04_encrypt, etc.)
     * @param params Map of parameters from the request
     * @param fallbackId Fallback identifier if key generation fails
     */
    fun getDeduplicationKey(
        callingApp: String,
        operationType: String,
        params: Map<String, String?>,
        fallbackId: String
    ): String {
        return try {
            when (operationType) {
                "sign_event" -> {
                    // Deduplicate by event ID, with special handling for kind 22242 (relay auth)
                    val eventJson = params["event"]
                    if (eventJson != null) {
                        val eventMap = gson.fromJson<Map<String, Any>>(eventJson, Map::class.java)
                        val kind = (eventMap["kind"] as? Number)?.toInt()

                        if (kind == 22242) {
                            // Kind 22242 = NIP-42 relay auth
                            // Deduplicate by challenge tag instead of event ID
                            // because Amethyst sends the same challenge with different event IDs
                            val challenge = extractTagValue(eventMap, "challenge")
                            val relay = extractTagValue(eventMap, "relay")
                            val key = "$callingApp:$operationType:auth:${relay ?: ""}:${challenge ?: eventJson.hashCode()}"
                            Log.d(TAG, "Kind 22242 dedup key: $key (challenge=$challenge, relay=$relay)")
                            key
                        } else {
                            // Normal events: deduplicate by event ID
                            val eventId = eventMap["id"] as? String
                            "$callingApp:$operationType:${eventId ?: eventJson.hashCode()}"
                        }
                    } else {
                        "$callingApp:$operationType:$fallbackId"
                    }
                }

                "nip04_decrypt", "nip44_decrypt" -> {
                    // Deduplicate by ciphertext + pubkey
                    val ciphertext = params["ciphertext"]
                    val pubkey = params["pubkey"]
                    "$callingApp:$operationType:${ciphertext?.hashCode()}:$pubkey"
                }

                "nip04_encrypt", "nip44_encrypt" -> {
                    // Deduplicate by plaintext + pubkey
                    val plaintext = params["plaintext"]
                    val pubkey = params["pubkey"]
                    "$callingApp:$operationType:${plaintext?.hashCode()}:$pubkey"
                }

                "decrypt_zap_event" -> {
                    // Deduplicate by event JSON
                    val eventJson = params["event"]
                    "$callingApp:$operationType:${eventJson?.hashCode()}"
                }

                "get_public_key" -> {
                    // Deduplicate by calling app + type only
                    "$callingApp:$operationType"
                }

                else -> {
                    // Fallback: use provided fallback ID
                    "$callingApp:$operationType:$fallbackId"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate deduplication key, using fallback", e)
            "$callingApp:$operationType:$fallbackId"
        }
    }

    /**
     * Generate a deduplication key from an array of arguments (used by NIP55ContentProvider)
     *
     * @param callingPackage The package name of the calling application
     * @param operationType The NIP-55 operation type (sign_event, nip04_encrypt, etc.)
     * @param args Array of arguments from the ContentProvider query
     */
    fun getDeduplicationKey(
        callingPackage: String,
        operationType: String,
        args: Array<String>
    ): String {
        return try {
            when (operationType) {
                "sign_event" -> {
                    // Deduplicate by event ID, with special handling for kind 22242 (relay auth)
                    val eventJson = args.getOrNull(0)
                    if (eventJson != null) {
                        @Suppress("UNCHECKED_CAST")
                        val eventMap = gson.fromJson(eventJson, Map::class.java) as Map<String, Any>
                        val kind = (eventMap["kind"] as? Number)?.toInt()

                        if (kind == 22242) {
                            // Kind 22242 = NIP-42 relay auth
                            // Deduplicate by challenge tag instead of event ID
                            val challenge = extractTagValue(eventMap, "challenge")
                            val relay = extractTagValue(eventMap, "relay")
                            "$callingPackage:$operationType:auth:${relay ?: ""}:${challenge ?: eventJson.hashCode()}"
                        } else {
                            val eventId = eventMap["id"]?.toString()
                            "$callingPackage:$operationType:${eventId ?: eventJson.hashCode()}"
                        }
                    } else {
                        "$callingPackage:$operationType:${System.currentTimeMillis()}"
                    }
                }

                "nip04_decrypt", "nip44_decrypt" -> {
                    // Deduplicate by ciphertext + pubkey
                    val ciphertext = args.getOrNull(0)
                    val pubkey = args.getOrNull(1)
                    "$callingPackage:$operationType:${ciphertext?.hashCode()}:$pubkey"
                }

                "nip04_encrypt", "nip44_encrypt" -> {
                    // Deduplicate by plaintext + pubkey
                    val plaintext = args.getOrNull(0)
                    val pubkey = args.getOrNull(1)
                    "$callingPackage:$operationType:${plaintext?.hashCode()}:$pubkey"
                }

                "decrypt_zap_event" -> {
                    // Deduplicate by event JSON
                    val eventJson = args.getOrNull(0)
                    "$callingPackage:$operationType:${eventJson?.hashCode()}"
                }

                "get_public_key" -> {
                    // Deduplicate by calling app + type only
                    "$callingPackage:$operationType"
                }

                else -> {
                    // Fallback: use timestamp to prevent deduplication for unknown types
                    "$callingPackage:$operationType:${System.currentTimeMillis()}"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate deduplication key, using timestamp", e)
            "$callingPackage:$operationType:${System.currentTimeMillis()}"
        }
    }
}
