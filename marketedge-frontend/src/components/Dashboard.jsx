// src/components/Dashboard.jsx

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import CandleChart from './CandleChart';
import SignalPanel from './SignalPanel';
import StatusBar from './StatusBar';
import ControlBar, { FX_SYMBOLS } from './ControlBar';
import { useWebSocket } from '../hooks/useWebSocket';

import {
  fetchCandles,
  fetchSymbols,
  fetchRecentTrades,
  triggerFetch,
  toDisplaySymbol,
} from '../services/api';

import {
  DEFAULT_TIMEFRAME,
  getCandleCount,
} from '../utils/TimeFrameConfig';

const DEFAULT_SYMBOL = 'XAUUSD';
const MAX_SIGNALS = 50;

export default function Dashboard() {
  const [symbol, setSymbol] = useState(DEFAULT_SYMBOL);
  const [timeframe, setTimeframe] = useState(DEFAULT_TIMEFRAME);

  const [symbols, setSymbols] = useState(FX_SYMBOLS);

  const [candles, setCandles] = useState([]);
  const [liveCandle, setLiveCandle] = useState(null);
  const [signals, setSignals] = useState([]);

  // FIX: tracks the most recent actionable (BUY/SELL) signal per strategy,
  // for the symbol/timeframe currently being viewed. Lets the panel keep
  // showing "the last triggered setup" when a strategy's live status drops
  // back to NO_TRADE / INSUFFICIENT_DATA, instead of going blank.
  const [lastHitSignals, setLastHitSignals] = useState({});

  const [health, setHealth] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // ─────────────────────────────────────────────────────────────
  // WebSocket Handlers
  // ─────────────────────────────────────────────────────────────

  const handleCandle = useCallback((candle) => {
    setLiveCandle(candle);

    setCandles((prev) => {
      if (!prev.length) return [candle];

      const exists = prev.some(
        (c) => String(c.timestamp) === String(candle.timestamp)
      );

      if (exists) {
        return prev.map((c) =>
          String(c.timestamp) === String(candle.timestamp) ? candle : c
        );
      }

      return [candle, ...prev];
    });
  }, []);

  // FIX: remembers the last actionable (BUY/SELL) signal per strategy+symbol,
  // independent of symbol/timeframe filtering — that filtering happens later,
  // in currentLastHitSignals below, the same way filteredSignals already
  // filters the live `signals` array.
  //
  // FIX (this pass): this now also receives historical TradeRecord rows from
  // the persistent-fallback fetch below. Both paths funnel through here, so
  // a timestamp guard is needed — a delayed historical fetch resolving after
  // a fresh live hit must not overwrite it, and an out-of-order historical
  // record must not overwrite a more recent one already stored.
  const recordIfActionable = useCallback((signal) => {
    if (!signal || !signal.strategyName || !signal.symbol) return;
    const actionable = signal.signalType === 'BUY' || signal.signalType === 'SELL';
    if (!actionable) return;

    const key = `${signal.strategyName}|${signal.symbol}`;

    setLastHitSignals((prev) => {
      const existing = prev[key];
      if (existing) {
        const existingTime = new Date(existing.evaluatedAt || existing.triggeredAt || 0).getTime();
        const incomingTime = new Date(signal.evaluatedAt || signal.triggeredAt || 0).getTime();
        if (Number.isFinite(existingTime) && Number.isFinite(incomingTime) && incomingTime < existingTime) {
          return prev; // what we already have is newer — keep it
        }
      }
      return { ...prev, [key]: signal };
    });
  }, []);

  // FIX: on mount and on every symbol change, seed lastHitSignals from the
  // database via GET /candles/trades/recent — this is what makes "last
  // triggered setup" survive a browser refresh instead of living only in
  // (volatile) React state.
  const seedHistoricalSignals = useCallback(async (sym) => {
    try {
      const trades = await fetchRecentTrades(sym, 5);
      if (Array.isArray(trades)) {
        trades.forEach(recordIfActionable);
      }
    } catch (err) {
      // Non-fatal — the live WebSocket feed will still populate this over
      // time. Just means the panel starts blank instead of pre-filled.
      console.warn('[Dashboard] Failed to load recent trade history:', err?.message);
    }
  }, [recordIfActionable]);

  useEffect(() => {
    seedHistoricalSignals(symbol);
  }, [symbol, seedHistoricalSignals]);

  const handleSignal = useCallback((signal) => {
    recordIfActionable(signal);

    setSignals((prev) => {
      const updated = prev.filter(
        (s) =>
          !(
            s.strategyName === signal.strategyName &&
            s.symbol === signal.symbol
          )
      );

      return [signal, ...updated].slice(0, MAX_SIGNALS);
    });
  }, [recordIfActionable]);

  const handleAllSignals = useCallback((tickSignals) => {
    if (!Array.isArray(tickSignals)) return;

    tickSignals.forEach(recordIfActionable);

    setSignals((prev) => {
      const map = new Map();

      prev.forEach((s) =>
        map.set(`${s.strategyName}_${s.symbol}`, s)
      );

      tickSignals.forEach((s) =>
        map.set(`${s.strategyName}_${s.symbol}`, s)
      );

      return Array.from(map.values()).slice(0, MAX_SIGNALS);
    });
  }, [recordIfActionable]);

  const handleHealth = useCallback((h) => setHealth(h), []);

  const { connected, lastError } = useWebSocket({
    symbol,
    timeframe,
    onCandle: handleCandle,
    onSignal: handleSignal,
    onAllSignals: handleAllSignals,
    onHealth: handleHealth,
  });

  // ─────────────────────────────────────────────────────────────
  // Data Loader (Stable & Safe)
  // ─────────────────────────────────────────────────────────────

  const loadCandles = useCallback(async (sym, tf) => {
    const limit = getCandleCount(tf);

    setLoading(true);
    setError(null);
    setLiveCandle(null);
    setCandles([]);

    try {
      const data = await fetchCandles(sym, tf, limit);
      setCandles(Array.isArray(data) ? data : []);
    } catch (err) {
      setError(err?.message || 'Failed to load candles');
    } finally {
      setLoading(false);
    }
  }, []);

  // ─────────────────────────────────────────────────────────────
  // Initial Symbol Load
  // ─────────────────────────────────────────────────────────────

  useEffect(() => {
    fetchSymbols()
      .then((list) => {
        if (!list?.length) return;

        const displayList = list
          .map(toDisplaySymbol)
          .filter((s) => FX_SYMBOLS.includes(s));

        if (displayList.length) {
          setSymbols(
            FX_SYMBOLS.filter((s) => displayList.includes(s))
          );
        }
      })
      .catch(() => {
        // fallback already handled
      });
  }, []);

  // ─────────────────────────────────────────────────────────────
  // Reload on Change
  // ─────────────────────────────────────────────────────────────

  useEffect(() => {
    loadCandles(symbol, timeframe);
    setSignals([]);
  }, [symbol, timeframe, loadCandles]);

  // ─────────────────────────────────────────────────────────────
  // Manual Refresh
  // ─────────────────────────────────────────────────────────────

  const handleRefresh = useCallback(async () => {
    const limit = getCandleCount(timeframe);

    setLoading(true);
    setError(null);
    setCandles([]);

    try {
      await triggerFetch(symbol, timeframe);

      const data = await fetchCandles(symbol, timeframe, limit);
      setCandles(Array.isArray(data) ? data : []);
    } catch (err) {
      setError(err?.message || 'Refresh failed');
    } finally {
      setLoading(false);
    }
  }, [symbol, timeframe]);

  // ─────────────────────────────────────────────────────────────
  // Derived Data (Optimized & Filtered by workspace)
  // ─────────────────────────────────────────────────────────────

  // ✅ FIX: Filters the global streaming feed to avoid overlay cross-contamination.
  // Kept timeframe-matched — this feeds the CHART price lines (latestSignals
  // below), where drawing a 15-minute entry/SL/TP line on a 1-hour chart's
  // price scale would be misleading.
  const filteredSignals = useMemo(() => {
    return signals.filter((s) => {
      if (!s) return false;

      // Strip potential slashes to perfectly match 'XAU/USD' with 'XAUUSD'
      const signalSymClean = s.symbol?.replace('/', '').toUpperCase();
      const currentSymClean = symbol?.replace('/', '').toUpperCase();

      // Isolate signals matching the currently viewed timeframe
      const isTimeframeMatch = !s.timeframe || s.timeframe === timeframe;

      return signalSymClean === currentSymClean && isTimeframeMatch;
    });
  }, [signals, symbol, timeframe]);

  // FIX (this pass): the Signal PANEL is symbol-only filtered, deliberately
  // NOT timeframe-filtered. Fusion Flow only ever runs on 15min and
  // SigmaStream only on 5min — if this stayed timeframe-matched the same way
  // filteredSignals is, those two cards would show "Pending initialization…"
  // for as long as you're looking at any OTHER chart timeframe, even though
  // both strategies are very much alive in the backend. The panel reports
  // each strategy's own status; only the chart overlay needs to match the
  // chart's own timeframe.
  const signalsForPanel = useMemo(() => {
    return signals.filter((s) => {
      if (!s) return false;
      const signalSymClean = s.symbol?.replace('/', '').toUpperCase();
      const currentSymClean = symbol?.replace('/', '').toUpperCase();
      return signalSymClean === currentSymClean;
    });
  }, [signals, symbol]);

  // Use the filtered set to map out singular strategy states for the chart lines
  const latestSignals = useMemo(() => {
    const map = new Map();

    filteredSignals.forEach((s) => {
      if (!map.has(s.strategyName)) {
        map.set(s.strategyName, s);
      }
    });

    return Array.from(map.values());
  }, [filteredSignals]);

  // FIX: project the global lastHitSignals map down to "strategyName -> signal"
  // for the symbol currently on screen (symbol-only, same reasoning as
  // signalsForPanel above — Fusion Flow's last 15min hit should still show
  // while you're looking at the 1h chart). Passed to SignalPanel as a
  // fallback so it can show "last triggered setup" when nothing is live
  // right now, whether that's because the strategy is outside its active
  // window or simply because the page was just refreshed.
  const currentLastHitSignals = useMemo(() => {
    const out = {};
    const currentSymClean = symbol?.replace('/', '').toUpperCase();

    Object.values(lastHitSignals).forEach((s) => {
      if (!s) return;
      const signalSymClean = s.symbol?.replace('/', '').toUpperCase();
      if (signalSymClean === currentSymClean) {
        out[s.strategyName] = s;
      }
    });

    return out;
  }, [lastHitSignals, symbol]);

  // ─────────────────────────────────────────────────────────────
  // UI
  // ─────────────────────────────────────────────────────────────

  return (
    <div className="dashboard">
      <header className="dashboard-header">
        <div className="logo">
          <span className="logo-mark">◈</span>
          <span className="logo-text">MarketEdge</span>
        </div>

        <ControlBar
          symbols={symbols}
          selectedSymbol={symbol}
          selectedTimeframe={timeframe}
          onSymbolChange={setSymbol}
          onTimeframeChange={setTimeframe}
          onRefresh={handleRefresh}
          loading={loading}
        />
      </header>

      {error && (
        <div className="error-banner">
          ⚠ {error}
          <button
            className="error-dismiss"
            onClick={() => setError(null)}
          >
            ✕
          </button>
        </div>
      )}

      <div className="dashboard-body">
        <main className="chart-area">
          <CandleChart
            candles={candles}
            liveCandle={liveCandle}
            symbol={symbol}
            timeframe={timeframe}
            signals={latestSignals}
            loading={loading}
          />
        </main>

        <aside className="sidebar">
          {/* ✅ FIX: Pass the cleaned filtered signals instead of global array.
              FIX (this pass): signalsForPanel (symbol-only) instead of
              filteredSignals (symbol+timeframe) — see comment above — plus
              lastHitSignals as the persistent fallback. */}
          <SignalPanel signals={signalsForPanel} lastHitSignals={currentLastHitSignals} />
        </aside>
      </div>

      <StatusBar
        connected={connected}
        health={health}
        lastError={lastError}
      />
    </div>
  );
}