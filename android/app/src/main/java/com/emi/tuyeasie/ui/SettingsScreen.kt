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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (state.testingConnection) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                CardAction("Save and test", viewModel::testConnection, enabled = !state.testingConnection)
            }
        }
        HaCard(title = "Home Wi-Fi") {
            WifiFields(state, viewModel)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) { CardAction("Save", viewModel::saveWifi) }
        }
        AccountsCard(devicesViewModel)
        BackupCard(devicesViewModel)
    }
}

@Composable
private fun BackupCard(viewModel: MyDevicesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), viewModel::onBackupFileChosen)
    HaCard(title = "Backup", actions = { CardAction("Export", viewModel::openBackupDialog, enabled = !state.exportingBackup) }) {
        CardText("Exports Tuya accounts, Home Assistant, Wi-Fi, device and camera settings to a file. " +
            "Usernames stay readable; passwords, tokens and stream URLs are encrypted with a passphrase you choose, " +
            "or with a key built into the app if you leave it empty.")
        if (state.exportingBackup) StatusLine("Encrypting backup…", busy = true)
        if (state.backupExported) Text("Backup exported", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 16.dp))
        state.backupError?.let { InlineError(it) }
    }
    if (state.backupDialogOpen) {
        val passphrase = state.backupPassphrase
        val tooShort = passphrase.isNotEmpty() && passphrase.length < MIN_BACKUP_PASSPHRASE_LENGTH
        val mismatch = state.backupPassphraseConfirm.isNotEmpty() && passphrase != state.backupPassphraseConfirm
        AlertDialog(
            onDismissRequest = viewModel::dismissBackupDialog,
            title = { Text("Export backup") },
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
                        createFile.launch("tuyeasie-backup-${LocalDate.now()}.json")
                    },
                    enabled = !tooShort && passphrase == state.backupPassphraseConfirm
                ) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissBackupDialog) { Text("Cancel") } }
        )
    }
}
