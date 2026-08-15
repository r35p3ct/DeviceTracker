package com.example.devicetracker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.File

class TrackerService : Service() {

    companion object {
        const val ACTION_START = "com.example.devicetracker.START"
        const val ACTION_STOP = "com.example.devicetracker.STOP"
        const val ACTION_TICK = "com.example.devicetracker.TICK"
        private const val CHANNEL_ID = "tracker"
        private const val NOTIF_ID = 1
        private const val ALARM_REQUEST_CODE = 100
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> TrackerLog.add("coroutine error: ${t.message}") }
    )
    private lateinit var store: ConfigStore
    private lateinit var collector: Collector
    private lateinit var buffer: OfflineBuffer
    private var wakeLock: PowerManager.WakeLock? = null

    private var mqtt: MqttClient? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        TrackerLog.init(this)
        store = ConfigStore(this)
        collector = Collector(this)
        buffer = OfflineBuffer(File(filesDir, "buffer.jsonl"))
        createChannel()
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        TrackerLog.add("service onCreate, v$version")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TrackerLog.add("onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_TICK -> {
                scope.launch { tick() }
                return START_STICKY
            }
            else -> {
                startForegroundCompat()
                acquireWakeLock()
                if (!running) {
                    running = true
                    TrackerLog.add("service started (device ${store.deviceId})")
                    try {
                        collector.start()
                    } catch (t: Throwable) {
                        TrackerLog.add("collector.start failed: ${t.message}")
                    }
                    scope.launch { tick() }
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun stopTracking() {
        running = false
        cancelAlarm()
        releaseWakeLock()
        scope.launch {
            try {
                publishStatus("offline")
            } catch (_: Exception) {}
            try {
                mqtt?.disconnect()
            } catch (_: Exception) {}
            mqtt = null
        }
        collector.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun tick() {
        try {
            ensureBootstrap()
            try {
                ensureConnected()
            } catch (_: Exception) {
            }

            collector.scanWifi()

            val payload = collector.telemetryJson().put("device_id", store.deviceId)
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)

            val connected = mqtt?.isConnected == true
            if (connected) {
                try {
                    mqtt?.publish(store.topicTelemetry, bytes, 1, false)
                    flushBuffer()
                    TrackerLog.add("sent ts=${payload.optLong("ts")}")
                } catch (e: Exception) {
                    buffer.append(payload.toString())
                    TrackerLog.add("publish fail, buffered (${buffer.size()})")
                }
            } else {
                buffer.append(payload.toString())
                TrackerLog.add("buffered (${buffer.size()})")
            }
        } catch (e: Exception) {
            TrackerLog.add("tick error: ${e.message}")
        }

        if (running) {
            renewWakeLock()
            scheduleNextAlarm()
        }
    }

    private fun scheduleNextAlarm() {
        val intervalSec = store.intervalSec.coerceIn(5, 3600)
        val intervalMs = intervalSec * 1000L
        val triggerAt = System.currentTimeMillis() + intervalMs

        val intent = Intent(this, AlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (e: SecurityException) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } catch (_: Exception) {}
        }
    }

    private fun cancelAlarm() {
        val intent = Intent(this, AlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(pending)
        pending.cancel()
    }

    private suspend fun ensureBootstrap() {
        if (store.brokerUri.isNotBlank()) return
        if (store.serverUrl.isBlank()) return
        TrackerLog.add("bootstrap POST ${store.serverUrl}/provision ...")
        try {
            val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
            val cfg = withContext(Dispatchers.IO) {
                BootstrapClient.provision(
                    baseUrl = store.serverUrl,
                    token = store.provisioningToken,
                    deviceId = store.deviceId,
                    model = Build.MODEL,
                    version = version,
                    insecure = store.insecureTls
                )
            }
            store.applyBootstrap(cfg)
            TrackerLog.add("bootstrap ok -> ${store.brokerUri}")
        } catch (e: Exception) {
            TrackerLog.add("bootstrap failed: ${e.message}")
        }
    }

    private suspend fun ensureConnected() {
        if (mqtt?.isConnected == true) return
        val uri = store.brokerUri
        if (uri.isBlank()) return

        TrackerLog.add("connecting $uri ...")
        try {
            val client = MqttClient(uri, store.deviceId, MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 90
                maxReconnectDelay = 60
                if (store.mqttUsername.isNotBlank()) userName = store.mqttUsername
                if (store.mqttPassword.isNotBlank()) password = store.mqttPassword.toCharArray()
                if (uri.startsWith("ssl://") && store.insecureTls) {
                    socketFactory = TlsUtil.trustAllSocketFactory()
                }
                setWill(
                    store.topicStatus,
                    "offline".toByteArray(Charsets.UTF_8),
                    1,
                    true
                )
            }
            client.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    TrackerLog.add("connection lost: ${cause?.message}")
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    handleMessage(topic, message?.payload?.toString(Charsets.UTF_8))
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            client.connect(opts)
            client.subscribe(store.topicConfig, 0)
            client.subscribe(store.topicCmd, 0)
            mqtt = client
            publishStatus("online")
            flushBuffer()
            TrackerLog.add("connected, buffer=${buffer.size()}")
        } catch (e: Exception) {
            TrackerLog.add("mqtt connect failed: ${e.message}")
            mqtt = null
            throw e
        }
    }

    private fun handleMessage(topic: String?, payload: String?) {
        if (payload == null) return
        when (topic) {
            store.topicConfig -> {
                val newInterval = Regex("\"interval_sec\"\\s*:\\s*(\\d+)").find(payload)
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (newInterval != null && newInterval in 5..3600) {
                    store.intervalSec = newInterval
                    TrackerLog.add("config: interval_sec=$newInterval")
                }
            }
            store.topicCmd -> {
                if (payload.trim() == "force_update") {
                    scope.launch {
                        collector.scanWifi()
                        val msg = collector.telemetryJson().put("device_id", store.deviceId)
                        try {
                            mqtt?.publish(store.topicTelemetry, msg.toString().toByteArray(), 1, false)
                        } catch (e: Exception) {
                            buffer.append(msg.toString())
                        }
                    }
                }
            }
        }
    }

    private fun flushBuffer() {
        val pending = buffer.drain()
        if (pending.isEmpty()) return
        var sent = 0
        for (line in pending) {
            try {
                mqtt?.publish(store.topicTelemetry, line.toByteArray(Charsets.UTF_8), 1, false)
                sent++
            } catch (e: Exception) {
                buffer.append(line)
                break
            }
        }
        if (sent > 0) TrackerLog.add("flushed $sent buffered points")
    }

    private fun publishStatus(status: String) {
        mqtt?.publish(store.topicStatus, MqttMessage(status.toByteArray(Charsets.UTF_8)).apply {
            qos = 1
            isRetained = true
        })
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:tracker"
        ).apply {
            acquire(10 * 60 * 1000L)
        }
        TrackerLog.add("wake lock acquired")
    }

    private fun renewWakeLock() {
        releaseWakeLock()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:tracker"
        ).apply {
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            TrackerLog.add("startForeground ok")
        } catch (e: Exception) {
            TrackerLog.add("startForeground FAILED: ${e.message}")
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Location tracking", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Device tracking service"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Tracking location")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
