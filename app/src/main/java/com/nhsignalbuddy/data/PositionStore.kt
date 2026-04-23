package com.nhsignalbuddy.data

import android.content.Context
import com.nhsignalbuddy.model.Position
import org.json.JSONObject

/**
 * Python 의 positions_state.json 과 같은 역할: 종목별 매수 평균가·수량.
 * 브로커 API와 연동하지 않고 사용자가 수동 입력한다.
 */
class PositionStore(context: Context) {
    private val prefs = context.getSharedPreferences("position_prefs", Context.MODE_PRIVATE)
    private val keyJson = "positions_json"

    fun get(symbol: String): Position? {
        val root = loadRoot()
        val o = root.optJSONObject(symbol.uppercase()) ?: return null
        val entry = o.optDouble("entry_price", 0.0)
        val qty = o.optInt("qty", 0)
        if (entry <= 0.0 || qty <= 0) return null
        return Position(entryPrice = entry, qty = qty)
    }

    fun set(symbol: String, entryPrice: Double, qty: Int) {
        val sym = symbol.uppercase()
        val root = loadRoot()
        val o = JSONObject()
        o.put("entry_price", entryPrice)
        o.put("qty", qty)
        root.put(sym, o)
        saveRoot(root)
    }

    fun clear(symbol: String) {
        val sym = symbol.uppercase()
        val root = loadRoot()
        root.remove(sym)
        saveRoot(root)
    }

    private fun loadRoot(): JSONObject {
        val raw = prefs.getString(keyJson, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun saveRoot(root: JSONObject) {
        prefs.edit().putString(keyJson, root.toString()).apply()
    }
}
