// src/utils/TimeFrameConfig.js
//
// Single source of truth for all timeframe definitions.
// Imported by ControlBar.jsx, Dashboard.jsx, and services/api.js.
//
// REQUIRED timeframes (per spec):  5M, 15M, 1H, 4H, 1D
// REMOVED timeframes:              1M (1min), 30M (30min)
//
// Candle counts match the specification matrix exactly:
//   5min  →  1440  (5 days  × 288 candles/day)
//   15min →  1440  (15 days × 96  candles/day)
//   1h    →   720  (30 days × 24  candles/day)
//   4h    →   360  (60 days × 6   candles/day)
//   1day  →    90  (90 days × 1   candle/day)
//
// `value` is the exact Twelve Data interval string stored in PostgreSQL.

export const TIMEFRAMES = [
  {
    value:         '5min',
    label:         '5M',
    candleCount:   1440,
    lookbackDays:  5,
    description:   '5 Days · 1440 bars',
  },
  {
    value:         '15min',
    label:         '15M',
    candleCount:   1440,
    lookbackDays:  15,
    description:   '15 Days · 1440 bars',
  },
  {
    value:         '1h',
    label:         '1H',
    candleCount:   720,
    lookbackDays:  30,
    description:   '30 Days · 720 bars',
  },
  {
    value:         '4h',
    label:         '4H',
    candleCount:   360,
    lookbackDays:  60,
    description:   '60 Days · 360 bars',
  },
  {
    value:         '1day',
    label:         '1D',
    candleCount:   90,
    lookbackDays:  90,
    description:   '90 Days · 90 bars',
  },
];

/** Default timeframe shown on first load. */
export const DEFAULT_TIMEFRAME = '1h';

/**
 * Returns the full config object for a timeframe value.
 * Falls back to 1h if the value is not recognised.
 */
export const getTimeframeConfig = (value) =>
  TIMEFRAMES.find((tf) => tf.value === value) ?? TIMEFRAMES[2];

/**
 * Returns the candle count (DB rows to fetch) for a timeframe.
 * This is passed as the `limit` query param to the backend.
 */
export const getCandleCount = (value) =>
  getTimeframeConfig(value).candleCount;