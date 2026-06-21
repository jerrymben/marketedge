package com.marketedge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;

/**
 * Type-safe binding for all {@code marketedge.*} properties in application.yml.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  ROOT-CAUSE CHANGES vs the original file
 * ═══════════════════════════════════════════════════════════════════
 *
 * TwelveData:
 *   - Added {@code timeframes} (List<String>) so the scheduler can
 *     rotate through [5min, 15min, 1h, 4h, 1day] instead of a
 *     single hardcoded "1min" value.  This is the primary reason
 *     4h, 1day, 5min, 15min had zero DB rows.
 *   - {@code timeframe} (String) kept for fetchOnDemand fallback only.
 *   - {@code outputSize} default raised from 5 → 1440.
 *     At 5 bars/call the scheduler needed 288 calls to reach 1440
 *     candles — effectively impossible on the free tier.
 *
 * Redis:
 *   - Added {@code timeframeTtlSeconds} map so each sorted-set key
 *     lives long enough to cover its full visible chart range.
 *   - Added {@code maxCandlesPerKey} to cap sorted-set growth.
 *   - Added {@code getTtlForTimeframe()} helper used by the service.
 */
@Configuration
@ConfigurationProperties(prefix = "marketedge")
@Validated
@Getter
@Setter
public class TwelveDataProperties {

    private TwelveData twelveData = new TwelveData();
    private Redis      redis      = new Redis();

    // ─── Twelve Data block ────────────────────────────────────────────────────

    @Getter
    @Setter
    public static class TwelveData {

        /**
         * API key — always read from the TWELVE_DATA_API_KEY environment variable.
         * PowerShell (current session): $env:TWELVE_DATA_API_KEY = "your_key"
         * PowerShell (permanent):       setx TWELVE_DATA_API_KEY "your_key"
         */
        @NotBlank(message = "Twelve Data API key must be set via TWELVE_DATA_API_KEY env var")
        private String apiKey = "YOUR_API_KEY_HERE";

        @NotBlank
        private String baseUrl = "https://api.twelvedata.com";

        /**
         * Delay between scheduler ticks (ms).
         * Rate budget: 9 symbols × 1 call/tick = 9 credits per tick.
         * At 75 000 ms per tick: 9 / 75 s = 7.2 credits/min < 8/min limit.
         */
        @Positive
        private long pollIntervalMs = 75_000;

        /** FX + Gold symbols in Twelve Data slash format. */
        @NotEmpty(message = "At least one symbol must be configured")
        private List<String> symbols = List.of(
                "XAU/USD", "EUR/USD", "GBP/USD", "USD/JPY",
                "GBP/JPY", "EUR/JPY", "USD/CHF", "CHF/JPY", "GBP/CAD"
        );

        /**
         * All timeframes the scheduler rotates through.
         * Each tick advances to the next entry (AtomicInteger index).
         * Matches TimeFrameConfig.js exactly — 1min and 30min excluded.
         */
        @NotEmpty(message = "At least one timeframe must be configured")
        private List<String> timeframes = List.of("5min", "15min", "1h", "4h", "1day");

        /**
         * Legacy single-value field — used only as a fallback in fetchOnDemand
         * when no timeframe is specified.  Scheduler ignores this field.
         */
        @NotBlank
        private String timeframe = "1h";

        /**
         * Bars to request per Twelve Data API call.
         * 1440 = the maximum any timeframe needs (5min / 15min views).
         * Twelve Data free tier supports up to 5000 bars per call.
         * CHANGED from 5 → 1440.
         */
        @Positive
        private int outputSize = 1440;
    }

    // ─── Redis pub/sub block ──────────────────────────────────────────────────

    @Getter
    @Setter
    public static class Redis {

        private String candleChannel     = "market_data_stream";
        private String candleCachePrefix = "candle:cache:";

        /** Default TTL fallback in seconds (used when timeframe not in map). */
        private long candleTtlSeconds = 3600;

        /**
         * Per-timeframe TTL map (seconds).
         * Sized so each key lives long enough to cover the full visible range:
         *   5min  →  5 days  =  432 000 s
         *   15min → 15 days  = 1 296 000 s
         *   1h    → 30 days  = 2 592 000 s
         *   4h    → 60 days  = 5 184 000 s
         *   1day  → 90 days  = 7 776 000 s
         */
        private Map<String, Long> timeframeTtlSeconds = Map.of(
                "5min",  432_000L,
                "15min", 1_296_000L,
                "1h",    2_592_000L,
                "4h",    5_184_000L,
                "1day",  7_776_000L
        );

        /**
         * Maximum entries per Redis sorted-set key.
         * ZREMRANGEBYRANK trims oldest entries on every write so the set
         * never exceeds this cap regardless of TTL.
         */
        private int maxCandlesPerKey = 1440;

        /** Returns the TTL in seconds for the given timeframe string. */
        public long getTtlForTimeframe(String timeframe) {
            return timeframeTtlSeconds.getOrDefault(timeframe, candleTtlSeconds);
        }
    }
}