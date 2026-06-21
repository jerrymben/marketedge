package com.marketedge.repository;

import com.marketedge.model.Candle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for Candle entities.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  KEY ADDITION vs the original file
 * ═══════════════════════════════════════════════════════════════════
 *
 * findRecentCandles(symbol, timeframe, Pageable):
 *   The original file only had findTop200... which hardcodes 200 rows.
 *   200 is far less than the 1440 needed for 5min/15min views, and
 *   more than the 90 needed for the 1day view.
 *
 *   The new method accepts a Pageable so the controller can pass:
 *     PageRequest.of(0, limit, Sort.by("timestamp").descending())
 *   where limit = getCandleCount(timeframe) from TimeFrameConfig.js.
 *
 *   findTop200... is kept for the StrategyEngineService which always
 *   fetches 200 bars and trims to 100 internally.
 */
@Repository
public interface CandleRepository extends JpaRepository<Candle, Long> {

    // =========================================================================
    //  NEW — dynamic-limit query for the REST controller
    // =========================================================================

    /**
     * Returns the most recent {@code pageable.getPageSize()} candles for a
     * symbol + timeframe, ordered newest → oldest.
     *
     * <p>Controller usage:
     * <pre>
     *   candleRepository.findRecentCandles(symbol, timeframe,
     *       PageRequest.of(0, limit, Sort.by("timestamp").descending()));
     * </pre>
     * The frontend reverses the list to oldest → newest for Lightweight Charts.
     */
    @Query("""
            SELECT c FROM Candle c
            WHERE c.symbol    = :symbol
              AND c.timeframe = :timeframe
            ORDER BY c.timestamp DESC
            """)
    List<Candle> findRecentCandles(
            @Param("symbol")    String   symbol,
            @Param("timeframe") String   timeframe,
            Pageable            pageable);

    // =========================================================================
    //  Existing — kept for StrategyEngineService (always needs exactly 200)
    // =========================================================================

    List<Candle> findTop200BySymbolAndTimeframeOrderByTimestampDesc(
            String symbol, String timeframe);

    // =========================================================================
    //  Existing — unchanged
    // =========================================================================

    List<Candle> findBySymbolAndTimeframeOrderByTimestampAsc(
            String symbol, String timeframe);

    Page<Candle> findBySymbolAndTimeframe(
            String symbol, String timeframe, Pageable pageable);

    Optional<Candle> findTopBySymbolAndTimeframeOrderByTimestampDesc(
            String symbol, String timeframe);

    @Query("""
            SELECT c FROM Candle c
            WHERE c.symbol    = :symbol
              AND c.timeframe = :timeframe
              AND c.timestamp BETWEEN :from AND :to
            ORDER BY c.timestamp ASC
            """)
    List<Candle> findBySymbolAndTimeframeAndTimestampBetween(
            @Param("symbol")    String  symbol,
            @Param("timeframe") String  timeframe,
            @Param("from")      Instant from,
            @Param("to")        Instant to);

    long countBySymbolAndTimeframeAndTimestampBetween(
            String symbol, String timeframe, Instant from, Instant to);

    boolean existsBySymbolAndTimeframeAndTimestamp(
            String symbol, String timeframe, Instant timestamp);

    @Query("SELECT DISTINCT c.symbol FROM Candle c ORDER BY c.symbol ASC")
    List<String> findDistinctSymbols();

    @Query("""
            SELECT DISTINCT c.timeframe FROM Candle c
            WHERE c.symbol = :symbol ORDER BY c.timeframe ASC
            """)
    List<String> findDistinctTimeframesBySymbol(@Param("symbol") String symbol);

    @Query("""
            SELECT MIN(c.lowPrice), MAX(c.highPrice),
                   MIN(c.timestamp), MAX(c.timestamp), COUNT(c.id)
            FROM Candle c
            WHERE c.symbol    = :symbol
              AND c.timeframe = :timeframe
              AND c.timestamp BETWEEN :from AND :to
            """)
    Object[] aggregateSummary(
            @Param("symbol")    String  symbol,
            @Param("timeframe") String  timeframe,
            @Param("from")      Instant from,
            @Param("to")        Instant to);

    @Modifying
    @Query("DELETE FROM Candle c WHERE c.timestamp < :cutoff")
    int deleteByTimestampBefore(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("DELETE FROM Candle c WHERE c.symbol = :symbol")
    int deleteAllBySymbol(@Param("symbol") String symbol);

    @Query(value = """
            SELECT gs.ts
            FROM generate_series(
                    :from\\:\\:TIMESTAMPTZ,
                    :to\\:\\:TIMESTAMPTZ,
                    :intervalSql\\:\\:INTERVAL
            ) AS gs(ts)
            WHERE NOT EXISTS (
                    SELECT 1 FROM candle c
                    WHERE c.symbol    = :symbol
                      AND c.timeframe = :timeframe
                      AND c.timestamp = gs.ts
            )
            ORDER BY gs.ts ASC
            """, nativeQuery = true)
    List<Instant> findMissingTimestamps(
            @Param("symbol")      String  symbol,
            @Param("timeframe")   String  timeframe,
            @Param("from")        Instant from,
            @Param("to")          Instant to,
            @Param("intervalSql") String  intervalSql);
}