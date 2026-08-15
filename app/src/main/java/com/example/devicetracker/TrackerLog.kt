package com.example.devicetracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Простой in-memory лог для экрана статуса. */
object TrackerLog {

    private val lines = mutableListOf<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.add("[${timeFmt.format(Date())}] $msg")
        while (lines.size > 300) lines.removeAt(0)
        listeners.forEach { it() }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }
}
