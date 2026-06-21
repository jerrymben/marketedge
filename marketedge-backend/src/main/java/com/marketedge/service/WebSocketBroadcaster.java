package com.marketedge.service;

import com.marketedge.model.Candle;
import com.marketedge.strategy.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Broadcasts live market events to all connected React WebSocket clients via STOMP.
 *
 * Topics:
 *   /topic/candles/{symbol}/{timeframe}  → new OHLCV candle per tick
 *   /topic/signals                       → actionable BUY/SELL signals only
 *   /topic/signals/all                   → ALL signals per tick (incl NO_TRADE)
 *   /topic/health                        → system heartbeat every 30 s
 *
 * FIX (Bug 4): Added broadcastAllSignals() so the React panel can display
 * per-strategy evaluation results on every tick, not just on BUY/SELL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    // ── Candle broadcast ──────────────────────────────────────────────────────

    /**
     * Sends a live candle to /topic/candles/{symbol}/{timeframe}.
     * Symbol slashes are replaced with underscores for valid topic paths.
     * e.g. XAU/USD → /topic/candles/XAU_USD/1min
     */
    public void broadcastCandle(Candle candle) {
        String destination = "/topic/candles/"
                + candle.getSymbol().replace("/", "_")
                + "/" + candle.getTimeframe();
        try {
            messagingTemplate.convertAndSend(destination, candle);
            log.debug("[WS] Candle → {} @ {}", destination, candle.getTimestamp());
        } catch (Exception e) {
            log.error("[WS] Candle broadcast failed to {}: {}", destination, e.getMessage());
        }
    }

    // ── Signal broadcasts ─────────────────────────────────────────────────────

    /**
     * Sends one actionable signal to /topic/signals.
     * Called per-signal for BUY and SELL only.
     */
    public void broadcastSignal(StrategySignal signal) {
        try {
            messagingTemplate.convertAndSend("/topic/signals", signal);
            log.info("[WS] Signal → /topic/signals | {} {} {}",
                    signal.getSignalType(), signal.getStrategyName(), signal.getSymbol());
        } catch (Exception e) {
            log.error("[WS] Signal broadcast failed: {}", e.getMessage());
        }
    }

    /**
     * Sends the complete list of signals from one evaluation tick to /topic/signals/all.
     * This includes NO_TRADE and INSUFFICIENT_DATA so the React panel can show
     * each strategy's current evaluation result, not just when a trade fires.
     *
     * FIX (Bug 4): This is the key method that was missing. React subscribes to
     * /topic/signals/all to populate the SignalPanel on every candle.
     */
    public void broadcastAllSignals(List<StrategySignal> signals) {
        if (signals == null || signals.isEmpty()) return;
        try {
            messagingTemplate.convertAndSend("/topic/signals/all", signals);
            log.debug("[WS] All signals ({}) → /topic/signals/all", signals.size());
        } catch (Exception e) {
            log.error("[WS] broadcastAllSignals failed: {}", e.getMessage());
        }
    }

    // ── Health heartbeat ──────────────────────────────────────────────────────

    public void broadcastHealth(Map<String, Object> healthPayload) {
        try {
            messagingTemplate.convertAndSend("/topic/health", healthPayload);
            log.debug("[WS] Health heartbeat broadcast");
        } catch (Exception e) {
            log.error("[WS] Health broadcast failed: {}", e.getMessage());
        }
    }
}