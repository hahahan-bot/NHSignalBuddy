package com.nhsignalbuddy.model

/** NH 앱에서 수동 체결한 뒤 입력하는 보유 정보 (Python positions_state.json 과 동일 역할) */
data class Position(
    val entryPrice: Double,
    val qty: Int,
)
