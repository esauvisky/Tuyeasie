package com.emi.tuyeasie.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thingclips.smart.sdk.bean.DeviceBean
import com.emi.tuyeasie.data.ActivatedDevice
import com.emi.tuyeasie.data.AccountAuth
import com.emi.tuyeasie.data.AppSettings
import com.emi.tuyeasie.data.BleDeviceRepository
import com.emi.tuyeasie.data.AddressSource
import com.emi.tuyeasie.data.CameraOnvifException
import com.emi.tuyeasie.data.CameraOnvifRepository
import com.emi.tuyeasie.data.DeviceAddress
import com.emi.tuyeasie.data.DeviceAddressException
import com.emi.tuyeasie.data.DeviceAddressResolver
import com.emi.tuyeasie.data.DetectedStream
import com.emi.tuyeasie.data.DeviceCapabilities
import com.emi.tuyeasie.data.CountryCodeProvider
import com.emi.tuyeasie.data.Credentials
import com.emi.tuyeasie.data.HaIntegrationOption
import com.emi.tuyeasie.data.HaEntryAssociation
import com.emi.tuyeasie.data.IntegrationKeys
import com.emi.tuyeasie.data.IntegrationStatus

import com.emi.tuyeasie.data.HotspotRepository
import com.emi.tuyeasie.data.NearbyNetwork
import com.emi.tuyeasie.data.NetworkReport
import com.emi.tuyeasie.data.ManualDataPoint
import com.emi.tuyeasie.data.OnvifClient
import com.emi.tuyeasie.data.OnvifDiscovery
import com.emi.tuyeasie.data.OnvifException
import com.emi.tuyeasie.data.OnvifInfo
import com.emi.tuyeasie.data.OnvifRecord
import com.emi.tuyeasie.data.OpenPort
import com.emi.tuyeasie.data.PortScanner
import com.emi.tuyeasie.data.SecureSettingsStore
import com.emi.tuyeasie.data.TuyaAccount
import com.emi.tuyeasie.data.TuyaActivationRepository
import com.emi.tuyeasie.data.TuyaBleDevice
import com.emi.tuyeasie.data.TuyaHotspot
import com.emi.tuyeasie.data.TuyaAccountRepository
import com.emi.tuyeasie.data.deviceCapabilities
import com.emi.tuyeasie.data.TuyaSdkException
import com.emi.tuyeasie.hass.HomeAssistantClient
import com.emi.tuyeasie.hass.HomeAssistantError
import com.emi.tuyeasie.hass.HomeAssistantException
import com.emi.tuyeasie.hass.HomeAssistantProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

enum class PairStage { SETUP, NEARBY, PAIRING }
enum class HotspotStatus { IDLE, PREPARING, READY, SCANNING, CONNECTING, CONNECTED, ERROR }
enum class ActivationStatus { IDLE, ACTIVATING, IMPORTING, FAILED, COMPLETE }
enum class OnvifStatus { IDLE, CONFIGURING, FAILED, COMPLETE }
enum class ScanStatus { IDLE, SCANNING }

private const val UNVERIFIED_ONVIF_MESSAGE = "ONVIF setup has not been verified for this device."
private const val NETWORK_SCAN_SETTLE_MILLIS = 4_000L
const val MANUAL_PAIRING_KEY = "manual"

data class OnboardingUiState(
    val isLoading: Boolean = true,
    val stage: PairStage = PairStage.SETUP,
    val pairingKey: String? = null,
    val pairingName: String? = null,
    val pairingIconUrl: String? = null,
    val pairingBluetooth: Boolean = false,
    val deviceScreenOpen: Boolean = false,
    val integrationChooserVisible: Boolean = false,
    val notice: String? = null,
    val settings: AppSettings = AppSettings(),
    val testingConnection: Boolean = false,
    val connectionVerified: Boolean = false,
    val connectionError: String? = null,
    val permissionRequestVersion: Long = 0,
    val hotspots: List<TuyaHotspot> = emptyList(),
    val bleDevices: List<TuyaBleDevice> = emptyList(),
    val hotspotStatus: HotspotStatus = HotspotStatus.IDLE,
    val activationReady: Boolean = false,
    val hotspotMessage: String? = null,
    val hotspotError: String? = null,
    val networks: List<NearbyNetwork> = emptyList(),
    val networksError: String? = null,
    val activationStatus: ActivationStatus = ActivationStatus.IDLE,
    val activationProgress: String? = null,
    val activationError: String? = null,
    val activatedDevice: ActivatedDevice? = null,
    val showLocalKey: Boolean = false,
    val cameraAccountDevice: TuyaBleDevice? = null,
    val cameraEmail: String = "",
    val cameraPassword: String = "",
    val cameraAuth: AccountAuth = AccountAuth.LOGIN,
    val cameraAccountError: String? = null,
    val preparingCameraAccount: Boolean = false,
    val onvifStatus: OnvifStatus = OnvifStatus.IDLE,
    val onvifError: String? = null,
    val onvifProgress: String? = null,
    val onvifRecord: OnvifRecord? = null,
    val showOnvifPassword: Boolean = false,
    val capabilities: DeviceCapabilities? = null,
    val integrationOptions: List<HaIntegrationOption> = emptyList(),
    val chooserSelection: Set<String> = emptySet(),
    val manualDataPoints: Map<String, ManualDataPoint> = emptyMap(),
    val scanStatus: ScanStatus = ScanStatus.IDLE,
    val scanProgress: String? = null,
    val scanError: String? = null,
    val networkReport: NetworkReport? = null
) {
    val busy: Boolean
        get() = activationStatus == ActivationStatus.ACTIVATING || testingConnection || preparingCameraAccount ||
            onvifStatus == OnvifStatus.CONFIGURING || integrationOptions.any { it.status == IntegrationStatus.ADDING }
}

