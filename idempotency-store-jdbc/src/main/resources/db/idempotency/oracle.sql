CREATE TABLE IF NOT EXISTS idempotency_record (
    id            VARCHAR2(64)  PRIMARY KEY,
    state         VARCHAR2(16)  NOT NULL,
    -- Nullable, unlike the other dialects: Oracle stores '' as NULL, and an empty fingerprint is how
    -- aspect mode says "do not compare" (fingerprintArgs = false). JdbcIdempotencyStore reads NULL
    -- back as ''.
    fingerprint   VARCHAR2(64),
    status        INTEGER,
    payload_type  VARCHAR2(512),
    payload       CLOB,
    created_at    TIMESTAMP WITH LOCAL TIME ZONE NOT NULL,
    expires_at    TIMESTAMP WITH LOCAL TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_idempotency_expires ON idempotency_record (expires_at);
