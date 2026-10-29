package io.github.emi.tuyaonboarding

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import com.thingclips.smart.android.network.ThingSmartNetWork
import com.thingclips.smart.home.sdk.ThingHomeSdk

class TuyaOnboardingApplication : Application() {
    private lateinit var tuyaPackageManager: PackageManager

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        tuyaPackageManager = TuyaPackageManager(base.packageManager, base.resources, base.packageName)
    }

    override fun getPackageName(): String = TuyaPackageManager.OFFICIAL_PACKAGE_NAME

    override fun getPackageManager(): PackageManager = tuyaPackageManager

    override fun onCreate() {
        super.onCreate()
        ThingSmartNetWork.mD = false
        ThingSmartNetWork.mSdk = false
        ThingSmartNetWork.mNTY = true
        ThingSmartNetWork.mSecurity = true
        ThingHomeSdk.init(this, BuildConfig.TUYA_APP_KEY, BuildConfig.TUYA_APP_SECRET)
    }
}
