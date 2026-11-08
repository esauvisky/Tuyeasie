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
import io.github.emi.tuyaonboarding.data.AccountAuth
import io.github.emi.tuyaonboarding.data.AppSettings
import io.github.emi.tuyaonboarding.data.BleDeviceRepository
import io.github.emi.tuyaonboarding.data.CameraOnvifException
import io.github.emi.tuyaonboarding.data.CameraOnvifRepository
import io.github.emi.tuyaonboarding.data.CountryCodeProvider
import io.github.emi.tuyaonboarding.data.HotspotRepository
import io.github.emi.tuyaonboarding.data.ImportState
import io.github.emi.tuyaonboarding.data.NearbyNetwork
import io.github.emi.tuyaonboarding.data.OnvifRecord
import io.github.emi.tuyaonboarding.data.PendingDeviceRecord
import io.github.emi.tuyaonboarding.data.SecureSettingsStore
import io.github.emi.tuyaonboarding.data.TuyaAccount
import io.github.emi.tuyaonboarding.data.TuyaActivationRepository
import io.github.emi.tuyaonboarding.data.TuyaBleDevice
import io.github.emi.tuyaonboarding.data.TuyaHotspot
import io.github.emi.tuyaonboarding.data.TuyaAccountRepository
import io.github.emi.tuyaonboarding.data.TuyaLanScanner
import io.github.emi.tuyaonboarding.data.TuyaSdkException
import io.github.emi.tuyaonboarding.hass.Go2RtcClient
import io.github.emi.tuyaonboarding.hass.HomeAssistantClient
import io.github.emi.tuyaonboarding.hass.HomeAssistantError
import io.github.emi.tuyaonboarding.hass.HomeAssistantException
import io.github.emi.tuyaonboarding.hass.HomeAssistantProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

enum class PairStage {
    SETUP,
    NEARBY,
    PAIRING
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
    COMPLETE
}

enum class OnvifStatus {
    IDLE,
    CONFIGURING,
    FAILED,
    COMPLETE
}

enum class StreamStatus {
    IDLE,
    REGISTERING,
    FAILED,
    COMPLETE
}

enum class StreamDestination(val storedName: String) {
    TUYA_LOCAL("tuya_local"),
    GENERIC_CAMERA("generic_camera"),
    GO2RTC("go2rtc")
}

data class AddedDevice(
    val name: String,
    val homeAssistantUrl: String
)

private const val ONVIF_USERNAME = "admin"
private const val UNVERIFIED_ONVIF_MESSAGE =
    "ONVIF was never verified for this camera. Its feed uses the camera's last known address and password."
internal const val CAMERA_CATEGORY = "sp"
private const val NETWORK_SCAN_SETTLE_MILLIS = 4_000L
const val MANUAL_PAIRING_KEY = "manual"

data class OnboardingUiState(
    val isLoading: Boolean = true,
    val stage: PairStage = PairStage.SETUP,
    val pairingKey: String? = null,
    val pairingName: String? = null,
    val pairingIconUrl: String? = null,
    val pairingBluetooth: Boolean = false,
    val sheetOpen: Boolean = false,
    val added: AddedDevice? = null,
    val notice: String? = null,
    val settings: AppSettings = AppSettings(),
    val testingConnection: Boolean = false,
    val connectionVerified: Boolean = false,
    val connectionError: String? = null,
    val pendingDevice: PendingDeviceRecord? = null,
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
    val onvifPasswordInput: String = CameraOnvifRepository.DEFAULT_PASSWORD,
    val onvifRecord: OnvifRecord? = null,
    val showOnvifPassword: Boolean = false,
    val showDestinationDialog: Boolean = false,
    val streamDestination: StreamDestination? = null,
    val go2rtcUrlError: String? = null,
    val streamStatus: StreamStatus = StreamStatus.IDLE,
    val streamError: String? = null,
    val mainError: String? = null,
    val subError: String? = null,
    val mainEntryId: String? = null,
    val subEntryId: String? = null,
    val homeAssistantEntryId: String? = null
) {
    val busy: Boolean
        get() = activationStatus == ActivationStatus.ACTIVATING ||
            activationStatus == ActivationStatus.IMPORTING ||
            testingConnection ||
            preparingCameraAccount ||
            onvifStatus == OnvifStatus.CONFIGURING ||
            streamStatus == StreamStatus.REGISTERING
}

