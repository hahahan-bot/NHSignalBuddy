from __future__ import annotations

import argparse
import json
import shutil
import urllib.parse
import urllib.request
from dataclasses import dataclass, asdict
from datetime import UTC, datetime
from pathlib import Path


DEFAULT_TICKERS = ["MRK", "WMT", "CAT", "MU", "STX"]
WATCHLIST_PATH = Path(__file__).resolve().parent / "watchlist.txt"
TP_PCT = 15.0
SL_PCT = -8.0
VIX_MAX = 25.0

STATE_PATH = Path(__file__).resolve().parent / "positions_state.json"
OUT_PATH = Path(__file__).resolve().parent / "signals_latest.json"


def fetch_series(symbol: str, rng: str = "2y") -> list[tuple[str, float]]:
    q = urllib.parse.quote(symbol, safe="")
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{q}?interval=1d&range={rng}"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        raw = json.loads(resp.read().decode("utf-8"))
    result = ((raw or {}).get("chart") or {}).get("result") or []
    if not result:
        return []
    ts = result[0].get("timestamp") or []
    closes = (((result[0].get("indicators") or {}).get("quote") or [{}])[0].get("close") or [])
    out: list[tuple[str, float]] = []
    for t, c in zip(ts, closes):
        if c is None:
            continue
        out.append((datetime.fromtimestamp(int(t), UTC).date().isoformat(), float(c)))
    return out


def sma(values: list[float], n: int) -> float | None:
    if len(values) < n:
        return None
    return sum(values[-n:]) / float(n)


def load_tickers(watchlist_file: Path | None) -> list[str]:
    path = watchlist_file or WATCHLIST_PATH
    if not path.exists():
        return list(DEFAULT_TICKERS)
    raw = path.read_text(encoding="utf-8").strip()
    if not raw:
        return list(DEFAULT_TICKERS)
    tickers: list[str] = []
    for part in raw.replace("\n", ",").split(","):
        s = part.strip().upper()
        if s:
            tickers.append(s)
    seen: set[str] = set()
    out: list[str] = []
    for t in tickers:
        if t not in seen:
            seen.add(t)
            out.append(t)
    return out if out else list(DEFAULT_TICKERS)


def load_positions() -> dict:
    if not STATE_PATH.exists():
        return {"positions": {}}
    try:
        return json.loads(STATE_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {"positions": {}}


@dataclass
class SignalRow:
    symbol: str
    last_price: float
    trend_up: bool | None
    vix_ok: bool
    action: str  # BUY_CANDIDATE / HOLD / SELL / WAIT
    reason: str
    tp_price: float | None
    sl_price: float | None


def main() -> None:
    ap = argparse.ArgumentParser(description="Generate signals_latest.json from watchlist")
    ap.add_argument(
        "--watchlist",
        type=Path,
        default=None,
        help=f"path to watchlist file (default: {WATCHLIST_PATH})",
    )
    ap.add_argument(
        "--output",
        type=Path,
        default=None,
        help=f"path to write JSON (default: {OUT_PATH})",
    )
    ap.add_argument(
        "--sync-app",
        action="store_true",
        help="copy signals_latest.json to NHSignalBuddy app assets",
    )
    args = ap.parse_args()

    tickers = load_tickers(args.watchlist)
    positions = load_positions().get("positions", {})
    vix_data = fetch_series("^VIX", "3mo")
    vix_now = vix_data[-1][1] if vix_data else None
    vix_ok = vix_now is None or vix_now < VIX_MAX

    rows: list[SignalRow] = []
    for symbol in tickers:
        series = fetch_series(symbol, "2y")
        if len(series) < 220:
            rows.append(
                SignalRow(symbol, 0.0, None, bool(vix_ok), "WAIT", "insufficient_data", None, None)
            )
            continue
        closes = [c for _, c in series]
        last = closes[-1]
        s50 = sma(closes, 50)
        s200 = sma(closes, 200)
        trend = None if (s50 is None or s200 is None) else (s50 > s200)

        pos = positions.get(symbol, {})
        entry = float((pos or {}).get("entry_price", 0.0) or 0.0)
        qty = int((pos or {}).get("qty", 0) or 0)
        tp_px = (entry * (1.0 + TP_PCT / 100.0)) if entry > 0 else None
        sl_px = (entry * (1.0 + SL_PCT / 100.0)) if entry > 0 else None

        if qty > 0 and entry > 0:
            pnl = (last / entry - 1.0) * 100.0
            if trend is False:
                action, reason = "SELL", "dead_cross_state"
            elif pnl >= TP_PCT:
                action, reason = "SELL", "take_profit"
            elif pnl <= SL_PCT:
                action, reason = "SELL", "stop_loss"
            else:
                action, reason = "HOLD", "in_position"
        else:
            if trend is True and vix_ok:
                action, reason = "BUY_CANDIDATE", "trend_up_and_vix_ok"
            elif trend is True and (not vix_ok):
                action, reason = "WAIT", "trend_up_but_vix_high"
            else:
                action, reason = "WAIT", "no_entry_signal"

        rows.append(
            SignalRow(
                symbol=symbol,
                last_price=round(last, 4),
                trend_up=trend,
                vix_ok=bool(vix_ok),
                action=action,
                reason=reason,
                tp_price=(round(tp_px, 4) if tp_px else None),
                sl_price=(round(sl_px, 4) if sl_px else None),
            )
        )

    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "vix_now": vix_now,
        "vix_max": VIX_MAX,
        "rules": {"tp_pct": TP_PCT, "sl_pct": SL_PCT},
        "rows": [asdict(r) for r in rows],
    }
    out_path = args.output if args.output is not None else OUT_PATH
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[ok] wrote {out_path} (tickers={tickers})")

    if args.sync_app:
        assets_dir = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets"
        dest = assets_dir / "signals_latest.json"
        if assets_dir.is_dir():
            shutil.copy2(out_path, dest)
            print(f"[ok] copied to {dest}")
        else:
            print(f"[warn] assets dir missing: {assets_dir}")


if __name__ == "__main__":
    main()
