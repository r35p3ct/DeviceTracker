package com.example.devicetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var store: ConfigStore
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var statusView: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            TrackerLog.add(
                "permissions: fine=" + (result[Manifest.permission.ACCESS_FINE_LOCATION] ?: false) +
                    ", coarse=" + (result[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false) +
                    ", background=" + (result[Manifest.permission.ACCESS_BACKGROUND_LOCATION] ?: false) +
                    ", notif=" + (result[Manifest.permission.POST_NOTIFICATIONS] ?: false)
            )
            if (hasLocationPermission()) {
                TrackerLog.add("location granted, auto-starting if configured")
                autoStartIfConfigured()
            } else {
                TrackerLog.add("location denied, tracking disabled")
                setStatus("location permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrackerLog.init(this)
        store = ConfigStore(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        scrollView = findViewById(R.id.log_scroll)

        findViewById<TextView>(R.id.device_id).text = store.deviceId
        findViewById<EditText>(R.id.server_url).setText(store.serverUrl)
        findViewById<EditText>(R.id.token).setText(store.provisioningToken)
        findViewById<CheckBox>(R.id.insecure_tls).isChecked = store.insecureTls

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.version).text = "v$versionName"

        TrackerLog.add("app opened, device ${store.deviceId}")
        refreshLog()
        TrackerLog.addListener {
            mainHandler.post {
                refreshLog()
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            saveFields()
            TrackerLog.add("start pressed")
            startIfPermitted()
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            TrackerLog.add("stop pressed")
            val i = Intent(this, TrackerService::class.java)
                .setAction(TrackerService.ACTION_STOP)
            startService(i)
        }

        if (hasLocationPermission()) {
            autoStartIfConfigured()
        } else {
            window.decorView?.post {
                autoRequestPermissions()
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun autoStartIfConfigured() {
        if (store.serverUrl.isBlank()) {
            setStatus("enter server URL and press start")
            return
        }
        if (!hasLocationPermission()) {
            setStatus("grant location permission to start")
            return
        }
        TrackerLog.add("auto-start with server ${store.serverUrl}")
        startTracking()
    }

    private fun autoRequestPermissions() {
        val loc = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (loc.isEmpty()) return
        if (!store.permissionsRequested) {
            store.permissionsRequested = true
            TrackerLog.add("auto-requesting foreground location: $loc")
            permLauncher.launch(loc.toTypedArray())
        }
    }

    private fun startIfPermitted() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startTracking()
        } else {
            TrackerLog.add("requesting permissions from start button: $missing")
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startTracking() {
        if (store.serverUrl.isBlank()) {
            TrackerLog.add("server URL is empty")
            setStatus("set server URL first")
            return
        }
        setStatus("starting service...")

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
                TrackerLog.add("requested battery optimization exemption")
            } catch (_: Exception) {}
        }

        try {
            val i = Intent(this, TrackerService::class.java)
                .setAction(TrackerService.ACTION_START)
            ContextCompat.startForegroundService(this, i)
            TrackerLog.add("startForegroundService ok")
        } catch (e: Exception) {
            TrackerLog.add("start service FAILED: ${e.message}")
            setStatus("failed: ${e.message}")
        }
    }

    private fun setStatus(s: String) {
        mainHandler.post { statusView.text = s }
    }

    private fun refreshLog() {
        val text = TrackerLog.text()
        logView.text = text
        if (text.isNotEmpty()) {
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun saveFields() {
        store.serverUrl = findViewById<EditText>(R.id.server_url).text.toString().trim().trimEnd('/')
        store.provisioningToken = findViewById<EditText>(R.id.token).text.toString().trim()
        store.insecureTls = findViewById<CheckBox>(R.id.insecure_tls).isChecked
    }
}