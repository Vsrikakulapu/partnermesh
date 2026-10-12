package com.partnermesh.enrollment.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with "equal jitter".
 *
 * <p>The ceiling for attempt {@code n} is {@code min(max, base * multiplier^(n-1))}; the actual delay is
 * drawn uniformly from {@code [ceiling/2, ceiling]}. Half the ceiling is guaranteed, so retries never
 * collapse into a tight loop, and the random half spreads retries from many instances so a recovering
 * downstream is not hit by a synchronized wave.
 */
public record BackoffPolicy(Duration base, Duration max, double multiplier) {

    public BackoffPolicy {
        if (base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("base must be positive");
        }
        if (max.compareTo(base) < 0) {
            throw new IllegalArgumentException("max must be >= base");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1");
        }
    }

    /** Upper bound of the delay after the given (1-based) failed attempt. */
    public Duration ceiling(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt is 1-based");
        }
        double millis = base.toMillis() * Math.pow(multiplier, attempt - 1);
        return Duration.ofMillis((long) Math.min(max.toMillis(), millis));
    }

    public Duration delay(int attempt, DoubleSupplier random) {
        long ceiling = ceiling(attempt).toMillis();
        double unit = Math.min(1.0, Math.max(0.0, random.getAsDouble()));
        return Duration.ofMillis(ceiling / 2 + Math.round((ceiling - ceiling / 2) * unit));
    }

    public Duration delay(int attempt) {
        return delay(attempt, () -> ThreadLocalRandom.current().nextDouble());
    }
}
