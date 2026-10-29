package io.github.emi.tuyaonboarding.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thingclips.smart.sdk.bean.DeviceBean
import io.github.emi.tuyaonboarding.data.ActivatedDevice
import io.github.emi.tuyaonboarding.data.AppSettings
import io.github.emi.tuyaonboarding.data.BleDeviceRepository
import io.github.emi.tuyaonboarding.data.HotspotRepository
import io.github.emi.tuyaonboarding.data.ImportState
import io.github.emi.tuyaonboarding.data.PendingDeviceRecord
import io.github.emi.tuyaonboarding.data.SecureSettingsStore
import io.github.emi.tuyaonboarding.data.TuyaAccount
import io.github.emi.tuyaonboarding.data.TuyaActivationRepository
import io.github.emi.tuyaonboarding.data.TuyaBleDevice
import io.github.emi.tuyaonboarding.data.TuyaHotspot
import io.github.emi.tuyaonboarding.hass.HomeAssistantClient
import io.github.emi.tuyaonboarding.hass.HomeAssistantError
import io.github.emi.tuyaonboarding.hass.HomeAssistantException
import io.github.emi.tuyaonboarding.hass.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

enum class WizardStep(val title: String) {
    CONNECT("Connect"),
    HOTSPOT("Hotspot"),
    ACTIVATE("Activate"),
    DONE("Done")
}

