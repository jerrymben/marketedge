package com.marketedge.strategy;

import com.marketedge.model.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shared utility methods used across all three {@link TradingStrategy} implementations.
 * Fully optimized to handle java.time.Instant with strict UTC zone mappings.
 */
public final class CandleUtils {

    private CandleUtils() { /* utility class — no instances */ }

    // ─── Session Window Definitions (UTC) ─────────────────────────────────────

    public static final LocalTime LONDON_OPEN      = LocalTime.of(8, 0);
    public static final LocalTime LONDON_CLOSE     = LocalTime.of(16, 0);
    public static final LocalTime NY_OPEN          = LocalTime.of(13, 0);
    public static final LocalTime NY_CLOSE         = LocalTime.of(21, 0);
    public static final LocalTime SIGMA_RANGE_END  = LocalTime.of(8, 30);
    private static final int CALCULATE_SCALE       = 4;

    // ═══════════════════════════════════════════════════════════════════════════
    //  Session filters
    // ═══════════════════════════════════════════════════════════════════════════

    /** Returns true if the candle's timestamp falls within the London session. */
    public static boolean isLondonSession(Candle candle) {
        if (candle == null || candle.getTimestamp() == null) return false;
        LocalTime t = candle.getTimestamp().atZone(ZoneOffset.UTC).toLocalTime();
        return !t.isBefore(LONDON_OPEN) && t.isBefore(LONDON_CLOSE);
    }

    /** Returns true if the candle is after the SigmaStream range window (after 08:30). */
    public static boolean isAfterSigmaRangeEnd(Candle candle) {
        if (candle == null || candle.getTimestamp() == null) return false;
        LocalTime t = candle.getTimestamp().atZone(ZoneOffset.UTC).toLocalTime();
        return !t.isBefore(SIGMA_RANGE_END);
    }

    /** Filters candles to those that belong to the pre-session sequence on the same day. */
    public static List<Candle> getSigmaRangeCandles(List<Candle> history, Candle reference) {
        if (history == null || reference == null || reference.getTimestamp() == null) {
            return List.of();
        }
        LocalDate referenceDate = reference.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
        return history.stream()
                .filter(c -> c.getTimestamp() != null)
                .filter(c -> {
                    LocalDate candleDate = c.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
                    LocalTime candleTime = c.getTimestamp().atZone(ZoneOffset.UTC).toLocalTime();
                    return candleDate.equals(referenceDate) && !candleTime.isAfter(SIGMA_RANGE_END);
                })
                .collect(Collectors.toList());
    }

