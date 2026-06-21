package com.marketedge.strategy.impl;

import com.marketedge.model.Candle;
import com.marketedge.strategy.AlphaMatrixContext;
import com.marketedge.strategy.CandleUtils;
import com.marketedge.strategy.SignalStatus;
import com.marketedge.strategy.SignalType;
import com.marketedge.strategy.StrategySignal;
import com.marketedge.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Strategy 2 — Alpha Matrix
 *
 * ════════════════════════════════════════════════════════════════════════
 *  THIS FILE REPLACES THE OLD "Alpha Matrix" (V1) IMPLEMENTATION
 * ════════════════════════════════════════════════════════════════════════
 *  V1 used the CURRENT day's London session range as both breakout and
 *  Fibonacci anchor, and required today's London session to already have
 *  produced ≥ 5 candles before it could evaluate at all. That does not
 *  match the spec (which references the PREVIOUS day's fixed levels and a
 *  06:05–23:45 UTC-4 entry window), and is the reason this strategy used to
 *  show "INSUFFICIENT_DATA — Insufficient London session candles (0 found)"
 *  any time it was checked before today's London session had started.
 *
 *  The logic below is what used to live in AlphaMatrixV2Strategy — renamed
 *  to "Alpha Matrix" per the spec, with two changes:
 *    1. persist(signal) removed — TradeRecord persistence is now centralised
 *       in StrategyEngineService.onNewCandle() for ALL strategies, so every
 *       actionable signal (not just this one) gets lifecycle-tracked.
 *    2. buildPreviousDayLevels() now searches backward for the most recent
 *       day that actually has candles, instead of assuming exactly
 *       "today − 1". The old version broke every Monday: today−1 is Sunday,
 *       and FX markets have no Sunday data, so PDH/PDL/PDLH/PDLL always came
 *       back null and the strategy returned INSUFFICIENT_DATA all day.
 *
 *  IMPORTANT: delete the old standalone "AlphaMatrixV2Strategy" file/class
 *  after dropping this in — otherwise you'll have two competing strategy
 *  beans both implementing the same logic under different names.
 *
 * ────────────────────────────────────────────────────────────────────────
 *  Reference Day: most recent previous trading day with data
 *  Reference Session (UTC-4): 03:00–23:45
 *    PDH  = Previous Day High  (highest high of full reference session)
 *    PDL  = Previous Day Low   (lowest  low  of full reference session)
 *    PDLH = Previous Day London High (08:00–16:00 UTC of the reference day)
 *    PDLL = Previous Day London Low  (08:00–16:00 UTC of the reference day)
 *
 *  All four levels are FIXED for the entire current trading day.
 *  Current Day Entry Window (UTC-4): 06:05–23:45
 *
 * ────────────────────────────────────────────────────────────────────────
 *  BUY ALGORITHM
 * ────────────────────────────────────────────────────────────────────────
 *  1. Breakout: close > PDLH → bullish breakout; track breakoutHigh.
 *  2. No-Touch: today's low must remain > PDLL, else INVALIDATE.
 *  3. Fib: fib786 = breakoutHigh − ((breakoutHigh − PDLL) × 0.786)
 *  4. Retracement: signal when price touches fib786 ± tolerance.
 *  5. Entry = fib786, SL = PDLL, TP = PDH. Max 1 BUY per day.
 *
 * ────────────────────────────────────────────────────────────────────────
 *  SELL ALGORITHM
 * ────────────────────────────────────────────────────────────────────────
 *  1. Breakdown: close < PDLL → bearish breakdown; track breakdownLow.
 *  2. No-Touch: today's high must remain < PDH, else INVALIDATE.
 *  3. Fib: fib786 = breakdownLow + ((PDH − breakdownLow) × 0.786)
 *  4. Retracement: signal when price touches fib786 ± tolerance.
 *  5. Entry = fib786, SL = PDH, TP = PDLL. Max 1 SELL per day.
 */
@Slf4j
@Component
public class AlphaMatrixStrategy implements TradingStrategy {

    // ── Strategy identity ─────────────────────────────────────────────────────

