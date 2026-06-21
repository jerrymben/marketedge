package com.marketedge.strategy;

/**
 * Lifecycle states for a trading signal.
 *
 * State machine:
 *   CREATED ──► TRIGGERED ──► TP_HIT   (WIN)
 *                         └──► SL_HIT   (LOSS)
 *   CREATED ──► EXPIRED               (entry never touched within window)
 *   CREATED ──► INVALIDATED           (market structure broke before entry)
 *
 * Transitions are applied by SignalLifecycleTracker on each incoming tick.
 * Terminal states: TP_HIT, SL_HIT, EXPIRED, INVALIDATED.
 */
public enum SignalStatus {

    /** Signal emitted; waiting for price to touch entry level. */
    CREATED,

    /** Entry price was touched — trade is now considered open. */
    TRIGGERED,

    /** Take-profit reached. Outcome = WIN. Terminal. */
    TP_HIT,

    /** Stop-loss reached. Outcome = LOSS. Terminal. */
    SL_HIT,

    /** Expiry window elapsed without entry being triggered. Terminal. */
    EXPIRED,

    /** Market structure invalidated the setup before entry. Terminal. */
    INVALIDATED
}