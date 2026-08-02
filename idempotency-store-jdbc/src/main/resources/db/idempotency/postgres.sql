CREATE TABLE IF NOT EXISTS idempotency_record (
    id            VARCHAR(64)  PRIMARY KEY,
    state         VARCHAR(16)  NOT NULL,
    fingerprint   VARCHAR(64)  NOT NULL,
    status        INTEGER,
    payload_type  VARCHAR(512),
    payload       TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_idempotency_expires ON idempotency_record (expires_at);
