package com.marketedge.service;

import com.marketedge.model.Candle;
import com.marketedge.model.TradeRecord;
import com.marketedge.repository.CandleRepository;
import com.marketedge.repository.TradeRecordRepository;
import com.marketedge.strategy.SignalLifecycleTracker;
import com.marketedge.strategy.StrategyEvaluationException;
import com.marketedge.strategy.StrategySignal;
import com.marketedge.strategy.TradingStrategy;
import com.marketedge.strategy.SignalType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Strategy Engine — dispatcher wiring Redis candle stream to all TradingStrategy implementations.
 *
 * ROOT-CAUSE FIX (Bug 4):
 *   Previous version published signals ONLY to Redis channel "strategy_signals".
 *   Nobody was consuming that Redis channel and forwarding to WebSocket.
 *   Fix: inject WebSocketBroadcaster and call it directly after every evaluateAll().
 */
@Slf4j
@Service
public class StrategyEngineService {

    /**
     * FIX (this pass): was 100. 100 candles is nowhere near enough for the
     * multi-day reference strategies:
     *   - Fusion Flow needs Friday's London session visible on a Monday on
     *     the 15min chart — that's >2 calendar days back, ~150+ 15min bars
     *     in the worst case.
     *   - Alpha Matrix needs the FULL previous trading day visible on every
     *     timeframe, including weekends (Monday needs Friday, since Sunday
     *     has no FX data).
     * 500 covers the worst case (Monday + 15min) with comfortable margin on
     * every other timeframe, while staying well under what a single Postgres
     * query returns instantly.
     */
    public static final int    HISTORY_SIZE   = 500;
    public static final String SIGNAL_CHANNEL = "strategy_signals";

    private final List<TradingStrategy>         strategies;
    private final CandleRepository              candleRepository;
    private final TradeRecordRepository         tradeRecordRepository;     // FIX: centralised persistence
    private final SignalLifecycleTracker        signalLifecycleTracker;    // FIX: was never called anywhere
    private final RedisTemplate<String, Object> redisTemplate;
    private final WebSocketBroadcaster          webSocketBroadcaster;

    public StrategyEngineService(
            List<TradingStrategy>         strategies,
            CandleRepository              candleRepository,
            TradeRecordRepository         tradeRecordRepository,
            SignalLifecycleTracker        signalLifecycleTracker,
            RedisTemplate<String, Object> redisTemplate,
            WebSocketBroadcaster          webSocketBroadcaster) {

        this.strategies            = Collections.unmodifiableList(strategies);
        this.candleRepository      = candleRepository;
        this.tradeRecordRepository = tradeRecordRepository;
        this.signalLifecycleTracker = signalLifecycleTracker;
        this.redisTemplate         = redisTemplate;
        this.webSocketBroadcaster  = webSocketBroadcaster;

        log.info("[StrategyEngine] Initialised with {} strategy(ies): {}",
                this.strategies.size(),
                this.strategies.stream().map(TradingStrategy::getName).collect(Collectors.joining(", ")));
    }

    // ── Main entry point ─────────────────────────────────────────────────────

    public List<StrategySignal> onNewCandle(Candle latestCandle) {
        String symbol    = latestCandle.getSymbol();
        String timeframe = latestCandle.getTimeframe();

        log.debug("[StrategyEngine] Tick received — {}/{} @ {}", symbol, timeframe, latestCandle.getTimestamp());

        // FIX: SignalLifecycleTracker existed but nothing ever called it, so every
        // signal stayed in CREATED forever and win/loss tracking never ran.
        // Advance any already-open trades against this candle's H/L before
        // evaluating whether a brand-new signal should fire.
        try {
            signalLifecycleTracker.evaluate(latestCandle);
        } catch (Exception e) {
            log.error("[StrategyEngine] Lifecycle tracking failed for {}/{}: {}",
                    symbol, timeframe, e.getMessage(), e);
        }

        List<Candle> history = fetchHistory(symbol, timeframe);
        if (history.isEmpty()) {
            log.warn("[StrategyEngine] No historical data for {}/{} — skipping", symbol, timeframe);
            return Collections.emptyList();
        }

        List<StrategySignal> signals = evaluateAll(history, latestCandle);

        // FIX: publish actionable signals via BOTH Redis (for future consumers)
        // AND directly via WebSocket so the React frontend receives them immediately.
        // FIX (this pass): also persist a TradeRecord here for every actionable
        // signal, regardless of which strategy produced it. Previously only
        // Alpha Matrix persisted its own signals — Fusion Flow and SigmaStream
        // signals were never saved, so they could never be lifecycle-tracked.
        signals.stream().filter(StrategySignal::isActionable).forEach(signal -> {
            persistTradeRecord(signal);
            publishSignalToRedis(signal);
            webSocketBroadcaster.broadcastSignal(signal);
        });

        // FIX: also broadcast ALL signals (including NO_TRADE / INSUFFICIENT_DATA)
        // so the signal panel can show per-strategy status on every tick.
        webSocketBroadcaster.broadcastAllSignals(signals);

        return signals;
    }

