package com.emi.tuyeasie.data

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet6Address
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

data class DiscoveredHomeAssistant(val name: String, val url: String)

class HomeAssistantDiscoveryException(message: String) : Exception(message)

// Home Assistant's zeroconf integration advertises _home-assistant._tcp with its configured URLs in the TXT record.
object HomeAssistantDiscovery {
    private const val SERVICE_TYPE = "_home-assistant._tcp"

    suspend fun discover(context: Context, timeoutMs: Long = 4000): List<DiscoveredHomeAssistant> {
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val started = CompletableDeferred<Unit>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                started.complete(Unit)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                started.completeExceptionally(HomeAssistantDiscoveryException("Network discovery could not start (error $errorCode)."))
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                found.trySend(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                throw IllegalStateException("Stopping Home Assistant discovery failed (error $errorCode)")
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        started.await()
        try {
            delay(timeoutMs)
        } finally {
            nsd.stopServiceDiscovery(listener)
            found.close()
        }
        val services = buildList { for (service in found) add(service) }.distinctBy { it.serviceName }
        // Older Android versions reject concurrent resolves, so they run one at a time.
        return services.map { toInstance(resolve(nsd, it)) }.distinctBy { it.url }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo =
        suspendCancellableCoroutine { continuation ->
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) = continuation.resume(info)

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = continuation.resumeWithException(
                    HomeAssistantDiscoveryException("Found ${info.serviceName} but could not resolve its address (error $errorCode).")
                )
            })
        }

    @Suppress("DEPRECATION")
    private fun toInstance(info: NsdServiceInfo): DiscoveredHomeAssistant {
        // TXT keys depend on the Home Assistant version and on which URLs the user configured, so each may be missing or empty.
        fun txt(key: String) = info.attributes[key]?.decodeToString().orEmpty()
        val host = info.host
        val address = if (host is Inet6Address) "[${host.hostAddress}]" else host.hostAddress
        val resolvedUrl = "http://$address:${info.port}"
        // Android does not reliably resolve .local names, while the resolved address is reachable by construction.
        val internal = txt("internal_url").trimEnd('/')
        val url = if (internal.isNotBlank() && Uri.parse(internal).host?.endsWith(".local") == false) internal else resolvedUrl
        return DiscoveredHomeAssistant(name = txt("location_name").ifBlank { info.serviceName }, url = url)
    }
}
