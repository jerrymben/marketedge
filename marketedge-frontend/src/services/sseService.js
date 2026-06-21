/**
 * Server-Sent Events (SSE) Service
 *
 * Connects to the Spring Boot SSE endpoint that relays Redis pub/sub messages
 * to the browser. Two streams are provided:
 *   - /api/stream/candles   → live OHLCV candle updates
 *   - /api/stream/signals   → actionable strategy signals
 *
 * Each function returns a cleanup function to close the EventSource.
 */

const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api';

/**
 * Subscribe to the live candle stream for a given symbol + timeframe.
 *
 * @param {string}   symbol
 * @param {string}   timeframe
 * @param {Function} onCandle   - called with a parsed Candle object on each event
 * @param {Function} onError    - called with an error message string
 * @returns {Function}          - call to unsubscribe / close the stream
 */
export function subscribeToCandleStream(symbol, timeframe, onCandle, onError) {
  const url = `${BASE_URL}/stream/candles?symbol=${encodeURIComponent(symbol)}&timeframe=${encodeURIComponent(timeframe)}`;
  const es = new EventSource(url);

  es.onmessage = (event) => {
    try {
      const candle = JSON.parse(event.data);
      onCandle(candle);
    } catch (err) {
      onError?.(`Failed to parse candle event: ${err.message}`);
    }
  };

  es.onerror = () => {
    onError?.('Candle stream disconnected — reconnecting…');
    // EventSource auto-reconnects; no manual action needed
  };

  return () => es.close();
}

/**
 * Subscribe to the live strategy signal stream.
 *
 * @param {string}   symbol
 * @param {Function} onSignal   - called with a parsed StrategySignal object
 * @param {Function} onError
 * @returns {Function}          - cleanup / close
 */
export function subscribeToSignalStream(symbol, onSignal, onError) {
  const url = `${BASE_URL}/stream/signals?symbol=${encodeURIComponent(symbol)}`;
  const es = new EventSource(url);

  es.onmessage = (event) => {
    try {
      const signal = JSON.parse(event.data);
      onSignal(signal);
    } catch (err) {
      onError?.(`Failed to parse signal event: ${err.message}`);
    }
  };

  es.onerror = () => {
    onError?.('Signal stream disconnected — reconnecting…');
  };

  return () => es.close();
}

/**
 * SSE connection state constants.
 */
export const SSE_STATE = {
  CONNECTING: 0,
  OPEN: 1,
  CLOSED: 2,
};