    // ── TradeRecord persistence (centralised — see FIX above) ──────────────────

    private void persistTradeRecord(StrategySignal signal) {
        try {
            TradeRecord record = TradeRecord.from(signal);
            tradeRecordRepository.save(record);
            log.info("[StrategyEngine] 💾 TradeRecord saved ID={} {} {}/{} Conf={}",
                    signal.getSignalId().substring(0, 8), signal.getSignalType(),
                    signal.getSymbol(), signal.getTimeframe(), signal.getConfidenceScore());
        } catch (Exception e) {
            log.warn("[StrategyEngine] TradeRecord save failed for {}: {}",
                    signal.getSignalId().substring(0, 8), e.getMessage());
        }
    }

    // ── History fetch ─────────────────────────────────────────────────────────

    private List<Candle> fetchHistory(String symbol, String timeframe) {
        try {
            // FIX: was findTop200BySymbolAndTimeframeOrderByTimestampDesc() trimmed
            // to 100 — hardcoded and too small. findRecentCandles() already exists
            // (used by the REST controller) and accepts an arbitrary page size, so
            // reuse it here instead of adding yet another hardcoded-count query.
            List<Candle> desc = candleRepository.findRecentCandles(
                    symbol,
                    timeframe,
                    PageRequest.of(0, HISTORY_SIZE, Sort.by("timestamp").descending()));
            List<Candle> ordered = new ArrayList<>(desc);
            Collections.reverse(ordered);
            return ordered;
        } catch (Exception e) {
            log.error("[StrategyEngine] DB error for {}/{}: {}", symbol, timeframe, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ── Strategy dispatch ─────────────────────────────────────────────────────

    private List<StrategySignal> evaluateAll(List<Candle> history, Candle latestCandle) {
        List<StrategySignal> results = new ArrayList<>();
        for (TradingStrategy strategy : strategies) {
            String name      = strategy.getName();
            String symbol    = latestCandle.getSymbol();
            String timeframe = latestCandle.getTimeframe();

            if (!strategy.supports(symbol)) { continue; }

            if (history.size() < strategy.getMinimumBarsRequired()) {
                results.add(StrategySignal.insufficientData(name, symbol, timeframe,
                        String.format("Need ≥ %d bars, only %d available",
                                strategy.getMinimumBarsRequired(), history.size())));
                continue;
            }

            try {
                long start  = System.nanoTime();
                StrategySignal signal = strategy.evaluate(history, latestCandle);
                long usec   = (System.nanoTime() - start) / 1_000;
                if (signal == null) signal = StrategySignal.noTrade(name, symbol, timeframe, "null returned");
                results.add(signal);
                logSignal(signal, usec);
            } catch (StrategyEvaluationException e) {
                results.add(StrategySignal.insufficientData(name, symbol, timeframe, "Eval error: " + e.getMessage()));
            } catch (Exception e) {
                log.error("[StrategyEngine] Unexpected error in '{}': {}", name, e.getMessage(), e);
                results.add(StrategySignal.insufficientData(name, symbol, timeframe, "Error: " + e.getMessage()));
            }
        }
        return results;
    }

    // ── Redis publisher ───────────────────────────────────────────────────────

    private void publishSignalToRedis(StrategySignal signal) {
        try { redisTemplate.convertAndSend(SIGNAL_CHANNEL, signal); }
        catch (Exception e) { log.error("[StrategyEngine] Redis publish failed: {}", e.getMessage()); }
    }

    // ── Logging ───────────────────────────────────────────────────────────────

    private void logSignal(StrategySignal signal, long usec) {
        if (signal.isActionable()) {
            log.info("[StrategyEngine] ✅ {} — {} {}/{} Entry={} SL={} TP={} RR={} | {} ({}µs)",
                    signal.getSignalType(), signal.getStrategyName(),
                    signal.getSymbol(), signal.getTimeframe(),
                    signal.getEntryPrice(), signal.getStopLoss(), signal.getTakeProfit(),
                    signal.riskRewardRatio(), signal.getReason(), usec);
        } else {
            log.debug("[StrategyEngine] ⬜ {} {} {}/{} | {} ({}µs)",
                    signal.getSignalType(), signal.getStrategyName(),
                    signal.getSymbol(), signal.getTimeframe(), signal.getReason(), usec);
        }
    }

    // ── Public accessors ──────────────────────────────────────────────────────

    public List<String> getRegisteredStrategyNames() {
        return strategies.stream().map(TradingStrategy::getName).collect(Collectors.toList());
    }

    public List<StrategyMetadata> getStrategyMetadata() {
        return strategies.stream()
                .map(s -> new StrategyMetadata(s.getName(), s.getDescription(), s.getMinimumBarsRequired()))
                .collect(Collectors.toList());
    }

    public record StrategyMetadata(String name, String description, int minimumBarsRequired) {}
}