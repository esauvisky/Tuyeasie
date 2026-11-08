package com.emi.tuyeasie.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.thingclips.smart.android.user.api.ILoginCallback
import com.thingclips.smart.android.user.api.IRegisterCallback
import com.thingclips.smart.android.user.bean.User
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.bean.HomeBean
import com.thingclips.smart.home.sdk.builder.ActivatorBuilder
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback
import com.thingclips.smart.sdk.api.IMultiModeActivator
import com.thingclips.smart.sdk.api.IMultiModeActivatorListener
import com.thingclips.smart.sdk.api.IThingActivator
import com.thingclips.smart.sdk.api.IThingActivatorGetToken
import com.thingclips.smart.sdk.api.IThingSmartActivatorListener
import com.thingclips.smart.sdk.bean.DeviceBean
import com.thingclips.smart.sdk.bean.MultiModeActivatorBean
import com.thingclips.smart.sdk.enums.ActivatorModelEnum

enum class AccountAuth { LOGIN, REGISTER, LOGIN_OR_REGISTER }

class TuyaActivationRepository(context: Context) {
    interface Listener {
        fun onProgress(message: String)
        fun onReady()
        fun onActivationStep(step: String)
        fun onActivated(device: DeviceBean)
        fun onFailure(stage: String, code: String, message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var operationGeneration = 0
    private var activationToken: String? = null
    private var homeId: Long? = null
    private var activator: IThingActivator? = null
    private var bleActivator: IMultiModeActivator? = null
    private var bleActivatorUuid: String? = null
    private var closed = false

    var listener: Listener? = null

    fun prepare(countryCode: String, account: TuyaAccount, auth: AccountAuth) {
        stopActivator()
        activationToken = null
        homeId = null
        val generation = ++operationGeneration
        progress(generation, "Signing in to the Tuya account…")
        val loginCallback = object : ILoginCallback {
            override fun onSuccess(user: User) = onCurrent(generation) {
                queryHomes(generation)
            }

            override fun onError(code: String, error: String) = onCurrent(generation) {
                failure("Tuya account sign-in", code, error)
            }
        }
        val user = ThingHomeSdk.getUserInstance()
        when (auth) {
            AccountAuth.LOGIN -> user.loginWithUid(countryCode, account.uid, account.password, loginCallback)
            // The official app upgrades its guest to a full account before Bluetooth pairing; match that.
            AccountAuth.LOGIN_OR_REGISTER ->
                user.loginOrRegisterWithUid(countryCode, account.uid, account.password, loginCallback)
            AccountAuth.REGISTER -> user.registerAccountWithUid(
                countryCode,
                account.uid,
                account.password,
                object : IRegisterCallback {
                    override fun onSuccess(user: User) = onCurrent(generation) {
                        queryHomes(generation)
                    }

                    override fun onError(code: String, error: String) = onCurrent(generation) {
                        failure("Tuya account registration", code, error)
                    }
                }
            )
        }
    }

    fun startActivation(settings: AppSettings) {
        val token = checkNotNull(activationToken) { "Activation token is missing" }
        stopActivator()
        val generation = operationGeneration
        progress(generation, "Starting secure AP activation…")
        val builder = ActivatorBuilder()
            .setContext(appContext)
            .setSsid(settings.homeWifiSsid.trim())
            .setPassword(settings.homeWifiPassword)
            .setToken(token)
            .setActivatorModel(ActivatorModelEnum.THING_AP)
            .setTimeOut(ACTIVATION_TIMEOUT_SECONDS)
            .setListener(object : IThingSmartActivatorListener {
                override fun onStep(step: String, data: Any?) = onCurrent(generation) {
                    listener?.onActivationStep(step)
                }

                override fun onActiveSuccess(device: DeviceBean) = onCurrent(generation) {
                    operationGeneration++
                    stopActivator()
                    activationToken = null
                    listener?.onActivated(device)
                }

                override fun onError(code: String, error: String) = onCurrent(generation) {
                    failure("Tuya AP activation", code, error)
                }
            })
        val createdActivator = checkNotNull(
            ThingHomeSdk.getActivatorInstance().newActivator(builder)
        ) { "Tuya activator creation failed" }
        activator = createdActivator
        createdActivator.start()
    }

    fun startBleActivation(settings: AppSettings, device: TuyaBleDevice) {
        val token = checkNotNull(activationToken) { "Activation token is missing" }
        val activationHomeId = checkNotNull(homeId) { "Activation home is missing" }
        stopActivator()
        val generation = operationGeneration
        progress(generation, "Pairing over Bluetooth and sending Wi-Fi credentials…")
        val bean = MultiModeActivatorBean(device.scan).apply {
            homeId = activationHomeId
            uuid = device.scan.uuid
            address = device.scan.address
            mac = device.scan.mac
            deviceType = device.scan.deviceType
            productId = device.scan.productId
            ssid = settings.homeWifiSsid.trim()
            pwd = settings.homeWifiPassword
            this.token = token
            timeout = ACTIVATION_TIMEOUT_SECONDS * 1000
        }
        val createdActivator = ThingHomeSdk.getActivator().newMultiModeActivator()
        bleActivator = createdActivator
        bleActivatorUuid = device.uuid
        createdActivator.startActivator(bean, object : IMultiModeActivatorListener {
            override fun onSuccess(device: DeviceBean) = onCurrent(generation) {
                operationGeneration++
                stopActivator()
                activationToken = null
                listener?.onActivated(device)
            }

            override fun onFailure(code: Int, error: String, handle: Any?) = onCurrent(generation) {
                failure("Tuya Bluetooth activation", code.toString(), error)
            }
        })
    }

    fun stopActivation() {
        operationGeneration++
        stopActivator()
    }

    fun reset() {
        stopActivation()
        activationToken = null
    }

    fun close() {
        closed = true
        reset()
        homeId = null
        listener = null
    }

    private fun queryHomes(generation: Int) {
        progress(generation, "Signed in. Finding a Tuya home…")
        ThingHomeSdk.getHomeManagerInstance().queryHomeList(object : IThingGetHomeListCallback {
            override fun onSuccess(homes: List<HomeBean>) = onCurrent(generation) {
                if (homes.isEmpty()) {
                    createHome(generation)
                } else {
                    requestActivationToken(generation, homes.first().homeId)
                }
            }

            override fun onError(code: String, error: String) = onCurrent(generation) {
                failure("Home query", code, error)
            }
        })
    }

    private fun createHome(generation: Int) {
        progress(generation, "Creating a Tuya home…")
        ThingHomeSdk.getHomeManagerInstance().createHome(
            "Tuya Onboarding",
            0.0,
            0.0,
            "Home",
            listOf("Default"),
            object : IThingHomeResultCallback {
                override fun onSuccess(home: HomeBean) = onCurrent(generation) {
                    requestActivationToken(generation, home.homeId)
                }

                override fun onError(code: String, error: String) = onCurrent(generation) {
                    failure("Home creation", code, error)
                }
            }
        )
    }

    private fun requestActivationToken(generation: Int, homeId: Long) {
        progress(generation, "Requesting a single-use activation token…")
        ThingHomeSdk.getActivatorInstance().getActivatorToken(
            homeId,
            object : IThingActivatorGetToken {
                override fun onSuccess(token: String) = onCurrent(generation) {
                    activationToken = token
                    this@TuyaActivationRepository.homeId = homeId
                    listener?.onReady()
                }

                override fun onFailure(code: String, error: String) = onCurrent(generation) {
                    failure("Activation token request", code, error)
                }
            }
        )
    }

    private fun failure(stage: String, code: String, error: String) {
        var sanitized = error
        val token = activationToken
        if (!token.isNullOrEmpty()) sanitized = sanitized.replace(token, "[redacted]")
        listener?.onFailure(stage, code, sanitized.take(MAX_ERROR_LENGTH))
    }

    private fun progress(generation: Int, message: String) = onCurrent(generation) {
        listener?.onProgress(message)
    }

    private fun onCurrent(generation: Int, action: () -> Unit) {
        mainHandler.post {
            if (!closed && generation == operationGeneration) action()
        }
    }

    private fun stopActivator() {
        activator?.stop()
        activator?.onDestroy()
        activator = null
        bleActivatorUuid?.let { uuid -> checkNotNull(bleActivator).stopActivator(uuid) }
        bleActivator = null
        bleActivatorUuid = null
    }

    private companion object {
        const val ACTIVATION_TIMEOUT_SECONDS = 120L
        const val MAX_ERROR_LENGTH = 1000
    }
}
