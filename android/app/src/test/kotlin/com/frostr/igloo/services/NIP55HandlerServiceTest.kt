package com.frostr.igloo.services

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for NIP55HandlerService.
 *
 * Tests verify service lifecycle management and the isRunning flag.
 *
 * Note: These tests focus on lifecycle behavior rather than initial state,
 * since the static isRunning flag persists across tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NIP55HandlerServiceTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    // ========== Service State Tests ==========

    @Test
    fun `service sets isRunning true on create`() {
        val controller = Robolectric.buildService(NIP55HandlerService::class.java).create()
        val service = controller.get()

        assertThat(NIP55HandlerService.isRunning).isTrue()

        // Cleanup
        controller.destroy()
    }

    @Test
    fun `service sets isRunning false on destroy`() {
        val controller = Robolectric.buildService(NIP55HandlerService::class.java).create()
        assertThat(NIP55HandlerService.isRunning).isTrue()

        controller.destroy()
        assertThat(NIP55HandlerService.isRunning).isFalse()
    }

    @Test
    fun `onStartCommand returns START_NOT_STICKY`() {
        val controller = Robolectric.buildService(NIP55HandlerService::class.java).create()
        val service = controller.get()

        val result = service.onStartCommand(null, 0, 1)
        assertThat(result).isEqualTo(android.app.Service.START_NOT_STICKY)

        // Cleanup
        controller.destroy()
    }

    @Test
    fun `onBind returns null`() {
        val controller = Robolectric.buildService(NIP55HandlerService::class.java).create()
        val service = controller.get()

        assertThat(service.onBind(null)).isNull()

        // Cleanup
        controller.destroy()
    }

    // ========== Lifecycle Tests ==========

    @Test
    fun `full lifecycle works correctly`() {
        // Create service
        val controller = Robolectric.buildService(NIP55HandlerService::class.java).create()
        assertThat(NIP55HandlerService.isRunning).isTrue()

        // Start command
        controller.startCommand(0, 1)
        assertThat(NIP55HandlerService.isRunning).isTrue()

        // Destroy
        controller.destroy()
        assertThat(NIP55HandlerService.isRunning).isFalse()
    }

    @Test
    fun `service can be created and destroyed multiple times`() {
        // First lifecycle
        var controller = Robolectric.buildService(NIP55HandlerService::class.java).create()
        assertThat(NIP55HandlerService.isRunning).isTrue()
        controller.destroy()
        assertThat(NIP55HandlerService.isRunning).isFalse()

        // Second lifecycle
        controller = Robolectric.buildService(NIP55HandlerService::class.java).create()
        assertThat(NIP55HandlerService.isRunning).isTrue()
        controller.destroy()
        assertThat(NIP55HandlerService.isRunning).isFalse()
    }

    // ========== Helper Method Tests ==========

    @Test
    fun `start is safe when service already running`() {
        // Create service first
        val controller = Robolectric.buildService(NIP55HandlerService::class.java).create()
        assertThat(NIP55HandlerService.isRunning).isTrue()

        // Calling start again should not crash
        NIP55HandlerService.start(context)
        assertThat(NIP55HandlerService.isRunning).isTrue()

        // Cleanup
        controller.destroy()
    }

    @Test
    fun `stop is safe when service not running`() {
        // Ensure service is not running
        val controller = Robolectric.buildService(NIP55HandlerService::class.java).create()
        controller.destroy()
        assertThat(NIP55HandlerService.isRunning).isFalse()

        // Calling stop when not running should not crash
        NIP55HandlerService.stop(context)
        assertThat(NIP55HandlerService.isRunning).isFalse()
    }
}
