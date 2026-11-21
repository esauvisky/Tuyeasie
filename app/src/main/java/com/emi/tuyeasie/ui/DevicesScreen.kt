package com.emi.tuyeasie.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emi.tuyeasie.data.SavedTuyaAccount
import com.emi.tuyeasie.data.TuyaAccountKind
import com.emi.tuyeasie.data.TuyaManagedDevice

@Composable
fun DevicesScreen(
    viewModel: MyDevicesViewModel,
    configureEnabled: Boolean,
    onConfigureDevice: (SavedTuyaAccount, TuyaManagedDevice) -> Unit,
    padding: PaddingValues
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    val devices = state.accounts.flatMap { entry -> entry.devices.orEmpty().map { entry to it } }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HaCard(title = null) {
            state.actionError?.let { InlineError(it) }
            if (state.refreshing) StatusLine("Loading devices…", busy = true)
            state.accounts.forEach { entry -> entry.error?.let { InlineError("${entry.saved.kind.label}: $it") } }
            if (state.accounts.isEmpty()) CardText("No Tuya accounts on this phone yet. Pair a device or add your account in Settings.")
            if (state.accounts.isNotEmpty() && !state.refreshing && devices.isEmpty()) CardText("No devices yet.")
            devices.forEach { (entry, device) ->
                var menuOpen by remember { mutableStateOf(false) }
                val enabled = configureEnabled && state.unpairingDeviceId == null
                val online = state.online[device.deviceId]
                Box {
                    EntityRow(
                        icon = {
                            EntityIcon(
                                categoryIcon(device.activated.category),
                                RowTone.NEUTRAL,
                                imageUrl = device.iconUrl,
                                busy = state.unpairingDeviceId == device.deviceId
                            )
                        },
                        title = device.name,
                        state = listOfNotNull(
                            online?.let { if (it) "Online" else "Offline" }, lanAddress(device.activated.ip), entry.saved.kind.label, device.homeName
                        ).joinToString(" · "),
                        tone = when (online) {
                            true -> RowTone.OK
                            false -> RowTone.FAILED
                            null -> RowTone.NEUTRAL
                        },
                        enabled = enabled,
                        onClick = { onConfigureDevice(entry.saved, device) },
                        onLongClick = { menuOpen = true }
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Open") },
                            onClick = { menuOpen = false; onConfigureDevice(entry.saved, device) }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy device ID") },
                            onClick = {
                                menuOpen = false
                                clipboard.setPrimaryClip(ClipData.newPlainText("Tuya device ID", device.deviceId))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy local key") },
                            onClick = {
                                menuOpen = false
                                clipboard.setPrimaryClip(sensitiveClip("Tuya local key", device.activated.localKey))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Unpair", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; viewModel.requestUnpair(entry.saved, device.deviceId, device.name) }
                        )
                    }
                }
            }
        }
    }
    state.confirmation?.let { ConfirmationDialog(it, viewModel::confirm, viewModel::toggleUnpairHomeAssistant, viewModel::dismissConfirmation) }
}

@Composable
fun AccountsCard(viewModel: MyDevicesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hasUserAccount = state.accounts.any { it.saved.kind == TuyaAccountKind.USER }
    HaCard(
        title = "Tuya accounts",
        actions = { if (!hasUserAccount) CardAction("Add account", viewModel::openAddAccount) }
    ) {
        if (state.accounts.isEmpty()) CardText("No accounts yet. One is created when you pair your first device.")
        state.accounts.forEach { entry ->
            val linked = entry.devices.orEmpty().size
            EntityRow(
                icon = { EntityIcon(Icons.Outlined.AccountCircle, RowTone.NEUTRAL) },
                title = entry.saved.account.uid,
                state = if (linked == 0) entry.saved.kind.label
                else "${entry.saved.kind.label} · $linked ${if (linked == 1) "device" else "devices"}, unpair to forget"
            ) {
                CardAction("Forget", { viewModel.requestForget(entry.saved) }, enabled = !entry.loading && linked == 0)
            }
        }
    }
    if (state.addDialogOpen) {
        AddAccountDialog(
            state = state,
            onEmailChange = viewModel::onAddEmailChange,
            onPasswordChange = viewModel::onAddPasswordChange,
            onConfirm = viewModel::confirmAddAccount,
            onDismiss = viewModel::dismissAddAccount
        )
    }
    state.confirmation?.let { ConfirmationDialog(it, viewModel::confirm, viewModel::toggleUnpairHomeAssistant, viewModel::dismissConfirmation) }
}

@Composable
private fun AddAccountDialog(
    state: MyDevicesUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.adding) onDismiss() },
        title = { Text("Add account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.addEmail,
                    onValueChange = onEmailChange,
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !state.adding,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.addPassword,
                    onValueChange = onPasswordChange,
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !state.adding,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.adding) Text("Signing in…")
                state.addError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !state.adding && state.addEmail.trim().contains("@") && state.addPassword.isNotBlank()
            ) { Text("Sign in and save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.adding) { Text("Cancel") } }
    )
}

@Composable
private fun ConfirmationDialog(confirmation: MyDevicesConfirmation, onConfirm: () -> Unit, onToggleHomeAssistant: () -> Unit, onDismiss: () -> Unit) {
    val (title, body, action) = when (confirmation) {
        is MyDevicesConfirmation.Unpair -> Triple(
            "Unpair device?",
            "\"${confirmation.name}\" (${confirmation.deviceId}) will be removed from " +
                "${confirmation.saved.account.uid}. A factory reset may be required to pair it again.",
            "Unpair"
        )
        is MyDevicesConfirmation.Forget -> Triple(
            "Forget account?",
            "Only the credentials saved on this phone for ${confirmation.saved.account.uid} are deleted. " +
                "Its devices stay paired, but you cannot manage them here without the password.",
            "Forget"
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body)
                if (confirmation is MyDevicesConfirmation.Unpair) {
                    CheckRow("Also remove it from Home Assistant", confirmation.removeFromHomeAssistant, onToggleHomeAssistant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
