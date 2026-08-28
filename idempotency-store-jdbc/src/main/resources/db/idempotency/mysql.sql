-- MySQL / MariaDB schema. Apply the same way as postgres.sql:
--   spring.sql.init.schema-locations=classpath:db/idempotency/mysql.sql
--
-- DATETIME(6), not TIMESTAMP: TIMESTAMP is limited to 2038 and silently converts through the
-- session time zone on both write and read, which would make expiry depend on the connecting
-- client's zone. Expiry is decided entirely server-side by comparing against CURRENT_TIMESTAMP(6),
-- so the column only has to be consistent with itself.
--
-- The (6) is not decorative. Without it MySQL truncates to whole seconds, so a sub-second TTL would
-- round to zero and two claims within the same second would compare as equal.
CREATE TABLE IF NOT EXISTS idempotency_record (
    id            VARCHAR(64)  NOT NULL PRIMARY KEY,
    state         VARCHAR(16)  NOT NULL,
    fingerprint   VARCHAR(64)  NOT NULL,
    status        INT,
    payload_type  VARCHAR(512),
    payload       LONGTEXT,
    created_at    DATETIME(6)  NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    -- Declared inline because MySQL has no CREATE INDEX IF NOT EXISTS; re-running this file must
    -- stay harmless.
    INDEX ix_idempotency_expires (expires_at)
) ENGINE = InnoDB;
