package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link IdempotencyRecordSweeper} on its own daemon thread. Deliberately not a
 * {@code @Scheduled} method: that would require the consuming application to have task
 * scheduling infrastructure enabled, which the starter shouldn't assume.
 */
public class IdempotencySweeperScheduler implements SmartLifecycle {

    private final IdempotencyRecordSweeper sweeper;
    private final Duration interval;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public IdempotencySweeperScheduler(IdempotencyRecordSweeper sweeper, Duration interval) {
        this.sweeper = sweeper;
        this.interval = interval;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotency-sweeper");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(sweeper::sweep, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
