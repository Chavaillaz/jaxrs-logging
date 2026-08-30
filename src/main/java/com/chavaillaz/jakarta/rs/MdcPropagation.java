package com.chavaillaz.jakarta.rs;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.MDC;

/**
 * Propagates the calling thread's MDC context map to a task run on another thread.
 * <p>
 * MDC is backed by a thread-local, so entries set by {@link LoggedFilter} (or by application code) on the
 * thread handling a request are not visible to a task submitted to an {@link ExecutorService}, a manually
 * started {@link Thread}, or any other thread hand-off - including a {@code @Suspended AsyncResponse} or a
 * reactive resource method resuming on a different worker thread, as already documented on
 * {@link LoggedFilter#MDC_CLOSEABLES_PROPERTY}. Wrap a task (or a whole {@link ExecutorService}) with this
 * class to copy the submitting thread's MDC context map onto the thread that actually runs it, and restore
 * that thread's own previous context map once the task completes - rather than merging into it, so a
 * pooled thread never leaks one task's MDC entries into the next one it happens to run.
 */
public final class MdcPropagation {

    private MdcPropagation() {
        // Utility class
    }

    /**
     * Wraps the given task so it runs with a copy of the calling thread's current MDC context map,
     * captured at the time this method is called (not when the returned task is eventually run).
     *
     * @param task The task to wrap
     * @return A task running the given one with the calling thread's MDC context map applied
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            setContext(context);
            try {
                task.run();
            } finally {
                setContext(previous);
            }
        };
    }

    /**
     * Wraps the given task so it runs with a copy of the calling thread's current MDC context map,
     * captured at the time this method is called (not when the returned task is eventually run).
     *
     * @param task The task to wrap
     * @param <T>  The type of the result returned by the task
     * @return A task running the given one with the calling thread's MDC context map applied
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            setContext(context);
            try {
                return task.call();
            } finally {
                setContext(previous);
            }
        };
    }

    /**
     * Wraps the given executor so every task submitted to it (through any of {@link ExecutorService}'s
     * task-accepting methods) runs with a copy of the submitting thread's MDC context map, captured at
     * submission time.
     * <p>
     * Lifecycle methods ({@link ExecutorService#shutdown()}, {@link ExecutorService#awaitTermination}, ...)
     * are delegated as-is to the given executor.
     *
     * @param executor The executor to wrap
     * @return An executor propagating MDC context to every task it runs
     */
    public static ExecutorService wrap(ExecutorService executor) {
        return new MdcPropagatingExecutorService(executor);
    }

    /**
     * Wraps the given executor so every task submitted to it (through any of {@link ExecutorService}'s or
     * {@link ScheduledExecutorService}'s task-accepting methods) runs with a copy of the submitting
     * thread's MDC context map, captured at submission time.
     * <p>
     * For a periodic task ({@link ScheduledExecutorService#scheduleAtFixedRate} or
     * {@link ScheduledExecutorService#scheduleWithFixedDelay}), the context map is captured once, when the
     * task is scheduled, and that same snapshot is applied to every execution of it - consistent with how
     * every other method here captures context at wrap/submission time rather than at run time (see
     * {@link #wrap(Runnable)}). A later change to the scheduling thread's MDC context is not picked up by
     * executions that already started running before that change.
     * <p>
     * Lifecycle methods ({@link ExecutorService#shutdown()}, {@link ExecutorService#awaitTermination}, ...)
     * are delegated as-is to the given executor.
     *
     * @param executor The executor to wrap
     * @return An executor propagating MDC context to every task it runs
     */
    public static ScheduledExecutorService wrap(ScheduledExecutorService executor) {
        return new MdcPropagatingScheduledExecutorService(executor);
    }

    private static void setContext(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

    /**
     * {@link ExecutorService} decorator delegating everything to an underlying executor, except that
     * every task-accepting method wraps its task(s) with {@link MdcPropagation#wrap(Runnable)} or
     * {@link MdcPropagation#wrap(Callable)} first.
     * <p>
     * Package-private (rather than {@code private}) so {@link MdcPropagatingScheduledExecutorService} can
     * extend it and reuse this behavior for the {@link ExecutorService} methods it does not itself override.
     */
    static class MdcPropagatingExecutorService implements ExecutorService {

        protected final ExecutorService delegate;

        MdcPropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(wrap(command));
        }

        @Override
        public Future<?> submit(Runnable task) {
            return delegate.submit(wrap(task));
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return delegate.submit(wrap(task), result);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return delegate.submit(wrap(task));
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            return delegate.invokeAll(wrapAll(tasks));
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.invokeAll(wrapAll(tasks), timeout, unit);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
            return delegate.invokeAny(wrapAll(tasks));
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.invokeAny(wrapAll(tasks), timeout, unit);
        }

        private <T> List<Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
            return tasks.stream()
                    .map(MdcPropagation::wrap)
                    .collect(Collectors.toList());
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

    }

    /**
     * {@link ScheduledExecutorService} decorator extending {@link MdcPropagatingExecutorService} with the
     * scheduling methods {@link ExecutorService} does not have, wrapping their task(s) the same way.
     */
    private static final class MdcPropagatingScheduledExecutorService extends MdcPropagatingExecutorService implements ScheduledExecutorService {

        private final ScheduledExecutorService scheduledDelegate;

        private MdcPropagatingScheduledExecutorService(ScheduledExecutorService delegate) {
            super(delegate);
            this.scheduledDelegate = delegate;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return scheduledDelegate.schedule(wrap(command), delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
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

}
