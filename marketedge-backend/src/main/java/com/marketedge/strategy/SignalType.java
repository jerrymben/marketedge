package com.marketedge.strategy;

/**
 * Enumeration of all possible trading signal types emitted by a
 * {@link TradingStrategy} evaluation.
 *
 * <p>Used in {@link StrategySignal#getSignalType()} to represent the
 * outcome of a single strategy run against the latest market data.
 */
public enum SignalType {

    /**
     * Enter a long (buy) position.
     * Associated with entry price, stop-loss below current price, and
     * take-profit above entry.
     */
    BUY,

    /**
     * Enter a short (sell) position.
     * Associated with entry price, stop-loss above current price, and
     * take-profit below entry.
     */
    SELL,

    /**
     * No tradeable setup detected this tick.
     * Strategy conditions were evaluated but none of the entry criteria
     * were met. The engine discards this signal without further action.
     */
    NO_TRADE,

    /**
     * The strategy could not evaluate due to insufficient historical data,
     * a missing session window, or an unexpected data gap.
     * Distinct from {@code NO_TRADE} so the UI can surface a data-quality warning
     * rather than silently logging "no trade".
     */
    INSUFFICIENT_DATA
}