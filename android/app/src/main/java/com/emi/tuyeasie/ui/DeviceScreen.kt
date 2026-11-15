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
import com.alibaba.fastjson.JSON
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
            DataPointsCard(state, viewModel)
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
        if (state.integrationOptions.isEmpty() && state.scanStatus == ScanStatus.IDLE) CardText("Nothing on this device can be added to Home Assistant.")
        state.integrationOptions.forEach { option ->
            val tone = when (option.status) {
                IntegrationStatus.ADDED -> RowTone.OK
                IntegrationStatus.ADDING -> RowTone.WORKING
                IntegrationStatus.FAILED -> RowTone.FAILED
                IntegrationStatus.AVAILABLE -> RowTone.NEUTRAL
            }
            EntityRow(
                icon = { EntityIcon(categoryIcon(state.activatedDevice?.category), tone, busy = option.status == IntegrationStatus.ADDING) },
                title = option.label,
                state = when {
                    option.status == IntegrationStatus.ADDING -> "Adding…"
                    option.status == IntegrationStatus.ADDED -> "Added to Home Assistant"
                    option.status == IntegrationStatus.FAILED -> checkNotNull(option.error)
                    else -> "Not added"
                },
                tone = tone
            ) {
                when (option.status) {
                    IntegrationStatus.ADDED -> CardAction("Open", { viewModel.openIntegration(option.key, uriHandler::openUri) })
                    IntegrationStatus.ADDING -> Unit
                    IntegrationStatus.AVAILABLE, IntegrationStatus.FAILED -> CardAction("Add", { viewModel.chooseIntegration(option.key) })
                }
            }
        }
        state.scanError?.let { InlineError(it) }
    }
    state.flowInputPrompt?.let { DynamicFlowDialog(state, it, viewModel) }
    if (state.integrationChooserVisible) IntegrationChooserDialog(state, viewModel)
}

@Composable
private fun IntegrationChooserDialog(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val selected = state.chooserSelection
    AlertDialog(
        onDismissRequest = viewModel::dismissIntegrationChooser,
        title = { Text("Add to Home Assistant") },
        text = {
            Column {
                state.integrationOptions.filter { it.status == IntegrationStatus.AVAILABLE }.forEach { option ->
                    CheckRow(option.label, option.key in selected) { viewModel.toggleChooserOption(option.key) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::addChosenIntegrations, enabled = selected.isNotEmpty()) {
                Text(if (selected.isEmpty()) "Add" else "Add ${selected.size}")
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissIntegrationChooser) { Text("Skip") } }
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
    }
}

@Composable
private fun DataPointsCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val capabilities = checkNotNull(state.capabilities) { "Data point card without device schema" }
    HaCard(title = "Data points", actions = { CardAction("Read all", viewModel::refreshDataPoints) }) {
        if (capabilities.dataPoints.isEmpty()) CardText("No Tuya data points were reported for this device.")
        capabilities.dataPoints.forEach { schema ->
            val point = checkNotNull(state.manualDataPoints[schema.id]) { "No state for data point ${schema.id}" }
            var value by remember(schema.id, point.value) { mutableStateOf(point.value?.toString().orEmpty()) }
            EntityRow(icon = { EntityIcon(categoryIcon(state.activatedDevice?.category), if (point.error != null) RowTone.FAILED else RowTone.NEUTRAL, busy = point.reading || point.writing) },
                title = "${schema.name} · ${schema.code}",
                state = "DP ${schema.id} · ${schema.type} · ${if (schema.writable) "read/write" else "read-only"}\n${point.error ?: point.value?.toString() ?: "Not read"}",
                tone = if (point.error != null) RowTone.FAILED else RowTone.NEUTRAL) {
                CardAction("Read", { viewModel.readDataPoint(schema.id) }, enabled = !point.reading && !point.writing)
            }
            val editorType = schema.editorType
            when (editorType) {
                "boolean" -> ChoiceRow("${schema.name}: $value", value == "true") { value = (value != "true").toString() }
                "enum" -> {
                    val range = JSON.parseObject(schema.propertyJson).getJSONArray("range")
                    Column(Modifier.padding(start = 16.dp)) {
                        (0 until range.size).forEach { index ->
                            val option = range.getString(index)
                            ChoiceRow(option, option == value) { value = option }
                        }
                    }
                }
                "integer", "number", "string" -> OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(schema.name) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (editorType == "integer" || editorType == "number") KeyboardType.Number else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
                null -> if (schema.writable) CardText("This writable schema has no supported editor and is read-only in Tuyeasie.")
            }
            if (editorType != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                CardAction("Set", { viewModel.setDataPoint(schema.id, value) }, enabled = !point.reading && !point.writing)
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

