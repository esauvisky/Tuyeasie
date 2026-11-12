package com.emi.tuyeasie.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emi.tuyeasie.data.CameraOnvifRepository
import com.emi.tuyeasie.data.DetectedStream
import com.emi.tuyeasie.data.ImportState
import com.emi.tuyeasie.data.StreamSource
import com.emi.tuyeasie.ui.theme.LocalHaBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    iconUrl: String?,
    onOpenSettings: () -> Unit,
    // Unpairing needs the Tuya account the device lives in, known only when the screen was opened from Devices.
    onUnpair: (() -> Unit)?
) {
    val device = checkNotNull(state.activatedDevice) { "Device screen without a device" }
    val busy = state.activationStatus == ActivationStatus.IMPORTING ||
        state.onvifStatus == OnvifStatus.CONFIGURING ||
        state.streamStatus == StreamStatus.REGISTERING
    val bar = LocalHaBar.current
    var menuOpen by remember { mutableStateOf(false) }
    // Leaving mid-operation would hide a running import or ONVIF run, so the screen holds until it settles.
    BackHandler { if (!busy) viewModel.closeDeviceScreen() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bar.container,
                    titleContentColor = bar.content,
                    navigationIconContentColor = bar.content,
                    actionIconContentColor = bar.content
                ),
                navigationIcon = {
                    IconButton(onClick = viewModel::closeDeviceScreen, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(device.name) },
                actions = {
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
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HaCard(title = null) {
                EntityRow(
                    icon = { EntityIcon(categoryIcon(device.category), RowTone.NEUTRAL, imageUrl = iconUrl) },
                    title = device.name,
                    state = listOfNotNull(
                        state.networkReport?.ip,
                        device.category?.let { "category $it" },
                        state.capabilities?.let { "${it.dataPoints.size} data points" }
                    ).joinToString(" · ")
                )
            }
            HaCard(title = "Home Assistant") {
                HomeAssistantRow(state, viewModel, onOpenSettings)
                if (state.onvifRecord != null && state.onvifStatus != OnvifStatus.CONFIGURING) FeedRow(state, viewModel)
            }
            OnvifCard(state, viewModel)
            NetworkCard(state, viewModel)
            DetailsCard(state, viewModel)
            AdvancedCard(state, viewModel)
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
            title = "Tuya Local",
            state = checkNotNull(state.activationProgress) { "Importing without progress" },
            tone = RowTone.WORKING
        )
        state.tuyaLocalUnavailable != null -> EntityRow(
            icon = { EntityIcon(icon, RowTone.NEUTRAL) },
            title = "Tuya Local",
            state = state.tuyaLocalUnavailable
        )
        entryId != null -> EntityRow(
            icon = { EntityIcon(icon, RowTone.OK) },
            title = "Tuya Local",
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
            title = "Tuya Local",
            state = pending.lastError ?: "Not added yet",
            tone = if (pending.lastError != null) RowTone.FAILED else RowTone.NEUTRAL
        ) { CardAction("Add", viewModel::retryPendingImport) }
        else -> EntityRow(
            icon = { EntityIcon(icon, RowTone.WORKING, busy = true) },
            title = "Tuya Local",
            state = "Checking…",
            tone = RowTone.WORKING
        )
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
private fun OnvifCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val tuyaOnvif = state.capabilities?.tuyaOnvif == true
    val onvif = state.networkReport?.onvif
    if (!tuyaOnvif && onvif == null && state.onvifRecord == null && state.networkReport?.onvifError == null) return
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    val icon = Icons.Outlined.Videocam
    HaCard(title = "ONVIF") {
        if (tuyaOnvif) {
            when (state.onvifStatus) {
                OnvifStatus.CONFIGURING -> EntityRow(
                    icon = { EntityIcon(icon, RowTone.WORKING, busy = true) },
                    title = "Turned on through Tuya",
                    state = checkNotNull(state.onvifProgress) { "ONVIF is configuring without progress" },
                    tone = RowTone.WORKING
                )
                OnvifStatus.FAILED -> EntityRow(
                    icon = { EntityIcon(icon, RowTone.FAILED) },
                    title = "Turned on through Tuya",
                    state = checkNotNull(state.onvifError) { "ONVIF failed without an error" },
                    tone = RowTone.FAILED
                ) { CardAction("Retry", viewModel::retryOnvif) }
                OnvifStatus.COMPLETE -> EntityRow(
                    icon = { EntityIcon(icon, RowTone.OK) },
                    title = "Turned on through Tuya",
                    state = "Enabled at ${checkNotNull(state.onvifRecord) { "ONVIF complete without a record" }.address}",
                    tone = RowTone.OK
                )
                OnvifStatus.IDLE -> EntityRow(
                    icon = { EntityIcon(icon, RowTone.NEUTRAL) },
                    title = "Can be turned on through Tuya",
                    state = "Not set up"
                ) { CardAction("Set up", viewModel::retryOnvif) }
            }
        }
        if (onvif != null) {
            EntityRow(
                icon = { EntityIcon(icon, RowTone.OK) },
                title = listOfNotNull(onvif.manufacturer, onvif.model).joinToString(" ").ifEmpty { "ONVIF device" },
                state = listOfNotNull(
                    onvif.firmware?.let { "firmware $it" },
                    if (onvif.authRequired) "login required" else "no login required"
                ).joinToString(" · "),
                tone = RowTone.OK
            )
        }
        state.networkReport?.onvifError?.let { InlineError("ONVIF query failed: $it") }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onvif != null) {
                CopyField("ONVIF service", onvif.serviceUrl) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("ONVIF service URL", onvif.serviceUrl))
                }
            }
            val streams = onvif?.streams.orEmpty() + state.networkReport?.portScanStreams.orEmpty()
            streams.forEach { stream -> StreamField(stream) { clipboard.setPrimaryClip(ClipData.newPlainText(stream.name, stream.url)) } }
            if (streams.isEmpty() && state.onvifRecord != null) {
                val record = state.onvifRecord
                CopyField("Main stream", record.mainUrl) { clipboard.setPrimaryClip(ClipData.newPlainText("Main RTSP URL", record.mainUrl)) }
                record.subUrl?.let { url ->
                    CopyField("Substream", url) { clipboard.setPrimaryClip(ClipData.newPlainText("Substream RTSP URL", url)) }
                }
            }
        }
    }
}

