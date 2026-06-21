package com.marketedge.service;

import com.marketedge.config.TwelveDataProperties;
import com.marketedge.model.Candle;
import com.marketedge.model.TwelveDataResponse;
import com.marketedge.model.TwelveDataResponse.OhlcvValue;
import com.marketedge.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core market data ingestion service.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  ROOT-CAUSE FIXES vs the original file
 * ═══════════════════════════════════════════════════════════════════
 *
 * FIX 1 — Scheduler polled only ONE timeframe (Issue 01 + Issue 02).
 *   Original: timeframe = props.getTwelveData().getTimeframe() → always "1min"
 *   This meant 4h, 1day, 5min, 15min had ZERO rows in PostgreSQL.
 *   When the UI switched to 1day, the controller returned [] which the
 *   chart rendered as blank — hence "1day completely broken".
 *
 *   Fix: AtomicInteger rotates through props.getTwelveData().getTimeframes()
 *   [5min, 15min, 1h, 4h, 1day]. Each tick fetches all 9 symbols for one
 *   timeframe, then advances. Full rotation every 5 × 75 s = 375 s ≈ 6 min.
 *
 * FIX 2 — outputSize was 5 (Issue 01).
 *   5 bars/call × 75 s per tick = effectively impossible to reach 1440 candles.
 *   Now reads from props (set to 1440 in application.yml).
 *
 * FIX 3 — Redis sorted set grew without bound.
 *   After every ZADD batch: ZREMRANGEBYRANK trims to maxCandlesPerKey.
 *
 * FIX 4 — Redis TTL was one global value.
 *   Now uses getTtlForTimeframe() from TwelveDataProperties.Redis.
 *
 * FIX 5 — Duplicate imports (ZoneOffset ×3, Instant ×2, Optional ×2).
 *   Cleaned — one import per type.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private static final DateTimeFormatter TD_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestTemplate                  twelveDataRestTemplate;
    private final TwelveDataProperties          props;
    private final CandleRepository              candleRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ApiRateLimiter                rateLimiter;

    /**
     * FIX 1: Rotating index over all configured timeframes.
     * AtomicInteger.getAndUpdate is safe for a single scheduler thread.
     */
    private final AtomicInteger timeframeIndex = new AtomicInteger(0);

    // ═══════════════════════════════════════════════════════════════════════════
    //  1. SCHEDULER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * FIX 1: Rotates through all required timeframes instead of one.
     *
     * Rotation with timeframes = [5min, 15min, 1h, 4h, 1day]:
     *   Tick 0 → all 9 symbols on 5min   (9 API credits)
     *   Tick 1 → all 9 symbols on 15min  (9 API credits)
     *   Tick 2 → all 9 symbols on 1h     (9 API credits)
     *   Tick 3 → all 9 symbols on 4h     (9 API credits)
     *   Tick 4 → all 9 symbols on 1day   (9 API credits)
     *   Tick 5 → back to 5min
     *
     * Rate: 9 calls / 75 s = 7.2/min  <  8/min free-tier limit. ✓
     */
    @Scheduled(
        fixedDelayString   = "${marketedge.twelve-data.poll-interval-ms:75000}",
        initialDelayString = "5000"
    )
    public void scheduledFetchAll() {
        List<String> timeframes = props.getTwelveData().getTimeframes();
        List<String> symbols    = props.getTwelveData().getSymbols();

        if (timeframes == null || timeframes.isEmpty()) {
            log.warn("[Scheduler] timeframes list is empty — skipping tick");
            return;
        }

        int    idx       = timeframeIndex.getAndUpdate(i -> (i + 1) % timeframes.size());
        String timeframe = timeframes.get(idx);

        log.info("[Scheduler] Tick [{}/{}] timeframe='{}' symbols={}",
                idx + 1, timeframes.size(), timeframe, symbols.size());

        for (String symbol : symbols) {
            try {
                fetchAndProcess(symbol, timeframe);
            } catch (Exception e) {
                log.error("[Scheduler] Error symbol='{}' tf='{}': {}",
                        symbol, timeframe, e.getMessage(), e);
            }
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyRateLimitCounter() {
        rateLimiter.resetDailyCounter();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  2. FETCH & PROCESS
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    @CacheEvict(cacheNames = "candles", allEntries = true)
    public List<Candle> fetchAndProcess(String symbol, String timeframe) {

        if (!rateLimiter.tryAcquire()) {
            log.warn("[Ingest] Rate limit reached — skipping '{}' tf='{}'", symbol, timeframe);
            return Collections.emptyList();
        }

        TwelveDataResponse response = callTwelveDataApi(symbol, timeframe);
        if (response == null || !response.isOk() || !response.hasValues()) {
            log.warn("[Ingest] No usable data for '{}' tf='{}'. status={}",
                    symbol, timeframe, response != null ? response.getStatus() : "null");
            return Collections.emptyList();
        }

        List<Candle> newCandles = parseAndSave(response, symbol, timeframe);

        if (!newCandles.isEmpty()) {
            warmRedisCache(symbol, timeframe, newCandles);
            newCandles.forEach(this::publishToRedis);
        }

        log.info("[Ingest] Saved {} new candle(s) for '{}' tf='{}'",
                newCandles.size(), symbol, timeframe);
        return newCandles;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  3. HTTP — Twelve Data REST call
    // ═══════════════════════════════════════════════════════════════════════════

    private TwelveDataResponse callTwelveDataApi(String symbol, String timeframe) {
        TwelveDataProperties.TwelveData cfg = props.getTwelveData();

        String url = UriComponentsBuilder
                .fromHttpUrl(cfg.getBaseUrl() + "/time_series")
                .queryParam("symbol",     symbol)
                .queryParam("interval",   timeframe)
                .queryParam("outputsize", cfg.getOutputSize()) // FIX 2: now 1440
                .queryParam("timezone",   "UTC")
                .queryParam("format",     "JSON")
                .queryParam("apikey",     cfg.getApiKey())
                .toUriString();

        log.debug("[API] GET {}", url.replace(cfg.getApiKey(), "***"));

        try {
            TwelveDataResponse response =
                    twelveDataRestTemplate.getForObject(url, TwelveDataResponse.class);
            if (response != null && !response.isOk()) {
                log.error("[API] Error — code={} message='{}'",
                        response.getCode(), response.getMessage());
            }
            return response;
        } catch (HttpClientErrorException e) {
            log.error("[API] 4xx {} for '{}' tf='{}': {}",
                    e.getStatusCode(), symbol, timeframe, e.getMessage());
        } catch (HttpServerErrorException e) {
            log.error("[API] 5xx {} for '{}' tf='{}': {}",
                    e.getStatusCode(), symbol, timeframe, e.getMessage());
        } catch (ResourceAccessException e) {
            log.error("[API] Network error for '{}' tf='{}': {}",
                    symbol, timeframe, e.getMessage());
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  4. PARSE & PERSIST
    // ═══════════════════════════════════════════════════════════════════════════

    private List<Candle> parseAndSave(TwelveDataResponse response,
                                      String symbol, String timeframe) {
        List<Candle> toSave = new ArrayList<>();

        Instant latestTimestamp = candleRepository
                .findTopBySymbolAndTimeframeOrderByTimestampDesc(symbol, timeframe)
                .map(Candle::getTimestamp)
                .orElse(Instant.MIN);

        for (OhlcvValue value : response.getValues()) {
            Instant ts = parseTimestamp(value.getDatetime());
            if (ts == null) continue;
            if (!ts.isAfter(latestTimestamp)) {
                log.trace("[Ingest] Duplicate skipped: {} {} {}", symbol, timeframe, ts);
                continue;
            }
            Candle candle = buildCandle(symbol, timeframe, ts, value);
            if (candle != null) toSave.add(candle);
        }

        return toSave.isEmpty() ? Collections.emptyList()
                                : candleRepository.saveAll(toSave);
    }

    private Candle buildCandle(String symbol, String timeframe,
                               Instant timestamp, OhlcvValue value) {
        try {
            return Candle.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .timestamp(timestamp)
                    .openPrice(new BigDecimal(value.getOpen().trim()))
                    .highPrice(new BigDecimal(value.getHigh().trim()))
                    .lowPrice(new BigDecimal(value.getLow().trim()))
                    .closePrice(new BigDecimal(value.getClose().trim()))
                    .volume(parseVolume(value.getVolume()))
                    .build();
        } catch (Exception e) {
            log.warn("[Parse] Bad OHLC data for {} @ {}: {}", symbol, timestamp, e.getMessage());
            return null;
        }
    }

    private BigDecimal parseVolume(String volume) {
        if (volume == null || volume.isBlank()) return null;
        try { return new BigDecimal(volume.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw, TD_FORMATTER)
                    .atZone(ZoneOffset.UTC)
                    .toInstant();
        } catch (DateTimeParseException e) {
            log.warn("[Parse] Cannot parse datetime '{}': {}", raw, e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  5. REDIS PUB/SUB
    // ═══════════════════════════════════════════════════════════════════════════

    private void publishToRedis(Candle candle) {
        String channel = props.getRedis().getCandleChannel();
        try {
            Long receivers = redisTemplate.convertAndSend(channel, candle);
            log.debug("[Redis] Published {}/{} @ {} → channel='{}' receivers={}",
                    candle.getSymbol(), candle.getTimeframe(),
                    candle.getTimestamp(), channel, receivers);
        } catch (Exception e) {
            log.error("[Redis] Publish failed for channel '{}': {}", channel, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  6. REDIS CACHE — bounded sorted set + per-timeframe TTL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * FIX 3: ZREMRANGEBYRANK trims oldest entries so the sorted set never
     * exceeds maxCandlesPerKey entries, preventing unbounded memory growth.
     *
     * FIX 4: TTL is looked up per timeframe from the yml map so each key
     * lives long enough to serve its full chart range from cache.
     */
    public void warmRedisCache(String symbol, String timeframe, List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return;

        TwelveDataProperties.Redis redisCfg = props.getRedis();
        String key     = redisCfg.getCandleCachePrefix() + symbol + ":" + timeframe;
        long   ttlSecs = redisCfg.getTtlForTimeframe(timeframe);
        int    maxSize = redisCfg.getMaxCandlesPerKey();

        try {
            for (Candle c : candles) {
                redisTemplate.opsForZSet().add(key, c, c.getTimestamp().getEpochSecond());
            }

            // FIX 3: trim to cap — keep the newest maxSize entries
            Long setSize = redisTemplate.opsForZSet().zCard(key);
            if (setSize != null && setSize > maxSize) {
                redisTemplate.opsForZSet().removeRange(key, 0, setSize - maxSize - 1);
                log.debug("[Cache] Trimmed '{}' from {} → {} entries", key, setSize, maxSize);
            }

            // FIX 4: per-timeframe TTL
            redisTemplate.expire(key, Duration.ofSeconds(ttlSecs));
            log.debug("[Cache] Warmed '{}' +{} candles TTL={}s", key, candles.size(), ttlSecs);

        } catch (Exception e) {
            log.error("[Cache] Failed warming '{}': {}", key, e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  7. PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    public List<Candle> fetchOnDemand(String symbol, String timeframe) {
        log.info("[OnDemand] Manual fetch: symbol='{}' tf='{}'", symbol, timeframe);
        return fetchAndProcess(symbol, timeframe);
    }

    public List<Candle> getLatestCandles(String symbol, String timeframe) {
        return candleRepository
                .findTop200BySymbolAndTimeframeOrderByTimestampDesc(symbol, timeframe);
    }
}