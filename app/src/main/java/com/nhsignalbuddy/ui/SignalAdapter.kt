package com.nhsignalbuddy.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nhsignalbuddy.R
import com.nhsignalbuddy.model.SignalRow
import java.util.Locale

class SignalAdapter : RecyclerView.Adapter<SignalAdapter.VH>() {
    private val items = mutableListOf<SignalRow>()

    /** ``macro_scenario_input.json`` 기준 종목당 유효 달러(트렌드 캡) — 카드에 표시 */
    var effectiveUsdPerSymbol: Double? = null

    /** 종목 카드 탭 → 매수가·수량 입력 */
    var onItemClick: ((SignalRow) -> Unit)? = null

    fun submitList(rows: List<SignalRow>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_signal_card, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.bind(row, effectiveUsdPerSymbol)
        holder.itemView.setOnClickListener { onItemClick?.invoke(row) }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val symbolText: TextView = itemView.findViewById(R.id.symbolText)
        private val actionText: TextView = itemView.findViewById(R.id.actionText)
        private val priceText: TextView = itemView.findViewById(R.id.priceText)
        private val tpSlText: TextView = itemView.findViewById(R.id.tpSlText)
        private val capHintText: TextView = itemView.findViewById(R.id.capHintText)
        private val reasonText: TextView = itemView.findViewById(R.id.reasonText)

        fun bind(row: SignalRow, trendCapUsd: Double?) {
            symbolText.text = row.symbol
            actionText.text = "action: ${row.action}"
            actionText.setTextColor(
                when (row.action) {
                    "BUY_CANDIDATE" -> Color.parseColor("#1B5E20")
                    "SELL" -> Color.parseColor("#B71C1C")
                    "HOLD" -> Color.parseColor("#0D47A1")
                    else -> Color.parseColor("#424242")
                }
            )

            val trend = when (row.trend_up) {
                true -> "UP"
                false -> "DOWN"
                null -> "N/A"
            }
            priceText.text =
                "last: ${String.format(Locale.US, "%.2f", row.last_price)}   trend: $trend   vix_ok: ${row.vix_ok}"

            val tp = row.tp_price?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
            val sl = row.sl_price?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
            tpSlText.text = "TP: $tp   SL: $sl"
            if (trendCapUsd != null && trendCapUsd > 0) {
                capHintText.visibility = View.VISIBLE
                capHintText.text = String.format(
                    Locale.US,
                    "trend cap (macro): ~$%.0f / symbol notional ref",
                    trendCapUsd,
                )
            } else {
                capHintText.visibility = View.GONE
            }
            reasonText.text = "reason: ${row.reason}"
        }
    }
}