enum class HotspotStatus {
    IDLE,
    PREPARING,
    READY,
    SCANNING,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class ActivationStatus {
    IDLE,
    ACTIVATING,
    IMPORTING,
    FAILED,
    IMPORT_FAILED,
    COMPLETE
}

data class OnboardingUiState(
    val isLoading: Boolean = true,
    val step: WizardStep = WizardStep.CONNECT,
    val settings: AppSettings = AppSettings(),
    val manualMode: Boolean = false,
    val testingConnection: Boolean = false,
    val connectionVerified: Boolean = false,
    val connectionNotice: String? = null,
    val connectionError: String? = null,
    val pendingDevice: PendingDeviceRecord? = null,
    val permissionRequestVersion: Long = 0,
    val hotspots: List<TuyaHotspot> = emptyList(),
    val bleDevices: List<TuyaBleDevice> = emptyList(),
    val hotspotStatus: HotspotStatus = HotspotStatus.IDLE,
    val activationReady: Boolean = false,
    val hotspotMessage: String? = null,
    val hotspotError: String? = null,
    val manualHotspotSsid: String = "",
    val activationStatus: ActivationStatus = ActivationStatus.IDLE,
    val activationProgress: String? = null,
    val activationError: String? = null,
    val activatedDevice: ActivatedDevice? = null,
    val importResult: ImportResult? = null,
    val showLocalKey: Boolean = false
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val store by lazy { SecureSettingsStore(application) }
    private val homeAssistantClient by lazy { HomeAssistantClient(store) }
    private val activationRepository = TuyaActivationRepository(application)
    private val hotspotRepository = HotspotRepository(application)
    private val bleRepository = BleDeviceRepository()
    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(OnboardingUiState())
    private val importGeneration = AtomicLong(0)
    private var tuyaAccount: TuyaAccount? = null
    private var wifiScanPending = false
    private var bleScanPending = false
    private var bluetoothOffDuringScan = false
    private var wifiScanError: String? = null
    val uiState: kotlinx.coroutines.flow.StateFlow<OnboardingUiState> = _uiState

    init {
        activationRepository.listener = object : TuyaActivationRepository.Listener {
            override fun onProgress(message: String) {
                update {
                    copy(
                        activationProgress = message,
                        hotspotMessage = if (step == WizardStep.HOTSPOT) message else hotspotMessage
                    )
                }
            }

            override fun onReady() {
                update {
                    copy(
                        hotspotStatus = HotspotStatus.READY,
                        activationReady = true,
                        hotspotMessage = "Activation is ready. Select your device hotspot.",
                        hotspotError = null,
                        activationError = null
                    )
                }
            }

            override fun onActivationStep(step: String) {
                update { copy(activationProgress = "Activating with Tuya AP mode: $step") }
            }

            override fun onActivated(device: DeviceBean) {
                hotspotRepository.disconnect()
                update { copy(activationReady = false) }
                val activated = ActivatedDevice(
                    deviceId = device.devId.orEmpty(),
                    localKey = device.localKey.orEmpty(),
                    name = device.name.orEmpty(),
                    productId = device.productId.orEmpty(),
                    protocolVersion = device.pv,
                    category = device.category,
                    ip = device.ip
                )
                if (current.manualMode) {
                    finishManualActivation(activated)
                } else {
                    importDevice(activated)
                }
            }

            override fun onFailure(stage: String, code: String, message: String) {
                val sanitized = sanitizeError(message, current.settings, current.activatedDevice)
                val detail = if (sanitized.contains("timeout", ignoreCase = true) ||
                    sanitized.contains("timed out", ignoreCase = true) ||
                    code.contains("timeout", ignoreCase = true)
                ) {
                    "Activation timed out. Keep the device in AP mode and try again."
                } else {
                    "$stage failed ($code): $sanitized"
                }
                if (current.step == WizardStep.ACTIVATE) {
                    hotspotRepository.disconnect()
                    update {
                        copy(
                            activationStatus = ActivationStatus.FAILED,
                            activationError = detail,
                            activationProgress = null
                        )
                    }
                } else {
                    update {
                        copy(
                            hotspotStatus = HotspotStatus.ERROR,
                            activationReady = false,
                            hotspotError = detail,
                            hotspotMessage = null
                        )
                    }
                }
            }
        }
        hotspotRepository.listener = object : HotspotRepository.Listener {
            override fun onScanResults(hotspots: List<TuyaHotspot>) {
                wifiScanPending = false
                update { copy(hotspots = hotspots) }
                finishScanIfDone()
            }

            override fun onScanFailed(message: String) {
                wifiScanPending = false
                wifiScanError = message
                finishScanIfDone()
            }

            override fun onConnected(ssid: String) {
                update {
                    copy(
                        step = WizardStep.ACTIVATE,
                        hotspotStatus = HotspotStatus.CONNECTED,
                        hotspotMessage = "Connected to $ssid",
                        activationStatus = ActivationStatus.ACTIVATING,
                        activationError = null
                    )
                }
                activationRepository.startActivation(current.settings)
                hotspotRepository.onActivationStarted()
            }

            override fun onConnectionFailed(message: String) {
                activationRepository.stopActivation()
                if (current.step == WizardStep.ACTIVATE) {
                    update {
                        copy(
                            activationStatus = ActivationStatus.FAILED,
                            activationError = message,
                            activationProgress = null
                        )
                    }
                } else {
                    update {
                        copy(
                            hotspotStatus = HotspotStatus.ERROR,
                            hotspotError = message,
                            hotspotMessage = null
                        )
                    }
                }
            }
        }
        bleRepository.listener = object : BleDeviceRepository.Listener {
            override fun onDeviceFound(device: TuyaBleDevice) {
                update {
                    val known = bleDevices.any { it.uuid == device.uuid }
                    copy(
                        bleDevices = if (known) bleDevices.map {
                            if (it.uuid == device.uuid) it.copy(rssi = device.rssi) else it
                        } else bleDevices + device
                    )
                }
            }

            override fun onProductInfo(uuid: String, name: String, iconUrl: String) {
                update {
                    copy(bleDevices = bleDevices.map {
                        if (it.uuid == uuid) it.copy(productName = name, iconUrl = iconUrl) else it
                    })
                }
            }

            override fun onProductInfoFailed(uuid: String, code: String, message: String) {
                update { copy(hotspotError = "Device lookup failed for $uuid ($code): $message") }
            }

            override fun onScanFinished() {
                bleScanPending = false
                finishScanIfDone()
            }
        }
        viewModelScope.launch {
            val (settings, pending) = withContext(Dispatchers.IO) {
                tuyaAccount = store.loadOrCreateTuyaAccount()
                store.loadSettings() to store.loadPendingDevice()
            }
            update {
                copy(
                    isLoading = false,
                    settings = settings,
                    pendingDevice = pending
                )
            }
        }
    }

    fun updateHassUrl(value: String) = update {
        copy(
            settings = settings.copy(hassUrl = value),
            connectionVerified = false,
            connectionNotice = null,
            connectionError = null
        )
    }

    fun updateAdminToken(value: String) = update {
        copy(
            settings = settings.copy(adminToken = value),
            connectionVerified = false,
            connectionNotice = null,
            connectionError = null
        )
    }

    fun updateCountryCode(value: String) = update {
        copy(settings = settings.copy(countryCode = value), connectionError = null)
    }

    fun updateHomeWifiSsid(value: String) = update {
        copy(settings = settings.copy(homeWifiSsid = value), connectionError = null)
    }

    fun updateHomeWifiPassword(value: String) = update {
        copy(settings = settings.copy(homeWifiPassword = value), connectionError = null)
    }

    fun updateManualHotspotSsid(value: String) = update {
        copy(manualHotspotSsid = value, hotspotError = null)
    }

    fun setManualMode(enabled: Boolean) = update {
        copy(manualMode = enabled, connectionError = null, connectionNotice = null)
    }

    fun toggleLocalKeyVisibility() = update { copy(showLocalKey = !showLocalKey) }

    fun testConnection() {
        val settings = normalizedSettings()
        val validationError = validateSettings(settings, requireHomeAssistant = true)
        if (validationError != null) {
            update { copy(connectionError = validationError, connectionNotice = null) }
            return
        }
        update {
            copy(
                settings = settings,
                testingConnection = true,
                connectionVerified = false,
                connectionError = null,
                connectionNotice = null
            )
        }
        viewModelScope.launch {
            try {
                val connected = withContext(Dispatchers.IO) {
                    store.saveSettings(settings)
                    homeAssistantClient.testConnection()
                }
                if (!connected) {
                    throw HomeAssistantException(
                        HomeAssistantError.CONNECTION_FAILED,
                        "Home Assistant could not verify the connection."
                    )
                }
                update {
                    copy(
                        connectionVerified = true,
                        connectionNotice = "Connected. The token has administrator access.",
                        connectionError = null
                    )
                }
            } catch (exception: HomeAssistantException) {
                update {
                    copy(
                        connectionVerified = false,
                        connectionError = homeAssistantMessage(exception, settings),
                        connectionNotice = null
                    )
                }
            } finally {
                update { copy(testingConnection = false) }
            }
        }
    }

    fun continueFromConnection() {
        val settings = normalizedSettings()
        val validationError = validateSettings(settings, requireHomeAssistant = !current.manualMode)
        if (validationError != null) {
            update { copy(connectionError = validationError) }
            return
        }
        check(current.manualMode || current.connectionVerified) {
            "A verified Home Assistant connection is required"
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.saveSettings(settings) }
            update {
                copy(
                    settings = settings,
                    step = WizardStep.HOTSPOT,
                    activationReady = false,
                    connectionError = null,
                    hotspotStatus = HotspotStatus.PREPARING,
                    hotspotMessage = "Preparing secure Tuya activation…",
                    hotspotError = null
                )
            }
            prepareActivation()
        }
    }

