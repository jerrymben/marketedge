package com.marketedge.strategy;

/**
 * Unchecked exception thrown when a {@link TradingStrategy} encounters an
 * unrecoverable error during evaluation.
 *
 * <p>Isolated per strategy by the engine to ensure engine uptime during bad ticks.
 */
public class StrategyEvaluationException extends RuntimeException {

    private final String strategyName;

    /**
     * Constructs a new evaluation exception with clean separation between strategy name and reason.
     *
     * @param strategyName the name of the strategy throwing the error
     * @param message the specific reason for the evaluation failure
     */
    public StrategyEvaluationException(String strategyName, String message) {
        // Fix: Do not append the name here. Keep the raw message clean.
        super(message);
        this.strategyName = strategyName;
    }

    /**
     * Constructs a new evaluation exception with an underlying root cause.
     *
     * @param strategyName the name of the strategy throwing the error
     * @param message the specific descriptive reason
     * @param cause the underlying exception (e.g., ArithmeticException, NullPointerException)
     */
    public StrategyEvaluationException(String strategyName, String message, Throwable cause) {
        super(message, cause);
        this.strategyName = strategyName;
    }

    /**
     * Constructs an evaluation exception wrapping a raw underlying cause.
     */
    public StrategyEvaluationException(String strategyName, Throwable cause) {
        super(cause != null ? cause.getMessage() : "Internal strategy execution failure", cause);
        this.strategyName = strategyName;
    }

    public String getStrategyName() {
        return strategyName;
    }
}