private fun OnboardingUiState.withoutDeviceState() = copy(
    integrationChooserVisible = false,
    chooserSelection = emptySet(),
    integrationOptions = emptyList(),
    manualDataPoints = emptyMap(),
    onvifProgress = null,
    onvifStatus = OnvifStatus.IDLE,
    onvifError = null,
    onvifRecord = null,
    showOnvifPassword = false,
    networkReport = null,
    scanStatus = ScanStatus.IDLE,
    scanProgress = null,
    scanError = null
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val store by lazy { SecureSettingsStore(application) }
    private val homeAssistantClient by lazy { HomeAssistantClient(store) }
    private val activationRepository = TuyaActivationRepository(application)
    private val hotspotRepository = HotspotRepository(application)
    private val bleRepository = BleDeviceRepository()
    private val onvifRepository = CameraOnvifRepository(application)
    private val accountRepository by lazy { TuyaAccountRepository(application, store) }
    private val addressResolver = DeviceAddressResolver(application)
    private val sdkLock = kotlinx.coroutines.sync.Mutex()
    private var activeTuyaAccount: TuyaAccount? = null
    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(OnboardingUiState())
    private val importGeneration = AtomicLong(0)
    private val scanGeneration = AtomicLong(0)
    private var pendingCameraDevice: TuyaBleDevice? = null
    private var pendingCameraAccount: TuyaAccount? = null
    private var pendingAfterReady: (() -> Unit)? = null
    private var sessionIsCamera = false
    private var pendingLookupUuid: String? = null
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
                        hotspotMessage = if (stage == PairStage.NEARBY) message else hotspotMessage
                    )
                }
            }

            override fun onReady() {
                val action = pendingAfterReady
                if (action != null) {
                    pendingAfterReady = null
                    action()
                    return
                }
                update {
                    copy(
                        hotspotStatus = HotspotStatus.READY,
                        activationReady = true,
                        hotspotMessage = "Activation is ready. Select your device hotspot.",
                        hotspotError = null,
                        activationError = null
                    )
                }
                requestDeviceScan()
            }

            override fun onActivationStep(step: String) {
                update { copy(activationProgress = "Activating with Tuya AP mode: $step") }
            }

            override fun onActivated(device: DeviceBean) {
                hotspotRepository.disconnect()
                update { copy(activationReady = false) }
                val activated = ActivatedDevice(
                    deviceId = device.devId,
                    localKey = device.localKey,
                    name = device.name.orEmpty(),
                    productId = device.productId.orEmpty(),
                    protocolVersion = device.pv,
                    // DeviceBean leaves `category` empty for cameras; `deviceCategory` carries the code (e.g. "sp").
                    category = device.deviceCategory,
                    ip = device.ip
                )
                store.saveCapabilities(device.devId, deviceCapabilities(device))
                importDevice(activated)
            }

            override fun onFailure(stage: String, code: String, message: String) {
                if (current.preparingCameraAccount) {
                    val device = pendingCameraDevice
                    val attempted = pendingCameraAccount
                    pendingCameraDevice = null
                    pendingCameraAccount = null
                    pendingAfterReady = null
                    val detail = "$stage failed ($code): " +
                        sanitizeError(message, current.settings, current.activatedDevice, attempted?.password, *accountSecrets())
                    update {
                        if (device != null) {
                            copy(
                                preparingCameraAccount = false,
                                hotspotStatus = HotspotStatus.READY,
                                cameraAccountDevice = device,
                                cameraEmail = checkNotNull(attempted) { "Camera attempt has no account" }.uid,
                                cameraPassword = attempted.password,
                                cameraAccountError = detail
                            )
                        } else {
                            copy(
                                preparingCameraAccount = false,
                                hotspotStatus = HotspotStatus.ERROR,
                                hotspotMessage = null,
                                hotspotError = detail
                            )
                        }
                    }
                    return
                }
                pendingCameraDevice = null
                pendingCameraAccount = null
                pendingAfterReady = null
                val sanitized = sanitizeError(message, current.settings, current.activatedDevice, *accountSecrets())
                val detail = if (sanitized.contains("timeout", ignoreCase = true) ||
                    sanitized.contains("timed out", ignoreCase = true) ||
                    code.contains("timeout", ignoreCase = true)
                ) {
                    "Activation timed out. Keep the device in AP mode and try again."
                } else {
                    "$stage failed ($code): $sanitized"
                }
                if (current.stage == PairStage.PAIRING) {
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
                        stage = PairStage.PAIRING,
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
                if (current.stage == PairStage.PAIRING) {
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
                            pairingKey = null,
                            pairingName = null,
                            pairingIconUrl = null,
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
                val lookup = pendingLookupUuid == uuid
                if (lookup) pendingLookupUuid = null
                if (lookup && current.stage == PairStage.NEARBY) {
                    routeBleDevice(current.bleDevices.single { it.uuid == uuid })
                }
            }

            override fun onProductInfoFailed(uuid: String, code: String, message: String) {
                if (pendingLookupUuid == uuid) {
                    pendingLookupUuid = null
                    update { copy(hotspotStatus = HotspotStatus.ERROR, hotspotMessage = null) }
                }
                update { copy(hotspotError = "Device lookup failed for $uuid ($code): $message") }
            }

            override fun onScanFinished() {
                bleScanPending = false
                finishScanIfDone()
            }
        }
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) { store.loadSettings() }
            update { copy(isLoading = false, settings = settings) }
            // A saved, complete setup goes straight to pairing once Home Assistant answers.
            if (validateSettings(settings) == null) testConnection()
        }
    }

    fun updateHassUrl(value: String) = update {
        copy(
            settings = settings.copy(hassUrl = value),
            connectionVerified = false,
            connectionError = null
        )
    }

    fun updateAdminToken(value: String) = update {
        copy(
            settings = settings.copy(adminToken = value),
            connectionVerified = false,
            connectionError = null
        )
    }

    fun updateHomeWifiSsid(value: String) = update {
        copy(settings = settings.copy(homeWifiSsid = value), connectionError = null)
    }

    fun updateHomeWifiPassword(value: String) = update {
        copy(settings = settings.copy(homeWifiPassword = value), connectionError = null)
    }

    fun saveWifi() {
        val settings = current.settings
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.saveSettings(settings) }
            update { copy(notice = "Home Wi-Fi saved.") }
        }
    }

    fun refreshNetworks(userInitiated: Boolean = false) {
        if (!hasScanPermissions()) {
            // After a denial the message stays until the user taps refresh, which asks again.
            if (userInitiated || current.networksError == null) {
                update { copy(networksError = null, permissionRequestVersion = permissionRequestVersion + 1) }
            }
            return
        }
        update { copy(networksError = null) }
        hotspotRepository.requestNearbyScan()
        viewModelScope.launch {
            // Give the scan a moment to land; the cached list is shown immediately either way.
            update { copy(networks = hotspotRepository.readNearbyNetworks()) }
            delay(NETWORK_SCAN_SETTLE_MILLIS)
            update { copy(networks = hotspotRepository.readNearbyNetworks()) }
        }
    }

    fun toggleLocalKeyVisibility() = update { copy(showLocalKey = !showLocalKey) }

    fun testConnection() {
        val settings = normalizedSettings()
        val validationError = validateSettings(settings)
        if (validationError != null) {
            update { copy(connectionError = validationError) }
            return
        }
        update {
            copy(
                settings = settings,
                testingConnection = true,
                connectionVerified = false,
                connectionError = null
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
                        connectionError = null
                    )
                }
                if (current.stage == PairStage.SETUP) enterNearby()
            } catch (exception: HomeAssistantException) {
                update {
                    copy(
                        connectionVerified = false,
                        connectionError = homeAssistantMessage(exception, settings)
                    )
                }
            } finally {
                update { copy(testingConnection = false) }
            }
        }
    }

    private fun enterNearby() {
        update {
            copy(
                stage = PairStage.NEARBY,
                activationReady = false,
                hotspotStatus = HotspotStatus.PREPARING,
                hotspotMessage = "Preparing secure Tuya activation…",
                hotspotError = null
            )
        }
        prepareActivation()
    }

    fun prepareActivation() {
        sessionIsCamera = false
        pendingCameraDevice = null
        pendingCameraAccount = null
        pendingAfterReady = null
        pendingLookupUuid = null
        val account = store.loadOrCreateTuyaAccount()
        activeTuyaAccount = account
        activationRepository.prepare(
            CountryCodeProvider.current(getApplication()),
            account,
            AccountAuth.LOGIN_OR_REGISTER
        )
        bleRepository.stop()
        update {
            copy(
                preparingCameraAccount = false,
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
            refreshNetworks()
            if (current.stage == PairStage.NEARBY && current.activationReady) scanDevices()
        } else if (current.stage != PairStage.NEARBY) {
            update {
                copy(networksError = "Permission denied. Allow Location and Nearby devices to list nearby Wi-Fi networks, then tap refresh.")
            }
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
        // A tap while a lookup, account dialog or account switch is in flight is a double-tap, not an error.
        if (pendingLookupUuid != null || current.cameraAccountDevice != null || current.preparingCameraAccount) return
        check(current.activationReady) { "Activation is not ready" }
        val device = current.bleDevices.single { it.uuid == uuid }
        if (device.productName == null) {
            pendingLookupUuid = uuid
            update {
                copy(
                    hotspotStatus = HotspotStatus.CONNECTING,
                    hotspotMessage = "Looking up device information…",
                    hotspotError = null
                )
            }
            return
        }
        routeBleDevice(device)
    }

    private fun routeBleDevice(device: TuyaBleDevice) {
        bleRepository.stop()
        bleScanPending = false
        if (device.productName.orEmpty().contains("camera", ignoreCase = true)) {
            // Read storage, not a cache: My Devices can forget the account while this screen lives.
            val saved = store.loadCameraAccount()
            if (saved != null) {
                prepareCameraAccount(saved, device, isNew = false, auth = AccountAuth.LOGIN_OR_REGISTER)
                return
            }
            update {
                copy(
                    hotspotStatus = HotspotStatus.READY,
                    cameraAccountDevice = device,
                    cameraEmail = "",
                    cameraPassword = "",
                    cameraAuth = AccountAuth.LOGIN,
                    cameraAccountError = null
                )
            }
            return
        }
        if (sessionIsCamera) {
            switchToGeneratedAccount { startBleActivation(device) }
            return
        }
        startBleActivation(device)
    }

    fun updateCameraEmail(value: String) = update { copy(cameraEmail = value, cameraAccountError = null) }

    fun updateCameraAuth(value: AccountAuth) = update { copy(cameraAuth = value, cameraAccountError = null) }

    fun updateCameraPassword(value: String) = update { copy(cameraPassword = value, cameraAccountError = null) }

    fun dismissCameraAccount() {
        if (current.preparingCameraAccount) return
        update {
            copy(
                cameraAccountDevice = null,
                cameraEmail = "",
                cameraPassword = "",
                cameraAccountError = null
            )
        }
        // A failed camera attempt leaves activation unprepared; re-prepare so Scan again works.
        if (!current.activationReady) prepareActivation()
    }

    fun confirmCameraAccount() {
        if (current.preparingCameraAccount) return
        val device = checkNotNull(current.cameraAccountDevice) { "No camera is waiting for an account" }
        val email = current.cameraEmail.trim()
        val password = current.cameraPassword
        if (!email.contains("@") || password.isBlank()) {
            update { copy(cameraAccountError = "Enter an email address and a password.") }
            return
        }
        prepareCameraAccount(TuyaAccount(email, password), device, isNew = true, auth = current.cameraAuth)
    }

    // New credentials are persisted only after the account signed in and the session is ready.
    private fun prepareCameraAccount(account: TuyaAccount, device: TuyaBleDevice, isNew: Boolean, auth: AccountAuth) {
        sessionIsCamera = true
        pendingCameraDevice = device
        pendingCameraAccount = account
        pendingAfterReady = {
            viewModelScope.launch {
                if (isNew) {
                    // preparingCameraAccount and the pending camera stay set while saving, so Back and taps stay blocked.
                    withContext(Dispatchers.IO) { store.saveCameraAccount(account) }
                }
                if (pendingCameraDevice === device && pendingCameraAccount === account &&
                    current.preparingCameraAccount && current.stage == PairStage.NEARBY
                ) {
                    markSessionReady()
                    startBleActivation(device)
                }
            }
        }
        update {
            copy(
                preparingCameraAccount = true,
                cameraAccountError = null,
                activationReady = false,
                hotspotStatus = HotspotStatus.PREPARING,
                hotspotMessage = "Switching to the camera account…",
                hotspotError = null
            )
        }
        activeTuyaAccount = account
        activationRepository.prepare(CountryCodeProvider.current(getApplication()), account, auth)
    }

    private fun markSessionReady() {
        pendingCameraDevice = null
        pendingCameraAccount = null
        update {
            copy(
                hotspotStatus = HotspotStatus.READY,
                activationReady = true,
                cameraAccountDevice = null,
                cameraEmail = "",
                cameraPassword = "",
                cameraAccountError = null,
                preparingCameraAccount = false
            )
        }
    }

    private fun switchToGeneratedAccount(action: () -> Unit) {
        sessionIsCamera = false
        pendingCameraDevice = null
        pendingCameraAccount = null
        pendingAfterReady = {
            markSessionReady()
            action()
        }
        update {
            copy(
                preparingCameraAccount = true,
                activationReady = false,
                hotspotStatus = HotspotStatus.PREPARING,
                hotspotMessage = "Switching back to the generated account…",
                hotspotError = null
            )
        }
        activationRepository.prepare(
            CountryCodeProvider.current(getApplication()),
            store.loadOrCreateTuyaAccount(),
            AccountAuth.LOGIN_OR_REGISTER
        )
    }

    private fun startBleActivation(device: TuyaBleDevice) {
        hotspotRepository.disconnect()
        update {
            copy(
                stage = PairStage.PAIRING,
                pairingKey = device.uuid,
                pairingName = checkNotNull(device.productName) { "Bluetooth device ${device.uuid} has no product name" },
                pairingIconUrl = device.iconUrl,
                pairingBluetooth = true,
                activationStatus = ActivationStatus.ACTIVATING,
                activationError = null,
                activationProgress = "Activating over Bluetooth…"
            )
        }
        activationRepository.startBleActivation(current.settings, device)
    }

    fun connectToHotspot(ssid: String) {
        if (current.preparingCameraAccount) return
        check(current.activationReady) { "Activation is not ready" }
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Automatic hotspot connection requires Android 10"
        }
        require(ssid.isNotBlank()) { "Hotspot SSID is required" }
        if (sessionIsCamera) {
            switchToGeneratedAccount { connectToHotspot(ssid) }
            return
        }
        update {
            copy(
                pairingKey = ssid,
                pairingName = ssid,
                pairingIconUrl = null,
                pairingBluetooth = false,
                hotspotStatus = HotspotStatus.CONNECTING,
                hotspotMessage = "Connecting to $ssid. Android may ask you to confirm the connection.",
                hotspotError = null
            )
        }
        bleRepository.stop()
        hotspotRepository.connect(ssid)
    }

    fun startManualActivation() {
        if (current.preparingCameraAccount) return
        check(current.activationReady) { "Activation is not ready" }
        if (sessionIsCamera) {
            switchToGeneratedAccount { startManualActivation() }
            return
        }
        update {
            copy(
                stage = PairStage.PAIRING,
                pairingKey = MANUAL_PAIRING_KEY,
                pairingName = "Current Wi-Fi hotspot",
                pairingIconUrl = null,
                pairingBluetooth = false,
                activationStatus = ActivationStatus.ACTIVATING,
                activationError = null,
                activationProgress = "Starting activation on the currently connected hotspot…"
            )
        }
        activationRepository.startActivation(current.settings)
    }

    fun retryActivation() {
        hotspotRepository.disconnect()
        update {
            copy(
                stage = PairStage.NEARBY,
                pairingKey = null,
                pairingName = null,
                pairingIconUrl = null,
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
                stage = PairStage.NEARBY,
                pairingKey = null,
                pairingName = null,
                pairingIconUrl = null,
                hotspotStatus = HotspotStatus.READY,
                hotspotMessage = if (failed) "Activation failed. Tap your device to try again."
                else "Activation cancelled. Reconnect or try again.",
                activationStatus = ActivationStatus.IDLE,
                activationProgress = null,
                activationError = null
            )
        }
    }

    fun openDeviceSetup(device: ActivatedDevice, capabilities: DeviceCapabilities, account: TuyaAccount? = null) {
        activeTuyaAccount = account

        store.saveCapabilities(device.deviceId, capabilities)
        val existing = store.loadOnvifRecord(device.deviceId)
        val options = store.loadIntegrationOptions(device.deviceId)
        update {
            copy(
                deviceScreenOpen = true,
                activatedDevice = device,
                capabilities = capabilities,
                integrationOptions = options,
                networkReport = store.loadNetworkReport(device.deviceId),
                manualDataPoints = capabilities.dataPoints.associate { it.id to ManualDataPoint(it.id, null) },
                onvifRecord = existing,
                onvifStatus = if (existing?.verified == true) OnvifStatus.COMPLETE else OnvifStatus.IDLE,
                onvifError = if (existing != null && !existing.verified) UNVERIFIED_ONVIF_MESSAGE else null,
                scanStatus = ScanStatus.IDLE,
                scanProgress = null,
                scanError = null,
                integrationChooserVisible = false
            )
        }
        viewModelScope.launch {
            if (current.networkReport == null) rescanNetwork()
            refreshDataPoints()
        }
    }

    fun closeDeviceScreen() {
        check(!deviceScreenBusy()) { "The device screen is busy" }
        clearDeviceScreen()
    }

    private fun deviceScreenBusy() = current.deviceScreenOpen && (
        current.activationStatus == ActivationStatus.IMPORTING ||
            current.onvifStatus == OnvifStatus.CONFIGURING ||
            current.integrationOptions.any { it.status == IntegrationStatus.ADDING }
        )

    private fun clearDeviceScreen() = update {
        copy(deviceScreenOpen = false, activatedDevice = null, showLocalKey = false).withoutDeviceState()
    }

    private fun resetPairing() {
        importGeneration.incrementAndGet()
        activationRepository.reset()
        hotspotRepository.disconnect()
        update {
            copy(
                stage = PairStage.NEARBY,
                pairingKey = null,
                pairingName = null,
                pairingIconUrl = null,
                activationStatus = ActivationStatus.IDLE,
                activationProgress = null,
                activationError = null
            )
        }
        prepareActivation()
    }

    private fun scanDevices() {
        pendingLookupUuid = null
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
        // Finding nothing is an expected outcome; only a failed Wi-Fi scan is an error.
        val notFound = buildString {
            append("No device found. Reset it until its light blinks fast, keep it close, then tap Scan.")
            if (bluetoothOffDuringScan) append(" Bluetooth is off, so Bluetooth devices can't be found.")
        }
        update {
            copy(
                hotspotStatus = HotspotStatus.READY,
                hotspotMessage = if (found) "Tap your device to pair it." else notFound,
                hotspotError = wifiScanError?.let { "Wi-Fi scan failed: $it" }
            )
        }
    }

    private fun importDevice(device: ActivatedDevice) {
        if (current.activationStatus == ActivationStatus.IMPORTING) return
        val generation = importGeneration.incrementAndGet()
        val capabilities = checkNotNull(store.loadCapabilities(device.deviceId)) { "No capabilities saved for ${device.deviceId}" }
        update {
            withoutDeviceState().copy(
                activatedDevice = device,
                capabilities = capabilities,
                activationStatus = ActivationStatus.IMPORTING,
                activationProgress = if (capabilities.tuyaOnvif) "Enabling ONVIF before scanning…" else "Finding device on the local network…",
                activationError = null,
                integrationChooserVisible = false
            )
        }
        viewModelScope.launch {
            if (generation != importGeneration.get()) return@launch
            var onvif = withContext(Dispatchers.IO) { store.loadOnvifRecord(device.deviceId)?.takeIf { it.verified } }
            var onvifError: String? = null
            if (capabilities.tuyaOnvif && onvif == null) {
                try {
                    signInToCamera()
                    val configured = onvifRepository.configure(
                        device, knownOnvifPassword(device.deviceId), targetPassword = null,
                        onProgress = { text -> if (generation == importGeneration.get()) update { copy(activationProgress = text) } }
                    ) { password -> withContext(Dispatchers.IO) { store.savePendingOnvifPassword(device.deviceId, password) } }
                    onvif = withContext(Dispatchers.IO) { keepEntryIds(configured).also(store::saveOnvifRecord) }
                } catch (e: CameraOnvifException) {
                    onvifError = sanitizeError(checkNotNull(e.message), current.settings, device, *accountSecrets())
                }
            }
            if (generation != importGeneration.get()) return@launch
            update { copy(activationProgress = "Resolving address and scanning ports…") }
            val address = try {
                withContext(Dispatchers.IO) { locate(device.deviceId, capabilities) }
            } catch (e: DeviceAddressException) {
                update { copy(scanError = e.message) }
                null
            }
            val resolved = device.copy(ip = address?.ip ?: device.ip, protocolVersion = address?.protocolVersion ?: device.protocolVersion)
            if (generation != importGeneration.get()) return@launch
            update {
                copy(activatedDevice = resolved, capabilities = capabilities, onvifRecord = onvif,
                    onvifStatus = when { onvifError != null -> OnvifStatus.FAILED; onvif != null -> OnvifStatus.COMPLETE; else -> OnvifStatus.IDLE },
                    onvifError = onvifError, activationStatus = ActivationStatus.COMPLETE, activationProgress = null,
                    deviceScreenOpen = true, manualDataPoints = capabilities.dataPoints.associate { it.id to ManualDataPoint(it.id, null) })
            }
            resetPairing()
            if (address == null) {
                val options = withContext(Dispatchers.IO) { store.loadIntegrationOptions(device.deviceId) }
                update { copy(integrationOptions = options, scanError = "No local address was found, so nothing can be added to Home Assistant yet.") }
            } else {
                launchNetworkScan(device.deviceId, capabilities, address, showChooserWhenComplete = true)
            }
        }
    }

    // Blocking.
    private fun locate(deviceId: String, capabilities: DeviceCapabilities): DeviceAddress =
        addressResolver.resolve(
            deviceId,
            capabilities,
            store.loadOnvifRecord(deviceId),
            Credentials.candidates(preferredCredentials(deviceId))
        )

    private fun preferredCredentials(deviceId: String): Credentials? {
        val record = store.loadOnvifRecord(deviceId)
        if (record != null) return Credentials(record.username, record.password)
        val user = store.loadPendingOnvifUsername(deviceId)
        val pass = store.loadPendingOnvifPassword(deviceId)
        if (user != null || pass != null) {
            return Credentials(
                user ?: CameraOnvifRepository.DEFAULT_USERNAME,
                pass ?: CameraOnvifRepository.DEFAULT_PASSWORD
            )
        }
        return null
    }


    // Blocking.
    private fun probeServices(
        address: DeviceAddress,
        ports: List<OpenPort>,
        preferred: Credentials?
    ): Pair<NetworkReport, Credentials?> {
        val candidates = Credentials.candidates(preferred)
        val serviceUrls = (
            OnvifDiscovery.discover(getApplication()).filter { it.ip == address.ip }.flatMap { it.serviceUrls } +
                ports.filter { it.service == PortScanner.SERVICE_ONVIF }
                    .map { "http://${address.ip}:${it.port}/onvif/device_service" }
            ).distinct()
        var onvif: OnvifInfo? = null
        var onvifError: String? = null
        if (serviceUrls.isNotEmpty()) {
            try {
                onvif = OnvifClient().probe(serviceUrls.first(), candidates)
            } catch (exception: OnvifException) {
                onvifError = exception.message
            } catch (exception: IOException) {
                onvifError = "${exception.javaClass.simpleName}: ${exception.message}"
            } catch (exception: IllegalStateException) {
                onvifError = exception.message
            }
        }
        val rtspPorts = ports.filter { it.service == PortScanner.SERVICE_RTSP }.map { it.port }
        val (guessed, guessedCredentials) = if (onvif == null || onvif.streams.isEmpty()) {
            PortScanner.guessRtspStreams(address.ip, rtspPorts, candidates)
        } else emptyList<DetectedStream>() to null
        val matched = onvif?.credentials ?: guessedCredentials
        val report = NetworkReport(
            ip = address.ip,
            addressSource = address.source,
            scannedAt = System.currentTimeMillis(),
            openPorts = ports,
            onvif = onvif,
            onvifError = onvifError?.let { sanitizeError(it, current.settings, null, preferred?.password) },
            portScanStreams = guessed
        )
        return report to matched
    }

    private fun launchNetworkScan(
        deviceId: String,
        capabilities: DeviceCapabilities,
        address: DeviceAddress,
        showChooserWhenComplete: Boolean = false
    ) {
        val generation = scanGeneration.incrementAndGet()
        fun updateIfCurrent(transform: OnboardingUiState.() -> OnboardingUiState) =
            update { if (activatedDevice?.deviceId == deviceId && scanGeneration.get() == generation) transform() else this }
        updateIfCurrent { copy(scanStatus = ScanStatus.SCANNING, scanProgress = "Scanning ports on ${address.ip}…", scanError = null) }
        viewModelScope.launch {
            val ports = PortScanner.scan(address.ip) { done, total ->
                viewModelScope.launch { updateIfCurrent { copy(scanProgress = "Scanning ports: $done of $total") } }
            }
            if (scanGeneration.get() != generation) return@launch
            updateIfCurrent { copy(scanProgress = "Checking services on ${address.ip}…") }
            val preferred = withContext(Dispatchers.IO) { preferredCredentials(deviceId) }
            val (report, matchedCredentials) = withContext(Dispatchers.IO) { probeServices(address, ports, preferred) }
            if (scanGeneration.get() != generation) return@launch
            withContext(Dispatchers.IO) { store.saveNetworkReport(deviceId, report) }
            // Without Home Assistant the flow handlers are unknown, so the saved catalog stays as it was.
            val handlers = try {
                withContext(Dispatchers.IO) { homeAssistantClient.availableFlowHandlers() }
            } catch (e: HomeAssistantException) {
                updateIfCurrent { copy(connectionVerified = false, connectionError = homeAssistantMessage(e, settings)) }
                null
            }
            val options = withContext(Dispatchers.IO) {
                val previous = store.loadIntegrationOptions(deviceId)
                if (handlers == null) previous
                else buildIntegrationCatalog(capabilities, report, handlers, previous).also { store.saveIntegrationOptions(deviceId, it) }
            }
            if (scanGeneration.get() != generation) return@launch
            val adopted = if (current.onvifRecord == null && matchedCredentials != null) {
                withContext(Dispatchers.IO) { adoptScannedStreams(deviceId, address, report, matchedCredentials) }
            } else null
            updateIfCurrent {
                copy(
                    networkReport = report,
                    integrationOptions = options,
                    scanStatus = ScanStatus.IDLE,
                    scanProgress = null,
                    onvifRecord = adopted ?: onvifRecord,
                    onvifStatus = if (adopted != null && onvifStatus != OnvifStatus.CONFIGURING) OnvifStatus.COMPLETE else onvifStatus,
                    integrationChooserVisible = integrationChooserVisible ||
                        (showChooserWhenComplete && handlers != null && options.any { it.status == IntegrationStatus.AVAILABLE })
                )
            }
        }
    }

    private fun adoptScannedStreams(
        deviceId: String,
        address: DeviceAddress,
        report: NetworkReport,
        credentials: Credentials
    ): OnvifRecord? {
        val streams = report.onvif?.streams.orEmpty().ifEmpty { report.portScanStreams }
        if (streams.isEmpty()) return null
        val main = streams.first()
        val sub = streams.getOrNull(1)
        val previous = store.loadOnvifRecord(deviceId)
        val unchanged = previous != null && previous.mainUrl == main.url && previous.subUrl == sub?.url &&
            previous.username == credentials.username && previous.password == credentials.password
        return OnvifRecord(
            deviceId = deviceId,
            address = address.ip,
            username = credentials.username,
            password = credentials.password,
            mainUrl = main.url,
            subUrl = sub?.url,
            entryIds = previous?.entryIds.orEmpty(),
            mainCodec = main.codec,
            mainResolution = main.resolution,
            subCodec = sub?.codec,
            subResolution = sub?.resolution,
            verified = report.onvif != null
        ).also(store::saveOnvifRecord)
    }

    fun rescanNetwork() {
        val device = checkNotNull(current.activatedDevice) { "No device to scan" }
        val capabilities = checkNotNull(current.capabilities) { "No capabilities for ${device.deviceId}" }
        check(current.scanStatus == ScanStatus.IDLE) { "A scan is already running" }
        val generation = scanGeneration.incrementAndGet()
        update { copy(scanStatus = ScanStatus.SCANNING, scanProgress = "Finding the device on the local network…", scanError = null) }
        viewModelScope.launch {
            val address = try {
                withContext(Dispatchers.IO) { locate(device.deviceId, capabilities) }
            } catch (exception: DeviceAddressException) {
                update {
                    if (activatedDevice?.deviceId != device.deviceId || scanGeneration.get() != generation) this
                    else copy(scanStatus = ScanStatus.IDLE, scanProgress = null, scanError = "Local address unknown: ${exception.message}")
                }
                return@launch
            }
            if (scanGeneration.get() != generation) return@launch
            launchNetworkScan(device.deviceId, capabilities, address)
        }
    }

    // DP commands are rejected with PERMISSION_DENIED unless the signed-in user owns the camera.
    private suspend fun signInToCamera() {
        val account = store.loadCameraAccount()
            ?: throw CameraOnvifException("Tuya account", "No camera account is saved, so the camera's settings can't be changed.")
        try {
            accountRepository.signIn(account)
        } catch (e: TuyaSdkException) {
            throw CameraOnvifException("Tuya account", checkNotNull(e.message))
        }
    }

    // A fresh setup result knows nothing about Home Assistant; the entries made for the earlier record still exist.
    private fun keepEntryIds(record: OnvifRecord): OnvifRecord =
        record.copy(entryIds = store.loadOnvifRecord(record.deviceId)?.entryIds.orEmpty())

    // The pending password is the most recent one sent to the camera; saving a record clears it.
    private fun knownOnvifPassword(deviceId: String): String? =
        store.loadPendingOnvifPassword(deviceId) ?: store.loadOnvifRecord(deviceId)?.password

    fun retryOnvif() {
        val device = checkNotNull(current.activatedDevice) { "No activated device for ONVIF" }
        check(checkNotNull(current.capabilities).tuyaOnvif) { "${device.deviceId} has no Tuya ONVIF data points" }
        check(current.onvifStatus == OnvifStatus.FAILED || current.onvifStatus == OnvifStatus.IDLE) {
            "ONVIF is ${current.onvifStatus}"
        }
        val generation = importGeneration.get()
        markOnvifConfiguring()
        viewModelScope.launch { configureOnvif(device, targetPassword = null, generation) }
    }

    /** Advanced: sets a new ONVIF password on a camera whose ONVIF is controlled through Tuya data points. */
    fun changeCameraOnvifPassword(newPassword: String) {
        val device = checkNotNull(current.activatedDevice) { "No activated device for ONVIF" }
        check(checkNotNull(current.capabilities).tuyaOnvif) { "${device.deviceId} has no Tuya ONVIF data points" }
        check(newPassword.isNotBlank()) { "ONVIF password is empty" }
        check(current.onvifStatus != OnvifStatus.CONFIGURING) { "ONVIF is already being configured" }
        val generation = importGeneration.get()
        markOnvifConfiguring()
        viewModelScope.launch { configureOnvif(device, newPassword, generation) }
    }

    /** Advanced: the credentials a device's own ONVIF and RTSP servers accept. The device itself is not changed. */
    fun saveDeviceCredentials(username: String, password: String) {
        val device = checkNotNull(current.activatedDevice) { "No device for credentials" }
        check(username.isNotBlank()) { "Username is empty" }
        check(current.scanStatus == ScanStatus.IDLE) { "A network scan is already running" }
        val record = current.onvifRecord?.copy(username = username, password = password)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.saveOnvifCredentials(device.deviceId, username, password)
                if (record != null) store.saveOnvifRecord(record)
            }
            update { copy(onvifRecord = record, notice = "Credentials saved. Rescanning…") }
            rescanNetwork()
        }
    }

    private fun markOnvifConfiguring() {
        scanGeneration.incrementAndGet()
        update {
            copy(
                onvifStatus = OnvifStatus.CONFIGURING,
                onvifError = null,
                onvifProgress = "Starting ONVIF setup…",
                showOnvifPassword = false,
                scanStatus = ScanStatus.IDLE,
                scanProgress = null,
                scanError = null
            )
        }
    }

    private suspend fun configureOnvif(device: ActivatedDevice, targetPassword: String?, generation: Long) {
        val capabilities = checkNotNull(current.capabilities) { "No capabilities for ${device.deviceId}" }
        val existingPassword = withContext(Dispatchers.IO) { knownOnvifPassword(device.deviceId) }
        if (generation != importGeneration.get()) return
        val errors = mutableListOf<String>()
        val record = try {
            signInToCamera()
            onvifRepository.configure(
                device,
                existingPassword,
                targetPassword,
                onProgress = { message ->
                    if (generation == importGeneration.get()) update { copy(onvifProgress = message) }
                }
            ) { password ->
                withContext(Dispatchers.IO) { store.savePendingOnvifPassword(device.deviceId, password) }
            }.let { configured -> withContext(Dispatchers.IO) { keepEntryIds(configured).also(store::saveOnvifRecord) } }
        } catch (exception: CameraOnvifException) {
            if (generation != importGeneration.get()) return
            val sentPassword = withContext(Dispatchers.IO) { knownOnvifPassword(device.deviceId) }
            errors += "ONVIF setup failed: " + sanitizeError(
                checkNotNull(exception.message), current.settings, device, existingPassword, sentPassword, targetPassword,
                *accountSecrets()
            )
            null
        }
        if (generation != importGeneration.get()) return
        update {
            copy(
                onvifStatus = if (errors.isEmpty()) OnvifStatus.COMPLETE else OnvifStatus.FAILED,
                onvifRecord = record ?: onvifRecord,
                onvifError = errors.joinToString("\n").ifEmpty { null },
                onvifProgress = null
            )
        }
        if (record != null) {
            launchNetworkScan(device.deviceId, capabilities, DeviceAddress(record.address, AddressSource.ONVIF_DATA_POINT, null))
        }
    }

    fun toggleOnvifPasswordVisibility() = update { copy(showOnvifPassword = !showOnvifPassword) }

    fun dismissIntegrationChooser() = update { copy(integrationChooserVisible = false, chooserSelection = emptySet()) }

    fun toggleChooserOption(key: String) = update {
        copy(chooserSelection = if (key in chooserSelection) chooserSelection - key else chooserSelection + key)
    }

    fun addChosenIntegrations() {
        val keys = current.chooserSelection
        check(keys.isNotEmpty()) { "No integration selected" }
        update { copy(integrationChooserVisible = false, chooserSelection = emptySet()) }
        keys.forEach(::addIntegration)
    }

    fun consumeNotice() = update { copy(notice = null) }

    fun addIntegration(key: String) {
        val option = current.integrationOptions.single { it.key == key }
        val device = checkNotNull(current.activatedDevice) { "No device for integration" }
        val report = checkNotNull(current.networkReport) { "No completed device scan" }
        val adding = option.copy(status = IntegrationStatus.ADDING, error = null, updatedAt = System.currentTimeMillis())
        updateIntegration(key) { adding }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.updateIntegrationOption(device.deviceId, adding) }
            try {
                val record = current.onvifRecord
                val association = withContext(Dispatchers.IO) {
                    val credentials = record?.let { Credentials(it.username, it.password) }
                        ?: preferredCredentials(device.deviceId) ?: Credentials.DEFAULT
                    homeAssistantClient.addIntegration(option, device, report, credentials)
                }
                persistIntegrationSuccess(key, association)
            } catch (e: HomeAssistantException) {
                if (key == IntegrationKeys.TUYA_LOCAL && e.error == HomeAssistantError.UNSUPPORTED_PROFILE) {
                    failIntegration(key, "Tuya Local has no profile for product ${device.productId}.")
                } else failIntegration(key, e.publicMessage)
            }
        }
    }

    private suspend fun persistIntegrationSuccess(key: String, association: HaEntryAssociation) {
        val deviceId = checkNotNull(current.activatedDevice) { "No active device after integration add" }.deviceId
        val old = current.integrationOptions.single { it.key == key }
        val saved = old.copy(
            status = IntegrationStatus.ADDED,
            entryId = association.entryId,
            deviceRegistryId = association.deviceRegistryId,
            openUrl = association.openUrl,
            error = null,
            updatedAt = System.currentTimeMillis()
        )
        withContext(Dispatchers.IO) { store.updateIntegrationOption(deviceId, saved) }
        updateIntegration(key) { saved }
    }

    private suspend fun failIntegration(key: String, message: String) {
        val deviceId = checkNotNull(current.activatedDevice) { "No active device after integration failure" }.deviceId
        val old = current.integrationOptions.single { it.key == key }
        val failed = old.copy(status = IntegrationStatus.FAILED, error = message, updatedAt = System.currentTimeMillis())
        withContext(Dispatchers.IO) { store.updateIntegrationOption(deviceId, failed) }
        updateIntegration(key) { failed }
    }

    private fun updateIntegration(key: String, transform: (HaIntegrationOption) -> HaIntegrationOption) = update {
        copy(integrationOptions = integrationOptions.map { if (it.key == key) transform(it) else it })
    }

    fun openIntegration(key: String, open: (String) -> Unit) {
        val option = current.integrationOptions.single { it.key == key }
        check(option.status == IntegrationStatus.ADDED) { "$key is not added to Home Assistant" }
        open(checkNotNull(option.openUrl) { "Added integration has no Home Assistant URL" })
    }

    fun refreshDataPoints() {
        val device = checkNotNull(current.activatedDevice) { "No device is open" }
        val ids = checkNotNull(current.capabilities) { "No device schema is loaded" }.dataPoints.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id -> updateDataPoint(id) { it.copy(reading = true, error = null) } }
            try {
                val values = withContext(Dispatchers.IO) { readDeviceDataPoints(device, ids) }
                ids.forEach { id -> updateDataPoint(id) { it.copy(value = values[id], reading = false) } }
            } catch (e: com.emi.tuyeasie.data.TuyaSdkException) {
                ids.forEach { id -> updateDataPoint(id) { it.copy(reading = false, error = e.message) } }
            }
        }
    }

    fun setDataPoint(id: String, raw: String) {
        val device = checkNotNull(current.activatedDevice) { "No device is open" }
        val schema = checkNotNull(current.capabilities) { "No device schema is loaded" }.dataPointById(id)
        check(schema.writable) { "Data point $id is not writable" }
        val value = parseDataPointValue(schema, raw)
        updateDataPoint(id) { it.copy(writing = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { publishDataPoint(device, id, value) }
                updateDataPoint(id) { it.copy(value = value, writing = false) }
                readDataPoint(id)
            } catch (e: com.emi.tuyeasie.data.TuyaSdkException) {
                updateDataPoint(id) { it.copy(writing = false, error = e.message) }
            }
        }
    }

    fun readDataPoint(id: String) {
        val device = checkNotNull(current.activatedDevice) { "No device is open" }
        checkNotNull(current.capabilities).dataPointById(id)
        updateDataPoint(id) { it.copy(reading = true, error = null) }
        viewModelScope.launch {
            try {
                val value = withContext(Dispatchers.IO) { readDeviceDataPoints(device, listOf(id))[id] }
                updateDataPoint(id) { it.copy(value = value, reading = false) }
            } catch (e: com.emi.tuyeasie.data.TuyaSdkException) {
                updateDataPoint(id) { it.copy(reading = false, error = e.message) }
            }
        }
    }

    private suspend fun readDeviceDataPoints(device: ActivatedDevice, ids: List<String>): Map<String, Any?> =
        sdkLock.withLock {
            check(ids.isNotEmpty()) { "At least one data-point ID is required" }
            val account = checkNotNull(activeTuyaAccount) { "No Tuya account owns this device session" }
            accountRepository.signIn(account)
            val sdk = com.thingclips.smart.home.sdk.ThingHomeSdk.newDeviceInstance(device.deviceId)
            awaitDataPointResult("Read data points", sdk) { callback -> sdk.getDpList(ids, callback) }
            val values = checkNotNull(com.thingclips.smart.home.sdk.ThingHomeSdk.getDataInstance().getDeviceBean(device.deviceId)) {
                "Tuya SDK has no device bean for ${device.deviceId}"
            }.dps
            // DPs the device has never reported are absent from the bean.
            ids.associateWith { id -> values[id] }
        }

    private suspend fun publishDataPoint(device: ActivatedDevice, id: String, value: Any) = sdkLock.withLock {
        val account = checkNotNull(activeTuyaAccount) { "No Tuya account owns this device session" }
        accountRepository.signIn(account)
        val sdk = com.thingclips.smart.home.sdk.ThingHomeSdk.newDeviceInstance(device.deviceId)
        val payload = com.alibaba.fastjson.JSON.toJSONString(mapOf(id to value))
        awaitDataPointResult("Set data point $id", sdk) { callback -> sdk.publishDps(payload, callback) }
    }

    private suspend fun awaitDataPointResult(
        stage: String,
        sdk: com.thingclips.smart.sdk.api.IThingDevice,
        start: (com.thingclips.smart.sdk.api.IResultCallback) -> Unit
    ) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
        start(object : com.thingclips.smart.sdk.api.IResultCallback {
            override fun onSuccess() {
                continuation.resume(Unit)
            }
            override fun onError(code: String, error: String?) {
                continuation.resumeWithException(com.emi.tuyeasie.data.TuyaSdkException(stage, code, error.orEmpty()))
            }
        })
    }


    private fun updateDataPoint(id: String, transform: (ManualDataPoint) -> ManualDataPoint) = update {
        val state = manualDataPoints[id] ?: error("No displayed state for data point $id")
        copy(manualDataPoints = manualDataPoints + (id to transform(state)))
    }

    private fun parseDataPointValue(schema: com.emi.tuyeasie.data.TuyaDataPointSchema, raw: String): Any {
        check(schema.writable) { "Data point ${schema.id} is read-only" }
        val property = com.alibaba.fastjson.JSON.parseObject(schema.propertyJson)
        return when (schema.editorType) {
            "boolean" -> raw.toBooleanStrict()
            "enum" -> raw.also { value ->
                val range = checkNotNull(property.getJSONArray("range")) { "Enum DP ${schema.id} has no range" }
                check((0 until range.size).any { range.getString(it) == value }) { "Value is not in the declared range" }
            }
            "integer" -> raw.toInt().also { number ->
                val min = property.getInteger("min")
                val max = property.getInteger("max")
                check(min == null || number >= min) { "Value is below minimum $min" }
                check(max == null || number <= max) { "Value is above maximum $max" }
            }
            "number" -> raw.toDouble().also { number ->
                val min = property.getDouble("min")
                val max = property.getDouble("max")
                check(min == null || number >= min) { "Value is below minimum $min" }
                check(max == null || number <= max) { "Value is above maximum $max" }
            }
            "string" -> raw
            else -> error("Data point ${schema.id} has no supported editor")
        }
    }

    private fun accountSecrets(): Array<String?> = arrayOf(
        store.loadExistingTuyaAccount()?.password,
        store.loadCameraAccount()?.password,
        pendingCameraAccount?.password,
        current.cameraPassword
    )

    private fun normalizedSettings() = current.settings.copy(
        hassUrl = current.settings.hassUrl.trim(),
        adminToken = current.settings.adminToken.trim(),
        countryCode = CountryCodeProvider.current(getApplication()),
        homeWifiSsid = current.settings.homeWifiSsid.trim()
    )

    private fun validateSettings(settings: AppSettings): String? = when {
        settings.hassUrl.isBlank() -> "Enter the Home Assistant URL."
        settings.adminToken.isBlank() -> "Enter an admin long-lived access token."
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
    device: ActivatedDevice?,
    vararg secrets: String?
): String {
    var sanitized = error
    listOfNotNull(
        settings.adminToken,
        settings.homeWifiPassword,
        settings.homeWifiSsid,
        device?.localKey,
        *secrets
    ).filter { it.isNotEmpty() }
        .forEach { secret -> sanitized = sanitized.replace(secret, "[redacted]") }
    return sanitized.take(1000)
}
