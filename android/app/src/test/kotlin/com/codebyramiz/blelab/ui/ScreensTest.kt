package com.codebyramiz.blelab.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codebyramiz.blelab.ble.model.ScannedDevice
import com.codebyramiz.blelab.ui.device.DeviceScreen
import com.codebyramiz.blelab.ui.device.DeviceUiState
import com.codebyramiz.blelab.ui.scan.ScanScreen
import com.codebyramiz.blelab.ui.scan.ScanUiState
import com.codebyramiz.blelab.ui.theme.BleLabTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests on Robolectric. Each test also records a Roborazzi screenshot into
 * docs/screenshots/android when run with -Proborazzi.test.record=true (verified otherwise).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { BleLabTheme(darkTheme = dark, dynamicColor = false, content = content) }
        compose.mainClock.advanceTimeBy(1_200)
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("../../docs/screenshots/android/$name.png")

    private fun scan(
        state: ScanUiState,
        granted: Boolean = true,
        dark: Boolean = false,
        onScan: () -> Unit = {},
        onRequest: () -> Unit = {},
        onOpen: (ScannedDevice) -> Unit = {},
    ) = show(dark) {
        ScanScreen(
            state = state,
            permissionsGranted = granted,
            usesLocation = false,
            onScan = onScan,
            onStop = {},
            onToggleFilter = {},
            onRequestPermissions = onRequest,
            onEnableBluetooth = {},
            onOpenDevice = onOpen,
        )
    }

    private fun device(state: DeviceUiState, dark: Boolean = false, onRetry: () -> Unit = {}, onLed: (Boolean) -> Unit = {}) = show(dark) { DeviceScreen(state = state, onBack = {}, onRetry = onRetry, onLedChange = onLed) }

    @Test
    fun scanResultsLight() {
        var opened: ScannedDevice? = null
        scan(Fixtures.scanResults, onOpen = { opened = it })
        compose.onNodeWithText("BLE Lab (demo)").assertIsDisplayed()
        shot("scan_light")
        compose.onNodeWithTag("device-${Fixtures.devices[1].address}").performClick()
        assertThat(opened).isEqualTo(Fixtures.devices[1])
    }

    @Test
    fun scanResultsDark() {
        scan(Fixtures.scanResults, dark = true)
        shot("scan_dark")
    }

    @Test
    fun permissionCardAsksBeforeScanning() {
        var requested = false
        scan(ScanUiState(), granted = false, onRequest = { requested = true })
        compose.onNodeWithTag("permissionCard").assertIsDisplayed()
        shot("scan_permission")
        compose.onNodeWithText("Grant access").performClick()
        assertThat(requested).isTrue()
    }

    @Test
    fun bluetoothOffCardShows() {
        scan(ScanUiState(bluetoothOn = false))
        compose.onNodeWithTag("bluetoothOffCard").assertIsDisplayed()
    }

    @Test
    fun scanButtonStartsScan() {
        var started = false
        scan(ScanUiState(), onScan = { started = true })
        compose.onNodeWithTag("scanButton").performClick()
        assertThat(started).isTrue()
    }

    @Test
    fun deviceReadyLight() {
        val writes = mutableListOf<Boolean>()
        device(Fixtures.ready, onLed = { writes += it })
        compose.onNodeWithTag("telemetryCard").assertIsDisplayed()
        shot("device_ready_light")
        compose.onNodeWithTag("ledSwitch").performScrollTo().assertIsOn().performClick()
        assertThat(writes).containsExactly(false)
    }

    @Test
    fun deviceReadyDark() {
        device(Fixtures.ready, dark = true)
        shot("device_ready_dark")
    }

    @Test
    fun reconnectingShowsCauseAndDisablesLed() {
        device(Fixtures.reconnecting)
        compose.onNodeWithTag("problemCard").assertIsDisplayed()
        compose.onNodeWithTag("ledSwitch").performScrollTo().assertIsNotEnabled()
        shot("device_reconnecting")
    }

    @Test
    fun failedOffersRetry() {
        var retried = false
        device(Fixtures.failed, onRetry = { retried = true })
        shot("device_failed")
        compose.onNodeWithTag("retryButton").performClick()
        assertThat(retried).isTrue()
    }
}
