package io.github.emi.tuyaonboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.emi.tuyaonboarding.data.ActivatedDevice
import io.github.emi.tuyaonboarding.data.TuyaAccountKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDevicesScreen(
    viewModel: MyDevicesViewModel,
    onBack: () -> Unit,
    configureEnabled: Boolean,
    onConfigureDevice: (ActivatedDevice) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val anyLoading = state.accounts.any { it.loading }
    val hasUserAccount = state.accounts.any { it.saved.kind == TuyaAccountKind.USER }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("My devices") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !anyLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            state.actionError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (!hasUserAccount) {
                item {
                    Button(onClick = viewModel::openAddAccount, modifier = Modifier.fillMaxWidth()) {
                        Text("Set user account")
                    }
                }
            }
            if (state.accounts.isEmpty()) {
                item {
                    Text(
                        "No Tuya accounts saved on this phone yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.accounts, key = { "${it.saved.kind}:${it.saved.account.uid}" }) { entry ->
                AccountCard(
                    entry = entry,
                    configureEnabled = configureEnabled,
                    unpairingDeviceId = state.unpairingDeviceId,
                    onConfigure = { onConfigureDevice(it.activated) },
                    onUnpair = { viewModel.requestUnpair(entry.saved, it) },
                    onForget = { viewModel.requestForget(entry.saved) }
                )
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
private fun AccountCard(
    entry: AccountDevicesState,
    configureEnabled: Boolean,
    unpairingDeviceId: String?,
    onConfigure: (io.github.emi.tuyaonboarding.data.TuyaManagedDevice) -> Unit,
    onUnpair: (io.github.emi.tuyaonboarding.data.TuyaManagedDevice) -> Unit,
    onForget: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.saved.kind.label, style = MaterialTheme.typography.labelLarge)
                    Text(
                        entry.saved.account.uid,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.loading) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                TextButton(onClick = onForget, enabled = !entry.loading) { Text("Forget") }
            }
            entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            entry.devices?.let { devices ->
                if (devices.isEmpty()) {
                    Text("No devices", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                devices.forEach { device ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text("Product: ${device.productId}", style = MaterialTheme.typography.bodyMedium)
                            Text("ID: ${device.deviceId}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Home: ${device.homeName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { onConfigure(device) },
                            enabled = configureEnabled && unpairingDeviceId == null && !entry.loading
                        ) {
                            Text("Configure")
                        }
                        TextButton(
                            onClick = { onUnpair(device) },
                            enabled = unpairingDeviceId == null && !entry.loading
                        ) {
                            if (unpairingDeviceId == device.deviceId) {
                                CircularProgressIndicator(Modifier.padding(4.dp))
                            } else {
                                Text("Unpair", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
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
