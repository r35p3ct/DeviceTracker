package com.example.devicetracker

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tracker", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) {
            val old = serverUrl
            prefs.edit().putString("server_url", value).apply()
            if (old != value) clearBootstrap()
        }

    var provisioningToken: String
        get() = prefs.getString("provisioning_token", "") ?: ""
        set(value) = prefs.edit().putString("provisioning_token", value).apply()

    var insecureTls: Boolean
        get() = prefs.getBoolean("insecure_tls", false)
        set(value) = prefs.edit().putBoolean("insecure_tls", value).apply()

    var permissionsRequested: Boolean
        get() = prefs.getBoolean("permissions_requested", false)
        set(value) = prefs.edit().putBoolean("permissions_requested", value).apply()

    var autostartPrompted: Boolean
        get() = prefs.getBoolean("autostart_prompted", false)
        set(value) = prefs.edit().putBoolean("autostart_prompted", value).apply()

    var trackingRunning: Boolean
        get() = prefs.getBoolean("tracking_running", false)
        set(value) = prefs.edit().putBoolean("tracking_running", value).apply()

    var lastSentTs: Long
        get() = prefs.getLong("last_sent_ts", 0)
        set(value) = prefs.edit().putLong("last_sent_ts", value).apply()

    var brokerUri: String
        get() = prefs.getString("broker_uri", "") ?: ""
        set(value) = prefs.edit().putString("broker_uri", value).apply()

    var mqttUsername: String
        get() = prefs.getString("mqtt_user", "") ?: ""
        set(value) = prefs.edit().putString("mqtt_user", value).apply()

    var mqttPassword: String
        get() = prefs.getString("mqtt_pass", "") ?: ""
        set(value) = prefs.edit().putString("mqtt_pass", value).apply()

    var intervalSec: Int
        get() = prefs.getInt("interval_sec", 25)
        set(value) = prefs.edit().putInt("interval_sec", value).apply()

    var topicTelemetry: String
        get() = prefs.getString("topic_telemetry", "devices/$deviceId/telemetry") ?: "devices/$deviceId/telemetry"
        set(value) = prefs.edit().putString("topic_telemetry", value).apply()

    var topicStatus: String
        get() = prefs.getString("topic_status", "devices/$deviceId/status") ?: "devices/$deviceId/status"
        set(value) = prefs.edit().putString("topic_status", value).apply()

    var topicConfig: String
        get() = prefs.getString("topic_config", "devices/$deviceId/config") ?: "devices/$deviceId/config"
        set(value) = prefs.edit().putString("topic_config", value).apply()

    var topicCmd: String
        get() = prefs.getString("topic_cmd", "devices/$deviceId/cmd") ?: "devices/$deviceId/cmd"
        set(value) = prefs.edit().putString("topic_cmd", value).apply()

    fun applyBootstrap(cfg: BootstrapConfig) {
        brokerUri = cfg.brokerUri
        if (cfg.username.isNotBlank()) mqttUsername = cfg.username
        if (cfg.password.isNotBlank()) mqttPassword = cfg.password
        if (cfg.intervalSec > 0) intervalSec = cfg.intervalSec
        if (cfg.topicTelemetry.isNotBlank()) topicTelemetry = cfg.topicTelemetry
        if (cfg.topicStatus.isNotBlank()) topicStatus = cfg.topicStatus
        if (cfg.topicConfig.isNotBlank()) topicConfig = cfg.topicConfig
        if (cfg.topicCmd.isNotBlank()) topicCmd = cfg.topicCmd
    }

    fun clearBootstrap() {
        prefs.edit()
            .remove("broker_uri")
            .remove("mqtt_user")
            .remove("mqtt_pass")
            .remove("interval_sec")
            .remove("topic_telemetry")
            .remove("topic_status")
            .remove("topic_config")
            .remove("topic_cmd")
            .apply()
    }
}
