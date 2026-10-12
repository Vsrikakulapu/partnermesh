package com.partnermesh.enrollment.domain;

import java.time.Instant;
import java.util.UUID;

public record Enrollment(
        UUID id,
        String partnerId,
        String programCode,
        String contactEmail,
        EnrollmentStatus status,
        String provisionId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {
}
