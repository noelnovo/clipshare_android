package win.downops.clipshare

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import win.downops.clipshare.clipboard.ClipboardPusher
import win.downops.clipshare.logs.Log
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.state.AppState
import win.downops.clipshare.ui.NavSaveIcon
import win.downops.clipshare.ui.navigation.AppBottomBar
import win.downops.clipshare.ui.navigation.AppNavigationHost
import win.downops.clipshare.ui.navigation.AppScreen
import win.downops.clipshare.ui.navigation.index
import win.downops.clipshare.ui.theme.ClipShareTheme

class MainActivity : ComponentActivity() {

    private var pendingSyncStart = false

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val wifiPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (pendingSyncStart) {
            val shouldStart = result.values.all { it }
            pendingSyncStart = false
            if (shouldStart) AppState.startSync(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(this)
        Log.i("MainActivity", "onCreate")
        AppState.loadHistory(this)
        AppState.setAppMode(Prefs.appMode(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ClipShareTheme {
                ClipShareApp()
            }
        }
    }

    private fun requestWifiDirectPermissions(): Boolean {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            else add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) {
            pendingSyncStart = true
            wifiPermission.launch(permissions.toTypedArray())
            return false
        }
        return true
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ClipShareApp() {
        val context = LocalContext.current
        val logsEnabled = Prefs.logsEnabled(context)
        val screens = AppScreen.ordered(logsEnabled)
        val pagerState = rememberPagerState(pageCount = { screens.size })
        val scope = rememberCoroutineScope()
        var saveSettings by remember { mutableStateOf<(() -> Unit)?>(null) }

        val currentPage = pagerState.currentPage.coerceAtMost(screens.lastIndex)
        val currentScreen = screens[currentPage]

        // When the Logs screen is removed (advanced toggle), land on a valid page.
        LaunchedEffect(screens.size) {
            if (pagerState.currentPage >= screens.size) {
                pagerState.scrollToPage((screens.size - 1).coerceAtLeast(0))
            }
        }

        BackHandler(enabled = currentScreen != AppScreen.Main) {
            scope.launch { pagerState.animateScrollToPage(AppScreen.Main.index(logsEnabled)) }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(currentScreen.titleRes)) },
                    actions = {
                        if (currentScreen == AppScreen.Settings) {
                            Button(
                                onClick = { saveSettings?.invoke() },
                                enabled = saveSettings != null,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                NavSaveIcon()
                                Spacer(Modifier.width(6.dp))
                                Text("Save")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                AppBottomBar(
                    selected = currentScreen,
                    screens = screens,
                    onSelect = { screen ->
                        scope.launch { pagerState.animateScrollToPage(screens.indexOf(screen)) }
                    },
                )
            }
        ) { padding ->
            AppNavigationHost(
                context = context,
                screens = screens,
                pagerState = pagerState,
                padding = padding,
                saveSettings = { saveSettings = it },
                onPushText = { text -> AppState.service?.send(text) },
                onConnectTo = { device ->
                    val svc = AppState.service
                    if (svc != null) svc.switchTo(device.host, device.port, device.tls) else {
                        Prefs.setServerHost(context, device.host)
                        Prefs.setServerPort(context, device.port)
                        AppState.startSync(context)
                    }
                },
                onToggle = {
                    if (AppState.running.value) {
                        AppState.stopSync(context)
                    } else {
                        val permissionsReady = !Prefs.wifiDirectEnabled(context) || requestWifiDirectPermissions()
                        if (permissionsReady) AppState.startSync(context)
                    }
                },
                onClearHistory = { AppState.clearHistory(context) },
                onSettingsBack = {
                    scope.launch { pagerState.animateScrollToPage(AppScreen.Main.index(logsEnabled)) }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.appInForeground = true
        ClipboardPusher.start(this)
    }

    override fun onPause() {
        super.onPause()
        ClipboardPusher.stop(this)
        AppState.appInForeground = false
    }
}
