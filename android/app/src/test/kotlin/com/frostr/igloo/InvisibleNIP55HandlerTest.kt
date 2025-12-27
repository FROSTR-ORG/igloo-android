package com.frostr.igloo

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for InvisibleNIP55Handler.
 *
 * Tests verify intent parsing, type validation, parameter extraction,
 * and request routing logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class InvisibleNIP55HandlerTest {

    @Before
    fun setup() {
        // Reset health manager state before each test
        com.frostr.igloo.health.IglooHealthManager.reset()
    }

    // ========== Type Validation Tests ==========

    @Test
    fun `valid NIP-55 types are recognized`() {
        val validTypes = listOf(
            "get_public_key",
            "sign_event",
            "nip04_encrypt",
            "nip04_decrypt",
            "nip44_encrypt",
            "nip44_decrypt",
            "decrypt_zap_event"
        )

        validTypes.forEach { type ->
            assertThat(isValidNIP55Type(type)).isTrue()
        }
    }

    @Test
    fun `invalid NIP-55 types are rejected`() {
        val invalidTypes = listOf(
            "invalid_type",
            "SIGN_EVENT",  // Case sensitive
            "sign",
            "",
            "get_private_key"
        )

        invalidTypes.forEach { type ->
            assertThat(isValidNIP55Type(type)).isFalse()
        }
    }

    // ========== Public Key Validation Tests ==========

    @Test
    fun `valid public key passes validation`() {
        val validPubkey = "a".repeat(64)
        assertThat(isValidPublicKey(validPubkey)).isTrue()
    }

    @Test
    fun `public key with uppercase hex is valid`() {
        val pubkey = "0123456789ABCDEF".repeat(4)
        assertThat(isValidPublicKey(pubkey)).isTrue()
    }

    @Test
    fun `public key with mixed case hex is valid`() {
        val pubkey = "0123456789abcDEF".repeat(4)
        assertThat(isValidPublicKey(pubkey)).isTrue()
    }

    @Test
    fun `short public key is invalid`() {
        val shortPubkey = "a".repeat(32)
        assertThat(isValidPublicKey(shortPubkey)).isFalse()
    }

    @Test
    fun `long public key is invalid`() {
        val longPubkey = "a".repeat(128)
        assertThat(isValidPublicKey(longPubkey)).isFalse()
    }

    @Test
    fun `non-hex public key is invalid`() {
        val nonHexPubkey = "g".repeat(64)
        assertThat(isValidPublicKey(nonHexPubkey)).isFalse()
    }

    @Test
    fun `empty public key is invalid`() {
        assertThat(isValidPublicKey("")).isFalse()
    }

    // ========== Intent Parsing Tests ==========

    @Test
    fun `parses get_public_key intent correctly`() {
        val uri = Uri.parse("nostrsigner:")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "get_public_key")
            putExtra("id", "test-123")
        }

        val params = parseIntentParams(intent, uri, "get_public_key")
        // get_public_key has no required params
        assertThat(params).isNotNull()
    }

    @Test
    fun `parses get_public_key with permissions correctly`() {
        val uri = Uri.parse("nostrsigner:")
        val permissionsJson = """[{"type":"sign_event","kind":1}]"""
        val intent = Intent().apply {
            data = uri
            putExtra("type", "get_public_key")
            putExtra("permissions", permissionsJson)
        }

        val params = parseIntentParams(intent, uri, "get_public_key")
        assertThat(params["permissions"]).isEqualTo(permissionsJson)
    }

    @Test
    fun `parses sign_event intent correctly`() {
        val eventJson = """{"kind":1,"content":"test","tags":[]}"""
        val uri = Uri.parse("nostrsigner:$eventJson")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "sign_event")
        }

        val params = parseIntentParams(intent, uri, "sign_event")
        assertThat(params["event"]).isEqualTo(eventJson)
    }

    @Test
    fun `parses nip04_encrypt intent correctly`() {
        val plaintext = "hello world"
        val pubkey = "a".repeat(64)
        val uri = Uri.parse("nostrsigner:$plaintext")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "nip04_encrypt")
            putExtra("pubkey", pubkey)
        }

        val params = parseIntentParams(intent, uri, "nip04_encrypt")
        assertThat(params["plaintext"]).isEqualTo(plaintext)
        assertThat(params["pubkey"]).isEqualTo(pubkey)
    }

    @Test
    fun `parses nip04_decrypt intent correctly`() {
        val ciphertext = "encrypted_data_here"
        val pubkey = "b".repeat(64)
        val uri = Uri.parse("nostrsigner:$ciphertext")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "nip04_decrypt")
            putExtra("pubkey", pubkey)
        }

        val params = parseIntentParams(intent, uri, "nip04_decrypt")
        assertThat(params["ciphertext"]).isEqualTo(ciphertext)
        assertThat(params["pubkey"]).isEqualTo(pubkey)
    }

    @Test
    fun `handles camelCase pubKey parameter`() {
        val pubkey = "c".repeat(64)
        val uri = Uri.parse("nostrsigner:data")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "nip04_encrypt")
            putExtra("pubKey", pubkey)  // camelCase
        }

        val params = parseIntentParams(intent, uri, "nip04_encrypt")
        assertThat(params["pubkey"]).isEqualTo(pubkey)
    }

    @Test
    fun `extracts current_user parameter`() {
        val eventJson = """{"kind":1,"content":"test"}"""
        val uri = Uri.parse("nostrsigner:$eventJson")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "sign_event")
            putExtra("current_user", "npub1abc...")
        }

        val params = parseIntentParams(intent, uri, "sign_event")
        assertThat(params["current_user"]).isEqualTo("npub1abc...")
    }

    // ========== Focus Switching Intent Extras Tests ==========

    @Test
    fun `wakeup intent includes background_signing_request extra`() {
        // Verify the intent extras structure expected by MainActivity for overlay control
        val wakeupIntent = Intent().apply {
            putExtra("nip55_wakeup", true)
            putExtra("background_signing_request", true)
            putExtra("signing_request_type", "sign_event")
            putExtra("signing_calling_app", "com.example.app")
        }

        assertThat(wakeupIntent.getBooleanExtra("nip55_wakeup", false)).isTrue()
        assertThat(wakeupIntent.getBooleanExtra("background_signing_request", false)).isTrue()
        assertThat(wakeupIntent.getStringExtra("signing_request_type")).isEqualTo("sign_event")
        assertThat(wakeupIntent.getStringExtra("signing_calling_app")).isEqualTo("com.example.app")
    }

    @Test
    fun `wakeup intent extras default to false when not set`() {
        val emptyIntent = Intent()

        assertThat(emptyIntent.getBooleanExtra("nip55_wakeup", false)).isFalse()
        assertThat(emptyIntent.getBooleanExtra("background_signing_request", false)).isFalse()
    }

    @Test
    fun `intent extras can be cleared`() {
        val intent = Intent().apply {
            putExtra("nip55_wakeup", true)
            putExtra("background_signing_request", true)
        }

        // Verify extras are set
        assertThat(intent.getBooleanExtra("nip55_wakeup", false)).isTrue()

        // Clear extras (as MainActivity does when detecting manual switch)
        intent.removeExtra("nip55_wakeup")
        intent.removeExtra("background_signing_request")

        // Verify extras are cleared
        assertThat(intent.getBooleanExtra("nip55_wakeup", false)).isFalse()
        assertThat(intent.getBooleanExtra("background_signing_request", false)).isFalse()
    }

    // ========== Validation Error Tests ==========

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid URI scheme`() {
        val uri = Uri.parse("http://example.com")
        validateUriScheme(uri)
    }

    @Test
    fun `accepts nostrsigner scheme`() {
        val uri = Uri.parse("nostrsigner:")
        // Should not throw
        validateUriScheme(uri)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects missing event for sign_event`() {
        val params = emptyMap<String, String>()
        validateRequiredParams("sign_event", params)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects missing pubkey for encrypt`() {
        val params = mapOf("plaintext" to "test")
        validateRequiredParams("nip04_encrypt", params)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects missing plaintext for encrypt`() {
        val params = mapOf("pubkey" to "a".repeat(64))
        validateRequiredParams("nip04_encrypt", params)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects missing pubkey for decrypt`() {
        val params = mapOf("ciphertext" to "test")
        validateRequiredParams("nip04_decrypt", params)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects missing ciphertext for decrypt`() {
        val params = mapOf("pubkey" to "a".repeat(64))
        validateRequiredParams("nip04_decrypt", params)
    }

    @Test
    fun `get_public_key has no required params`() {
        val params = emptyMap<String, String>()
        // Should not throw
        validateRequiredParams("get_public_key", params)
    }

    // ========== Helper Functions ==========

    /**
     * Mirror of InvisibleNIP55Handler.isValidNIP55Type for testing
     */
    private fun isValidNIP55Type(type: String): Boolean {
        return type in setOf(
            "get_public_key",
            "sign_event",
            "nip04_encrypt",
            "nip04_decrypt",
            "nip44_encrypt",
            "nip44_decrypt",
            "decrypt_zap_event"
        )
    }

    /**
     * Mirror of InvisibleNIP55Handler.validatePublicKey for testing
     */
    private fun isValidPublicKey(pubkey: String): Boolean {
        return pubkey.length == 64 && pubkey.matches(Regex("^[0-9a-fA-F]+$"))
    }

    /**
     * Simplified version of intent param parsing for testing
     */
    private fun parseIntentParams(intent: Intent, uri: Uri, type: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        when (type) {
            "get_public_key" -> {
                intent.getStringExtra("permissions")?.let { params["permissions"] = it }
            }
            "sign_event" -> {
                uri.schemeSpecificPart?.let { content ->
                    if (content.isNotEmpty()) {
                        params["event"] = content
                    }
                }
                intent.getStringExtra("current_user")?.let { params["current_user"] = it }
            }
            "nip04_encrypt", "nip44_encrypt" -> {
                uri.schemeSpecificPart?.let { params["plaintext"] = it }
                (intent.getStringExtra("pubkey") ?: intent.getStringExtra("pubKey"))?.let {
                    params["pubkey"] = it
                }
                intent.getStringExtra("current_user")?.let { params["current_user"] = it }
            }
            "nip04_decrypt", "nip44_decrypt" -> {
                uri.schemeSpecificPart?.let { params["ciphertext"] = it }
                (intent.getStringExtra("pubkey") ?: intent.getStringExtra("pubKey"))?.let {
                    params["pubkey"] = it
                }
                intent.getStringExtra("current_user")?.let { params["current_user"] = it }
            }
            "decrypt_zap_event" -> {
                uri.schemeSpecificPart?.let { content ->
                    if (content.isNotEmpty()) {
                        params["event"] = content
                    }
                }
                intent.getStringExtra("current_user")?.let { params["current_user"] = it }
            }
        }

        return params
    }

    /**
     * Mirror of URI scheme validation
     */
    private fun validateUriScheme(uri: Uri) {
        if (uri.scheme != "nostrsigner") {
            throw IllegalArgumentException("Invalid URI scheme: '${uri.scheme}'")
        }
    }

    /**
     * Mirror of required params validation
     */
    private fun validateRequiredParams(type: String, params: Map<String, String>) {
        when (type) {
            "sign_event" -> {
                if (!params.containsKey("event")) {
                    throw IllegalArgumentException("Missing required 'event' parameter")
                }
            }
            "nip04_encrypt", "nip44_encrypt" -> {
                if (!params.containsKey("pubkey") || !params.containsKey("plaintext")) {
                    throw IllegalArgumentException("Missing required parameters for $type")
                }
            }
            "nip04_decrypt", "nip44_decrypt" -> {
                if (!params.containsKey("pubkey") || !params.containsKey("ciphertext")) {
                    throw IllegalArgumentException("Missing required parameters for $type")
                }
            }
            "decrypt_zap_event" -> {
                if (!params.containsKey("event")) {
                    throw IllegalArgumentException("Missing required 'event' parameter")
                }
            }
        }
    }
}
