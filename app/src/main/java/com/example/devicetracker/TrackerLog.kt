package com.example.devicetracker

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Лог работы: in-memory + сохранение в файл, чтобы переживал пересоздание Activity. */
object TrackerLog {

    private val lines = mutableListOf<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var file: File? = null

    fun init(context: Context) {
        if (file == null) {
            file = File(context.filesDir, "tracker.log")
            try {
                file?.readLines()?.takeLast(100)?.let { lines.addAll(it) }
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun add(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg"
        lines.add(line)
        while (lines.size > 500) lines.removeAt(0)
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {}
        listeners.forEach { it() }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        try {
            file?.delete()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }
}