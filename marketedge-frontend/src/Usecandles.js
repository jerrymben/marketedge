/**
 * useCandles — manages candle data lifecycle
 *
 * Fetches historical candles on mount / when symbol or timeframe changes,
 * then opens an SSE connection to receive live updates, prepending new bars
 * to the chart in real-time.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { fetchCandles } from '../services/api';
import { subscribeToCandleStream } from '../services/sseService';

const MAX_CANDLES = 300; // keep the last N bars in memory

export function useCandles(symbol, timeframe) {
  const [candles, setCandles]       = useState([]);
  const [loading, setLoading]       = useState(false);
  const [error, setError]           = useState(null);
  const [connected, setConnected]   = useState(false);
  const [lastUpdate, setLastUpdate] = useState(null);
  const unsubRef                    = useRef(null);

  // ── Helper: normalise a candle from the REST/SSE wire format ──────────────
  const normalise = useCallback((raw) => ({
    time:      raw.timestamp ? new Date(raw.timestamp).getTime() : Date.now(),
    open:      parseFloat(raw.openPrice  ?? raw.open  ?? 0),
    high:      parseFloat(raw.highPrice  ?? raw.high  ?? 0),
    low:       parseFloat(raw.lowPrice   ?? raw.low   ?? 0),
    close:     parseFloat(raw.closePrice ?? raw.close ?? 0),
    volume:    parseFloat(raw.volume ?? 0),
    symbol:    raw.symbol,
    timeframe: raw.timeframe,
  }), []);

  // ── Load historical data ───────────────────────────────────────────────────
  const loadHistory = useCallback(async () => {
    if (!symbol || !timeframe) return;
    setLoading(true);
    setError(null);
    try {
      const data = await fetchCandles(symbol, timeframe, 200);
      // REST returns newest-first; we need oldest-first for the chart
      const sorted = (Array.isArray(data) ? data : [])
        .map(normalise)
        .sort((a, b) => a.time - b.time)
        .slice(-MAX_CANDLES);
      setCandles(sorted);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [symbol, timeframe, normalise]);

  // ── SSE live feed ──────────────────────────────────────────────────────────
  const connectStream = useCallback(() => {
    if (!symbol || !timeframe) return;

    // Close any existing subscription
    if (unsubRef.current) {
      unsubRef.current();
      unsubRef.current = null;
    }

    const unsub = subscribeToCandleStream(
      symbol,
      timeframe,
      (raw) => {
        const candle = normalise(raw);
        setCandles((prev) => {
          // Deduplicate by time — update in-place if same timestamp
          const idx = prev.findIndex((c) => c.time === candle.time);
          if (idx !== -1) {
            const next = [...prev];
            next[idx] = candle;
            return next;
          }
          // Append and keep rolling window
          return [...prev, candle].slice(-MAX_CANDLES);
        });
        setLastUpdate(new Date());
        setConnected(true);
      },
      (errMsg) => {
        setError(errMsg);
        setConnected(false);
      }
    );

    unsubRef.current = unsub;
    setConnected(true);
  }, [symbol, timeframe, normalise]);

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  useEffect(() => {
    loadHistory();
    connectStream();
    return () => {
      if (unsubRef.current) {
        unsubRef.current();
        unsubRef.current = null;
      }
      setConnected(false);
    };
  }, [symbol, timeframe]); // eslint-disable-line react-hooks/exhaustive-deps

  return { candles, loading, error, connected, lastUpdate, reload: loadHistory };
}