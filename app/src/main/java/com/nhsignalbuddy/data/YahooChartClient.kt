package com.nhsignalbuddy.data

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Yahoo Finance chart API (same family as the Python backend).
 * Unofficial endpoint — for personal use; may change without notice.
 */
class YahooChartClient(
    private val client: OkHttpClient = OkHttpClient()
) {

    suspend fun fetchDailyCloses(symbol: String, rangeParam: String): List<Double> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(symbol, Charsets.UTF_8.name())
            val url =
                "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?interval=1d&range=$rangeParam"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; NHSignalBuddy/1.0)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string().orEmpty()
                parseClosesFromChart(body)
            }
        }

    private fun parseClosesFromChart(json: String): List<Double> {
        val root = JSONObject(json)
        val result = root.optJSONObject("chart")?.optJSONArray("result") ?: return emptyList()
        if (result.length() == 0) return emptyList()
        val o = result.getJSONObject(0)
        val closes = o.optJSONObject("indicators")
            ?.optJSONArray("quote")
            ?.optJSONObject(0)
            ?.optJSONArray("close")
            ?: return emptyList()
        val out = ArrayList<Double>(closes.length())
        for (i in 0 until closes.length()) {
            if (closes.isNull(i)) continue
            out.add(closes.getDouble(i))
        }
        return out
    }
}
