package com.partnermesh.enrollment.outbox;

import java.util.UUID;

/** A claimed outbox event. Updates check leaseToken so a stale worker can't overwrite a newer owner. */
public record OutboxEvent(UUID id, UUID aggregateId, String eventType, String payload, int attempts, UUID leaseToken) {
}
