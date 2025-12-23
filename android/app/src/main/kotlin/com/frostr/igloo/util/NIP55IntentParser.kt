package com.frostr.igloo.util

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Parses NIP-55 nostrsigner: URIs into structured requests.
 *
 * Centralizes the parsing logic that was duplicated in InvisibleNIP55Handler.
 *
 * NIP-55 URI format:
 * - nostrsigner:<payload>?type=<operation>&pubkey=<hex>
 * - Intent extras: type, pubkey, id, current_user, permissions
 */
object NIP55IntentParser {

    private const val TAG = "NIP55IntentParser"
    private const val SCHEME = "nostrsigner"

    private val gson = Gson()

    /**
     * Valid NIP-55 operation types
     */
    val VALID_TYPES = setOf(
        "get_public_key",
        "sign_event",
        "nip04_encrypt",
        "nip04_decrypt",
        "nip44_encrypt",
        "nip44_decrypt",
        "decrypt_zap_event"
    )

    /**
     * Parse a NIP-55 intent into a ParsedNIP55Request.
     *
     * @param intent The intent containing the nostrsigner: URI
     * @param callerPackage The package name of the calling app (if known)
     * @return ParsedNIP55Request with all extracted data
     * @throws NIP55ParseException if parsing fails
     */
    fun parse(intent: Intent, callerPackage: String? = null): ParsedNIP55Request {
        val uri = intent.data
            ?: throw NIP55ParseException("Intent data is null - no URI provided")

        if (uri.scheme != SCHEME) {
            throw NIP55ParseException("Invalid URI scheme: '${uri.scheme}' (expected '$SCHEME')")
        }

        val type = intent.getStringExtra("type")
            ?: throw NIP55ParseException("Missing required 'type' parameter")

        if (!isValidType(type)) {
            throw NIP55ParseException("Unsupported NIP-55 type: '$type'")
        }

        val requestId = intent.getStringExtra("id") ?: RequestIdGenerator.generate()
        val callingApp = callerPackage
            ?: intent.getStringExtra("calling_package")
            ?: "unknown"

        val params = parseParams(intent, uri, type)
        validateRequiredParams(type, params)

        return ParsedNIP55Request(
            id = requestId,
            type = type,
            params = params,
            callingApp = callingApp,
            uri = uri,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Parse just the URI (for content provider queries).
     *
     * @param uri The nostrsigner: URI
     * @param type The operation type
     * @return Parsed parameters map
     */
    fun parseUri(uri: Uri, type: String): Map<String, String> {
        if (uri.scheme != SCHEME) {
            throw NIP55ParseException("Invalid URI scheme: '${uri.scheme}'")
        }

        if (!isValidType(type)) {
            throw NIP55ParseException("Unsupported NIP-55 type: '$type'")
        }

        return parseUriContent(uri, type)
    }

    /**
     * Check if a type is a valid NIP-55 operation type.
     */
    fun isValidType(type: String): Boolean = type in VALID_TYPES

    /**
     * Validate that a public key is in the correct hex format.
     *
     * @param pubkey The public key to validate
     * @return true if valid, false otherwise
     */
    fun isValidPublicKey(pubkey: String): Boolean {
        return pubkey.length == 64 && pubkey.matches(Regex("^[0-9a-fA-F]+$"))
    }

    /**
     * Validate public key and throw if invalid.
     *
     * @param pubkey The public key to validate
     * @throws NIP55ParseException if invalid
     */
    fun validatePublicKey(pubkey: String) {
        if (!isValidPublicKey(pubkey)) {
            throw NIP55ParseException("Invalid public key format: expected 64 hex characters")
        }
    }

    /**
     * Parse parameters from intent and URI based on type.
     */
    private fun parseParams(intent: Intent, uri: Uri, type: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        // Add common parameters
        intent.getStringExtra("current_user")?.let { params["current_user"] = it }

        // Add type-specific parameters
        when (type) {
            "get_public_key" -> {
                intent.getStringExtra("permissions")?.let { params["permissions"] = it }
            }

            "sign_event" -> {
                parseJsonContent(uri)?.let { params["event"] = it }
            }

            "nip04_encrypt", "nip44_encrypt" -> {
                uri.schemeSpecificPart?.let { params["plaintext"] = it }
                parsePubkey(intent)?.let { params["pubkey"] = it }
            }

            "nip04_decrypt", "nip44_decrypt" -> {
                uri.schemeSpecificPart?.let { params["ciphertext"] = it }
                parsePubkey(intent)?.let { params["pubkey"] = it }
            }

            "decrypt_zap_event" -> {
                parseJsonContent(uri)?.let { params["event"] = it }
            }
        }

        return params
    }

    /**
     * Parse just URI content (for content provider queries).
     */
    private fun parseUriContent(uri: Uri, type: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        when (type) {
            "sign_event", "decrypt_zap_event" -> {
                parseJsonContent(uri)?.let { params["event"] = it }
            }
            "nip04_encrypt", "nip44_encrypt" -> {
                uri.schemeSpecificPart?.let { params["plaintext"] = it }
            }
            "nip04_decrypt", "nip44_decrypt" -> {
                uri.schemeSpecificPart?.let { params["ciphertext"] = it }
            }
        }

        return params
    }

    /**
     * Parse and validate JSON content from URI.
     */
    private fun parseJsonContent(uri: Uri): String? {
        val content = uri.schemeSpecificPart
        if (content.isNullOrEmpty()) return null

        return try {
            // Validate it's valid JSON
            gson.fromJson(content, Map::class.java)
            content
        } catch (e: JsonSyntaxException) {
            throw NIP55ParseException("Invalid JSON in URI data: ${e.message}")
        }
    }

    /**
     * Parse public key from intent (handles both "pubkey" and "pubKey" extras).
     */
    private fun parsePubkey(intent: Intent): String? {
        val pubkey = intent.getStringExtra("pubkey") ?: intent.getStringExtra("pubKey")
        if (pubkey != null) {
            validatePublicKey(pubkey)
        }
        return pubkey
    }

    /**
     * Validate that required parameters are present.
     */
    private fun validateRequiredParams(type: String, params: Map<String, String>) {
        when (type) {
            "sign_event" -> {
                if (!params.containsKey("event")) {
                    throw NIP55ParseException("Missing required 'event' parameter")
                }
            }
            "nip04_encrypt", "nip44_encrypt" -> {
                if (!params.containsKey("pubkey") || !params.containsKey("plaintext")) {
                    throw NIP55ParseException("Missing required parameters for $type (pubkey and plaintext)")
                }
            }
            "nip04_decrypt", "nip44_decrypt" -> {
                if (!params.containsKey("pubkey") || !params.containsKey("ciphertext")) {
                    throw NIP55ParseException("Missing required parameters for $type (pubkey and ciphertext)")
                }
            }
            "decrypt_zap_event" -> {
                if (!params.containsKey("event")) {
                    throw NIP55ParseException("Missing required 'event' parameter")
                }
            }
            // get_public_key has no required params
        }
    }
}

/**
 * Parsed NIP-55 request data.
 */
data class ParsedNIP55Request(
    val id: String,
    val type: String,
    val params: Map<String, String>,
    val callingApp: String,
    val uri: Uri,
    val timestamp: Long
)

/**
 * Exception thrown when NIP-55 parsing fails.
 */
class NIP55ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
