package com.partnermesh.enrollment.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void sameLogicalRequestHasTheSameFingerprint() {
        assertThat(RequestFingerprint.of(" acme", "GOLD ", "Ops@Acme.example"))
                .isEqualTo(RequestFingerprint.of("acme", "GOLD", "ops@acme.example"))
                .hasSize(64);
    }

    @Test
    void fieldBoundariesCannotBeShiftedToForgeACollision() {
        assertThat(RequestFingerprint.of("ab", "c", "x@y.z")).isNotEqualTo(RequestFingerprint.of("a", "bc", "x@y.z"));
    }

    @Test
    void differentRequestsHaveDifferentFingerprints() {
        assertThat(RequestFingerprint.of("acme", "GOLD", "a@b.c")).isNotEqualTo(RequestFingerprint.of("acme", "SILVER", "a@b.c"));
    }
}
