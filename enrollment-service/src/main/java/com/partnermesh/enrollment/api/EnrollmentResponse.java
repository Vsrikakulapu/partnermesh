package com.partnermesh.enrollment.api;

import com.partnermesh.enrollment.domain.Enrollment;
import java.time.Instant;
import java.util.UUID;

public record EnrollmentResponse(
        UUID id,
        String partnerId,
        String programCode,
        String contactEmail,
        String status,
        String provisionId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static EnrollmentResponse from(Enrollment e) {
        return new EnrollmentResponse(e.id(), e.partnerId(), e.programCode(), e.contactEmail(), e.status().name(),
                e.provisionId(), e.failureReason(), e.createdAt(), e.updatedAt());
    }
}
