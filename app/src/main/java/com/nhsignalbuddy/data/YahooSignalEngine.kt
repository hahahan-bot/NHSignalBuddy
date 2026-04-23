package com.nhsignalbuddy.data

import com.nhsignalbuddy.model.SignalsPayload
import com.nhsignalbuddy.rules.SignalRules
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Yahoo 차트 API로 시세를 받아 Python 과 동일 규칙으로 시그널 생성.
 */
class YahooSignalEngine(
    private val client: YahooChartClient = YahooChartClient(),
) {

    suspend fun loadPayload(
        symbols: List<String>,
        positionStore: PositionStore,
    ): SignalsPayload {
        val vixCloses = client.fetchDailyCloses("^VIX", "3mo")
        val vixNow = vixCloses.lastOrNull()

        val rows = symbols.map { sym ->
            val closes = client.fetchDailyCloses(sym, "2y")
            val pos = positionStore.get(sym)
            SignalRules.buildRowFromCloses(sym, closes, vixNow, pos)
        }

        val generatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return SignalRules.payloadShell(generatedAt, vixNow, rows)
    }
}
