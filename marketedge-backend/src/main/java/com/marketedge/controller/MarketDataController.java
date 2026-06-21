package com.marketedge.controller;

import com.marketedge.model.Candle;
import com.marketedge.repository.CandleRepository;
import com.marketedge.service.ApiRateLimiter;
import com.marketedge.service.MarketDataService;
import com.marketedge.service.StrategyEngineService;
import com.marketedge.service.WebSocketBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for market data endpoints.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  ROOT-CAUSE FIXES + ADDITIONS vs previous version
 * ═══════════════════════════════════════════════════════════════════
 *
 * FIX — Dynamic candle limit:
 *   The old controller called findTop200... regardless of the `limit`
 *   param. Now calls findRecentCandles(symbol, timeframe, PageRequest)
 *   where limit = the value sent by the frontend (90–1440).
 *
 * FIX — Symbol as @RequestParam (not @PathVariable):
 *   Prevents Spring from splitting "GBP/CAD" on the "/" character.
 *
 * NEW — POST /api/candles/backfill:
 *   Triggers fetchOnDemand for every symbol × every timeframe immediately.
 *   Essential because the rotating scheduler needs ~6 min per full cycle
 *   and delivers only the latest 1440 bars per call.  Without backfill,
 *   switching to the 1day view shows an empty chart until the scheduler
 *   has polled the 1day timeframe at least once (up to 5 ticks = 6 min wait).
 *
 *   HOW TO USE (run once after starting the backend):
 *     curl -X POST http://localhost:8080/api/candles/backfill
 *   Or from a REST client / browser tab.
 *   Re-run after 2–3 minutes to fill any rate-limited pairs.
 */
@Slf4j
@RestController
@RequestMapping("/candles")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173", "http://localhost:*"})
public class MarketDataController {

    private static final int MAX_CANDLE_LIMIT = 1440;
    private static final int DEFAULT_LIMIT    = 720;

    /** All 5 required timeframes — kept in sync with TimeFrameConfig.js. */
    private static final List<String> ALL_TIMEFRAMES =
            List.of("5min", "15min", "1h", "4h", "1day");

    /** All 9 required FX + Gold symbols in Twelve Data slash format. */
    private static final List<String> ALL_SYMBOLS = List.of(
            "XAU/USD", "EUR/USD", "GBP/USD", "USD/JPY",
            "GBP/JPY", "EUR/JPY", "USD/CHF", "CHF/JPY", "GBP/CAD"
    );

    private final MarketDataService     marketDataService;
    private final StrategyEngineService strategyEngineService;
    private final CandleRepository      candleRepository;
    private final ApiRateLimiter        rateLimiter;
    private final WebSocketBroadcaster  webSocketBroadcaster;

    // ═══════════════════════════════════════════════════════════════════════════
    //  1. Historical candles
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/candles/{timeframe}?symbol=XAU%2FUSD[&limit=1440]
     *
     * Returns the most recent `limit` candles, newest → oldest.
     * Frontend toCandleSeries() reverses to oldest → newest for the chart.
     *
     * limit ranges by timeframe (from TimeFrameConfig.js):
     *   5min  → 1440   15min → 1440   1h → 720   4h → 360   1day → 90
     */
    @GetMapping("/{timeframe}")
    public ResponseEntity<List<Candle>> getCandles(
            @RequestParam                       String symbol,
            @PathVariable                       String timeframe,
            @RequestParam(defaultValue = "720") int    limit) {

        int safeLimit = Math.min(Math.max(limit, 1), MAX_CANDLE_LIMIT);
        log.debug("[REST] GET /candles/{} symbol='{}' limit={}", timeframe, symbol, safeLimit);

        List<Candle> candles = candleRepository.findRecentCandles(
                symbol,
                timeframe,
                PageRequest.of(0, safeLimit, Sort.by("timestamp").descending()));

        return ResponseEntity.ok(candles);
    }

    /** GET /api/candles/{timeframe}/range?symbol=...&from=...&to=... */
    @GetMapping("/{timeframe}/range")
    public ResponseEntity<List<Candle>> getCandlesInRange(
            @RequestParam String symbol,
            @PathVariable String timeframe,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        log.debug("[REST] GET /candles/{}/range symbol='{}' from={} to={}",
                timeframe, symbol, from, to);
        return ResponseEntity.ok(
                candleRepository.findBySymbolAndTimeframeAndTimestampBetween(
                        symbol, timeframe, from, to));
    }

// ═══════════════════════════════════════════════════════════════════════════
    //  2. On-demand fetch (single pair with Database Fallback)
    // ═══════════════════════════════════════════════════════════════════════════

