package com.example.devicetracker

import java.io.File

/**
 * Дисковый offline-буфер: строки телеметрии пишутся в файл при ошибке отправки
 * и отдаются пачкой после восстановления соединения (FIFO, без дубликатов).
 */
class OfflineBuffer(private val file: File) {

    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    fun size(): Int = try {
        file.readLines().count()
    } catch (_: Exception) {
        0
    }

    @Synchronized
    fun append(line: String) {
        try {
            file.appendText(line + "\n", Charsets.UTF_8)
        } catch (_: Exception) {
            // Если диск переполнен — теряем старые записи, но держим файл живым.
            try {
                val lines = file.readLines().drop(1)
                file.writeText((lines + line).joinToString("\n") + "\n", Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    /** Возвращает все накопленные строки и очищает буфер. */
    @Synchronized
    fun drain(): List<String> {
        val lines = try {
            file.readLines().filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
        try {
            file.delete()
        } catch (_: Exception) {}
        return lines
    }
}
