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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Router
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
import com.emi.tuyeasie.data.HaFlowPrompt
import com.emi.tuyeasie.data.IntegrationStatus
import com.emi.tuyeasie.ui.theme.LocalHaBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(state: OnboardingUiState, viewModel: OnboardingViewModel, iconUrl: String?, onOpenSettings: () -> Unit, onUnpair: (() -> Unit)?) {
    val device = checkNotNull(state.activatedDevice) { "Device screen without a device" }
    val busy = state.activationStatus == ActivationStatus.ACTIVATING || state.onvifStatus == OnvifStatus.CONFIGURING ||
        state.integrationOptions.any { it.status == IntegrationStatus.ADDING }
    val bar = LocalHaBar.current
    var menuOpen by remember { mutableStateOf(false) }
    BackHandler { if (!busy) viewModel.closeDeviceScreen() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bar.container, titleContentColor = bar.content,
                    navigationIconContentColor = bar.content, actionIconContentColor = bar.content),
                navigationIcon = {
                    IconButton(onClick = viewModel::closeDeviceScreen, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(device.name) },
                actions = { if (onUnpair != null) Box {
                    IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More for ${device.name}")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Unpair", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onUnpair() })
                    }
                } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HaCard(title = null) {
                EntityRow(
                    icon = { EntityIcon(categoryIcon(device.category), RowTone.NEUTRAL, imageUrl = iconUrl) },
                    title = device.name,
                    state = listOfNotNull(state.networkReport?.ip, device.category?.let { "category $it" }, state.capabilities?.let { "${it.dataPoints.size} data points" }).joinToString(" · ")
                )
            }
            HomeAssistantIntegrationsCard(state, viewModel, onOpenSettings)
            NetworkCard(state, viewModel)
            DetailsCard(state, viewModel)
            AdvancedCard(state, viewModel)
        }
    }
}

