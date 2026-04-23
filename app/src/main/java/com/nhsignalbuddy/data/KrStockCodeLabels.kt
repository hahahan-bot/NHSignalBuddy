package com.nhsignalbuddy.data

/**
 * `kr_trend_picks`에 6자리 숫자 코드만 오면 한글 종목명을 붙여 표시한다.
 * 매핑에 없으면 코드만 보여준다. JSON에 이미 "삼성전자"처럼 한글이 있으면 그대로 둔다.
 */
object KrStockCodeLabels {

    private val nameBySixDigit: Map<String, String> = mapOf(
        "005930" to "삼성전자",
        "000660" to "SK하이닉스",
        "373220" to "LG에너지솔루션",
        "207940" to "삼성바이오로직스",
        "247540" to "에코프로비엠",
        "012330" to "현대모비스",
        "005380" to "현대차",
        "000270" to "기아",
        "035420" to "NAVER",
        "035720" to "카카오",
        "051910" to "LG화학",
        "006400" to "삼성SDI",
        "068270" to "셀트리온",
        "402340" to "SK스퀘어",
        "028260" to "삼성물산",
        "003670" to "포스코퓨처엠",
        "005490" to "POSCO홀딩스",
        "034730" to "SK",
        "096770" to "SK이노베이션",
        "017670" to "SK텔레콤",
        "032830" to "삼성생명",
        "015760" to "한국전력",
        "055550" to "신한지주",
        "105560" to "KB금융",
        "086790" to "하나금융지주",
        "316140" to "우리금융지주",
        "329180" to "현대중공업",
        "009540" to "HD한국조선해양",
        "010130" to "고려아연",
        "267260" to "HD현대일렉트릭",
        "042660" to "한화오션",
    )

    fun displayLabel(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        if (t.any { it in '\uAC00'..'\uD7A3' }) return t
        val digits = t.filter { it.isDigit() }
        if (digits.length < 4) return t
        val code6 = when {
            digits.length == 6 -> digits
            digits.length < 6 -> digits.padStart(6, '0')
            else -> digits.takeLast(6)
        }
        val name = nameBySixDigit[code6]
        return if (name != null) "$name ($code6)" else code6
    }
}
