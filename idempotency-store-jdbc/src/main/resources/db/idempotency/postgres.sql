CREATE TABLE IF NOT EXISTS idempotency_record (
    id            VARCHAR(64)  PRIMARY KEY,
    state         VARCHAR(16)  NOT NULL,
    fingerprint   VARCHAR(64)  NOT NULL,
    status        INTEGER,
    payload_type  VARCHAR(512),
    payload       TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    owner         VARCHAR(36)
);
-- Added in 1.2. CREATE TABLE IF NOT EXISTS does not touch a table created by an earlier version,
-- so add the column separately; re-running this file on an up-to-date table stays harmless.
ALTER TABLE idempotency_record ADD COLUMN IF NOT EXISTS owner VARCHAR(36);
CREATE INDEX IF NOT EXISTS ix_idempotency_expires ON idempotency_record (expires_at);