    private static final String NAME = "Alpha Matrix";

    // ── Timezone (UTC-4 = US/Eastern EDT used by specification) ──────────────

    private static final ZoneId ZONE_UTC4 = ZoneId.of("America/New_York");

    // ── Reference session window in UTC-4 ────────────────────────────────────

    private static final LocalTime REF_SESSION_OPEN  = LocalTime.of(3, 0);
    private static final LocalTime REF_SESSION_CLOSE = LocalTime.of(23, 45);

    // ── Previous day London session in UTC ────────────────────────────────────

    private static final LocalTime LONDON_OPEN_UTC  = LocalTime.of(8,  0);
    private static final LocalTime LONDON_CLOSE_UTC = LocalTime.of(16, 0);

    // ── Entry window in UTC-4 ─────────────────────────────────────────────────

    private static final LocalTime ENTRY_WINDOW_OPEN  = LocalTime.of(6,  5);
    private static final LocalTime ENTRY_WINDOW_CLOSE = LocalTime.of(23, 45);

    // ── Fibonacci ratio ───────────────────────────────────────────────────────

    private static final BigDecimal FIB_786_RATIO = new BigDecimal("0.786");

    // ── Price display scale ───────────────────────────────────────────────────

    private static final int SCALE = 4;

    // ── Tolerance constants ───────────────────────────────────────────────────

    private static final BigDecimal MAX_TOLERANCE_RANGE_RATIO = new BigDecimal("0.1500");
    private static final BigDecimal ATR_TOLERANCE_MULTIPLIER  = new BigDecimal("0.50");
    private static final int ATR_PERIOD = 14;

    // ── Breakout strength gate ────────────────────────────────────────────────

    private static final double MIN_BREAKOUT_BODY_RATIO = 0.40;

    // ── Confidence score weights (must sum to 100) ────────────────────────────

    private static final int CONF_BREAKOUT_MAX   = 30;
    private static final int CONF_RETEST_MAX     = 30;
    private static final int CONF_VOLATILITY_MAX = 20;
    private static final int CONF_MULTI_MAX      = 20;

    /**
     * FIX: how many calendar days backward buildPreviousDayLevels() will search
     * for a day that actually has candles, before giving up. 5 comfortably
     * covers a weekend (Mon needs Fri = 3 days back) plus a public holiday.
     */
    private static final int MAX_LOOKBACK_DAYS_FOR_PREV_SESSION = 5;

    // ── Daily trade locks (thread-safe) ──────────────────────────────────────

    private final Map<String, Boolean> buyTriggered  = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sellTriggered = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════════════
    //  TradingStrategy contract
    // ═══════════════════════════════════════════════════════════════════════════

    @Override public String  getName()               { return NAME; }
    @Override public boolean supports(String symbol)  { return true; }
    @Override public int     getMinimumBarsRequired() { return 60; }

