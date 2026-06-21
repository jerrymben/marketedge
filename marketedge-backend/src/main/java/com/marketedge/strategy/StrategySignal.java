package com.marketedge.strategy;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable result produced by a single TradingStrategy#evaluate() call.
 *
 * ═══════════════════════════════════════════════════════════════════
 * ORIGINAL FIELDS — UNCHANGED (all existing builders still compile)
 * ═══════════════════════════════════════════════════════════════════
 * signalType, strategyName, symbol, timeframe,
 * entryPrice, stopLoss, takeProfit, reason, evaluatedAt
 *
 * ═══════════════════════════════════════════════════════════════════
 * NEW FIELDS — all @Builder.Default so existing callers compile
 * ═══════════════════════════════════════════════════════════════════
 * signalId         UUID per signal (FK for TradeRecord, lifecycle tracker)
 * confidenceScore  0–100 scored by the emitting strategy
 * breakoutStrength body/range ratio of the breakout candle (0.0–1.0)
 * retestPrecision  normalised distance from exact Fib level (0.0=perfect)
 * atr              ATR(14) at signal creation time
 * londonRangeSize  London session H−L at signal creation
 * signalStatus     CREATED → TRIGGERED → TP_HIT / SL_HIT
 * tradeOutcome     WIN / LOSS / BREAKEVEN / PENDING
 * triggeredAt      UTC when entry price was first touched
 * closedAt         UTC when TP or SL was hit
 */
@Getter
@Builder
@ToString
public class StrategySignal {

    // ─── Original fields (unchanged types and names) ───────────────────────────

    private final SignalType  signalType;
    private final String      strategyName;
    private final String      symbol;
    private final String      timeframe;
    private final BigDecimal  entryPrice;
    private final BigDecimal  stopLoss;
    private final BigDecimal  takeProfit;
    private final String      reason;

    @Builder.Default
    private final LocalDateTime evaluatedAt = LocalDateTime.now();

    // ─── New: Unique identity ──────────────────────────────────────────────────

    /**
     * UUID generated at signal creation.
     * Used as the primary key in TradeRecord and as the correlation key
     * in SignalLifecycleTracker. Auto-generated — callers do not set this.
     */
    @Builder.Default
    private final String signalId = UUID.randomUUID().toString();

    // ─── New: Quality analytics ────────────────────────────────────────────────

    /**
     * Confidence score 0–100 computed by the emitting strategy.
     *
     * Alpha Matrix scoring components:
     * Breakout strength  (0–30 pts) — body/range of breakout candle
     * Retest precision   (0–30 pts) — closeness to exact 78.6% Fib level
     * Volatility context (0–20 pts) — session range vs ATR(14)
     * Multi-candle conf  (0–20 pts) — both prev and latest close beyond level
     */
    @Builder.Default
    private final int confidenceScore = 0;

    /** Body/range ratio of the breakout candle (0.0 doji → 1.0 marubozu). */
    @Builder.Default
    private final double breakoutStrength = 0.0;

    /**
     * Normalised distance of retest candle's close from exact Fib level,
     * expressed as a fraction of session range. 0.0 = perfect touch, 1.0 = edge.
     * Lower is better.
     */
    @Builder.Default
    private final double retestPrecision = 0.0;

    /** ATR(14) at signal creation time. Null when not computed. */
    private final BigDecimal atr;

    /** London session H−L at signal creation. Null for non-actionable signals. */
    private final BigDecimal londonRangeSize;

    // ─── New: Lifecycle ────────────────────────────────────────────────────────

    @Builder.Default
    private final SignalStatus signalStatus = SignalStatus.CREATED;

    @Builder.Default
    private final TradeOutcome tradeOutcome = TradeOutcome.PENDING;

    /** Set when price touches the entry level (TRIGGERED). */
    private final LocalDateTime triggeredAt;

    /** Set when TP or SL is hit (TP_HIT / SL_HIT). */
    private final LocalDateTime closedAt;

    // ─── Static factory helpers (backward compatible — unchanged signatures) ───

    public static StrategySignal noTrade(String strategyName, String symbol,
                                         String timeframe, String reason) {
        return StrategySignal.builder()
                .signalType(SignalType.NO_TRADE)
                .strategyName(strategyName)
                .symbol(symbol)
                .timeframe(timeframe)
                .reason(reason)
                .build();
    }

    public static StrategySignal insufficientData(String strategyName, String symbol,
                                                   String timeframe, String reason) {
        return StrategySignal.builder()
                .signalType(SignalType.INSUFFICIENT_DATA)
                .strategyName(strategyName)
                .symbol(symbol)
                .timeframe(timeframe)
                .reason(reason)
                .build();
    }

    // ─── Derived helpers ───────────────────────────────────────────────────────

    /** True if this signal is a BUY or SELL. */
    public boolean isActionable() {
        return signalType == SignalType.BUY || signalType == SignalType.SELL;
    }

    /** True if the signal has reached a terminal lifecycle state. */
    public boolean isClosed() {
        return signalStatus == SignalStatus.TP_HIT
                || signalStatus == SignalStatus.SL_HIT
                || signalStatus == SignalStatus.EXPIRED
                || signalStatus == SignalStatus.INVALIDATED;
    }

    /**
     * Risk/reward ratio (reward distance / risk distance).
     * Returns null for non-actionable signals or missing prices.
     * Unchanged from original implementation.
     */
    @JsonProperty("riskRewardRatio")
    public BigDecimal riskRewardRatio() {
        if (!isActionable() || entryPrice == null || stopLoss == null || takeProfit == null) {
            return null;
        }
        BigDecimal risk   = entryPrice.subtract(stopLoss).abs();
        BigDecimal reward = takeProfit.subtract(entryPrice).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) return null;
        return reward.divide(risk, 4, RoundingMode.HALF_UP);
    }

    /**
     * Human-readable confidence band for UI badge display.
     * 80–100 → HIGH
     * 50–79  → MEDIUM
     * 0–49   → LOW
     */
    public String confidenceBand() {
        if (confidenceScore >= 80) return "HIGH";
        if (confidenceScore >= 50) return "MEDIUM";
        return "LOW";
    }

    /**
     * Compact one-line log summary.
     * Example: BUY XAU/USD 1h | Entry=2345.1200 SL=2320.0000 TP=2360.0000 RR=0.6094 Conf=72/MEDIUM | ID=a3f8b2c1
     */
    public String toLogSummary() {
        if (!isActionable()) {
            return signalType + " " + symbol + "/" + timeframe + " — " + reason;
        }
        return String.format(
                "%s %s %s | Entry=%s SL=%s TP=%s RR=%s Conf=%d/%s | ID=%s",
                signalType, symbol, timeframe,
                entryPrice, stopLoss, takeProfit,
                riskRewardRatio(), confidenceScore, confidenceBand(),
                signalId.substring(0, 8));
    }
}