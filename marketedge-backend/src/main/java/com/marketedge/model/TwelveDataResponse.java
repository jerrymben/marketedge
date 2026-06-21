package com.marketedge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * DTO hierarchy for the Twelve Data {@code /time_series} REST response.
 *
 * <p>Sample abbreviated JSON returned by Twelve Data:
 * <pre>{@code
 * {
 *   "meta": {
 *     "symbol":    "AAPL",
 *     "interval":  "1min",
 *     "currency":  "USD",
 *     "type":      "Common Stock"
 *   },
 *   "values": [
 *     {
 *       "datetime": "2024-06-06 15:59:00",
 *       "open":     "189.14000",
 *       "high":     "189.25000",
 *       "low":      "189.05000",
 *       "close":    "189.20000",
 *       "volume":   "123456"
 *     },
 *     ...
 *   ],
 *   "status": "ok"
 * }
 * }</pre>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} ensures forward
 * compatibility — extra fields added by Twelve Data will not break parsing.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class TwelveDataResponse {

    /**
     * Metadata block describing the instrument and request parameters.
     */
    @JsonProperty("meta")
    private Meta meta;

    /**
     * Ordered list of OHLCV bars, most-recent first (Twelve Data default).
     */
    @JsonProperty("values")
    private List<OhlcvValue> values;

    /**
     * API-level status: {@code "ok"} on success, {@code "error"} otherwise.
     * Error detail is returned in the {@code message} field at root level.
     */
    @JsonProperty("status")
    private String status;

    /**
     * Present only when {@code status = "error"}.
     * E.g. "**symbol** not found" or "API credits exhausted".
     */
    @JsonProperty("message")
    private String message;

    /**
     * Error code returned alongside error responses.
     * E.g. 400 (bad request), 401 (invalid key), 429 (rate-limited).
     */
    @JsonProperty("code")
    private Integer code;

    // ── Convenience ──────────────────────────────────────────────────────────

    /** Returns {@code true} when the API call succeeded. */
    public boolean isOk() {
        return "ok".equalsIgnoreCase(status);
    }

    /** Returns {@code true} when {@link #values} contains at least one bar. */
    public boolean hasValues() {
        return values != null && !values.isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Nested DTOs
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Twelve Data {@code meta} block.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {

        @JsonProperty("symbol")
        private String symbol;

        @JsonProperty("interval")
        private String interval;

        @JsonProperty("currency")
        private String currency;

        @JsonProperty("exchange_timezone")
        private String exchangeTimezone;

        @JsonProperty("exchange")
        private String exchange;

        @JsonProperty("mic_code")
        private String micCode;

        @JsonProperty("type")
        private String type;
    }

    /**
     * One OHLCV bar from the Twelve Data {@code values} array.
     *
     * <p>All numeric fields are received as {@link String} to avoid floating-point
     * rounding.  The service layer converts them to {@link java.math.BigDecimal}
     * via {@code new BigDecimal(String)} before building the {@link Candle} entity.
     *
     * <p>{@code datetime} format: {@code "yyyy-MM-dd HH:mm:ss"} (UTC for stocks,
     * exchange-local for forex unless {@code timezone=UTC} is added to the request).
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OhlcvValue {

        /** Bar open timestamp as string, e.g. {@code "2024-06-06 15:59:00"}. */
        @JsonProperty("datetime")
        private String datetime;

        /** Open price as decimal string, e.g. {@code "189.14000"}. */
        @JsonProperty("open")
        private String open;

        /** High price as decimal string. */
        @JsonProperty("high")
        private String high;

        /** Low price as decimal string. */
        @JsonProperty("low")
        private String low;

        /** Close price as decimal string. */
        @JsonProperty("close")
        private String close;

        /**
         * Trade volume as string.
         * May be {@code "0"} or absent for forex/crypto instruments.
         * Mapped to a future {@code volume} column (Phase 3 schema extension).
         */
        @JsonProperty("volume")
        private String volume;
    }
}