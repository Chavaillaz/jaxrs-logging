package com.chavaillaz.jakarta.rs.mdc;

import static com.chavaillaz.jakarta.rs.mdc.MdcPropagation.wrap;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;

/**
 * {@link ExecutorService} decorator delegating everything to an underlying executor, except that
 * every task-accepting method wraps its task(s) with {@link MdcPropagation#wrap(Runnable)} or
 * {@link MdcPropagation#wrap(Callable)} first.
 * <p>
 * Left open for {@link MdcPropagatingScheduledExecutorService}, which extends it and reuses this behavior for
 * the {@link ExecutorService} methods it does not itself override.
 */
class MdcPropagatingExecutorService implements ExecutorService {

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
    public <T extends @Nullable Object> Future<T> submit(Runnable task, T result) {
        return delegate.submit(wrap(task), result);
    }

    @Override
    public <T extends @Nullable Object> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T extends @Nullable Object> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks));
    }

    @Override
    public <T extends @Nullable Object> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks), timeout, unit);
    }

    @Override
    public <T extends @Nullable Object> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapAll(tasks));
    }

    @Override
    public <T extends @Nullable Object> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrapAll(tasks), timeout, unit);
    }

    private <T extends @Nullable Object> List<Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
        return tasks.stream()
                .map(MdcPropagation::wrap)
                .toList();
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

    /**
     * Delegated rather than left to the default {@link ExecutorService#close()}, which shuts the
     * executor down and waits for it to terminate through the methods above: an executor overriding
     * {@code close()} does so because that default does not suit it. The common
     * {@link java.util.concurrent.ForkJoinPool} is the extreme case - it cannot be shut down, so its
     * own {@code close()} does nothing, while the default waits forever for it to terminate, spinning
     * a whole core on the thread closing it.
     */
    @Override
    public void close() {
        delegate.close();
    }

}
