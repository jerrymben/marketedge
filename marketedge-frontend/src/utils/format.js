/**
 * Formatting utilities for the MarketEdge dashboard.
 */

/**
 * Format a timestamp (ms epoch) to a readable label.
 * @param {number} ms
 * @param {string} timeframe  e.g. '1min', '1h', '1day'
 */
export function formatTime(ms, timeframe = '1min') {
  if (!ms) return '';
  const d = new Date(ms);
  if (timeframe === '1day' || timeframe === '1week') {
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  }
  return d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false });
}

/**
 * Format a price with appropriate decimal places.
 */
export function formatPrice(price, symbol = '') {
  if (price == null || isNaN(price)) return '—';
  const decimals = symbol.includes('JPY') ? 2 : 4;
  return parseFloat(price).toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/**
 * Format a price change as ±x.xxxx (±x.xx%)
 */
export function formatChange(current, previous) {
  if (!current || !previous) return { abs: '—', pct: '—', direction: 'neutral' };
  const abs = current - previous;
  const pct = (abs / previous) * 100;
  const direction = abs > 0 ? 'up' : abs < 0 ? 'down' : 'neutral';
  return {
    abs: (abs >= 0 ? '+' : '') + abs.toFixed(4),
    pct: (pct >= 0 ? '+' : '') + pct.toFixed(2) + '%',
    direction,
  };
}

/**
 * Format a relative time (e.g. "2s ago", "1m ago")
 */
export function timeAgo(date) {
  if (!date) return '';
  const s = Math.floor((Date.now() - new Date(date).getTime()) / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  return `${Math.floor(m / 60)}h ago`;
}

/**
 * Compute candle body color
 */
export function candleColor(candle) {
  return candle.close >= candle.open ? '#10B981' : '#EF4444';
}