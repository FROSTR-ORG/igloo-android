package com.frostr.igloo.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for NIP55Deduplicator utility.
 *
 * Tests verify correct deduplication key generation for different
 * NIP-55 operation types.
 *
 * Uses Robolectric to support Android Log calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NIP55DeduplicatorTest {

    // === sign_event tests ===

    @Test
    fun `sign_event deduplicates by event id`() {
        val params = mapOf("event" to """{"id":"abc123","kind":1,"content":"test"}""")
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "sign_event",
            params = params,
            fallbackId = "fallback"
        )
        assertThat(key).isEqualTo("com.example.app:sign_event:abc123")
    }

    @Test
    fun `sign_event falls back to content hash when no id`() {
        val eventJson = """{"kind":1,"content":"test without id"}"""
        val params = mapOf("event" to eventJson)
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "sign_event",
            params = params,
            fallbackId = "fallback"
        )
        // Should use hashCode of the event JSON when no id present
        assertThat(key).isEqualTo("com.example.app:sign_event:${eventJson.hashCode()}")
    }

    @Test
    fun `sign_event with different events produces different keys`() {
        val params1 = mapOf("event" to """{"id":"event1","kind":1}""")
        val params2 = mapOf("event" to """{"id":"event2","kind":1}""")

        val key1 = NIP55Deduplicator.getDeduplicationKey("app", "sign_event", params1, "f1")
        val key2 = NIP55Deduplicator.getDeduplicationKey("app", "sign_event", params2, "f2")

        assertThat(key1).isNotEqualTo(key2)
    }

    @Test
    fun `sign_event uses fallback when event is null`() {
        val params = mapOf<String, String?>("event" to null)
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "sign_event",
            params = params,
            fallbackId = "fallback123"
        )
        assertThat(key).isEqualTo("com.example.app:sign_event:fallback123")
    }

    // === Kind 22242 (NIP-42 relay auth) tests ===

    @Test
    fun `kind 22242 deduplicates by challenge tag instead of event id`() {
        // NIP-42 relay auth events should deduplicate by challenge, not event ID
        // because Amethyst sends the same challenge with different event IDs on retry
        val eventJson = """{"id":"unique-id-1","kind":22242,"tags":[["relay","wss://relay.example.com"],["challenge","abc123challenge"]]}"""
        val params = mapOf("event" to eventJson)
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "sign_event",
            params = params,
            fallbackId = "fallback"
        )
        // Should include auth prefix, relay, and challenge - NOT the event id
        assertThat(key).contains(":auth:")
        assertThat(key).contains("wss://relay.example.com")
        assertThat(key).contains("abc123challenge")
        assertThat(key).doesNotContain("unique-id-1")
    }

    @Test
    fun `kind 22242 same challenge produces same key with different event ids`() {
        // Amethyst sends same challenge with different event IDs on retry
        val eventJson1 = """{"id":"id-attempt-1","kind":22242,"tags":[["relay","wss://relay.example.com"],["challenge","same_challenge"]]}"""
        val eventJson2 = """{"id":"id-attempt-2","kind":22242,"tags":[["relay","wss://relay.example.com"],["challenge","same_challenge"]]}"""

        val key1 = NIP55Deduplicator.getDeduplicationKey("app", "sign_event", mapOf("event" to eventJson1), "f1")
        val key2 = NIP55Deduplicator.getDeduplicationKey("app", "sign_event", mapOf("event" to eventJson2), "f2")

        // Same challenge = same dedup key (even with different event IDs)
        assertThat(key1).isEqualTo(key2)
    }

    @Test
    fun `kind 22242 different challenges produce different keys`() {
        val eventJson1 = """{"id":"id1","kind":22242,"tags":[["relay","wss://relay.example.com"],["challenge","challenge_A"]]}"""
        val eventJson2 = """{"id":"id2","kind":22242,"tags":[["relay","wss://relay.example.com"],["challenge","challenge_B"]]}"""

        val key1 = NIP55Deduplicator.getDeduplicationKey("app", "sign_event", mapOf("event" to eventJson1), "f1")
        val key2 = NIP55Deduplicator.getDeduplicationKey("app", "sign_event", mapOf("event" to eventJson2), "f2")

        assertThat(key1).isNotEqualTo(key2)
    }

    @Test
    fun `kind 22242 different relays produce different keys`() {
        val eventJson1 = """{"id":"id1","kind":22242,"tags":[["relay","wss://relay1.example.com"],["challenge","same_challenge"]]}"""
        val eventJson2 = """{"id":"id2","kind":22242,"tags":[["relay","wss://relay2.example.com"],["challenge","same_challenge"]]}"""

        val key1 = NIP55Deduplicator.getDeduplicationKey("app", "sign_event", mapOf("event" to eventJson1), "f1")
        val key2 = NIP55Deduplicator.getDeduplicationKey("app", "sign_event", mapOf("event" to eventJson2), "f2")

        // Different relays = different auth contexts
        assertThat(key1).isNotEqualTo(key2)
    }

    @Test
    fun `kind 22242 without challenge tag falls back to hash`() {
        // Edge case: kind 22242 without challenge tag
        val eventJson = """{"id":"abc123","kind":22242,"tags":[["relay","wss://relay.example.com"]]}"""
        val params = mapOf("event" to eventJson)
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "sign_event",
            params = params,
            fallbackId = "fallback"
        )
        // Should still include auth prefix and relay, but use hash for challenge
        assertThat(key).contains(":auth:")
        assertThat(key).contains("wss://relay.example.com")
    }

    @Test
    fun `array overload kind 22242 deduplicates by challenge`() {
        val eventJson = """{"id":"unique-id","kind":22242,"tags":[["relay","wss://test.relay"],["challenge","test_challenge_123"]]}"""
        val args = arrayOf(eventJson)
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingPackage = "com.example.app",
            operationType = "sign_event",
            args = args
        )
        assertThat(key).contains(":auth:")
        assertThat(key).contains("test_challenge_123")
        assertThat(key).doesNotContain("unique-id")
    }

    // === Encryption tests ===

    @Test
    fun `nip04_encrypt deduplicates by plaintext and pubkey`() {
        val params = mapOf("plaintext" to "hello", "pubkey" to "abc123")
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "nip04_encrypt",
            params = params,
            fallbackId = "fallback"
        )
        assertThat(key).isEqualTo("com.example.app:nip04_encrypt:${"hello".hashCode()}:abc123")
    }

    @Test
    fun `nip04_decrypt deduplicates by ciphertext and pubkey`() {
        val params = mapOf("ciphertext" to "encrypted_data", "pubkey" to "abc123")
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "nip04_decrypt",
            params = params,
            fallbackId = "fallback"
        )
        assertThat(key).isEqualTo("com.example.app:nip04_decrypt:${"encrypted_data".hashCode()}:abc123")
    }

    @Test
    fun `nip44_encrypt deduplicates by plaintext and pubkey`() {
        val params = mapOf("plaintext" to "hello44", "pubkey" to "def456")
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "nip44_encrypt",
            params = params,
            fallbackId = "fallback"
        )
        assertThat(key).isEqualTo("com.example.app:nip44_encrypt:${"hello44".hashCode()}:def456")
    }

    @Test
    fun `nip44_decrypt deduplicates by ciphertext and pubkey`() {
        val params = mapOf("ciphertext" to "nip44_encrypted", "pubkey" to "def456")
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "nip44_decrypt",
            params = params,
            fallbackId = "fallback"
        )
        assertThat(key).isEqualTo("com.example.app:nip44_decrypt:${"nip44_encrypted".hashCode()}:def456")
    }

    // === get_public_key tests ===

    @Test
    fun `get_public_key deduplicates by calling app only`() {
        val params = emptyMap<String, String?>()
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "get_public_key",
            params = params,
            fallbackId = "fallback"
        )
        assertThat(key).isEqualTo("com.example.app:get_public_key")
    }

    @Test
    fun `get_public_key same for different fallback ids`() {
        val params = emptyMap<String, String?>()
        val key1 = NIP55Deduplicator.getDeduplicationKey("app", "get_public_key", params, "id1")
        val key2 = NIP55Deduplicator.getDeduplicationKey("app", "get_public_key", params, "id2")
        assertThat(key1).isEqualTo(key2)
    }

    // === Edge cases ===

    @Test
    fun `unknown operation type uses fallback key`() {
        val params = mapOf("foo" to "bar")
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "unknown_operation",
            params = params,
            fallbackId = "fallback123"
        )
        assertThat(key).isEqualTo("com.example.app:unknown_operation:fallback123")
    }

    @Test
    fun `empty string params handled gracefully`() {
        val params = mapOf("event" to "")
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "sign_event",
            params = params,
            fallbackId = "fallback"
        )
        // Empty string should still produce a valid key (using fallback due to parse failure)
        assertThat(key).isNotEmpty()
    }

    @Test
    fun `malformed JSON in params handled gracefully`() {
        val params = mapOf("event" to "not valid json {{{")
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingApp = "com.example.app",
            operationType = "sign_event",
            params = params,
            fallbackId = "fallback"
        )
        // Should fall back to using fallback or content hash
        assertThat(key).isNotEmpty()
        assertThat(key).startsWith("com.example.app:sign_event:")
    }

    @Test
    fun `content hash is deterministic`() {
        val params = mapOf("event" to """{"kind":1,"content":"same content"}""")
        val key1 = NIP55Deduplicator.getDeduplicationKey("app", "sign_event", params, "f1")
        val key2 = NIP55Deduplicator.getDeduplicationKey("app", "sign_event", params, "f2")
        // Same content should produce same key (when no id, uses hash)
        assertThat(key1).isEqualTo(key2)
    }

    // === Array-based overload tests ===

    @Test
    fun `array overload sign_event deduplicates by event id`() {
        val args = arrayOf("""{"id":"abc123","kind":1}""")
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingPackage = "com.example.app",
            operationType = "sign_event",
            args = args
        )
        assertThat(key).isEqualTo("com.example.app:sign_event:abc123")
    }

    @Test
    fun `array overload nip04_decrypt uses correct args`() {
        val args = arrayOf("ciphertext_data", "recipient_pubkey")
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingPackage = "com.example.app",
            operationType = "nip04_decrypt",
            args = args
        )
        assertThat(key).isEqualTo("com.example.app:nip04_decrypt:${"ciphertext_data".hashCode()}:recipient_pubkey")
    }

    @Test
    fun `array overload get_public_key works with empty args`() {
        val args = emptyArray<String>()
        val key = NIP55Deduplicator.getDeduplicationKey(
            callingPackage = "com.example.app",
            operationType = "get_public_key",
            args = args
        )
        assertThat(key).isEqualTo("com.example.app:get_public_key")
    }

    @Test
    fun `different callers produce different keys for same operation`() {
        val params = mapOf("event" to """{"id":"same_event"}""")
        val key1 = NIP55Deduplicator.getDeduplicationKey("app1", "sign_event", params, "f")
        val key2 = NIP55Deduplicator.getDeduplicationKey("app2", "sign_event", params, "f")
        assertThat(key1).isNotEqualTo(key2)
    }
}
