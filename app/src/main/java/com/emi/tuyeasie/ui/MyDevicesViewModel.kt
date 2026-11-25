package com.emi.tuyeasie.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emi.tuyeasie.data.PortScanner
import com.emi.tuyeasie.data.SavedTuyaAccount
import com.emi.tuyeasie.data.SecureSettingsStore
import com.emi.tuyeasie.data.SettingsBackup
import com.emi.tuyeasie.data.SettingsFileException
import com.emi.tuyeasie.data.TuyaAccountKind
import com.emi.tuyeasie.data.TuyaAccountRepository
import com.emi.tuyeasie.data.TuyaManagedDevice
import com.emi.tuyeasie.data.TuyaSdkException
import com.emi.tuyeasie.hass.HomeAssistantClient
import com.emi.tuyeasie.hass.HomeAssistantException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    // Tuya's data-center code ("AZ", "EU", "IN", "RU", "AY"), known once the account has signed in on this phone.
    val dataCenter: String? = null,
    val loading: Boolean = false,
    val devices: List<TuyaManagedDevice>? = null,
    val error: String? = null
)

sealed interface MyDevicesConfirmation {
    data class Unpair(val saved: SavedTuyaAccount, val deviceId: String, val name: String, val removeFromHomeAssistant: Boolean = true) : MyDevicesConfirmation
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
    // True from the start of a refresh until every account is loaded and duplicate names are numbered.
    val refreshing: Boolean = false,
    // Devices absent here were never scanned or had no open port, so there is nothing to try.
    val online: Map<String, Boolean> = emptyMap(),
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
    val backupExported: Boolean = false,
    // The chosen settings file, held until the import is confirmed.
    val importJson: String? = null,
    val importNeedsPassphrase: Boolean = false,
    val importPassphrase: String = "",
    val importing: Boolean = false,
    val importError: String? = null,
    val settingsImported: Boolean = false
)

const val MIN_BACKUP_PASSPHRASE_LENGTH = 8

// Tuya lets several devices share a name, but Home Assistant entries are told apart by it. Numbering follows device ID.
fun numberedDuplicateNames(devices: List<TuyaManagedDevice>): Map<String, String> {
    val taken = devices.map { it.name }.toMutableSet()
    return devices.groupBy { it.name }
        .filterValues { it.size > 1 }
        .flatMap { (name, group) ->
            var number = 0
            group.sortedBy { it.deviceId }.map { device ->
                var candidate: String
                do {
                    number++
                    candidate = "$name $number"
                } while (candidate in taken)
                taken += candidate
                device.deviceId to candidate
            }
        }
        .toMap()
}

/** The default name of a newly paired device: its own, or the number after the last device already called that. */
fun nextDeviceName(name: String, taken: Collection<String>): String {
    val numbers = taken.mapNotNull { other ->
        if (other == name) 1
        else other.removePrefix("$name ").takeIf { it != other && it.all(Char::isDigit) }?.toIntOrNull()
    }
    return if (numbers.isEmpty()) name else "$name ${numbers.max() + 1}"
}

class MyDevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureSettingsStore(application)
    private val repository = TuyaAccountRepository(application, store)
    private val settingsBackup = SettingsBackup(store)
    private val homeAssistantClient = HomeAssistantClient(store)
    // The Tuya SDK holds a single signed-in user, so sign-in + operation pairs must not interleave.
    private val sdkLock = Mutex()
    private var refreshJob: Job? = null
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
                    AccountDevicesState(account, store.loadDataCenter(account.account.uid), loading = true, devices = cached)
                },
                refreshing = true,
                actionError = null
            )
        }
        // A newer refresh replaces one still running, so the list never settles on the older answer.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // The cached list is checked right away; signing in to Tuya takes seconds.
            val cached = launch { checkOnline() }
            saved.forEach { loadDevices(it) }
            saveNumberedNames()
            _uiState.update { it.copy(refreshing = false) }
            cached.join()
            checkOnline()
        }
    }

    private suspend fun checkOnline() {
        val ids = _uiState.value.accounts.flatMap { it.devices.orEmpty() }.map { it.deviceId }
        val checked = coroutineScope {
            ids.map { id -> async { id to PortScanner.isOnline(withContext(Dispatchers.IO) { store.loadNetworkReport(id) }) } }.awaitAll()
        }
        _uiState.update { state -> state.copy(online = checked.mapNotNull { (id, online) -> online?.let { id to it } }.toMap()) }
    }

    // Saved to Tuya once, so a device paired later can never shift the numbers of the ones already there.
    private suspend fun saveNumberedNames() {
        val accounts = _uiState.value.accounts
        // A failed account shows its cached list, which may no longer match Tuya.
        if (accounts.any { it.error != null }) return
        val owners = accounts.flatMap { entry -> entry.devices.orEmpty().map { it to entry.saved } }
        val names = numberedDuplicateNames(owners.map { it.first })
        for ((device, saved) in owners) {
            val name = names[device.deviceId] ?: continue
            try {
                sdkLock.withLock { repository.renameDevice(saved.account, device.deviceId, name) }
            } catch (e: TuyaSdkException) {
                _uiState.update { it.copy(actionError = "\"${device.name}\" could not be numbered: ${e.message}") }
                return
            }
            val renamed = device.copy(name = name, activated = device.activated.copy(name = name))
            val devices = checkNotNull(_uiState.value.accounts.single { it.saved == saved }.devices)
                .map { if (it.deviceId == device.deviceId) renamed else it }
            updateAccount(saved) { it.copy(devices = devices) }
            val titles = withContext(Dispatchers.IO) {
                store.saveCachedDevices(saved.account.uid, devices)
                entryTitles(name, store.loadIntegrationOptions(device.deviceId))
            }
            if (titles.isEmpty() || !store.loadSettings().homeAssistantConfigured) continue
            try {
                withContext(Dispatchers.IO) { homeAssistantClient.renameEntries(device.deviceId, titles) }
            } catch (e: HomeAssistantException) {
                _uiState.update { it.copy(actionError = "$name was renamed in Tuya, but not in Home Assistant: ${e.publicMessage}") }
            }
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
                dataCenter = store.loadDataCenter(saved.account.uid),
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

    fun requestUnpair(saved: SavedTuyaAccount, deviceId: String, name: String) =
        _uiState.update { it.copy(confirmation = MyDevicesConfirmation.Unpair(saved, deviceId, name)) }

    fun toggleUnpairHomeAssistant() = _uiState.update {
        val unpair = it.confirmation as MyDevicesConfirmation.Unpair
        it.copy(confirmation = unpair.copy(removeFromHomeAssistant = !unpair.removeFromHomeAssistant))
    }

    fun requestForget(saved: SavedTuyaAccount) {
        // Forgetting the account would leave its devices paired with no way to manage or unpair them here.
        check(_uiState.value.accounts.single { it.saved == saved }.devices.isNullOrEmpty()) { "${saved.account.uid} still has devices" }
        _uiState.update { it.copy(confirmation = MyDevicesConfirmation.Forget(saved)) }
    }

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
        val deviceId = confirmation.deviceId
        _uiState.update { it.copy(unpairingDeviceId = deviceId, actionError = null) }
        viewModelScope.launch {
            if (confirmation.removeFromHomeAssistant && store.loadSettings().homeAssistantConfigured) {
                try {
                    withContext(Dispatchers.IO) {
                        homeAssistantClient.removeDevice(
                            deviceId,
                            store.integrationEntryIds(deviceId),
                            listOf("${confirmation.name} Main", "${confirmation.name} Substream")
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
                sdkLock.withLock { repository.removeDevice(confirmation.saved.account, deviceId) }
            } catch (e: TuyaSdkException) {
                _uiState.update { it.copy(unpairingDeviceId = null, actionError = e.message) }
                return@launch
            }
            withContext(Dispatchers.IO) { store.forgetDevice(deviceId) }
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
            _uiState.update { it.copy(backupError = "The app restarted while choosing the file. Export the settings again.") }
            return
        }
        val passphrase = state.backupPassphrase.ifEmpty { null }
        val accounts = repository.savedAccounts()
        _uiState.update { it.copy(exportingBackup = true, backupError = null, backupExported = false) }
        viewModelScope.launch {
            val json = withContext(Dispatchers.Default) { settingsBackup.export(passphrase, accounts) }
            try {
                withContext(Dispatchers.IO) {
                    val output = checkNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) {
                        "No output stream for $uri"
                    }
                    output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
            } catch (e: IOException) {
                _uiState.update { it.copy(exportingBackup = false, backupError = "Could not write the settings file: ${e.message}") }
                return@launch
            }
            _uiState.update { it.copy(exportingBackup = false, backupExported = true) }
        }
    }

    fun onImportFileChosen(uri: Uri?) {
        if (uri == null) return
        _uiState.update { it.copy(backupError = null, backupExported = false, settingsImported = false) }
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val input = checkNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) { "No input stream for $uri" }
                    input.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                val needsPassphrase = settingsBackup.needsPassphrase(json)
                _uiState.update { it.copy(importJson = json, importNeedsPassphrase = needsPassphrase, importPassphrase = "", importError = null) }
            } catch (e: IOException) {
                _uiState.update { it.copy(backupError = "Could not read the settings file: ${e.message}") }
            } catch (e: SettingsFileException) {
                _uiState.update { it.copy(backupError = e.message) }
            }
        }
    }

    fun onImportPassphraseChange(value: String) = _uiState.update { it.copy(importPassphrase = value, importError = null) }

    fun dismissImport() = _uiState.update { it.copy(importJson = null, importPassphrase = "", importError = null) }

    fun confirmImport(onImported: () -> Unit) {
        val state = _uiState.value
        val json = checkNotNull(state.importJson) { "No settings file is waiting to be imported" }
        check(!state.importing) { "An import is already running" }
        val passphrase = if (state.importNeedsPassphrase) state.importPassphrase else null
        _uiState.update { it.copy(importing = true, importError = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { settingsBackup.restore(json, passphrase) }
            } catch (e: SettingsFileException) {
                _uiState.update { it.copy(importing = false, importError = e.message) }
                return@launch
            }
            _uiState.update { it.copy(importing = false, importJson = null, importPassphrase = "", settingsImported = true) }
            refresh()
            onImported()
        }
    }
}
