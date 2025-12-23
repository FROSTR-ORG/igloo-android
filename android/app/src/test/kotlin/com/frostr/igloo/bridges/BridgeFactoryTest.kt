package com.frostr.igloo.bridges

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for BridgeFactory.
 *
 * Note: Tests that would create StorageBridge are skipped because
 * EncryptedSharedPreferences requires Android Keystore which isn't
 * available in Robolectric. These are tested via integration tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BridgeFactoryTest {

    @Test
    fun `BridgeContext enum has expected values`() {
        val values = BridgeFactory.BridgeContext.values()
        assertThat(values).hasLength(2)
        assertThat(values).asList().containsExactly(
            BridgeFactory.BridgeContext.MAIN_ACTIVITY,
            BridgeFactory.BridgeContext.FOREGROUND_SERVICE
        )
    }

    @Test
    fun `BridgeContext MAIN_ACTIVITY name is correct`() {
        assertThat(BridgeFactory.BridgeContext.MAIN_ACTIVITY.name)
            .isEqualTo("MAIN_ACTIVITY")
    }

    @Test
    fun `BridgeContext FOREGROUND_SERVICE name is correct`() {
        assertThat(BridgeFactory.BridgeContext.FOREGROUND_SERVICE.name)
            .isEqualTo("FOREGROUND_SERVICE")
    }

    @Test
    fun `BridgeContext valueOf returns correct values`() {
        assertThat(BridgeFactory.BridgeContext.valueOf("MAIN_ACTIVITY"))
            .isEqualTo(BridgeFactory.BridgeContext.MAIN_ACTIVITY)
        assertThat(BridgeFactory.BridgeContext.valueOf("FOREGROUND_SERVICE"))
            .isEqualTo(BridgeFactory.BridgeContext.FOREGROUND_SERVICE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `BridgeContext valueOf throws for invalid value`() {
        BridgeFactory.BridgeContext.valueOf("INVALID")
    }

    // Note: Tests that create actual bridges (StorageBridge, etc.) require
    // Android Keystore and are tested via instrumented tests instead.
    // The factory pattern itself is simple constructor delegation.
}
