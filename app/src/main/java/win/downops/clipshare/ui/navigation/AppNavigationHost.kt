package win.downops.clipshare.ui.navigation

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import win.downops.clipshare.state.DiscoveredDevice
import win.downops.clipshare.state.WifiDirectPeer
import win.downops.clipshare.ui.AccessibilityScreen
import win.downops.clipshare.ui.LogsScreen
import win.downops.clipshare.ui.MainScreen
import win.downops.clipshare.ui.SettingsScreen

/**
 * The swipeable content area of the app.
 *
 * [pagerState] is owned by the caller so the bottom navigation bar and the
 * back handler can keep it in sync.
 */
@Composable
fun AppNavigationHost(
    context: Context,
    screens: List<AppScreen>,
    pagerState: PagerState,
    padding: PaddingValues,
    saveSettings: ((() -> Unit)?) -> Unit,
    onPushText: (String) -> Unit,
    onConnectTo: (DiscoveredDevice) -> Unit,
    onConnectWifiDirect: (WifiDirectPeer) -> Unit,
    onWaitForPeers: () -> Unit,
    onToggle: () -> Unit,
    onClearHistory: () -> Unit,
    onSettingsBack: () -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        userScrollEnabled = true,
        beyondViewportPageCount = 1,
    ) { page ->
        when (screens[page]) {
            AppScreen.Main -> MainScreen(
                onPushText = onPushText,
                onConnectTo = onConnectTo,
                onConnectWifiDirect = onConnectWifiDirect,
                onWaitForPeers = onWaitForPeers,
                onToggle = onToggle,
                onClearHistory = onClearHistory,
            )
            AppScreen.Capture -> AccessibilityScreen(context)
            AppScreen.Logs -> LogsScreen(context)
            AppScreen.Settings -> SettingsScreen(
                context = context,
                onBack = onSettingsBack,
                registerSave = saveSettings,
            )
        }
    }
}
