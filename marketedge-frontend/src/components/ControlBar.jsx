// src/components/ControlBar.jsx
//
// Timeframe pills sourced from TimeFrameConfig.js.
// FX_SYMBOLS exported so Dashboard.jsx has one canonical list.
// REMOVED: 1M (1min), 30M (30min).

import React from 'react';
import { TIMEFRAMES } from '../utils/TimeFrameConfig';

// ── Canonical FX + Gold symbol list (display format, no slash) ───────────────
// Exported so Dashboard.jsx imports it instead of maintaining a duplicate.
// api.js SYMBOL_MAP converts these to Twelve Data format on every request.
export const FX_SYMBOLS = [
  'XAUUSD',  // Gold — top of list
  'EURUSD',
  'GBPUSD',
  'USDJPY',
  'GBPJPY',
  'EURJPY',
  'USDCHF',
  'CHFJPY',
  'GBPCAD',
];

export default function ControlBar({
  symbols,
  selectedSymbol,
  selectedTimeframe,
  onSymbolChange,
  onTimeframeChange,
  onRefresh,
  loading,
}) {
  const displaySymbols = symbols?.length ? symbols : FX_SYMBOLS;

  return (
    <div className="control-bar">

      {/* Symbol selector */}
      <div className="control-group">
        <label className="control-label">Symbol</label>
        <select
          className="control-select"
          value={selectedSymbol}
          onChange={(e) => onSymbolChange(e.target.value)}
        >
          {displaySymbols.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
      </div>

      {/* Timeframe pills — 5M 15M 1H 4H 1D only */}
      <div className="control-group">
        <label className="control-label">Timeframe</label>
        <div className="timeframe-pills">
          {TIMEFRAMES.map(({ value, label, description }) => (
            <button
              key={value}
              className={`pill ${selectedTimeframe === value ? 'pill-active' : ''}`}
              onClick={() => onTimeframeChange(value)}
              title={description}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {/* Refresh */}
      <button
        className="refresh-btn"
        onClick={onRefresh}
        disabled={loading}
        title="Pull latest bars from Twelve Data"
      >
        {loading ? '⟳ Loading…' : '↻ Refresh'}
      </button>

    </div>
  );
}