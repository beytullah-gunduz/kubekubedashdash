package com.kubedash.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppLogEntry(
    val timestamp: Long,
    val level: String,
    val loggerName: String,
    val message: String,
    val formattedMessage: String,
    val threadName: String,
    val throwable: String? = null,
) {
    val shortLoggerName: String
        get() = loggerName.substringAfterLast('.')
}

object AppLogStore {
    var maxEntries: Int = 500

    private val _entries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogEntry>> = _entries.asStateFlow()

    fun addEntry(entry: AppLogEntry) {
        val updated = (_entries.value + entry).takeLast(maxEntries)
        _entries.value = updated
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
