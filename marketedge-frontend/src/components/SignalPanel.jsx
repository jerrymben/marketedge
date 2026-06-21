// src/components/SignalPanel.jsx

import React, { useMemo, useState, useEffect } from 'react';
import { fmt4, fmtTime } from '../utils/candleUtils';

// Live UTC clock for visually cross-checking session windows against the
// reasons shown on each card (e.g. "Outside entry window 06:05–23:45 UTC-4").
const useUtcClock = () => {
  const [time, setTime] = useState(new Date());
  useEffect(() => {
    const timer = setInterval(() => setTime(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);
  return time.toLocaleTimeString('en-GB', { timeZone: 'UTC', hour12: false });
};

// FIX: 'Alpha Matrix V2' removed. Per spec there are exactly three strategies —
// Fusion Flow, Alpha Matrix, SigmaStream. If "Alpha Matrix V2" still shows up
// in the live feed, it means the old standalone V2 strategy file/bean was
// never deleted from the backend and is still running alongside the new one.
const STRATEGY_NAMES = ['Fusion Flow', 'Alpha Matrix', 'SigmaStream'];

// Active-window reference (per the strategy refresh/execution timetable).
// Purely informational — tells the trader when to expect each card to go
// live again, instead of leaving them guessing why a card looks "inactive".
const STRATEGY_SCHEDULE = {
  'Fusion Flow': {
    days: 'Mon–Wed',
    local: '08:00–16:00 UTC',
    ist:   '1:30 PM–9:30 PM IST',
  },
  'Alpha Matrix': {
    days: 'Mon–Fri',
    local: '10:05–03:45 UTC (next day)',
    ist:   '3:35 PM–9:15 AM IST (next day)',
  },
  'SigmaStream': {
    days: 'Mon–Fri',
    local: '04:00–08:30 UTC range · 08:30–16:00 UTC entry',
    ist:   '9:30 AM–6:00 PM IST range · 6:00 PM–9:30 PM IST entry',
  },
};

const STATE_STYLE = {
  BUY:               { badge: 'badge-buy',    card: 'signal-buy'  },
  SELL:              { badge: 'badge-sell',   card: 'signal-sell' },
  NO_TRADE:          { badge: 'badge-wait',   card: 'signal-wait' },
  INSUFFICIENT_DATA: { badge: 'badge-data',   card: 'signal-data' },
  CREATED:           { badge: 'badge-wait',   card: 'signal-wait' },
  TRIGGERED:         { badge: 'badge-active', card: 'signal-active' },
  TP_HIT:            { badge: 'badge-tp',     card: 'signal-tp-hit' },
  SL_HIT:            { badge: 'badge-sl',     card: 'signal-sl-hit' },
  EXPIRED:           { badge: 'badge-expired',card: 'signal-expired' },
  INVALIDATED:       { badge: 'badge-expired',card: 'signal-expired' },
};

const isActionable = (s) => s && (s.signalType === 'BUY' || s.signalType === 'SELL');

/**
 * @param {Array}  signals          live, per-tick signals (NO_TRADE included) for the current symbol/timeframe
 * @param {Object} lastHitSignals   map of strategyName -> most recent actionable (BUY/SELL) signal seen
 *                                  for the current symbol/timeframe. Supplied by Dashboard.jsx.
 *
 * FIX (per requirement): "if there is no current output the dashboard should
 * show the previous or last hit strategy". A strategy with no live setup
 * right now (NO_TRADE / INSUFFICIENT_DATA / outside its trading window) will
 * fall back to the last BUY/SELL it actually fired, clearly marked as such,
 * instead of just showing "Pending initialization…" forever between setups.
 */
export default function SignalPanel({ signals, lastHitSignals = {} }) {
  const utcTime = useUtcClock();

  const signalMap = useMemo(() => {
    const map = new Map();
    (signals || []).forEach((s) => {
      if (s && s.strategyName) map.set(s.strategyName, s);
    });
    return map;
  }, [signals]);

  const strategyNames = useMemo(() => {
    const set = new Set(STRATEGY_NAMES);
    (signals || []).forEach((s) => { if (s?.strategyName) set.add(s.strategyName); });
    return Array.from(set);
  }, [signals]);

  return (
    <div className="signal-container">
      <div className="signal-header">
        <h3>Strategy Signals</h3>
        <div className="utc-clock-badge">System Time (UTC): {utcTime}</div>
      </div>

      <div className="signal-grid">
        {strategyNames.map((name) => {
          const live = signalMap.get(name) || null;
          const fallback = lastHitSignals?.[name] || null;

          // Show the live signal if it's actually a current setup or status
          // update; otherwise fall back to the last time this strategy fired.
          const useFallback = !isActionable(live) && fallback;
          const display = useFallback ? { ...fallback, isLastHit: true } : live;

          return <SignalCard key={name} name={name} signal={display} liveReason={live?.reason} />;
        })}
      </div>
    </div>
  );
}

function SignalCard({ name, signal, liveReason }) {
  if (!signal) {
    return (
      <div className="signal-card signal-empty">
        <h4>{name}</h4>
        <p>Pending initialization...</p>
        {STRATEGY_SCHEDULE[name] && (
          <div className="signal-schedule-hint">
            Active: {STRATEGY_SCHEDULE[name].days} · {STRATEGY_SCHEDULE[name].local}
            <br />
            ({STRATEGY_SCHEDULE[name].ist})
          </div>
        )}
      </div>
    );
  }

  const style = STATE_STYLE[signal.signalStatus] || STATE_STYLE.NO_TRADE;
  const timestamp = signal.evaluatedAt || signal.triggeredAt;

  return (
    <div className={`signal-card ${style.card} ${signal.isLastHit ? 'signal-stale' : ''}`}>
      <div className="signal-card-header">
        <h4>{name}</h4>
        <span className={`badge ${style.badge}`}>{signal.signalStatus || 'ACTIVE'}</span>
      </div>

      {/* FIX: clearly mark this as a historical fallback, not a live alert,
          so it doesn't look like a brand-new BUY/SELL just fired. */}
      {signal.isLastHit && (
        <div className="signal-last-hit-tag">
          ⏪ Last triggered setup — no current setup active
          {liveReason ? <span className="signal-last-hit-reason"> ({liveReason})</span> : null}
        </div>
      )}

      <div className="signal-details">
        <div className="signal-pair">
          {signal.symbol}
          {signal.timeframe && <span className="signal-timeframe"> · {signal.timeframe}</span>}
        </div>
      </div>

      {/* Trade Parameters (Entry/SL/TP) */}
      {(signal.entryPrice || signal.stopLoss || signal.takeProfit) && (
        <div className="signal-grid trade-params">
          <SignalRow label="Entry" value={fmt4(signal.entryPrice)} />
          <SignalRow label="SL" value={fmt4(signal.stopLoss)} className="signal-sl" />
          <SignalRow label="TP" value={fmt4(signal.takeProfit)} className="signal-tp" />
        </div>
      )}

      {/* Lifecycle & Confidence Metrics */}
      {(signal.confidenceScore != null || signal.tradeOutcome) && (
        <div className="signal-lifecycle-meta">
          <div className="signal-grid execution-details">
            {signal.confidenceScore != null && (
              <SignalRow label="Confidence" value={`${signal.confidenceScore}%`} />
            )}
            {signal.tradeOutcome && signal.tradeOutcome !== 'PENDING' && (
              <SignalRow label="Outcome" value={signal.tradeOutcome} className={`outcome-${signal.tradeOutcome.toLowerCase()}`} />
            )}
          </div>
        </div>
      )}

      {!signal.isLastHit && (
        <div className="signal-reason">
          {signal.reason || 'No explanation provided'}
        </div>
      )}

      <div className="signal-time">
        {timestamp ? fmtTime(timestamp) : '—'}
      </div>

      {STRATEGY_SCHEDULE[name] && (
        <div className="signal-schedule-hint">
          Active: {STRATEGY_SCHEDULE[name].days} · {STRATEGY_SCHEDULE[name].local}
          <br />
          ({STRATEGY_SCHEDULE[name].ist})
        </div>
      )}
    </div>
  );
}

function SignalRow({ label, value, className = '' }) {
  return (
    <div className="signal-row">
      <span className="signal-label">{label}</span>
      <span className={`signal-value ${className}`}>{value ?? '—'}</span>
    </div>
  );
}