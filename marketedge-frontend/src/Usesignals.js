/**
 * useSignals — manages live strategy signals
 *
 * Opens an SSE connection for the selected symbol and accumulates
 * StrategySignal objects in a ring buffer (last 100).
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import { subscribeToSignalStream } from '../services/sseService';
import { fetchSignals } from '../services/api';

const MAX_SIGNALS = 100;

export function useSignals(symbol) {
  const [signals, setSignals]     = useState([]);
  const [loading, setLoading]     = useState(false);
  const [flash, setFlash]         = useState(null); // latest actionable signal for UI flash
  const unsubRef                  = useRef(null);
  const flashTimerRef             = useRef(null);

  // ── Load recent historical signals ────────────────────────────────────────
  const loadHistory = useCallback(async () => {
    if (!symbol) return;
    setLoading(true);
    try {
      const data = await fetchSignals(symbol, 50);
      setSignals(Array.isArray(data) ? data.slice(-MAX_SIGNALS) : []);
    } catch {
      // Signals history is non-critical; silent fail is acceptable
    } finally {
      setLoading(false);
    }
  }, [symbol]);

  // ── Trigger flash animation on actionable signal ───────────────────────────
  const triggerFlash = useCallback((signal) => {
    setFlash(signal);
    if (flashTimerRef.current) clearTimeout(flashTimerRef.current);
    flashTimerRef.current = setTimeout(() => setFlash(null), 4000);
  }, []);

  // ── SSE subscription ───────────────────────────────────────────────────────
  const connectStream = useCallback(() => {
    if (!symbol) return;
    if (unsubRef.current) { unsubRef.current(); unsubRef.current = null; }

    const unsub = subscribeToSignalStream(
      symbol,
      (signal) => {
        setSignals((prev) => {
          const next = [signal, ...prev].slice(0, MAX_SIGNALS);
          return next;
        });
        if (signal.signalType === 'BUY' || signal.signalType === 'SELL') {
          triggerFlash(signal);
        }
      },
      () => {} // silent error — stream will auto-reconnect
    );

    unsubRef.current = unsub;
  }, [symbol, triggerFlash]);

  useEffect(() => {
    loadHistory();
    connectStream();
    return () => {
      if (unsubRef.current) { unsubRef.current(); unsubRef.current = null; }
      if (flashTimerRef.current) clearTimeout(flashTimerRef.current);
    };
  }, [symbol]); // eslint-disable-line react-hooks/exhaustive-deps

  return { signals, loading, flash };
}