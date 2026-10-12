package com.partnermesh.provisioning;

import java.time.Instant;
import java.util.UUID;

public record Provision(UUID enrollmentId, String provisionId, String partnerId, String programCode, Instant createdAt) {
}
