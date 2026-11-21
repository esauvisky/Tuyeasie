package com.emi.tuyeasie

import android.app.Application
import com.thingclips.smart.android.network.ThingSmartNetWork
import com.thingclips.smart.home.sdk.ThingHomeSdk

class TuyeasieApplication : Application() {
    lateinit var tuyaContext: Application
        private set

    override fun onCreate() {
        super.onCreate()
        tuyaContext = TuyaIdentityApplication(this)
        ThingSmartNetWork.mD = false
        ThingSmartNetWork.mSdk = false
        ThingSmartNetWork.mNTY = true
        ThingSmartNetWork.mSecurity = true
        ThingHomeSdk.init(tuyaContext, BuildConfig.TUYA_APP_KEY, BuildConfig.TUYA_APP_SECRET)
    }
}
