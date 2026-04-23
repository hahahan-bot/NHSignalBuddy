package com.nhsignalbuddy.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PC와 동일한 ``macro_scenario_input.json`` 을
 * (1) ``ACTION_OPEN_DOCUMENT_TREE``로 저장한 폴더( kiwoom 등)에서 동일 파일명으로 로드
 * (2) 단일 파일 SAF URI
 * (3) 없으면 assets
 */
data class MacroTrendView(
    val aiCapMultiple: Double,
    val usBaseUsd: Double,
    val usEffectiveMultiple: Double,
    val usEffectivePerSymbolUsd: Double,
    val krRegimeCapKrw: Long?,
    val krEffectiveTrendCapKrw: Long?,
    val rawError: String?,
    /** UI 표시: "내장 assets/…", "macro_scenario_input.json", 등 */
    val sourceLabel: String = "",
    /** 그록·macro `grok` 블록 요약 (헤더 아래 전용 TextView) */
    val grokSummaryDisplay: String = "",
    /** `grok.us_trend_picks` — 비어 있으면 앱에서 시그널 기반 US 후보 표시 */
    val usTrendPicks: List<String> = emptyList(),
    /** `grok.kr_trend_picks` — 한국 티커/종목코드 등 (최대 5) */
    val krTrendPicks: List<String> = emptyList(),
) {
    fun headerLines(): String {
        val usLine = String.format(
            Locale.US,
            "US trend cap: $%.0f/symbol (base $%.0f × %.2f) | ai_cap=%.2f",
            usEffectivePerSymbolUsd,
            usBaseUsd,
            usEffectiveMultiple,
            aiCapMultiple,
        )
        val krLine = if (krRegimeCapKrw != null && krEffectiveTrendCapKrw != null) {
            String.format(
                Locale.KOREA,
                "KR trend cap(ref): %,d원 → %,d원 (×%.2f)",
                krRegimeCapKrw,
                krEffectiveTrendCapKrw,
                aiCapMultiple,
            )
        } else {
            "KR trend cap(ref): (macro에 kr_regime_cap_krw 없음)"
        }
        return "$usLine\n$krLine"
    }
}

