package com.emi.tuyeasie.data

import android.content.Context
import com.thingclips.smart.android.user.api.ILoginCallback
import com.thingclips.smart.android.user.api.ILogoutCallback
import com.thingclips.smart.android.user.bean.User
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.bean.HomeBean
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback
import com.thingclips.smart.sdk.api.IResultCallback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val iconUrl: String?,
    val capabilities: DeviceCapabilities,
    val activated: ActivatedDevice
)

class TuyaSdkException(val stage: String, val code: String, message: String) :
    RuntimeException("$stage failed ($code): $message")

// Device beans from activation and home detail carry no schemaMap; the SDK's data cache holds the schema.
fun deviceCapabilities(bean: com.thingclips.smart.sdk.bean.DeviceBean): DeviceCapabilities {
    val schema = checkNotNull(ThingHomeSdk.getDataInstance().getSchema(bean.devId)) {
        "Tuya SDK has no schema for ${bean.devId}"
    }
    return DeviceCapabilities(
        dataPoints = schema.values.map {
            TuyaDataPointSchema(
                id = it.id,
                code = it.code,
                name = it.name,
                mode = it.mode,
                type = it.type,
                propertyJson = it.property,
                schemaType = it.schemaType
            )
        },
        mac = bean.mac,
        tuyaLan = bean.isHasLanCommunication()
    )
}

private val regionLock = Mutex()

class TuyaAccountRepository(context: Context, private val store: SecureSettingsStore) {
    private val appContext = context.applicationContext

    fun savedAccounts(): List<SavedTuyaAccount> =
        listOfNotNull(
            store.loadExistingTuyaAccount()?.let { SavedTuyaAccount(TuyaAccountKind.TEMPORARY, it) },
            store.loadCameraAccount()?.let { SavedTuyaAccount(TuyaAccountKind.USER, it) }
        )

    /** Signs in to an existing account, finding its data center, and only then persists it as the user account. */
    suspend fun addAccount(email: String, password: String) {
        check(store.loadCameraAccount() == null) {
            "A user account is already saved. Forget it first to switch accounts."
        }
        store.saveCameraAccount(signInExisting(email, password))
    }

    /**
     * Finds an existing account's data center with a login that cannot register a duplicate. Tuya answers even
     * when the request's country code points elsewhere; the returned user carries the account's real region.
     */
    suspend fun signInExisting(uid: String, password: String): TuyaAccount {
        logOutIfSignedIn()
        val user = login(CountryCodeProvider.current(appContext), uid, password, register = false)
        val account = TuyaAccount(uid, password, checkNotNull(user.phoneCode) { "Signed-in Tuya user has no country code" })
        recordDataCenter(account)
        return account
    }

    /** Replaces the locale-guessed country code of accounts saved before codes were stored per account. */
    suspend fun confirmGuessedRegions() = regionLock.withLock {
        for (saved in savedAccounts()) {
            if (!store.isRegionGuessed(saved.kind)) continue
            val confirmed = signInExisting(saved.account.uid, saved.account.password)
            when (saved.kind) {
                TuyaAccountKind.TEMPORARY -> store.saveGeneratedTuyaAccount(confirmed)
                TuyaAccountKind.USER -> store.saveCameraAccount(confirmed)
            }
        }
    }

    fun hasGuessedRegions(): Boolean = savedAccounts().any { store.isRegionGuessed(it.kind) }

    // Called right after `account` signed in, so the SDK's current user is that account. The login response
    // carries a domain only sometimes (the SDK itself copies it only when present), so its absence records nothing.
    fun recordDataCenter(account: TuyaAccount) {
        val user = checkNotNull(ThingHomeSdk.getUserInstance().user) { "No signed-in Tuya user after signing in" }
        val domain = user.domain ?: return
        store.saveDataCenter(account.uid, domain.regionCode)
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
                    iconUrl = it.iconUrl,
                    capabilities = deviceCapabilities(it),
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

    suspend fun renameDevice(account: TuyaAccount, deviceId: String, name: String) {
        signIn(account)
        suspendCancellableCoroutine { cont ->
            ThingHomeSdk.newDeviceInstance(deviceId).renameDevice(name, object : IResultCallback {
                override fun onSuccess() = cont.resume(Unit)
                override fun onError(code: String, error: String) =
                    cont.resumeWithException(TuyaSdkException("Rename device", code, error))
            })
        }
    }

    // Every caller signs in to a saved account. It is read back from storage because confirming a guessed
    // region rewrites its country code, and a caller's older copy would sign in to the wrong data center.
    suspend fun signIn(requested: TuyaAccount) {
        confirmGuessedRegions()
        val account = savedAccounts().single { it.account.uid == requested.uid }.account
        val user = ThingHomeSdk.getUserInstance()
        // The SDK keeps the previous account's data-center domain until logout, so a session in another
        // region must end before signing in; same-region switches log in over it as before.
        if (user.isLogin && checkNotNull(user.user) { "Signed-in Tuya session has no user" }.phoneCode != account.countryCode) {
            logOutIfSignedIn()
        }
        login(account.countryCode, account.uid, account.password, register = true)
        recordDataCenter(account)
    }

    private suspend fun logOutIfSignedIn() {
        val user = ThingHomeSdk.getUserInstance()
        if (!user.isLogin) return
        suspendCancellableCoroutine { cont ->
            user.logout(object : ILogoutCallback {
                override fun onSuccess() = cont.resume(Unit)
                override fun onError(code: String, error: String) =
                    cont.resumeWithException(TuyaSdkException("Tuya sign-out from the previous region", code, error))
            })
        }
    }

    private suspend fun login(countryCode: String, uid: String, password: String, register: Boolean): User {
        return suspendCancellableCoroutine { cont ->
            val callback = object : ILoginCallback {
                override fun onSuccess(user: User) = cont.resume(user)
                override fun onError(code: String, error: String) =
                    cont.resumeWithException(TuyaSdkException("Tuya account sign-in", code, error))
            }
            val user = ThingHomeSdk.getUserInstance()
            if (register) user.loginOrRegisterWithUid(countryCode, uid, password, callback)
            else user.loginWithUid(countryCode, uid, password, callback)
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
