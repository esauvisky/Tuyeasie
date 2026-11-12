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
fun SettingsScreen(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    devicesViewModel: MyDevicesViewModel,
    padding: PaddingValues
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HaCard(title = "Home Assistant") {
            HomeAssistantFields(state, viewModel)
            when {
                state.connectionError != null -> InlineError(state.connectionError)
                state.connectionVerified -> Text(
                    "Connected with administrator access",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (state.testingConnection) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                CardAction("Save and test", viewModel::testConnection, enabled = !state.testingConnection)
            }
        }
        HaCard(title = "Home Wi-Fi") {
            WifiFields(state, viewModel)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                CardAction("Save", viewModel::saveWifi)
            }
        }
        AccountsCard(devicesViewModel)
        BackupCard(devicesViewModel)
        HaCard(title = "Camera feeds") {
            CardText("Where new camera feeds go in Home Assistant. Each camera can override it on its device screen. " +
                "Only options that device actually has are offered: ONVIF needs a detected ONVIF service, and Tuya Local needs a Tuya Local entry.")
            Column(Modifier.padding(horizontal = 12.dp)) {
                StreamDestination.entries.forEach { destination ->
                    ChoiceRow(destinationLabel(destination), state.settings.feedDestination == destination.storedName) {
                        viewModel.selectDefaultDestination(destination)
                    }
                }
            }
            if (state.settings.feedDestination == StreamDestination.GO2RTC.storedName) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Go2rtcFields(state, viewModel) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                CardAction("Save", viewModel::saveFeedSettings)
            }
        }
    }
}

@Composable
private fun BackupCard(viewModel: MyDevicesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
        viewModel::onBackupFileChosen
    )
    HaCard(
        title = "Backup",
        actions = { CardAction("Export", viewModel::openBackupDialog, enabled = !state.exportingBackup) }
    ) {
        CardText(
            "Exports Tuya accounts, Home Assistant, Wi-Fi, go2rtc and camera settings to a file. " +
                "Usernames stay readable; passwords, tokens and stream URLs are encrypted with a passphrase you choose."
        )
        if (state.exportingBackup) StatusLine("Encrypting backup…", busy = true)
        if (state.backupExported) {
            Text(
                "Backup exported",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        state.backupError?.let { InlineError(it) }
    }
    if (state.backupDialogOpen) {
        val tooShort = state.backupPassphrase.length < MIN_BACKUP_PASSPHRASE_LENGTH
        val mismatch = state.backupPassphraseConfirm.isNotEmpty() && state.backupPassphrase != state.backupPassphraseConfirm
        AlertDialog(
            onDismissRequest = viewModel::dismissBackupDialog,
            title = { Text("Export backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("The passphrase cannot be recovered. Without it the encrypted fields are unreadable.")
                    OutlinedTextField(
                        value = state.backupPassphrase,
                        onValueChange = viewModel::onBackupPassphraseChange,
                        label = { Text("Passphrase") },
                        supportingText = { Text("At least $MIN_BACKUP_PASSPHRASE_LENGTH characters") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
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
                    enabled = !tooShort && state.backupPassphrase == state.backupPassphraseConfirm
                ) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissBackupDialog) { Text("Cancel") } }
        )
    }
}
