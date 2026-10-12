package com.partnermesh.enrollment.idempotency;

/** The key was already used for a request with different content. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
    }
}
