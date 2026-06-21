package com.marketedge.strategy.impl;

import com.marketedge.model.Candle;
import com.marketedge.strategy.CandleUtils;
import com.marketedge.strategy.SignalType;
import com.marketedge.strategy.StrategySignal;
import com.marketedge.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Strategy 3 — SigmaStream (Gold breakout optimization).
 *
 * ════════════════════════════════════════════════════════════════════════
 *  FIX (this pass) — timezone and window corrections vs. spec
 * ════════════════════════════════════════════════════════════════════════
 *  The spec is explicit: "Timezone: UTC -4 (New York Session)" and
 *  "Placing the Order Between (08:30 - 12:00 PM) UTC -4". The previous
 *  version used UTC throughout (via CandleUtils.isAfterSigmaRangeEnd /
 *  getSigmaRangeCandles, both UTC-based) and had NO upper bound on the
 *  entry window — it would keep allowing trades all the way to midnight.
 *
 *  This version:
 *    1. Uses America/New_York (the same "UTC-4" convention already used by
 *       Alpha Matrix elsewhere in this codebase) for both the range window
 *       and the entry window, instead of the shared UTC-based CandleUtils
 *       helpers.
 *    2. Adds the missing 12:00 PM NY cutoff — after that, the setup for the
 *       day is over.
 *    3. Adds a 5-minute timeframe guard — SigmaStream is only specified on
 *       the 5-minute chart.
 *  Range window (unchanged in concept): 00:00–08:30 NY time, same calendar
 *  day, immediately preceding the entry window.
 */
@Slf4j
@Component
public class SigmaStreamStrategy implements TradingStrategy {

    private static final String NAME = "SigmaStream";
    private static final double RR_MULTIPLIER = 2.0;
    private static final int DISPLAY_SCALE = 4;

    /** FIX: SigmaStream is specified only on the 5-minute timeframe. */
    private static final String REQUIRED_TIMEFRAME = "5min";

    /** FIX: "UTC -4 (New York Session)" per spec — same convention as Alpha Matrix. */
    private static final ZoneId ZONE_NY = ZoneId.of("America/New_York");

    private static final LocalTime RANGE_START        = LocalTime.of(0, 0);
    private static final LocalTime RANGE_END           = LocalTime.of(8, 30);
    /** FIX: spec caps order placement at 12:00 PM NY — previously unbounded. */
    private static final LocalTime ENTRY_WINDOW_CLOSE = LocalTime.of(12, 0);

    // Minimum allowable range step ($0.50 for Gold) to guarantee risk-reward math can't divide by zero
    private static final BigDecimal MINIMUM_RISK_BUFFER = new BigDecimal("0.5000");

    private static final Set<String> GOLD_SYMBOLS = Set.of("XAU/USD", "XAUUSD", "GOLD");

    @Override
    public String getName() { return NAME; }

    @Override
    public String getDescription() {
        return "Asian/pre-NY-session range (00:00–08:30 UTC-4/NY) breakout for Gold (XAU/USD), " +
               "5-minute timeframe only. Targets 2:1 reward-to-risk on confirmed breakouts, " +
               "entry window 08:30–12:00 UTC-4/NY.";
    }

    @Override
    public int getMinimumBarsRequired() { return 30; }

    @Override
    public boolean supports(final String symbol) {
        return symbol != null && GOLD_SYMBOLS.contains(symbol.toUpperCase().trim());
    }

