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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import io.github.emi.tuyaonboarding.data.AccountAuth
import io.github.emi.tuyaonboarding.data.ImportState
import io.github.emi.tuyaonboarding.data.NearbyNetwork
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
        onWifiSsidChange = viewModel::updateHomeWifiSsid,
        onRefreshNetworks = viewModel::refreshNetworks,
        onWifiPasswordChange = viewModel::updateHomeWifiPassword,
        onTestConnection = viewModel::testConnection,
        onContinue = viewModel::continueFromConnection,
        onBackToConnection = viewModel::backToConnection,
        onPrepareActivation = viewModel::prepareActivation,
        onScan = viewModel::requestDeviceScan,
        onConnectBleDevice = viewModel::connectToBleDevice,
        onCameraEmailChange = viewModel::updateCameraEmail,
        onCameraPasswordChange = viewModel::updateCameraPassword,
        onCameraAuthChange = viewModel::updateCameraAuth,
        onConfirmCameraAccount = viewModel::confirmCameraAccount,
        onDismissCameraAccount = viewModel::dismissCameraAccount,
        onConnectHotspot = viewModel::connectToHotspot,
        onOpenWifiSettings = onOpenWifiSettings,
        onManualActivation = viewModel::startManualActivation,
        onCancelActivation = viewModel::cancelActivation,
        onRetryActivation = viewModel::retryActivation,
        onRetryImport = viewModel::retryPendingImport,
        onShowPendingCredentials = viewModel::showPendingCredentials,
        onToggleLocalKey = viewModel::toggleLocalKeyVisibility,
        onRetryOnvif = viewModel::retryOnvif,
        onToggleOnvifPassword = viewModel::toggleOnvifPasswordVisibility,
        onSelectDestination = viewModel::selectDestination,
        onGo2rtcUrlChange = viewModel::updateGo2rtcUrl,
        onGo2rtcUsernameChange = viewModel::updateGo2rtcUsername,
        onGo2rtcPasswordChange = viewModel::updateGo2rtcPassword,
        onConfirmDestination = viewModel::confirmDestination,
        onDismissDestination = viewModel::dismissDestinationDialog,
        onRetryStreams = viewModel::retryStreamRegistration,
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
    onWifiSsidChange: (String) -> Unit,
    onRefreshNetworks: (Boolean) -> Unit,
    onWifiPasswordChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onContinue: () -> Unit,
    onBackToConnection: () -> Unit,
    onPrepareActivation: () -> Unit,
    onScan: () -> Unit,
    onConnectBleDevice: (String) -> Unit,
    onCameraEmailChange: (String) -> Unit,
    onCameraPasswordChange: (String) -> Unit,
    onCameraAuthChange: (AccountAuth) -> Unit,
    onConfirmCameraAccount: () -> Unit,
    onDismissCameraAccount: () -> Unit,
    onConnectHotspot: (String) -> Unit,
    onOpenWifiSettings: () -> Unit,
    onManualActivation: () -> Unit,
    onCancelActivation: () -> Unit,
    onRetryActivation: () -> Unit,
    onRetryImport: () -> Unit,
    onShowPendingCredentials: () -> Unit,
    onToggleLocalKey: () -> Unit,
    onRetryOnvif: () -> Unit,
    onToggleOnvifPassword: () -> Unit,
    onSelectDestination: (StreamDestination) -> Unit,
    onGo2rtcUrlChange: (String) -> Unit,
    onGo2rtcUsernameChange: (String) -> Unit,
    onGo2rtcPasswordChange: (String) -> Unit,
    onConfirmDestination: () -> Unit,
    onDismissDestination: () -> Unit,
    onRetryStreams: () -> Unit,
    onAddAnother: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    // Platform "Remove animations" sets the animator duration scale to 0; 1f is the platform default when unset.
    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val imeVisible = WindowInsets.isImeVisible
    if (state.cameraAccountDevice != null) {
        CameraAccountDialog(state, onCameraEmailChange, onCameraPasswordChange, onCameraAuthChange, onConfirmCameraAccount, onDismissCameraAccount)
    }
    if (state.showDestinationDialog) {
        DestinationDialog(state, onSelectDestination, onGo2rtcUrlChange, onGo2rtcUsernameChange, onGo2rtcPasswordChange, onConfirmDestination, onDismissDestination)
    }
    BackHandler(enabled = !state.isLoading && state.step != WizardStep.CONNECT && state.step != WizardStep.DONE) {
        when (state.step) {
            // Back is consumed while switching Tuya accounts; leaving would race the pending activation.
            WizardStep.HOTSPOT -> if (!state.preparingCameraAccount) onBackToConnection()
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
                            IconButton(onClick = onBackToConnection, enabled = !state.preparingCameraAccount) {
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
                                onWifiSsidChange = onWifiSsidChange,
                                onRefreshNetworks = onRefreshNetworks,
                                onWifiPasswordChange = onWifiPasswordChange
                            )
                            WizardStep.HOTSPOT -> HotspotStep(
                                state = state,
                                onConnectBleDevice = onConnectBleDevice,
                                onRefreshNetworks = onRefreshNetworks,
                                onConnectHotspot = onConnectHotspot,
                                onOpenWifiSettings = onOpenWifiSettings,
                                onManualActivation = onManualActivation
                            )
                            WizardStep.ACTIVATE -> ActivationStep(state)
                            WizardStep.DONE -> DoneStep(
                                state = state,
                                onToggleLocalKey = onToggleLocalKey,
                                onRetryImport = onRetryImport,
                                onBackToConnection = onBackToConnection,
                                onRetryOnvif = onRetryOnvif,
                                onToggleOnvifPassword = onToggleOnvifPassword,
                                onRetryStreams = onRetryStreams
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
        else -> Unit
    }
    if ((anyFound || state.activationReady) && !imeVisible) {
        SecondaryAction(
            "Scan again",
            onScan,
            enabled = state.activationReady && !scanning && !connecting
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraAccountDialog(
    state: OnboardingUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAuthChange: (AccountAuth) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val busy = state.preparingCameraAccount
    var showPassword by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Camera account") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "IF YOU LOSE THIS EMAIL AND PASSWORD, YOU WON'T BE ABLE TO RESET THIS CAMERA AGAIN.",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Text(
                            "Either is fine, as long as you remember this email and password or never uninstall Tuyeasie.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                val choices = listOf(
                    AccountAuth.LOGIN to "Existing account",
                    AccountAuth.REGISTER to "New account"
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    choices.forEachIndexed { index, (auth, label) ->
                        SegmentedButton(
                            selected = state.cameraAuth == auth,
                            onClick = { onAuthChange(auth) },
                            enabled = !busy,
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                            label = {
                                Text(
                                    label,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = state.cameraEmail,
                    onValueChange = onEmailChange,
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.cameraPassword,
                    onValueChange = onPasswordChange,
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (busy) StatusBanner(
                    if (state.cameraAuth == AccountAuth.REGISTER) "Creating the camera account…" else "Signing in to the camera account…",
                    busy = true
                )
                state.cameraAccountError?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy && state.cameraEmail.trim().contains("@") && state.cameraPassword.isNotBlank()
            ) { Text("Save and pair") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        }
    )
}

@Composable
private fun DestinationDialog(
    state: OnboardingUiState,
    onSelect: (StreamDestination) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register camera streams") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Both the main stream and the substream were verified. Choose where Home Assistant should get them.",
                    style = MaterialTheme.typography.bodyMedium
                )
                listOf(
                    StreamDestination.TUYA_LOCAL to "Tuya Local device (with controls)",
                    StreamDestination.GENERIC_CAMERA to "Generic Camera",
                    StreamDestination.GO2RTC to "go2rtc"
                ).forEach { (destination, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.streamDestination == destination,
                                onClick = { onSelect(destination) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = state.streamDestination == destination, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (state.streamDestination == StreamDestination.GO2RTC) {
                    OutlinedTextField(
                        value = state.go2rtcUrl,
                        onValueChange = onUrlChange,
                        label = { Text("go2rtc API URL") },
                        placeholder = { Text("http://192.168.1.10:1984") },
                        singleLine = true,
                        isError = state.go2rtcUrlError != null,
                        supportingText = state.go2rtcUrlError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.go2rtcUsername,
                        onValueChange = onUsernameChange,
                        label = { Text("Username (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.go2rtcPassword,
                        onValueChange = onPasswordChange,
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = state.streamDestination != null &&
                    (state.streamDestination != StreamDestination.GO2RTC || state.go2rtcUrl.isNotBlank())
            ) { Text("Register") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
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
    onWifiSsidChange: (String) -> Unit,
    onRefreshNetworks: (Boolean) -> Unit,
    onWifiPasswordChange: (String) -> Unit
) {
    SectionCard(
        title = "Home Assistant",
        descriptionContent = {
            val uriHandler = LocalUriHandler.current
            val hassUrl = state.settings.hassUrl.trim()
            val text = buildAnnotatedString {
                append("Use an administrator long-lived access token. Credentials stay in encrypted app storage. ")
                if (hassUrl.isNotEmpty()) {
                    val link = LinkAnnotation.Clickable(
                        tag = "token-help",
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                    ) { uriHandler.openUri(hassUrl.trimEnd('/') + "/profile/security") }
                    withLink(link) { append("Where do I create a token?") }
                }
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
    var passwordDialogSsid by rememberSaveable { mutableStateOf<String?>(null) }
    var showWifiPassword by rememberSaveable { mutableStateOf(true) }
    SectionCard(
        title = "Device and home Wi-Fi",
        description = "The device joins this 2.4 GHz Wi-Fi network."
    ) {
        if (state.settings.homeWifiSsid.isNotBlank()) {
            OutlinedTextField(
                value = state.settings.homeWifiPassword,
                onValueChange = onWifiPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password for ${state.settings.homeWifiSsid}") },
                singleLine = true,
                visualTransformation = if (showWifiPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showWifiPassword = !showWifiPassword }) {
                        Icon(
                            if (showWifiPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showWifiPassword) "Hide password" else "Show password"
                        )
                    }
                }
            )
        }
        NetworkPicker(
            state = state,
            title = "Home Wi-Fi name (SSID)",
            selectedSsid = state.settings.homeWifiSsid,
            require24Ghz = true,
            enabled = true,
            onRefresh = onRefreshNetworks,
            onSelect = { ssid ->
                if (ssid != state.settings.homeWifiSsid) {
                    onWifiPasswordChange("")
                }
                onWifiSsidChange(ssid)
                passwordDialogSsid = ssid
            }
        )
    }
    passwordDialogSsid?.let { ssid ->
        AlertDialog(
            onDismissRequest = { passwordDialogSsid = null },
            title = { Text("Password for $ssid") },
            text = {
                OutlinedTextField(
                    value = state.settings.homeWifiPassword,
                    onValueChange = onWifiPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Home Wi-Fi password") },
                    singleLine = true,
                    visualTransformation = if (showWifiPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showWifiPassword = !showWifiPassword }) {
                            Icon(
                                if (showWifiPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showWifiPassword) "Hide password" else "Show password"
                            )
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { passwordDialogSsid = null }) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
private fun HotspotStep(
    state: OnboardingUiState,
    onConnectBleDevice: (String) -> Unit,
    onRefreshNetworks: (Boolean) -> Unit,
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
            "If scanning does not find the device, pick its hotspot from the nearby networks or connect in Android Wi-Fi settings. No saved networks or auto-join settings are changed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            NetworkPicker(
                state = state,
                title = "Nearby networks",
                selectedSsid = null,
                require24Ghz = false,
                enabled = state.activationReady && state.hotspotStatus != HotspotStatus.CONNECTING,
                onRefresh = onRefreshNetworks,
                onSelect = onConnectHotspot
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
    onBackToConnection: () -> Unit,
    onRetryOnvif: () -> Unit,
    onToggleOnvifPassword: () -> Unit,
    onRetryStreams: () -> Unit
) {
    val device = checkNotNull(state.activatedDevice) { "Done step requires an activated device" }
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    var copied by remember(device.deviceId) { mutableStateOf<String?>(null) }
    val imported = state.pendingDevice?.state == ImportState.IMPORTED
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
        state.connectionError?.let { ErrorBanner(it) }
    }
    if (state.pendingDevice?.state == ImportState.PENDING) {
        SecondaryAction("Import into Home Assistant", onRetryImport)
    }
    if (state.pendingDevice?.state == ImportState.PENDING || state.connectionError != null) {
        SecondaryAction("Home Assistant settings", onBackToConnection)
    }
    if (state.openedFromDeviceSettings || state.onvifStatus == OnvifStatus.COMPLETE) {
        OnvifCard(state, clipboard, onRetryOnvif, onToggleOnvifPassword, onRetryStreams, device.deviceId.isNotBlank())
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
private fun OnvifCard(
    state: OnboardingUiState,
    clipboard: ClipboardManager,
    onRetryOnvif: () -> Unit,
    onToggleOnvifPassword: () -> Unit,
    onRetryStreams: () -> Unit,
    canRetryOnvif: Boolean
) {
    var copied by remember(state.onvifRecord) { mutableStateOf<String?>(null) }
    SectionCard(title = "Camera streams") {
        when (state.onvifStatus) {
            OnvifStatus.CONFIGURING -> StatusBanner(
                "Configuring ONVIF and verifying both RTSP streams. This can take up to a minute.",
                busy = true
            )
            OnvifStatus.FAILED -> {
                ErrorBanner("ONVIF setup failed. The Tuya Local setup above is unaffected. ${state.onvifError}")
                if (canRetryOnvif) SecondaryAction("Retry ONVIF", onRetryOnvif)
            }
            OnvifStatus.COMPLETE -> {
                val record = checkNotNull(state.onvifRecord) { "ONVIF is complete but has no record" }
                SuccessBanner("ONVIF is enabled at ${record.address} and both RTSP streams were verified.")
                CredentialField(
                    label = "Main stream URL",
                    value = record.mainUrl,
                    onCopy = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Main RTSP URL", record.mainUrl))
                        copied = "Main stream URL copied"
                    }
                )
                CredentialField(
                    label = "Substream URL",
                    value = record.subUrl,
                    onCopy = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Substream RTSP URL", record.subUrl))
                        copied = "Substream URL copied"
                    }
                )
                OutlinedTextField(
                    value = "admin",
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ONVIF username") },
                    readOnly = true,
                    singleLine = true
                )
                OutlinedTextField(
                    value = record.password,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ONVIF password") },
                    readOnly = true,
                    singleLine = true,
                    visualTransformation = if (state.showOnvifPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = onToggleOnvifPassword) {
                                Icon(
                                    if (state.showOnvifPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (state.showOnvifPassword) "Hide ONVIF password"
                                    else "Show ONVIF password"
                                )
                            }
                            IconButton(onClick = {
                                val clip = ClipData.newPlainText("ONVIF password", record.password)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    clip.description.extras = PersistableBundle().apply {
                                        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                                    }
                                }
                                clipboard.setPrimaryClip(clip)
                                copied = "ONVIF password copied"
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy ONVIF password")
                            }
                        }
                    }
                )
                copied?.let { SuccessBanner(it) }
                StreamRegistrationStatus(state, onRetryStreams)
            }
            OnvifStatus.IDLE -> {
                Text(
                    "Set up ONVIF and register RTSP camera streams whenever you need them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (canRetryOnvif) SecondaryAction("Set up ONVIF", onRetryOnvif)
            }
        }
    }
}

@Composable
private fun StreamRegistrationStatus(state: OnboardingUiState, onRetryStreams: () -> Unit) {
    if (!state.connectionVerified) {
        Text(
            "Home Assistant registration was not attempted because no verified Home Assistant connection exists.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    when (state.streamStatus) {
        StreamStatus.IDLE -> SecondaryAction("Choose stream destination", onRetryStreams)
        StreamStatus.REGISTERING -> StatusBanner("Registering camera streams in Home Assistant…", busy = true)
        StreamStatus.FAILED, StreamStatus.COMPLETE -> {
            Text(
                "Main stream: " + if (state.mainEntryId != null) "registered (${state.mainEntryId})" else "not registered",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Substream: " + if (state.subEntryId != null) "registered (${state.subEntryId})" else "not registered",
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.streamStatus == StreamStatus.FAILED) {
                state.mainError?.let { ErrorBanner("Main stream: $it") }
                state.subError?.let { ErrorBanner("Substream: $it") }
                SecondaryAction("Retry stream registration", onRetryStreams)
            } else {
                SuccessBanner("Both streams are registered in Home Assistant.")
                SecondaryAction("Change stream destination", { onRetryStreams() })
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
private fun NetworkPicker(
    state: OnboardingUiState,
    title: String,
    selectedSsid: String?,
    require24Ghz: Boolean,
    enabled: Boolean,
    onRefresh: (Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh(false)
            delay(NETWORK_REFRESH_MILLIS)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onRefresh(true) }) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh nearby networks")
        }
    }
    if (selectedSsid != null) {
        Text(
            if (selectedSsid.isBlank()) "No network selected." else "Selected: $selectedSsid",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    state.networksError?.let { ErrorBanner(it) }
    if (state.networksError == null && state.networks.isEmpty()) {
        Text(
            "No networks found yet. Scanning every 10 seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.networks.forEach { network ->
            val unsupported = require24Ghz && !network.has24Ghz
            NetworkRow(
                network = network,
                selected = selectedSsid == network.ssid,
                unsupported = unsupported,
                enabled = enabled && !unsupported,
                onTap = { onSelect(network.ssid) }
            )
        }
    }
}

@Composable
private fun NetworkRow(
    network: NearbyNetwork,
    selected: Boolean,
    unsupported: Boolean,
    enabled: Boolean,
    onTap: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onTap),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Wifi,
                contentDescription = "Signal ${network.signalLevel} of 3, ${network.signalDbm} dBm",
                tint = if (unsupported) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    network.ssid,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (unsupported) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (unsupported) "${network.signalDbm} dBm, 5 GHz only: not supported, Tuya needs 2.4 GHz"
                    else "${network.signalDbm} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unsupported) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val NETWORK_REFRESH_MILLIS = 10_000L

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
    descriptionContent: (@Composable () -> Unit)? = null,
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
            descriptionContent?.invoke()
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