fun OnboardingUiState.homeAssistantEntryUrl(entryId: String) =
    settings.hassUrl.trimEnd('/') + "/config/integrations/integration/tuya_local#config_entry=$entryId"

private fun OnboardingUiState.withoutCameraState() = copy(
    homeAssistantEntryId = null,
    onvifProgress = null,
    onvifPasswordInput = CameraOnvifRepository.DEFAULT_PASSWORD,
    onvifStatus = OnvifStatus.IDLE,
    onvifError = null,
    onvifRecord = null,
    showOnvifPassword = false,
    showDestinationDialog = false,
    streamDestination = null,
    go2rtcUrlError = null,
    streamStatus = StreamStatus.IDLE,
    streamError = null,
    mainError = null,
    subError = null,
    mainEntryId = null,
    subEntryId = null
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val store by lazy { SecureSettingsStore(application) }
    private val homeAssistantClient by lazy { HomeAssistantClient(store) }
    private val activationRepository = TuyaActivationRepository(application)
    private val hotspotRepository = HotspotRepository(application)
    private val bleRepository = BleDeviceRepository()
    private val onvifRepository = CameraOnvifRepository()
    private val accountRepository by lazy { TuyaAccountRepository(application, store) }
    private val go2rtcClient = Go2RtcClient()
    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(OnboardingUiState())
    private val importGeneration = AtomicLong(0)
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
                    deviceId = device.devId.orEmpty(),
                    localKey = device.localKey.orEmpty(),
                    name = device.name.orEmpty(),
                    productId = device.productId.orEmpty(),
                    protocolVersion = device.pv,
                    // DeviceBean leaves `category` empty for cameras; `deviceCategory` carries the code (e.g. "sp").
                    category = device.deviceCategory,
                    ip = device.ip
                )
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
            val (settings, pending) = withContext(Dispatchers.IO) {
                store.loadSettings() to store.loadPendingDevice()
            }
            update {
                copy(
                    isLoading = false,
                    settings = settings,
                    pendingDevice = pending
                )
            }
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
        activationRepository.prepare(
            CountryCodeProvider.current(getApplication()),
            store.loadOrCreateTuyaAccount(),
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

    fun retryPendingImport() {
        val pending = checkNotNull(current.pendingDevice) { "No pending device to import" }
        importDevice(pending.device)
    }

    fun openDeviceSetup(device: ActivatedDevice) {
        check(!current.busy) { "Onboarding is busy; cannot open device setup" }
        viewModelScope.launch {
            val (onvif, lastSent) = withContext(Dispatchers.IO) {
                store.loadOnvifRecord(device.deviceId) to knownOnvifPassword(device.deviceId)
            }
            update {
                copy(
                    sheetOpen = true,
                    pendingDevice = null,
                    activatedDevice = device,
                    showLocalKey = false
                ).withoutCameraState().copy(
                    onvifPasswordInput = lastSent ?: CameraOnvifRepository.DEFAULT_PASSWORD,
                    onvifRecord = onvif,
                    onvifStatus = when {
                        onvif == null -> OnvifStatus.IDLE
                        onvif.verified -> OnvifStatus.COMPLETE
                        else -> OnvifStatus.FAILED
                    },
                    onvifError = if (onvif != null && !onvif.verified) UNVERIFIED_ONVIF_MESSAGE else null,
                    connectionError = null
                )
            }
            val entryId = try {
                withContext(Dispatchers.IO) { homeAssistantClient.tuyaLocalEntryId(device.deviceId) }
            } catch (exception: HomeAssistantException) {
                update { copy(connectionVerified = false, connectionError = homeAssistantMessage(exception, settings)) }
                return@launch
            }
            val record = PendingDeviceRecord(
                device,
                if (entryId != null) ImportState.IMPORTED else ImportState.PENDING,
                null
            )
            withContext(Dispatchers.IO) { store.savePendingDevice(record) }
            // tuyaLocalEntryId opens an admin-verified session, so success proves the connection.
            update { copy(pendingDevice = record, homeAssistantEntryId = entryId, connectionVerified = true) }
        }
    }

    fun consumeAdded() = update { copy(added = null) }

    fun consumeNotice() = update { copy(notice = null) }

    fun closeSheet() {
        check(!sheetBusy()) { "The device sheet is busy" }
        clearSheet()
    }

    private fun sheetBusy() = current.sheetOpen && (
        current.activationStatus == ActivationStatus.IMPORTING ||
            current.onvifStatus == OnvifStatus.CONFIGURING ||
            current.streamStatus == StreamStatus.REGISTERING
        )

    private fun clearSheet() = update {
        copy(sheetOpen = false, activatedDevice = null, pendingDevice = null, showLocalKey = false).withoutCameraState()
    }

    // Leaves the device sheet's state alone: a failure hands its device to the sheet right after this reset.
    private fun resetPairing(added: AddedDevice?) {
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
                activationError = null,
                added = added
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
        val isCamera = device.category == CAMERA_CATEGORY
        update {
            copy(
                activatedDevice = device,
                activationStatus = ActivationStatus.IMPORTING,
                activationProgress = if (isCamera) "Activated. Configuring ONVIF…"
                else "Activated. Importing into Home Assistant…",
                activationError = null
            ).withoutCameraState()
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.savePendingDevice(device) }
            if (generation != importGeneration.get()) return@launch
            var onvifConfigured: OnvifRecord? = withContext(Dispatchers.IO) {
                store.loadOnvifRecord(device.deviceId)
            }
            var onvifFailure: CameraOnvifException? = null
            if (isCamera && onvifConfigured?.verified != true && device.deviceId.isNotBlank()) {
                if (generation != importGeneration.get()) return@launch
                val existingPassword = withContext(Dispatchers.IO) { knownOnvifPassword(device.deviceId) }
                try {
                    signInToCamera()
                    val record = onvifRepository.configure(
                        device,
                        existingPassword,
                        onProgress = { message ->
                            if (generation == importGeneration.get()) {
                                update { copy(activationProgress = "Configuring ONVIF: $message") }
                            }
                        }
                    ) { password ->
                        withContext(Dispatchers.IO) {
                            store.savePendingOnvifPassword(device.deviceId, password)
                        }
                    }
                    withContext(Dispatchers.IO) { store.saveOnvifRecord(record) }
                    onvifConfigured = record
                } catch (exception: CameraOnvifException) {
                    // ONVIF failing doesn't stop the import; the device is added anyway and the error is shown.
                    onvifFailure = exception
                }
            }
            if (generation != importGeneration.get()) return@launch
            // A failed ONVIF run may still have moved the camera to a new DHCP address, so rediscover it.
            val existingLanIp = onvifConfigured?.address
                ?: device.ip?.trim()?.takeIf { onvifFailure == null && HomeAssistantProtocol.isRfc1918Address(it) }
            val existingLanProto = device.protocolVersion?.trim()
                ?.takeIf(HomeAssistantProtocol::isSupportedProtocolVersion)
            val lanDiscovered = if (existingLanIp == null || existingLanProto == null) {
                update { copy(activationProgress = "Discovering device on local network…") }
                withContext(Dispatchers.IO) { TuyaLanScanner.findDevice(device.deviceId) }
            } else {
                null
            }
            if (generation != importGeneration.get()) return@launch
            update {
                copy(activationProgress = "Importing device into Home Assistant…")
            }
            val deviceForImport = device.copy(
                ip = existingLanIp ?: lanDiscovered?.ip ?: device.ip,
                protocolVersion = existingLanProto ?: lanDiscovered?.protocolVersion ?: device.protocolVersion
            )
            if (onvifFailure != null) onvifConfigured = unverifiedOnvifRecord(deviceForImport)
            try {
                val result = withContext(Dispatchers.IO) {
                    store.savePendingDevice(deviceForImport)
                    homeAssistantClient.importDevice(deviceForImport)
                }
                if (generation != importGeneration.get()) return@launch
                check(result.loaded) { "Home Assistant returned an unloaded import result" }
                val imported = PendingDeviceRecord(deviceForImport, ImportState.IMPORTED, null)
                withContext(Dispatchers.IO) {
                    if (generation == importGeneration.get()) {
                        store.savePendingDevice(imported)
                    }
                }
                if (generation != importGeneration.get()) return@launch
                val onvifError = onvifFailure?.let { failure ->
                    val pendingOnvifPassword = withContext(Dispatchers.IO) { knownOnvifPassword(device.deviceId) }
                    sanitizeError(
                        checkNotNull(failure.message),
                        current.settings,
                        deviceForImport,
                        pendingOnvifPassword,
                        *accountSecrets()
                    )
                }
                update {
                    copy(
                        pendingDevice = imported,
                        activatedDevice = deviceForImport,
                        onvifRecord = onvifConfigured,
                        onvifStatus = when {
                            onvifFailure != null -> OnvifStatus.FAILED
                            onvifConfigured != null -> OnvifStatus.COMPLETE
                            else -> OnvifStatus.IDLE
                        },
                        onvifError = onvifError?.let { "ONVIF setup failed: $it" },
                        streamDestination = defaultDestination(),
                        streamStatus = StreamStatus.IDLE,
                        mainEntryId = null,
                        subEntryId = null
                    )
                }
                if (onvifConfigured != null && current.connectionVerified) {
                    update { copy(activationProgress = "Adding the camera feed to Home Assistant…") }
                    registerStreams(generation)
                    if (generation != importGeneration.get()) return@launch
                }
                if (!isCamera || (current.onvifStatus == OnvifStatus.COMPLETE && current.streamStatus == StreamStatus.COMPLETE)) {
                    val added = AddedDevice(deviceForImport.name, current.homeAssistantEntryUrl(result.entryId))
                    if (current.sheetOpen) clearSheet()
                    resetPairing(added)
                    return@launch
                }
                if (isCamera && onvifConfigured == null) {
                    update {
                        copy(
                            onvifStatus = OnvifStatus.FAILED,
                            onvifError = listOfNotNull(
                                onvifError,
                                "The camera's local IP address is unknown, so its feed was not added."
                            ).joinToString("\n")
                        )
                    }
                }
                resetPairing(added = null)
                update { copy(sheetOpen = true, homeAssistantEntryId = result.entryId) }
            } catch (exception: HomeAssistantException) {
                if (generation != importGeneration.get()) return@launch
                val message = homeAssistantMessage(exception, current.settings)
                val failed = PendingDeviceRecord(deviceForImport, ImportState.PENDING, message)
                withContext(Dispatchers.IO) {
                    if (generation == importGeneration.get()) store.savePendingDevice(failed)
                }
                if (generation != importGeneration.get()) return@launch
                resetPairing(added = null)
                update { copy(sheetOpen = true, pendingDevice = failed, activatedDevice = deviceForImport) }
            }
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

    // The pending password is the most recent one sent to the camera; saving a record clears it.
    private fun knownOnvifPassword(deviceId: String): String? =
        store.loadPendingOnvifPassword(deviceId) ?: store.loadOnvifRecord(deviceId)?.password

    // Returns null when the camera's LAN address is unknown, so there is no feed URL to register.
    private suspend fun unverifiedOnvifRecord(device: ActivatedDevice): OnvifRecord? {
        val address = device.ip?.trim()?.takeIf(HomeAssistantProtocol::isRfc1918Address) ?: return null
        return withContext(Dispatchers.IO) {
            val record = onvifRepository.unverifiedRecord(device, address, knownOnvifPassword(device.deviceId))
            store.saveOnvifRecord(record)
            record
        }
    }

    fun retryOnvif() {
        val device = checkNotNull(current.activatedDevice) { "No activated device for ONVIF" }
        check(device.deviceId.isNotBlank()) { "Cannot retry ONVIF without a Tuya device ID" }
        check(current.onvifStatus == OnvifStatus.FAILED || current.onvifStatus == OnvifStatus.IDLE) {
            "ONVIF is ${current.onvifStatus}"
        }
        val generation = importGeneration.get()
        markOnvifConfiguring()
        viewModelScope.launch { configureOnvif(device, generation) }
    }

    private fun markOnvifConfiguring() = update {
        copy(
            onvifStatus = OnvifStatus.CONFIGURING,
            onvifError = null,
            onvifProgress = "Starting ONVIF setup…",
            onvifRecord = null,
            showOnvifPassword = false,
            showDestinationDialog = false,
            streamStatus = StreamStatus.IDLE,
            streamError = null,
            mainError = null,
            subError = null,
            mainEntryId = null,
            subEntryId = null
        )
    }

    private suspend fun configureOnvif(device: ActivatedDevice, generation: Long) {
        val existingPassword = withContext(Dispatchers.IO) { knownOnvifPassword(device.deviceId) }
        if (generation != importGeneration.get()) return
        val errors = mutableListOf<String>()
        val record = try {
            signInToCamera()
            onvifRepository.configure(
                device,
                existingPassword,
                onProgress = { message ->
                    if (generation == importGeneration.get()) update { copy(onvifProgress = message) }
                }
            ) { password ->
                withContext(Dispatchers.IO) { store.savePendingOnvifPassword(device.deviceId, password) }
            }.also { configured -> withContext(Dispatchers.IO) { store.saveOnvifRecord(configured) } }
        } catch (exception: CameraOnvifException) {
            if (generation != importGeneration.get()) return
            val sentPassword = withContext(Dispatchers.IO) { knownOnvifPassword(device.deviceId) }
            errors += "ONVIF setup failed: " + sanitizeError(
                checkNotNull(exception.message), current.settings, device, existingPassword, sentPassword, *accountSecrets()
            )
            update { copy(onvifProgress = "Finding the camera on the local network…") }
            val found = withContext(Dispatchers.IO) { TuyaLanScanner.findDevice(device.deviceId) }
            unverifiedOnvifRecord(device.copy(ip = found?.ip ?: device.ip))
        }
        if (generation != importGeneration.get()) return
        if (record == null) errors += "The camera's local IP address is unknown, so its feed was not added."
        // Cycling the IP mode can give the camera a new DHCP address; Tuya Local must follow it. Compare with the
        // host Tuya Local actually uses: re-importing an unchanged host opens a second connection the camera refuses.
        val haHost = if (record != null && current.connectionVerified) {
            update { copy(onvifProgress = "Checking the camera's address in Home Assistant…") }
            try {
                withContext(Dispatchers.IO) { homeAssistantClient.tuyaLocalHost(device.deviceId) }
            } catch (exception: HomeAssistantException) {
                errors += "Could not read the camera's address from Home Assistant: " +
                    homeAssistantMessage(exception, current.settings)
                null
            }
        } else null
        if (generation != importGeneration.get()) return
        if (record != null && haHost != null && haHost != record.address) {
            update { copy(onvifProgress = "The camera moved from $haHost to ${record.address}. Updating Home Assistant…") }
            val moved = device.copy(ip = record.address)
            try {
                val result = withContext(Dispatchers.IO) { homeAssistantClient.importDevice(moved) }
                val imported = PendingDeviceRecord(moved, ImportState.IMPORTED, null)
                withContext(Dispatchers.IO) { store.savePendingDevice(imported) }
                if (generation != importGeneration.get()) return
                update { copy(activatedDevice = moved, pendingDevice = imported, homeAssistantEntryId = result.entryId) }
            } catch (exception: HomeAssistantException) {
                errors += "Could not update the camera's address in Home Assistant: " +
                    homeAssistantMessage(exception, current.settings)
            }
        }
        val lastSent = withContext(Dispatchers.IO) { knownOnvifPassword(device.deviceId) }
        if (generation != importGeneration.get()) return
        update {
            copy(
                onvifStatus = if (errors.isEmpty()) OnvifStatus.COMPLETE else OnvifStatus.FAILED,
                onvifPasswordInput = lastSent ?: CameraOnvifRepository.DEFAULT_PASSWORD,
                onvifRecord = record,
                onvifError = errors.joinToString("\n").ifEmpty { null },
                onvifProgress = null
            )
        }
        if (record != null && current.connectionVerified) {
            update { copy(streamDestination = streamDestination ?: defaultDestination()) }
            registerStreams(generation)
        }
    }

    fun toggleOnvifPasswordVisibility() = update { copy(showOnvifPassword = !showOnvifPassword) }

    fun updateOnvifPasswordInput(value: String) = update { copy(onvifPasswordInput = value) }

    // The typed password is the one the camera has now. It is stored before use, so a retry keeps it.
    fun useOnvifPasswordInput() {
        val device = checkNotNull(current.activatedDevice) { "No device for the ONVIF password" }
        val password = current.onvifPasswordInput
        check(password.isNotBlank()) { "ONVIF password is empty" }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.savePendingOnvifPassword(device.deviceId, password) }
            retryOnvif()
        }
    }

    fun selectDestination(destination: StreamDestination) =
        update { copy(streamDestination = destination, go2rtcUrlError = null) }

    fun updateGo2rtcUrl(value: String) =
        update { copy(settings = settings.copy(go2rtcUrl = value), go2rtcUrlError = null) }

    fun updateGo2rtcUsername(value: String) =
        update { copy(settings = settings.copy(go2rtcUsername = value), go2rtcUrlError = null) }

    fun updateGo2rtcPassword(value: String) =
        update { copy(settings = settings.copy(go2rtcPassword = value), go2rtcUrlError = null) }

    fun saveWifi() {
        val settings = normalizedSettings()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.saveSettings(settings) }
            update { copy(settings = settings, notice = "Home Wi-Fi saved.") }
        }
    }

    fun selectDefaultDestination(destination: StreamDestination) =
        update { copy(settings = settings.copy(feedDestination = destination.storedName), go2rtcUrlError = null) }

    fun saveFeedSettings() {
        if (!validGo2rtcTarget(current.settings.feedDestination == StreamDestination.GO2RTC.storedName)) return
        val settings = current.settings
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.saveSettings(settings) }
            update { copy(notice = "Camera feed settings saved.") }
        }
    }

    // go2rtc is only parsed when it is the destination in use; other destinations never read it.
    private fun validGo2rtcTarget(usesGo2rtc: Boolean): Boolean {
        if (!usesGo2rtc) return true
        try {
            go2rtcClient.parseTarget(current.settings.go2rtcUrl, current.settings.go2rtcUsername, current.settings.go2rtcPassword)
        } catch (exception: IllegalArgumentException) {
            update { copy(go2rtcUrlError = "Enter a valid go2rtc API URL (for example http://192.168.1.10:1984); username and password must be filled together.") }
            return false
        }
        return true
    }

    private fun defaultDestination() =
        StreamDestination.entries.single { it.storedName == current.settings.feedDestination }

    fun dismissDestinationDialog() = update { copy(showDestinationDialog = false) }

    fun confirmDestination() {
        val destination = checkNotNull(current.streamDestination) { "No stream destination selected" }
        if (!validGo2rtcTarget(destination == StreamDestination.GO2RTC)) return
        if (destination == StreamDestination.GO2RTC) {
            val settings = current.settings
            viewModelScope.launch { withContext(Dispatchers.IO) { store.saveSettings(settings) } }
        }
        update {
            copy(showDestinationDialog = false, mainEntryId = null, subEntryId = null, mainError = null, subError = null)
        }
        val generation = importGeneration.get()
        viewModelScope.launch { registerStreams(generation) }
    }

    fun retryStreamRegistration() {
        if (current.streamStatus == StreamStatus.IDLE || current.streamStatus == StreamStatus.COMPLETE) {
            update {
                copy(
                    streamDestination = streamDestination ?: defaultDestination(),
                    showDestinationDialog = true
                )
            }
            return
        }
        val generation = importGeneration.get()
        viewModelScope.launch { registerStreams(generation) }
    }

    private suspend fun registerStreams(generation: Long) {
        val device = checkNotNull(current.activatedDevice) { "No activated device to register" }
        val record = checkNotNull(current.onvifRecord) { "ONVIF has not completed" }
        val destination = checkNotNull(current.streamDestination) { "No stream destination selected" }
        check(current.connectionVerified) { "Home Assistant connection is not verified" }
        check(current.streamStatus != StreamStatus.REGISTERING) { "Stream registration is already running" }
        val settings = current.settings
        update { copy(streamStatus = StreamStatus.REGISTERING, streamError = null) }
        val target = if (destination == StreamDestination.GO2RTC) {
            go2rtcClient.parseTarget(current.settings.go2rtcUrl, current.settings.go2rtcUsername, current.settings.go2rtcPassword)
        } else null

        suspend fun register(title: String, directUrl: String, streamName: String): Pair<String?, String?> {
            try {
                val source = if (target != null) {
                    withContext(Dispatchers.IO) {
                        go2rtcClient.putStream(target, streamName, authenticatedUrl(directUrl, record.password))
                    }
                    "${target.rtspBase}/$streamName"
                } else directUrl
                val entryId = withContext(Dispatchers.IO) {
                    if (target != null) homeAssistantClient.createCamera(title, source, "", "")
                    else homeAssistantClient.createCamera(title, source, ONVIF_USERNAME, record.password)
                }
                return entryId to null
            } catch (exception: HomeAssistantException) {
                return null to sanitizeError(homeAssistantMessage(exception, settings), settings, device, record.password, *accountSecrets())
            } catch (exception: IOException) {
                val detail = "go2rtc is unreachable: ${exception.javaClass.simpleName}: ${exception.message}"
                return null to sanitizeError(detail, settings, device, record.password, *accountSecrets())
            } catch (exception: IllegalStateException) {
                return null to sanitizeError(checkNotNull(exception.message), settings, device, record.password, *accountSecrets())
            }
        }

        if (destination == StreamDestination.TUYA_LOCAL) {
            try {
                val entryId = withContext(Dispatchers.IO) {
                    homeAssistantClient.configureTuyaStreams(
                        device.deviceId,
                        authenticatedUrl(record.mainUrl, record.password),
                        authenticatedUrl(record.subUrl, record.password)
                    )
                }
                if (generation != importGeneration.get()) return
                val registered = record.copy(destination = destination.storedName, registered = true)
                withContext(Dispatchers.IO) { store.saveOnvifRecord(registered) }
                if (generation != importGeneration.get()) return
                update {
                    copy(
                        mainEntryId = entryId,
                        subEntryId = entryId,
                        mainError = null,
                        subError = null,
                        streamStatus = StreamStatus.COMPLETE,
                        onvifRecord = registered
                    )
                }
                return
            } catch (exception: HomeAssistantException) {
                if (exception.error != HomeAssistantError.UNKNOWN_COMMAND) {
                    if (generation != importGeneration.get()) return
                    val error = sanitizeError(homeAssistantMessage(exception, settings), settings, device, record.password, *accountSecrets())
                    update {
                        copy(
                            mainError = error,
                            subError = null,
                            streamStatus = StreamStatus.FAILED,
                            streamError = error
                        )
                    }
                    return
                }
                // Stock tuya-local does not expose tuya_local/configure_streams; register via Generic Camera.
            }
        }

        if (current.mainEntryId == null) {
            val (entryId, error) = register(
                "${device.name} Main", record.mainUrl, go2rtcClient.mainStreamName(device.deviceId)
            )
            if (generation != importGeneration.get()) return
            update { copy(mainEntryId = entryId, mainError = error) }
        }
        if (current.subEntryId == null) {
            val (entryId, error) = register(
                "${device.name} Substream", record.subUrl, go2rtcClient.subStreamName(device.deviceId)
            )
            if (generation != importGeneration.get()) return
            update { copy(subEntryId = entryId, subError = error) }
        }
        if (current.mainEntryId != null && current.subEntryId != null) {
            val registered = record.copy(destination = destination.storedName, registered = true)
            withContext(Dispatchers.IO) { store.saveOnvifRecord(registered) }
            if (generation != importGeneration.get()) return
            update { copy(streamStatus = StreamStatus.COMPLETE, onvifRecord = registered) }
        } else {
            update {
                copy(
                    streamStatus = StreamStatus.FAILED,
                    streamError = listOfNotNull(
                        mainError?.let { "Main stream: $it" },
                        subError?.let { "Substream: $it" }
                    ).joinToString("\n")
                )
            }
        }
    }

    private fun accountSecrets(): Array<String?> = arrayOf(
        store.loadExistingTuyaAccount()?.password,
        store.loadCameraAccount()?.password,
        pendingCameraAccount?.password,
        current.cameraPassword,
        current.settings.go2rtcPassword
    )

    private fun authenticatedUrl(url: String, password: String) =
        url.replaceFirst("rtsp://", "rtsp://$ONVIF_USERNAME:$password@")

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
