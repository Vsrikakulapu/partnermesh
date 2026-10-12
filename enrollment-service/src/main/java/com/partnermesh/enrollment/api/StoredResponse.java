package com.partnermesh.enrollment.api;

import java.util.UUID;

/** An HTTP response exactly as it was (or will be) returned for an idempotency key. */
public record StoredResponse(int status, String body, UUID enrollmentId, boolean replayed) {
}
