package com.nhsignalbuddy.data

import android.content.Context
import android.net.Uri
import com.nhsignalbuddy.model.Rules
import com.nhsignalbuddy.model.SignalRow
import com.nhsignalbuddy.model.SignalsPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.File

class SignalRepository(private val context: Context) {

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val cacheFile: File
        get() = File(context.filesDir, CACHE_FILE_NAME)

    fun loadFromAssets(fileName: String = "signals_latest.json"): SignalsPayload {
        val json = context.assets.open(fileName).bufferedReader().use { it.readText() }
        return parse(json)
    }

    fun loadFromInternalCacheFile(): SignalsPayload? {
        if (!cacheFile.isFile) return null
        return runCatching {
            val json = cacheFile.readText()
            parse(json)
        }.getOrNull()
    }

    /**
     * Syncthing·Download 등에 둔 `signals_latest.json` — [Uri]는 DocumentProvider 기준.
     */
    fun loadFromContentUri(uri: Uri): SignalsPayload {
        val json = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("contentResolver could not read uri")
        return parse(json)
    }

    private fun parse(raw: String): SignalsPayload {
        val root = JSONObject(raw)
        val rulesObj = root.getJSONObject("rules")
        val rules = Rules(
            tp_pct = rulesObj.optDouble("tp_pct", 15.0),
            sl_pct = rulesObj.optDouble("sl_pct", -8.0),
        )
        val rows = mutableListOf<SignalRow>()
        val arr = root.optJSONArray("rows")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                rows += SignalRow(
                    symbol = o.optString("symbol", ""),
                    last_price = o.optDouble("last_price", 0.0),
                    trend_up = if (o.isNull("trend_up")) null else o.optBoolean("trend_up"),
                    vix_ok = o.optBoolean("vix_ok", true),
                    action = o.optString("action", "WAIT"),
                    reason = o.optString("reason", ""),
                    tp_price = if (o.isNull("tp_price")) null else o.optDouble("tp_price"),
                    sl_price = if (o.isNull("sl_price")) null else o.optDouble("sl_price"),
                )
            }
        }
        return SignalsPayload(
            generated_at = root.optString("generated_at", ""),
            vix_now = if (root.isNull("vix_now")) null else root.optDouble("vix_now"),
            vix_max = root.optDouble("vix_max", 25.0),
            rules = rules,
            rows = rows,
        )
    }

    /**
     * 1) 원격 GET · 성공 시 [cacheFile] + [SignalRemotePreferences] 타임스탬프
     * 2) 이전에 성공한 로컬 캐시
     * 3) [loadFromAssets] 폴백
     */
    suspend fun loadFromRemoteUrlOrCacheOrAssets(
        remoteUrl: String,
        remotePrefs: SignalRemotePreferences,
    ): RemoteSignalsLoadResult = withContext(Dispatchers.IO) {
        val url = remoteUrl.trim()
        if (url.isEmpty()) {
            return@withContext RemoteSignalsLoadResult(
                loadFromAssets(),
                usedOfflineAfterRemoteOrMissingNetwork = false,
            )
        }

        val fetched = runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
                val body = response.body?.string() ?: error("empty body")
                val parsed = parse(body)
                cacheFile.writeText(body)
                remotePrefs.setLastFetchSuccessMillis(System.currentTimeMillis())
                parsed
            }
        }

        if (fetched.isSuccess) {
            return@withContext RemoteSignalsLoadResult(
                fetched.getOrThrow(),
                usedOfflineAfterRemoteOrMissingNetwork = false,
            )
        }

        val fromCache = loadFromInternalCacheFile()
        if (fromCache != null) {
            return@withContext RemoteSignalsLoadResult(
                fromCache,
                usedOfflineAfterRemoteOrMissingNetwork = true,
            )
        }

        RemoteSignalsLoadResult(
            loadFromAssets(),
            usedOfflineAfterRemoteOrMissingNetwork = true,
        )
    }

    data class RemoteSignalsLoadResult(
        val payload: SignalsPayload,
        /** 원격 URL이 있었는데 fetch에 실패해 캐시 또는 assets을 쓴 경우 */
        val usedOfflineAfterRemoteOrMissingNetwork: Boolean,
    )

    private companion object {
        const val CACHE_FILE_NAME = "signals_latest_cache.json"
    }
}
