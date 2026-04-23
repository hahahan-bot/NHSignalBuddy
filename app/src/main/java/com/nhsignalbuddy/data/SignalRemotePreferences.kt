package com.nhsignalbuddy.data

import android.content.Context
import android.content.SharedPreferences

/** GitHub Pages 등 원격 `signals_latest.json` URL 및 마지막 fetch 시각. */
class SignalRemotePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRemoteUrl(): String =
        prefs.getString(KEY_SIGNAL_REMOTE_URL, null)?.trim().orEmpty()

    fun setRemoteUrl(url: String) {
        prefs.edit().putString(KEY_SIGNAL_REMOTE_URL, url).apply()
    }

    fun getLastFetchSuccessMillis(): Long =
        prefs.getLong(KEY_LAST_FETCH_OK_MILLIS, 0L)

    fun setLastFetchSuccessMillis(millis: Long) {
        prefs.edit().putLong(KEY_LAST_FETCH_OK_MILLIS, millis).apply()
    }

    companion object {
        const val KEY_SIGNAL_REMOTE_URL = "signal_remote_url"
        private const val KEY_LAST_FETCH_OK_MILLIS = "signal_remote_last_ok_millis"
        private const val PREFS_NAME = "nh_signal_buddy_settings"
    }
}
