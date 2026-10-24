package win.downops.clipshare

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
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
import androidx.compose.runtime.collectAsState
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
import win.downops.clipshare.certs.CertStore
import win.downops.clipshare.clipboard.ClipboardPusher
import win.downops.clipshare.logs.Log
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.state.AppState
import win.downops.clipshare.state.WifiDirectPeer
import win.downops.clipshare.ui.CertificatePickerDialog
import win.downops.clipshare.ui.NavSaveIcon
import win.downops.clipshare.ui.navigation.AppBottomBar
import win.downops.clipshare.ui.navigation.AppNavigationHost
import win.downops.clipshare.ui.navigation.AppScreen
import win.downops.clipshare.ui.navigation.index
import win.downops.clipshare.ui.theme.ClipShareTheme
import win.downops.clipshare.util.Constants

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
        handleCertIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCertIntent(intent)
    }

    /** Opens the certificate picker when launched from the tile/notification. */
    private fun handleCertIntent(intent: Intent?) {
        val i = intent ?: return
        val name = i.getStringExtra(Constants.Extras.CERT_PEER_NAME) ?: return
        val address = i.getStringExtra(Constants.Extras.CERT_PEER_ADDRESS).orEmpty()
        if (name.isBlank() && address.isBlank()) return
        AppState.onCertificateRequired(
            WifiDirectPeer(name.ifBlank { address }, address, 0, isGroupOwner = false),
        )
        i.removeExtra(Constants.Extras.CERT_PEER_NAME)
        i.removeExtra(Constants.Extras.CERT_PEER_ADDRESS)
    }

    private fun cancelCertNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(Constants.Notification.CERT_ID)
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
        val pendingCertPeer by AppState.pendingCertPeer.collectAsState()

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
                onConnectWifiDirect = { peer ->
                    val certs = CertStore.clientCerts(context)
                    val selected = Prefs.wifiDirectCertId(context, peer.address, peer.name)
                    val svc = AppState.service
                    when {
                        svc == null -> AppState.onError("Start sync first")
                        certs.isEmpty() -> AppState.onError("No client certificate. Import one in Settings.")
                        selected != null && certs.any { it.id == selected } ->
                            svc.connectToWifiDirectPeer(peer)
                        certs.size == 1 -> {
                            Prefs.setWifiDirectCertId(context, peer.address, peer.name, certs[0].id)
                            svc.connectToWifiDirectPeer(peer)
                        }
                        else -> AppState.onCertificateRequired(peer)
                    }
                },
                onWaitForPeers = {
                    AppState.service?.waitForWifiDirectPeers()
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

        // Global certificate picker: works from the main screen, a notification
        // and the quick-settings tile, independent of the current page.
        pendingCertPeer?.let { peer ->
            CertificatePickerDialog(
                peer = peer,
                certs = CertStore.clientCerts(context),
                selectedId = Prefs.wifiDirectCertId(context, peer.address, peer.name),
                onSelect = { cert ->
                    Prefs.setWifiDirectCertId(context, peer.address, peer.name, cert.id)
                    AppState.clearPendingCert()
                    cancelCertNotification()
                    AppState.service?.connectToWifiDirectPeer(peer)
                },
                onDismiss = {
                    AppState.clearPendingCert()
                    cancelCertNotification()
                    val svc = AppState.service
                    if (svc != null && !AppState.wifiDirectWaiting.value && !AppState.connected.value) {
                        svc.waitForWifiDirectPeers()
                    }
                },
                onOpenSettings = {
                    AppState.clearPendingCert()
                    cancelCertNotification()
                    scope.launch {
                        pagerState.animateScrollToPage(AppScreen.Settings.index(logsEnabled))
                    }
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