    @Override
    public String getDescription() {
        return "Previous-day level breakout with Fibonacci 78.6% retracement. " +
               "Uses PDH/PDL/PDLH/PDLL as fixed daily reference levels. " +
               "BUY: break above PDLH → fib786 retest → TP at PDH. " +
               "SELL: break below PDLL → fib786 retest → TP at PDLL. " +
               "Max 1 signal per direction per day. Entry window 06:05–23:45 UTC-4.";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  evaluate() — main entry point, called per tick by StrategyEngineService
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public StrategySignal evaluate(final List<Candle> historicalData,
                                   final Candle latestCandle) {

        final String symbol    = latestCandle.getSymbol();
        final String timeframe = latestCandle.getTimeframe();

        if (historicalData == null || historicalData.size() < 2) {
            return StrategySignal.insufficientData(NAME, symbol, timeframe,
                    "Insufficient historical data (need ≥ 2 bars)");
        }

        if (!isWithinEntryWindow(latestCandle)) {
            log.debug("[{}] {} — Outside entry window (06:05–23:45 UTC-4). Candle ts={}",
                    NAME, symbol, latestCandle.getTimestamp());
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    "Outside entry window (06:05–23:45 UTC-4)");
        }

        AlphaMatrixContext ctx = buildPreviousDayLevels(historicalData, latestCandle);

        if (ctx == null || !ctx.isBuyContextValid()) {
            return StrategySignal.insufficientData(NAME, symbol, timeframe,
                    "Cannot compute previous-day reference levels (PDH/PDL/PDLH/PDLL). " +
                    "Ensure history spans at least one full previous trading day with candles.");
        }

        log.debug("[{}] {} | Context — PDH={} PDL={} PDLH={} PDLL={} Date={}",
                NAME, symbol,
                ctx.getPdh(), ctx.getPdl(), ctx.getPdlh(), ctx.getPdll(),
                ctx.getTradingDate());

        final BigDecimal atr       = computeAtr(historicalData);
        final Candle     prevCandle = historicalData.get(historicalData.size() - 1);

        StrategySignal buySignal = evaluateBuy(ctx, historicalData, latestCandle,
                                               prevCandle, atr, symbol, timeframe);
        if (buySignal != null && buySignal.isActionable()) return buySignal;

        StrategySignal sellSignal = evaluateSell(ctx, historicalData, latestCandle,
                                                  prevCandle, atr, symbol, timeframe);
        if (sellSignal != null && sellSignal.isActionable()) return sellSignal;

        if (buySignal != null) return buySignal;
        if (sellSignal != null) return sellSignal;

        return StrategySignal.noTrade(NAME, symbol, timeframe,
                String.format(Locale.ROOT,
                        "No setup — Price=%.4f PDH=%.4f PDL=%.4f PDLH=%.4f PDLL=%.4f",
                        latestCandle.getClosePrice(),
                        ctx.getPdh(), ctx.getPdl(), ctx.getPdlh(), ctx.getPdll()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  BUY path
    // ═══════════════════════════════════════════════════════════════════════════

    private StrategySignal evaluateBuy(AlphaMatrixContext ctx,
                                        List<Candle> history,
                                        Candle latest,
                                        Candle prev,
                                        BigDecimal atr,
                                        String symbol,
                                        String timeframe) {

        final BigDecimal pdh  = ctx.getPdh();
        final BigDecimal pdll = ctx.getPdll();
        final BigDecimal pdlh = ctx.getPdlh();
        final LocalDate  today = ctx.getTradingDate();

        if (buyAlreadyTriggered(symbol, today)) {
            log.debug("[{}] {} — BUY daily lock active for {}", NAME, symbol, today);
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    "BUY daily lock: already fired one BUY today (" + today + ")");
        }

        boolean bullishBreakout = latest.getClosePrice().compareTo(pdlh) > 0
                               || prev.getClosePrice().compareTo(pdlh) > 0;

        if (!bullishBreakout) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT,
                            "BUY: No breakout above PDLH=%.4f (Close=%.4f)",
                            pdlh, latest.getClosePrice()));
        }

        Candle breakoutBar   = selectBreakoutCandle(prev, latest, pdlh, true);
        double bStrength     = breakoutStrength(breakoutBar);
        boolean multiCandle  = prev.getClosePrice().compareTo(pdlh) > 0
                            && latest.getClosePrice().compareTo(pdlh) > 0;

