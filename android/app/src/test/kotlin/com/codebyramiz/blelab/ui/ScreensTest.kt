package com.codebyramiz.blelab.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.ConnectionState
import com.codebyramiz.blelab.ble.model.ScannedDevice
import com.codebyramiz.blelab.ui.device.DeviceScreen
import com.codebyramiz.blelab.ui.device.DeviceTab
import com.codebyramiz.blelab.ui.device.DeviceUiState
import com.codebyramiz.blelab.ui.history.HistoryScreen
import com.codebyramiz.blelab.ui.history.HistoryUiState
import com.codebyramiz.blelab.ui.history.SessionDetailScreen
import com.codebyramiz.blelab.ui.history.SessionDetailUiState
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

    /** The clock is paused in [show], so let pending recompositions and animations run after an interaction. */
    private fun settle() = compose.mainClock.advanceTimeBy(500)

    private fun shot(name: String) = compose.onRoot().captureRoboImage("../../docs/screenshots/android/$name.png")

    private fun scan(
        state: ScanUiState,
        granted: Boolean = true,
        dark: Boolean = false,
        onScan: () -> Unit = {},
        onRequest: () -> Unit = {},
        onOpen: (ScannedDevice) -> Unit = {},
        onHistory: () -> Unit = {},
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
            onOpenHistory = onHistory,
        )
    }

    private fun device(
        state: DeviceUiState,
        dark: Boolean = false,
        tab: DeviceTab = DeviceTab.Live,
        onRetry: () -> Unit = {},
        onLed: (Boolean) -> Unit = {},
        onRecord: () -> Unit = {},
        onSend: (String) -> Unit = {},
    ) = show(dark) {
        DeviceScreen(state = state, onBack = {}, onRetry = onRetry, onLedChange = onLed, onToggleRecording = onRecord, onSendMessage = onSend, initialTab = tab)
    }

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
        device(Fixtures.ready, tab = DeviceTab.Board, onLed = { writes += it })
        compose.onNodeWithTag("ledSwitch").performScrollTo().assertIsOn().performClick()
        assertThat(writes).containsExactly(false)
    }

    @Test
    fun deviceLiveLight() {
        val toggles = mutableListOf<Unit>()
        device(Fixtures.ready, onRecord = { toggles += Unit })
        compose.onNodeWithTag("telemetryCard").assertIsDisplayed()
        shot("device_ready_light")
        compose.onNodeWithTag("recordButton").performClick()
        assertThat(toggles).hasSize(1)
    }

    @Test
    fun deviceReadyDark() {
        device(Fixtures.ready, dark = true)
        shot("device_ready_dark")
    }

    @Test
    fun reconnectingShowsCauseAndDisablesLed() {
        device(Fixtures.reconnecting, tab = DeviceTab.Board)
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

    @Test
    fun motionTabShowsTiltAndCharts() {
        device(Fixtures.ready, tab = DeviceTab.Motion)
        compose.onNodeWithTag("tiltCard").assertIsDisplayed()
        compose.onNodeWithTag("accelCard").assertIsDisplayed()
        shot("device_motion_light")
    }

    @Test
    fun motionTabDark() {
        device(Fixtures.ready, dark = true, tab = DeviceTab.Motion)
        shot("device_motion_dark")
    }

    @Test
    fun devKitExplainsMissingMotionSensor() {
        device(Fixtures.devKit, tab = DeviceTab.Motion)
        compose.onNodeWithTag("motionUnavailable").assertIsDisplayed()
        compose.onNodeWithTag("tiltCard").assertDoesNotExist()
    }

    @Test
    fun boardTabShowsTouchesAndSendsMessage() {
        val sent = mutableListOf<String>()
        device(Fixtures.ready, tab = DeviceTab.Board, onSend = { sent += it })
        compose.onNodeWithTag("touchCard").assertIsDisplayed()
        shot("device_board_light")
        compose.onNodeWithTag("sendButton").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("messageField").performScrollTo().performTextInput("Hi board")
        settle()
        compose.onNodeWithTag("sendButton").assertIsEnabled().performClick()
        assertThat(sent).containsExactly("Hi board")
    }

    @Test
    fun boardTabDark() {
        device(Fixtures.ready, dark = true, tab = DeviceTab.Board)
        shot("device_board_dark")
    }

    @Test
    fun devKitHasNoTouchScreen() {
        device(Fixtures.devKit, tab = DeviceTab.Board)
        compose.onNodeWithText("This board has no touch screen.").assertIsDisplayed()
    }

    @Test
    fun overlongMessageCannotBeSent() {
        device(Fixtures.ready, tab = DeviceTab.Board)
        compose.onNodeWithTag("messageField").performScrollTo().performTextInput("x".repeat(65))
        settle()
        compose.onNodeWithTag("sendButton").assertIsNotEnabled()
    }

    @Test
    fun tabsSwitchContent() {
        device(Fixtures.ready)
        compose.onNodeWithTag("tab-Motion").performClick()
        settle()
        compose.onNodeWithTag("tiltCard").assertIsDisplayed()
        compose.onNodeWithTag("tab-Board").performClick()
        settle()
        compose.onNodeWithTag("touchCard").assertIsDisplayed()
    }

    @Test
    fun scanOpensHistory() {
        var opened = false
        scan(Fixtures.scanResults, onHistory = { opened = true })
        compose.onNodeWithTag("historyButton").performClick()
        assertThat(opened).isTrue()
    }

    @Test
    fun historyListsSessionsAndHandlesTaps() {
        var opened: Long? = null
        var deleted: Long? = null
        show { HistoryScreen(Fixtures.history, onBack = {}, onOpenSession = { opened = it }, onDelete = { deleted = it }) }
        shot("history_light")
        compose.onNodeWithTag("session-6").performClick()
        compose.onNodeWithTag("delete-5").performClick()
        assertThat(opened).isEqualTo(6)
        assertThat(deleted).isEqualTo(5)
    }

    @Test
    fun emptyHistoryExplainsHowToRecord() {
        show { HistoryScreen(HistoryUiState(loaded = true), onBack = {}, onOpenSession = {}, onDelete = {}) }
        compose.onNodeWithTag("historyEmpty").assertIsDisplayed()
        shot("history_empty")
    }

    @Test
    fun sessionDetailShowsChartsAndExports() {
        var exported = false
        show { SessionDetailScreen(Fixtures.sessionDetail, onBack = {}, onExport = { exported = true }) }
        compose.onNodeWithTag("telemetryChart").assertIsDisplayed()
        shot("session_detail_light")
        compose.onNodeWithTag("exportButton").performClick()
        assertThat(exported).isTrue()
    }

    @Test
    fun sessionDetailWithoutMotionSaysSo() {
        show { SessionDetailScreen(Fixtures.sessionDetail.copy(acceleration = emptyList(), rotation = emptyList()), onBack = {}, onExport = {}) }
        compose.onAllNodesWithText("No motion data in this session.").assertCountEquals(2)
    }

    @Test
    fun missingSessionShowsMessage() {
        show { SessionDetailScreen(SessionDetailUiState(loading = false), onBack = {}, onExport = {}) }
        compose.onNodeWithTag("sessionMissing").assertIsDisplayed()
        compose.onNodeWithTag("exportButton").assertIsNotEnabled()
    }

    @Test
    fun connectionStatesRenderEveryPill() {
        val address = Fixtures.ready.address
        val states = listOf(
            ConnectionState.Idle,
            ConnectionState.Connecting(address, 1),
            ConnectionState.Discovering(address),
            ConnectionState.Bonding(address),
        )
        var current by mutableStateOf<ConnectionState>(states.first())
        show { DeviceScreen(Fixtures.ready.copy(connection = current), onBack = {}, onRetry = {}, onLedChange = {}, onToggleRecording = {}, onSendMessage = {}, initialTab = DeviceTab.Live) }
        states.forEach {
            current = it
            settle()
            compose.onNodeWithTag("statusPill").assertIsDisplayed()
        }
    }

    @Test
    fun scanningShowsStopButton() {
        scan(ScanUiState(scanning = true))
        compose.onNodeWithTag("scanButton").assertIsDisplayed()
    }

    @Test
    fun scanErrorOffersRetry() {
        var retried = false
        scan(ScanUiState(error = BleError.Unexpected("boom")), onScan = { retried = true })
        compose.onNodeWithTag("scanErrorCard").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertThat(retried).isTrue()
    }
}
