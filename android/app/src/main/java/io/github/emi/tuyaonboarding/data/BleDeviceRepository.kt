package io.github.emi.tuyaonboarding.data

import android.os.Handler
import android.os.Looper
import com.thingclips.smart.android.ble.api.LeScanSetting
import com.thingclips.smart.android.ble.api.ScanDeviceBean
import com.thingclips.smart.android.ble.api.ScanType
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.bean.ConfigProductInfoBean
import com.thingclips.smart.sdk.api.IThingDataCallback

data class TuyaBleDevice(
    val uuid: String,
    val productId: String,
    val rssi: Int,
    val scan: ScanDeviceBean,
    val productName: String? = null,
    val iconUrl: String? = null
)

class BleDeviceRepository {
    interface Listener {
        fun onDeviceFound(device: TuyaBleDevice)
        fun onProductInfo(uuid: String, name: String, iconUrl: String)
        fun onProductInfoFailed(uuid: String, code: String, message: String)
        fun onScanFinished()
    }

    private val operator = ThingHomeSdk.getBleOperator()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var scanTimeout: Runnable? = null
    private val lookedUp = mutableSetOf<String>()
    private var closed = false

    var listener: Listener? = null

    fun isBluetoothOn(): Boolean = operator.isBluetoothOpened

    fun scan() {
        stop()
        lookedUp.clear()
        val scanGeneration = generation
        val setting = LeScanSetting.Builder()
            .setTimeout(SCAN_TIMEOUT_MILLIS)
            .addScanType(ScanType.SINGLE)
            .build()
        operator.startLeScan(setting) { bean ->
            mainHandler.post {
                // Only Wi-Fi + BLE combo devices end up on the LAN where tuya-local can reach them.
                if (!closed && scanGeneration == generation && bean.configType == CONFIG_TYPE_WIFI_COMBO) {
                    listener?.onDeviceFound(TuyaBleDevice(bean.uuid, bean.productId, bean.rssi, bean))
                    if (lookedUp.add(bean.uuid)) lookUpProduct(scanGeneration, bean)
                }
            }
        }
        val timeout = Runnable {
            if (!closed && scanGeneration == generation) {
                stop()
                listener?.onScanFinished()
            }
        }
        scanTimeout = timeout
        mainHandler.postDelayed(timeout, SCAN_TIMEOUT_MILLIS)
    }

    private fun lookUpProduct(scanGeneration: Int, bean: ScanDeviceBean) {
        ThingHomeSdk.getActivatorInstance().getActivatorDeviceInfo(
            bean.productId,
            bean.uuid,
            bean.mac,
            object : IThingDataCallback<ConfigProductInfoBean> {
                override fun onSuccess(info: ConfigProductInfoBean) {
                    mainHandler.post {
                        if (!closed && scanGeneration == generation) {
                            listener?.onProductInfo(bean.uuid, info.name, info.icon)
                        }
                    }
                }

                override fun onError(code: String, message: String) {
                    mainHandler.post {
                        if (!closed && scanGeneration == generation) {
                            listener?.onProductInfoFailed(bean.uuid, code, message)
                        }
                    }
                }
            }
        )
    }

    fun stop() {
        generation++
        scanTimeout?.let(mainHandler::removeCallbacks)
        scanTimeout = null
        operator.stopLeScan()
    }

    fun close() {
        closed = true
        stop()
        listener = null
    }

    private companion object {
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val CONFIG_TYPE_WIFI_COMBO = "config_type_wifi"
    }
}
