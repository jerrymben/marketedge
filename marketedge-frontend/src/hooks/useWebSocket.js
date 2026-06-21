// src/hooks/useWebSocket.js

import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const WS_URL = 'http://localhost:8080/api/ws';
const RECONNECT_MS = 5000;

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

function toTopicKey(displaySymbol) {
  if (!displaySymbol) return '';
  const api = DISPLAY_TO_API[displaySymbol?.toUpperCase()];
  return (api || displaySymbol).replace('/', '_');
}

export function useWebSocket({
  symbol,
  timeframe,
  onCandle,
  onSignal,
  onAllSignals,
  onHealth
}) {
  const clientRef = useRef(null);

  const subsRef = useRef({
    candle: null,
    signal: null,
    all: null,
    health: null,
    lifecycle: null // ✅ NEW
  });

  const cbCandle = useRef(onCandle);
  const cbSignal = useRef(onSignal);
  const cbAll = useRef(onAllSignals);
  const cbHealth = useRef(onHealth);

  const [connected, setConnected] = useState(false);
  const [lastError, setLastError] = useState(null);
  const [debugFeed, setDebugFeed] = useState([]);

  useEffect(() => { cbCandle.current = onCandle; }, [onCandle]);
  useEffect(() => { cbSignal.current = onSignal; }, [onSignal]);
  useEffect(() => { cbAll.current = onAllSignals; }, [onAllSignals]);
  useEffect(() => { cbHealth.current = onHealth; }, [onHealth]);

  const symbolKey = toTopicKey(symbol);

  useEffect(() => {
    if (!symbolKey || !timeframe) return;

    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: RECONNECT_MS,

      onConnect: () => {
        setConnected(true);
        setLastError(null);

        console.log(`[WS] Connected → ${symbolKey}/${timeframe}`);

        // Cleanup old subscriptions
        Object.values(subsRef.current).forEach(sub => sub?.unsubscribe?.());

        // ─────────────────────────────
        // 1. Candles
        // ─────────────────────────────
        subsRef.current.candle = client.subscribe(
          `/topic/candles/${symbolKey}/${timeframe}`,
          (msg) => {
            try {
              const data = JSON.parse(msg.body);
              cbCandle.current?.(data);

              setDebugFeed(prev => [
                { type: 'CANDLE', data },
                ...prev.slice(0, 20)
              ]);
            } catch (e) {
              console.error('[WS] Candle parse error', e);
            }
          }
        );

        // ─────────────────────────────
        // 2. BUY/SELL signals
        // ─────────────────────────────
        subsRef.current.signal = client.subscribe(
          '/topic/signals',
          (msg) => {
            try {
              const data = JSON.parse(msg.body);
              cbSignal.current?.(data);

              setDebugFeed(prev => [
                { type: 'SIGNAL', data },
                ...prev.slice(0, 20)
              ]);
            } catch (e) {
              console.error('[WS] Signal parse error', e);
            }
          }
        );

        // ─────────────────────────────
        // 3. ALL signals
        // ─────────────────────────────
        subsRef.current.all = client.subscribe(
          '/topic/signals/all',
          (msg) => {
            try {
              const data = JSON.parse(msg.body);
              cbAll.current?.(data);

              setDebugFeed(prev => [
                { type: 'ALL_SIGNALS', data },
                ...prev.slice(0, 20)
              ]);
            } catch (e) {
              console.error('[WS] AllSignals parse error', e);
            }
          }
        );

        // ─────────────────────────────
        // 4. ✅ LIFECYCLE (FIXED PROPERTIES & PAYLOAD SHAPE)
        // ─────────────────────────────
        subsRef.current.lifecycle = client.subscribe(
          '/topic/lifecycle',
          (msg) => {
            try {
              const raw = JSON.parse(msg.body);

              // 🔥 Normalize backend → frontend model
              const normalized = {
                id: raw.signalId,
                symbol: raw.symbol,
                timeframe: raw.timeframe,
                strategyName: raw.strategyName, // ✅ Fixed: mapped to strategyName instead of strategy

                status: raw.status,
                outcome: raw.outcome,
                confidence: raw.confidence,

                triggeredAt: raw.triggeredAt,
                closedAt: raw.closedAt,

                isActive: raw.status === 'TRIGGERED',
                isClosed: ['TP_HIT', 'SL_HIT', 'EXPIRED'].includes(raw.status)
              };

              console.log('[WS] Lifecycle:', normalized);

              // 🔥 Propagate properly
              cbAll.current?.([normalized]); // ✅ Fixed: Wrapped in an array to pass the Array.isArray validation check
              cbSignal.current?.(normalized);

              setDebugFeed(prev => [
                { type: 'LIFECYCLE', data: normalized },
                ...prev.slice(0, 20)
              ]);

            } catch (e) {
              console.error('[WS] Lifecycle parse error', e);
            }
          }
        );

        // ─────────────────────────────
        // 5. Health
        // ─────────────────────────────
        subsRef.current.health = client.subscribe(
          '/topic/health',
          (msg) => {
            try {
              cbHealth.current?.(JSON.parse(msg.body));
            } catch (e) {
              console.error('[WS] Health parse error', e);
            }
          }
        );
      },

      onDisconnect: () => {
        setConnected(false);
        console.log('[WS] Disconnected');
      },

      onStompError: (frame) => {
        setLastError(frame.headers?.message || 'STOMP error');
        setConnected(false);
      },

      onWebSocketError: () => {
        setLastError('WebSocket connection failed');
        setConnected(false);
      },
    });

    client.activate();
    clientRef.current = client;

    return () => {
      Object.values(subsRef.current).forEach(sub => sub?.unsubscribe?.());
      client.deactivate();
      clientRef.current = null;
    };
  }, [symbolKey, timeframe]);

  return {
    connected,
    lastError,
    debugFeed
  };
}