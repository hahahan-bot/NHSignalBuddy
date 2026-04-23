package com.nhsignalbuddy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.nhsignalbuddy.alert.SignalAlertManager
import com.nhsignalbuddy.data.MacroScenarioRepository
import com.nhsignalbuddy.data.MacroTrendView
import com.nhsignalbuddy.data.MacroUriPreferences
import com.nhsignalbuddy.data.SignalRemotePreferences
import com.nhsignalbuddy.data.SignalUriPreferences
import com.nhsignalbuddy.data.KrStockCodeLabels
import com.nhsignalbuddy.data.TrendPickHelper
import com.nhsignalbuddy.data.PositionStore
import com.nhsignalbuddy.data.SignalRepository
import com.nhsignalbuddy.data.WatchlistStore
import com.nhsignalbuddy.data.YahooSignalEngine
import com.nhsignalbuddy.model.SignalRow
import com.nhsignalbuddy.model.SignalsPayload
import com.nhsignalbuddy.rules.SignalRules
import com.nhsignalbuddy.ui.SignalAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var watchlistStore: WatchlistStore
    private lateinit var positionStore: PositionStore
    private lateinit var adapter: SignalAdapter
    private lateinit var headerText: TextView
    private lateinit var macroFileStatusText: TextView
    private lateinit var pickMacroFileBtn: Button
    private lateinit var pickMacroTreeBtn: Button
    private lateinit var clearMacroUriBtn: Button
    private lateinit var refreshMacroFileBtn: Button
    private lateinit var autoRefreshMacroCheck: CheckBox
    private lateinit var grokSummaryText: TextView
    private lateinit var trendPicksText: TextView
    private lateinit var signalsFileStatusText: TextView
    private lateinit var pickSignalsFileBtn: Button
    private lateinit var clearSignalsFileBtn: Button
    private lateinit var refreshSignalsDataBtn: Button
    private lateinit var signalRemoteUrlEdit: EditText
    private lateinit var signalRemoteFetchTimeText: TextView
    private var lastPayload: SignalsPayload? = null
    private var lastMacroForUi: MacroTrendView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshBadgeHideRunnable: Runnable? = null

    private val yahooEngine = YahooSignalEngine()
    private val macroUriPrefs by lazy { MacroUriPreferences(this) }
    private val signalUriPrefs by lazy { SignalUriPreferences(this) }
    private val signalRemotePrefs by lazy { SignalRemotePreferences(this) }
    private val macroRepo by lazy { MacroScenarioRepository(this, macroUriPrefs) }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // No-op: alert manager checks permission before posting.
    }

    private val openMacroDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // 영구 권한을 주지 않는 공급자(드라이브·일부 OEM)는 세션 동안
            // Intent 플래그로 부여된 읽기 권한으로만 동작할 수 있음.
            // 이후에도 [loadMacroData] 가 같은 Uri 로 열 수 있으면 그대로 사용.
        }
        macroUriPrefs.saveUri(uri)
        macroRepo.invalidateMacroCache()
        val macro = macroRepo.loadMacroData(forceReload = true)
        Toast.makeText(
            this,
            "JSON 로드 완료: ai_cap_multiple = ${String.format(Locale.US, "%.2f", macro.aiCapMultiple)}",
            Toast.LENGTH_LONG,
        ).show()
        applyMacroFileUi(macro)
        refreshSignals()
    }

    /** Syncthing `kiwoom` 등 — 폴더 전체에 대한 읽기 권한 후 `macro_scenario_input.json` 자동 탐색. */
    private val openMacroTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // 일부 기기/OEM
        }
        macroUriPrefs.saveTreeUri(uri)
        macroRepo.invalidateMacroCache()
        val macro = macroRepo.loadMacroData(forceReload = true)
        Toast.makeText(
            this,
            "폴더 연결됨 · ai_cap_multiple = ${
                String.format(Locale.US, "%.2f", macro.aiCapMultiple)
            }",
            Toast.LENGTH_LONG,
        ).show()
        applyMacroFileUi(macro)
        refreshSignals()
    }

    private val openSignalDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        signalUriPrefs.saveUri(uri)
        Toast.makeText(this, "시그널 JSON 연결됨", Toast.LENGTH_SHORT).show()
        applySignalFileUi()
        refreshSignals()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        maybeRequestNotificationPermission()

        headerText = findViewById(R.id.headerText)
        macroFileStatusText = findViewById(R.id.macroFileStatusText)
        grokSummaryText = findViewById(R.id.grokSummaryText)
        trendPicksText = findViewById(R.id.trendPicksText)
        signalsFileStatusText = findViewById(R.id.signalsFileStatusText)
        pickSignalsFileBtn = findViewById(R.id.pickSignalsFileBtn)
        clearSignalsFileBtn = findViewById(R.id.clearSignalsFileBtn)
        refreshSignalsDataBtn = findViewById(R.id.refreshSignalsDataBtn)
        signalRemoteUrlEdit = findViewById(R.id.signalRemoteUrlEdit)
        signalRemoteFetchTimeText = findViewById(R.id.signalRemoteFetchTimeText)
        pickMacroFileBtn = findViewById(R.id.pickMacroFileBtn)
        pickMacroTreeBtn = findViewById(R.id.pickMacroTreeBtn)
        clearMacroUriBtn = findViewById(R.id.clearMacroUriBtn)
        refreshMacroFileBtn = findViewById(R.id.refreshMacroFileBtn)
        autoRefreshMacroCheck = findViewById(R.id.autoRefreshMacroCheck)
        val signalList = findViewById<RecyclerView>(R.id.signalList)
        val editBtn = findViewById<android.widget.Button>(R.id.editWatchlistBtn)
        adapter = SignalAdapter()
        watchlistStore = WatchlistStore(this)
        positionStore = PositionStore(this)
        signalList.layoutManager = LinearLayoutManager(this)
        signalList.adapter = adapter

        adapter.onItemClick = { row -> showPositionEditor(row.symbol) }

        pickMacroFileBtn.setOnClickListener { openMacroDocumentPicker() }
        pickMacroTreeBtn.setOnClickListener { openMacroTreePicker() }
        applySignalFileUi()
        pickSignalsFileBtn.setOnClickListener { openSignalDocumentPicker() }
        clearSignalsFileBtn.setOnClickListener {
            signalUriPrefs.getSavedUri()?.let { uri ->
                try {
                    contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {
                }
            }
            signalUriPrefs.clearUri()
            Toast.makeText(this, "Yahoo·내장 시그널을 사용합니다.", Toast.LENGTH_SHORT).show()
            applySignalFileUi()
            refreshSignals()
        }
        refreshSignalsDataBtn.setOnClickListener { refreshSignals() }

        clearMacroUriBtn.setOnClickListener {
            macroUriPrefs.getSavedUri()?.let { uri ->
                try {
                    contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {
                    // 무시
                }
            }
            macroUriPrefs.getTreeUri()?.let { uri ->
                try {
                    contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {
                    // 무시
                }
            }
            macroUriPrefs.clearUri()
            macroRepo.invalidateMacroCache()
            Toast.makeText(this, "내장 macro 스냅샷을 사용합니다.", Toast.LENGTH_SHORT).show()
            val macro = macroRepo.loadMacroData(forceReload = true)
            applyMacroFileUi(macro)
            refreshSignals()
        }

        autoRefreshMacroCheck.isChecked = getPreferences(MODE_PRIVATE).getBoolean(
            PREF_AUTO_REFRESH_MACRO,
            false,
        )
        autoRefreshMacroCheck.setOnCheckedChangeListener { _, isChecked ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_AUTO_REFRESH_MACRO, isChecked).apply()
        }

        refreshMacroFileBtn.setOnClickListener {
            refreshMacroFromFile(showToast = true, showRefreshBadge = true)
        }

        if (autoRefreshMacroCheck.isChecked) {
            refreshMacroFileBtn.isEnabled = false
            refreshMacroFileBtn.post {
                refreshMacroFromFile(showToast = false, showRefreshBadge = false)
                refreshMacroFileBtn.isEnabled = true
            }
        } else {
            val initialMacro = macroRepo.loadMacroData(forceReload = false)
            applyMacroFileUi(initialMacro)
        }
        maybePromptMacroIfNeeded()

        signalRemoteUrlEdit.setText(signalRemotePrefs.getRemoteUrl())
        applySignalFileUi()
        applyRemoteFetchFooterFromPrefs()
        refreshSignals()
        editBtn.setOnClickListener { showWatchlistEditor() }
    }

    /**
     * 디스크/Syncthing 반영 후 macro JSON만 다시 읽고 헤더·카드 참고 금액 갱신 (네트워크 TR 없음).
     * [forceReload]는 항상 true — 최신 파일 반영.
     */
    private fun refreshMacroFromFile(
        showToast: Boolean,
        showRefreshBadge: Boolean = false,
    ) {
        val macro = macroRepo.loadMacroData(forceReload = true)
        applyMacroFileUi(macro)
        if (showRefreshBadge) {
            showMacroRefreshBadge()
        }
        lastPayload?.let { p ->
            val rows = applyWatchlist(p)
            headerText.text = buildHeader(
                p.generated_at,
                p.vix_now,
                p.vix_max,
                macro,
            )
            adapter.effectiveUsdPerSymbol = macro.usEffectivePerSymbolUsd
            adapter.notifyDataSetChanged()
            applyGrokPanel(macro, rows)
        } ?: applyGrokPanel(macro, null)
        if (showToast) {
            val ac = String.format(Locale.US, "%.2f", macro.aiCapMultiple)
            if (!macroUriPrefs.hasExternalMacroSource()) {
                // assets는 APK에 박힌 스냅샷: 소스만 바꾸고 새로고침해도 휴 대기 앱이 바뀌지 않음
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "읽기 완료 · ai_cap_multiple = $ac (assets 내장)\n" +
                        "PC에서 고친 JSON이면 휴 대기(Download 등)에 복사한 뒤 [파일 선택]으로 열어 주세요. " +
                        "또는 Android Studio로 앱을 다시 설치(빌드)하면 내장 사본이 갱신됩니다.",
                    Snackbar.LENGTH_LONG,
                ).setAction("파일 선택") { openMacroDocumentPicker() }
                    .show()
            } else {
                Toast.makeText(
                    this,
                    "macro JSON 갱신 · ai_cap_multiple = $ac",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** macro 상태 줄에 "(새로고침됨)" 잠시 표시. */
    private fun showMacroRefreshBadge() {
        refreshBadgeHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val m = lastMacroForUi ?: return
        applyMacroFileUi(m, footerExtra = "\n(새로고침됨)")
        val r = Runnable {
            lastMacroForUi?.let { applyMacroFileUi(it, footerExtra = null) }
        }
        refreshBadgeHideRunnable = r
        mainHandler.postDelayed(r, 2500L)
    }

    private fun openMacroDocumentPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/plain", "text/*"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val initial = MacroScenarioRepository.resolveInitialPickerUri(
                        macroUriPrefs.getSavedUri(),
                    )
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                } catch (_: Exception) {
                    // EXTRA_INITIAL_URI 미지원·URI 오류 시 피커 기본 동작
                }
            }
        }
        openMacroDocumentLauncher.launch(intent)
    }

    private fun openMacroTreePicker() {
        openMacroTreeLauncher.launch(null)
    }

    private fun openSignalDocumentPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/plain", "text/*"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val initial = MacroScenarioRepository.resolveInitialPickerUri(
                        signalUriPrefs.getSavedUri(),
                    )
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                } catch (_: Exception) {
                }
            }
        }
        openSignalDocumentLauncher.launch(intent)
    }

    private fun applySignalFileUi() {
        val line = if (signalUriPrefs.hasUri()) {
            val seg = signalUriPrefs.getSavedUri()?.lastPathSegment?.toString().orEmpty()
            buildString {
                append("시그널: PC·Syncthing JSON (Yahoo·원격·내장 백업 생략)\n")
                if (seg.isNotBlank()) append("· ").append(seg)
            }
        } else if (signalRemotePrefs.getRemoteUrl().isNotBlank()) {
            "시그널: GitHub Pages URL → 캐시 → assets (Yahoo 생략)"
        } else {
            "시그널: Yahoo 실시간 → 실패 시 assets signals_latest.json"
        }
        signalsFileStatusText.text = line
    }

    /** 원격 URL 모드: 마지막 fetch 성공 시각(내부 저장). SAF·로컬 파일이 우선이면 숨김. */
    private fun applyRemoteFetchFooterFromPrefs() {
        val hasUrl = signalRemotePrefs.getRemoteUrl().isNotBlank()
        val okMs = signalRemotePrefs.getLastFetchSuccessMillis()
        if (signalUriPrefs.hasUri() || !hasUrl || okMs <= 0L) {
            signalRemoteFetchTimeText.visibility = android.view.View.GONE
            return
        }
        val fmt = SimpleDateFormat("a h:mm", Locale.KOREA)
        signalRemoteFetchTimeText.text = "업데이트: ${fmt.format(Date(okMs))}"
        signalRemoteFetchTimeText.visibility = android.view.View.VISIBLE
    }

    private fun applyMacroFileUi(macro: MacroTrendView, footerExtra: String? = null) {
        lastMacroForUi = macro
        val uriSaved = macroUriPrefs.hasExternalMacroSource()
        val lines = buildString {
            append("현재 연결: ")
            append(macro.sourceLabel.ifBlank { "—" })
            if (macro.rawError != null) {
                append("\n")
                append(macro.rawError)
            } else if (!uriSaved) {
                append("\n")
                append("※「macro 새로고침」만으로는 내장(APK) json이 바뀌지 않습니다. ")
                append("최신 PC본은 휴 대기에 복사 후「파일 선택」, 또는 앱을 다시 설치(빌드)하세요.")
            }
            if (!footerExtra.isNullOrBlank()) {
                append(footerExtra)
            }
        }
        macroFileStatusText.text = lines
        macroFileStatusText.setTextColor(
            if (macro.rawError != null) {
                Color.parseColor("#C62828")
            } else {
                Color.parseColor("#37474F")
            },
        )
        val warn = macro.rawError != null
        val btnTint = ColorStateList.valueOf(
            if (warn) {
                ContextCompat.getColor(this, R.color.macro_btn_warn_bg)
            } else {
                ContextCompat.getColor(this, R.color.macro_btn_default_tint)
            },
        )
        pickMacroFileBtn.backgroundTintList = btnTint
        pickMacroTreeBtn.backgroundTintList = btnTint
    }

    private fun maybePromptMacroIfNeeded() {
        val p = getPreferences(MODE_PRIVATE)
        if (p.getBoolean(PREF_SHOWN_MACRO_HINT, false)) return
        if (macroUriPrefs.hasExternalMacroSource()) return
        p.edit().putBoolean(PREF_SHOWN_MACRO_HINT, true).apply()
        Toast.makeText(
            this,
            "macro_scenario_input.json 을「파일 선택」으로 지정하면 PC와 동일한 트렌드 캡을 씁니다.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private suspend fun loadPayloadFromYahooOrAssets(): SignalsPayload {
        val wl = watchlistStore.load()
        return runCatching {
            yahooEngine.loadPayload(wl, positionStore)
        }.fold(
            onSuccess = { it },
            onFailure = { e ->
                Toast.makeText(
                    this@MainActivity,
                    "네트워크 실패, 저장된 파일 사용: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
                val base = SignalRepository(this@MainActivity).loadFromAssets()
                base.copy(
                    rows = base.rows.map { row ->
                        SignalRules.applyPosition(row, positionStore.get(row.symbol))
                    },
                )
            },
        )
    }

    private fun refreshSignals() {
        headerText.text = "업데이트 중…"
        lifecycleScope.launch {
            val signalRepo = SignalRepository(this@MainActivity)
            val remoteUrlTyped = signalRemoteUrlEdit.text.toString().trim()
            signalRemotePrefs.setRemoteUrl(remoteUrlTyped)

            val payload: SignalsPayload = if (signalUriPrefs.hasUri()) {
                val uri = checkNotNull(signalUriPrefs.getSavedUri())
                val fromFile = runCatching {
                    val base = signalRepo.loadFromContentUri(uri)
                    base.copy(
                        rows = base.rows.map { row ->
                            SignalRules.applyPosition(row, positionStore.get(row.symbol))
                        },
                    )
                }
                if (fromFile.isSuccess) {
                    fromFile.getOrThrow()
                } else {
                    val err = fromFile.exceptionOrNull()
                    Toast.makeText(
                        this@MainActivity,
                        "시그널 JSON 읽기 실패, Yahoo·내장: ${err?.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                    loadPayloadFromYahooOrAssets()
                }
            } else if (remoteUrlTyped.isNotEmpty()) {
                val r = signalRepo.loadFromRemoteUrlOrCacheOrAssets(
                    remoteUrlTyped,
                    signalRemotePrefs,
                )
                if (r.usedOfflineAfterRemoteOrMissingNetwork) {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "오프라인 데이터 사용 중",
                        Snackbar.LENGTH_LONG,
                    ).show()
                }
                r.payload.copy(
                    rows = r.payload.rows.map { row ->
                        SignalRules.applyPosition(row, positionStore.get(row.symbol))
                    },
                )
            } else {
                loadPayloadFromYahooOrAssets()
            }
            lastPayload = payload
            val macroFresh = macroRepo.loadMacroData(forceReload = false)
            applyMacroFileUi(macroFresh)
            applySignalFileUi()
            applyRemoteFetchFooterFromPrefs()
            headerText.text = buildHeader(
                payload.generated_at,
                payload.vix_now,
                payload.vix_max,
                macroFresh,
            )
            val rows = applyWatchlist(payload)
            adapter.effectiveUsdPerSymbol = macroFresh.usEffectivePerSymbolUsd
            adapter.submitList(rows)
            applyGrokPanel(macroFresh, rows)
            SignalAlertManager(this@MainActivity).notifyTransitions(rows)
        }
    }

    /** Grok JSON 요약 + US/KR 트렌드 후보( JSON 또는 시그널 폴백 ). */
    private fun applyGrokPanel(macro: MacroTrendView, rows: List<SignalRow>?) {
        val summary = macro.grokSummaryDisplay.trim()
        grokSummaryText.text = if (summary.isNotEmpty()) {
            summary
        } else {
            "(macro JSON grok 블록에 요약할 필드가 없습니다.)"
        }
        val usShown = if (macro.usTrendPicks.isNotEmpty()) {
            macro.usTrendPicks
        } else {
            TrendPickHelper.topUsFromSignals(rows.orEmpty(), 5)
        }
        val usNote = if (macro.usTrendPicks.isNotEmpty()) {
            "(macro JSON · us_trend_picks)"
        } else {
            "(워치리스트·실시간 시그널 기준 추정)"
        }
        val krShown = macro.krTrendPicks
        val krNote = if (krShown.isNotEmpty()) {
            "(macro JSON · kr_trend_picks)"
        } else {
            "macro JSON에 kr_trend_picks 를 넣으면 표시"
        }
        trendPicksText.text = buildString {
            append("[트렌드 유리 종목 · 각 최대 5개]\n")
            append("US ").append(usNote).append('\n')
            append(
                if (usShown.isNotEmpty()) {
                    usShown.joinToString(", ")
                } else {
                    "(워치에 종목이 없거나 후보 없음)"
                },
            )
            append("\n\n")
            append("KR ").append(krNote).append('\n')
            append(
                if (krShown.isNotEmpty()) {
                    krShown.joinToString(", ") { KrStockCodeLabels.displayLabel(it) }
                } else {
                    "—"
                },
            )
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun buildHeader(
        generatedAt: String,
        vixNow: Double?,
        vixMax: Double,
        macro: MacroTrendView,
    ): String {
        val vixText = if (vixNow == null) "N/A" else String.format(Locale.US, "%.2f", vixNow)
        val err = macro.rawError?.let { "\n$it" }.orEmpty()
        return "업데이트: $generatedAt\nVIX: $vixText / 게이트: < ${
            String.format(
                Locale.US,
                "%.2f",
                vixMax,
            )
        }\n---\n${macro.headerLines()}$err"
    }

    private fun applyWatchlist(payload: SignalsPayload): List<SignalRow> {
        val wl = watchlistStore.load()
        val map = payload.rows.associateBy { it.symbol.uppercase() }
        return wl.map { s ->
            map[s] ?: SignalRow(
                symbol = s,
                last_price = 0.0,
                trend_up = null,
                vix_ok = true,
                action = "WAIT",
                reason = "not_in_signal_payload",
                tp_price = null,
                sl_price = null,
            )
        }
    }

    private fun showWatchlistEditor() {
        val current = watchlistStore.load().joinToString(",")
        val input = EditText(this).apply {
            setText(current)
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "예: MRK,WMT,CAT,MU,STX"
        }
        AlertDialog.Builder(this)
            .setTitle("Watchlist 편집")
            .setMessage("쉼표(,)로 구분해서 입력하세요.")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val symbols = input.text.toString()
                    .split(",")
                    .map { it.trim().uppercase() }
                    .filter { it.isNotBlank() }
                watchlistStore.save(symbols)
                refreshSignals()
                Toast.makeText(this, "Watchlist 저장됨", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showPositionEditor(symbol: String) {
        val pos = positionStore.get(symbol)
        val pad = (resources.displayMetrics.density * 16).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val entryInput = EditText(this).apply {
            hint = "매수 평균가 (USD)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(
                pos?.entryPrice?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.4f", it) }.orEmpty(),
            )
        }
        val qtyInput = EditText(this).apply {
            hint = "수량 (주)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(pos?.qty?.takeIf { it > 0 }?.toString().orEmpty())
        }
        layout.addView(entryInput)
        layout.addView(qtyInput)

        AlertDialog.Builder(this)
            .setTitle("포지션: $symbol")
            .setMessage("NH 앱에서 체결한 뒤 평균가와 수량을 입력합니다. 미보유면 삭제하세요.")
            .setView(layout)
            .setNegativeButton("취소", null)
            .setNeutralButton("삭제") { _, _ ->
                positionStore.clear(symbol)
                refreshSignals()
                Toast.makeText(this, "포지션 삭제됨", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("저장") { _, _ ->
                val entry = entryInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
                val qty = qtyInput.text.toString().toIntOrNull() ?: 0
                when {
                    entry > 0.0 && qty > 0 -> {
                        positionStore.set(symbol, entry, qty)
                        refreshSignals()
                        Toast.makeText(this, "포지션 저장됨", Toast.LENGTH_SHORT).show()
                    }
                    entry <= 0.0 && qty <= 0 -> {
                        positionStore.clear(symbol)
                        refreshSignals()
                        Toast.makeText(this, "포지션 비움", Toast.LENGTH_SHORT).show()
                    }
                    else -> Toast.makeText(
                        this,
                        "매수가와 수량을 둘 다 입력하거나, 삭제를 누르세요.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        signalRemotePrefs.setRemoteUrl(signalRemoteUrlEdit.text.toString().trim())
    }

    override fun onDestroy() {
        // showMacroRefreshBadge()의 postDelayed 제거 (누수·백그라운드 콜백 방지)
        refreshBadgeHideRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacksAndMessages(null)
        refreshBadgeHideRunnable = null
        super.onDestroy()
    }

    companion object {
        private const val PREF_SHOWN_MACRO_HINT = "shown_macro_pick_hint_once"
        /** 시작 시 [refreshMacroFromFile] 자동 호출 */
        private const val PREF_AUTO_REFRESH_MACRO = "pref_auto_refresh_macro_on_start"
    }
}
