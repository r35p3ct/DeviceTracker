package com.example.devicetracker

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection

data class BootstrapConfig(
    val brokerUri: String,
    val username: String,
    val password: String,
    val intervalSec: Int,
    val topicTelemetry: String,
    val topicStatus: String,
    val topicConfig: String,
    val topicCmd: String
)

object BootstrapClient {

    /**
     * POST {baseUrl}/provision
     * Body: {"device_id": "...", "model": "...", "version": "...", "token": "..."}
     * Response 200:
     *   {
     *     "broker": "ssl://host:8883",
     *     "username": "...", "password": "...",
     *     "interval_sec": 15,
     *     "topic_telemetry": "devices/{id}/telemetry", ...
     *   }
     * All fields except "broker" are optional.
     */
    fun provision(
        baseUrl: String,
        token: String,
        deviceId: String,
        model: String,
        version: String,
        insecure: Boolean
    ): BootstrapConfig {
        val url = URL(baseUrl.trimEnd('/') + "/provision")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")

        if (conn is HttpsURLConnection && insecure) {
            conn.sslSocketFactory = TlsUtil.trustAllSocketFactory()
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }

        val body = JSONObject()
            .put("device_id", deviceId)
            .put("model", model)
            .put("version", version)
        if (token.isNotBlank()) body.put("token", token)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("HTTP $code: $text")

        val j = JSONObject(text)
        val broker = j.optString("broker").trim()
        if (broker.isBlank()) throw IOException("Ответ /provision не содержит 'broker'")

        return BootstrapConfig(
            brokerUri = broker,
            username = j.optString("username", ""),
            password = j.optString("password", ""),
            intervalSec = j.optInt("interval_sec", 15),
            topicTelemetry = j.optString("topic_telemetry", ""),
            topicStatus = j.optString("topic_status", ""),
            topicConfig = j.optString("topic_config", ""),
            topicCmd = j.optString("topic_cmd", "")
        )
    }
}
