@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.emi.tuyaonboarding.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.emi.tuyaonboarding.data.ImportState
import io.github.emi.tuyaonboarding.data.PendingDeviceRecord
import coil.compose.AsyncImage
import io.github.emi.tuyaonboarding.data.TuyaBleDevice
import io.github.emi.tuyaonboarding.data.TuyaHotspot

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onRequestScanPermissions: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onFinish: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.permissionRequestVersion) {
        if (state.permissionRequestVersion > 0) {
            onRequestScanPermissions()
            viewModel.onPermissionRequestConsumed()
        }
    }
    OnboardingContent(
        state = state,
        onHassUrlChange = viewModel::updateHassUrl,
        onAdminTokenChange = viewModel::updateAdminToken,
        onCountryCodeChange = viewModel::updateCountryCode,
        onWifiSsidChange = viewModel::updateHomeWifiSsid,
        onWifiPasswordChange = viewModel::updateHomeWifiPassword,
        onManualModeChange = viewModel::setManualMode,
        onTestConnection = viewModel::testConnection,
        onContinue = viewModel::continueFromConnection,
        onBackToConnection = viewModel::backToConnection,
        onPrepareActivation = viewModel::prepareActivation,
        onScan = viewModel::requestDeviceScan,
        onConnectBleDevice = viewModel::connectToBleDevice,
        onHotspotSsidChange = viewModel::updateManualHotspotSsid,
        onConnectHotspot = viewModel::connectToHotspot,
        onOpenWifiSettings = onOpenWifiSettings,
        onManualActivation = viewModel::startManualActivation,
        onCancelActivation = viewModel::cancelActivation,
        onRetryActivation = viewModel::retryActivation,
        onRetryImport = viewModel::retryPendingImport,
        onShowPendingCredentials = viewModel::showPendingCredentials,
        onToggleLocalKey = viewModel::toggleLocalKeyVisibility,
        onAddAnother = viewModel::addAnotherDevice,
        onFinish = onFinish
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onHassUrlChange: (String) -> Unit,
    onAdminTokenChange: (String) -> Unit,
    onCountryCodeChange: (String) -> Unit,
    onWifiSsidChange: (String) -> Unit,
    onWifiPasswordChange: (String) -> Unit,
    onManualModeChange: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onContinue: () -> Unit,
    onBackToConnection: () -> Unit,
    onPrepareActivation: () -> Unit,
    onScan: () -> Unit,
    onConnectBleDevice: (String) -> Unit,
    onHotspotSsidChange: (String) -> Unit,
    onConnectHotspot: (String) -> Unit,
    onOpenWifiSettings: () -> Unit,
    onManualActivation: () -> Unit,
    onCancelActivation: () -> Unit,
    onRetryActivation: () -> Unit,
    onRetryImport: () -> Unit,
    onShowPendingCredentials: () -> Unit,
    onToggleLocalKey: () -> Unit,
    onAddAnother: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    // Platform "Remove animations" sets the animator duration scale to 0; 1f is the platform default when unset.
    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val imeVisible = WindowInsets.isImeVisible
    BackHandler(enabled = !state.isLoading && state.step != WizardStep.CONNECT && state.step != WizardStep.DONE) {
        when (state.step) {
            WizardStep.HOTSPOT -> onBackToConnection()
            WizardStep.ACTIVATE -> when (state.activationStatus) {
                ActivationStatus.FAILED -> onCancelActivation()
                ActivationStatus.IMPORT_FAILED -> onBackToConnection()
                // Back is consumed while activating or importing; an accidental press would abort pairing.
                ActivationStatus.ACTIVATING, ActivationStatus.IMPORTING -> Unit
                else -> error("Unexpected activation status ${state.activationStatus} on activation step")
            }
            else -> error("Back handler is not enabled for ${state.step}")
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Tuyeasie") },
                    navigationIcon = {
                        if (!state.isLoading && state.step == WizardStep.HOTSPOT) {
                            IconButton(onClick = onBackToConnection) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back to connection settings")
                            }
                        }
                    }
                )
                if (!imeVisible) WizardProgress(state.step)
            }
        },
        bottomBar = {
            if (!state.isLoading && hasActions(state)) {
                ActionArea {
                    when (state.step) {
                        WizardStep.CONNECT -> ConnectionActions(state, onTestConnection, onContinue)
                        WizardStep.HOTSPOT -> HotspotActions(state, imeVisible, onPrepareActivation, onScan, onOpenWifiSettings)
                        WizardStep.ACTIVATE -> ActivationActions(
                            state,
                            onCancelActivation,
                            onRetryActivation,
                            onRetryImport,
                            onShowPendingCredentials,
                            onBackToConnection
                        )
                        WizardStep.DONE -> DoneActions(onFinish, onAddAnother)
                    }
                }
            }
        }
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.size(16.dp))
                Text("Loading encrypted settings…")
            }
        } else {
            AnimatedContent(
                targetState = state.step,
                modifier = Modifier.fillMaxSize().padding(padding),
                transitionSpec = {
                    if (!animate) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 8 * direction } +
                            fadeIn(tween(220, delayMillis = 60))) togetherWith
                            (slideOutHorizontally(tween(150, easing = FastOutSlowInEasing)) { -it / 8 * direction } +
                                fadeOut(tween(150)))
                    }
                },
                label = "Wizard step"
            ) { step ->
                Box(
                    modifier = Modifier.fillMaxSize().semantics { paneTitle = step.title },
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (step) {
                            WizardStep.CONNECT -> ConnectionStep(
                                state = state,
                                onHassUrlChange = onHassUrlChange,
                                onAdminTokenChange = onAdminTokenChange,
                                onCountryCodeChange = onCountryCodeChange,
                                onWifiSsidChange = onWifiSsidChange,
                                onWifiPasswordChange = onWifiPasswordChange,
                                onManualModeChange = onManualModeChange,
                                onRetryImport = onRetryImport,
                                onShowPendingCredentials = onShowPendingCredentials
                            )
                            WizardStep.HOTSPOT -> HotspotStep(
                                state = state,
                                onConnectBleDevice = onConnectBleDevice,
                                onHotspotSsidChange = onHotspotSsidChange,
                                onConnectHotspot = onConnectHotspot,
                                onOpenWifiSettings = onOpenWifiSettings,
                                onManualActivation = onManualActivation
                            )
                            WizardStep.ACTIVATE -> ActivationStep(state)
                            WizardStep.DONE -> DoneStep(
                                state = state,
                                onToggleLocalKey = onToggleLocalKey,
                                onRetryImport = onRetryImport,
                                onBackToConnection = onBackToConnection
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun hasActions(state: OnboardingUiState): Boolean = when (state.step) {
    WizardStep.CONNECT, WizardStep.HOTSPOT, WizardStep.DONE -> true
    WizardStep.ACTIVATE -> state.activationStatus == ActivationStatus.ACTIVATING ||
        state.activationStatus == ActivationStatus.FAILED ||
        state.activationStatus == ActivationStatus.IMPORT_FAILED
}

@Composable
private fun WizardProgress(current: WizardStep) {
    val steps = WizardStep.entries
    val index = steps.indexOf(current)
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 8.dp)
            .semantics {
                contentDescription = "Step ${index + 1} of ${steps.size}: ${current.title}"
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Step ${index + 1} of ${steps.size} · ${current.title}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { (index + 1) / steps.size.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ActionArea(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

@Composable
private fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) {
        Text(text)
    }
}

@Composable
private fun TertiaryAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(text)
    }
}

@Composable
private fun ConnectionActions(
    state: OnboardingUiState,
    onTestConnection: () -> Unit,
    onContinue: () -> Unit
) {
    when {
        state.manualMode -> PrimaryAction("Continue manually", onContinue, enabled = !state.testingConnection)
        !state.connectionVerified -> PrimaryAction(
            text = if (state.testingConnection) "Testing connection…" else "Test connection",
            onClick = onTestConnection,
            enabled = !state.testingConnection,
            busy = state.testingConnection
        )
        else -> PrimaryAction("Continue to hotspot", onContinue)
    }
}

@Composable
private fun HotspotActions(
    state: OnboardingUiState,
    imeVisible: Boolean,
    onPrepare: () -> Unit,
    onScan: () -> Unit,
    onOpenWifiSettings: () -> Unit
) {
    val connecting = state.hotspotStatus == HotspotStatus.CONNECTING
    val scanning = state.hotspotStatus == HotspotStatus.SCANNING
    val canConnect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val anyFound = state.hotspots.isNotEmpty() || state.bleDevices.isNotEmpty()
    when {
        state.hotspotStatus == HotspotStatus.ERROR && !state.activationReady ->
            PrimaryAction("Retry activation setup", onPrepare, icon = Icons.Default.Refresh)
        state.hotspots.isNotEmpty() && !canConnect ->
            PrimaryAction("Open Wi-Fi settings", onOpenWifiSettings, icon = Icons.Default.Settings)
        connecting -> PrimaryAction("Connecting…", onClick = {}, enabled = false, busy = true)
        anyFound -> Unit
        else -> PrimaryAction(
            text = if (scanning) "Scanning…" else "Find device",
            onClick = onScan,
            enabled = state.activationReady && !scanning && !connecting,
            busy = scanning,
            icon = Icons.Default.Wifi
        )
    }
    if (anyFound && !imeVisible) {
        SecondaryAction(
            "Scan again",
            onScan,
            enabled = state.activationReady && !scanning && !connecting
        )
    }
}

@Composable
private fun ActivationActions(
    state: OnboardingUiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRetryImport: () -> Unit,
    onShowCredentials: () -> Unit,
    onBackToConnection: () -> Unit
) {
    when (state.activationStatus) {
        ActivationStatus.ACTIVATING -> SecondaryAction("Cancel activation", onCancel)
        ActivationStatus.FAILED -> {
            PrimaryAction("Retry activation setup", onRetry, icon = Icons.Default.Refresh)
            SecondaryAction("Back to hotspot", onCancel)
        }
        ActivationStatus.IMPORT_FAILED -> {
            PrimaryAction("Retry Home Assistant import", onRetryImport, icon = Icons.Default.Refresh)
            SecondaryAction("View saved credentials", onShowCredentials)
            TertiaryAction("Home Assistant settings", onBackToConnection)
        }
        else -> error("No actions for activation status ${state.activationStatus}")
    }
}

@Composable
private fun DoneActions(onFinish: () -> Unit, onAddAnother: () -> Unit) {
    PrimaryAction("Finish", onFinish)
    SecondaryAction("Add another device", onAddAnother)
}

@Composable
private fun DisclosureSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = { expanded = !expanded })
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
        }
    }
}

