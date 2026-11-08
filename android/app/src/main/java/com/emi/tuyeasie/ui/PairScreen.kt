package com.emi.tuyeasie.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.emi.tuyeasie.data.AccountAuth

@Composable
fun PairScreen(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    onOpenWifiSettings: () -> Unit,
    padding: PaddingValues
) {
    if (state.cameraAccountDevice != null) CameraAccountDialog(state, viewModel)
    if (state.isLoading) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.stage == PairStage.SETUP) SetupCard(state, viewModel) else NearbyCard(state, viewModel, onOpenWifiSettings)
    }
}

@Composable
private fun SetupCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    HaCard(title = "Connect Home Assistant") {
        CardText("Devices are imported into Tuya Local. Use an administrator long-lived access token.")
        HomeAssistantFields(state, viewModel)
        WifiFields(state, viewModel)
        state.connectionError?.let { InlineError(it) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (state.testingConnection) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            CardAction("Connect", viewModel::testConnection, enabled = !state.testingConnection)
        }
    }
}

@Composable
fun HomeAssistantFields(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.settings.hassUrl,
            onValueChange = viewModel::updateHassUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Home Assistant URL") },
            placeholder = { Text("http://homeassistant.local:8123") },
            singleLine = true,
            enabled = !state.testingConnection,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        OutlinedTextField(
            value = state.settings.adminToken,
            onValueChange = viewModel::updateAdminToken,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Long-lived access token") },
            singleLine = true,
            enabled = !state.testingConnection,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        val hassUrl = state.settings.hassUrl.trim()
        if (hassUrl.isNotEmpty()) {
            TextButton(onClick = { uriHandler.openUri(hassUrl.trimEnd('/') + "/profile/security") }) {
                Text("Create a token in Home Assistant")
            }
        }
    }
}

// First run lists networks inline; once one is chosen it collapses to that network with a CHANGE picker.
@Composable
fun WifiFields(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val ssid = state.settings.homeWifiSsid
    val select = { chosen: String ->
        if (chosen != ssid) viewModel.updateHomeWifiPassword("")
        viewModel.updateHomeWifiSsid(chosen)
        picking = false
    }
    if (ssid.isBlank()) {
        CardText("Devices join this 2.4 GHz network.")
        NetworkList(state.networks, state.networksError, null, require24Ghz = true, enabled = true, onRefresh = viewModel::refreshNetworks, onSelect = select)
        return
    }
    EntityRow(
        icon = { EntityIcon(Icons.Outlined.Wifi, RowTone.NEUTRAL) },
        title = ssid,
        state = "Devices join this 2.4 GHz network"
    ) { CardAction("Change", { picking = true }) }
    OutlinedTextField(
        value = state.settings.homeWifiPassword,
        onValueChange = viewModel::updateHomeWifiPassword,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        label = { Text("Password for $ssid") },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (showPassword) "Hide password" else "Show password"
                )
            }
        }
    )
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Home Wi-Fi") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NetworkList(state.networks, state.networksError, ssid, require24Ghz = true, enabled = true, onRefresh = viewModel::refreshNetworks, onSelect = select)
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NearbyCard(state: OnboardingUiState, viewModel: OnboardingViewModel, onOpenWifiSettings: () -> Unit) {
    val canJoinHotspots = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val connecting = state.hotspotStatus == HotspotStatus.CONNECTING
    val pairing = state.stage == PairStage.PAIRING || connecting
    val scanning = state.hotspotStatus == HotspotStatus.SCANNING
    val tappable = state.activationReady && !pairing && !state.preparingCameraAccount
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var pickHotspot by rememberSaveable { mutableStateOf(false) }
    HaCard(
        title = "Nearby",
        actions = {
            CardAction("Scan", viewModel::requestDeviceScan, enabled = state.activationReady && !scanning && !pairing)
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More pairing options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (canJoinHotspots) {
                        DropdownMenuItem(
                            text = { Text("Join a hotspot from Wi-Fi list") },
                            enabled = tappable,
                            onClick = { menuOpen = false; pickHotspot = true }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Start on current hotspot") },
                        enabled = tappable,
                        onClick = { menuOpen = false; viewModel.startManualActivation() }
                    )
                    DropdownMenuItem(text = { Text("Open Wi-Fi settings") }, onClick = { menuOpen = false; onOpenWifiSettings() })
                }
            }
        }
    ) {
        if (pairing) LivePairingRow(state, viewModel)
        if (!pairing) {
            state.hotspotMessage?.let {
                StatusLine(it, busy = state.hotspotStatus == HotspotStatus.PREPARING || scanning)
            }
        }
        state.hotspotError?.let { error ->
            InlineError(error)
            if (!state.activationReady && state.hotspotStatus == HotspotStatus.ERROR) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    CardAction("Retry setup", viewModel::prepareActivation)
                }
            }
        }
        state.bleDevices.filter { it.uuid != state.pairingKey || !pairing }.forEach { device ->
            EntityRow(
                icon = { EntityIcon(Icons.Outlined.Bluetooth, RowTone.NEUTRAL, imageUrl = device.iconUrl) },
                title = device.productName ?: "Looking up device…",
                state = "Bluetooth · ${device.rssi} dBm",
                enabled = tappable,
                onClick = { viewModel.connectToBleDevice(device.uuid) }
            ) { CardAction("Pair", { viewModel.connectToBleDevice(device.uuid) }, enabled = tappable) }
        }
        state.hotspots.filter { it.ssid != state.pairingKey || !pairing }.forEach { hotspot ->
            EntityRow(
                icon = { EntityIcon(Icons.Outlined.Wifi, RowTone.NEUTRAL) },
                title = hotspot.ssid,
                state = if (canJoinHotspots) "Wi-Fi hotspot · ${hotspot.signalDbm} dBm"
                else "Wi-Fi hotspot · join it in Wi-Fi settings, then Start on current hotspot",
                enabled = tappable && canJoinHotspots,
                onClick = { viewModel.connectToHotspot(hotspot.ssid) }
            ) { if (canJoinHotspots) CardAction("Pair", { viewModel.connectToHotspot(hotspot.ssid) }, enabled = tappable) }
        }
    }
    if (pickHotspot) {
        AlertDialog(
            onDismissRequest = { pickHotspot = false },
            title = { Text("Join a hotspot") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NetworkList(
                        networks = state.networks,
                        error = state.networksError,
                        selectedSsid = null,
                        require24Ghz = false,
                        enabled = tappable,
                        onRefresh = viewModel::refreshNetworks,
                        onSelect = { ssid -> pickHotspot = false; viewModel.connectToHotspot(ssid) }
                    )
                }
            },
            confirmButton = { TextButton(onClick = { pickHotspot = false }) { Text("Cancel") } }
        )
    }
}

