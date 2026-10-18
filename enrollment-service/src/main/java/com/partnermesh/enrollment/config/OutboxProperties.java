package com.partnermesh.enrollment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tuning for the outbox dispatcher. */
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
