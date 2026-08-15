package com.example.devicetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, TrackerService::class.java)
            .setAction(TrackerService.ACTION_TICK)
        ContextCompat.startForegroundService(context, svc)
    }
}
