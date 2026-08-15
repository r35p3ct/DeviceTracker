package com.example.devicetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val store = ConfigStore(context)
        if (store.serverUrl.isBlank()) {
            TrackerLog.add("boot: no server URL, skip autostart")
            return
        }

        TrackerLog.add("boot: autostarting tracker service")
        val i = Intent(context, TrackerService::class.java)
            .setAction(TrackerService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, i)
        } catch (e: Exception) {
            TrackerLog.add("boot: startForegroundService failed: ${e.message}")
        }
    }
}
