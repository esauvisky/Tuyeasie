package io.github.emi.tuyaonboarding.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.emi.tuyaonboarding.data.ImportState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    onOpenSettings: () -> Unit,
    // Unpairing needs the Tuya account the device lives in, known only when the sheet was opened from Devices.
    onUnpair: (() -> Unit)?
) {
    val device = checkNotNull(state.activatedDevice) { "Device sheet without a device" }
    val busy by rememberUpdatedState(
        state.activationStatus == ActivationStatus.IMPORTING ||
            state.onvifStatus == OnvifStatus.CONFIGURING ||
            state.streamStatus == StreamStatus.REGISTERING
    )
    // Dismissing mid-operation would hide a running import or ONVIF run, so the sheet holds until it settles.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !busy })
    var menuOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!busy) viewModel.closeSheet() }, sheetState = sheetState) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            EntityRow(
                icon = { EntityIcon(categoryIcon(device.category), RowTone.NEUTRAL) },
                title = device.name,
                state = lanAddress(device.ip) ?: state.onvifRecord?.address
            ) {
                if (onUnpair != null) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More for ${device.name}")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Unpair", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; onUnpair() }
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            HomeAssistantRow(state, viewModel, onOpenSettings)
            if (device.category == CAMERA_CATEGORY) CameraRows(state, viewModel, device.deviceId.isNotBlank())
            DetailsSection(state, viewModel)
        }
    }
    if (state.showDestinationDialog) DestinationDialog(state, viewModel)
}

@Composable
private fun HomeAssistantRow(state: OnboardingUiState, viewModel: OnboardingViewModel, onOpenSettings: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val entryId = state.homeAssistantEntryId
    val pending = state.pendingDevice?.takeIf { it.state == ImportState.PENDING }
    val icon = Icons.Outlined.Home
    when {
        state.activationStatus == ActivationStatus.IMPORTING -> EntityRow(
            icon = { EntityIcon(icon, RowTone.WORKING, busy = true) },
            title = "Home Assistant",
            state = checkNotNull(state.activationProgress) { "Importing without progress" },
            tone = RowTone.WORKING
        )
        entryId != null -> EntityRow(
            icon = { EntityIcon(icon, RowTone.OK) },
            title = "Home Assistant",
            state = "Loaded in Tuya Local",
            tone = RowTone.OK
        ) { CardAction("Open", { uriHandler.openUri(state.homeAssistantEntryUrl(entryId)) }) }
        state.connectionError != null -> EntityRow(
            icon = { EntityIcon(icon, RowTone.FAILED) },
            title = "Home Assistant",
            state = state.connectionError,
            tone = RowTone.FAILED
        ) { CardAction("Settings", onOpenSettings) }
        pending != null -> EntityRow(
            icon = { EntityIcon(icon, if (pending.lastError != null) RowTone.FAILED else RowTone.NEUTRAL) },
            title = "Home Assistant",
            state = pending.lastError ?: "Not added yet",
            tone = if (pending.lastError != null) RowTone.FAILED else RowTone.NEUTRAL
        ) { CardAction("Add", viewModel::retryPendingImport) }
        else -> EntityRow(
            icon = { EntityIcon(icon, RowTone.WORKING, busy = true) },
            title = "Home Assistant",
            state = "Checking…",
            tone = RowTone.WORKING
        )
    }
}

@Composable
private fun CameraRows(state: OnboardingUiState, viewModel: OnboardingViewModel, canSetUp: Boolean) {
    val icon = Icons.Outlined.Videocam
    when (state.onvifStatus) {
        OnvifStatus.CONFIGURING -> EntityRow(
            icon = { EntityIcon(icon, RowTone.WORKING, busy = true) },
            title = "ONVIF",
            state = checkNotNull(state.onvifProgress) { "ONVIF is configuring without progress" },
            tone = RowTone.WORKING
        )
        OnvifStatus.FAILED -> {
            EntityRow(
                icon = { EntityIcon(icon, RowTone.FAILED) },
                title = "ONVIF",
                state = checkNotNull(state.onvifError) { "ONVIF failed without an error" },
                tone = RowTone.FAILED
            )
            if (canSetUp) OnvifPasswordField(state, viewModel)
        }
        OnvifStatus.COMPLETE -> EntityRow(
            icon = { EntityIcon(icon, RowTone.OK) },
            title = "ONVIF",
            state = "Enabled at ${checkNotNull(state.onvifRecord) { "ONVIF complete without a record" }.address}",
            tone = RowTone.OK
        )
        OnvifStatus.IDLE -> EntityRow(
            icon = { EntityIcon(icon, RowTone.NEUTRAL) },
            title = "ONVIF",
            state = "Not set up"
        ) { if (canSetUp) CardAction("Set up", viewModel::retryOnvif) }
    }
    if (state.onvifRecord != null && state.onvifStatus != OnvifStatus.CONFIGURING) FeedRow(state, viewModel)
}

