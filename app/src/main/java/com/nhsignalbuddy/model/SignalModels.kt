package com.nhsignalbuddy.model

data class SignalsPayload(
    val generated_at: String,
    val vix_now: Double?,
    val vix_max: Double,
    val rules: Rules,
    val rows: List<SignalRow>
)

data class Rules(
    val tp_pct: Double,
    val sl_pct: Double
)

data class SignalRow(
    val symbol: String,
    val last_price: Double,
    val trend_up: Boolean?,
    val vix_ok: Boolean,
    val action: String,
    val reason: String,
    val tp_price: Double?,
    val sl_price: Double?
)
