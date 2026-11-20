package com.emi.tuyeasie.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

@Composable
fun SettingsScreen(state: OnboardingUiState, viewModel: OnboardingViewModel, devicesViewModel: MyDevicesViewModel, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HaCard(title = "Home Assistant") {
            HomeAssistantFields(state, viewModel)
            when {
                state.connectionError != null -> InlineError(state.connectionError)
                state.connectionVerified -> Text("Connected with administrator access", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 16.dp))
                !state.settings.homeAssistantConfigured -> CardText("Not set up. Devices are only paired with Tuya.")
            }
            HomeAssistantActions(state, viewModel, if (state.settings.homeAssistantConfigured) "Save and test" else "Save")
        }
        HaCard(title = "Home Wi-Fi") {
            WifiFields(state, viewModel)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) { CardAction("Save", viewModel::saveWifi) }
        }
        AccountsCard(devicesViewModel)
        SettingsFileCard(devicesViewModel, importEnabled = !state.busy, onImported = viewModel::reloadSettings)
    }
}

@Composable
private fun SettingsFileCard(viewModel: MyDevicesViewModel, importEnabled: Boolean, onImported: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), viewModel::onBackupFileChosen)
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), viewModel::onImportFileChosen)
    val working = state.exportingBackup || state.importing
    HaCard(title = "Export / import settings") {
        CardText("Manage the same devices from another phone or tablet: export the settings here, then import the file there. " +
            "It holds Tuya accounts, Home Assistant, Wi-Fi, device and camera settings.")
        CardText("Usernames stay readable; passwords, tokens and stream URLs are encrypted with a passphrase you choose, " +
            "or with a key built into the app if you leave it empty.")
        if (state.exportingBackup) StatusLine("Encrypting settings…", busy = true)
        if (state.importing) StatusLine("Importing settings…", busy = true)
        val done = when {
            state.backupExported -> "Settings exported"
            state.settingsImported -> "Settings imported"
            else -> null
        }
        done?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        state.backupError?.let { InlineError(it) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
            // Android's picker greys out JSON files whose provider reports another type, so every file is offered.
            CardAction("Import", { openFile.launch(arrayOf("*/*")) }, enabled = importEnabled && !working)
            CardAction("Export", viewModel::openBackupDialog, enabled = !working)
        }
    }
    if (state.importJson != null) {
        AlertDialog(
            onDismissRequest = { if (!state.importing) viewModel.dismissImport() },
            title = { Text("Import settings?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Home Assistant, Wi-Fi and the Tuya accounts in this file replace the ones saved on this device.")
                    if (state.importNeedsPassphrase) OutlinedTextField(
                        value = state.importPassphrase,
                        onValueChange = viewModel::onImportPassphraseChange,
                        label = { Text("Passphrase") },
                        singleLine = true,
                        enabled = !state.importing,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.importError?.let { InlineError(it) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmImport(onImported) },
                    enabled = !state.importing && (!state.importNeedsPassphrase || state.importPassphrase.isNotEmpty())
                ) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissImport, enabled = !state.importing) { Text("Cancel") } }
        )
    }
    if (state.backupDialogOpen) {
        val passphrase = state.backupPassphrase
        val tooShort = passphrase.isNotEmpty() && passphrase.length < MIN_BACKUP_PASSPHRASE_LENGTH
        val mismatch = state.backupPassphraseConfirm.isNotEmpty() && passphrase != state.backupPassphraseConfirm
        AlertDialog(
            onDismissRequest = viewModel::dismissBackupDialog,
            title = { Text("Export settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (passphrase.isEmpty()) "No passphrase: secrets are sealed with a key built into the app, which anyone with the APK can extract."
                        else "The passphrase cannot be recovered. Without it the encrypted fields are unreadable."
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = viewModel::onBackupPassphraseChange,
                        label = { Text("Passphrase (optional)") },
                        isError = tooShort,
                        supportingText = { Text("Empty, or at least $MIN_BACKUP_PASSPHRASE_LENGTH characters") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (passphrase.isNotEmpty()) OutlinedTextField(
                        value = state.backupPassphraseConfirm,
                        onValueChange = viewModel::onBackupPassphraseConfirmChange,
                        label = { Text("Repeat passphrase") },
                        isError = mismatch,
                        supportingText = { if (mismatch) Text("Passphrases do not match") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.startBackupExport()
                        createFile.launch("tuyeasie-settings-${LocalDate.now()}.json")
                    },
                    enabled = !tooShort && passphrase == state.backupPassphraseConfirm
                ) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissBackupDialog) { Text("Cancel") } }
        )
    }
}
