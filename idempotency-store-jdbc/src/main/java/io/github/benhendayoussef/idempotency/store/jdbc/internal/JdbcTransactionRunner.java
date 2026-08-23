package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import io.github.benhendayoussef.idempotency.internal.TransactionRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TransactionRunner} backed by a {@link TransactionTemplate}, so the advised handler's own
 * {@code @Transactional(REQUIRED)} joins this transaction instead of opening and closing its own
 * beforehand. That is what lets the business data and the completion record share a single commit.
 *
 * <p>Lives in the JDBC module because that is where {@code spring-tx} is already a dependency;
 * {@code idempotency-core} has none, and must not gain one.
 *
 * <p>Propagation stays {@code REQUIRED} (the template default): the aspect runs outside the
 * transactional advisor, so there is normally nothing to join and this template is what starts the
 * transaction. Isolation and timeout are left at the transaction manager's defaults - a handler's
 * own {@code @Transactional(timeout = ...)} no longer applies once it joins, so a timeout has to be
 * configured on the manager instead. See the README's joined-mode caveats.
 */
public class JdbcTransactionRunner implements TransactionRunner {

    private final TransactionTemplate template;

    public JdbcTransactionRunner(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T inTransaction(TransactionCallback<T> action) throws Throwable {
        // Spring's callback cannot throw a checked exception, but the advised method can. Wrap on
        // the way in, unwrap on the way out, so the aspect sees the handler's original throwable and
        // applies its release/complete failure policy to that rather than to a wrapper.
        try {
            return template.execute(status -> {
                try {
                    return action.run();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new CheckedCarrier(e);
                }
            });
        } catch (CheckedCarrier carrier) {
            throw carrier.getCause();
        }
    }

    /**
     * Smuggles a checked throwable through Spring's unchecked-only callback signature. Never escapes
     * this class - {@link #inTransaction} unwraps it. Rollback still happens normally, because the
     * template only cares that <em>something</em> was thrown.
     *
     * <p>Suppression and stack trace are both disabled: this frame is an implementation detail whose
     * stack is never read, and the carried throwable keeps its own.
     */
    private static final class CheckedCarrier extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CheckedCarrier(Throwable cause) {
            super(null, cause, false, false);
        }
    }
}
