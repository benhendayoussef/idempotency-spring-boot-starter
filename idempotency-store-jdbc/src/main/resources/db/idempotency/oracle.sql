CREATE TABLE IF NOT EXISTS idempotency_record (
    id            VARCHAR2(64)  PRIMARY KEY,
    state         VARCHAR2(16)  NOT NULL,
    fingerprint   VARCHAR2(64)  NOT NULL,
    status        INTEGER,
    payload_type  VARCHAR2(512),
    payload       CLOB,
    created_at    TIMESTAMP WITH LOCAL TIME ZONE NOT NULL,
    expires_at    TIMESTAMP WITH LOCAL TIME ZONE NOT NULL
                                );
CREATE INDEX IF NOT EXISTS ix_idempotency_expires ON idempotency_record (expires_at);