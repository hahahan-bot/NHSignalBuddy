package com.nhsignalbuddy.alert

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nhsignalbuddy.R
import com.nhsignalbuddy.model.SignalRow
import org.json.JSONObject

class SignalAlertManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("signal_alert_prefs", Context.MODE_PRIVATE)
    private val channelId = "signal_changes"

    fun notifyTransitions(rows: List<SignalRow>) {
        ensureChannel()
        val before = loadActionMap()
        val after = mutableMapOf<String, String>()

        for (row in rows) {
            val symbol = row.symbol
            val prev = before[symbol]
            val now = row.action
            after[symbol] = now

            if (prev == null || prev == now) continue
            if (now != "BUY_CANDIDATE" && now != "SELL") continue
            if (!canPostNotifications()) continue

            val title = if (now == "SELL") "SELL Signal: $symbol" else "BUY Candidate: $symbol"
            val body = "action $prev -> $now | reason=${row.reason}"
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(symbol.hashCode(), notification)
        }

        saveActionMap(after)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "Signal Changes",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "BUY/SELL signal transition alerts"
        }
        manager.createNotificationChannel(channel)
    }

    private fun loadActionMap(): Map<String, String> {
        val raw = prefs.getString("last_actions_json", "{}") ?: "{}"
        val obj = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val out = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optString(k, "")
        }
        return out
    }

    private fun saveActionMap(map: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs.edit().putString("last_actions_json", obj.toString()).apply()
    }
}