@Composable
private fun StreamField(stream: DetectedStream, onCopy: () -> Unit) {
    val origin = when (stream.source) {
        StreamSource.ONVIF -> "from ONVIF"
        StreamSource.PORT_SCAN -> "found by port scan"
    }
    Column {
        CopyField(stream.name, stream.url, onCopy)
        Text(
            listOfNotNull(stream.codec, stream.resolution, origin).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp)
        )
    }
}

@Composable
private fun NetworkCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val report = state.networkReport
    val scanning = state.scanStatus == ScanStatus.SCANNING
    HaCard(
        title = "Network",
        actions = { CardAction(if (report == null) "Scan" else "Rescan", viewModel::rescanNetwork, enabled = !scanning) }
    ) {
        if (scanning) StatusLine(checkNotNull(state.scanProgress) { "Scanning without progress" }, busy = true)
        state.scanError?.let { InlineError(it) }
        if (report == null) {
            if (!scanning && state.scanError == null) CardText("Not scanned yet.")
            return@HaCard
        }
        EntityRow(
            icon = { EntityIcon(Icons.Outlined.Router, RowTone.NEUTRAL) },
            title = report.ip,
            state = listOfNotNull(
                report.addressSource.label,
                state.capabilities?.mac?.let { "MAC $it" },
                "scanned " + DateUtils.getRelativeTimeSpanString(report.scannedAt)
            ).joinToString(" · ")
        )
        if (report.openPorts.isEmpty()) CardText("No open TCP ports.")
        report.openPorts.forEach { port ->
            EntityRow(
                icon = { EntityIcon(Icons.Outlined.Lan, RowTone.NEUTRAL) },
                title = "${port.port} · ${port.service}",
                state = listOfNotNull(port.description, port.detail).joinToString("\n")
            )
        }
        CardText("All 65535 TCP ports were checked. UDP services are not scanned.")
    }
}

@Composable
private fun DetailsCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val device = checkNotNull(state.activatedDevice) { "Details without a device" }
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    fun copy(label: String, value: String) = clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    HaCard(title = "Details") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CopyField("Device ID", device.deviceId) { copy("Tuya device ID", device.deviceId) }
            SecretField("Local key", device.localKey, state.showLocalKey, viewModel::toggleLocalKeyVisibility) {
                clipboard.setPrimaryClip(sensitiveClip("Tuya local key", device.localKey))
            }
            CopyField("Product ID", device.productId) { copy("Tuya product ID", device.productId) }
            device.protocolVersion?.let { version -> CopyField("Protocol version", version) { copy("Tuya protocol version", version) } }
        }
        state.capabilities?.let { capabilities ->
            Disclosure("Data points (${capabilities.dataPoints.size})") {
                capabilities.dataPoints.entries.sortedBy { it.value.toInt() }.forEach { (code, id) ->
                    Text("$id  $code", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AdvancedCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val capabilities = state.capabilities ?: return
    val record = state.onvifRecord
    val showsOnvif = capabilities.tuyaOnvif || record != null || state.networkReport?.onvif != null
    if (!showsOnvif) return
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    HaCard(title = null) {
        Disclosure("Advanced") {
            if (record != null) {
                SecretField("ONVIF password (user ${record.username})", record.password, state.showOnvifPassword, viewModel::toggleOnvifPasswordVisibility) {
                    clipboard.setPrimaryClip(sensitiveClip("ONVIF password", record.password))
                }
            }
            if (capabilities.tuyaOnvif) {
                var password by remember { mutableStateOf("") }
                Text(
                    "Sets a new ONVIF password on the camera through Tuya. The default is " +
                        "${CameraOnvifRepository.DEFAULT_USERNAME}/${CameraOnvifRepository.DEFAULT_PASSWORD}.",
                    style = MaterialTheme.typography.bodyMedium
                )
                PasswordInput("New ONVIF password", password) { password = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CardAction(
                        "Change on camera",
                        { viewModel.changeCameraOnvifPassword(password) },
                        enabled = password.isNotBlank() && state.onvifStatus != OnvifStatus.CONFIGURING
                    )
                }
            } else {
                var username by remember { mutableStateOf(record?.username ?: CameraOnvifRepository.DEFAULT_USERNAME) }
                var password by remember { mutableStateOf("") }
                Text(
                    "The login this device's ONVIF and RTSP accept. This only tells the app; the device is not changed.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                PasswordInput("Password", password) { password = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CardAction(
                        "Save and rescan",
                        { viewModel.saveDeviceCredentials(username.trim(), password) },
                        enabled = username.isNotBlank() && state.scanStatus == ScanStatus.IDLE
                    )
                }
            }
        }
    }
}

@Composable
private fun PasswordInput(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}

fun destinationLabel(destination: StreamDestination) = when (destination) {
    StreamDestination.ONVIF -> "ONVIF integration"
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
                viewModel.availableDestinations().forEach { destination ->
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
