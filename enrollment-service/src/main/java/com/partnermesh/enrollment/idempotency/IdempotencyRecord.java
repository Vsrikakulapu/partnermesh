package com.partnermesh.enrollment.idempotency;

/** The stored outcome of the first request made with an idempotency key. */
public record IdempotencyRecord(String key, String requestHash, int responseStatus, String responseBody) {
}
