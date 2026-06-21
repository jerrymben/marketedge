package com.marketedge.repository;

import com.marketedge.model.TradeRecord;
import com.marketedge.strategy.SignalStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for TradeRecord entities.
 *
 * Includes dashboard analytics queries:
 *   - Recent signals per strategy / symbol
 *   - Win/loss counts for ratio calculation
 *   - Confidence-score aggregations
 *   - Open signals for SignalLifecycleTracker
 *   - Backtesting date-range queries with optional min-confidence filter
 */
@Repository
public interface TradeRecordRepository extends JpaRepository<TradeRecord, Long> {

    // ── Identity lookup ────────────────────────────────────────────────────────

    Optional<TradeRecord> findBySignalId(String signalId);

    // ── Dashboard: persistent "recent performance" fallback ───────────────────

    /**
     * Returns every trade for a symbol evaluated after {@code cutoff}, newest
     * first — across ALL strategies and timeframes. Used by
     * GET /candles/trades/recent so the dashboard can show "last triggered
     * setup" per strategy even right after a browser refresh, instead of only
     * holding it in (volatile) React state.
     *
     * Deliberately not filtered by strategyName or timeframe: the frontend
     * reduces this down to "most recent per strategyName" itself, the same
     * way it already reduces the live WebSocket signal stream.
     */
    List<TradeRecord> findBySymbolAndEvaluatedAtAfterOrderByEvaluatedAtDesc(
            String symbol, LocalDateTime cutoff);

    // ── Dashboard: recent signals ──────────────────────────────────────────────

    List<TradeRecord> findByStrategyNameOrderByEvaluatedAtDesc(
            String strategyName, Pageable pageable);

    List<TradeRecord> findBySymbolOrderByEvaluatedAtDesc(
            String symbol, Pageable pageable);

    List<TradeRecord> findByStrategyNameAndSymbolOrderByEvaluatedAtDesc(
            String strategyName, String symbol, Pageable pageable);

    // ── Dashboard: win/loss counts ─────────────────────────────────────────────

    @Query("SELECT COUNT(t) FROM TradeRecord t WHERE t.strategyName = :s AND t.signalStatus = 'TP_HIT'")
    long countWins(@Param("s") String strategyName);

    @Query("SELECT COUNT(t) FROM TradeRecord t WHERE t.strategyName = :s AND t.signalStatus = 'SL_HIT'")
    long countLosses(@Param("s") String strategyName);

    @Query("""
            SELECT COUNT(t) FROM TradeRecord t
            WHERE t.strategyName = :s
              AND t.signalStatus IN ('TP_HIT','SL_HIT','EXPIRED','INVALIDATED')
            """)
    long countClosed(@Param("s") String strategyName);

    // ── Dashboard: confidence aggregations ────────────────────────────────────

    @Query("SELECT AVG(t.confidenceScore) FROM TradeRecord t WHERE t.strategyName = :s")
    Double averageConfidence(@Param("s") String strategyName);

    @Query("SELECT AVG(t.confidenceScore) FROM TradeRecord t WHERE t.strategyName = :s AND t.signalStatus = 'TP_HIT'")
    Double averageWinnerConfidence(@Param("s") String strategyName);

    @Query("SELECT AVG(t.confidenceScore) FROM TradeRecord t WHERE t.strategyName = :s AND t.signalStatus = 'SL_HIT'")
    Double averageLoserConfidence(@Param("s") String strategyName);

    // ── Lifecycle tracker: open signals ───────────────────────────────────────

    /**
     * Returns all non-terminal records for a symbol + timeframe.
     * Called by SignalLifecycleTracker on every incoming candle.
     */
    @Query("""
            SELECT t FROM TradeRecord t
            WHERE t.signalStatus IN ('CREATED','TRIGGERED')
              AND t.symbol    = :symbol
              AND t.timeframe = :timeframe
            ORDER BY t.evaluatedAt DESC
            """)
    List<TradeRecord> findOpenSignals(
            @Param("symbol")    String symbol,
            @Param("timeframe") String timeframe);

    // ── Backtesting ────────────────────────────────────────────────────────────

    /**
     * All signals in a UTC time window for backtesting replay.
     */
    @Query("""
            SELECT t FROM TradeRecord t
            WHERE t.strategyName = :strategy
              AND t.symbol       = :symbol
              AND t.timeframe    = :timeframe
              AND t.evaluatedAt BETWEEN :from AND :to
            ORDER BY t.evaluatedAt ASC
            """)
    List<TradeRecord> findForBacktest(
            @Param("strategy")  String strategyName,
            @Param("symbol")    String symbol,
            @Param("timeframe") String timeframe,
            @Param("from")      LocalDateTime from,
            @Param("to")        LocalDateTime to);

    /**
     * Backtest filtered by minimum confidence score.
     * Useful for isolating only HIGH-confidence signals.
     */
    @Query("""
            SELECT t FROM TradeRecord t
            WHERE t.strategyName   = :strategy
              AND t.symbol         = :symbol
              AND t.timeframe      = :timeframe
              AND t.confidenceScore >= :minConf
              AND t.evaluatedAt BETWEEN :from AND :to
            ORDER BY t.evaluatedAt ASC
            """)
    List<TradeRecord> findByMinConfidence(
            @Param("strategy")  String strategyName,
            @Param("symbol")    String symbol,
            @Param("timeframe") String timeframe,
            @Param("minConf")   int    minConfidence,
            @Param("from")      LocalDateTime from,
            @Param("to")        LocalDateTime to);
}