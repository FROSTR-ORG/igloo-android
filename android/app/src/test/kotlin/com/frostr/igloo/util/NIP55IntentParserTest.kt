package com.frostr.igloo.util

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for NIP55IntentParser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NIP55IntentParserTest {

    @Test
    fun `parses sign_event URI correctly`() {
        val eventJson = """{"kind":1,"content":"test"}"""
        val uri = Uri.parse("nostrsigner:$eventJson")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "sign_event")
            putExtra("id", "req123")
        }

        val result = NIP55IntentParser.parse(intent, "com.test.app")

        assertThat(result.type).isEqualTo("sign_event")
        assertThat(result.id).isEqualTo("req123")
        assertThat(result.callingApp).isEqualTo("com.test.app")
        assertThat(result.params["event"]).isEqualTo(eventJson)
    }

    @Test
    fun `parses get_public_key URI correctly`() {
        val uri = Uri.parse("nostrsigner:")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "get_public_key")
            putExtra("id", "pk123")
        }

        val result = NIP55IntentParser.parse(intent, "com.test.app")

        assertThat(result.type).isEqualTo("get_public_key")
        assertThat(result.id).isEqualTo("pk123")
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

        val result = NIP55IntentParser.parse(intent, "com.test.app")

        assertThat(result.type).isEqualTo("get_public_key")
        assertThat(result.params["permissions"]).isEqualTo(permissionsJson)
    }

    @Test
    fun `parses nip04_encrypt URI correctly`() {
        val plaintext = "hello world"
        val pubkey = "a".repeat(64)
        val uri = Uri.parse("nostrsigner:$plaintext")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "nip04_encrypt")
            putExtra("pubkey", pubkey)
        }

        val result = NIP55IntentParser.parse(intent, "com.test.app")

        assertThat(result.type).isEqualTo("nip04_encrypt")
        assertThat(result.params["plaintext"]).isEqualTo(plaintext)
        assertThat(result.params["pubkey"]).isEqualTo(pubkey)
    }

    @Test
    fun `parses nip04_decrypt URI correctly`() {
        val ciphertext = "encrypted_data"
        val pubkey = "b".repeat(64)
        val uri = Uri.parse("nostrsigner:$ciphertext")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "nip04_decrypt")
            putExtra("pubkey", pubkey)
        }

        val result = NIP55IntentParser.parse(intent, "com.test.app")

        assertThat(result.type).isEqualTo("nip04_decrypt")
        assertThat(result.params["ciphertext"]).isEqualTo(ciphertext)
        assertThat(result.params["pubkey"]).isEqualTo(pubkey)
    }

    @Test
    fun `handles camelCase pubKey parameter`() {
        val pubkey = "c".repeat(64)
        val uri = Uri.parse("nostrsigner:data")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "nip04_encrypt")
            putExtra("pubKey", pubkey) // camelCase
        }

        val result = NIP55IntentParser.parse(intent, "com.test.app")

        assertThat(result.params["pubkey"]).isEqualTo(pubkey)
    }

    @Test(expected = NIP55ParseException::class)
    fun `rejects invalid scheme`() {
        val uri = Uri.parse("http://example.com")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "sign_event")
        }

        NIP55IntentParser.parse(intent)
    }

    @Test(expected = NIP55ParseException::class)
    fun `rejects missing type`() {
        val uri = Uri.parse("nostrsigner:")
        val intent = Intent().apply {
            data = uri
        }

        NIP55IntentParser.parse(intent)
    }

    @Test(expected = NIP55ParseException::class)
    fun `rejects invalid type`() {
        val uri = Uri.parse("nostrsigner:")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "invalid_type")
        }

        NIP55IntentParser.parse(intent)
    }

    @Test(expected = NIP55ParseException::class)
    fun `rejects missing event for sign_event`() {
        val uri = Uri.parse("nostrsigner:") // Empty content
        val intent = Intent().apply {
            data = uri
            putExtra("type", "sign_event")
        }

        NIP55IntentParser.parse(intent)
    }

    @Test(expected = NIP55ParseException::class)
    fun `rejects missing pubkey for encrypt`() {
        val uri = Uri.parse("nostrsigner:plaintext")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "nip04_encrypt")
            // Missing pubkey
        }

        NIP55IntentParser.parse(intent)
    }

    @Test(expected = NIP55ParseException::class)
    fun `rejects invalid pubkey format`() {
        val uri = Uri.parse("nostrsigner:plaintext")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "nip04_encrypt")
            putExtra("pubkey", "invalid") // Too short
        }

        NIP55IntentParser.parse(intent)
    }

    @Test(expected = NIP55ParseException::class)
    fun `rejects invalid JSON in sign_event`() {
        val uri = Uri.parse("nostrsigner:not valid json")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "sign_event")
        }

        NIP55IntentParser.parse(intent)
    }

    @Test
    fun `extracts event JSON correctly`() {
        val eventJson = """{"kind":1,"content":"hello","tags":[]}"""
        val uri = Uri.parse("nostrsigner:$eventJson")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "sign_event")
        }

        val result = NIP55IntentParser.parse(intent)

        assertThat(result.params["event"]).isEqualTo(eventJson)
    }

    @Test
    fun `generates request ID when not provided`() {
        val uri = Uri.parse("nostrsigner:")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "get_public_key")
            // No id extra
        }

        val result = NIP55IntentParser.parse(intent)

        assertThat(result.id).startsWith("nip55_")
    }

    @Test
    fun `uses calling_package extra when caller unknown`() {
        val uri = Uri.parse("nostrsigner:")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "get_public_key")
            putExtra("calling_package", "com.from.intent")
        }

        val result = NIP55IntentParser.parse(intent, callerPackage = null)

        assertThat(result.callingApp).isEqualTo("com.from.intent")
    }

    @Test
    fun `falls back to unknown when no caller info`() {
        val uri = Uri.parse("nostrsigner:")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "get_public_key")
        }

        val result = NIP55IntentParser.parse(intent, callerPackage = null)

        assertThat(result.callingApp).isEqualTo("unknown")
    }

    @Test
    fun `isValidType returns true for valid types`() {
        assertThat(NIP55IntentParser.isValidType("get_public_key")).isTrue()
        assertThat(NIP55IntentParser.isValidType("sign_event")).isTrue()
        assertThat(NIP55IntentParser.isValidType("nip04_encrypt")).isTrue()
        assertThat(NIP55IntentParser.isValidType("nip04_decrypt")).isTrue()
        assertThat(NIP55IntentParser.isValidType("nip44_encrypt")).isTrue()
        assertThat(NIP55IntentParser.isValidType("nip44_decrypt")).isTrue()
        assertThat(NIP55IntentParser.isValidType("decrypt_zap_event")).isTrue()
    }

    @Test
    fun `isValidType returns false for invalid types`() {
        assertThat(NIP55IntentParser.isValidType("invalid")).isFalse()
        assertThat(NIP55IntentParser.isValidType("")).isFalse()
        assertThat(NIP55IntentParser.isValidType("SIGN_EVENT")).isFalse() // Case sensitive
    }

    @Test
    fun `isValidPublicKey validates correctly`() {
        assertThat(NIP55IntentParser.isValidPublicKey("a".repeat(64))).isTrue()
        assertThat(NIP55IntentParser.isValidPublicKey("0123456789abcdef".repeat(4))).isTrue()
        assertThat(NIP55IntentParser.isValidPublicKey("0123456789ABCDEF".repeat(4))).isTrue()

        assertThat(NIP55IntentParser.isValidPublicKey("short")).isFalse()
        assertThat(NIP55IntentParser.isValidPublicKey("g".repeat(64))).isFalse() // Non-hex
        assertThat(NIP55IntentParser.isValidPublicKey("")).isFalse()
    }

    @Test
    fun `RequestIdGenerator creates unique IDs`() {
        val id1 = RequestIdGenerator.generate()
        val id2 = RequestIdGenerator.generate()

        assertThat(id1).startsWith("nip55_")
        assertThat(id2).startsWith("nip55_")
        // They should be different (with very high probability)
        assertThat(id1).isNotEqualTo(id2)
    }

    @Test
    fun `preserves current_user parameter`() {
        val uri = Uri.parse("nostrsigner:")
        val intent = Intent().apply {
            data = uri
            putExtra("type", "get_public_key")
            putExtra("current_user", "npub1abc...")
        }

        val result = NIP55IntentParser.parse(intent)

        assertThat(result.params["current_user"]).isEqualTo("npub1abc...")
    }

    @Test
    fun `parseUri works for sign_event`() {
        val eventJson = """{"kind":1}"""
        val uri = Uri.parse("nostrsigner:$eventJson")

        val params = NIP55IntentParser.parseUri(uri, "sign_event")

        assertThat(params["event"]).isEqualTo(eventJson)
    }

    @Test
    fun `parseUri works for encrypt`() {
        val plaintext = "secret message"
        val uri = Uri.parse("nostrsigner:$plaintext")

        val params = NIP55IntentParser.parseUri(uri, "nip04_encrypt")

        assertThat(params["plaintext"]).isEqualTo(plaintext)
    }
}
