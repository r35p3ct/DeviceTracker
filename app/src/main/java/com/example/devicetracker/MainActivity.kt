package com.example.devicetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                it[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                startTracking()
            } else {
                TrackerLog.add("location permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<TextView>(R.id.device_id).text = store.deviceId
        findViewById<EditText>(R.id.server_url).setText(store.serverUrl)
        findViewById<EditText>(R.id.token).setText(store.provisioningToken)
        findViewById<CheckBox>(R.id.insecure_tls).isChecked = store.insecureTls

        logView = findViewById(R.id.log)
        logView.text = TrackerLog.text()
        TrackerLog.addListener { runOnUiThread { logView.text = TrackerLog.text() } }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            saveFields()
            requestPermissionsAndStart()
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            val i = Intent(this, TrackerService::class.java)
                .setAction(TrackerService.ACTION_STOP)
            startService(i)
            TrackerLog.add("stop requested")
        }
    }

    private fun saveFields() {
        store.serverUrl = findViewById<EditText>(R.id.server_url).text.toString().trim().trimEnd('/')
        store.provisioningToken = findViewById<EditText>(R.id.token).text.toString().trim()
        store.insecureTls = findViewById<CheckBox>(R.id.insecure_tls).isChecked
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
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
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startTracking() {
        if (store.serverUrl.isBlank()) {
            TrackerLog.add("set server URL first")
            return
        }
        val i = Intent(this, TrackerService::class.java)
            .setAction(TrackerService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
    }
}