    /** POST /api/candles/{timeframe}/fetch?symbol=XAU%2FUSD */
    @PostMapping("/{timeframe}/fetch")
    public ResponseEntity<Map<String, Object>> triggerFetch(
            @RequestParam String symbol,
            @PathVariable String timeframe) {

        log.info("[REST] POST /candles/{}/fetch symbol='{}'", timeframe, symbol);
        List<Candle> newCandles = marketDataService.fetchOnDemand(symbol, timeframe);

        boolean fallbackTriggered = false;

        // 💡 SMART FALLBACK: If external API returns 0 candles (rate-limited),
        // pull the latest known candle from the local database and run the engine.
        if (newCandles.isEmpty()) {
            List<Candle> localCandles = candleRepository.findRecentCandles(
                    symbol,
                    timeframe,
                    PageRequest.of(0, 1, Sort.by("timestamp").descending()));

            if (!localCandles.isEmpty()) {
                log.info("[REST] External API rate-limited. Triggering fallback engine evaluation on local DB data.");
                strategyEngineService.onNewCandle(localCandles.get(0));
                fallbackTriggered = true;
            } else {
                log.warn("[REST] Fallback failed: No historical data found in local DB for {}/{}", symbol, timeframe);
            }
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("symbol",             symbol);
        resp.put("timeframe",          timeframe);
        resp.put("newCandles",         newCandles.size());
        resp.put("fallbackTriggered",  fallbackTriggered);
        resp.put("rateLimitRemaining", rateLimiter.getRemainingCredits());
        return ResponseEntity.ok(resp);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  3. Backfill — seeds every symbol × every timeframe at once
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/candles/backfill
     *
     * Immediately fetches all 9 symbols × all 5 timeframes = 45 API calls.
     * Free tier cap is 8/min so ~6 minutes of calls are needed in total.
     * Calls that hit the rate limit return 0 new candles.
     * Re-run the endpoint after 2-3 minutes to fill any skipped pairs.
     *
     * Returns a "symbol:timeframe" → newCount map plus a total.
     */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfillAll() {
        log.info("[Backfill] Starting: {} symbols × {} timeframes = {} calls",
                ALL_SYMBOLS.size(), ALL_TIMEFRAMES.size(),
                ALL_SYMBOLS.size() * ALL_TIMEFRAMES.size());

        Map<String, Integer> details = new HashMap<>();
        int totalNew = 0;

        for (String timeframe : ALL_TIMEFRAMES) {
            for (String symbol : ALL_SYMBOLS) {
                try {
                    int n = marketDataService.fetchOnDemand(symbol, timeframe).size();
                    details.put(symbol + ":" + timeframe, n);
                    totalNew += n;
                    log.info("[Backfill] {}/{} → {} new candles", symbol, timeframe, n);
                } catch (Exception e) {
                    log.error("[Backfill] Error {}/{}: {}", symbol, timeframe, e.getMessage());
                    details.put(symbol + ":" + timeframe, -1);
                }
            }
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("totalNewCandles",    totalNew);
        resp.put("rateLimitRemaining", rateLimiter.getRemainingCredits());
        resp.put("details",            details);
        resp.put("note",
                "Rate limited to 8/min. Re-run in 2 minutes to fill rate-limited pairs.");
        log.info("[Backfill] Done — {} new candles total", totalNew);
        return ResponseEntity.ok(resp);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  4. Metadata
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/symbols")
    public ResponseEntity<List<String>> getSymbols() {
        return ResponseEntity.ok(candleRepository.findDistinctSymbols());
    }

    @GetMapping("/strategies")
    public ResponseEntity<List<StrategyEngineService.StrategyMetadata>> getStrategies() {
        return ResponseEntity.ok(strategyEngineService.getStrategyMetadata());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  5. Health
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        return ResponseEntity.ok(buildHealthPayload());
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void broadcastHealthHeartbeat() {
        webSocketBroadcaster.broadcastHealth(buildHealthPayload());
    }

    private Map<String, Object> buildHealthPayload() {
        Map<String, Object> h = new HashMap<>();
        h.put("status",             "UP");
        h.put("timestamp",          Instant.now().toString());
        h.put("rateLimitRemaining", rateLimiter.getRemainingCredits());
        h.put("totalApiCallsToday", rateLimiter.getTotalCallsToday());
        h.put("strategies",         strategyEngineService.getRegisteredStrategyNames());
        return h;
    }
}