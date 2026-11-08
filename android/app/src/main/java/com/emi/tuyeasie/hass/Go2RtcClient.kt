package com.emi.tuyeasie.hass

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// username and password are both set or both null: go2rtc deployments may be unauthenticated.
data class Go2RtcTarget(
    val apiUrl: String,
    val rtspBase: String,
    val username: String? = null,
    val password: String? = null
) {
    override fun toString() = "Go2RtcTarget(apiUrl=$apiUrl, rtspBase=$rtspBase, username=$username, password=[redacted])"
}

class Go2RtcClient(
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    fun parseTarget(apiUrl: String, username: String = "", password: String = ""): Go2RtcTarget {
        val url = apiUrl.trim().toHttpUrl()
        require(url.scheme == "http" || url.scheme == "https") {
            "go2rtc API URL must use http or https: ${url.scheme}"
        }
        require(url.host.isNotBlank()) { "go2rtc API URL has a blank hostname" }
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        require(username.isEmpty() == password.isEmpty()) { "go2rtc username and password must be provided together" }
        return Go2RtcTarget(
            url.toString(),
            "rtsp://$host:8554",
            username.ifEmpty { null },
            password.ifEmpty { null }
        )
    }

    fun mainStreamName(deviceId: String) = "tuyeasie_${deviceId}_main"

    fun subStreamName(deviceId: String) = "tuyeasie_${deviceId}_sub"

    fun putStream(target: Go2RtcTarget, name: String, sourceUrl: String) {
        val url = target.apiUrl.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("streams")
            .addQueryParameter("name", name)
            .addQueryParameter("src", "$sourceUrl#transport=tcp")
            .build()
        val request = Request.Builder()
            .url(url)
            .put(ByteArray(0).toRequestBody())
            .apply {
                if (target.username != null) header("Authorization", Credentials.basic(target.username, checkNotNull(target.password)))
            }
            .build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "go2rtc PUT /api/streams for $name failed: HTTP ${response.code}"
            }
        }
    }
}
