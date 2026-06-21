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
  const recordIfActionable = useCallback((signal) => {
    if (!signal || !signal.strategyName || !signal.symbol) return;
    const actionable = signal.signalType === 'BUY' || signal.signalType === 'SELL';
    if (!actionable) return;

    setLastHitSignals((prev) => ({
      ...prev,
      [`${signal.strategyName}|${signal.symbol}`]: signal,
    }));
  }, []);

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

  // ✅ FIX: Filters the global streaming feed to avoid overlay cross-contamination
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
  // for the symbol/timeframe currently on screen, the same way filteredSignals
  // narrows the live `signals` array. Passed to SignalPanel as a fallback so
  // it can show "last triggered setup" when nothing is live right now.
  const currentLastHitSignals = useMemo(() => {
    const out = {};
    const currentSymClean = symbol?.replace('/', '').toUpperCase();

    Object.values(lastHitSignals).forEach((s) => {
      if (!s) return;
      const signalSymClean = s.symbol?.replace('/', '').toUpperCase();
      const isTimeframeMatch = !s.timeframe || s.timeframe === timeframe;
      if (signalSymClean === currentSymClean && isTimeframeMatch) {
        out[s.strategyName] = s;
      }
    });

    return out;
  }, [lastHitSignals, symbol, timeframe]);

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
              FIX (this pass): also pass lastHitSignals so a strategy with no
              live setup right now still shows the last one it fired. */}
          <SignalPanel signals={filteredSignals} lastHitSignals={currentLastHitSignals} />
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