    fun backToConnection() {
        importGeneration.incrementAndGet()
        activationRepository.reset()
        hotspotRepository.disconnect()
        update {
            copy(
                step = WizardStep.CONNECT,
                hotspots = emptyList(),
                activationReady = false,
                hotspotStatus = HotspotStatus.IDLE,
                hotspotMessage = null,
                hotspotError = null,
                activationStatus = ActivationStatus.IDLE,
                activationError = null,
                activationProgress = null
            )
        }
    }

    fun prepareActivation() {
        activationRepository.prepare(
            current.settings.countryCode.trim(),
            checkNotNull(tuyaAccount) { "Tuya account is not loaded" }
        )
        bleRepository.stop()
        update {
            copy(
                hotspots = emptyList(),
                bleDevices = emptyList(),
                hotspotStatus = HotspotStatus.PREPARING,
                activationReady = false,
                hotspotMessage = "Preparing secure Tuya activation…",
                hotspotError = null
            )
        }
    }

    fun requestDeviceScan() {
        if (!hasScanPermissions()) {
            update { copy(permissionRequestVersion = permissionRequestVersion + 1) }
            return
        }
        scanDevices()
    }

    fun onPermissionRequestConsumed() = update { copy(permissionRequestVersion = 0) }

    fun onScanPermissionsResult(granted: Boolean) {
        if (granted) {
            scanDevices()
        } else {
            val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "Permission denied. Allow Location and Nearby devices to discover the Tuya device."
            } else {
                "Permission denied. Allow Location access to discover the Tuya device."
            }
            update {
                copy(
                    hotspotStatus = HotspotStatus.ERROR,
                    hotspotError = message,
                    hotspotMessage = null
                )
            }
        }
    }

    fun connectToBleDevice(uuid: String) {
        check(current.activationReady) { "Activation is not ready" }
        val device = current.bleDevices.single { it.uuid == uuid }
        bleRepository.stop()
        hotspotRepository.disconnect()
        update {
            copy(
                step = WizardStep.ACTIVATE,
                activationStatus = ActivationStatus.ACTIVATING,
                activationError = null,
                activationProgress = null
            )
        }
        activationRepository.startBleActivation(current.settings, device)
    }

    fun connectToHotspot(ssid: String) {
        check(current.activationReady) { "Activation is not ready" }
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Automatic hotspot connection requires Android 10"
        }
        require(ssid.isNotBlank()) { "Hotspot SSID is required" }
        update {
            copy(
                hotspotStatus = HotspotStatus.CONNECTING,
                hotspotMessage = "Connecting to $ssid. Android may ask you to confirm the connection.",
                hotspotError = null
            )
        }
        bleRepository.stop()
        hotspotRepository.connect(ssid)
    }

    fun startManualActivation() {
        check(current.activationReady) { "Activation is not ready" }
        update {
            copy(
                step = WizardStep.ACTIVATE,
                activationStatus = ActivationStatus.ACTIVATING,
                activationError = null,
                activationProgress = "Starting activation on the currently connected hotspot…"
            )
        }
        activationRepository.startActivation(current.settings)
    }

    fun retryActivation() {
        if (current.activationStatus == ActivationStatus.IMPORT_FAILED) {
            retryPendingImport()
            return
        }
        hotspotRepository.disconnect()
        update {
            copy(
                step = WizardStep.HOTSPOT,
                hotspotStatus = HotspotStatus.PREPARING,
                activationReady = false,
                hotspotMessage = "Requesting a fresh activation token…",
                hotspotError = null,
                activationStatus = ActivationStatus.IDLE,
                activationError = null
            )
        }
        prepareActivation()
    }

    fun cancelActivation() {
        val failed = current.activationStatus == ActivationStatus.FAILED
        activationRepository.stopActivation()
        hotspotRepository.disconnect()
        update {
            copy(
                step = WizardStep.HOTSPOT,
                hotspotStatus = HotspotStatus.READY,
                hotspotMessage = if (failed) "Activation failed. Tap your device to try again."
                else "Activation cancelled. Reconnect or try again.",
                activationStatus = ActivationStatus.IDLE,
                activationProgress = null,
                activationError = null
            )
        }
    }

    fun retryPendingImport() {
        val pending = checkNotNull(current.pendingDevice) { "No pending device to import" }
        importDevice(pending.device)
    }

    fun showPendingCredentials() {
        val pending = checkNotNull(current.pendingDevice) { "No pending device to display" }
        update {
            copy(
                step = WizardStep.DONE,
                activatedDevice = pending.device,
                importResult = null,
                activationStatus = ActivationStatus.COMPLETE,
                activationError = pending.lastError
            )
        }
    }

    fun addAnotherDevice() {
        val generation = importGeneration.incrementAndGet()
        activationRepository.reset()
        hotspotRepository.disconnect()
        update {
            copy(
                step = WizardStep.HOTSPOT,
                pendingDevice = null,
                hotspots = emptyList(),
                bleDevices = emptyList(),
                hotspotStatus = HotspotStatus.PREPARING,
                activationReady = false,
                hotspotMessage = "Preparing the next activation…",
                hotspotError = null,
                manualHotspotSsid = "",
                activationStatus = ActivationStatus.IDLE,
                activationProgress = null,
                activationError = null,
                showLocalKey = false
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deletePendingDevice() }
            if (generation == importGeneration.get()) prepareActivation()
        }
    }

    private fun scanDevices() {
        val bluetoothOn = bleRepository.isBluetoothOn()
        wifiScanPending = true
        bleScanPending = bluetoothOn
        bluetoothOffDuringScan = !bluetoothOn
        wifiScanError = null
        update {
            copy(
                hotspotStatus = HotspotStatus.SCANNING,
                hotspotMessage = if (bluetoothOn) "Scanning for Tuya hotspots and Bluetooth devices…"
                else "Scanning for Tuya hotspots. Bluetooth is off, so Bluetooth devices will not be found.",
                hotspotError = null,
                hotspots = emptyList(),
                bleDevices = emptyList()
            )
        }
        hotspotRepository.scan()
        if (bluetoothOn) bleRepository.scan()
    }

    private fun finishScanIfDone() {
        if (wifiScanPending || bleScanPending) return
        val found = current.hotspots.isNotEmpty() || current.bleDevices.isNotEmpty()
        val notFound = buildString {
            append("No Tuya device found. Put the device in pairing mode, then scan again.")
            if (bluetoothOffDuringScan) append(" Turn on Bluetooth to find Bluetooth-paired devices.")
            wifiScanError?.let { append(" Wi-Fi scan: ").append(it) }
        }
        update {
            copy(
                hotspotStatus = if (found) HotspotStatus.READY else HotspotStatus.ERROR,
                hotspotMessage = if (found) "Tap your device to continue." else null,
                hotspotError = if (found) null else notFound
            )
        }
    }

    private fun importDevice(device: ActivatedDevice) {
        if (current.activationStatus == ActivationStatus.IMPORTING) return
        val generation = importGeneration.incrementAndGet()
        update {
            copy(
                step = WizardStep.ACTIVATE,
                activatedDevice = device,
                activationStatus = ActivationStatus.IMPORTING,
                activationProgress = "Device activated. Importing it into Home Assistant…",
                activationError = null,
                importResult = null
            )
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    store.savePendingDevice(device)
                    homeAssistantClient.importDevice(device)
                }
                if (generation != importGeneration.get()) return@launch
                check(result.loaded) { "Home Assistant returned an unloaded import result" }
                val imported = PendingDeviceRecord(device, ImportState.IMPORTED, null)
                withContext(Dispatchers.IO) {
                    if (generation == importGeneration.get()) store.savePendingDevice(imported)
                }
                if (generation != importGeneration.get()) return@launch
                update {
                    copy(
                        step = WizardStep.DONE,
                        pendingDevice = imported,
                        activatedDevice = device,
                        importResult = result,
                        activationStatus = ActivationStatus.COMPLETE,
                        activationProgress = null,
                        activationError = null
                    )
                }
            } catch (exception: HomeAssistantException) {
                if (generation != importGeneration.get()) return@launch
                val message = homeAssistantMessage(exception, current.settings)
                val failed = PendingDeviceRecord(device, ImportState.PENDING, message)
                withContext(Dispatchers.IO) {
                    if (generation == importGeneration.get()) store.savePendingDevice(failed)
                }
                if (generation != importGeneration.get()) return@launch
                update {
                    copy(
                        pendingDevice = failed,
                        activatedDevice = device,
                        activationStatus = ActivationStatus.IMPORT_FAILED,
                        activationProgress = null,
                        activationError = message,
                        importResult = null
                    )
                }
            }
        }
    }

    private fun finishManualActivation(device: ActivatedDevice) {
        update {
            copy(
                activatedDevice = device,
                activationStatus = ActivationStatus.IMPORTING,
                activationProgress = "Saving the activated device credentials…"
            )
        }
        viewModelScope.launch {
            val missingFields = buildList {
                if (device.deviceId.isBlank()) add("device ID")
                if (device.localKey.isBlank()) add("local key")
                if (device.name.isBlank()) add("device name")
                if (device.productId.isBlank()) add("product ID")
            }
            val validationError = if (missingFields.isEmpty()) null else
                "Activation returned no ${missingFields.joinToString()}. The returned credentials were saved, " +
                    "but automatic import requires all four fields."
            val pending = PendingDeviceRecord(
                device,
                ImportState.PENDING,
                validationError ?: "Manual walkthrough: Home Assistant import was not attempted."
            )
            withContext(Dispatchers.IO) { store.savePendingDevice(pending) }
            update {
                copy(
                    step = WizardStep.DONE,
                    pendingDevice = pending,
                    activationStatus = ActivationStatus.COMPLETE,
                    activationProgress = null,
                    activationError = validationError,
                    importResult = null
                )
            }
        }
    }

    private fun normalizedSettings() = current.settings.copy(
        hassUrl = current.settings.hassUrl.trim(),
        adminToken = current.settings.adminToken.trim(),
        countryCode = current.settings.countryCode.trim(),
        homeWifiSsid = current.settings.homeWifiSsid.trim()
    )

    private fun validateSettings(settings: AppSettings, requireHomeAssistant: Boolean): String? = when {
        requireHomeAssistant && settings.hassUrl.isBlank() -> "Enter the Home Assistant URL."
        requireHomeAssistant && settings.adminToken.isBlank() -> "Enter an admin long-lived access token."
        settings.countryCode.isBlank() -> "Enter your Tuya country code."
        settings.homeWifiSsid.isBlank() -> "Enter your home Wi-Fi name."
        else -> null
    }

    private fun hasScanPermissions(): Boolean {
        val context = getApplication<Application>()
        val locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val nearbyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        val bluetoothGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        return locationGranted && nearbyGranted && bluetoothGranted
    }

    private val current: OnboardingUiState
        get() = _uiState.value

    private fun update(transform: OnboardingUiState.() -> OnboardingUiState) {
        _uiState.value = current.transform()
    }

    override fun onCleared() {
        importGeneration.incrementAndGet()
        activationRepository.close()
        hotspotRepository.close()
        bleRepository.close()
        super.onCleared()
    }
}

