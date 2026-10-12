package com.partnermesh.enrollment.outbox;

import java.util.UUID;

/**
 * A claimed outbox event. {@code leaseToken} identifies this particular claim: every state change is
 * conditional on it, so a worker whose lease expired (and whose event was re-claimed by another
 * instance) can no longer overwrite the newer owner's result.
 */
public record OutboxEvent(UUID id, UUID aggregateId, String eventType, String payload, int attempts, UUID leaseToken) {
}
