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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Strategy 1 — Fusion Flow
 *
 * ════════════════════════════════════════════════════════════════════════
 *  UPDATE (this pass): live evaluation window confirmed
 * ════════════════════════════════════════════════════════════════════════
 *  An earlier pass removed the "current candle must be in London session"
 *  gate, reasoning that "Consider only on London Session" referred only to
 *  which candles define the *reference day's* high/low. The user's strategy
 *  execution timetable clarifies that this was wrong: Fusion Flow's
 *  "wait for FVG / execute" phase is genuinely scoped to 08:00–16:00 UTC
 *  (04:00 AM–12:00 PM UTC-4 / 1:30 PM–9:30 PM IST) on its active days. That
 *  gate is restored below — but layered correctly this time, as one of
 *  three conditions (day-of-week AND timeframe AND session window) that
 *  must all hold, rather than the sole gate that caused the original
 *  "Outside London session — strategy inactive" bug on every day/timeframe.
 *
 * ════════════════════════════════════════════════════════════════════════
 *  SPEC SUMMARY
 * ════════════════════════════════════════════════════════════════════════
 *  - Bank-trading-concepts strategy. Applies ONLY Monday, Tuesday, Wednesday.
 *  - Applies ONLY on the 15-minute timeframe.
 *  - Live evaluation window: 08:00–16:00 UTC (London session).
 *  - Reference day:
 *        Monday    → previous Friday    (today − 3)
 *        Tuesday   → previous Monday    (today − 1)
 *        Wednesday → previous Tuesday   (today − 1)
 *  - Reference levels = High and Low of the REFERENCE day's London session
 *    only (08:00–16:00 UTC) — "uncheck all other timezone sessions".
 *  - BUY:  Entry = reference London Low,  Target = reference London High
 *  - SELL: Entry = reference London High, Target = reference London Low
 *  - Trigger: a 15-minute bullish imbalance (Fair Value Gap) forming at the
 *    reference Low zone → BUY. A bearish imbalance at the reference High
 *    zone → SELL. ("Wait for 15 minutes Bullish/Bearish Imbalance.")
 *
 *  An imbalance (FVG) is the standard 3-candle gap: the high of the candle
 *  two bars back sits below the low of the current candle (bullish), or the
 *  low of the candle two bars back sits above the high of the current
 *  candle (bearish). See CandleUtils.isBullishImbalance / isBearishImbalance.
 *
 *  Stop-loss is not given an explicit value in the spec (only Entry and
 *  Target are named) — a small buffer beyond the Entry level, on the side
 *  away from the Target, has been added for risk management, sized as a
 *  fraction of the reference range (the same buffer-ratio pattern already
 *  used elsewhere in this codebase).
 */
@Slf4j
@Component
public class FusionFlowStrategy implements TradingStrategy {

    private static final String NAME = "Fusion Flow";

    /** Fusion Flow is defined only on the 15-minute chart per spec. */
    private static final String REQUIRED_TIMEFRAME = "15min";

    private static final int DISPLAY_SCALE = 4;

    /** Stop-loss buffer as a fraction of the reference London range. */
    private static final BigDecimal SL_BUFFER_RATIO = new BigDecimal("0.10");

    /** How close the imbalance candle must sit to the Entry level to count as "at the zone". */
    private static final BigDecimal ZONE_TOLERANCE_RATIO = new BigDecimal("0.10");

    @Override public String  getName()               { return NAME; }
    @Override public boolean supports(String symbol)  { return true; }
    @Override public int     getMinimumBarsRequired()  { return 100; }

    @Override
    public String getDescription() {
        return "Bank trading concept. Monday–Wednesday only, 15-minute timeframe only. " +
               "Uses the reference day's London session High/Low " +
               "(Mon→Fri, Tue→Mon, Wed→Tue) as Entry/Target, confirmed by a " +
               "15-minute bullish/bearish imbalance (FVG) forming at the zone.";
    }

