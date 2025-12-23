package com.frostr.igloo.di

import android.app.Activity
import com.frostr.igloo.bridges.BridgeFactory
import com.frostr.igloo.health.IglooHealthManager
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for AppContainer.
 *
 * Note: Tests that access storageBridge or permissionChecker are skipped
 * because they require EncryptedSharedPreferences which needs Android Keystore.
 * Those are tested via instrumented tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppContainerTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        // Reset container before each test
        AppContainer.reset()
        activity = Robolectric.buildActivity(Activity::class.java).create().get()
    }

    @After
    fun tearDown() {
        // Clean up after each test
        AppContainer.reset()
    }

    @Test
    fun `getInstance returns singleton`() {
        val instance1 = AppContainer.getInstance(activity)
        val instance2 = AppContainer.getInstance(activity)

        assertThat(instance1).isSameInstanceAs(instance2)
    }

    @Test
    fun `getInstance with different contexts returns same instance`() {
        val context1 = Robolectric.buildActivity(Activity::class.java).create().get()
        val context2 = Robolectric.buildActivity(Activity::class.java).create().get()

        val instance1 = AppContainer.getInstance(context1)
        val instance2 = AppContainer.getInstance(context2)

        // Should return same singleton (uses applicationContext)
        assertThat(instance1).isSameInstanceAs(instance2)
    }

    @Test
    fun `setTestInstance overrides singleton`() {
        // Get the normal singleton first
        val normalInstance = AppContainer.getInstance(activity)

        // Create a test instance
        val testContainer = AppContainer(activity)
        AppContainer.setTestInstance(testContainer)

        // Should now return test instance
        val retrievedInstance = AppContainer.getInstance(activity)
        assertThat(retrievedInstance).isSameInstanceAs(testContainer)
        assertThat(retrievedInstance).isNotSameInstanceAs(normalInstance)
    }

    @Test
    fun `reset clears all instances`() {
        val instance1 = AppContainer.getInstance(activity)
        AppContainer.reset()
        val instance2 = AppContainer.getInstance(activity)

        assertThat(instance1).isNotSameInstanceAs(instance2)
    }

    @Test
    fun `reset clears test instance`() {
        val testContainer = AppContainer(activity)
        AppContainer.setTestInstance(testContainer)

        AppContainer.reset()

        val retrievedInstance = AppContainer.getInstance(activity)
        assertThat(retrievedInstance).isNotSameInstanceAs(testContainer)
    }

    @Test
    fun `createBridgeFactory returns new instance each time`() {
        val container = AppContainer.getInstance(activity)

        val factory1 = container.createBridgeFactory()
        val factory2 = container.createBridgeFactory()

        assertThat(factory1).isNotNull()
        assertThat(factory2).isNotNull()
        assertThat(factory1).isNotSameInstanceAs(factory2)
    }

    @Test
    fun `createBridgeFactory returns BridgeFactory instance`() {
        val container = AppContainer.getInstance(activity)

        val factory = container.createBridgeFactory()

        assertThat(factory).isInstanceOf(BridgeFactory::class.java)
    }

    @Test
    fun `setTestInstance with null clears test instance`() {
        val testContainer = AppContainer(activity)
        AppContainer.setTestInstance(testContainer)

        // Verify test instance is active
        assertThat(AppContainer.getInstance(activity)).isSameInstanceAs(testContainer)

        // Clear test instance
        AppContainer.setTestInstance(null)

        // Should now fall back to regular singleton
        val instance = AppContainer.getInstance(activity)
        assertThat(instance).isNotSameInstanceAs(testContainer)
    }

    // Note: Tests that access storageBridge or permissionChecker require
    // Android Keystore and are tested via instrumented tests instead.

    // ==================== NIP-55 Components ====================

    @Test
    fun `healthManager returns singleton`() {
        val container = AppContainer.getInstance(activity)

        val manager1 = container.healthManager
        val manager2 = container.healthManager

        assertThat(manager1).isSameInstanceAs(manager2)
    }

    @Test
    fun `healthManager is IglooHealthManager instance`() {
        val container = AppContainer.getInstance(activity)

        val manager = container.healthManager

        assertThat(manager).isSameInstanceAs(IglooHealthManager)
    }
}