    @Override
    public StrategySignal evaluate(final List<Candle> historicalData, final Candle latestCandle) {
        final String symbol = latestCandle.getSymbol();
        final String timeframe = latestCandle.getTimeframe();

        // ── Guard: timeframe (FIX — was unrestricted; spec requires 5-minute) ──
        if (!REQUIRED_TIMEFRAME.equals(timeframe)) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    "SigmaStream only runs on the 5-minute timeframe (current: " + timeframe + ")");
        }

        final LocalTime nyTime = latestCandle.getTimestamp().atZone(ZONE_NY).toLocalTime();

        // ── Guard: still building the range (FIX — now NY time, not UTC) ───────
        if (nyTime.isBefore(RANGE_END)) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    "Before 08:30 UTC-4/NY — still building range window. Current NY time=" + nyTime);
        }

        // ── Guard: entry window has closed (FIX — was missing entirely) ────────
        if (!nyTime.isBefore(ENTRY_WINDOW_CLOSE)) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    "After 12:00 PM UTC-4/NY — SigmaStream's entry window has closed for today. " +
                    "Current NY time=" + nyTime);
        }

        // ── Step 1: Build 00:00–08:30 NY range ──────────────────────────────
        final List<Candle> rangeCandles = getRangeCandles(historicalData, latestCandle);

        if (rangeCandles.size() < 3) {
            return StrategySignal.insufficientData(NAME, symbol, timeframe,
                    "Insufficient candles in 00:00–08:30 window (" + rangeCandles.size() + " found, need ≥ 3)");
        }

        final Optional<BigDecimal> rangeHighOpt = CandleUtils.highestHigh(rangeCandles);
        final Optional<BigDecimal> rangeLowOpt  = CandleUtils.lowestLow(rangeCandles);

        if (rangeHighOpt.isEmpty() || rangeLowOpt.isEmpty()) {
            return StrategySignal.insufficientData(NAME, symbol, timeframe,
                    "Cannot compute range high/low from 00:00–08:30 candles");
        }

        final BigDecimal rangeHigh = rangeHighOpt.get().setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
        final BigDecimal rangeLow  = rangeLowOpt.get().setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
        final BigDecimal rangeSize = rangeHigh.subtract(rangeLow);

        if (rangeSize.compareTo(BigDecimal.ZERO) == 0) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    "Range is zero — flat consolidation during pre-session window");
        }

        // Fix 1: Compute normalized and bounded average range
        final BigDecimal avgRange = calculateBoundedAverageRange(historicalData);

        log.debug("[{}] {} — RangeHigh={} RangeLow={} RangeSize={} AvgRange={}",
                NAME, symbol, rangeHigh, rangeLow, rangeSize, avgRange);

        final BigDecimal close = latestCandle.getClosePrice();
        final BigDecimal low   = latestCandle.getLowPrice();
        final BigDecimal high  = latestCandle.getHighPrice();

        // ═════════════════════════════════════════════════════════════════════
        //  CASE 1: Bullish Breakout
        // ═════════════════════════════════════════════════════════════════════
        if (close.compareTo(rangeHigh) > 0) {
            final boolean pullback      = low.compareTo(rangeHigh) <= 0;
            final boolean strongBullish = CandleUtils.isStrongBullish(latestCandle);

            if (pullback || strongBullish) {
                final BigDecimal entry      = rangeHigh;
                final BigDecimal stopLoss   = rangeHigh.subtract(avgRange).setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
                final BigDecimal risk       = entry.subtract(stopLoss).abs();
                final BigDecimal takeProfit = entry.add(risk.multiply(BigDecimal.valueOf(RR_MULTIPLIER)))
                                                   .setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);

                final String confirmation = pullback ? "pullback into breakout level" : "strong bullish candle";
                
                // Fix 2: Force Locale.ROOT to guarantee decimal safety across cloud nodes
                final String reason = String.format(Locale.ROOT,
                        "Bullish breakout above range high (%.4f). Confirmation: %s. Risk=%.4f TP=%.4f (2:1 RR)",
                        rangeHigh, confirmation, risk, takeProfit);

                log.info("[{}] ✅ BUY signal — {} | Entry={} SL={} TP={} | {}",
                        NAME, symbol, entry, stopLoss, takeProfit, reason);

                return StrategySignal.builder()
                        .signalType(SignalType.BUY)
                        .strategyName(NAME)
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .entryPrice(entry)
                        .stopLoss(stopLoss)
                        .takeProfit(takeProfit)
                        .reason(reason)
                        .build();
            }

            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT, "Bullish breakout above %.4f — awaiting pullback or strong candle. Current low=%.4f", rangeHigh, low));
        }

        // ═════════════════════════════════════════════════════════════════════
        //  CASE 2: Bearish Breakdown
        // ═════════════════════════════════════════════════════════════════════
        if (close.compareTo(rangeLow) < 0) {
            final boolean pullback       = high.compareTo(rangeLow) >= 0;
            final boolean strongBearish  = CandleUtils.isStrongBearish(latestCandle);

            if (pullback || strongBearish) {
                final BigDecimal entry      = rangeLow;
                final BigDecimal stopLoss   = rangeLow.add(avgRange).setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
                final BigDecimal risk       = stopLoss.subtract(entry).abs();
                final BigDecimal takeProfit = entry.subtract(risk.multiply(BigDecimal.valueOf(RR_MULTIPLIER)))
                                                   .setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);

                final String confirmation = pullback ? "pullback into breakdown level" : "strong bearish candle";
                final String reason = String.format(Locale.ROOT,
                        "Bearish breakdown below range low (%.4f). Confirmation: %s. Risk=%.4f TP=%.4f (2:1 RR)",
                        rangeLow, confirmation, risk, takeProfit);

                log.info("[{}] ✅ SELL signal — {} | Entry={} SL={} TP={} | {}",
                        NAME, symbol, entry, stopLoss, takeProfit, reason);

                return StrategySignal.builder()
                        .signalType(SignalType.SELL)
                        .strategyName(NAME)
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .entryPrice(entry)
                        .stopLoss(stopLoss)
                        .takeProfit(takeProfit)
                        .reason(reason)
                        .build();
            }

            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT, "Bearish breakdown below %.4f — awaiting pullback or strong candle. Current high=%.4f", rangeLow, high));
        }

        return StrategySignal.noTrade(NAME, symbol, timeframe,
                String.format(Locale.ROOT, "Price inside pre-session range. High=%.4f Low=%.4f Close=%.4f", rangeHigh, rangeLow, close));
    }

    // ─── Bounded Math Helper ─────────────────────────────────────────────────

    private BigDecimal calculateBoundedAverageRange(final List<Candle> history) {
        if (history == null || history.isEmpty()) {
            return MINIMUM_RISK_BUFFER;
        }
        
        final BigDecimal total = history.stream()
                .map(CandleUtils::range)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
                
        final BigDecimal average = total.divide(BigDecimal.valueOf(history.size()), DISPLAY_SCALE, RoundingMode.HALF_UP);
        
        // Fix 3: Fallback check ensures avgRange can never drop to 0
        return average.compareTo(BigDecimal.ZERO) == 0 ? MINIMUM_RISK_BUFFER : average;
    }

    // ─── NY-timezone range window (FIX — replaces UTC-based CandleUtils helper) ─

    /**
     * Returns candles within 00:00–08:30 NY time, on the same NY calendar date
     * as {@code reference}. Mirrors CandleUtils.getSigmaRangeCandles() but in
     * America/New_York instead of UTC, per spec.
     */
    private List<Candle> getRangeCandles(List<Candle> history, Candle reference) {
        if (history == null || reference == null || reference.getTimestamp() == null) {
            return List.of();
        }
        final LocalDate refDate = reference.getTimestamp().atZone(ZONE_NY).toLocalDate();
        return history.stream()
                .filter(c -> c.getTimestamp() != null)
                .filter(c -> {
                    LocalDate d = c.getTimestamp().atZone(ZONE_NY).toLocalDate();
                    LocalTime t = c.getTimestamp().atZone(ZONE_NY).toLocalTime();
                    return d.equals(refDate) && !t.isBefore(RANGE_START) && t.isBefore(RANGE_END);
                })
                .collect(Collectors.toList());
    }
}