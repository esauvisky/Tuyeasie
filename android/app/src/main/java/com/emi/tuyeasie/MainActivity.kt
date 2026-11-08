package com.emi.tuyeasie

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emi.tuyeasie.data.SavedTuyaAccount
import com.emi.tuyeasie.data.TuyaManagedDevice
import com.emi.tuyeasie.ui.DeviceSheet
import com.emi.tuyeasie.ui.DevicesScreen
import com.emi.tuyeasie.ui.MyDevicesViewModel
import com.emi.tuyeasie.ui.OnboardingViewModel
import com.emi.tuyeasie.ui.PairScreen
import com.emi.tuyeasie.ui.SettingsScreen
import com.emi.tuyeasie.ui.theme.LocalHaBar
import com.emi.tuyeasie.ui.theme.OnboardingTheme

private enum class Tab(val label: String, val icon: ImageVector) {
    PAIR("Pair", Icons.Outlined.Add),
    DEVICES("Devices", Icons.Outlined.Devices),
    SETTINGS("Settings", Icons.Outlined.Settings)
}

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<OnboardingViewModel>()
    private val myDevicesViewModel by viewModels<MyDevicesViewModel>()
    private val scanPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onScanPermissionsResult(hasScanPermissions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The top bar is dark blue in light mode and near-black in dark mode, so status icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContent {
            OnboardingTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableStateOf(Tab.PAIR) }
                val snackbar = remember { SnackbarHostState() }
                val uriHandler = LocalUriHandler.current
                val bar = LocalHaBar.current
                var sheetSource by remember { mutableStateOf<Pair<SavedTuyaAccount, TuyaManagedDevice>?>(null) }
                LaunchedEffect(state.permissionRequestVersion) {
                    if (state.permissionRequestVersion > 0) {
                        scanPermissions.launch(requiredScanPermissions())
                        viewModel.onPermissionRequestConsumed()
                    }
                }
                LaunchedEffect(state.added) {
                    val added = state.added ?: return@LaunchedEffect
                    val result = snackbar.showSnackbar(
                        "${added.name} added to Home Assistant",
                        actionLabel = "Open",
                        duration = SnackbarDuration.Long
                    )
                    viewModel.consumeAdded()
                    if (result == SnackbarResult.ActionPerformed) uriHandler.openUri(added.homeAssistantUrl)
                }
                LaunchedEffect(state.notice) {
                    val notice = state.notice ?: return@LaunchedEffect
                    snackbar.showSnackbar(notice)
                    viewModel.consumeNotice()
                }
                BackHandler(enabled = tab != Tab.PAIR) { tab = Tab.PAIR }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        val host = Uri.parse(state.settings.hassUrl.trim()).host
                        val showHost = tab == Tab.PAIR && state.connectionVerified && host != null
                        TopAppBar(
                            expandedHeight = if (showHost) 76.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = bar.container,
                                titleContentColor = bar.content,
                                actionIconContentColor = bar.content
                            ),
                            title = {
                                Column {
                                    Text(if (tab == Tab.PAIR) "Tuyeasie" else tab.label)
                                    if (showHost) {
                                        Text("Home Assistant · $host", style = MaterialTheme.typography.bodyMedium, color = bar.subtitle)
                                    }
                                }
                            },
                            actions = {
                                if (tab == Tab.DEVICES) {
                                    val devicesState by myDevicesViewModel.uiState.collectAsStateWithLifecycle()
                                    IconButton(onClick = myDevicesViewModel::refresh, enabled = devicesState.accounts.none { it.loading }) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh devices")
                                    }
                                }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            Tab.entries.forEach { entry ->
                                NavigationBarItem(
                                    selected = tab == entry,
                                    onClick = {
                                        if (entry == Tab.DEVICES && tab != Tab.DEVICES) myDevicesViewModel.refresh()
                                        tab = entry
                                    },
                                    icon = { Icon(entry.icon, contentDescription = null) },
                                    label = { Text(entry.label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    when (tab) {
                        Tab.PAIR -> PairScreen(
                            state = state,
                            viewModel = viewModel,
                            onOpenWifiSettings = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                            padding = padding
                        )
                        Tab.DEVICES -> DevicesScreen(
                            viewModel = myDevicesViewModel,
                            configureEnabled = !state.busy,
                            onConfigureDevice = { account, device ->
                                sheetSource = account to device
                                viewModel.openDeviceSetup(device.activated)
                            },
                            padding = padding
                        )
                        Tab.SETTINGS -> SettingsScreen(state, viewModel, myDevicesViewModel, padding)
                    }
                }
                if (state.sheetOpen) {
                    val source = sheetSource?.takeIf { it.second.deviceId == state.activatedDevice?.deviceId }
                    DeviceSheet(
                        state,
                        viewModel,
                        onOpenSettings = {
                            viewModel.closeSheet()
                            tab = Tab.SETTINGS
                        },
                        onUnpair = source?.let { (account, device) ->
                            {
                                viewModel.closeSheet()
                                tab = Tab.DEVICES
                                myDevicesViewModel.requestUnpair(account, device)
                            }
                        }
                    )
                }
            }
        }
    }

    // uiMode is a handled config change, so a light/dark switch must re-derive the navigation bar scrim here.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
    }

    private fun hasScanPermissions(): Boolean = requiredScanPermissions().all { permission ->
        checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requiredScanPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