@Composable
private fun ConnectionStep(
    state: OnboardingUiState,
    onHassUrlChange: (String) -> Unit,
    onAdminTokenChange: (String) -> Unit,
    onCountryCodeChange: (String) -> Unit,
    onWifiSsidChange: (String) -> Unit,
    onWifiPasswordChange: (String) -> Unit,
    onManualModeChange: (Boolean) -> Unit,
    onRetryImport: () -> Unit,
    onShowPendingCredentials: () -> Unit
) {
    state.pendingDevice?.takeIf { it.state == ImportState.PENDING }?.let { pending ->
        PendingDeviceCard(
            pending = pending,
            onRetryImport = onRetryImport,
            onShowCredentials = onShowPendingCredentials
        )
    }
    SectionCard(
        title = "Home Assistant",
        description = "Use an administrator long-lived access token. Credentials stay in encrypted app storage."
    ) {
        OutlinedTextField(
            value = state.settings.hassUrl,
            onValueChange = onHassUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Home Assistant URL") },
            placeholder = { Text("http://homeassistant.local:8123") },
            singleLine = true,
            enabled = !state.testingConnection,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        OutlinedTextField(
            value = state.settings.adminToken,
            onValueChange = onAdminTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Admin long-lived access token") },
            singleLine = true,
            enabled = !state.testingConnection,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        state.connectionNotice?.let { SuccessBanner(it) }
        state.connectionError?.let { ErrorBanner(it) }
    }
    SectionCard(
        title = "Device and home Wi-Fi",
        description = "Tuya uses the country code for the activation account. The device joins this 2.4 GHz Wi-Fi network."
    ) {
        OutlinedTextField(
            value = state.settings.countryCode,
            onValueChange = onCountryCodeChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Country code") },
            supportingText = { Text("For example, 55 for Brazil or 1 for the United States.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = state.settings.homeWifiSsid,
            onValueChange = onWifiSsidChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Home Wi-Fi name (SSID)") },
            singleLine = true
        )
        OutlinedTextField(
            value = state.settings.homeWifiPassword,
            onValueChange = onWifiPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Home Wi-Fi password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
    }
    DisclosureSection(title = "Advanced", initiallyExpanded = state.manualMode) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = state.manualMode,
                    role = Role.Switch,
                    onValueChange = onManualModeChange
                ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Manual walkthrough", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Activate without automatic import, then copy or import the saved credentials later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = state.manualMode, onCheckedChange = null)
        }
    }
}

@Composable
private fun HotspotStep(
    state: OnboardingUiState,
    onConnectBleDevice: (String) -> Unit,
    onHotspotSsidChange: (String) -> Unit,
    onConnectHotspot: (String) -> Unit,
    onOpenWifiSettings: () -> Unit,
    onManualActivation: () -> Unit
) {
    SectionCard(title = "Put the device in pairing mode") {
        InstructionRow(
            number = "1",
            text = "Power on the device. If it was paired before, reset it first."
        )
        InstructionRow(
            number = "2",
            text = "Hold the pairing button until the light blinks or the device announces pairing mode."
        )
        InstructionRow(
            number = "3",
            text = "Keep it nearby, with this phone’s Wi-Fi, Bluetooth, and Location on."
        )
    }
    state.hotspotMessage?.let { message ->
        StatusBanner(
            text = message,
            busy = state.hotspotStatus == HotspotStatus.PREPARING ||
                state.hotspotStatus == HotspotStatus.SCANNING ||
                state.hotspotStatus == HotspotStatus.CONNECTING
        )
    }
    state.hotspotError?.let { ErrorBanner(it) }
    if (state.hotspots.isNotEmpty()) {
        Text("Available open hotspots", style = MaterialTheme.typography.titleMedium)
        val resultsRequester = remember { BringIntoViewRequester() }
        LaunchedEffect(state.hotspots) { resultsRequester.bringIntoView() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Text(
                "This Android version cannot connect automatically. Open Wi-Fi settings, join the hotspot, return here, then choose Start on current hotspot under Advanced connection.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Column(
            modifier = Modifier.bringIntoViewRequester(resultsRequester),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.hotspots.forEach { hotspot ->
                HotspotCard(
                    hotspot = hotspot,
                    tappable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    enabled = state.activationReady && state.hotspotStatus != HotspotStatus.CONNECTING,
                    onTap = { onConnectHotspot(hotspot.ssid) }
                )
            }
        }
    }
    if (state.bleDevices.isNotEmpty()) {
        Text("Bluetooth devices", style = MaterialTheme.typography.titleMedium)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.bleDevices.forEach { device ->
                BleDeviceCard(
                    device = device,
                    enabled = state.activationReady && state.hotspotStatus != HotspotStatus.CONNECTING,
                    onTap = { onConnectBleDevice(device.uuid) }
                )
            }
        }
    }
    DisclosureSection(
        title = "Advanced connection",
        initiallyExpanded = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    ) {
        Text(
            "If scanning does not find the device, enter its hotspot name or connect in Android Wi-Fi settings. No saved networks or auto-join settings are changed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = state.manualHotspotSsid,
            onValueChange = onHotspotSsidChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Tuya hotspot SSID") },
            singleLine = true
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SecondaryAction(
                "Connect to entered hotspot",
                onClick = { onConnectHotspot(state.manualHotspotSsid.trim()) },
                enabled = state.activationReady && state.manualHotspotSsid.isNotBlank() &&
                    state.hotspotStatus != HotspotStatus.CONNECTING
            )
        } else {
            Text(
                "Automatic hotspot connection needs Android 10 or newer. Use Wi-Fi settings below.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        OutlinedButton(
            onClick = onOpenWifiSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Open Wi-Fi settings")
        }
        Text(
            "After connecting in system settings, return here and start activation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SecondaryAction("Start on current hotspot", onManualActivation, enabled = state.activationReady)
    }
}

@Composable
private fun ActivationStep(state: OnboardingUiState) {
    SectionCard(
        title = if (state.activationStatus == ActivationStatus.IMPORTING)
            "Finishing Home Assistant setup" else "Activating your device",
        description = "Keep the device powered on and in AP mode until activation finishes."
    ) {
        if (state.activationStatus == ActivationStatus.ACTIVATING ||
            state.activationStatus == ActivationStatus.IMPORTING
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Text(
                    state.activationProgress ?: "Working…",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                if (state.activationStatus == ActivationStatus.IMPORTING)
                    "The device is activated. Success is shown only after its Tuya Local entry is loaded."
                else "This can take up to two minutes. Keep this screen open.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.activationError?.let { ErrorBanner(it) }
    }
}

@Composable
private fun DoneStep(
    state: OnboardingUiState,
    onToggleLocalKey: () -> Unit,
    onRetryImport: () -> Unit,
    onBackToConnection: () -> Unit
) {
    val device = checkNotNull(state.activatedDevice) { "Done step requires an activated device" }
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    var copied by remember(device.deviceId) { mutableStateOf<String?>(null) }
    val imported = state.importResult != null
    SectionCard(title = if (imported) "Device is ready" else "Credentials saved") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (imported) Icons.Default.CheckCircle else Icons.Default.LockOpen,
                contentDescription = null,
                tint = if (imported) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(36.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    if (imported) "The Tuya Local config entry is loaded in Home Assistant."
                    else "Home Assistant import is still pending. Your credentials are stored securely for retry.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        state.importResult?.let { result ->
            SuccessBanner(
                when (result.outcome.name) {
                    "ADDED" -> "Added to Home Assistant."
                    "UPDATED" -> "Updated the existing Home Assistant entry."
                    "ALREADY_PRESENT" -> "Already present in Home Assistant; the entry is loaded."
                    else -> "Home Assistant import completed."
                }
            )
            Text("Config entry ID: ${result.entryId}", style = MaterialTheme.typography.bodySmall)
        }
        state.activationError?.let { ErrorBanner(it) }
    }
    if (state.pendingDevice?.state == ImportState.PENDING) {
        SecondaryAction("Retry Home Assistant import", onRetryImport)
        SecondaryAction("Home Assistant settings", onBackToConnection)
    }
    DisclosureSection(title = "View saved credentials") {
        Text(
            "Keep the local key private. Use it only with your own local integrations.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CredentialField(
            label = "Device ID",
            value = device.deviceId,
            onCopy = {
                clipboard.setPrimaryClip(ClipData.newPlainText("Tuya device ID", device.deviceId))
                copied = "Device ID copied"
            }
        )
        OutlinedTextField(
            value = device.localKey,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Local key") },
            readOnly = true,
            singleLine = true,
            visualTransformation = if (state.showLocalKey) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                Row {
                    IconButton(onClick = onToggleLocalKey) {
                        Icon(
                            if (state.showLocalKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (state.showLocalKey) "Hide local key" else "Show local key"
                        )
                    }
                    IconButton(onClick = {
                        val clip = ClipData.newPlainText("Tuya local key", device.localKey)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            clip.description.extras = PersistableBundle().apply {
                                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                        }
                        clipboard.setPrimaryClip(clip)
                        copied = "Local key copied"
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy local key")
                    }
                }
            }
        )
        copied?.let { SuccessBanner(it) }
    }
}

@Composable
private fun PendingDeviceCard(
    pending: PendingDeviceRecord,
    onRetryImport: () -> Unit,
    onShowCredentials: () -> Unit
) {
    OutlinedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Finish your previous device", style = MaterialTheme.typography.titleMedium)
            Text("${pending.device.name} · ${pending.device.deviceId}")
            pending.lastError?.let { ErrorBanner(it) }
            Button(onClick = onRetryImport, modifier = Modifier.fillMaxWidth()) {
                Text("Retry Home Assistant import")
            }
            TextButton(onClick = onShowCredentials, modifier = Modifier.fillMaxWidth()) {
                Text("View saved credentials")
            }
        }
    }
}

@Composable
private fun BleDeviceCard(
    device: TuyaBleDevice,
    enabled: Boolean,
    onTap: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Product info arrives from the cloud after the device is first seen.
            val iconUrl = device.iconUrl
            if (iconUrl == null) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                AsyncImage(
                    model = iconUrl,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.productName ?: "Looking up device…",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Bluetooth · ${device.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HotspotCard(
    hotspot: TuyaHotspot,
    tappable: Boolean,
    enabled: Boolean,
    onTap: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (tappable) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onTap)
                else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Wifi,
                contentDescription = "Signal ${hotspot.signalLevel} of 3, ${hotspot.signalDbm} dBm",
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(hotspot.ssid, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${hotspot.signalDbm} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Icon(
                        Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Open", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}


@Composable
private fun InstructionRow(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(number, style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusBanner(text: String, busy: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SuccessBanner(text: String) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(text) { requester.bringIntoView() }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorBanner(text: String) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(text) { requester.bringIntoView() }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .semantics {
                contentDescription = "Error: $text"
                liveRegion = LiveRegionMode.Assertive
            }
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CredentialField(label: String, value: String, onCopy: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        readOnly = true,
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label")
            }
        }
    )
}
