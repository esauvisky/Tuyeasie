package com.emi.tuyeasie.data

import android.content.Context
import com.thingclips.smart.android.user.api.ILoginCallback
import com.thingclips.smart.android.user.bean.User
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.bean.HomeBean
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback
import com.thingclips.smart.sdk.api.IResultCallback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

enum class TuyaAccountKind(val label: String) {
    TEMPORARY("Temporary account"),
    USER("User account")
}

data class SavedTuyaAccount(val kind: TuyaAccountKind, val account: TuyaAccount)

data class TuyaManagedDevice(
    val deviceId: String,
    val name: String,
    val productId: String,
    val homeName: String,
    val activated: ActivatedDevice
)

class TuyaSdkException(val stage: String, val code: String, message: String) :
    RuntimeException("$stage failed ($code): $message")

class TuyaAccountRepository(context: Context, private val store: SecureSettingsStore) {
    private val appContext = context.applicationContext

    fun savedAccounts(): List<SavedTuyaAccount> =
        listOfNotNull(
            store.loadExistingTuyaAccount()?.let { SavedTuyaAccount(TuyaAccountKind.TEMPORARY, it) },
            store.loadCameraAccount()?.let { SavedTuyaAccount(TuyaAccountKind.USER, it) }
        )

    /** Signs in (registering if new) and only then persists the single user account. */
    suspend fun addAccount(email: String, password: String) {
        check(store.loadCameraAccount() == null) {
            "A user account is already saved. Forget it first to switch accounts."
        }
        val account = TuyaAccount(email, password)
        signIn(account)
        store.saveCameraAccount(account)
    }

    fun forgetAccount(saved: SavedTuyaAccount) {
        when (saved.kind) {
            TuyaAccountKind.TEMPORARY -> store.deleteGeneratedTuyaAccount()
            TuyaAccountKind.USER -> store.deleteCameraAccount()
        }
    }

    suspend fun listDevices(account: TuyaAccount): List<TuyaManagedDevice> {
        signIn(account)
        return homes().flatMap { home ->
            homeDetail(home.homeId).deviceList.orEmpty().map {
                val deviceId: String = it.devId
                val name: String = it.name
                val productId: String = it.productId
                TuyaManagedDevice(
                    deviceId = deviceId,
                    name = name,
                    productId = productId,
                    homeName = home.name,
                    activated = ActivatedDevice(
                        deviceId = deviceId,
                        localKey = it.localKey,
                        name = name,
                        productId = productId,
                        protocolVersion = it.pv,
                        // Home-list beans leave `category` empty; `deviceCategory` carries the code (e.g. "sp").
                        category = it.deviceCategory,
                        ip = it.ip
                    )
                )
            }
        }
    }

    suspend fun removeDevice(account: TuyaAccount, deviceId: String) {
        signIn(account)
        suspendCancellableCoroutine { cont ->
            ThingHomeSdk.newDeviceInstance(deviceId).removeDevice(object : IResultCallback {
                override fun onSuccess() = cont.resume(Unit)
                override fun onError(code: String, error: String) =
                    cont.resumeWithException(TuyaSdkException("Remove device", code, error))
            })
        }
    }

    suspend fun signIn(account: TuyaAccount) {
        val countryCode = CountryCodeProvider.current(appContext)
        suspendCancellableCoroutine { cont ->
            ThingHomeSdk.getUserInstance().loginOrRegisterWithUid(
                countryCode,
                account.uid,
                account.password,
                object : ILoginCallback {
                    override fun onSuccess(user: User) = cont.resume(Unit)
                    override fun onError(code: String, error: String) =
                        cont.resumeWithException(TuyaSdkException("Tuya account sign-in", code, error))
                }
            )
        }
    }

    private suspend fun homes(): List<HomeBean> = suspendCancellableCoroutine { cont ->
        ThingHomeSdk.getHomeManagerInstance().queryHomeList(object : IThingGetHomeListCallback {
            override fun onSuccess(homes: List<HomeBean>) = cont.resume(homes)
            override fun onError(code: String, error: String) =
                cont.resumeWithException(TuyaSdkException("Home query", code, error))
        })
    }

    private suspend fun homeDetail(homeId: Long): HomeBean = suspendCancellableCoroutine { cont ->
        ThingHomeSdk.newHomeInstance(homeId).getHomeDetail(object : IThingHomeResultCallback {
            override fun onSuccess(home: HomeBean) = cont.resume(home)
            override fun onError(code: String, error: String) =
                cont.resumeWithException(TuyaSdkException("Home detail", code, error))
        })
    }
}