@Composable
private fun OnvifPasswordField(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    OutlinedTextField(
        value = state.onvifPasswordInput,
        onValueChange = viewModel::updateOnvifPasswordInput,
        label = { Text("Current ONVIF password") },
        singleLine = true,
        visualTransformation = if (state.showOnvifPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = viewModel::toggleOnvifPasswordVisibility) {
                Icon(
                    if (state.showOnvifPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (state.showOnvifPassword) "Hide ONVIF password" else "Show ONVIF password"
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
        CardAction("Use this password and retry", viewModel::useOnvifPasswordInput, enabled = state.onvifPasswordInput.isNotBlank())
    }
}

@Composable
private fun FeedRow(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val icon = Icons.Outlined.LiveTv
    if (!state.connectionVerified) {
        EntityRow(icon = { EntityIcon(icon, RowTone.NEUTRAL) }, title = "Camera feed", state = "Connect to Home Assistant to add the feed")
        return
    }
    when (state.streamStatus) {
        StreamStatus.IDLE -> EntityRow(
            icon = { EntityIcon(icon, RowTone.NEUTRAL) },
            title = "Camera feed",
            state = "Not in Home Assistant"
        ) { CardAction("Add feed", viewModel::retryStreamRegistration) }
        StreamStatus.REGISTERING -> EntityRow(
            icon = { EntityIcon(icon, RowTone.WORKING, busy = true) },
            title = "Camera feed",
            state = "Adding to Home Assistant…",
            tone = RowTone.WORKING
        )
        StreamStatus.FAILED -> EntityRow(
            icon = { EntityIcon(icon, RowTone.FAILED) },
            title = "Camera feed",
            state = checkNotNull(state.streamError) { "Stream registration failed without an error" },
            tone = RowTone.FAILED
        ) { CardAction("Retry", viewModel::retryStreamRegistration) }
        StreamStatus.COMPLETE -> EntityRow(
            icon = { EntityIcon(icon, RowTone.OK) },
            title = "Camera feed",
            state = "Added via ${destinationLabel(checkNotNull(state.streamDestination) { "Feed added without a destination" })}",
            tone = RowTone.OK
        ) { CardAction("Change", viewModel::retryStreamRegistration) }
    }
}

@Composable
private fun DetailsSection(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val device = checkNotNull(state.activatedDevice) { "Details without a device" }
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    Disclosure("Details") {
        CopyField("Device ID", device.deviceId) { clipboard.setPrimaryClip(ClipData.newPlainText("Tuya device ID", device.deviceId)) }
        SecretField("Local key", device.localKey, state.showLocalKey, viewModel::toggleLocalKeyVisibility) {
            clipboard.setPrimaryClip(sensitiveClip("Tuya local key", device.localKey))
        }
        state.onvifRecord?.takeIf { state.onvifStatus != OnvifStatus.CONFIGURING }?.let { record ->
            CopyField("Main stream", record.mainUrl) { clipboard.setPrimaryClip(ClipData.newPlainText("Main RTSP URL", record.mainUrl)) }
            CopyField("Substream", record.subUrl) { clipboard.setPrimaryClip(ClipData.newPlainText("Substream RTSP URL", record.subUrl)) }
            SecretField("ONVIF password (user admin)", record.password, state.showOnvifPassword, viewModel::toggleOnvifPasswordVisibility) {
                clipboard.setPrimaryClip(sensitiveClip("ONVIF password", record.password))
            }
        }
    }
}

fun destinationLabel(destination: StreamDestination) = when (destination) {
    StreamDestination.TUYA_LOCAL -> "Tuya Local"
    StreamDestination.GENERIC_CAMERA -> "Generic Camera"
    StreamDestination.GO2RTC -> "go2rtc"
}

@Composable
private fun DestinationDialog(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissDestinationDialog,
        title = { Text("Where should the feed go?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StreamDestination.entries.forEach { destination ->
                    ChoiceRow(destinationLabel(destination), state.streamDestination == destination) { viewModel.selectDestination(destination) }
                }
                if (state.streamDestination == StreamDestination.GO2RTC) Go2rtcFields(state, viewModel)
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmDestination, enabled = state.streamDestination != null) { Text("Add feed") }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissDestinationDialog) { Text("Cancel") } }
    )
}

@Composable
fun Go2rtcFields(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    var showPassword by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.settings.go2rtcUrl,
            onValueChange = viewModel::updateGo2rtcUrl,
            label = { Text("go2rtc API URL") },
            placeholder = { Text("http://192.168.1.10:1984") },
            singleLine = true,
            isError = state.go2rtcUrlError != null,
            supportingText = state.go2rtcUrlError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.settings.go2rtcUsername,
            onValueChange = viewModel::updateGo2rtcUsername,
            label = { Text("Username (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.settings.go2rtcPassword,
            onValueChange = viewModel::updateGo2rtcPassword,
            label = { Text("Password (optional)") },
            singleLine = true,
            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
