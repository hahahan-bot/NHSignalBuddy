package com.nhsignalbuddy.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/** PC `generate_signals.py` 출력 `signals_latest.json` — SAF로 단일 파일만 연결. */
class SignalUriPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedUriString(): String? =
        prefs.getString(KEY_FILE_URI, null)?.takeIf { it.isNotBlank() }

    fun getSavedUri(): Uri? = getSavedUriString()?.let { Uri.parse(it) }

    fun hasUri(): Boolean = getSavedUriString() != null

    fun saveUri(uri: Uri) {
        prefs.edit().putString(KEY_FILE_URI, uri.toString()).apply()
    }

    fun clearUri() {
        prefs.edit().remove(KEY_FILE_URI).apply()
    }

    companion object {
        const val KEY_FILE_URI = "pref_signals_json_uri"
        private const val PREFS_NAME = "nh_signal_buddy_signal"
    }
}
