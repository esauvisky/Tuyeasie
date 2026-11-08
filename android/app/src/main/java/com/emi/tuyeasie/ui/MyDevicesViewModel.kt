package com.emi.tuyeasie.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emi.tuyeasie.data.SavedTuyaAccount
import com.emi.tuyeasie.data.SecureSettingsStore
import com.emi.tuyeasie.data.TuyaAccountKind
import com.emi.tuyeasie.data.TuyaAccountRepository
import com.emi.tuyeasie.data.TuyaManagedDevice
import com.emi.tuyeasie.data.TuyaSdkException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AccountDevicesState(
    val saved: SavedTuyaAccount,
    val loading: Boolean = false,
    val devices: List<TuyaManagedDevice>? = null,
    val error: String? = null
)

sealed interface MyDevicesConfirmation {
    data class Unpair(val saved: SavedTuyaAccount, val device: TuyaManagedDevice) : MyDevicesConfirmation
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
    val actionError: String? = null
)

class MyDevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureSettingsStore(application)
    private val repository = TuyaAccountRepository(application, store)
    // The Tuya SDK holds a single signed-in user, so sign-in + operation pairs must not interleave.
    private val sdkLock = Mutex()
    private val _uiState = MutableStateFlow(MyDevicesUiState())
    val uiState: StateFlow<MyDevicesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val saved = repository.savedAccounts()
        _uiState.update { state ->
            state.copy(
                accounts = saved.map { AccountDevicesState(it, loading = true) },
                actionError = null
            )
        }
        viewModelScope.launch {
            saved.forEach { loadDevices(it) }
        }
    }

    private suspend fun loadDevices(saved: SavedTuyaAccount) {
        val result = try {
            Result.success(sdkLock.withLock { repository.listDevices(saved.account) })
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
        _uiState.update { it.copy(unpairingDeviceId = confirmation.device.deviceId, actionError = null) }
        viewModelScope.launch {
            try {
                sdkLock.withLock {
                    repository.removeDevice(confirmation.saved.account, confirmation.device.deviceId)
                }
            } catch (e: TuyaSdkException) {
                _uiState.update { it.copy(unpairingDeviceId = null, actionError = e.message) }
                return@launch
            }
            _uiState.update { it.copy(unpairingDeviceId = null) }
            refresh()
        }
    }
}
