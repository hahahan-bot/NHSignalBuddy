package com.nhsignalbuddy.data

import android.content.Context

class WatchlistStore(context: Context) {
    private val prefs = context.getSharedPreferences("watchlist_prefs", Context.MODE_PRIVATE)
    private val key = "watchlist_csv"
    private val defaultList = listOf("MRK", "WMT", "CAT", "MU", "STX")

    fun load(): List<String> {
        val raw = prefs.getString(key, null)?.trim().orEmpty()
        if (raw.isBlank()) return defaultList
        val parsed = raw.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
        return if (parsed.isEmpty()) defaultList else parsed
    }

    fun save(symbols: List<String>) {
        val cleaned = symbols
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
        prefs.edit().putString(key, cleaned.joinToString(",")).apply()
    }
}