        if (bStrength < MIN_BREAKOUT_BODY_RATIO) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT,
                            "BUY breakout rejected — weak candle body (%.0f%% < %.0f%% minimum)",
                            bStrength * 100, MIN_BREAKOUT_BODY_RATIO * 100));
        }

        List<Candle> todayCandles = getTodayCandles(history, latest);
        if (hasTouchedPDLL(todayCandles, pdll)) {
            log.info("[{}] {} BUY — INVALID: PDLL={} was touched today (No-Touch violated)",
                    NAME, symbol, pdll);
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT,
                            "BUY No-Touch violated: PDLL=%.4f was touched by today's price action", pdll));
        }

        BigDecimal breakoutHigh = findBreakoutHigh(history, latest, pdlh);
        if (breakoutHigh == null) {
            return StrategySignal.insufficientData(NAME, symbol, timeframe,
                    "BUY: Could not determine breakoutHigh after PDLH break");
        }

        BigDecimal fibRange = breakoutHigh.subtract(pdll);
        if (fibRange.compareTo(BigDecimal.ZERO) <= 0) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT,
                            "BUY: Invalid Fib range — breakoutHigh=%.4f ≤ PDLL=%.4f",
                            breakoutHigh, pdll));
        }
        BigDecimal fib786Buy = calculateFib786Buy(breakoutHigh, pdll);

        BigDecimal tolerance  = computeTolerance(fibRange, atr);
        BigDecimal lowerBand  = fib786Buy.subtract(tolerance);
        BigDecimal upperBand  = fib786Buy.add(tolerance);
        boolean    touched    = latest.getLowPrice().compareTo(upperBand) <= 0
                             && latest.getHighPrice().compareTo(lowerBand) >= 0;

        if (!touched) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT,
                            "BUY: Awaiting retracement to Fib786=%.4f (band %.4f–%.4f). " +
                            "BreakoutHigh=%.4f PDLL=%.4f",
                            fib786Buy, lowerBand, upperBand, breakoutHigh, pdll));
        }

        double retestPrec = retestPrecision(latest.getClosePrice(), fib786Buy, fibRange);
        int    confidence = confidence(bStrength, retestPrec, fibRange, atr, multiCandle);

        final String reason = String.format(Locale.ROOT,
                "BUY: Breakout above PDLH=%.4f | BreakoutHigh=%.4f | " +
                "PDLL=%.4f (untouched) | Fib786=%.4f | " +
                "Conf=%d/%s Strength=%.0f%% Precision=%.0f%%",
                pdlh, breakoutHigh, pdll, fib786Buy,
                confidence, band(confidence),
                bStrength * 100, (1.0 - retestPrec) * 100);

        StrategySignal signal = StrategySignal.builder()
                .signalType(SignalType.BUY)
                .strategyName(NAME)
                .symbol(symbol)
                .timeframe(timeframe)
                .entryPrice(fib786Buy)
                .stopLoss(pdll)
                .takeProfit(pdh)
                .reason(reason)
                .confidenceScore(confidence)
                .breakoutStrength(bStrength)
                .retestPrecision(retestPrec)
                .atr(atr)
                .londonRangeSize(fibRange)
                .signalStatus(SignalStatus.CREATED)
                .build();

        lockBuy(symbol, today);

        log.info("[{}] ✅ {}", NAME, signal.toLogSummary());

        return signal;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SELL path
    // ═══════════════════════════════════════════════════════════════════════════

    private StrategySignal evaluateSell(AlphaMatrixContext ctx,
                                         List<Candle> history,
                                         Candle latest,
                                         Candle prev,
                                         BigDecimal atr,
                                         String symbol,
                                         String timeframe) {

        final BigDecimal pdh  = ctx.getPdh();
        final BigDecimal pdll = ctx.getPdll();
        final BigDecimal pdlh = ctx.getPdlh();
        final LocalDate  today = ctx.getTradingDate();

        if (sellAlreadyTriggered(symbol, today)) {
            log.debug("[{}] {} — SELL daily lock active for {}", NAME, symbol, today);
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    "SELL daily lock: already fired one SELL today (" + today + ")");
        }

        boolean bearishBreakdown = latest.getClosePrice().compareTo(pdll) < 0
                                || prev.getClosePrice().compareTo(pdll) < 0;

        if (!bearishBreakdown) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT,
                            "SELL: No breakdown below PDLL=%.4f (Close=%.4f)",
                            pdll, latest.getClosePrice()));
        }

        Candle breakdownBar  = selectBreakoutCandle(prev, latest, pdll, false);
        double bStrength     = breakoutStrength(breakdownBar);
        boolean multiCandle  = prev.getClosePrice().compareTo(pdll) < 0
                            && latest.getClosePrice().compareTo(pdll) < 0;

        if (bStrength < MIN_BREAKOUT_BODY_RATIO) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT,
                            "SELL breakdown rejected — weak candle body (%.0f%% < %.0f%% minimum)",
                            bStrength * 100, MIN_BREAKOUT_BODY_RATIO * 100));
        }

        List<Candle> todayCandles = getTodayCandles(history, latest);
        if (hasTouchedPDH(todayCandles, pdh)) {
            log.info("[{}] {} SELL — INVALID: PDH={} was touched today (No-Touch violated)",
                    NAME, symbol, pdh);
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT,
                            "SELL No-Touch violated: PDH=%.4f was touched by today's price action", pdh));
        }

        BigDecimal breakdownLow = findBreakdownLow(history, latest, pdll);
        if (breakdownLow == null) {
            return StrategySignal.insufficientData(NAME, symbol, timeframe,
                    "SELL: Could not determine breakdownLow after PDLL break");
        }

        BigDecimal fibRange = pdh.subtract(breakdownLow);
        if (fibRange.compareTo(BigDecimal.ZERO) <= 0) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT,
                            "SELL: Invalid Fib range — PDH=%.4f ≤ breakdownLow=%.4f",
                            pdh, breakdownLow));
        }
        BigDecimal fib786Sell = calculateFib786Sell(pdh, breakdownLow);

        BigDecimal tolerance  = computeTolerance(fibRange, atr);
        BigDecimal lowerBand  = fib786Sell.subtract(tolerance);
        BigDecimal upperBand  = fib786Sell.add(tolerance);
        boolean    touched    = latest.getLowPrice().compareTo(upperBand) <= 0
                             && latest.getHighPrice().compareTo(lowerBand) >= 0;

        if (!touched) {
            return StrategySignal.noTrade(NAME, symbol, timeframe,
                    String.format(Locale.ROOT,
                            "SELL: Awaiting retracement to Fib786=%.4f (band %.4f–%.4f). " +
                            "BreakdownLow=%.4f PDH=%.4f",
                            fib786Sell, lowerBand, upperBand, breakdownLow, pdh));
        }

        double retestPrec = retestPrecision(latest.getClosePrice(), fib786Sell, fibRange);
        int    confidence = confidence(bStrength, retestPrec, fibRange, atr, multiCandle);

        final String reason = String.format(Locale.ROOT,
                "SELL: Breakdown below PDLL=%.4f | BreakdownLow=%.4f | " +
                "PDH=%.4f (untouched) | Fib786=%.4f | " +
                "Conf=%d/%s Strength=%.0f%% Precision=%.0f%%",
                pdll, breakdownLow, pdh, fib786Sell,
                confidence, band(confidence),
                bStrength * 100, (1.0 - retestPrec) * 100);

        StrategySignal signal = StrategySignal.builder()
                .signalType(SignalType.SELL)
                .strategyName(NAME)
                .symbol(symbol)
                .timeframe(timeframe)
                .entryPrice(fib786Sell)
                .stopLoss(pdh)
                .takeProfit(pdll)
                .reason(reason)
                .confidenceScore(confidence)
                .breakoutStrength(bStrength)
                .retestPrecision(retestPrec)
                .atr(atr)
                .londonRangeSize(fibRange)
                .signalStatus(SignalStatus.CREATED)
                .build();

        lockSell(symbol, today);

        log.info("[{}] ✅ {}", NAME, signal.toLogSummary());

        return signal;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Context builder — calculates all four previous-day reference levels
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Builds the AlphaMatrixContext from historical candles.
     *
     * FIX: previously assumed the previous trading day was always exactly
     * "today − 1". On Mondays that resolves to Sunday, which has zero FX
     * candles, so PDH/PDL/PDLH/PDLL always came back null and every Monday
     * showed INSUFFICIENT_DATA all day. This now walks backward — Sunday,
     * then Saturday, then Friday — until it finds a day that actually has
     * candles, capped at {@link #MAX_LOOKBACK_DAYS_FOR_PREV_SESSION} days.
     */
    private AlphaMatrixContext buildPreviousDayLevels(List<Candle> history,
                                                       Candle latestCandle) {
        LocalDate today = toUTC4Date(latestCandle.getTimestamp());

        for (int back = 1; back <= MAX_LOOKBACK_DAYS_FOR_PREV_SESSION; back++) {
            LocalDate candidate = today.minusDays(back);

            List<Candle> prevDayAll = getPreviousDayCandles(history, candidate);
            if (prevDayAll.isEmpty()) {
                continue; // weekend / holiday — try the day before that
            }

            List<Candle> prevDayLondon = getPreviousDayLondonCandles(history, candidate);

            BigDecimal pdh  = calculatePDH(prevDayAll);
            BigDecimal pdl  = calculatePDL(prevDayAll);
            BigDecimal pdlh = calculatePDLH(prevDayLondon);
            BigDecimal pdll = calculatePDLL(prevDayLondon);

            if (pdh == null || pdl == null || pdlh == null || pdll == null) {
                log.debug("[{}] Day {} has session candles but incomplete London levels " +
                          "(PDLH={} PDLL={}) — trying further back", NAME, candidate, pdlh, pdll);
                continue;
            }

            if (back > 1) {
                log.info("[{}] Previous trading day resolved to {} ({} day(s) back from {} — gap skipped)",
                        NAME, candidate, back, today);
            }

            return AlphaMatrixContext.builder()
                    .tradingDate(today)
                    .pdh(pdh)
                    .pdl(pdl)
                    .pdlh(pdlh)
                    .pdll(pdll)
                    .build();
        }

        log.warn("[{}] No usable previous trading day found within {} day(s) back from {}",
                NAME, MAX_LOOKBACK_DAYS_FOR_PREV_SESSION, today);
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Reference-day candle filters
    // ═══════════════════════════════════════════════════════════════════════════

    private List<Candle> getPreviousDayCandles(List<Candle> history, LocalDate prevDay) {
        return history.stream()
                .filter(c -> c.getTimestamp() != null)
                .filter(c -> {
                    ZonedDateTime zdt  = c.getTimestamp().atZone(ZONE_UTC4);
                    LocalDate     date = zdt.toLocalDate();
                    LocalTime     time = zdt.toLocalTime();
                    return date.equals(prevDay)
                        && !time.isBefore(REF_SESSION_OPEN)
                        &&  time.isBefore(REF_SESSION_CLOSE);
                })
                .collect(Collectors.toList());
    }

    private List<Candle> getPreviousDayLondonCandles(List<Candle> history, LocalDate prevDay) {
        return history.stream()
                .filter(c -> c.getTimestamp() != null)
                .filter(c -> {
                    ZonedDateTime zdtUtc4 = c.getTimestamp().atZone(ZONE_UTC4);
                    LocalDate     dateU4  = zdtUtc4.toLocalDate();
                    LocalTime     timeUtc = c.getTimestamp().atZone(ZoneOffset.UTC).toLocalTime();
                    return dateU4.equals(prevDay)
                        && !timeUtc.isBefore(LONDON_OPEN_UTC)
                        &&  timeUtc.isBefore(LONDON_CLOSE_UTC);
                })
                .collect(Collectors.toList());
    }

    private List<Candle> getTodayCandles(List<Candle> history, Candle latestCandle) {
        LocalDate today = toUTC4Date(latestCandle.getTimestamp());
        return history.stream()
                .filter(c -> c.getTimestamp() != null
                          && toUTC4Date(c.getTimestamp()).equals(today))
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PDH / PDL / PDLH / PDLL calculators
    // ═══════════════════════════════════════════════════════════════════════════

    private BigDecimal calculatePDH(List<Candle> prevDayCandles) {
        return prevDayCandles.stream()
                .map(Candle::getHighPrice)
                .max(Comparator.naturalOrder())
                .map(v -> v.setScale(SCALE, RoundingMode.HALF_UP))
                .orElse(null);
    }

    private BigDecimal calculatePDL(List<Candle> prevDayCandles) {
        return prevDayCandles.stream()
                .map(Candle::getLowPrice)
                .min(Comparator.naturalOrder())
                .map(v -> v.setScale(SCALE, RoundingMode.HALF_UP))
                .orElse(null);
    }

    private BigDecimal calculatePDLH(List<Candle> prevDayLondonCandles) {
        return prevDayLondonCandles.stream()
                .map(Candle::getHighPrice)
                .max(Comparator.naturalOrder())
                .map(v -> v.setScale(SCALE, RoundingMode.HALF_UP))
                .orElse(null);
    }

    private BigDecimal calculatePDLL(List<Candle> prevDayLondonCandles) {
        return prevDayLondonCandles.stream()
                .map(Candle::getLowPrice)
                .min(Comparator.naturalOrder())
                .map(v -> v.setScale(SCALE, RoundingMode.HALF_UP))
                .orElse(null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Entry window
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isWithinEntryWindow(Candle candle) {
        if (candle == null || candle.getTimestamp() == null) return false;
        LocalTime timeUTC4 = candle.getTimestamp().atZone(ZONE_UTC4).toLocalTime();
        return !timeUTC4.isBefore(ENTRY_WINDOW_OPEN)
            &&  timeUTC4.isBefore(ENTRY_WINDOW_CLOSE);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  No-Touch validation
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean hasTouchedPDLL(List<Candle> todayCandles, BigDecimal pdll) {
        return todayCandles.stream()
                .anyMatch(c -> c.getLowPrice().compareTo(pdll) <= 0);
    }

    private boolean hasTouchedPDH(List<Candle> todayCandles, BigDecimal pdh) {
        return todayCandles.stream()
                .anyMatch(c -> c.getHighPrice().compareTo(pdh) >= 0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Breakout high / breakdown low trackers
    // ═══════════════════════════════════════════════════════════════════════════

    private BigDecimal findBreakoutHigh(List<Candle> history, Candle latest, BigDecimal pdlh) {
        LocalDate today = toUTC4Date(latest.getTimestamp());
        return history.stream()
                .filter(c -> c.getTimestamp() != null
                          && toUTC4Date(c.getTimestamp()).equals(today)
                          && c.getClosePrice().compareTo(pdlh) > 0)
                .map(Candle::getHighPrice)
                .max(Comparator.naturalOrder())
                .map(v -> v.setScale(SCALE, RoundingMode.HALF_UP))
                .orElse(null);
    }

    private BigDecimal findBreakdownLow(List<Candle> history, Candle latest, BigDecimal pdll) {
        LocalDate today = toUTC4Date(latest.getTimestamp());
        return history.stream()
                .filter(c -> c.getTimestamp() != null
                          && toUTC4Date(c.getTimestamp()).equals(today)
                          && c.getClosePrice().compareTo(pdll) < 0)
                .map(Candle::getLowPrice)
                .min(Comparator.naturalOrder())
                .map(v -> v.setScale(SCALE, RoundingMode.HALF_UP))
                .orElse(null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Fibonacci calculations
    // ═══════════════════════════════════════════════════════════════════════════

    private BigDecimal calculateFib786Buy(BigDecimal breakoutHigh, BigDecimal pdll) {
        BigDecimal range = breakoutHigh.subtract(pdll);
        return breakoutHigh.subtract(range.multiply(FIB_786_RATIO))
                           .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateFib786Sell(BigDecimal pdh, BigDecimal breakdownLow) {
        BigDecimal range = pdh.subtract(breakdownLow);
        return breakdownLow.add(range.multiply(FIB_786_RATIO))
                           .setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Daily trade locks
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean buyAlreadyTriggered(String symbol, LocalDate date) {
        return Boolean.TRUE.equals(buyTriggered.get(dailyKey(symbol, date)));
    }

    private boolean sellAlreadyTriggered(String symbol, LocalDate date) {
        return Boolean.TRUE.equals(sellTriggered.get(dailyKey(symbol, date)));
    }

    private void lockBuy(String symbol, LocalDate date) {
        buyTriggered.put(dailyKey(symbol, date), Boolean.TRUE);
        log.info("[{}] 🔒 BUY daily lock set for {} on {}", NAME, symbol, date);
    }

    private void lockSell(String symbol, LocalDate date) {
        sellTriggered.put(dailyKey(symbol, date), Boolean.TRUE);
        log.info("[{}] 🔒 SELL daily lock set for {} on {}", NAME, symbol, date);
    }

    private static String dailyKey(String symbol, LocalDate date) {
        return symbol + ":" + date;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ATR, tolerance, strength, precision, confidence
    // ═══════════════════════════════════════════════════════════════════════════

    private BigDecimal computeAtr(List<Candle> history) {
        int n = Math.min(ATR_PERIOD, history.size() - 1);
        if (n < 1) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = history.size() - n; i < history.size(); i++) {
            Candle c    = history.get(i);
            Candle prev = history.get(i - 1);
            BigDecimal hl  = c.getHighPrice().subtract(c.getLowPrice()).abs();
            BigDecimal hpc = c.getHighPrice().subtract(prev.getClosePrice()).abs();
            BigDecimal lpc = c.getLowPrice().subtract(prev.getClosePrice()).abs();
            sum = sum.add(hl.max(hpc).max(lpc));
        }
        return sum.divide(BigDecimal.valueOf(n), SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal computeTolerance(BigDecimal rangeSize, BigDecimal atr) {
        BigDecimal rangeCap = rangeSize
                .multiply(MAX_TOLERANCE_RANGE_RATIO)
                .setScale(SCALE, RoundingMode.HALF_UP);
        if (atr == null || atr.compareTo(BigDecimal.ZERO) == 0) return rangeCap;
        BigDecimal atrTol = atr
                .multiply(ATR_TOLERANCE_MULTIPLIER)
                .setScale(SCALE, RoundingMode.HALF_UP);
        return atrTol.min(rangeCap);
    }

    private Candle selectBreakoutCandle(Candle prev, Candle latest,
                                         BigDecimal level, boolean bullish) {
        boolean prevBroke = bullish
                ? prev.getClosePrice().compareTo(level) > 0
                : prev.getClosePrice().compareTo(level) < 0;
        return prevBroke ? prev : latest;
    }

    private double breakoutStrength(Candle candle) {
        BigDecimal r = CandleUtils.range(candle);
        if (r.compareTo(BigDecimal.ZERO) == 0) return 0.0;
        return CandleUtils.bodySize(candle)
                .divide(r, 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double retestPrecision(BigDecimal closePrice,
                                    BigDecimal fibLevel,
                                    BigDecimal rangeSize) {
        if (rangeSize.compareTo(BigDecimal.ZERO) == 0) return 1.0;
        double dist  = closePrice.subtract(fibLevel).abs().doubleValue();
        return Math.min(dist / rangeSize.doubleValue(), 1.0);
    }

    private int confidence(double bStrength, double retestPrec,
                           BigDecimal fibRange, BigDecimal atr,
                           boolean multiCandle) {

        int c1 = (int) Math.min(bStrength * CONF_BREAKOUT_MAX, CONF_BREAKOUT_MAX);

        int c2 = (int) Math.round((1.0 - retestPrec) * CONF_RETEST_MAX);
        c2 = Math.max(0, Math.min(c2, CONF_RETEST_MAX));

        int c3 = 0;
        if (atr != null && atr.compareTo(BigDecimal.ZERO) > 0) {
            double ratio = fibRange.doubleValue() / atr.doubleValue();
            if (ratio >= 2.0) {
                c3 = CONF_VOLATILITY_MAX;
            } else if (ratio >= 0.5) {
                c3 = (int) Math.round(((ratio - 0.5) / 1.5) * CONF_VOLATILITY_MAX);
            }
            c3 = Math.min(c3, CONF_VOLATILITY_MAX);
        }

        int c4 = multiCandle ? CONF_MULTI_MAX : 0;

        int total = c1 + c2 + c3 + c4;
        log.debug("[{}] Confidence: breakout={} retest={} volatility={} multi={} → {}",
                NAME, c1, c2, c3, c4, total);

        return Math.min(total, 100);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Utility
    // ═══════════════════════════════════════════════════════════════════════════

    private LocalDate toUTC4Date(Instant instant) {
        return instant.atZone(ZONE_UTC4).toLocalDate();
    }

    private static String band(int score) {
        if (score >= 80) return "HIGH";
        if (score >= 50) return "MEDIUM";
        return "LOW";
    }
}