class MacroScenarioRepository(
    private val context: Context,
    private val uriPrefs: MacroUriPreferences = MacroUriPreferences(context),
) {

    /** 동일 소스(URI/내장)에 대해 짧은 시간 내 반복 호출 시 I/O 생략. */
    private var lastLoadedTrend: MacroTrendView? = null
    private var lastLoadTimeMs: Long = 0
    private var lastCacheKey: String? = null

    private fun storageKey(): String {
        return uriPrefs.getTreeUriString()
            ?: uriPrefs.getSavedUriString()
            ?: "assets:$ASSET_NAME"
    }

    /** 캐시 무효화: 파일 재선택·URI 삭제·강제 새로고침 시 호출. */
    fun invalidateMacroCache() {
        lastLoadedTrend = null
        lastLoadTimeMs = 0
        lastCacheKey = null
    }

    /**
     * @param forceReload true 이면 캐시 무시(예: 사용자「macro 새로고침」, Syncthing 반영).
     */
    fun loadMacroData(forceReload: Boolean = false): MacroTrendView {
        if (forceReload) invalidateMacroCache()
        val key = storageKey()
        val now = System.currentTimeMillis()
        val hit = lastLoadedTrend != null &&
            lastCacheKey == key &&
            (now - lastLoadTimeMs) < CACHE_TTL_MS
        if (hit) {
            return lastLoadedTrend!!
        }
        val v = loadUncached()
        lastLoadedTrend = v
        lastLoadTimeMs = now
        lastCacheKey = key
        return v
    }

    /**
     * 내부·호환용. 캐시를 거치지 않고 즉시 읽으려면 [loadMacroData](forceReload = true).
     */
    fun load(): MacroTrendView = loadMacroData(forceReload = false)

    /**
     * 1) 트리(폴더) — Syncthing `kiwoom` 루트 등에서 `macro_scenario_input.json` 검색
     * 2) 단일 파일 URI
     * 3) assets
     */
    private fun loadUncached(): MacroTrendView {
        val treeStr = uriPrefs.getTreeUriString()
        if (!treeStr.isNullOrBlank()) {
            return loadFromTreeUri(Uri.parse(treeStr))
        }
        val uriStr = uriPrefs.getSavedUriString()
        if (!uriStr.isNullOrBlank()) {
            val uri = Uri.parse(uriStr)
            logPersistedUriMatch(uri)
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                }
                if (text != null) {
                    return parseJsonText(text).copy(
                        rawError = null,
                        sourceLabel = fileNameOnlyForUi(uri),
                    )
                }
                return mergeUriFailureFallback(uri)
            } catch (e: Exception) {
                return mergeUriFailureFallback(uri)
            }
        }
        return loadFromAssets()
    }

    private fun loadFromTreeUri(treeUri: Uri): MacroTrendView {
        logPersistedUriMatch(treeUri)
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
            if (root == null || !root.exists()) {
                return mergeTreeFailure("폴더(트리)를 열 수 없습니다. 다시「폴더 선택」하세요.")
            }
            var child = root.findFile("macro_scenario_input.json")
            if (child == null || !child.isFile) {
                child = root.listFiles()
                    ?.firstOrNull { f ->
                        f.isFile && f.name?.equals("macro_scenario_input.json", ignoreCase = true) == true
                    }
            }
            if (child == null || !child.isFile) {
                return mergeTreeFailure(
                    "이 폴더에 macro_scenario_input.json 이 없습니다. PC kiwoom 루트에 파일이 있는지 확인하세요.",
                )
            }
            val text = context.contentResolver.openInputStream(child.uri)?.use { input ->
                input.bufferedReader().readText()
            }
            if (text.isNullOrBlank()) {
                return mergeTreeFailure("macro_scenario_input.json 내용을 읽을 수 없습니다.")
            }
            val folderLabel = root.name?.trim().orEmpty().ifEmpty { "폴더" }
            parseJsonText(text).copy(
                rawError = null,
                sourceLabel = "${child.name ?: "macro_scenario_input.json"} ($folderLabel/트리)",
            )
        } catch (e: Exception) {
            Log.w(TAG, "loadFromTreeUri: ${e.message}")
            mergeTreeFailure(e.message)
        }
    }

    private fun mergeTreeFailure(detail: String? = null): MacroTrendView {
        val fb = loadFromAssets()
        return fb.copy(
            rawError = buildString {
                if (!detail.isNullOrBlank()) {
                    append(detail).append("\n\n")
                }
                append(MSG_URI_NOT_ACCESSIBLE)
            },
            sourceLabel = "폴더(트리) (폴백)",
        )
    }

    /**
     * 재부팅 후 등으로 persistable 권한이 사라진 경우 디버깅용.
     * [ContentResolver.getPersistedUriPermissions] 에 현재 macro URI와 일치하는 읽기 권한이 있는지 로그.
     */
    private fun logPersistedUriMatch(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        try {
            val perms = context.contentResolver.getPersistedUriPermissions()
            val matched = perms.any { p ->
                p.isReadPermission && p.uri.toString() == uri.toString()
            }
            Log.d(
                TAG,
                "[macroUri] persisted_count=${perms.size} read_persist_matched=$matched " +
                    "target=${uri.toString().take(96)}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "[macroUri] persistedUriPermissions query failed: ${e.message}")
        }
    }

    /** URI 실패 시 내장 assets 로 숫자는 유지하고 친절한 안내만 남김. */
    private fun mergeUriFailureFallback(uri: Uri): MacroTrendView {
        val fb = loadFromAssets()
        val label = fileNameOnlyForUi(uri)
        return fb.copy(
            rawError = MSG_URI_NOT_ACCESSIBLE,
            sourceLabel = "$label (폴백)",
        )
    }

    private fun loadFromAssets(): MacroTrendView {
        return try {
            val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            parseJsonText(text).copy(
                rawError = null,
                sourceLabel = "$ASSET_NAME (내장)",
            )
        } catch (e: Exception) {
            errorView(e.message ?: "assets 로드 실패", "assets")
        }
    }

    private fun errorView(msg: String, sourceLabel: String): MacroTrendView {
        return MacroTrendView(
            aiCapMultiple = 1.0,
            usBaseUsd = 500.0,
            usEffectiveMultiple = 1.0,
            usEffectivePerSymbolUsd = 500.0,
            krRegimeCapKrw = null,
            krEffectiveTrendCapKrw = null,
            rawError = msg,
            sourceLabel = sourceLabel,
            grokSummaryDisplay = "",
            usTrendPicks = emptyList(),
            krTrendPicks = emptyList(),
        )
    }

    /** 상태 줄에 넣기: 전체 URI 대신 파일명만 (경로 포함 시 마지막 세그먼트). */
    fun fileNameOnlyForUi(uri: Uri): String {
        val dn = queryDisplayName(uri)
        val raw = dn ?: uri.lastPathSegment ?: return "문서"
        val slash = raw.lastIndexOf('/')
        return if (slash >= 0 && slash < raw.length - 1) {
            raw.substring(slash + 1)
        } else {
            raw
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    c.getString(idx)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseJsonText(text: String): MacroTrendView {
        val root = JSONObject(text)
        val grok = root.optJSONObject("grok") ?: root
        val mapping = root.optJSONObject("_mapping")

        val aiFromGrok = grok.optDouble("ai_cap_multiple", Double.NaN)
        val aiFromMapping = mapping?.optDouble("ai_cap_multiple", Double.NaN) ?: Double.NaN
        val aiCap = when {
            !aiFromGrok.isNaN() -> aiFromGrok
            !aiFromMapping.isNaN() -> aiFromMapping
            else -> 1.0
        }.coerceIn(0.1, 20.0)

        val usBase = when {
            grok.has("us_per_symbol_budget_usd") && !grok.isNull("us_per_symbol_budget_usd") ->
                grok.optDouble("us_per_symbol_budget_usd", 500.0)
            else -> 500.0
        }.coerceAtLeast(1.0)

        val effMult = if (grok.isNull("us_ai_cap_multiple")) {
            aiCap
        } else {
            grok.optDouble("us_ai_cap_multiple", aiCap).coerceIn(0.1, 20.0)
        }

        val usEff = max(0.01, usBase * effMult)

        val krReg = if (grok.has("kr_regime_cap_krw") && !grok.isNull("kr_regime_cap_krw")) {
            grok.optLong("kr_regime_cap_krw", 0L).takeIf { it > 0 }
        } else {
            null
        }
        val krEff = krReg?.let { max(1L, (it * aiCap).roundToInt().toLong()) }

        val usPicks = optStringListLimit(grok, "us_trend_picks", 5) { it.uppercase(Locale.US) }
        val krPicks = optStringListLimit(grok, "kr_trend_picks", 5) { it.trim() }

        return MacroTrendView(
            aiCapMultiple = aiCap,
            usBaseUsd = usBase,
            usEffectiveMultiple = effMult,
            usEffectivePerSymbolUsd = usEff,
            krRegimeCapKrw = krReg,
            krEffectiveTrendCapKrw = krEff,
            rawError = null,
            sourceLabel = "",
            grokSummaryDisplay = buildGrokSummaryBlock(grok),
            usTrendPicks = usPicks,
            krTrendPicks = krPicks,
        )
    }

    private fun optStringListLimit(
        grok: JSONObject,
        key: String,
        max: Int,
        transform: (String) -> String,
    ): List<String> {
        val arr = grok.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(min(max, arr.length()))
        for (i in 0 until min(arr.length(), max)) {
            val s = transform(arr.optString(i, ""))
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun buildGrokSummaryBlock(grok: JSONObject): String {
        val date = grok.optString("date", "").trim()
        val regime = grok.optString("regime", "").trim()
        val risk = grok.optString("risk_on_off", "").trim()
        val oil = when {
            grok.has("oil_sensitivity") && !grok.isNull("oil_sensitivity") ->
                String.format(Locale.US, "%.2f", grok.optDouble("oil_sensitivity", 0.0))
            else -> ""
        }
        val head = buildString {
            append("[Grok·macro 요약]\n")
            if (date.isNotEmpty()) append("날짜: ").append(date).append('\n')
            val bits = mutableListOf<String>()
            if (regime.isNotEmpty()) bits.add("레짐: $regime")
            if (risk.isNotEmpty()) bits.add("리스크: $risk")
            if (oil.isNotEmpty()) bits.add("유가 민감도: $oil")
            if (bits.isNotEmpty()) {
                append(bits.joinToString("  |  "))
                append('\n')
            }
        }
        val events = grok.optJSONArray("key_events")
        val evLines = buildString {
            if (events != null && events.length() > 0) {
                append("주요 이슈:\n")
                for (i in 0 until events.length()) {
                    val line = events.optString(i, "").trim()
                    if (line.isNotEmpty()) append("• ").append(line).append('\n')
                }
            }
        }
        val sectors = grok.optJSONArray("sector_focus")
        val secLine = if (sectors != null && sectors.length() > 0) {
            val parts = (0 until sectors.length()).mapNotNull { i ->
                sectors.optString(i, "").trim().takeIf { it.isNotEmpty() }
            }
            if (parts.isNotEmpty()) "섹터 포커스: " + parts.joinToString(", ") + "\n" else ""
        } else {
            ""
        }
        val alerts = grok.optJSONArray("alert_conditions")
        val alertLines = buildString {
            if (alerts != null && alerts.length() > 0) {
                append("주의 조건:\n")
                for (i in 0 until min(alerts.length(), 6)) {
                    val o = alerts.optJSONObject(i) ?: continue
                    val cond = o.optString("condition", "").trim()
                    val pri = o.optString("priority", "").trim()
                    if (cond.isEmpty()) continue
                    if (pri.isNotEmpty()) append("• [").append(pri).append("] ")
                    else append("• ")
                    append(cond).append('\n')
                }
            }
        }
        val block = head + evLines + secLine + alertLines
        return block.trimEnd()
    }

    companion object {
        private const val TAG = "MacroScenarioRepo"
        private const val ASSET_NAME = "macro_scenario_input.json"
        private const val CACHE_TTL_MS = 5000L

        /** OPEN_DOCUMENT 피커가 처음 열릴 때 사용 (API 26+). */
        fun initialPickerUriDocumentsRoot(): android.net.Uri =
            DocumentsContract.buildDocumentUri(
                EXTERNAL_STORAGE_AUTHORITY,
                "primary:Documents",
            )

        /** Download 등 다른 위치를 쓰는 경우용 (기기별로 Documents가 없을 때 보조). */
        fun initialPickerUriDownloadRoot(): android.net.Uri =
            DocumentsContract.buildDocumentUri(
                EXTERNAL_STORAGE_AUTHORITY,
                "primary:Download",
            )

        /**
         * 이전에 선택한 문서의 **부모 폴더** — 피커 초기 위치로 적합.
         * 실패 시 null (Documents/Download 순 폴백).
         */
        fun parentFolderUriForDocument(documentUri: Uri): Uri? {
            return try {
                val authority = documentUri.authority ?: return null
                val docId = DocumentsContract.getDocumentId(documentUri)
                val slash = docId.lastIndexOf('/')
                if (slash <= 0) return null
                val parentId = docId.substring(0, slash)
                DocumentsContract.buildDocumentUri(authority, parentId)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * [EXTRA_INITIAL_URI] 우선순위: 저장 문서의 부모 폴더 → Documents → Download.
         */
        fun resolveInitialPickerUri(savedDocumentUri: Uri?): Uri {
            savedDocumentUri?.let { doc ->
                parentFolderUriForDocument(doc)?.let { return it }
            }
            return try {
                initialPickerUriDocumentsRoot()
            } catch (_: Exception) {
                initialPickerUriDownloadRoot()
            }
        }

        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        const val MSG_URI_NOT_ACCESSIBLE: String =
            "파일을 찾을 수 없거나 권한이 취소되었습니다. 파일을 다시 선택해주세요.\n" +
                "(지금은 내장 스냅샷으로 표시 중입니다.)"
    }
}
