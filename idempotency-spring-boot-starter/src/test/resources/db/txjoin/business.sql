-- Stand-in for the application's own business data, so the transaction-joining tests can assert on
-- something other than the idempotency table itself: exactly-once means the business row and the
-- completion record share a fate, and that is only observable with two tables.
CREATE TABLE IF NOT EXISTS tx_join_business (
    id   TEXT PRIMARY KEY,
    note TEXT NOT NULL
);

-- A deferred unique constraint is the only clean way to make COMMIT itself fail: the duplicate is
-- accepted by the INSERT and only rejected when the transaction tries to commit. That is the third
-- of the three rollback paths the aspect has to survive (handler throws / setRollbackOnly / the
-- commit fails), and it cannot be reached by throwing from the handler.
CREATE TABLE IF NOT EXISTS tx_join_deferred (
    id   TEXT PRIMARY KEY,
    slot TEXT NOT NULL,
    CONSTRAINT tx_join_deferred_slot_unique UNIQUE (slot) DEFERRABLE INITIALLY DEFERRED
);
