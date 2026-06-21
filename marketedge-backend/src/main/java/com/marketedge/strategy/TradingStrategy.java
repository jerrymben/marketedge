package com.marketedge.strategy;

import com.marketedge.model.Candle;
import java.util.Collections;
import java.util.List;

/**
 * Contract for all MarketEdge trading strategy implementations.
 * Discovered dynamically via Spring dependency injection.
 */
public interface TradingStrategy {

    // ─── Core Evaluation Method ───────────────────────────────────────────────

    /**
     * Evaluate the strategy against the current market state and return a signal.
     *
     * @param historicalData unmodifiable ordered list of candles, oldest → newest.
     * The last element is the most recently closed bar.
     * @param latestCandle   the newest candle just received from the market stream.
     * @return a non-null {@link StrategySignal} describing the outcome
     */
    StrategySignal evaluate(final List<Candle> historicalData, final Candle latestCandle);

    // ─── Metadata Methods with Sensible Defaults ──────────────────────────────

    /**
     * Unique human-readable identifier displayed in logs and the UI dashboard.
     */
    String getName();

    /**
     * Minimum number of historical candles required before this strategy can evaluate.
     */
    default int getMinimumBarsRequired() {
        return 200;
    }

    /**
     * Declares whether this strategy supports evaluating the given instrument symbol.
     */
    default boolean supports(String symbol) {
        return true;
    }

    /**
     * Optional description of the strategy's core logic.
     */
    default String getDescription() {
        return "No description provided.";
    }
}