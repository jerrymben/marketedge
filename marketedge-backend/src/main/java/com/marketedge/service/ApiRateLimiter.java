package com.marketedge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight in-process rate limiter for the Twelve Data free tier.
 *
 * <h3>Free-tier limits (as of 2024):</h3>
 * <ul>
 *   <li>8 API credits per minute</li>
 *   <li>800 API credits per day</li>
 *   <li>Each {@code /time_series} call costs 1 credit per symbol</li>
 * </ul>
 *
 * <h3>Strategy:</h3>
 * <p>A sliding-window counter tracks calls made in the current 60-second window.
 * Before every outbound HTTP call, {@link #tryAcquire()} is invoked:
 * <ul>
 *   <li>If the window has capacity → decrement counter, allow the call.</li>
 *   <li>If the window is full → return {@code false}; the scheduler skips
 *       this cycle and tries again on the next tick.</li>
 * </ul>
 *
 * <p>This is intentionally simple (no Guava RateLimiter dependency) and safe
 * for single-threaded {@code @Scheduled} use. All fields are {@code Atomic*}
 * for correctness should the scheduler thread pool ever be expanded.
 */
@Slf4j
@Component
public class ApiRateLimiter {

    /** Maximum calls allowed per rolling 60-second window. */
    private static final int  MAX_CALLS_PER_MINUTE = 8;

    /** Window duration in milliseconds. */
    private static final long WINDOW_MS            = 60_000L;

    /** Remaining credits in the current window. */
    private final AtomicInteger remainingCredits = new AtomicInteger(MAX_CALLS_PER_MINUTE);

    /** Epoch-ms timestamp when the current window started. */
    private final AtomicLong windowStartMs = new AtomicLong(Instant.now().toEpochMilli());

    /** Cumulative call counter for daily-cap monitoring (log only for now). */
    private final AtomicInteger totalCallsToday = new AtomicInteger(0);

    /**
     * Attempt to acquire one API credit.
     *
     * @return {@code true} if the call is allowed, {@code false} if rate-limited.
     */
    public synchronized boolean tryAcquire() {
        long now = Instant.now().toEpochMilli();

        // Roll the window if 60 seconds have elapsed
        if (now - windowStartMs.get() >= WINDOW_MS) {
            windowStartMs.set(now);
            remainingCredits.set(MAX_CALLS_PER_MINUTE);
            log.debug("[RateLimiter] Window reset. Credits restored to {}", MAX_CALLS_PER_MINUTE);
        }

        if (remainingCredits.get() <= 0) {
            long waitMs = WINDOW_MS - (now - windowStartMs.get());
            log.warn("[RateLimiter] Rate limit reached. Next window opens in ~{} ms", waitMs);
            return false;
        }

        remainingCredits.decrementAndGet();
        totalCallsToday.incrementAndGet();
        log.debug("[RateLimiter] Credit acquired. Remaining this window: {} | Total today: {}",
                remainingCredits.get(), totalCallsToday.get());
        return true;
    }

    /**
     * Current number of credits remaining in the active window.
     * Exposed for health-check / actuator endpoint use (Phase 4).
     */
    public int getRemainingCredits() {
        return remainingCredits.get();
    }

    /** Total API calls made since application startup. */
    public int getTotalCallsToday() {
        return totalCallsToday.get();
    }

    /** Reset the daily counter — call this from a midnight {@code @Scheduled} job. */
    public void resetDailyCounter() {
        log.info("[RateLimiter] Daily counter reset. Previous total: {}", totalCallsToday.get());
        totalCallsToday.set(0);
    }
}