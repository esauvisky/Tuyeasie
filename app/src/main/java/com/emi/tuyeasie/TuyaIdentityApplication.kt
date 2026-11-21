package com.emi.tuyeasie

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.pm.PackageManager

class TuyaIdentityApplication(private val real: Application) : Application() {
    private val tuyaPackageManager: PackageManager

    init {
        attachBaseContext(real)
        tuyaPackageManager = TuyaPackageManager(real.packageManager, real.resources, real.packageName)
    }

    override fun getPackageName(): String = TuyaPackageManager.OFFICIAL_PACKAGE_NAME

    override fun getPackageManager(): PackageManager = tuyaPackageManager

    override fun getApplicationContext(): Context = this

    override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) =
        real.registerActivityLifecycleCallbacks(callback)

    override fun unregisterActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) =
        real.unregisterActivityLifecycleCallbacks(callback)

    override fun registerComponentCallbacks(callback: ComponentCallbacks) =
        real.registerComponentCallbacks(callback)

    override fun unregisterComponentCallbacks(callback: ComponentCallbacks) =
        real.unregisterComponentCallbacks(callback)
}
