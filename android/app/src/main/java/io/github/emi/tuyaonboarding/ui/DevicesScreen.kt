package io.github.emi.tuyaonboarding.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.emi.tuyaonboarding.data.SavedTuyaAccount
import io.github.emi.tuyaonboarding.data.TuyaAccountKind
import io.github.emi.tuyaonboarding.data.TuyaManagedDevice

@Composable
fun DevicesScreen(
    viewModel: MyDevicesViewModel,
    configureEnabled: Boolean,
    onConfigureDevice: (SavedTuyaAccount, TuyaManagedDevice) -> Unit,
    padding: PaddingValues
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val devices = state.accounts.flatMap { entry -> entry.devices.orEmpty().map { entry to it } }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HaCard(title = null) {
            state.actionError?.let { InlineError(it) }
            state.accounts.forEach { entry ->
                if (entry.loading) StatusLine("Loading ${entry.saved.account.uid}…", busy = true)
                entry.error?.let { InlineError("${entry.saved.kind.label}: $it") }
            }
            if (state.accounts.isEmpty()) CardText("No Tuya accounts on this phone yet. Pair a device or add your account in Settings.")
            if (state.accounts.isNotEmpty() && state.accounts.none { it.loading } && devices.isEmpty()) CardText("No devices yet.")
            devices.forEach { (entry, device) ->
                EntityRow(
                    icon = { EntityIcon(categoryIcon(device.activated.category), RowTone.NEUTRAL, busy = state.unpairingDeviceId == device.deviceId) },
                    title = device.name,
                    state = listOfNotNull(lanAddress(device.activated.ip), entry.saved.kind.label, device.homeName).joinToString(" · "),
                    enabled = configureEnabled && state.unpairingDeviceId == null && !entry.loading,
                    onClick = { onConfigureDevice(entry.saved, device) }
                )
            }
        }
    }
    state.confirmation?.let { ConfirmationDialog(it, viewModel::confirm, viewModel::dismissConfirmation) }
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
            EntityRow(
                icon = { EntityIcon(Icons.Outlined.AccountCircle, RowTone.NEUTRAL) },
                title = entry.saved.account.uid,
                state = entry.saved.kind.label
            ) {
                CardAction("Forget", { viewModel.requestForget(entry.saved) }, enabled = !entry.loading)
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
    state.confirmation?.let { ConfirmationDialog(it, viewModel::confirm, viewModel::dismissConfirmation) }
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
private fun ConfirmationDialog(confirmation: MyDevicesConfirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, body, action) = when (confirmation) {
        is MyDevicesConfirmation.Unpair -> Triple(
            "Unpair device?",
            "\"${confirmation.device.name}\" (${confirmation.device.deviceId}) will be removed from " +
                "${confirmation.saved.account.uid} and factory-reset state may be required to pair it again.",
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
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