@Composable
private fun HomeAssistantIntegrationsCard(state: OnboardingUiState, viewModel: OnboardingViewModel, onOpenSettings: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    HaCard(title = "Home Assistant") {
        state.connectionError?.let { error ->
            EntityRow(icon = { EntityIcon(Icons.Outlined.Home, RowTone.FAILED) }, title = "Connection", state = error, tone = RowTone.FAILED) {
                CardAction("Settings", onOpenSettings)
            }
        }
        if (state.scanStatus == ScanStatus.SCANNING && state.integrationOptions.isEmpty()) {
            StatusLine(checkNotNull(state.scanProgress) { "Scan is active without progress" }, busy = true)
        }
        if (state.integrationOptions.isEmpty() && state.scanStatus == ScanStatus.IDLE) CardText("No Home Assistant integrations detected yet.")
        state.integrationOptions.forEach { option ->
            val tone = when (option.status) {
                IntegrationStatus.ADDED -> RowTone.OK
                IntegrationStatus.ADDING -> RowTone.WORKING
                IntegrationStatus.FAILED -> RowTone.FAILED
                IntegrationStatus.AVAILABLE, IntegrationStatus.REMOVED -> RowTone.NEUTRAL
            }
            EntityRow(
                icon = { EntityIcon(categoryIcon(state.activatedDevice?.category), tone, busy = option.status == IntegrationStatus.ADDING) },
                title = option.label,
                state = when {
                    option.status == IntegrationStatus.ADDING -> "Adding…"
                    option.status == IntegrationStatus.ADDED -> "Added to Home Assistant"
                    option.status == IntegrationStatus.FAILED -> checkNotNull(option.error)
                    !option.enabled -> checkNotNull(option.unavailableReason)
                    else -> option.detectedService ?: "Available"
                },
                tone = tone
            ) {
                when (option.status) {
                    IntegrationStatus.ADDED -> CardAction("Open", { viewModel.openIntegration(option.key, uriHandler::openUri) })
                    IntegrationStatus.ADDING -> Unit
                    else -> if (option.enabled) CardAction("Add", { viewModel.chooseIntegration(option.key) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            CardAction("Choose integration", viewModel::showIntegrationChooser, enabled = state.scanStatus == ScanStatus.IDLE)
        }
        state.scanError?.let { InlineError(it) }
    }
    state.flowInputPrompt?.let { DynamicFlowDialog(state, it, viewModel) }
    if (state.integrationChooserVisible) IntegrationChooserDialog(state, viewModel)
}

@Composable
private fun IntegrationChooserDialog(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissIntegrationChooser,
        title = { Text("Choose Home Assistant integrations") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.integrationOptions.forEach { option ->
                    ChoiceRow(option.label + if (option.enabled) "" else " · unavailable", state.selectedIntegrationKey == option.key) {
                        if (option.enabled) viewModel.chooseIntegration(option.key)
                    }
                    option.unavailableReason?.let { CardText(it) }
                }
                if (state.integrationOptions.isEmpty()) CardText("No compatible integration detected. You can Skip and rescan later.")
            }
        },
        confirmButton = { TextButton(onClick = viewModel::skipIntegrationChoice) { Text("Skip") } },
        dismissButton = { TextButton(onClick = viewModel::dismissIntegrationChooser) { Text("Cancel") } }
    )
}

@Composable
private fun DynamicFlowDialog(state: OnboardingUiState, prompt: HaFlowPrompt, viewModel: OnboardingViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::cancelFlowPrompt,
        title = { Text("Add ${prompt.handler}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                prompt.fields.forEach { field ->
                    val value = state.flowInputValues[field.name] ?: field.defaultValue.orEmpty()
                    when (field.type) {
                        "boolean" -> ChoiceRow(field.label, value.toBoolean()) { viewModel.setFlowField(field.name, (!value.toBoolean()).toString()) }
                        "select" -> field.options.forEach { option -> ChoiceRow(option, option == value) { viewModel.setFlowField(field.name, option) } }
                        else -> OutlinedTextField(value, { viewModel.setFlowField(field.name, it) }, label = { Text(field.label) }, singleLine = true,
                            visualTransformation = if (field.type == "password") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (field.type in setOf("integer", "number")) KeyboardType.Number else KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                state.flowInputError?.let { InlineError(it) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::submitFlowPrompt) { Text("Continue") } },
        dismissButton = { TextButton(onClick = viewModel::cancelFlowPrompt) { Text("Cancel") } }
    )
}

@Composable
private fun NetworkCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val report = state.networkReport
    val scanning = state.scanStatus == ScanStatus.SCANNING
    HaCard(title = "Network", actions = { CardAction(if (report == null) "Scan" else "Rescan", viewModel::rescanNetwork, enabled = !scanning) }) {
        if (scanning) StatusLine(checkNotNull(state.scanProgress) { "Scanning without progress" }, busy = true)
        state.scanError?.let { InlineError(it) }
        if (report == null) {
            if (!scanning && state.scanError == null) CardText("Not scanned yet.")
            return@HaCard
        }
        EntityRow(icon = { EntityIcon(Icons.Outlined.Router, RowTone.NEUTRAL) }, title = report.ip ?: "LAN address unavailable",
            state = listOfNotNull(report.addressSource?.label, state.capabilities?.mac?.let { "MAC $it" }, "scanned " + DateUtils.getRelativeTimeSpanString(report.scannedAt)).joinToString(" · "))
        if (report.openPorts.isEmpty()) CardText("No open TCP ports.")
        report.openPorts.forEach { port -> EntityRow(icon = { EntityIcon(Icons.Outlined.Lan, RowTone.NEUTRAL) }, title = "${port.port} · ${port.service}", state = listOfNotNull(port.description, port.detail).joinToString("\n")) }
        CardText("All 65535 TCP ports were checked. UDP services are not scanned.")
    }
}

@Composable
private fun DetailsCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val device = checkNotNull(state.activatedDevice) { "Details without a device" }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    fun copy(label: String, value: String) = clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    HaCard(title = "Details") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CopyField("Device ID", device.deviceId) { copy("Tuya device ID", device.deviceId) }
            SecretField("Local key", device.localKey, state.showLocalKey, viewModel::toggleLocalKeyVisibility) { clipboard.setPrimaryClip(sensitiveClip("Tuya local key", device.localKey)) }
            CopyField("Product ID", device.productId) { copy("Tuya product ID", device.productId) }
            device.protocolVersion?.let { CopyField("Protocol version", it) { copy("Tuya protocol version", it) } }
            device.category?.let { CopyField("Category", it) { copy("Tuya category", it) } }
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
    val device = checkNotNull(state.activatedDevice) { "Advanced section without a device" }
    val record = state.onvifRecord
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var username by remember(device.deviceId, record?.username) { mutableStateOf(record?.username ?: CameraOnvifRepository.DEFAULT_USERNAME) }
    var password by remember(device.deviceId, record?.password) { mutableStateOf(record?.password ?: CameraOnvifRepository.DEFAULT_PASSWORD) }
    val info = state.networkReport?.onvif
    val main = record?.mainUrl ?: info?.streams?.firstOrNull()?.url ?: state.networkReport?.portScanStreams?.firstOrNull()?.url
    val sub = record?.subUrl ?: info?.streams?.getOrNull(1)?.url ?: state.networkReport?.portScanStreams?.getOrNull(1)?.url
    if (info == null && record == null && state.capabilities?.tuyaOnvif != true && main == null) return
    HaCard(title = null) {
        Disclosure("Advanced") {
            info?.let { CopyField("ONVIF service URL", it.serviceUrl) { clipboard.setPrimaryClip(ClipData.newPlainText("ONVIF service URL", it.serviceUrl)) } }
            main?.let { CopyField("Main RTSP URL", it) { clipboard.setPrimaryClip(ClipData.newPlainText("Main RTSP URL", it)) } }
            sub?.let { CopyField("Substream RTSP URL", it) { clipboard.setPrimaryClip(ClipData.newPlainText("Substream RTSP URL", it)) } }
            OutlinedTextField(username, { username = it }, label = { Text("ONVIF username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PasswordInput("ONVIF password", password) { password = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CardAction("Save credentials", { viewModel.saveDeviceCredentials(username, password) }, enabled = state.scanStatus == ScanStatus.IDLE)
            }
            if (state.capabilities?.tuyaOnvif == true) {
                var newPassword by remember(device.deviceId) { mutableStateOf("") }
                // The camera rejects a change to the password it already has.
                Text("Set a new ONVIF password on the camera", style = MaterialTheme.typography.bodyMedium)
                PasswordInput("New ONVIF password", newPassword) { newPassword = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CardAction("Set on camera", { viewModel.changeCameraOnvifPassword(newPassword) },
                        enabled = newPassword.isNotBlank() && newPassword != record?.password && state.onvifStatus != OnvifStatus.CONFIGURING)
                }
            }
        }
    }
}

@Composable
private fun PasswordInput(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
}

