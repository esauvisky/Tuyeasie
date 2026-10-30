package io.github.emi.tuyaonboarding

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.emi.tuyaonboarding.ui.MyDevicesScreen
import io.github.emi.tuyaonboarding.ui.MyDevicesViewModel
import io.github.emi.tuyaonboarding.ui.OnboardingScreen
import io.github.emi.tuyaonboarding.ui.OnboardingViewModel
import io.github.emi.tuyaonboarding.ui.theme.OnboardingTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<OnboardingViewModel>()
    private val myDevicesViewModel by viewModels<MyDevicesViewModel>()
    private val scanPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onScanPermissionsResult(hasScanPermissions())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContent {
            OnboardingTheme {
                val onboardingState by viewModel.uiState.collectAsStateWithLifecycle()
                var showMyDevices by rememberSaveable { mutableStateOf(false) }
                val openMyDevices = {
                    showMyDevices = true
                    myDevicesViewModel.refresh()
                }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = !showMyDevices,
                                onClick = { showMyDevices = false },
                                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                                label = { Text("Add device") }
                            )
                            NavigationBarItem(
                                selected = showMyDevices,
                                onClick = openMyDevices,
                                icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                                label = { Text("My Devices") }
                            )
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        if (showMyDevices) {
                            MyDevicesScreen(
                                viewModel = myDevicesViewModel,
                                onBack = { showMyDevices = false },
                                configureEnabled = !onboardingState.busy,
                                onConfigureDevice = { device ->
                                    viewModel.openDeviceSetup(device)
                                    showMyDevices = false
                                }
                            )
                        } else {
                            OnboardingScreen(
                                viewModel = viewModel,
                                onRequestScanPermissions = { scanPermissions.launch(requiredScanPermissions()) },
                                onOpenWifiSettings = {
                                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                },
                                onFinish = openMyDevices
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
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
