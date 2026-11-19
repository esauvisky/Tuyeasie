package com.emi.tuyeasie.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emi.tuyeasie.data.BackupExporter
import com.emi.tuyeasie.data.SavedTuyaAccount
import com.emi.tuyeasie.data.SecureSettingsStore
import com.emi.tuyeasie.data.TuyaAccountKind
import com.emi.tuyeasie.data.TuyaAccountRepository
import com.emi.tuyeasie.data.TuyaManagedDevice
import com.emi.tuyeasie.data.TuyaSdkException
import com.emi.tuyeasie.hass.HomeAssistantClient
import com.emi.tuyeasie.hass.HomeAssistantException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AccountDevicesState(
    val saved: SavedTuyaAccount,
    val loading: Boolean = false,
    val devices: List<TuyaManagedDevice>? = null,
    val error: String? = null
)

sealed interface MyDevicesConfirmation {
    data class Unpair(val saved: SavedTuyaAccount, val device: TuyaManagedDevice, val removeFromHomeAssistant: Boolean = true) : MyDevicesConfirmation
    data class Forget(val saved: SavedTuyaAccount) : MyDevicesConfirmation
}

data class MyDevicesUiState(
    val accounts: List<AccountDevicesState> = emptyList(),
    val addDialogOpen: Boolean = false,
    val addEmail: String = "",
    val addPassword: String = "",
    val adding: Boolean = false,
    val addError: String? = null,
    val confirmation: MyDevicesConfirmation? = null,
    val unpairingDeviceId: String? = null,
    val actionError: String? = null,
    val backupDialogOpen: Boolean = false,
    // Empty means the backup uses the app-wide key instead of a passphrase.
    val backupPassphrase: String = "",
    val backupPassphraseConfirm: String = "",
    // Set when the file picker opens; lost if Android kills the app meanwhile, along with the passphrase.
    val backupExportPending: Boolean = false,
    val exportingBackup: Boolean = false,
    val backupError: String? = null,
    val backupExported: Boolean = false
)

const val MIN_BACKUP_PASSPHRASE_LENGTH = 8

class MyDevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureSettingsStore(application)
    private val repository = TuyaAccountRepository(application, store)
    private val backupExporter = BackupExporter(store)
    private val homeAssistantClient = HomeAssistantClient(store)
    // The Tuya SDK holds a single signed-in user, so sign-in + operation pairs must not interleave.
    private val sdkLock = Mutex()
    private val _uiState = MutableStateFlow(MyDevicesUiState())
    val uiState: StateFlow<MyDevicesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val saved = repository.savedAccounts()
        val existingByAccount = _uiState.value.accounts.associateBy { it.saved }
        _uiState.update { state ->
            state.copy(
                accounts = saved.map { account ->
                    val cached = existingByAccount[account]?.devices
                        ?: store.loadCachedDevices(account.account.uid)
                    AccountDevicesState(account, loading = true, devices = cached)
                },
                actionError = null
            )
        }
        viewModelScope.launch {
            saved.forEach { loadDevices(it) }
        }
    }

    private suspend fun loadDevices(saved: SavedTuyaAccount) {
        val result = try {
            val devices = sdkLock.withLock { repository.listDevices(saved.account) }
            withContext(Dispatchers.IO) { store.saveCachedDevices(saved.account.uid, devices) }
            Result.success(devices)
        } catch (e: TuyaSdkException) {
            Result.failure(e)
        }
        updateAccount(saved) {
            it.copy(
                loading = false,
                devices = result.getOrNull() ?: it.devices,
                error = result.exceptionOrNull()?.message
            )
        }
    }

    private fun updateAccount(saved: SavedTuyaAccount, transform: (AccountDevicesState) -> AccountDevicesState) {
        _uiState.update { state ->
            state.copy(accounts = state.accounts.map { if (it.saved == saved) transform(it) else it })
        }
    }

    fun openAddAccount() = _uiState.update {
        it.copy(addDialogOpen = true, addEmail = "", addPassword = "", addError = null)
    }

    fun dismissAddAccount() = _uiState.update { it.copy(addDialogOpen = false, addError = null) }

    fun onAddEmailChange(value: String) = _uiState.update { it.copy(addEmail = value, addError = null) }

    fun onAddPasswordChange(value: String) = _uiState.update { it.copy(addPassword = value, addError = null) }

    fun confirmAddAccount() {
        val state = _uiState.value
        check(!state.adding) { "Add account already in progress" }
        val email = state.addEmail.trim()
        if (repository.savedAccounts().any { it.kind == TuyaAccountKind.USER }) {
            _uiState.update { it.copy(addError = "A user account is already saved. Forget it first to switch accounts.") }
            return
        }
        _uiState.update { it.copy(adding = true, addError = null) }
        viewModelScope.launch {
            try {
                sdkLock.withLock { repository.addAccount(email, state.addPassword) }
            } catch (e: TuyaSdkException) {
                _uiState.update { it.copy(adding = false, addError = e.message) }
                return@launch
            }
            _uiState.update { it.copy(adding = false, addDialogOpen = false) }
            refresh()
        }
    }

    fun requestUnpair(saved: SavedTuyaAccount, device: TuyaManagedDevice) =
        _uiState.update { it.copy(confirmation = MyDevicesConfirmation.Unpair(saved, device)) }

    fun toggleUnpairHomeAssistant() = _uiState.update {
        val unpair = it.confirmation as MyDevicesConfirmation.Unpair
        it.copy(confirmation = unpair.copy(removeFromHomeAssistant = !unpair.removeFromHomeAssistant))
    }

    fun requestForget(saved: SavedTuyaAccount) =
        _uiState.update { it.copy(confirmation = MyDevicesConfirmation.Forget(saved)) }

    fun dismissConfirmation() = _uiState.update { it.copy(confirmation = null) }

    fun confirm() {
        val confirmation = checkNotNull(_uiState.value.confirmation) { "No confirmation pending" }
        _uiState.update { it.copy(confirmation = null) }
        when (confirmation) {
            is MyDevicesConfirmation.Forget -> {
                repository.forgetAccount(confirmation.saved)
                refresh()
            }
            is MyDevicesConfirmation.Unpair -> unpair(confirmation)
        }
    }

    private fun unpair(confirmation: MyDevicesConfirmation.Unpair) {
        val device = confirmation.device
        _uiState.update { it.copy(unpairingDeviceId = device.deviceId, actionError = null) }
        viewModelScope.launch {
            if (confirmation.removeFromHomeAssistant && store.loadSettings().hassUrl.isNotBlank()) {
                try {
                    withContext(Dispatchers.IO) {
                        homeAssistantClient.removeDevice(
                            device.deviceId,
                            store.integrationEntryIds(device.deviceId),
                            listOf("${device.name} Main", "${device.name} Substream")
                        )
                    }
                } catch (e: HomeAssistantException) {
                    _uiState.update {
                        it.copy(unpairingDeviceId = null, actionError = "Home Assistant cleanup failed, so the device was not unpaired: ${e.publicMessage}")
                    }
                    return@launch
                }
            }
            try {
                sdkLock.withLock { repository.removeDevice(confirmation.saved.account, device.deviceId) }
            } catch (e: TuyaSdkException) {
                _uiState.update { it.copy(unpairingDeviceId = null, actionError = e.message) }
                return@launch
            }
            withContext(Dispatchers.IO) { store.forgetDevice(device.deviceId) }
            _uiState.update { it.copy(unpairingDeviceId = null) }
            refresh()
        }
    }

    fun openBackupDialog() = _uiState.update {
        it.copy(backupDialogOpen = true, backupPassphrase = "", backupPassphraseConfirm = "", backupError = null, backupExported = false)
    }

    fun dismissBackupDialog() = _uiState.update {
        it.copy(backupDialogOpen = false, backupPassphrase = "", backupPassphraseConfirm = "")
    }

    fun onBackupPassphraseChange(value: String) = _uiState.update { it.copy(backupPassphrase = value) }

    fun onBackupPassphraseConfirmChange(value: String) = _uiState.update { it.copy(backupPassphraseConfirm = value) }

    /** Closes the dialog but keeps the passphrase until the file picker returns. */
    fun startBackupExport() {
        val state = _uiState.value
        check(state.backupPassphrase.isEmpty() || state.backupPassphrase.length >= MIN_BACKUP_PASSPHRASE_LENGTH) { "Backup passphrase too short" }
        check(state.backupPassphrase == state.backupPassphraseConfirm) { "Backup passphrases differ" }
        _uiState.update { it.copy(backupDialogOpen = false, backupExportPending = true) }
    }

    fun onBackupFileChosen(uri: Uri?) {
        val state = _uiState.value
        _uiState.update { it.copy(backupPassphrase = "", backupPassphraseConfirm = "", backupExportPending = false) }
        if (uri == null) return
        if (!state.backupExportPending) {
            _uiState.update { it.copy(backupError = "The app restarted while choosing the file. Export the backup again.") }
            return
        }
        val passphrase = state.backupPassphrase.ifEmpty { null }
        val accounts = repository.savedAccounts()
        _uiState.update { it.copy(exportingBackup = true, backupError = null, backupExported = false) }
        viewModelScope.launch {
            val json = withContext(Dispatchers.Default) { backupExporter.export(passphrase, accounts) }
            try {
                withContext(Dispatchers.IO) {
                    val output = checkNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) {
                        "No output stream for $uri"
                    }
                    output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
            } catch (e: IOException) {
                _uiState.update { it.copy(exportingBackup = false, backupError = "Could not write the backup: ${e.message}") }
                return@launch
            }
            _uiState.update { it.copy(exportingBackup = false, backupExported = true) }
        }
    }
}
