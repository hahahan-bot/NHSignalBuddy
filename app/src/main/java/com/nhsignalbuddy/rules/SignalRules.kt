package com.nhsignalbuddy.rules

import com.nhsignalbuddy.model.Position
import com.nhsignalbuddy.model.Rules
import com.nhsignalbuddy.model.SignalRow
import com.nhsignalbuddy.model.SignalsPayload
import kotlin.math.round

/**
 * Python signal_backend/generate_signals.py 와 동일 규칙.
 */
object SignalRules {
    const val TP_PCT = 15.0
    const val SL_PCT = -8.0
    const val VIX_MAX = 25.0
    const val MIN_BARS = 220

    fun sma(values: List<Double>, n: Int): Double? {
        if (values.size < n) return null
        return values.takeLast(n).average()
    }

    fun vixOk(vixNow: Double?): Boolean = vixNow == null || vixNow < VIX_MAX

    fun buildRowFromCloses(
        symbol: String,
        closes: List<Double>,
        vixNow: Double?,
        position: Position?,
    ): SignalRow {
        val vixGate = vixOk(vixNow)
        if (closes.size < MIN_BARS) {
            return SignalRow(
                symbol = symbol,
                last_price = 0.0,
                trend_up = null,
                vix_ok = vixGate,
                action = "WAIT",
                reason = "insufficient_data",
                tp_price = null,
                sl_price = null,
            )
        }
        val last = closes.last()
        val s50 = sma(closes, 50)
        val s200 = sma(closes, 200)
        val trend = if (s50 == null || s200 == null) null else (s50 > s200)
        return computeFromMetrics(
            symbol = symbol,
            last = last,
            trend = trend,
            vixOk = vixGate,
            position = position,
        )
    }

    /**
     * 오프라인(assets) 로드 후에도 앱에 저장된 포지션으로 action/tp/sl 을 다시 맞춘다.
     */
    fun applyPosition(row: SignalRow, position: Position?): SignalRow {
        if (row.reason == "insufficient_data") return row
        return computeFromMetrics(
            symbol = row.symbol,
            last = row.last_price,
            trend = row.trend_up,
            vixOk = row.vix_ok,
            position = position,
        )
    }

    private fun computeFromMetrics(
        symbol: String,
        last: Double,
        trend: Boolean?,
        vixOk: Boolean,
        position: Position?,
    ): SignalRow {
        val entry = position?.entryPrice ?: 0.0
        val qty = position?.qty ?: 0
        val tpPx = if (entry > 0.0) entry * (1.0 + TP_PCT / 100.0) else null
        val slPx = if (entry > 0.0) entry * (1.0 + SL_PCT / 100.0) else null

        val action: String
        val reason: String
        if (qty > 0 && entry > 0.0) {
            val pnl = (last / entry - 1.0) * 100.0
            when {
                trend == false -> {
                    action = "SELL"
                    reason = "dead_cross_state"
                }
                pnl >= TP_PCT -> {
                    action = "SELL"
                    reason = "take_profit"
                }
                pnl <= SL_PCT -> {
                    action = "SELL"
                    reason = "stop_loss"
                }
                else -> {
                    action = "HOLD"
                    reason = "in_position"
                }
            }
        } else {
            when {
                trend == true && vixOk -> {
                    action = "BUY_CANDIDATE"
                    reason = "trend_up_and_vix_ok"
                }
                trend == true && !vixOk -> {
                    action = "WAIT"
                    reason = "trend_up_but_vix_high"
                }
                else -> {
                    action = "WAIT"
                    reason = "no_entry_signal"
                }
            }
        }

        return SignalRow(
            symbol = symbol,
            last_price = round4(last),
            trend_up = trend,
            vix_ok = vixOk,
            action = action,
            reason = reason,
            tp_price = tpPx?.let { round4(it) },
            sl_price = slPx?.let { round4(it) },
        )
    }

    private fun round4(x: Double): Double = round(x * 10000.0) / 10000.0

    fun payloadShell(
        generatedAt: String,
        vixNow: Double?,
        rows: List<SignalRow>,
    ): SignalsPayload = SignalsPayload(
        generated_at = generatedAt,
        vix_now = vixNow,
        vix_max = VIX_MAX,
        rules = Rules(tp_pct = TP_PCT, sl_pct = SL_PCT),
        rows = rows,
    )
}
