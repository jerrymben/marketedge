package com.marketedge.model;

import com.marketedge.strategy.SignalStatus;
import com.marketedge.strategy.SignalType;
import com.marketedge.strategy.StrategySignal;
import com.marketedge.strategy.TradeOutcome;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * JPA entity that persists every actionable StrategySignal to PostgreSQL.
 *
 * Purpose: provides durable storage for the dashboard to show:
 *   - Historical signal list per strategy / symbol
 *   - Win/loss ratio and confidence-score distribution
 *   - Chart overlays (entry/SL/TP lines on historical bars)
 *   - Backtesting replay by date range
 *
 * Lifecycle:
 *   TradeRecord.from(signal) creates the initial CREATED / PENDING row.
 *   SignalLifecycleTracker updates signalStatus, tradeOutcome, triggeredAt,
 *   and closedAt as price action progresses.
 *
 * DDL (add to schema.sql):
 *   See schema_trade_record.sql delivered alongside this file.
 */
@Entity
@Table(
    name = "trade_record",
    indexes = {
        @Index(name = "idx_trade_strategy_symbol",  columnList = "strategy_name, symbol"),
        @Index(name = "idx_trade_signal_status",    columnList = "signal_status"),
        @Index(name = "idx_trade_evaluated_at",     columnList = "evaluated_at DESC"),
        @Index(name = "idx_trade_signal_id",        columnList = "signal_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "trade_seq_gen")
    @SequenceGenerator(name = "trade_seq_gen", sequenceName = "trade_record_id_seq", allocationSize = 1)
    private Long id;

    /** UUID matching StrategySignal#getSignalId(). Unique per row. */
    @Column(name = "signal_id", nullable = false, unique = true, length = 36)
    private String signalId;

    @Column(name = "strategy_name", nullable = false, length = 50)
    private String strategyName;

    @Column(name = "symbol", nullable = false, length = 12)
    private String symbol;

    @Column(name = "timeframe", nullable = false, length = 5)
    private String timeframe;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 10)
    private SignalType signalType;

    @Column(name = "entry_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "stop_loss", nullable = false, precision = 18, scale = 4)
    private BigDecimal stopLoss;

    @Column(name = "take_profit", nullable = false, precision = 18, scale = 4)
    private BigDecimal takeProfit;

    /** Risk/reward ratio cached at creation time. */
    @Column(name = "risk_reward_ratio", precision = 8, scale = 4)
    private BigDecimal riskRewardRatio;

    /** 0–100 confidence score from the strategy. */
    @Column(name = "confidence_score", nullable = false)
    @Builder.Default
    private int confidenceScore = 0;

    /** Body/range ratio of the breakout candle. */
    @Column(name = "breakout_strength", precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal breakoutStrength = BigDecimal.ZERO;

    /** Normalised distance from exact Fib level (0.0 = perfect touch). */
    @Column(name = "retest_precision", precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal retestPrecision = BigDecimal.ZERO;

    /** ATR(14) at signal creation. Gives context for SL sizing. */
    @Column(name = "atr", precision = 18, scale = 4)
    private BigDecimal atr;

    /** London session H−L at signal creation. */
    @Column(name = "london_range_size", precision = 18, scale = 4)
    private BigDecimal londonRangeSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_status", nullable = false, length = 20)
    @Builder.Default
    private SignalStatus signalStatus = SignalStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_outcome", nullable = false, length = 20)
    @Builder.Default
    private TradeOutcome tradeOutcome = TradeOutcome.PENDING;

    /** UTC timestamp when the strategy produced this signal. */
    @Column(name = "evaluated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime evaluatedAt;

    /** UTC timestamp when entry price was first touched. Null until TRIGGERED. */
    @Column(name = "triggered_at", columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime triggeredAt;

    /** UTC timestamp when TP or SL was hit. Null until TP_HIT or SL_HIT. */
    @Column(name = "closed_at", columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime closedAt;

    /** Row insert timestamp. */
    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT NOW()")
    private Instant createdAt;

    /** Short description of why the signal was generated. */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    // ─── Lifecycle update helpers ──────────────────────────────────────────────

    public void markTriggered(LocalDateTime at) {
        this.signalStatus = SignalStatus.TRIGGERED;
        this.triggeredAt  = at;
    }

    public void markTpHit(LocalDateTime at) {
        this.signalStatus = SignalStatus.TP_HIT;
        this.tradeOutcome = TradeOutcome.WIN;
        this.closedAt     = at;
    }

    public void markSlHit(LocalDateTime at) {
        this.signalStatus = SignalStatus.SL_HIT;
        this.tradeOutcome = TradeOutcome.LOSS;
        this.closedAt     = at;
    }

    public void markExpired(LocalDateTime at) {
        this.signalStatus = SignalStatus.EXPIRED;
        this.closedAt     = at;
    }

    public void markInvalidated(LocalDateTime at) {
        this.signalStatus = SignalStatus.INVALIDATED;
        this.closedAt     = at;
    }

    // ─── Static factory ────────────────────────────────────────────────────────

    /**
     * Creates a TradeRecord from an actionable StrategySignal.
     * Sets createdAt = now; all analytics fields are copied from the signal.
     */
    public static TradeRecord from(StrategySignal signal) {
        return TradeRecord.builder()
                .signalId(signal.getSignalId())
                .strategyName(signal.getStrategyName())
                .symbol(signal.getSymbol())
                .timeframe(signal.getTimeframe())
                .signalType(signal.getSignalType())
                .entryPrice(signal.getEntryPrice())
                .stopLoss(signal.getStopLoss())
                .takeProfit(signal.getTakeProfit())
                .riskRewardRatio(signal.riskRewardRatio())
                .confidenceScore(signal.getConfidenceScore())
                .breakoutStrength(BigDecimal.valueOf(signal.getBreakoutStrength()))
                .retestPrecision(BigDecimal.valueOf(signal.getRetestPrecision()))
                .atr(signal.getAtr())
                .londonRangeSize(signal.getLondonRangeSize())
                .signalStatus(signal.getSignalStatus())
                .tradeOutcome(signal.getTradeOutcome())
                .evaluatedAt(signal.getEvaluatedAt())
                .reason(signal.getReason())
                .createdAt(Instant.now())
                .build();
    }
}