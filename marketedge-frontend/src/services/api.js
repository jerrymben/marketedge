// src/services/api.js
//
// KEY FACTS ABOUT YOUR PROJECT SETUP:
//
// 1. You are using VITE (not CRA).
//    vite.config.js has a proxy: { '/api': 'http://localhost:8080' }
//    So fetches to /api/... are proxied to Spring Boot at 8080.
//    This file uses /api as the baseURL — correct for Vite.
//
// 2. Symbol URL encoding:
//    Spring @RequestParam decodes %2F → "/" automatically.
//    axios params object calls encodeURIComponent() before sending,
//    so passing { symbol: "XAU/USD" } in params produces ?symbol=XAU%2FUSD.
//    This is the correct and safe way to pass FX symbols.
//
// 3. URL pattern (symbol as query param, not path variable):
//    GET  /api/candles/{timeframe}?symbol=XAU%2FUSD&limit=720
//    POST /api/candles/{timeframe}/fetch?symbol=XAU%2FUSD
//
// 4. Candle limit:
//    fetchCandles() accepts a `limit` parameter that the caller provides
//    from getCandleCount(timeframe) in TimeFrameConfig.js.
//    This ensures 5min → 1440, 1h → 720, 1day → 90, etc.

import axios from 'axios';

// ── Symbol format maps ────────────────────────────────────────────────────────
// Frontend display (no slash) ↔ Twelve Data / PostgreSQL format (with slash)

const DISPLAY_TO_API = {
  XAUUSD: 'XAU/USD',
  EURUSD: 'EUR/USD',
  GBPUSD: 'GBP/USD',
  USDJPY: 'USD/JPY',
  GBPJPY: 'GBP/JPY',
  EURJPY: 'EUR/JPY',
  USDCHF: 'USD/CHF',
  CHFJPY: 'CHF/JPY',
  GBPCAD: 'GBP/CAD',
};

const API_TO_DISPLAY = Object.fromEntries(
  Object.entries(DISPLAY_TO_API).map(([d, a]) => [a, d])
);

/** "EURUSD" → "EUR/USD". Pass-through if not in map (e.g. AAPL). */
export const toApiSymbol = (display) =>
  DISPLAY_TO_API[display?.toUpperCase()] ?? display;

/** "EUR/USD" → "EURUSD". */
export const toDisplaySymbol = (api) =>
  API_TO_DISPLAY[api] ?? api?.replace('/', '') ?? api;

// ── Axios client ──────────────────────────────────────────────────────────────
const client = axios.create({
  baseURL: '/api',       // Vite proxy forwards /api/* → http://localhost:8080
  timeout: 15_000,
  headers: { 'Content-Type': 'application/json' },
});

client.interceptors.response.use(
  (res) => res,
  (err) => {
    const msg = err.response?.data?.message ?? err.message ?? 'Network error';
    console.error('[API]', err.config?.url, '→', msg);
    return Promise.reject(new Error(msg));
  }
);

// ── Candle endpoints ──────────────────────────────────────────────────────────

/**
 * Fetch the most recent `limit` candles for a symbol + timeframe.
 *
 * @param {string} displaySymbol  e.g. "XAUUSD"
 * @param {string} timeframe      e.g. "1h"  (Twelve Data interval string)
 * @param {number} limit          from getCandleCount(timeframe) — 90 to 1440
 *
 * Backend returns newest → oldest. Frontend (CandleChart) reverses via
 * toCandleSeries() in candleUtils.js.
 */
export const fetchCandles = (displaySymbol, timeframe, limit) => {
  const apiSymbol = toApiSymbol(displaySymbol);
  return client
    .get(`/candles/${timeframe}`, {
      params: { symbol: apiSymbol, limit },
    })
    .then((r) => r.data);
};

/**
 * Fetch candles within a UTC time range.
 * URL: GET /api/candles/{timeframe}/range?symbol=...&from=...&to=...
 */
export const fetchCandlesInRange = (displaySymbol, timeframe, from, to) =>
  client
    .get(`/candles/${timeframe}/range`, {
      params: { symbol: toApiSymbol(displaySymbol), from, to },
    })
    .then((r) => r.data);

/**
 * Trigger an immediate Twelve Data pull (Refresh button).
 * URL: POST /api/candles/{timeframe}/fetch?symbol=...
 */
export const triggerFetch = (displaySymbol, timeframe) =>
  client
    .post(`/candles/${timeframe}/fetch`, null, {
      params: { symbol: toApiSymbol(displaySymbol) },
    })
    .then((r) => r.data);

/**
 * Fetch every TradeRecord for a symbol from the last `days` days, across all
 * strategies and timeframes, newest first.
 *
 * Backs the persistent "last triggered setup" fallback in Dashboard.jsx —
 * called on mount and on every symbol change so the signal panel survives a
 * browser refresh instead of resetting to "Pending initialization…" until
 * the next live WebSocket hit.
 *
 * URL: GET /api/candles/trades/recent?symbol=...&days=5
 */
export const fetchRecentTrades = (displaySymbol, days = 5) =>
  client
    .get('/candles/trades/recent', {
      params: { symbol: toApiSymbol(displaySymbol), days },
    })
    .then((r) => r.data);

// ── Metadata ──────────────────────────────────────────────────────────────────

/** Returns symbols from DB in Twelve Data slash format ("XAU/USD"). */
export const fetchSymbols = () =>
  client.get('/candles/symbols').then((r) => r.data);

export const fetchStrategies = () =>
  client.get('/candles/strategies').then((r) => r.data);

export const fetchHealth = () =>
  client.get('/candles/health').then((r) => r.data);

export default client;