private fun homeAssistantMessage(
    exception: HomeAssistantException,
    settings: AppSettings
): String {
    val message = when (exception.error) {
        HomeAssistantError.UNAUTHORIZED ->
            "Invalid Home Assistant token. Create an admin long-lived access token and try again."
        HomeAssistantError.NON_ADMIN ->
            "This token belongs to a non-admin user. Use an administrator token."
        HomeAssistantError.CONNECTION_FAILED,
        HomeAssistantError.TIMEOUT ->
            "Home Assistant is unreachable. Check the URL, network, and that Home Assistant is running."
        HomeAssistantError.UNSUPPORTED_PROFILE,
        HomeAssistantError.UNSUPPORTED_PROTOCOL ->
            "This device is not supported by the installed Tuya Local integration."
        else -> exception.publicMessage
    }
    return sanitizeError(message, settings, device = null)
}

private fun sanitizeError(
    error: String,
    settings: AppSettings,
    device: ActivatedDevice?
): String {
    var sanitized = error
    listOfNotNull(
        settings.adminToken,
        settings.homeWifiPassword,
        settings.homeWifiSsid,
        device?.localKey
    ).filter { it.isNotEmpty() }
        .forEach { secret -> sanitized = sanitized.replace(secret, "[redacted]") }
    return sanitized.take(1000)
}
