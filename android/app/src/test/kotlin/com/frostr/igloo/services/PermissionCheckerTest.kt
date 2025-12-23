package com.frostr.igloo.services

import com.frostr.igloo.bridges.StorageBridge
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for PermissionChecker service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PermissionCheckerTest {

    @Mock
    private lateinit var storageBridge: StorageBridge

    private lateinit var permissionChecker: PermissionChecker

    @Before
    fun setUp() {
        storageBridge = org.mockito.Mockito.mock(StorageBridge::class.java)
        permissionChecker = PermissionChecker(storageBridge)
    }

    @Test
    fun `returns Allowed for matching permission`() {
        val permissionsJson = """
            {
                "permissions": [
                    {"appId": "com.test.app", "type": "sign_event", "kind": null, "allowed": true, "timestamp": 123}
                ]
            }
        """.trimIndent()

        `when`(storageBridge.getItem("local", "nip55_permissions_v2")).thenReturn(permissionsJson)

        val result = permissionChecker.checkPermission("com.test.app", "sign_event")

        assertThat(result).isInstanceOf(PermissionResult.Allowed::class.java)
        assertThat((result as PermissionResult.Allowed).timestamp).isEqualTo(123)
    }

    @Test
    fun `returns Denied for explicitly denied permission`() {
        val permissionsJson = """
            {
                "permissions": [
                    {"appId": "com.test.app", "type": "sign_event", "kind": null, "allowed": false, "timestamp": 456}
                ]
            }
        """.trimIndent()

        `when`(storageBridge.getItem("local", "nip55_permissions_v2")).thenReturn(permissionsJson)

        val result = permissionChecker.checkPermission("com.test.app", "sign_event")

        assertThat(result).isInstanceOf(PermissionResult.Denied::class.java)
        assertThat((result as PermissionResult.Denied).timestamp).isEqualTo(456)
    }

    @Test
    fun `returns PromptRequired for unknown app`() {
        val permissionsJson = """
            {
                "permissions": [
                    {"appId": "com.other.app", "type": "sign_event", "kind": null, "allowed": true, "timestamp": 123}
                ]
            }
        """.trimIndent()

        `when`(storageBridge.getItem("local", "nip55_permissions_v2")).thenReturn(permissionsJson)

        val result = permissionChecker.checkPermission("com.test.app", "sign_event")

        assertThat(result).isEqualTo(PermissionResult.PromptRequired)
    }

    @Test
    fun `returns PromptRequired when no permissions stored`() {
        `when`(storageBridge.getItem("local", "nip55_permissions_v2")).thenReturn(null)

        val result = permissionChecker.checkPermission("com.test.app", "sign_event")

        assertThat(result).isEqualTo(PermissionResult.PromptRequired)
    }

    @Test
    fun `kind-specific permission takes precedence over wildcard`() {
        val permissionsJson = """
            {
                "permissions": [
                    {"appId": "com.test.app", "type": "sign_event", "kind": null, "allowed": false, "timestamp": 100},
                    {"appId": "com.test.app", "type": "sign_event", "kind": 1, "allowed": true, "timestamp": 200}
                ]
            }
        """.trimIndent()

        `when`(storageBridge.getItem("local", "nip55_permissions_v2")).thenReturn(permissionsJson)

        // Kind 1 should be allowed (kind-specific)
        val result1 = permissionChecker.checkPermission("com.test.app", "sign_event", eventKind = 1)
        assertThat(result1).isInstanceOf(PermissionResult.Allowed::class.java)

        // Kind 2 should be denied (wildcard)
        val result2 = permissionChecker.checkPermission("com.test.app", "sign_event", eventKind = 2)
        assertThat(result2).isInstanceOf(PermissionResult.Denied::class.java)
    }

    @Test
    fun `wildcard permission matches any kind when no kind-specific exists`() {
        val permissionsJson = """
            {
                "permissions": [
                    {"appId": "com.test.app", "type": "sign_event", "kind": null, "allowed": true, "timestamp": 100}
                ]
            }
        """.trimIndent()

        `when`(storageBridge.getItem("local", "nip55_permissions_v2")).thenReturn(permissionsJson)

        // Any kind should match the wildcard
        val result = permissionChecker.checkPermission("com.test.app", "sign_event", eventKind = 999)
        assertThat(result).isInstanceOf(PermissionResult.Allowed::class.java)
    }

    @Test
    fun `non-sign_event operations use simple lookup`() {
        val permissionsJson = """
            {
                "permissions": [
                    {"appId": "com.test.app", "type": "nip04_encrypt", "kind": null, "allowed": true, "timestamp": 123}
                ]
            }
        """.trimIndent()

        `when`(storageBridge.getItem("local", "nip55_permissions_v2")).thenReturn(permissionsJson)

        val result = permissionChecker.checkPermission("com.test.app", "nip04_encrypt")

        assertThat(result).isInstanceOf(PermissionResult.Allowed::class.java)
    }

    @Test
    fun `returns PromptRequired for unknown operation type`() {
        val permissionsJson = """
            {
                "permissions": [
                    {"appId": "com.test.app", "type": "sign_event", "kind": null, "allowed": true, "timestamp": 123}
                ]
            }
        """.trimIndent()

        `when`(storageBridge.getItem("local", "nip55_permissions_v2")).thenReturn(permissionsJson)

        val result = permissionChecker.checkPermission("com.test.app", "nip04_decrypt")

        assertThat(result).isEqualTo(PermissionResult.PromptRequired)
    }

    @Test
    fun `extractEventKind returns kind from valid JSON`() {
        val eventJson = """{"kind": 1, "content": "hello"}"""

        val kind = permissionChecker.extractEventKind(eventJson)

        assertThat(kind).isEqualTo(1)
    }

    @Test
    fun `extractEventKind returns null for missing kind`() {
        val eventJson = """{"content": "hello"}"""

        val kind = permissionChecker.extractEventKind(eventJson)

        assertThat(kind).isNull()
    }

    @Test
    fun `extractEventKind returns null for invalid JSON`() {
        val eventJson = "not valid json"

        val kind = permissionChecker.extractEventKind(eventJson)

        assertThat(kind).isNull()
    }

    @Test
    fun `extractEventKind returns null for null input`() {
        val kind = permissionChecker.extractEventKind(null)

        assertThat(kind).isNull()
    }

    @Test
    fun `toStatusString converts results correctly`() {
        assertThat(permissionChecker.toStatusString(PermissionResult.Allowed(123))).isEqualTo("allowed")
        assertThat(permissionChecker.toStatusString(PermissionResult.Denied(456))).isEqualTo("denied")
        assertThat(permissionChecker.toStatusString(PermissionResult.PromptRequired)).isEqualTo("prompt_required")
    }

    @Test
    fun `handles malformed JSON gracefully`() {
        `when`(storageBridge.getItem("local", "nip55_permissions_v2")).thenReturn("{invalid json}")

        val result = permissionChecker.checkPermission("com.test.app", "sign_event")

        // Should return PromptRequired rather than crash
        assertThat(result).isEqualTo(PermissionResult.PromptRequired)
    }

    @Test
    fun `handles empty permissions array`() {
        val permissionsJson = """{"permissions": []}"""

        `when`(storageBridge.getItem("local", "nip55_permissions_v2")).thenReturn(permissionsJson)

        val result = permissionChecker.checkPermission("com.test.app", "sign_event")

        assertThat(result).isEqualTo(PermissionResult.PromptRequired)
    }
}
