package com.chavaillaz.jakarta.rs.mdc;

import static com.chavaillaz.jakarta.rs.mdc.MdcPropagation.wrap;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

/**
 * {@link ScheduledExecutorService} decorator extending {@link MdcPropagatingExecutorService} with the
 * scheduling methods {@link ExecutorService} does not have, wrapping their task(s) the same way.
 */
final class MdcPropagatingScheduledExecutorService extends MdcPropagatingExecutorService implements ScheduledExecutorService {

    private final ScheduledExecutorService scheduledDelegate;

    MdcPropagatingScheduledExecutorService(ScheduledExecutorService delegate) {
        super(delegate);
        this.scheduledDelegate = delegate;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return scheduledDelegate.schedule(wrap(command), delay, unit);
    }

    @Override
    public <V extends @Nullable Object> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return scheduledDelegate.schedule(wrap(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return scheduledDelegate.scheduleAtFixedRate(wrap(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return scheduledDelegate.scheduleWithFixedDelay(wrap(command), initialDelay, delay, unit);
    }

}
