package com.frostr.igloo.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for AuditLogger.
 *
 * Tests verify audit log creation, entry writing, reading,
 * and file management operations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AuditLoggerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any existing audit log before each test
        AuditLogger.clearAuditLog(context)
    }

    @After
    fun teardown() {
        // Clean up after tests
        AuditLogger.clearAuditLog(context)
    }

    // ========== logSignEvent Tests ==========

    @Test
    fun `logSignEvent creates audit file if not exists`() {
        val eventJson = """{"id":"abc123","kind":1,"content":"test"}"""

        AuditLogger.logSignEvent(context, "com.test.app", eventJson)

        val logPath = AuditLogger.getAuditLogPath(context)
        assertThat(File(logPath).exists()).isTrue()
    }

    @Test
    fun `logSignEvent writes valid JSON entry`() {
        val eventJson = """{"id":"abc123","kind":1,"content":"test"}"""

        AuditLogger.logSignEvent(context, "com.test.app", eventJson, "req-123")

        val entries = AuditLogger.readAuditEntries(context)
        assertThat(entries).hasSize(1)

        val entry = entries[0]
        assertThat(entry.getString("calling_app")).isEqualTo("com.test.app")
        assertThat(entry.getString("request_id")).isEqualTo("req-123")
        assertThat(entry.has("timestamp")).isTrue()
        assertThat(entry.has("unix_ts")).isTrue()
        assertThat(entry.has("event")).isTrue()
    }

    @Test
    fun `logSignEvent parses event JSON correctly`() {
        val eventJson = """{"id":"abc123","kind":1,"content":"test message","tags":[]}"""

        AuditLogger.logSignEvent(context, "com.test.app", eventJson)

        val entries = AuditLogger.readAuditEntries(context)
        val eventObj = entries[0].getJSONObject("event")
        assertThat(eventObj.getString("id")).isEqualTo("abc123")
        assertThat(eventObj.getInt("kind")).isEqualTo(1)
        assertThat(eventObj.getString("content")).isEqualTo("test message")
    }

    @Test
    fun `logSignEvent handles malformed JSON gracefully`() {
        val malformedJson = "not valid json {{{"

        AuditLogger.logSignEvent(context, "com.test.app", malformedJson)

        val entries = AuditLogger.readAuditEntries(context)
        assertThat(entries).hasSize(1)

        val entry = entries[0]
        assertThat(entry.has("event_raw")).isTrue()
        assertThat(entry.getString("event_raw")).isEqualTo(malformedJson)
        assertThat(entry.has("parse_error")).isTrue()
    }

    @Test
    fun `logSignEvent without requestId omits field`() {
        val eventJson = """{"id":"abc123","kind":1}"""

        AuditLogger.logSignEvent(context, "com.test.app", eventJson)

        val entries = AuditLogger.readAuditEntries(context)
        assertThat(entries[0].has("request_id")).isFalse()
    }

    // ========== logCryptoOperation Tests ==========

    @Test
    fun `logCryptoOperation writes correct entry format`() {
        val pubkey = "a".repeat(64)

        AuditLogger.logCryptoOperation(
            context,
            "com.test.app",
            "nip04_encrypt",
            pubkey,
            "req-456"
        )

        val entries = AuditLogger.readAuditEntries(context)
        assertThat(entries).hasSize(1)

        val entry = entries[0]
        assertThat(entry.getString("calling_app")).isEqualTo("com.test.app")
        assertThat(entry.getString("operation")).isEqualTo("nip04_encrypt")
        assertThat(entry.getString("pubkey")).isEqualTo(pubkey)
        assertThat(entry.getString("request_id")).isEqualTo("req-456")
    }

    @Test
    fun `logCryptoOperation works for all operation types`() {
        val operations = listOf(
            "nip04_encrypt",
            "nip04_decrypt",
            "nip44_encrypt",
            "nip44_decrypt"
        )

        operations.forEach { op ->
            AuditLogger.logCryptoOperation(context, "app", op, "pubkey")
        }

        assertThat(AuditLogger.getAuditEntryCount(context)).isEqualTo(4)
    }

    // ========== readAuditEntries Tests ==========

    @Test
    fun `readAuditEntries returns empty list when no log exists`() {
        val entries = AuditLogger.readAuditEntries(context)
        assertThat(entries).isEmpty()
    }

    @Test
    fun `readAuditEntries returns newest entries first`() {
        AuditLogger.logSignEvent(context, "app1", """{"id":"first"}""")
        Thread.sleep(10) // Ensure different timestamps
        AuditLogger.logSignEvent(context, "app2", """{"id":"second"}""")

        val entries = AuditLogger.readAuditEntries(context)
        assertThat(entries).hasSize(2)
        // Newest first
        assertThat(entries[0].getString("calling_app")).isEqualTo("app2")
        assertThat(entries[1].getString("calling_app")).isEqualTo("app1")
    }

    @Test
    fun `readAuditEntries respects limit parameter`() {
        repeat(10) { i ->
            AuditLogger.logSignEvent(context, "app$i", """{"id":"event$i"}""")
        }

        val entries = AuditLogger.readAuditEntries(context, limit = 3)
        assertThat(entries).hasSize(3)
    }

    // ========== getAuditLogPath Tests ==========

    @Test
    fun `getAuditLogPath returns consistent path`() {
        val path1 = AuditLogger.getAuditLogPath(context)
        val path2 = AuditLogger.getAuditLogPath(context)

        assertThat(path1).isEqualTo(path2)
        assertThat(path1).contains("audit")
        assertThat(path1).contains("signing_audit.jsonl")
    }

    // ========== getAuditLogSize Tests ==========

    @Test
    fun `getAuditLogSize returns zero when no log exists`() {
        assertThat(AuditLogger.getAuditLogSize(context)).isEqualTo(0)
    }

    @Test
    fun `getAuditLogSize increases after writing entries`() {
        val sizeBefore = AuditLogger.getAuditLogSize(context)

        AuditLogger.logSignEvent(context, "app", """{"id":"test"}""")

        val sizeAfter = AuditLogger.getAuditLogSize(context)
        assertThat(sizeAfter).isGreaterThan(sizeBefore)
    }

    // ========== getAuditEntryCount Tests ==========

    @Test
    fun `getAuditEntryCount returns zero when no log exists`() {
        assertThat(AuditLogger.getAuditEntryCount(context)).isEqualTo(0)
    }

    @Test
    fun `getAuditEntryCount counts entries correctly`() {
        repeat(5) { i ->
            AuditLogger.logSignEvent(context, "app", """{"id":"event$i"}""")
        }

        assertThat(AuditLogger.getAuditEntryCount(context)).isEqualTo(5)
    }

    // ========== clearAuditLog Tests ==========

    @Test
    fun `clearAuditLog removes all entries`() {
        repeat(5) { i ->
            AuditLogger.logSignEvent(context, "app", """{"id":"event$i"}""")
        }
        assertThat(AuditLogger.getAuditEntryCount(context)).isEqualTo(5)

        val cleared = AuditLogger.clearAuditLog(context)

        assertThat(cleared).isTrue()
        assertThat(AuditLogger.getAuditEntryCount(context)).isEqualTo(0)
    }

    @Test
    fun `clearAuditLog succeeds when no log exists`() {
        val cleared = AuditLogger.clearAuditLog(context)
        assertThat(cleared).isTrue()
    }

    @Test
    fun `clearAuditLog can be called multiple times`() {
        AuditLogger.clearAuditLog(context)
        AuditLogger.clearAuditLog(context)
        AuditLogger.clearAuditLog(context)
        // No exception = success
    }

    // ========== Mixed Operation Tests ==========

    @Test
    fun `sign and crypto operations are logged together`() {
        AuditLogger.logSignEvent(context, "app", """{"id":"sign1"}""")
        AuditLogger.logCryptoOperation(context, "app", "nip04_encrypt", "pubkey")
        AuditLogger.logSignEvent(context, "app", """{"id":"sign2"}""")

        val entries = AuditLogger.readAuditEntries(context)
        assertThat(entries).hasSize(3)

        // Verify different entry types are distinguishable
        val signEntries = entries.filter { it.has("event") || it.has("event_raw") }
        val cryptoEntries = entries.filter { it.has("operation") }
        assertThat(signEntries).hasSize(2)
        assertThat(cryptoEntries).hasSize(1)
    }
}
