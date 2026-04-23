package com.nhsignalbuddy.data

import com.nhsignalbuddy.model.SignalRow

/**
 * macro JSON에 `us_trend_picks`가 없을 때, 워치리스트·시그널로 US 종목 후보를 고른다.
 */
object TrendPickHelper {
    fun topUsFromSignals(rows: List<SignalRow>, limit: Int = 5): List<String> {
        if (rows.isEmpty()) return emptyList()
        fun score(r: SignalRow): Int = when {
            r.action == "BUY_CANDIDATE" -> 100
            r.action == "HOLD" && r.trend_up == true -> 50
            r.trend_up == true && r.vix_ok -> 35
            r.vix_ok -> 10
            else -> 0
        }
        return rows
            .sortedWith(
                compareByDescending<SignalRow> { score(it) }
                    .thenBy { it.symbol },
            )
            .map { it.symbol }
            .distinct()
            .take(limit)
    }
}
