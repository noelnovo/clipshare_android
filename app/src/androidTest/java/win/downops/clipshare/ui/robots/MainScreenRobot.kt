package win.downops.clipshare.ui.robots

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import win.downops.clipshare.state.AppState
import win.downops.clipshare.state.DiscoveredDevice
import win.downops.clipshare.ui.MainScreen

/** Robot for interacting with the main screen. */
class MainScreenRobot(
    private val composeRule: AndroidComposeTestRule<*, out ComponentActivity>,
) {

    var lastPushedText: String? = null
        private set
    var lastConnectedDevice: DiscoveredDevice? = null
        private set
    var toggleCount: Int = 0
        private set

    fun setContent(
        onClearHistory: () -> Unit = {},
    ) {
        lastPushedText = null
        lastConnectedDevice = null
        toggleCount = 0
        composeRule.setContent {
            MainScreen(
                onPushText = { lastPushedText = it },
                onConnectTo = { lastConnectedDevice = it },
                onConnectWifiDirect = {},
                onWaitForPeers = {},
                onToggle = { toggleCount++ },
                onClearHistory = onClearHistory,
            )
        }
    }

    fun assertTextDisplayed(text: String, substring: Boolean = false) {
        composeRule.onNodeWithText(text, substring = substring).assertIsDisplayed()
    }

    fun assertTextDoesNotExist(text: String) {
        composeRule.onNodeWithText(text).assertDoesNotExist()
    }

    fun click(text: String) {
        composeRule.onNodeWithText(text).performClick()
    }

    fun typePushText(text: String) {
        composeRule.onNode(hasSetTextAction()).performTextInput(text)
    }

    fun clickSend() = click("Send to server")
    fun clickBroadcast() = click("Broadcast to clients")
    fun clickToggle() {
        composeRule.onNodeWithTag("sync_switch").performClick()
    }

    fun clickDevice(name: String) = click(name)

    fun assertSendEnabled() {
        composeRule.onNodeWithText("Send to server").assertIsEnabled()
    }

    fun assertSendDisabled() {
        composeRule.onNodeWithText("Send to server").assertIsNotEnabled()
    }

    fun assertBroadcastEnabled() {
        composeRule.onNodeWithText("Broadcast to clients").assertIsEnabled()
    }

    fun assertBroadcastDisabled() {
        composeRule.onNodeWithText("Broadcast to clients").assertIsNotEnabled()
    }

    // ------------------------------------------------------------------
    // App-state helpers used to set up the scenario under test
    // ------------------------------------------------------------------

    fun setDiscoveredDevices(devices: List<DiscoveredDevice>) {
        AppState.setDiscovered(devices)
    }

    fun connectTo(name: String, host: String) {
        AppState.onConnected(name, host)
    }

    fun setRunning(running: Boolean) {
        AppState.setRunningForTesting(running)
    }

    fun showError(message: String) {
        AppState.onError(message)
    }
}