    /** Returns candles within the London session on the same date as {@code reference}. */
    public static List<Candle> getLondonSessionCandles(List<Candle> history, Candle reference) {
        if (history == null || reference == null || reference.getTimestamp() == null) {
            return List.of();
        }
        LocalDate date = reference.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
        return history.stream()
                .filter(c -> c.getTimestamp() != null && c.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().equals(date))
                .filter(CandleUtils::isLondonSession)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Previous-day High / Low (PDH / PDL)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Computes the Previous Day High (PDH) from historical candles. */
    public static Optional<BigDecimal> getPreviousDayHigh(List<Candle> history, Candle reference) {
        if (history == null || reference == null || reference.getTimestamp() == null) return Optional.empty();
        LocalDate prevDay = reference.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        return history.stream()
                .filter(c -> c.getTimestamp() != null && c.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().equals(prevDay))
                .map(Candle::getHighPrice)
                .max(Comparator.naturalOrder());
    }

    /** Computes the Previous Day Low (PDL) from historical candles. */
    public static Optional<BigDecimal> getPreviousDayLow(List<Candle> history, Candle reference) {
        if (history == null || reference == null || reference.getTimestamp() == null) return Optional.empty();
        LocalDate prevDay = reference.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        return history.stream()
                .filter(c -> c.getTimestamp() != null && c.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().equals(prevDay))
                .map(Candle::getLowPrice)
                .min(Comparator.naturalOrder());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Candle body / strength helpers
    // ═══════════════════════════════════════════════════════════════════════════

    public static BigDecimal bodySize(Candle c) {
        return c.getClosePrice().subtract(c.getOpenPrice()).abs();
    }

    public static BigDecimal range(Candle c) {
        return c.getHighPrice().subtract(c.getLowPrice());
    }

    public static boolean isBullish(Candle c) {
        return c.getClosePrice().compareTo(c.getOpenPrice()) > 0;
    }

    public static boolean isBearish(Candle c) {
        return c.getClosePrice().compareTo(c.getOpenPrice()) < 0;
    }

    public static boolean isStrong(Candle c, double bodyRatio) {
        BigDecimal r = range(c);
        if (r.compareTo(BigDecimal.ZERO) == 0) return false;
        BigDecimal ratio = bodySize(c).divide(r, CALCULATE_SCALE, RoundingMode.HALF_UP);
        return ratio.compareTo(BigDecimal.valueOf(bodyRatio)) >= 0;
    }

    public static boolean isStrongBullish(Candle c) {
        return isBullish(c) && isStrong(c, 0.6);
    }

    public static boolean isStrongBearish(Candle c) {
        return isBearish(c) && isStrong(c, 0.6);
    }

    public static boolean closedAbovePreviousHigh(Candle current, Candle previous) {
        return current.getClosePrice().compareTo(previous.getHighPrice()) > 0;
    }

    public static boolean closedBelowPreviousLow(Candle current, Candle previous) {
        return current.getClosePrice().compareTo(previous.getLowPrice()) < 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Range / Fibonacci helpers
    // ═══════════════════════════════════════════════════════════════════════════

    public static Optional<BigDecimal> highestHigh(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return Optional.empty();
        return candles.stream().map(Candle::getHighPrice).max(Comparator.naturalOrder());
    }

    public static Optional<BigDecimal> lowestLow(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return Optional.empty();
        return candles.stream().map(Candle::getLowPrice).min(Comparator.naturalOrder());
    }

    public static Optional<Candle> previousCandle(List<Candle> history) {
        if (history == null || history.size() < 2) return Optional.empty();
        return Optional.of(history.get(history.size() - 2));
    }

    public static BigDecimal fibLevel(BigDecimal low, BigDecimal high, BigDecimal ratio) {
        BigDecimal rangeFib = high.subtract(low);
        return high.subtract(rangeFib.multiply(ratio)).setScale(CALCULATE_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal fibLevel(BigDecimal low, BigDecimal high, double ratio) {
        return fibLevel(low, high, BigDecimal.valueOf(ratio));
    }

    public static BigDecimal fibExtension(BigDecimal low, BigDecimal high, BigDecimal ratio) {
        BigDecimal rangeFib = high.subtract(low);
        return low.add(rangeFib.multiply(ratio)).setScale(CALCULATE_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal fibExtension(BigDecimal low, BigDecimal high, double ratio) {
        return fibExtension(low, high, BigDecimal.valueOf(ratio));
    }

    public static BigDecimal addBuffer(BigDecimal price, BigDecimal buffer) {
        return price.add(buffer).setScale(CALCULATE_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal subtractBuffer(BigDecimal price, BigDecimal buffer) {
        return price.subtract(buffer).setScale(CALCULATE_SCALE, RoundingMode.HALF_UP);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  NEW — Session candles on an explicit date (vs. relative-to-reference-candle)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns candles within the London session (08:00–16:00 UTC) on a specific
     * calendar date, rather than deriving the date from a reference candle.
     *
     * <p>Needed by strategies whose reference day is NOT the same day as the
     * latest candle — e.g. Fusion Flow's Monday→Friday / Tuesday→Monday /
     * Wednesday→Tuesday rule. {@link #getLondonSessionCandles} only supports
     * "the same day as {@code reference}", which can't express that lookup.
     */
    public static List<Candle> getLondonSessionCandlesForDate(List<Candle> history, LocalDate date) {
        if (history == null || date == null) return List.of();
        return history.stream()
                .filter(c -> c.getTimestamp() != null
                          && c.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().equals(date))
                .filter(CandleUtils::isLondonSession)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  NEW — Fair Value Gap / "Imbalance" detection (3-candle pattern)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Bullish imbalance (Fair Value Gap): the high of the first candle in a
     * 3-candle window sits below the low of the third candle — the middle
     * candle's displacement left an unfilled gap between them.
     *
     * <p>Typical usage: {@code isBullishImbalance(history.get(history.size()-2), latestCandle)}
     * — i.e. two candles back vs. the live candle, with the previous candle
     * (history.get(size-1)) as the implicit middle/displacement bar.
     */
    public static boolean isBullishImbalance(Candle first, Candle third) {
        if (first == null || third == null) return false;
        if (first.getHighPrice() == null || third.getLowPrice() == null) return false;
        return first.getHighPrice().compareTo(third.getLowPrice()) < 0;
    }

    /**
     * Bearish imbalance (Fair Value Gap): the low of the first candle in a
     * 3-candle window sits above the high of the third candle.
     */
    public static boolean isBearishImbalance(Candle first, Candle third) {
        if (first == null || third == null) return false;
        if (first.getLowPrice() == null || third.getHighPrice() == null) return false;
        return first.getLowPrice().compareTo(third.getHighPrice()) > 0;
    }
}