// The signature move: the tapped device's row becomes a live entity whose state line is the pairing stage.
@Composable
private fun LivePairingRow(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val name = checkNotNull(state.pairingName) { "Pairing without a target name" }
    val failed = state.activationStatus == ActivationStatus.FAILED
    val icon = when {
        state.pairingBluetooth -> Icons.Outlined.Bluetooth
        state.pairingKey == MANUAL_PAIRING_KEY -> Icons.Outlined.WifiTethering
        else -> Icons.Outlined.Wifi
    }
    val stateText = when {
        failed -> checkNotNull(state.activationError) { "Activation failed without an error" }
        state.stage == PairStage.PAIRING -> checkNotNull(state.activationProgress) { "Pairing without progress" }
        else -> checkNotNull(state.hotspotMessage) { "Connecting without a message" }
    }
    EntityRow(
        icon = { EntityIcon(icon, if (failed) RowTone.FAILED else RowTone.WORKING, imageUrl = state.pairingIconUrl, busy = !failed) },
        title = name,
        state = stateText,
        tone = if (failed) RowTone.FAILED else RowTone.WORKING,
        trailing = {
            when {
                failed -> CardAction("Try again", viewModel::retryActivation)
                state.activationStatus == ActivationStatus.ACTIVATING -> CardAction("Cancel", viewModel::cancelActivation)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraAccountDialog(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val busy = state.preparingCameraAccount
    var showPassword by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { if (!busy) viewModel.dismissCameraAccount() },
        title = { Text("Camera account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Cameras pair to your own Tuya account. If you lose this email and password, you won't be able to reset this camera again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                val choices = listOf(AccountAuth.LOGIN to "Existing account", AccountAuth.REGISTER to "New account")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    choices.forEachIndexed { index, (auth, label) ->
                        SegmentedButton(
                            selected = state.cameraAuth == auth,
                            onClick = { viewModel.updateCameraAuth(auth) },
                            enabled = !busy,
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }
                OutlinedTextField(
                    value = state.cameraEmail,
                    onValueChange = viewModel::updateCameraEmail,
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.cameraPassword,
                    onValueChange = viewModel::updateCameraPassword,
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(if (state.cameraAuth == AccountAuth.REGISTER) "Creating the account…" else "Signing in…")
                    }
                }
                state.cameraAccountError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::confirmCameraAccount,
                enabled = !busy && state.cameraEmail.trim().contains("@") && state.cameraPassword.isNotBlank()
            ) { Text("Save and pair") }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissCameraAccount, enabled = !busy) { Text("Cancel") } }
    )
}
