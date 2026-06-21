// src/components/CandleChart.jsx
//
// KEY BEHAVIOURS:
//  1. Chart is created ONCE on mount and never destroyed on symbol/timeframe
//     change. A loading overlay appears above it while data fetches.
//  2. When candles=[] (cleared by Dashboard before fetch) → series.setData([])
//  3. When candles=[...data] → series.setData(chartData) + scrollToRealtime()
//  4. On every live WebSocket tick → series.update() + scrollToRealtime()
//     so the chart always tracks current price without user scrolling.

import React, { useEffect, useRef } from 'react';
import { createChart, CrosshairMode } from 'lightweight-charts';
import { toChartCandle, toCandleSeries } from '../utils/candleUtils';

const C = {
  bg:       '#0f172a',
  grid:     '#1e293b',
  text:     '#94a3b8',
  border:   '#334155',
  up:       '#22c55e',
  down:     '#ef4444',
  wickUp:   '#16a34a',
  wickDown: '#dc2626',
  cross:    '#64748b',
};

export default function CandleChart({
  candles,
  liveCandle,
  symbol,
  timeframe,
  signals = [],
  loading,
}) {
  const containerRef  = useRef(null);
  const chartRef      = useRef(null);
  const seriesRef     = useRef(null);
  const priceLineRefs = useRef([]);

  // ── Create chart once on mount ────────────────────────────────────────────
  useEffect(() => {
    if (!containerRef.current) return;

    const chart = createChart(containerRef.current, {
      width:  containerRef.current.clientWidth  || 900,
      height: containerRef.current.clientHeight || 500,
      layout: { background: { color: C.bg }, textColor: C.text },
      grid:   { vertLines: { color: C.grid }, horzLines: { color: C.grid } },
      crosshair: {
        mode: CrosshairMode.Normal,
        vertLine: { color: C.cross, labelBackgroundColor: C.border },
        horzLine: { color: C.cross, labelBackgroundColor: C.border },
      },
      rightPriceScale: { borderColor: C.border },
      timeScale: {
        borderColor:    C.border,
        timeVisible:    true,
        secondsVisible: false,
        rightOffset:    5,   // small gap to the right of the latest bar
      },
    });

    const series = chart.addCandlestickSeries({
      upColor:         C.up,
      downColor:       C.down,
      borderUpColor:   C.up,
      borderDownColor: C.down,
      wickUpColor:     C.wickUp,
      wickDownColor:   C.wickDown,
    });

    chartRef.current  = chart;
    seriesRef.current = series;

    const ro = new ResizeObserver(() => {
      if (!containerRef.current || !chartRef.current) return;
      chartRef.current.applyOptions({
        width:  containerRef.current.clientWidth,
        height: containerRef.current.clientHeight,
      });
    });
    ro.observe(containerRef.current);

    return () => {
      ro.disconnect();
      chart.remove();
      chartRef.current  = null;
      seriesRef.current = null;
    };
  }, []); // mount once, never recreate

  // ── Load / clear data when candles prop changes ───────────────────────────
  useEffect(() => {
    if (!seriesRef.current) return;

    if (!candles || candles.length === 0) {
      // Dashboard set candles=[] before the fetch — clear the chart
      seriesRef.current.setData([]);
      return;
    }

    // toCandleSeries: converts newest-first array → oldest-first, deduplicates
    const chartData = toCandleSeries(candles);
    seriesRef.current.setData(chartData);

    // Snap to live price after every data load
    const ts = chartRef.current?.timeScale();
    if (ts) {
      if (typeof ts.scrollToRealtime === 'function') {
        ts.scrollToRealtime();
      } else {
        ts.fitContent();
      }
    }
  }, [candles]); // fires when Dashboard replaces the candles array reference

  // ── Live WebSocket tick ───────────────────────────────────────────────────
  useEffect(() => {
    if (!seriesRef.current || !liveCandle) return;
    try {
      seriesRef.current.update(toChartCandle(liveCandle));
      // Track live price
      const ts = chartRef.current?.timeScale();
      if (ts && typeof ts.scrollToRealtime === 'function') {
        ts.scrollToRealtime();
      }
    } catch (e) {
      console.warn('[Chart] Live update error:', e.message);
    }
  }, [liveCandle]);

  // ── Signal price lines ────────────────────────────────────────────────────
  useEffect(() => {
    if (!seriesRef.current) return;
    priceLineRefs.current.forEach((pl) => {
      try { seriesRef.current.removePriceLine(pl); } catch (_) {}
    });
    priceLineRefs.current = [];

    signals.forEach((signal) => {
      if (signal.signalType !== 'BUY' && signal.signalType !== 'SELL') return;
      const base = signal.signalType === 'BUY' ? C.up : C.down;
      [
        { price: parseFloat(signal.entryPrice), color: base,   lineWidth: 1, lineStyle: 0, title: `${signal.strategyName}` },
        { price: parseFloat(signal.stopLoss),   color: C.down, lineWidth: 1, lineStyle: 2, title: 'SL' },
        { price: parseFloat(signal.takeProfit), color: C.up,   lineWidth: 1, lineStyle: 2, title: 'TP' },
      ].forEach((def) => {
        if (!isNaN(def.price) && def.price > 0) {
          try { priceLineRefs.current.push(seriesRef.current.createPriceLine(def)); }
          catch (_) {}
        }
      });
    });
  }, [signals]);

  return (
    <div className="chart-wrapper">
      <div className="chart-header">
        <span className="chart-title">{symbol} · {timeframe}</span>
        {liveCandle && <span className="chart-live-badge">● LIVE</span>}
      </div>

      {/* Relative wrapper so overlay sits on top of chart */}
      <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
        <div
          ref={containerRef}
          className="chart-container"
          style={{ width: '100%', height: '100%' }}
        />
        {loading && (
          <div className="chart-loading-overlay">
            <div className="spinner" />
            <span>Loading {symbol} {timeframe}…</span>
          </div>
        )}
      </div>
    </div>
  );
}