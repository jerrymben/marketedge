package com.marketedge.strategy;

/**
 * Final outcome of a closed trade, stored on the TradeRecord entity.
 *
 * Set by SignalLifecycleTracker when the signal reaches a terminal SignalStatus.
 */
public enum TradeOutcome {
    /** Take-profit reached — closed in profit. */
    WIN,
    /** Stop-loss reached — closed at a loss. */
    LOSS,
    /** Closed at/near entry (spread / slippage). */
    BREAKEVEN,
    /** Trade not yet closed (CREATED or TRIGGERED). */
    PENDING
}