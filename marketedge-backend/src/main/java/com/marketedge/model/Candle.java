package com.marketedge.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for one OHLCV candlestick bar.
 *
 * ══════════════════════════════════════════════════════════════════
 *  ROOT-CAUSE FIXES
 * ══════════════════════════════════════════════════════════════════
 *
 * Fix 1 — Issue #3: symbol VARCHAR(10) too short for FX pairs.
 *   "GBP/CAD" = 7 chars  → fits in 10, but "XAU/USD" = 7 chars,
 *   "USD/JPY" = 7 chars — all fit. HOWEVER the original schema DDL
 *   must also use VARCHAR(12) so Hibernate validate does not fail.
 *   Length raised to 12 for safe margin (longest needed = 7).
 *
 * Fix 2 — Issue #5: Hibernate validate rejects Instant on timestamptz.
 *   Without columnDefinition, Hibernate maps Instant → TIMESTAMP (no tz).
 *   PostgreSQL stores the column as TIMESTAMP WITH TIME ZONE (timestamptz).
 *   Hibernate validate compares the mapped type against the DB type and
 *   throws SchemaManagementException on startup.
 *   Fix: add columnDefinition = "TIMESTAMPTZ" so Hibernate knows the
 *   intended SQL type and validate passes.
 *
 * Fix 3 — Issue #5: volume column nullable=true (no NOT NULL on schema).
 *   Forex instruments return volume=0 or null from Twelve Data.
 *   The column must be nullable to accept these rows without constraint
 *   violations. Schema DDL must match (no NOT NULL on volume).
 *
 * Fix 4 — Issue #5: sequence name.
 *   The original schema used BIGSERIAL which auto-creates candle_id_seq.
 *   If you re-created the table as a plain BIGINT + separate sequence,
 *   the sequence name must match. See schema.sql comments.
 */
@Entity
@Table(
    name = "candle",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_candle_symbol_timeframe_timestamp",
            columnNames = {"symbol", "timeframe", "timestamp"}
        )
    },
    indexes = {
        @Index(
            name = "idx_candle_lookup_sort",
            columnList = "symbol, timeframe, timestamp"
        )
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Candle {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "candle_seq_gen")
    @SequenceGenerator(name = "candle_seq_gen", sequenceName = "candle_id_seq", allocationSize = 1)
    private Long id;

    // FIX 1: length=12 (was 10). "GBP/CAD" = 7 chars, "XAU/USD" = 7 chars.
    // 12 gives headroom for any future pair without another schema change.
    @Column(name = "symbol", nullable = false, length = 12)
    private String symbol;

    @Column(name = "timeframe", nullable = false, length = 5)
    private String timeframe;

    @Column(name = "open_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal closePrice;

    // FIX 2: columnDefinition = "TIMESTAMPTZ" tells Hibernate the SQL type
    // is timestamp-with-timezone, matching PostgreSQL's TIMESTAMPTZ column.
    // Without this, Hibernate validate compares "timestamp" (Java mapping)
    // against "timestamptz" (DB column) and throws SchemaManagementException.
    @Column(name = "timestamp", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant timestamp;

    // FIX 3: volume is nullable (Forex has no volume; nullable = true is default
    // but stated explicitly for clarity).
    @Column(name = "volume", precision = 18, scale = 4, nullable = true)
    private BigDecimal volume;

    /**
     * Convenience factory used by the ingestion service.
     * Does not set volume — use the Lombok builder if volume is needed.
     */
    public static Candle of(
            String symbol,
            String timeframe,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            Instant timestamp) {

        return Candle.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .openPrice(openPrice)
                .highPrice(highPrice)
                .lowPrice(lowPrice)
                .closePrice(closePrice)
                .timestamp(timestamp)
                .build();
    }
}