CREATE TABLE provisions (
    enrollment_id   UUID PRIMARY KEY,          -- the idempotency key: one provision per enrollment
    provision_id    TEXT        NOT NULL UNIQUE,
    partner_id      TEXT        NOT NULL,
    program_code    TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);
