CREATE TABLE enrollments (
    id              UUID PRIMARY KEY,
    partner_id      TEXT        NOT NULL,
    program_code    TEXT        NOT NULL,
    contact_email   TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'FAILED')),
    provision_id    TEXT,
    failure_reason  TEXT,
    version         INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_enrollment_partner_program UNIQUE (partner_id, program_code)
);

-- One row per client-supplied Idempotency-Key, holding the exact response that was returned.
CREATE TABLE idempotency_keys (
    idempotency_key TEXT PRIMARY KEY,
    request_hash    TEXT        NOT NULL,
    response_status INT         NOT NULL,
    response_body   TEXT        NOT NULL,
    enrollment_id   UUID REFERENCES enrollments (id),
    created_at      TIMESTAMPTZ NOT NULL
);

-- Transactional outbox: written in the same transaction as the enrollment, delivered asynchronously.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_id    UUID        NOT NULL REFERENCES enrollments (id),
    event_type      TEXT        NOT NULL,
    payload         TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT', 'DONE', 'DEAD')),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    locked_until    TIMESTAMPTZ,
    lease_token     UUID,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_outbox_due ON outbox_events (next_attempt_at) WHERE status IN ('PENDING', 'IN_FLIGHT');
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id);
