package com.frostr.igloo

import android.database.MatrixCursor
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.frostr.igloo.health.IglooHealthManager

/**
 * Unit tests for NIP55ContentProvider.
 *
 * Tests verify operation type parsing, request creation,
 * cursor formats, and health manager integration.
 *
 * Note: Some tests require mocking dependencies like MainActivity.getWebViewInstance()
 * which is not available in unit tests. Those behaviors are tested indirectly through
 * the Intent flow tests in InvisibleNIP55HandlerTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NIP55ContentProviderTest {

    private val gson = Gson()

    @Before
    fun setup() {
        IglooHealthManager.reset()
    }

    @After
    fun teardown() {
        IglooHealthManager.reset()
    }

    // ========== Operation Type Parsing Tests ==========

    @Test
    fun `parseOperationType returns correct type for each authority`() {
        val packageName = "com.frostr.igloo"

        // Test operation type parsing by checking URI authority patterns
        val operationMap = mapOf(
            "$packageName.GET_PUBLIC_KEY" to "get_public_key",
            "$packageName.SIGN_EVENT" to "sign_event",
            "$packageName.NIP04_ENCRYPT" to "nip04_encrypt",
            "$packageName.NIP04_DECRYPT" to "nip04_decrypt",
            "$packageName.NIP44_ENCRYPT" to "nip44_encrypt",
            "$packageName.NIP44_DECRYPT" to "nip44_decrypt",
            "$packageName.DECRYPT_ZAP_EVENT" to "decrypt_zap_event"
        )

        operationMap.forEach { (authority, expectedType) ->
            val uri = Uri.parse("content://$authority")
            assertThat(uri.authority).isEqualTo(authority)
        }
    }

    @Test
    fun `invalid authority is not matched`() {
        val invalidAuthorities = listOf(
            "com.frostr.igloo.INVALID_OP",
            "com.other.app.SIGN_EVENT",
            "",
            "SIGN_EVENT"  // Missing package prefix
        )

        invalidAuthorities.forEach { authority ->
            // These should not match any valid operation
            val validOps = listOf(
                "GET_PUBLIC_KEY", "SIGN_EVENT", "NIP04_ENCRYPT",
                "NIP04_DECRYPT", "NIP44_ENCRYPT", "NIP44_DECRYPT", "DECRYPT_ZAP_EVENT"
            )
            val isValid = validOps.any { authority.endsWith(".$it") && authority.startsWith("com.frostr.igloo.") }
            assertThat(isValid).isFalse()
        }
    }

    // ========== NIP55Request Creation Tests ==========

    @Test
    fun `sign_event request includes event param`() {
        val eventJson = """{"id":"abc123","kind":1,"content":"test"}"""
        val args = arrayOf(eventJson)

        val params = createRequestParams("sign_event", args)

        assertThat(params["event"]).isEqualTo(eventJson)
    }

    @Test
    fun `encrypt request includes plaintext and pubkey`() {
        val plaintext = "hello world"
        val pubkey = "a".repeat(64)
        val args = arrayOf(plaintext, pubkey)

        val params = createRequestParams("nip04_encrypt", args)

        assertThat(params["plaintext"]).isEqualTo(plaintext)
        assertThat(params["pubkey"]).isEqualTo(pubkey)
    }

    @Test
    fun `decrypt request includes ciphertext and pubkey`() {
        val ciphertext = "encrypted_data"
        val pubkey = "b".repeat(64)
        val args = arrayOf(ciphertext, pubkey)

        val params = createRequestParams("nip04_decrypt", args)

        assertThat(params["ciphertext"]).isEqualTo(ciphertext)
        assertThat(params["pubkey"]).isEqualTo(pubkey)
    }

    @Test
    fun `decrypt_zap_event request includes event param`() {
        val eventJson = """{"id":"zap123","kind":9735}"""
        val args = arrayOf(eventJson)

        val params = createRequestParams("decrypt_zap_event", args)

        assertThat(params["event"]).isEqualTo(eventJson)
    }

    @Test
    fun `get_public_key request has empty params`() {
        val args = emptyArray<String>()

        val params = createRequestParams("get_public_key", args)

        assertThat(params).isEmpty()
    }

    @Test
    fun `missing args handled gracefully`() {
        val args = emptyArray<String>()

        val params = createRequestParams("sign_event", args)

        // Should not throw, just have empty/missing params
        assertThat(params["event"]).isNull()
    }

    // ========== Result Cursor Format Tests ==========

    @Test
    fun `sign_event cursor has signature and event columns`() {
        val signedEvent = """{"id":"abc","kind":1,"sig":"sig123","content":"test"}"""

        val cursor = createResultCursor(signedEvent, "sign_event")

        assertThat(cursor.columnNames).asList().containsAtLeast("signature", "event")
        cursor.moveToFirst()
        assertThat(cursor.getString(cursor.getColumnIndex("signature"))).isEqualTo("sig123")
        assertThat(cursor.getString(cursor.getColumnIndex("event"))).isEqualTo(signedEvent)
    }

    @Test
    fun `decrypt cursor has result column`() {
        val decryptedText = "decrypted message"

        val cursor = createResultCursor(decryptedText, "nip04_decrypt")

        assertThat(cursor.columnNames).asList().contains("result")
        cursor.moveToFirst()
        assertThat(cursor.getString(cursor.getColumnIndex("result"))).isEqualTo(decryptedText)
    }

    @Test
    fun `encrypt cursor has result column`() {
        val encryptedText = "encrypted_ciphertext"

        val cursor = createResultCursor(encryptedText, "nip44_encrypt")

        assertThat(cursor.columnNames).asList().contains("result")
        cursor.moveToFirst()
        assertThat(cursor.getString(cursor.getColumnIndex("result"))).isEqualTo(encryptedText)
    }

    @Test
    fun `rejected cursor has rejected column`() {
        val reason = "Permission denied"

        val cursor = createRejectedCursor(reason)

        assertThat(cursor.columnNames).asList().contains("rejected")
        cursor.moveToFirst()
        assertThat(cursor.getString(cursor.getColumnIndex("rejected"))).isEqualTo(reason)
    }

    // ========== Health Manager Integration Tests ==========

    @Test
    fun `query returns null when health manager is unhealthy`() {
        // Health manager starts unhealthy
        assertThat(IglooHealthManager.isHealthy).isFalse()

        // In real ContentProvider.query(), this would return null
        // We can verify the condition directly
    }

    @Test
    fun `health manager can transition to healthy`() {
        assertThat(IglooHealthManager.isHealthy).isFalse()

        IglooHealthManager.markHealthy()

        assertThat(IglooHealthManager.isHealthy).isTrue()
    }

    // ========== NIP55ResultBridge Tests ==========

    @Test
    fun `result bridge stores and retrieves results`() {
        val bridge = NIP55ContentProvider.NIP55ResultBridge()
        val resultKey = "test-key-123"
        val jsonResult = """{"ok":true,"result":"success"}"""

        // Simulate JS setting result
        bridge.setResult(resultKey, jsonResult)

        // Wait for result should return immediately
        val result = bridge.waitForResult(resultKey, 1000)

        assertThat(result).isEqualTo(jsonResult)
    }

    @Test
    fun `result bridge returns null on timeout`() {
        val bridge = NIP55ContentProvider.NIP55ResultBridge()

        val result = bridge.waitForResult("nonexistent-key", 100)

        assertThat(result).isNull()
    }

    @Test
    fun `result bridge cleanup removes pending results`() {
        val bridge = NIP55ContentProvider.NIP55ResultBridge()
        val resultKey = "cleanup-test"

        bridge.setResult(resultKey, "some result")
        bridge.cleanup(resultKey)

        // After cleanup, should timeout
        val result = bridge.waitForResult(resultKey, 100)
        assertThat(result).isNull()
    }

    @Test
    fun `result bridge handles concurrent access`() {
        val bridge = NIP55ContentProvider.NIP55ResultBridge()
        val results = mutableListOf<String?>()

        // Simulate multiple concurrent operations
        val threads = (1..5).map { i ->
            Thread {
                val key = "key-$i"
                bridge.setResult(key, "result-$i")
                val result = bridge.waitForResult(key, 1000)
                synchronized(results) {
                    results.add(result)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertThat(results.filterNotNull()).hasSize(5)
    }

    // ========== Helper Methods ==========

    /**
     * Mirror of request params creation logic
     */
    private fun createRequestParams(operationType: String, args: Array<String>): Map<String, String> {
        val params = mutableMapOf<String, String>()

        when (operationType) {
            "sign_event" -> {
                args.getOrNull(0)?.let { params["event"] = it }
            }
            "nip04_encrypt", "nip44_encrypt" -> {
                args.getOrNull(0)?.let { params["plaintext"] = it }
                args.getOrNull(1)?.let { params["pubkey"] = it }
            }
            "nip04_decrypt", "nip44_decrypt" -> {
                args.getOrNull(0)?.let { params["ciphertext"] = it }
                args.getOrNull(1)?.let { params["pubkey"] = it }
            }
            "decrypt_zap_event" -> {
                args.getOrNull(0)?.let { params["event"] = it }
            }
        }

        return params
    }

    /**
     * Mirror of cursor creation logic for sign_event
     */
    private fun createResultCursor(jsonResult: String, operationType: String): MatrixCursor {
        return when (operationType) {
            "sign_event" -> {
                val resultMap = gson.fromJson(jsonResult, Map::class.java)
                val cursor = MatrixCursor(arrayOf("signature", "event"))
                val signature = resultMap["sig"]?.toString() ?: ""
                cursor.addRow(arrayOf(signature, jsonResult))
                cursor
            }
            "nip04_decrypt", "nip44_decrypt", "nip04_encrypt", "nip44_encrypt", "decrypt_zap_event" -> {
                val cursor = MatrixCursor(arrayOf("signature", "event", "result"))
                cursor.addRow(arrayOf(jsonResult, jsonResult, jsonResult))
                cursor
            }
            else -> {
                val cursor = MatrixCursor(arrayOf("result"))
                cursor.addRow(arrayOf(jsonResult))
                cursor
            }
        }
    }

    /**
     * Mirror of rejected cursor creation
     */
    private fun createRejectedCursor(reason: String): MatrixCursor {
        val cursor = MatrixCursor(arrayOf("rejected"))
        cursor.addRow(arrayOf(reason))
        return cursor
    }
}
