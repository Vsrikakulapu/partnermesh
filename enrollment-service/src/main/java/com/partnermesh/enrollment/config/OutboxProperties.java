package com.partnermesh.enrollment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the outbox dispatcher.
 *
 * @param enabled         whether the background poller runs (tests can drive dispatch manually)
 * @param pollIntervalMs  delay between polls
 * @param batchSize       events claimed per poll
 * @param lease           how long a claimed event is owned before another instance may take it over
 * @param maxAttempts     delivery attempts before an event is dead-lettered and the enrollment failed
 * @param backoffBase     delay before the second attempt
 * @param backoffMax      upper bound for any single delay
 * @param backoffMultiplier growth factor per attempt
 */
@ConfigurationProperties(prefix = "partnermesh.outbox")
public record OutboxProperties(
        boolean enabled,
        long pollIntervalMs,
        int batchSize,
        Duration lease,
        int maxAttempts,
        Duration backoffBase,
        Duration backoffMax,
        double backoffMultiplier) {
}
