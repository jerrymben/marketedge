// src/utils/candleUtils.js
//
// ROOT-CAUSE FIX — Why 1day candles disappeared silently:
//
// Spring Boot serializes java.time.Instant based on Jackson config.
// With write-dates-as-timestamps: false (our setting) it produces ISO-8601:
//   "2026-06-17T00:00:00Z"
//
// The PREVIOUS candleUtils.js did:
//   const ms = new Date(ts).getTime() / 1000;
// For a plain ISO string like "2026-06-17T00:00:00Z" this works fine.
// BUT if Jackson emits any other format the parser silently returns NaN,
// toCandleSeries() filters out NaN entries, and the chart shows nothing.
//
// Observed Jackson Instant formats in the wild:
//   "2026-06-17T00:00:00Z"           ISO-8601 with Z          ← our yml setting
//   "2026-06-17 00:00:00"            space separator (no Z)   ← Twelve Data raw
//   1750118400.000000000             epoch seconds as decimal  ← timestamps=true
//   { epochSecond: 1750118400 }      object form               ← some configs
//   [1750118400, 0]                  array [sec, nano]         ← rare
//
// parseInstantToSeconds() handles ALL of them so no candle is ever lost.

// ── Timestamp parsing ─────────────────────────────────────────────────────────

/**
 * Converts any Jackson Instant representation to Unix epoch SECONDS (integer).
 * Returns null only if the value is genuinely unparseable.
 */
export function parseInstantToSeconds(ts) {
  if (ts == null) return null;

  // ── String ─────────────────────────────────────────────────────────────────
  if (typeof ts === 'string') {
    if (!ts.trim()) return null;
    // Normalise "2026-06-17 00:00:00" → "2026-06-17T00:00:00Z"
    const iso = ts.includes('T') ? ts
              : ts.replace(' ', 'T') + (ts.endsWith('Z') ? '' : 'Z');
    const ms = Date.parse(iso);
    return isNaN(ms) ? null : Math.floor(ms / 1000);
  }

  // ── Number ─────────────────────────────────────────────────────────────────
  if (typeof ts === 'number') {
    // > 1e11 → epoch millis;  ≤ 1e11 → epoch seconds
    return isFinite(ts) ? (ts > 1e11 ? Math.floor(ts / 1000) : Math.floor(ts)) : null;
  }

  // ── Array [epochSecond, nanoAdjustment] ────────────────────────────────────
  if (Array.isArray(ts) && typeof ts[0] === 'number') {
    return Math.floor(ts[0]);
  }

  // ── Object { epochSecond, nano } ───────────────────────────────────────────
  if (typeof ts === 'object' && ts !== null && 'epochSecond' in ts) {
    const s = Number(ts.epochSecond);
    return isFinite(s) ? Math.floor(s) : null;
  }

  return null;
}

// ── Candle conversion ─────────────────────────────────────────────────────────

/**
 * Converts one Spring Boot Candle entity to Lightweight Charts CandlestickData.
 * Returns null if the timestamp cannot be parsed or any price is NaN.
 */
export function toChartCandle(candle) {
  if (!candle) return null;

  const time = parseInstantToSeconds(candle.timestamp);
  if (time === null || time <= 0) return null;

  const open  = parseFloat(candle.openPrice);
  const high  = parseFloat(candle.highPrice);
  const low   = parseFloat(candle.lowPrice);
  const close = parseFloat(candle.closePrice);

  if (isNaN(open) || isNaN(high) || isNaN(low) || isNaN(close)) return null;

  return { time, open, high, low, close };
}

/**
 * Converts an array of candles (newest → oldest from backend) to the
 * Lightweight Charts format: oldest → newest, deduplicated by time, sorted.
 */
export function toCandleSeries(candles) {
  if (!candles?.length) return [];

  const seen = new Set();
  return candles
    .map(toChartCandle)
    .filter((c) => {
      if (!c) return false;
      if (seen.has(c.time)) return false;
      seen.add(c.time);
      return true;
    })
    .sort((a, b) => a.time - b.time);
}

// ── Signal / display helpers ──────────────────────────────────────────────────

export const SIGNAL_COLORS = { BUY: '#22c55e', SELL: '#ef4444' };

export const fmt4 = (v) => (v != null ? parseFloat(v).toFixed(4) : '—');

export const fmtTime = (ts) => {
  if (!ts) return '—';
  const secs = parseInstantToSeconds(ts);
  if (secs === null) return String(ts);
  try { return new Date(secs * 1000).toLocaleString(); }
  catch { return String(ts); }
};