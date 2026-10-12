package com.partnermesh.enrollment.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(Duration.ofMillis(500), Duration.ofSeconds(60), 2.0);

    @Test
    void ceilingGrowsExponentiallyUpToTheCap() {
        assertThat(policy.ceiling(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.ceiling(2)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.ceiling(5)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.ceiling(8)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.ceiling(50)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void delayIsAlwaysBetweenHalfTheCeilingAndTheCeiling() {
        assertThat(policy.delay(3, () -> 0.0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delay(3, () -> 1.0)).isEqualTo(Duration.ofSeconds(2));
        for (int attempt = 1; attempt <= 12; attempt++) {
            Duration delay = policy.delay(attempt);
            assertThat(delay).isBetween(policy.ceiling(attempt).dividedBy(2), policy.ceiling(attempt));
        }
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1), 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1), 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.ceiling(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
