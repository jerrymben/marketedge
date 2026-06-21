// src/components/StatusBar.jsx
// Bottom status bar: WebSocket connection state, API rate limit remaining,
// and system health heartbeat from /topic/health.

import React from 'react';

export default function StatusBar({ connected, health, lastError }) {
  const statusColor = connected ? '#22c55e' : lastError ? '#ef4444' : '#f59e0b';
  const statusText  = connected ? 'Connected' : lastError ? 'Error' : 'Connecting…';

  return (
    <div className="status-bar">
      <div className="status-indicator">
        <span className="status-dot" style={{ background: statusColor }} />
        <span className="status-text">{statusText}</span>
        {lastError && <span className="status-error">{lastError}</span>}
      </div>

      {health && (
        <div className="health-info">
          <span className="health-item">
            API Credits: <strong>{health.rateLimitRemaining ?? '—'}</strong>/8
          </span>
          <span className="health-item">
            Calls Today: <strong>{health.totalApiCallsToday ?? '—'}</strong>
          </span>
          {health.strategies && (
            <span className="health-item">
              Strategies: <strong>{health.strategies.join(', ')}</strong>
            </span>
          )}
        </div>
      )}

      <div className="status-brand">MarketEdge</div>
    </div>
  );
}