    @Override
    public StrategySignal evaluate(final List<Candle> historicalData, final Candle latestCandle) {
        final String symbol    = latestCandle.getSymbol();
        final String timeframe = latestCandle.getTimeframe();

        // ── Guard 1: timeframe ─────────────────────────────────────────────────
        if (!REQUIRED_TIMEFRAME.equals(timeframe)) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    "Fusion Flow only runs on the 15-minute timeframe (current: " + timeframe + ")");
        }

        // ── Guard 2: day of week + reference day resolution ─────────────────────
        final DayOfWeek dow   = latestCandle.getTimestamp().atZone(ZoneOffset.UTC).getDayOfWeek();
        final LocalDate today = latestCandle.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
        final LocalDate referenceDate = resolveReferenceDate(dow, today);

        if (referenceDate == null) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    "Fusion Flow only trades Monday–Wednesday (today is " + dow + ")");
        }

        // ── Guard 3: live evaluation window (08:00–16:00 UTC / London session) ─
        // Per the strategy execution timetable: Fusion Flow's "wait for FVG /
        // execute" phase only runs 08:00–16:00 UTC (04:00 AM–12:00 PM UTC-4,
        // 1:30 PM–9:30 PM IST) on its active days. This is layered ON TOP of
        // the day-of-week and timeframe guards above — by itself this check
        // was the original bug ("Outside London session — strategy inactive"
        // firing on every day/timeframe); here it's one of three conditions
        // that must ALL hold, which is what the schedule actually specifies.
        if (!CandleUtils.isLondonSession(latestCandle)) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    "Outside Fusion Flow's active window (08:00–16:00 UTC / London session)");
        }

        // ── Guard 4: minimum context for the 3-candle imbalance check ──────────
        if (historicalData == null || historicalData.size() < 2) {
            return StrategySignal.insufficientData(NAME, symbol, timeframe,
                    "Insufficient historical data context (need ≥ 2 bars)");
        }

        // ── Step 1: Reference day's London session High/Low ────────────────────
        final List<Candle> refLondon =
                CandleUtils.getLondonSessionCandlesForDate(historicalData, referenceDate);

        if (refLondon.size() < 3) {
            return StrategySignal.insufficientData(NAME, symbol, timeframe,
                    String.format(Locale.ROOT,
                            "Reference day %s (%s) London session has only %d candle(s) in history " +
                            "(need ≥ 3) — needs more 15-minute lookback in the backend",
                            referenceDate, refDayLabel(dow), refLondon.size()));
        }

        final Optional<BigDecimal> refHighOpt = CandleUtils.highestHigh(refLondon);
        final Optional<BigDecimal> refLowOpt  = CandleUtils.lowestLow(refLondon);

        if (refHighOpt.isEmpty() || refLowOpt.isEmpty()) {
            return StrategySignal.insufficientData(NAME, symbol, timeframe,
                    "Cannot compute reference day London session high/low");
        }

        final BigDecimal refHigh = refHighOpt.get().setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
        final BigDecimal refLow  = refLowOpt.get().setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
        final BigDecimal range   = refHigh.subtract(refLow);

        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    "Reference day London range is zero — flat market, skipping");
        }

        final BigDecimal tolerance = range.multiply(ZONE_TOLERANCE_RATIO)
                .setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
        final BigDecimal slBuffer = range.multiply(SL_BUFFER_RATIO)
                .setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);

        log.debug("[{}] {} | Ref={} ({}) RefHigh={} RefLow={} Range={} Tol={}",
                NAME, symbol, referenceDate, refDayLabel(dow), refHigh, refLow, range, tolerance);

        // ── Step 2: 3-candle imbalance ending at the latest candle ─────────────
        // first = 2 bars back, middle = previous bar (the displacement candle),
        // latestCandle = the 3rd bar of the pattern.
        final Candle first  = historicalData.get(historicalData.size() - 2);
        final Candle middle = historicalData.get(historicalData.size() - 1);

        final boolean bullishImbalance = CandleUtils.isBullishImbalance(first, latestCandle);
        final boolean bearishImbalance = CandleUtils.isBearishImbalance(first, latestCandle);

        // ── CASE 1: BUY — bullish imbalance at the reference Low zone ──────────
        if (bullishImbalance) {
            final BigDecimal lowerBand = refLow.subtract(tolerance);
            final BigDecimal upperBand = refLow.add(tolerance);
            final boolean atZone = middle.getLowPrice().compareTo(upperBand) <= 0
                                && middle.getHighPrice().compareTo(lowerBand) >= 0;

            if (atZone) {
                final BigDecimal entry      = refLow;
                final BigDecimal stopLoss   = entry.subtract(slBuffer).setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
                final BigDecimal takeProfit = refHigh;

                final String reason = String.format(Locale.ROOT,
                        "BUY: Bullish imbalance at reference %s London Low (%.4f). " +
                        "Entry=%.4f SL=%.4f Target=%.4f (reference High)",
                        refDayLabel(dow), refLow, entry, stopLoss, takeProfit);

                final StrategySignal signal = StrategySignal.builder()
                        .signalType(SignalType.BUY)
                        .strategyName(NAME)
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .entryPrice(entry)
                        .stopLoss(stopLoss)
                        .takeProfit(takeProfit)
                        .reason(reason)
                        .londonRangeSize(range)
                        .build();

                log.info("[{}] ✅ {}", NAME, signal.toLogSummary());
                return signal;
            }

            return StrategySignal.noTrade(NAME, symbol, timeframe, String.format(Locale.ROOT,
                    "Bullish imbalance detected but away from reference %s Low zone (%.4f ± %.4f)",
                    refDayLabel(dow), refLow, tolerance));
        }

        // ── CASE 2: SELL — bearish imbalance at the reference High zone ────────
        if (bearishImbalance) {
            final BigDecimal lowerBand = refHigh.subtract(tolerance);
            final BigDecimal upperBand = refHigh.add(tolerance);
            final boolean atZone = middle.getLowPrice().compareTo(upperBand) <= 0
                                && middle.getHighPrice().compareTo(lowerBand) >= 0;

            if (atZone) {
                final BigDecimal entry      = refHigh;
                final BigDecimal stopLoss   = entry.add(slBuffer).setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
                final BigDecimal takeProfit = refLow;

                final String reason = String.format(Locale.ROOT,
                        "SELL: Bearish imbalance at reference %s London High (%.4f). " +
                        "Entry=%.4f SL=%.4f Target=%.4f (reference Low)",
                        refDayLabel(dow), refHigh, entry, stopLoss, takeProfit);

                final StrategySignal signal = StrategySignal.builder()
                        .signalType(SignalType.SELL)
                        .strategyName(NAME)
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .entryPrice(entry)
                        .stopLoss(stopLoss)
                        .takeProfit(takeProfit)
                        .reason(reason)
                        .londonRangeSize(range)
                        .build();

                log.info("[{}] ✅ {}", NAME, signal.toLogSummary());
                return signal;
            }

            return StrategySignal.noTrade(NAME, symbol, timeframe, String.format(Locale.ROOT,
                    "Bearish imbalance detected but away from reference %s High zone (%.4f ± %.4f)",
                    refDayLabel(dow), refHigh, tolerance));
        }

        return StrategySignal.noTrade(NAME, symbol, timeframe, String.format(Locale.ROOT,
                "No imbalance yet. Watching reference %s zone — Low=%.4f High=%.4f",
                refDayLabel(dow), refLow, refHigh));
    }

    // ─── Day-of-week reference resolution (per spec) ───────────────────────────

    /**
     * Maps today's day-of-week to the spec's reference day:
     *   Monday    → previous Friday   (today − 3)
     *   Tuesday   → previous Monday   (today − 1)
     *   Wednesday → previous Tuesday  (today − 1)
     * Any other day → null (strategy inactive).
     */
    private LocalDate resolveReferenceDate(DayOfWeek dow, LocalDate today) {
        switch (dow) {
            case MONDAY:    return today.minusDays(3);
            case TUESDAY:   return today.minusDays(1);
            case WEDNESDAY: return today.minusDays(1);
            default:        return null;
        }
    }

    private static String refDayLabel(DayOfWeek dow) {
        switch (dow) {
            case MONDAY:    return "Friday";
            case TUESDAY:   return "Monday";
            case WEDNESDAY: return "Tuesday";
            default:        return "N/A";
        }
    }
}